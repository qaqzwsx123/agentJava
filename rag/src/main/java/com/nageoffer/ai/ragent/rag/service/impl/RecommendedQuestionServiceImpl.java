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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.core.source.CitationMarkup;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 推荐追问问题服务默认实现
 * <p>
 * null 表示未生成，空数组表示已生成但无合适追问，非空数组表示生成成功
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendedQuestionServiceImpl implements RecommendedQuestionService {
    // 推荐追问问题服务默认实现 null 表示未生成，空数组表示已生成但无合适追问，非空数组表示生成成功。

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";

    private final ConversationMessageMapper conversationMessageMapper;
    private final RecommendedQuestionGenerator generator;

    /**
     * 为用户自己的助手回答读取缓存或生成推荐追问。
     * 停用场景返回空结果；SUCCESS 和 EMPTY 都缓存，FAILED 不写入缓存。
     */
    @Override
    public RecommendedQuestionsPayload generate(String messageId, String userId) {
        // 先验证目标消息的角色与归属，避免使用他人回答生成推荐。
        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        if (isRecommendationDisabled(message)) {
            return RecommendedQuestionsPayload.empty();
        }

        List<String> cached = message.getRecommendedQuestions();
        // 空列表也是有效的已生成结果，只有 null 才表示尚未生成。
        if (cached != null) {
            return RecommendedQuestionsPayload.success(cached);
        }

        String question = loadQuestion(message);
        RecommendedQuestionsPayload generated =
                generator.generate(question, CitationMarkup.strip(message.getContent()), message.getRetrievedChunks());
        // 生成失败保留可重试空间，不把失败误缓存成没有推荐问题。
        if (generated.status() == RecommendedQuestionsPayload.Status.FAILED) {
            return generated;
        }

        // SUCCESS 与 EMPTY 都落库，空数组作为有效的负缓存
        ConversationMessageDO update = new ConversationMessageDO();
        update.setId(message.getId());
        update.setRecommendedQuestions(generated.questions());
        conversationMessageMapper.updateById(update);
        return generated;
    }

    /**
     * 定位 assistant 消息并校验归属（他人消息或非 assistant 消息一律视为不存在）
     * 读取属于指定用户的有效助手回复。
     * 消息 ID、用户、assistant 角色及有效状态同时匹配，否则报告消息不存在。
     */
    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        // 只允许读取当前用户未删除的助手消息作为推荐输入，未命中时统一提示消息不存在。
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, ROLE_ASSISTANT)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        if (message == null) {
            throw new ClientException("消息不存在");
        }
        return message;
    }

    /**
     * 通过明确的 replyToMessageId 获取当前答案对应的用户提问
     * 根据助手回复关联的提问 ID 读取原用户问题。
     * 查询同时匹配会话、用户和 user 角色，缺失关联或原问题时返回 null。
     */
    private String loadQuestion(ConversationMessageDO message) {
        // 沿回复关联 ID 查同一会话、同一用户的有效提问，没有关联或原提问已不存在则返回 null。
        if (StrUtil.isBlank(message.getReplyToMessageId())) {
            return null;
        }
        ConversationMessageDO question = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, message.getReplyToMessageId())
                        .eq(ConversationMessageDO::getConversationId, message.getConversationId())
                        .eq(ConversationMessageDO::getUserId, message.getUserId())
                        .eq(ConversationMessageDO::getRole, ROLE_USER)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        return question == null ? null : question.getContent();
    }

    /**
     * 判断该条回答是否禁止生成推荐追问。
     * 有明确消息状态且不是 NORMAL 时停用推荐，避免对失败或中断结果继续生成。
     */
    private boolean isRecommendationDisabled(ConversationMessageDO message) {
        // 明确存在且不是 NORMAL 的消息状态禁用追问，避免对取消、拒绝等异常结果继续推荐。
        return StrUtil.isNotBlank(message.getMessageStatus())
                && !ChatMessage.MessageStatus.NORMAL.name().equals(message.getMessageStatus());
    }
}
