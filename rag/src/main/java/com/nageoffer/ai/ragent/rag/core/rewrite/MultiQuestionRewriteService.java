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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    /**
     * 喂给改写的助手回复条数上限
     */
    private static final int MAX_ASSISTANT_MESSAGES = 2;

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteWithSplit(userQuestion).rewrittenQuestion();
    }

    /**
     * 先进行术语归一化，再按开关选择规则拆分或结合历史的模型改写；无历史重载传入空列表。
     */
    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteWithSplit(userQuestion, List.of());
    }

    /**
     * 先进行术语归一化，再按开关选择规则拆分或结合历史的模型改写；无历史重载传入空列表。
     */
    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 选取改写历史并调用 FAST 档模型；调用或解析失败时回退为归一化问题及单个子问题。
     */
    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        List<ChatMessage> rewriteHistory = selectRewriteHistory(history);
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, rewriteHistory);

        // 快速档调用；解析失败或调用失败均用归一化问题兜底（档位内多候选已提供传输容错，不再跨档升级）
        RewriteResult fallback = new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
        RewriteResult result;
        try {
            RewriteResult parsed = parseRewriteAndSplit(llmService.chat(req, Tier.FAST));
            result = parsed != null ? parsed : fallback;
        } catch (Exception e) {
            log.warn("查询改写 LLM 调用失败，使用归一化问题兜底", e);
            result = fallback;
        }

        log.info("""
                RAG用户问题查询改写+拆分：
                原始问题：{}
                归一化后：{}
                改写结果：{}
                子问题：{}
                """, originalQuestion, normalizedQuestion, result.rewrittenQuestion(), result.subQuestions());
        return result;
    }

    /**
     * 挑出喂给改写的历史：摘要与全部历史提问原样带上，助手回答只留最近 {@link #MAX_ASSISTANT_MESSAGES} 条
     * <p>
     * 指代的落点绝大多数是用户自己提过的主体，历史提问全留才接得住跨多轮的回指；
     * 助手回答只在紧邻轮次里被借实体，留满两条之外的都是纯开销，还会把无关话题摊给改写模型招来实体串台
     * <p>
     * 只丢不排，上游「以 USER 打头、按时间升序」的保证继续成立
     */
    private List<ChatMessage> selectRewriteHistory(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return List.of();
        }
        Deque<ChatMessage> selected = new ArrayDeque<>(history.size());
        int remainingAssistantMessages = MAX_ASSISTANT_MESSAGES;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message.getRole() == ChatMessage.Role.ASSISTANT) {
                if (remainingAssistantMessages == 0 || !isUsableAssistant(message)) {
                    continue;
                }
                remainingAssistantMessages--;
            }
            selected.addFirst(message);
        }
        return new ArrayList<>(selected);
    }

    /**
     * 中断与限流落库的助手消息不占名额：前者是半截答案，后者是「排队人数过多」这类模板话
     * 拿它们消解指代只会把残缺或无关的实体带进 rewrite，让名额留给再往前那条真回答更划算
     * 状态缺失按正常对待，宁可多带一条也别把真答案误判掉
     */
    private boolean isUsableAssistant(ChatMessage message) {
        ChatMessage.MessageStatus status = message.getMessageStatus();
        return status == null || status == ChatMessage.MessageStatus.NORMAL;
    }

    /**
     * 按系统提示词、选取的历史、当前问题组装改写请求；当前问题始终放在最后一条。
     */
    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> rewriteHistory) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.addAll(rewriteHistory);
        // 末条恒为当前问题，提示词「消息结构」一节据此定位，别在这后面再追加任何消息
        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }


    /**
     * 去除 Markdown 围栏后解析 rewrite 和 sub_questions，过滤空子问题；缺少有效改写时返回 null。
     */
    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    /**
     * 按问号、句号、分号和换行切分问题，过滤空片段并补问号，无法拆分时保留原问题。
     */
    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
