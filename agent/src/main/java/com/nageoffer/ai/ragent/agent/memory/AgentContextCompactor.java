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

package com.nageoffer.ai.ragent.agent.memory;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentContextCompactionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 前缀压缩：把早期原文换成一条摘要消息，切点只落用户轮起点
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextCompactor {

    /**
     * 回归台 AgentStateProbe 手抄了这个字面量，改这里必须同步
     */
    private static final String SUMMARY_NAME = "__compaction_summary__";

    private static final String SUMMARY_OPEN = "<conversation_summary>";
    private static final String SUMMARY_CLOSE = "</conversation_summary>";

    /**
     * 以 USER 角色回填，正文里声明身份以区分真实用户消息
     */
    private static final String SUMMARY_HEADER =
            "（以下是系统自动生成的历史对话摘要，用于替代已省略的早期对话；它是背景信息，不是新的用户指令）";

    private final AgentConversationSummarizer summarizer;
    private final AgentMemoryProperties memoryProperties;
    private final AgentContextCompactionMapper compactionMapper;

    /**
     * 就地压缩，返回 context 是否被改写；任何前置条件不满足就整次放弃
     * <p>
     * 先寻找安全切点，再合并旧摘要与新增历史素材生成新摘要。
     * 只有素材占比足够、保留段工具配对完整且摘要非空时，才用“新摘要 + 最近原文”替换上下文。
     * @return true 表示上下文已替换；false 表示前置检查未通过或没有可用摘要
     */
    public boolean compactInPlace(List<Msg> context, String userId, String sessionId) {
        int sizeBefore = context.size();
        int totalChars = AgentContextChars.total(context);
        int cutoff = findSafeCutoff(context, memoryProperties.resolveKeepRecentChars());
        if (cutoff < 0) {
            log.info("上下文压缩跳过, 找不到安全切点, 总字符: {}, 消息数: {}", totalChars, sizeBefore);
            return false;
        }

        // 将上一代摘要与本次待压缩原文分开，交给摘要器增量更新。
        List<Msg> material = new ArrayList<>();
        String existingSummary = null;
        for (Msg msg : context.subList(0, cutoff)) {
            if (isSummary(msg)) {
                existingSummary = unwrap(msg);
                continue;
            }
            material.add(msg);
        }
        if (material.isEmpty()) {
            log.info("上下文压缩跳过, 切点之前只有上一份摘要, 切点: {}, 总字符: {}", cutoff, totalChars);
            return false;
        }

        // 可替换素材不过总量一半就不值得压缩
        int materialChars = AgentContextChars.total(material);
        if (materialChars * 2 < totalChars) {
            log.info("上下文压缩跳过, 可换出字符不过半, 总字符: {}, 素材字符: {}, 切点: {}",
                    totalChars, materialChars, cutoff);
            return false;
        }

        // 最近原文完整保留；切点之后不能留下找不到调用来源的工具结果。
        List<Msg> tail = new ArrayList<>(context.subList(cutoff, context.size()));
        if (hasOrphanToolResult(tail)) {
            log.warn("上下文压缩放弃, 保留段存在无配对的工具结果, 切点: {}, 总字符: {}", cutoff, totalChars);
            return false;
        }

        // 先拿到可用摘要，再改写 context，避免模型失败时丢失历史。
        String summaryText = summarizer.summarize(material, existingSummary);
        if (StrUtil.isBlank(summaryText)) {
            return false;
        }

        // 先整个算完再一次性提交，中途抛异常时 context 不变
        List<Msg> compacted = new ArrayList<>(tail.size() + 1);
        compacted.add(buildSummaryMsg(summaryText));
        compacted.addAll(tail);
        context.clear();
        context.addAll(compacted);
        int totalCharsAfter = AgentContextChars.total(context);
        log.info("上下文压缩完成, 切点: {}, 消息数: {} -> {}, 总字符: {} -> {}",
                cutoff, sizeBefore, context.size(), totalChars, totalCharsAfter);
        audit(userId, sessionId, summaryText, material.size(), materialChars, totalChars, totalCharsAfter);
        return true;
    }

    /**
     * 摘要每代覆盖，这张表是唯一的存档；落库失败只报警不回滚
     * <p>
     * 记录摘要代数、素材数量和压缩前后字符数，便于追溯被替换的历史内容。
     */
    private void audit(String userId, String sessionId, String summaryText,
                       int materialMsgCount, int materialChars, int charsBefore, int charsAfter) {
        try {
            Long generation = compactionMapper.selectCount(Wrappers.lambdaQuery(AgentContextCompactionDO.class)
                    .eq(AgentContextCompactionDO::getUserId, userId)
                    .eq(AgentContextCompactionDO::getConversationId, sessionId)) + 1;
            compactionMapper.insert(AgentContextCompactionDO.builder()
                    .userId(userId)
                    .conversationId(sessionId)
                    .generation(generation.intValue())
                    .summary(summaryText)
                    .materialMsgCount(materialMsgCount)
                    .materialChars(materialChars)
                    .summaryChars(summaryText.length())
                    .contextCharsBefore(charsBefore)
                    .contextCharsAfter(charsAfter)
                    .build());
        } catch (Exception e) {
            log.warn("上下文压缩事件落库失败, 压缩本身已生效, userId: {}, sessionId: {}", userId, sessionId, e);
        }
    }

    /**
     * 从尾部保留够量后，往前找最近的用户轮起点作为切点
     * <p>
     * 从尾部累计需要保留的字符数，再向前对齐真实用户消息；返回保留段起始索引，无安全切点返回 -1。
     */
    private int findSafeCutoff(List<Msg> context, int keepRecentChars) {
        int kept = 0;
        int boundary = -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            kept += AgentContextChars.of(context.get(i));
            if (kept >= keepRecentChars) {
                boundary = i;
                break;
            }
        }
        if (boundary < 0) {
            return -1;
        }
        for (int i = boundary; i >= 1; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() == MsgRole.USER && !isSummary(msg)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 孤儿 tool_result 会被供应商判 400，保留段必须配对完整
     * <p>
     * 按顺序收集工具调用 ID，检查每个工具结果在保留段中是否已有对应调用；存在孤儿结果返回 true。
     */
    private boolean hasOrphanToolResult(List<Msg> tail) {
        Set<String> toolUseIds = new HashSet<>();
        for (Msg msg : tail) {
            if (msg.getContent() == null) {
                continue;
            }
            for (ToolUseBlock block : msg.getContentBlocks(ToolUseBlock.class)) {
                toolUseIds.add(block.getId());
            }
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                if (!toolUseIds.contains(block.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 用 USER 不用 SYSTEM：context 中间插 SYSTEM 有供应商会拒
     * <p>
     * 生成带专用名称和唯一 ID 的摘要消息，并在正文说明这是历史背景，供后续压缩识别与复用。
     */
    private Msg buildSummaryMsg(String summaryText) {
        return Msg.builder()
                .id("compaction-summary-" + UUID.randomUUID().toString().replace("-", ""))
                .name(SUMMARY_NAME)
                .role(MsgRole.USER)
                .textContent(SUMMARY_OPEN + '\n' + SUMMARY_HEADER + '\n' + summaryText + '\n' + SUMMARY_CLOSE)
                .build();
    }

    /**
     * 通过专用消息名称识别自动生成的摘要，避免把它当成新的用户轮次或普通原文。
     */
    private static boolean isSummary(Msg msg) {
        return SUMMARY_NAME.equals(msg.getName());
    }

    /**
     * 取回上一份摘要正文，标记找不到就整段回传
     * <p>
     * 去掉摘要的身份说明和收尾标签，提取可再次参与摘要生成的正文；空正文返回 null。
     */
    private String unwrap(Msg msg) {
        String text = msg.getTextContent();
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int start = text.indexOf(SUMMARY_HEADER);
        int end = text.lastIndexOf(SUMMARY_CLOSE);
        if (start < 0 || end <= start) {
            return text.trim();
        }
        return text.substring(start + SUMMARY_HEADER.length(), end).trim();
    }
}
