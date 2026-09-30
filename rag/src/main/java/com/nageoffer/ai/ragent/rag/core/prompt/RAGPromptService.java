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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.ANSWER_CITATION_RULES_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

/**
 * RAG Prompt 编排服务
 * <p>
 * 根据知识库检索结果选择模板，并构造发送给 LLM 的消息序列
 */
@Service
@RequiredArgsConstructor
public class RAGPromptService {
    // RAG Prompt 编排服务 根据知识库检索结果选择模板，并构造发送给 LLM 的消息序列。

    private final PromptTemplateLoader templateLoader;
    private final AgentPromptResolver agentPromptResolver;
    private final RAGConfigProperties ragConfigProperties;

    /**
     * 生成系统提示词，并对模板格式做清理
     */
    public String buildSystemPrompt(PromptContext context) {
        // 要求已有知识上下文，优先采用唯一合格意图的自定义模板，否则使用默认知识回答槽位，并按开关追加引用规则。
        return buildSystemPrompt(context, true);
    }

    /**
     * citationEligible=false 时无条件跳过引用规则拼接（Agent 模式检索门面无来源编号，不读引用开关）
     */
    private String buildSystemPrompt(PromptContext context, boolean citationEligible) {
        // 要求已有知识上下文，优先采用唯一合格意图的自定义模板，否则使用默认知识回答槽位，并按开关追加引用规则。
        if (!context.hasKb()) {
            throw new IllegalStateException("PromptContext requires KB context");
        }
        String configured = configuredTemplate(context.getKbIntents(), context.getEligibleIntentIds());
        String template = StrUtil.isNotBlank(configured)
                ? configured
                : agentPromptResolver.resolve(AgentPromptSlot.KB_ANSWER);
        String systemPrompt = StrUtil.isBlank(template) ? "" : PromptTemplateUtils.cleanupPrompt(template);
        if (!citationEligible || !Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())) {
            return systemPrompt;
        }

        String citationRules = PromptTemplateUtils.cleanupPrompt(
                templateLoader.load(ANSWER_CITATION_RULES_PROMPT_PATH));
        if (StrUtil.isBlank(systemPrompt)) {
            return citationRules;
        }
        if (StrUtil.isBlank(citationRules)) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + citationRules;
    }

    /**
     * 构造发送给 LLM 的完整消息列表（system + evidence + history + user）
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        // 兼容未显式指定引用资格的调用，默认允许引用后转交完整消息装配方法。
        return buildStructuredMessages(context, history, question, subQuestions, true);
    }

    /**
     * citationEligible 透传给系统提示词分支，false 表示调用方不具备角标渲染能力
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions,
                                                     boolean citationEligible) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词
        String systemPrompt = buildSystemPrompt(context, citationEligible);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 2. 对话历史（含摘要，摘要作为 history[0] 的 system message 自然紧跟系统提示词）
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        // 3. 证据 + 问题（合并为一条 user message）
        String evidenceBody = buildEvidenceBody(context);
        String userQuestion = buildUserQuestion(question, subQuestions);
        String userContent = mergeEvidenceAndQuestion(evidenceBody, userQuestion);
        if (StrUtil.isNotBlank(userContent)) {
            messages.add(ChatMessage.user(userContent));
        }

        return messages;
    }

    /**
     * 仅在证据准入后恰有一个不同意图时使用该节点的自定义提示词，多意图或无意图返回 null。
     */
    private String configuredTemplate(List<NodeScore> intents, Set<String> eligibleIntentIds) {
        // 先按证据准入 ID 过滤并去重意图，只有恰好一个有效意图时才使用其专属模板。
        List<NodeScore> safeIntents = intents == null ? List.of() : intents;
        Map<String, NodeScore> eligibleById = new LinkedHashMap<>();
        for (NodeScore intent : safeIntents) {
            if (intent == null || intent.getNode() == null) {
                continue;
            }
            String intentId = intent.getNode().getId();
            if (!eligibleIntentIds.contains(intentId)) {
                continue;
            }
            eligibleById.putIfAbsent(intentId, intent);
        }
        if (eligibleById.size() != 1) {
            return null;
        }
        IntentNode only = eligibleById.values().iterator().next().getNode();
        return StrUtil.trimToNull(only.getPromptTemplate());
    }

    /**
     * 多个子问题按编号渲染，单问题使用单问题模板，空问题返回空字符串。
     */
    private String buildUserQuestion(String question, List<String> subQuestions) {
        // 多个子问题使用编号模板，单问题使用普通模板，空问题返回空文本。
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            String numbered = IntStream.range(0, subQuestions.size())
                    .mapToObj(i -> (i + 1) + ". " + subQuestions.get(i))
                    .collect(Collectors.joining("\n"));
            return renderSection("multi-questions", Map.of("questions", numbered));
        }
        if (StrUtil.isBlank(question)) {
            return "";
        }
        return renderSection("single-question", Map.of("question", question));
    }

    /**
     * 把非空证据和问题用空行连接，任一部分为空时直接使用另一部分。
     */
    private String mergeEvidenceAndQuestion(String evidenceBody, String question) {
        // 证据和问题均存在时用段落分隔连接，任一为空则直接返回另一个。
        if (StrUtil.isBlank(evidenceBody)) {
            return question;
        }
        if (StrUtil.isBlank(question)) {
            return evidenceBody;
        }
        return evidenceBody + "\n\n" + question;
    }

    /**
     * 将知识库证据渲染为一个文本块
     */
    private String buildEvidenceBody(PromptContext context) {
        // 只有有效知识上下文才渲染证据区段，并清理首尾空白。
        return StrUtil.isBlank(context.getKbContext())
                ? ""
                : renderSection("kb-evidence", Map.of("body", context.getKbContext().trim())).trim();
    }

    /**
     * 调用统一模板加载器渲染上下文配置中的指定区段。
     */
    private String renderSection(String section, Map<String, String> slots) {
        // 统一通过上下文资源模板渲染指定区段，避免各调用点重复定义格式。
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, slots);
    }
}
