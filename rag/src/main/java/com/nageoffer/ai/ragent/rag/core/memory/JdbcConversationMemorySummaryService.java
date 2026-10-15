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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.source.CitationMarkup;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcConversationMemorySummaryService implements ConversationMemorySummaryService {

    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";

    private final ConversationGroupService conversationGroupService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final AgentPromptResolver agentPromptResolver;
    private final RedissonClient redissonClient;
    private final Executor memorySummaryExecutor;

    /**
     * 仅在摘要开启且追加的是助手消息时异步检查压缩条件，失败记录日志，不阻塞当前回复。
     */
    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }
        if (message.getRole() != ChatMessage.Role.ASSISTANT) {
            return;
        }
        CompletableFuture.runAsync(() -> doCompressIfNeeded(conversationId, userId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("对话记忆摘要异步任务失败 - conversationId: {}, userId: {}",
                            conversationId, userId, ex);
                    return null;
                });
    }

    /**
     * 按会话和用户读取最新摘要，转换为可放入模型上下文的系统消息。
     */
    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);
        return toChatMessage(summary);
    }

    /**
     * 使用摘要包装模板标明历史摘要的用途，再作为系统消息放入上下文。
     */
    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }
        String wrapped = promptTemplateLoader.renderSection(
                CONTEXT_FORMAT_PATH, "summary-wrapper",
                Map.of("content", summary.getContent().trim())
        );
        return ChatMessage.system(wrapped);
    }

    /**
     * 尝试获取会话级分布式锁，检查轮数阈值和已有摘要水位，选取新的摘要覆盖范围。
     * 保留与原文窗口约一半的重叠，生成摘要后保存最后覆盖消息 ID；异常记录日志，finally 释放当前线程持有的锁。
     */
    private void doCompressIfNeeded(String conversationId, String userId) {
        long startTime = System.currentTimeMillis();
        int triggerTurns = memoryProperties.getSummaryStartTurns();
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        if (maxTurns <= 0 || triggerTurns <= 0) {
            return;
        }

        // 同一用户同一会话只允许一个摘要任务生成新水位，获取不到锁就跳过本次检查。
        String lockKey = SUMMARY_LOCK_PREFIX + buildLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock()) {
            return;
        }
        try {
            long total = conversationGroupService.countUserMessages(conversationId, userId);
            if (total < triggerTurns) {
                return;
            }

            ConversationSummaryDO latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);
            List<ConversationMessageDO> latestUserTurns = conversationGroupService.listLatestUserOnlyMessages(
                    conversationId,
                    userId,
                    maxTurns
            );
            if (latestUserTurns.isEmpty()) {
                return;
            }
            // 先确定仍保留原文的最早用户轮次，再检查已有摘要是否已覆盖到该边界。
            String historyStartId = resolveHistoryStartId(latestUserTurns);
            if (StrUtil.isBlank(historyStartId)) {
                return;
            }

            // 已有水位是增量摘要起点，旧记录缺少水位时按摘要时间回查，避免重复压缩全部历史。
            String afterId = resolveSummaryStartId(conversationId, userId, latestSummary);
            if (afterId != null && Long.parseLong(afterId) >= Long.parseLong(historyStartId)) {
                return;
            }

            // 摘要覆盖约一半原文窗口；只有这段重叠滑出窗口后才再次生成摘要
            String summaryCutoffId = resolveSummaryCutoffId(latestUserTurns);
            if (StrUtil.isBlank(summaryCutoffId)) {
                return;
            }

            List<ConversationMessageDO> toSummarize = conversationGroupService.listMessagesBetweenIds(
                    conversationId,
                    userId,
                    afterId,
                    summaryCutoffId
            );
            if (CollUtil.isEmpty(toSummarize)) {
                return;
            }

            String lastMessageId = resolveLastMessageId(toSummarize);
            if (StrUtil.isBlank(lastMessageId)) {
                return;
            }

            // 把新选中的对话与上一份摘要合并，新的覆盖水位与摘要正文一起保存。
            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
            String summary = summarizeMessages(toSummarize, existingSummary);
            if (StrUtil.isBlank(summary)) {
                return;
            }

            createSummary(conversationId, userId, summary, lastMessageId);
            log.info("摘要成功 - conversationId：{}，userId：{}，消息数：{}，耗时：{}ms",
                    conversationId, userId, toSummarize.size(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("摘要失败 - conversationId：{}，userId：{}", conversationId, userId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 将历史摘要和待压缩对话交给 FAST 档模型合并去重，提示限制摘要长度和行数。
     * 模型调用失败返回旧摘要；长度约束由提示词提出，本方法没有再次截断模型结果。
     */
    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        List<ChatMessage> histories = toHistoryMessages(messages);
        if (CollUtil.isEmpty(histories)) {
            return existingSummary;
        }

        int summaryMaxChars = memoryProperties.getSummaryMaxChars();
        List<ChatMessage> summaryMessages = new ArrayList<>();
        String summaryPrompt = agentPromptResolver.render(
                AgentPromptSlot.CONVERSATION_SUMMARY,
                Map.of("summary_max_chars", String.valueOf(summaryMaxChars))
        );
        summaryMessages.add(ChatMessage.system(summaryPrompt));

        // 旧摘要只作为合并素材，提示模型发生冲突时以本批原始对话为准。
        if (StrUtil.isNotBlank(existingSummary)) {
            summaryMessages.add(ChatMessage.assistant(
                    "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
                            + existingSummary.trim()
            ));
        }
        summaryMessages.addAll(histories);
        summaryMessages.add(ChatMessage.user(
                "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤" + summaryMaxChars + "字符；仅一行。"
        ));

        ChatRequest request = ChatRequest.builder()
                .messages(summaryMessages)
                .temperature(0.3D)
                .topP(0.9D)
                .thinking(false)
                .build();
        try {
            String result = llmService.chat(request, Tier.FAST);
            log.info("对话摘要生成 - resultChars: {}", result.length());

            return result;
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            return existingSummary;
        }
    }

    /**
     * 只保留非空的用户、助手正文，去除助手引用标记，作为摘要模型的素材。
     */
    private List<ChatMessage> toHistoryMessages(List<ConversationMessageDO> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        return messages.stream()
                .filter(item -> item != null
                        && StrUtil.isNotBlank(item.getContent())
                        && StrUtil.isNotBlank(item.getRole()))
                .map(item -> {
                    String role = item.getRole().toLowerCase();
                    if ("user".equals(role)) {
                        return ChatMessage.user(item.getContent());
                    } else if ("assistant".equals(role)) {
                        return ChatMessage.assistant(CitationMarkup.strip(item.getContent()));
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 把非空摘要记录转换为系统消息，缺少有效摘要时返回 null。
     */
    private ChatMessage toChatMessage(ConversationSummaryDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(ChatMessage.Role.SYSTEM, record.getContent());
    }

    /**
     * 优先用摘要记录中的消息水位；兼容旧记录时按摘要更新时间或创建时间查询覆盖边界。
     */
    private String resolveSummaryStartId(String conversationId, String userId, ConversationSummaryDO summary) {
        if (summary == null) {
            return null;
        }
        if (summary.getLastMessageId() != null) {
            return summary.getLastMessageId();
        }

        Date after = summary.getUpdateTime();
        if (after == null) {
            after = summary.getCreateTime();
        }
        return conversationGroupService.findMaxMessageIdAtOrBefore(conversationId, userId, after);
    }

    /**
     * 从倒序用户轮次中取得最早一条的 ID，作为当前原文窗口起点。
     */
    private String resolveHistoryStartId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        // 倒序列表的最后一个就是最早的
        ConversationMessageDO oldest = latestUserTurns.get(latestUserTurns.size() - 1);
        return oldest == null ? null : oldest.getId();
    }

    /**
     * 取用户轮次列表约中间位置的 ID，作为本轮摘要截止边界，保留与原文的重叠。
     */
    private String resolveSummaryCutoffId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        ConversationMessageDO overlapBoundary = latestUserTurns.get((latestUserTurns.size() - 1) / 2);
        return overlapBoundary == null ? null : overlapBoundary.getId();
    }

    /**
     * 从待摘要消息尾部向前寻找最后一个有效 ID，保存为新摘要的覆盖水位。
     */
    private String resolveLastMessageId(List<ConversationMessageDO> toSummarize) {
        for (int i = toSummarize.size() - 1; i >= 0; i--) {
            ConversationMessageDO item = toSummarize.get(i);
            if (item != null && item.getId() != null) {
                return item.getId();
            }
        }
        return null;
    }

    /**
     * 把摘要正文及最后覆盖消息 ID 交给消息摘要服务持久化。
     */
    private void createSummary(String conversationId,
                               String userId,
                               String content,
                               String lastMessageId) {
        ConversationSummaryBO summaryRecord = ConversationSummaryBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(content)
                .lastMessageId(lastMessageId)
                .build();
        conversationMessageService.addMessageSummary(summaryRecord);
    }

    /**
     * 由用户 ID 和会话 ID 共同构造摘要锁后缀，使不同用户、会话的摘要互不争用。
     */
    private String buildLockKey(String conversationId, String userId) {
        return userId.trim() + ":" + conversationId.trim();
    }
}
