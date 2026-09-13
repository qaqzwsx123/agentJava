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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.ttl.TtlRunnable;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.service.ratelimit.FairDistributedRateLimiter.AcquireRequest;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SSE 全局并发限流入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";

    private final FairDistributedRateLimiter chatRateLimiter;
    private final Executor chatEntryExecutor;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;

    /**
     * 开启全局限流时登记带超时和取消回调的排队请求；关闭时直接投递执行线程池。
     * 获准后的业务执行使用指定线程池，SSE 完成、超时或错误会取消等待；线程池拒绝也进入拒绝响应。
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            try {
                chatEntryExecutor.execute(onAcquire);
            } catch (RejectedExecutionException ex) {
                log.warn("直通分支线程池拒绝任务，转 reject 流程", ex);
                handleReject(question, conversationId, emitter);
            }
            return;
        }

        chatRateLimiter.acquire(AcquireRequest.builder()
                .maxWaitMillis(TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds()))
                .onAcquired(TtlRunnable.get(onAcquire))
                .onTimeout(TtlRunnable.get(() -> handleReject(question, conversationId, emitter)))
                .onAcquiredExecutor(chatEntryExecutor)
                .cancelBinder(cancel -> {
                    emitter.onCompletion(cancel);
                    emitter.onTimeout(cancel);
                    emitter.onError(e -> cancel.run());
                })
                .build());
    }

    // ==================== Reject 业务 ====================

    /**
     * 尝试保存被拒绝的问答记录，再发送拒绝事件；保存失败仍结束 SSE，避免前端一直等待。
     */
    private void handleReject(String question, String conversationId, SseEmitter emitter) {
        RejectedContext context = null;
        try {
            context = recordRejectedConversation(question, conversationId, resolveUserId());
        } catch (Exception ex) {
            // 记录失败不能阻塞 emitter，否则前端永远收不到 DONE
            log.warn("记录 reject 会话失败，仍向前端发送 DONE", ex);
        }
        sendRejectEvents(emitter, context);
    }

    /**
     * 为有效用户和问题创建或复用会话，保存用户消息与 REJECTED 回复，并准备拒绝响应所需的标识。
     * 新会话优先回读生成的标题，缺失时按问题截取兜底标题。
     */
    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question) || StrUtil.isBlank(userId)) {
            return null;
        }

        String actualConversationId;
        boolean isNewConversation;
        if (StrUtil.isBlank(conversationId)) {
            // 入参未带 conversationId：刚生成的雪花 ID 不可能命中已有会话，跳过 existence 查询
            actualConversationId = IdUtil.getSnowflakeNextIdStr();
            isNewConversation = true;
        } else {
            actualConversationId = conversationId;
            isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;
        }

        String questionMessageId = memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        ChatMessage rejectedMessage = ChatMessage.assistant(REJECT_MESSAGE);
        rejectedMessage.setReplyToMessageId(questionMessageId);
        rejectedMessage.setMessageStatus(ChatMessage.MessageStatus.REJECTED);
        String messageId = memoryService.append(actualConversationId, userId, rejectedMessage);

        String title = Strings.EMPTY;
        if (isNewConversation) {
            // append(USER) 内部会触发 conversationService.createOrUpdate（含 LLM 生成标题），此处回查拿到生成结果
            var conversation = conversationGroupService.findConversation(actualConversationId, userId);
            title = conversation != null ? conversation.getTitle() : Strings.EMPTY;
            if (StrUtil.isBlank(title)) {
                title = buildFallbackTitle(question);
            }
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    /**
     * 从问题截取配置长度作为拒绝会话的备用标题，空问题返回空字符串。
     */
    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return Strings.EMPTY;
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    /**
     * 存在拒绝记录时依次发送 meta、reject、finish，最后始终发送 done 并关闭 SSE。
     */
    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            sender.sendEvent(SSEEventType.FINISH.value(),
                    new CompletionPayload(String.valueOf(rejectedContext.messageId), rejectedContext.title,
                            null, ChatMessage.MessageStatus.REJECTED));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    /**
     * 优先读取传递到当前线程的用户上下文，缺失时尝试登录框架，均不可用时返回 null。
     */
    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }
}
