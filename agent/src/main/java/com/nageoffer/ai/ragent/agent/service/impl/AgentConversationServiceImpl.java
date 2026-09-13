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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 会话管理实现
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final int RENAME_MAX_LENGTH = 128;
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CONFIRM_STATUS_PENDING = "pending";
    private static final String CONFIRM_STATUS_APPROVED = "approved";
    private static final String CONFIRM_STATUS_DENIED = "denied";
    private static final String CONFIRM_STATUS_EXPIRED = "expired";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final PgAgentStateStore agentStateStore;
    private final AgentRunGate runGate;
    /**
     * 延迟获取，避免与 ReActAgentProvider 循环依赖
     */
    private final ObjectProvider<ReActAgentProvider> agentProviderRef;

    /**
     * 创建新 Agent 会话或刷新已有会话的活跃时间。
     * 查询带用户 ID；首次创建冲突时重新读取已落库记录，复用同一会话。
     */
    @Override
    public String touchConversation(String conversationId, String userId, String question) {
        // 先按会话 ID + 用户查已有记录（带 userId 校验，防越权）
        AgentConversationDO existing = selectConversation(conversationId, userId);
        // 已存在 → 只刷新活跃时间并返回原标题（续聊路径）
        if (existing != null) {
            return touchLastTime(existing);
        }
        // 不存在 → 先清理可能的残留数据（上一轮启动失败留下的半成品），避免脏数据干扰
        purgeResidue(conversationId, userId);

        // v1 简化：截断首问作标题，不走 LLM 生成
        // 空值兜底 + trim，再按最大长度截断作为标题
        String title = StrUtil.sub(StrUtil.emptyIfNull(question).trim(), 0, TITLE_MAX_LENGTH);
        // 组装新会话记录（雪花 ID 由上游生成，这里只负责落库）
        AgentConversationDO conversation = AgentConversationDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .title(title)
                .lastTime(new Date())
                .build();
        try {
            // 插入新会话记录
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException dke) {
            // 并发首问唯一键冲突，重查已有记录
            // 说明同一会话被并发创建，自己不是 winner，改为复用对方已插入的记录
            AgentConversationDO winner = selectConversation(conversationId, userId);
            // 仍查不到说明不是并发冲突导致，属异常情况，原样抛出
            if (winner == null) {
                throw dke;
            }
            // 复用 winner 记录，刷新活跃时间并返回其标题
            return touchLastTime(winner);
        }
        // 插入成功，返回本次生成的标题
        return title;
    }

    /**
     * 同时按会话 ID 和用户 ID 查询记录，避免仅凭会话标识访问其他用户的数据。
     */
    private AgentConversationDO selectConversation(String conversationId, String userId) {
        return conversationMapper.selectOne(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getConversationId, conversationId)
                .eq(AgentConversationDO::getUserId, userId));  //执行查询，返回一条记录：查到 → 返回 AgentConversationDO。     查不到 → 返回 null。
    }

    /**
     * 刷新会话的最近活跃时间并保留原有标题，返回值供本轮 SSE 收尾展示。
     */
    private String touchLastTime(AgentConversationDO conversation) {
        // 刷新会话的最后活跃时间为当前时刻
        conversation.setLastTime(new Date());
        // 按主键 id 更新该记录（把新的 lastTime 落库）
        conversationMapper.updateById(conversation);
        return conversation.getTitle();
    }

    /**
     * 会话行不存在但同 ID 还残留状态/消息时，先清理再建新会话
     */
    private void purgeResidue(String conversationId, String userId) {
        // 清掉该会话在状态存储里的运行态（内存/Redis 中的 Agent 状态）
        agentStateStore.delete(userId, conversationId);
        // 物理删除该会话下、属于该用户的所有历史消息（防止脏数据带到新会话）
        messageMapper.delete(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId));
        // 逐出本地状态缓存，避免内存里还留着这个已废弃会话的旧状态
        evictStateCache(userId, conversationId);
    }

    /**
     * 持久化本轮用户提问并返回消息 ID。
     * 用户消息状态设为 NORMAL，消息 ID 用于关联后续助手回复。
     */
    @Override
    public String addUserMessage(String conversationId, String userId, String content) {
        // 用户身份和会话范围由调用方传入，避免异步线程再次读取登录上下文。
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_USER)
                .content(content)
                .messageStatus(AgentMessageStatus.NORMAL.name())
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    /**
     * 保存助手回复、思考内容及工具或确认卡片块。
     * replyToMessageId 关联原始提问，status 与 durationMs 描述本次回复结果。
     */
    @Override
    public String addAssistantMessage(String conversationId, String userId, String content, String thinkingContent,
                                      List<AgentBlock> blocks, String replyToMessageId, AgentMessageStatus status,
                                      Long durationMs) {
        // 思考内容为空时存为 null，工具块与正文一并保存以便恢复前端时间线。
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(ROLE_ASSISTANT)
                .content(content)
                .thinkingContent(StrUtil.blankToDefault(thinkingContent, null))
                .blocks(blocks)
                .replyToMessageId(replyToMessageId)
                .messageStatus(status.name())
                .durationMs(durationMs)
                .build();
        messageMapper.insert(message);
        return message.getId();
    }

    /**
     * 只读验证会话及待确认消息，返回续跑需要的标题和原提问关联。
     * 当前用户无此会话、消息已处理或没有 pending 卡片时拒绝续跑。
     */
    @Override
    public AgentConfirmSettlement getPendingConfirm(String conversationId, String userId, String messageId) {
        // 查询同时限定会话和用户，避免仅凭消息 ID 操作他人确认卡片。
        AgentConversationDO conversation = selectConversation(conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        PendingConfirm pending = selectPendingConfirm(conversationId, userId, messageId);
        if (pending == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), pending.message().getReplyToMessageId());
    }

    /**
     * 将待确认卡片结算为批准或拒绝状态。
     * 没有可结算的 pending 卡片时抛出异常，成功后返回原提问关联信息。
     */
    @Override
    public AgentConfirmSettlement settlePendingConfirm(String conversationId, String userId,
                                                      String messageId, boolean approved) {
        // 先验证会话归属，再按 approved 决定卡片终态。
        AgentConversationDO conversation = selectConversation(conversationId, userId);
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        AgentMessageDO message = settleConfirmBlock(conversationId, userId, messageId,
                approved ? CONFIRM_STATUS_APPROVED : CONFIRM_STATUS_DENIED);
        if (message == null) {
            throw new ClientException("待确认的操作不存在或已处理");
        }
        return new AgentConfirmSettlement(conversation.getTitle(), message.getReplyToMessageId());
    }

    /**
     * 把已经失去对应工具状态的待确认卡片标记为过期。
     * 卡片已结算时不重复改写，成功过期后解除会话的新提问阻塞。
     */
    @Override
    public void expirePendingConfirm(String conversationId, String userId, String messageId) {
        // 过期处理只调整确认消息，不补执行已经失效的工具操作。
        // 卡片标记失效，没找到说明已被结算过
        if (settleConfirmBlock(conversationId, userId, messageId, CONFIRM_STATUS_EXPIRED) != null) {
            log.warn("待确认卡片已失效，标记结算, conversationId: {}, messageId: {}", conversationId, messageId);
        }
    }

    /**
     * 把挂起的确认卡片改写成终态并落库，返回结算后的消息，没有可结算的卡片返回 null
     * 修改 pending 确认块状态，并将消息从待确认恢复为普通状态。
     * 只处理仍可结算的卡片，找不到时返回 null，避免重复结算已完成的确认。
     */
    private AgentMessageDO settleConfirmBlock(String conversationId, String userId,
                                              String messageId, String blockStatus) {
        // 卡片终态和消息状态一起更新，解除后续新提问的阻塞条件。
        PendingConfirm pending = selectPendingConfirm(conversationId, userId, messageId);
        if (pending == null) {
            return null;
        }
        pending.block().setStatus(blockStatus);
        // 卡片有了终态，消息改回 NORMAL 以解除新提问的阻塞
        AgentMessageDO message = pending.message();
        message.setMessageStatus(AgentMessageStatus.NORMAL.name());
        messageMapper.updateById(message);
        return message;
    }

    /**
     * 查出仍挂着 pending 确认卡片的消息，连同卡片块一起返回，没有则返回 null
     * 按用户、会话及消息 ID 查询仍有效的确认卡片。
     * 消息必须处于 AWAITING_CONFIRM，内部确认块也必须仍是 pending。
     */
    private PendingConfirm selectPendingConfirm(String conversationId, String userId, String messageId) {
        // 同时检查消息级与卡片级状态，不能只依靠其中一个标记。
        AgentMessageDO message = messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getId, messageId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId));
        if (message == null || !AgentMessageStatus.AWAITING_CONFIRM.name().equals(message.getMessageStatus())) {
            return null;
        }
        AgentBlock block = findPendingConfirmBlock(message);
        return block == null ? null : new PendingConfirm(message, block);
    }

    /**
     * 一次查询查出的两样东西：待确认的那条消息，和它里面那张 pending 卡片
     */
    private record PendingConfirm(AgentMessageDO message, AgentBlock block) {
    }

    /**
     * 判断当前用户的指定会话中是否仍有等待确认的回复。
     * 新提问入口用此结果阻止用户跳过尚未处理的确认卡片。
     */
    @Override
    public boolean hasPendingConfirm(String conversationId, String userId) {
        // 通过消息状态 AWAITING_CONFIRM 判断，而非检查普通回复正文。
        return messageMapper.exists(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.AWAITING_CONFIRM.name()));
    }

    /**
     * 在消息块中寻找状态为 pending 的确认块；无消息块或无匹配时返回 null。
     */
    private static AgentBlock findPendingConfirmBlock(AgentMessageDO message) {
        if (CollUtil.isEmpty(message.getBlocks())) {
            return null;
        }
        return message.getBlocks().stream()
                .filter(block -> AgentBlock.KIND_CONFIRM.equals(block.getKind()))
                .filter(block -> CONFIRM_STATUS_PENDING.equals(block.getStatus()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 返回当前用户的 Agent 会话列表及每个会话的提问轮次。
     * 会话按最后活跃时间倒序，轮次按用户消息数量批量统计。
     */
    @Override
    public List<AgentConversationVO> listByUserId(String userId) {
        // 会话基础查询带用户条件，再集中补充统计结果，避免逐会话查询。
        List<AgentConversationDO> conversations = conversationMapper.selectList(
                Wrappers.lambdaQuery(AgentConversationDO.class)
                        .eq(AgentConversationDO::getUserId, userId)
                        .orderByDesc(AgentConversationDO::getLastTime));
        Map<String, Long> turnCounts = countTurns(conversations, userId);
        return conversations.stream()
                .map(item -> AgentConversationVO.builder()
                        .conversationId(item.getConversationId())
                        .title(item.getTitle())
                        .lastTime(item.getLastTime())
                        .turns(turnCounts.getOrDefault(item.getConversationId(), 0L).intValue())
                        .build())
                .toList();
    }

    /**
     * 按会话统计用户提问数，一次 groupBy 避免 N+1
     * 对一组会话一次性统计用户提问数。
     * 只统计 user 角色消息；空会话集合直接返回空映射。
     */
    private Map<String, Long> countTurns(List<AgentConversationDO> conversations, String userId) {
        // 使用 groupBy 批量聚合，避免为每个会话单独执行 COUNT 查询。
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<String> ids = conversations.stream().map(AgentConversationDO::getConversationId).toList();
        QueryWrapper<AgentMessageDO> query = new QueryWrapper<AgentMessageDO>()
                .select("conversation_id", "COUNT(*) AS turn_count")
                .eq("user_id", userId)
                .eq("role", ROLE_USER)
                .in("conversation_id", ids)
                .groupBy("conversation_id");
        return messageMapper.selectMaps(query).stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("conversation_id")),
                        row -> ((Number) row.get("turn_count")).longValue()));
    }

    /**
     * 修改当前用户的 Agent 会话标题。
     * 标题去除首尾空格后不能为空，超过展示上限的内容会被截断。
     */
    @Override
    public void rename(String conversationId, String userId, String title) {
        // 在数据库更新前同时校验标题和会话所属用户。
        String trimmed = StrUtil.trimToEmpty(title);
        if (trimmed.isEmpty()) {
            throw new ClientException("会话标题不能为空");
        }
        AgentConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(AgentConversationDO.class)
                        .eq(AgentConversationDO::getConversationId, conversationId)
                        .eq(AgentConversationDO::getUserId, userId));
        if (conversation == null) {
            throw new ClientException("会话不存在");
        }
        conversation.setTitle(StrUtil.sub(trimmed, 0, RENAME_MAX_LENGTH));
        conversationMapper.updateById(conversation);
    }

    /**
     * 按消息 ID 顺序读取当前用户在指定 Agent 会话中的消息。
     * 响应包含正文、思考、工具块、消息状态和耗时，供前端恢复完整对话。
     */
    @Override
    public List<AgentMessageVO> listMessages(String conversationId, String userId) {
        // 数据库查询同时限定 conversationId 和 userId。
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                        .eq(AgentMessageDO::getConversationId, conversationId)
                        .eq(AgentMessageDO::getUserId, userId)
                        .orderByAsc(AgentMessageDO::getId))
                .stream()
                .map(item -> AgentMessageVO.builder()
                        .id(item.getId())
                        .role(item.getRole())
                        .content(item.getContent())
                        .thinkingContent(item.getThinkingContent())
                        .blocks(item.getBlocks())
                        .messageStatus(item.getMessageStatus())
                        .durationMs(item.getDurationMs())
                        .createTime(item.getCreateTime())
                        .build())
                .toList();
    }

    /**
     * 在事务内清理会话、消息与持久化 Agent 状态。
     * 若同用户该会话仍在运行则拒绝删除；事务提交后再驱逐本地缓存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String conversationId, String userId) {
        // 先挡住会继续回写消息的在途运行，避免删除后又出现残留数据。
        // 在途流收尾会把状态和消息写回来，必须先拦住
        if (runGate.runningTaskId(userId, conversationId) != null) {
            throw new ClientException("该会话消息正在生成中，请先停止后再删除");
        }
        conversationMapper.delete(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getConversationId, conversationId)
                .eq(AgentConversationDO::getUserId, userId));
        messageMapper.delete(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId));
        // Agent 状态同库，随事务一起删
        agentStateStore.delete(userId, conversationId);
        // 提交后再清内存缓存和停止在途流
        afterCommit(() -> evictStateCache(userId, conversationId));
    }

    /**
     * 在同一事务中删除多个 Agent 会话。
     * 空集合直接返回，重复 ID 去重；单个会话删除规则在当前事务内复用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<String> conversationIds, String userId) {
        // 这里的类内调用不另开代理事务，所有删除共享当前事务边界。
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        // 自调用不经代理，各会话的删除逻辑并入当前事务
        conversationIds.stream().distinct().forEach(id -> delete(id, userId));
    }

    /**
     * 驱逐当前节点指定会话的 Agent 内存状态。
     * 延迟获取 Provider 以避免循环依赖，组件不存在时跳过缓存清理。
     */
    private void evictStateCache(String userId, String conversationId) {
        // 延迟获取 ReActAgentProvider（可选依赖，避免循环依赖/启动失败，拿不到返回 null）
        ReActAgentProvider agentProvider = agentProviderRef.getIfAvailable();
        // 拿得到才清理，避免 NPE；拿不到就跳过（容错）
        if (agentProvider != null) {
            // 转调 provider 清掉该用户该会话的 Agent 状态缓存，防止废弃会话的旧状态被复用
            agentProvider.evictStateCache(userId, conversationId);
        }
    }

    /**
     * 事务提交后执行，无事务时立即执行
     * 将缓存驱逐等副作用延迟到数据库事务提交之后。
     * 没有事务同步环境时立即执行，有事务时注册提交回调。
     */
    private void afterCommit(Runnable action) {
        // 避免数据库最终回滚时提前清掉仍有效的本地状态。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
