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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    private final MessageFeedbackMapper feedbackMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final MessageQueueProducer messageQueueProducer;

    @Value("message-feedback_topic${unique-name:}")
    private String feedbackTopic;

    /**
     * 验证当前用户的赞踩参数并发布反馈事件。
     * vote 只能为 1 或 -1，接口返回时消息尚可能未消费落库。
     */
    @Override
    public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
        // 先检查用户、消息 ID 和反馈内容，再构造携带提交时间的事件。
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .vote(vote)
                .reason(request.getReason())
                .comment(request.getComment())
                .submitTime(System.currentTimeMillis())
                .build();
        // 生产端发送成功与消费者落库完成不是同一时刻，接口只确认提交结果。
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "消息反馈", event);
    }

    /**
     * 为当前用户撤销指定回答的评价，发布异步撤销事件。
     * 事件带 cancelled 标记和提交时间，实际数据库更新由消费者完成。
     */
    @Override
    public void cancelFeedbackAsync(String messageId) {
        // 校验登录用户和消息 ID，发送带取消标记与提交时间的反馈事件，归属始终取登录上下文。
        // 事件用户 ID 来自登录上下文，不接受前端指定他人的评价归属。
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .cancelled(true)
                .submitTime(System.currentTimeMillis())
                .build();
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "取消消息反馈", event);
    }

    /**
     * 同步保存当前用户对某条助手回答的评价。
     * 先校验消息角色和归属，再使用统一的反馈 upsert 逻辑。
     */
    @Override
    public void submitFeedback(String messageId, MessageFeedbackRequest request) {
        // 同步入口只接受正负一赞踩值，确认是当前用户的助手消息后再写入反馈。
        // 同步入口与异步入口共用相同的合法赞踩值约束。
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));

        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(messageId, userId, message.getConversationId(),
                vote, request.getReason(), request.getComment(), System.currentTimeMillis());
    }

    /**
     * 批量查询当前用户对一组回答的赞踩状态。
     * 没有用户或没有消息 ID 时返回空映射，避免逐消息查询反馈表。
     */
    @Override
    public Map<String, Integer> getUserVotes(String userId, List<String> messageIds) {
        // 按用户和消息集合批量查询有效反馈，同一消息重复记录保留首次值，空输入不查库。
        // 按 userId 和消息 ID 集合一次读取有效评价记录。
        if (StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        List<MessageFeedbackDO> records = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
                        .in(MessageFeedbackDO::getMessageId, messageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyMap();
        }
        return records.stream()
                .collect(Collectors.toMap(
                        MessageFeedbackDO::getMessageId,
                        MessageFeedbackDO::getVote,
                        (first, second) -> first
                ));
    }

    /**
     * 验证目标消息是当前评价用户自己的有效助手回答。
     * 消费者传入事件中的 userId；不存在或不符合角色、归属时拒绝写反馈。
     */
    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        // 查询必须同时匹配消息 ID、所属用户及未删除状态，再检查角色为助手，避免越权或评价用户消息。
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        Assert.isTrue("assistant".equalsIgnoreCase(message.getRole()), () -> new ClientException("仅支持对助手消息反馈"));
        return message;
    }

    /**
     * 新增或修改一个用户对一条回答的反馈。
     * 已有反馈按提交时间限制覆盖，避免较旧事件覆盖较新的数据库记录。
     */
    private void doUpsertFeedback(String messageId, String userId, String conversationId,
                                  Integer vote, String reason, String comment, long submitTime) {
        // 先查当前有效反馈，再选择新增 upsert 或带时间条件的更新分支。
        MessageFeedbackDO existing = feedbackMapper.selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, messageId)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
        );

        if (existing == null) {
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(messageId)
                    .conversationId(conversationId)
                    .userId(userId)
                    .vote(vote)
                    .reason(reason)
                    .comment(comment)
                    .build();
            feedbackMapper.upsertActiveFeedback(feedback);
        } else {
            // 仅当本次提交时间晚于记录最后更新时间时才覆盖，避免多节点并行消费乱序
            feedbackMapper.update(
                    MessageFeedbackDO.builder()
                            .vote(vote)
                            .reason(reason)
                            .comment(comment)
                            .build(),
                    Wrappers.lambdaUpdate(MessageFeedbackDO.class)
                            .eq(MessageFeedbackDO::getId, existing.getId())
                            .lt(MessageFeedbackDO::getUpdateTime, new Date(submitTime))
            );
        }
    }

    /**
     * 消费反馈事件，根据取消标记选择撤销或保存评价。
     * 用户身份来自事件本身，不能依赖消息消费线程上的登录上下文。
     */
    @Override
    public void submitFeedbackByEvent(MessageFeedbackEvent event) {
        // 先读取并校验事件标识，再按 cancelled 区分两条消费路径。
        String messageId = event.getMessageId();
        String userId = event.getUserId();
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notBlank(userId, () -> new ClientException("用户ID不能为空"));
        // 取消事件使用独立 upsert 路径；普通评价继续使用提交时间参与的保存逻辑。
        if (event.isCancelled()) {
            ConversationMessageDO message = loadAssistantMessage(messageId, userId);
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(messageId)
                    .conversationId(message.getConversationId())
                    .userId(userId)
                    .build();
            feedbackMapper.upsertCancelledFeedback(feedback);
            return;
        }

        Assert.notNull(event.getVote(), () -> new ClientException("反馈值不能为空"));
        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(
                messageId,
                userId,
                message.getConversationId(),
                event.getVote(),
                event.getReason(),
                event.getComment(),
                event.getSubmitTime())
        ;
    }
}
