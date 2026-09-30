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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {
    // 会话消息管理实现：校验会话归属，保存、读取和删除消息及摘要，装配引用来源、推荐问题和用户反馈等返回信息。

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackService feedbackService;

    /**
     * 将编排链路产生的消息业务对象转换为数据库记录。
     * 返回落库后的消息 ID，供回复关联、反馈和推荐追问使用。
     */
    @Override
    public String addMessage(ConversationMessageBO conversationMessage) {
        // 业务对象与持久化对象分开，保存时使用当前传入的完整消息信息。
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
        return messageDO.getId();
    }

    /**
     * 按用户和会话读取历史消息，并补充当前用户的评价状态。
     * 倒序加限量用于取最近消息，取出后反转为前端按时间阅读的顺序。
     */
    @Override
    public List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order) {
        // 先验证会话归属，再执行消息查询，空范围直接返回空列表。
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            return List.of();
        }

        boolean asc = order == null || order == ConversationMessageOrder.ASC;
        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderBy(true, asc, ConversationMessageDO::getCreateTime)
                        .orderBy(true, asc, ConversationMessageDO::getId)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        // 倒序只用于挑出最近的记录；返回前反转，使前端始终按会话先后阅读。
        if (!asc) {
            Collections.reverse(records);
        }

        List<String> assistantMessageIds = records.stream()
                .filter(record -> "assistant".equalsIgnoreCase(record.getRole()))
                .map(ConversationMessageDO::getId)
                .toList();
        // 只为本批助手回复批量查询当前用户评价，避免每条消息各查一次反馈表。
        Map<String, Integer> votesByMessageId = feedbackService.getUserVotes(userId, assistantMessageIds);

        List<ConversationMessageVO> result = new ArrayList<>();
        for (ConversationMessageDO record : records) {
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .thinkingContent(record.getThinkingContent())
                    .thinkingDuration(record.getThinkingDuration())
                    .vote(votesByMessageId.get(record.getId()))
                    .sources(record.getSources())
                    .recommendedQuestions(record.getRecommendedQuestions())
                    .messageStatus(record.getMessageStatus())
                    .createTime(record.getCreateTime())
                    .build();
            result.add(vo);
        }

        return result;
    }

    /**
     * 保存一次会话历史压缩产生的摘要。
     * 摘要与普通消息分别存储，供后续会话记忆加载使用。
     */
    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        // 只负责持久化传入摘要，不在此方法调用模型生成摘要。
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class);
        conversationSummaryMapper.insert(conversationSummaryDO);
    }
}
