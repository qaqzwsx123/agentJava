/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;

import java.util.List;
import java.util.Optional;

@Slf4j
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long thinkingStartMs;
    private int thinkingDurationSeconds;
    private List<SourceRef> sources;
    private List<GroundingChunk> groundingChunks;
    private String replyToMessageId;

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, userId, this::finishCancelledStream);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            try {
                String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
                ChatMessage message = ChatMessage.assistant(content, thinkingContent, resolveThinkingDuration());
                message.setSources(sources);
                message.setRetrievedChunks(groundingChunks);
                message.setReplyToMessageId(replyToMessageId);
                message.setMessageStatus(ChatMessage.MessageStatus.INTERRUPTED);
                messageId = memoryService.append(conversationId, userId, message);
            } catch (Exception e) {
                log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
            }
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        return new CompletionPayload(messageIdText, title, sources, ChatMessage.MessageStatus.INTERRUPTED);
    }

    /**
     * 取消收尾：持久化已累积内容后补发 cancel/done 事件并结束响应流
     */
    private void finishCancelledStream() {
        CompletionPayload payload = buildCompletionPayloadOnCancel();
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    @Override
    public void onReplyToMessageId(String messageId) {
        this.replyToMessageId = messageId;
    }

    @Override
    public void onSources(List<SourceRef> sources) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (CollUtil.isEmpty(sources)) {
            return;
        }
        // 暂存来源 随完成事件（finish）一并下发并落库
        this.sources = sources;
    }

    @Override
    public void onGroundingChunks(List<GroundingChunk> chunks) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        // 暂存 grounding 片段 随 assistant 消息一并落库 供后续推荐追问生成 grounding
        this.groundingChunks = chunks;
    }

    /**
     * 取消后忽略正文，过滤空白增量，结束思考计时并累积回答，再按配置大小发送 SSE 消息。
     */
    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            thinkingDurationSeconds = Math.max(1, Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    /**
     * 取消后忽略思考增量，首次有效思考记录起点，累积文本并通过 think 类型 SSE 消息发送。
     */
    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        sendChunked(TYPE_THINK, chunk);
    }

    /**
     * 保存正常回复、思考和来源信息后发送 finish、done，注销任务并关闭连接。
     * 持久化失败记录日志，完成事件中的消息 ID 可为空，仍结束响应流。
     */
    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        // 先尝试落库再发完成事件，保存失败允许空消息 ID，但仍注销任务并关闭流。
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent, resolveThinkingDuration());
            message.setSources(sources);
            message.setRetrievedChunks(groundingChunks);
            message.setReplyToMessageId(replyToMessageId);
            message.setMessageStatus(ChatMessage.MessageStatus.NORMAL);
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception e) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        sender.sendEvent(SSEEventType.FINISH.value(),
                new CompletionPayload(messageIdText, title, sources, ChatMessage.MessageStatus.NORMAL));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    /**
     * 非取消场景下注销任务并以错误关闭 SSE；已取消任务交由取消路径收尾。
     */
    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    /**
     * 按 Unicode 码点数量拆分 SSE 文本，避免把表情等代理对从中间截断，末尾不足一块也发送。
     */
    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            // 按码点移动索引，避免切断 UTF-16 代理对造成表情等字符损坏。
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    /**
     * 返回已记录的正数思考秒数，尚未产生有效计时时返回 null。
     */
    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    /**
     * 需要返回标题时按用户和会话查询，缺少有效标题则使用“新对话”；无需标题时返回 null。
     */
    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
