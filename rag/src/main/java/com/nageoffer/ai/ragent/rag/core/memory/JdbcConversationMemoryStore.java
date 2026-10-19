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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.source.CitationMarkup;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationCreateBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {
    // 会话历史存储实现：通过会话及消息服务从数据库读取指定用户的历史消息、追加新消息并维护对应读取状态。

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        // 保存会话、消息和记忆配置依赖，历史读取与追加统一通过现有业务服务完成。
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 按消息上限读取会话记录，转换成模型消息并过滤无效角色、空正文，再规范历史起点。
     */
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        // 按配置条数加载当前用户会话的最近消息，转换并过滤有效角色，再移除窗口开头孤立的助手消息。
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        // 去掉窗口开头没有对应用户提问的孤立助手消息，再作为模型历史使用。
        return normalizeHistory(result);
    }

    /**
     * 保存消息正文、思考、来源和回复关联等信息；用户消息还会创建或刷新会话记录。
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        // 保存正文、思考、来源和回复关联等字段，只有用户消息才触发会话创建或活跃时间更新。
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .sources(message.getSources())
                .retrievedChunks(message.getRetrievedChunks())
                .replyToMessageId(message.getReplyToMessageId())
                .messageStatus(message.getMessageStatus() == null ? null : message.getMessageStatus().name())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        // 仅用户消息触发会话创建或活跃时间更新；助手消息保留其回复关联。
        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    /**
     * 把持久化记录转为模型历史消息，助手正文去除内部引用标记，保留消息状态。
     */
    private ChatMessage toChatMessage(ConversationMessageVO record) {
        // 无正文记录不进入历史，助手正文先移除历史引用标记，再还原角色与消息状态。
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        String content = role == ChatMessage.Role.ASSISTANT
                ? CitationMarkup.strip(record.getContent())
                : record.getContent();
        return new ChatMessage(
                role,
                content,
                ChatMessage.MessageStatus.valueOf(record.getMessageStatus())
        );
    }

    /**
     * 移除列表开头孤立的助手消息，保证保留的历史从用户消息开始；全部为助手消息时返回空列表。
     */
    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        // 从第一个用户消息开始保留窗口，过滤开头没有对应提问的助手回复，全是助手消息时返回空列表。
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    /**
     * 仅保留有正文的用户和助手消息，排除空对象及其他角色。
     */
    private boolean isHistoryMessage(ChatMessage message) {
        // 历史只保留非空的用户或助手消息，其他角色或空正文不会进入最近对话窗口。
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    /**
     * 按每轮两条消息，将保留历史轮数转换为最大消息数。
     */
    private int resolveMaxHistoryMessages() {
        // 每轮按用户和助手两条消息估算，将保留轮数换算成查询消息上限。
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
