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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 按子问题执行知识库检索，并组装回答所需的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {
    // 子问题检索编排器：按共同预算并行执行各子问题的多通道召回与后处理，格式化资料并合并意图归因和证据准入集合。

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;

    /**
     * 并行构建各子问题的知识上下文，使用同一份预算配置，随后合并意图切片和可用意图 ID。
     * 单个子问题失败时保留其可用意图信息并降级为空上下文；多个子问题按模板分段组装。
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        // 所有子问题共用同一份检索预算
        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget budget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        // 子问题在线程池中并行构建上下文，收集时保持原子问题顺序。
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(intent -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(intent, budget);
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", intent.subQuestion(), e);
                                return new SubQuestionContext(
                                        intent.subQuestion(), "", Map.of(),
                                        KnowledgeRetrievalResult.empty().eligibleIntentIds(
                                                NodeScoreFilters.kb(intent.nodeScores())));
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        // 合并各子问题的归因切片及准入集合，供来源展示和最终提示词共同使用。
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new LinkedHashMap<>();
        Set<String> eligibleIntentIds = new LinkedHashSet<>();
        for (SubQuestionContext context : contexts) {
            eligibleIntentIds.addAll(context.eligibleIntentIds());
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                context.intentChunks().forEach((intentId, chunks) -> {
                    if (CollUtil.isNotEmpty(chunks)) {
                        mergedIntentChunks
                                .computeIfAbsent(intentId, ignored -> new ArrayList<>())
                                .addAll(chunks);
                    }
                });
            }
        }

        // 单子问题复用其正文；多子问题通过模板编号分段，保留证据对应的问题。
        String kbContext;
        if (contexts.size() == 1) {
            kbContext = StrUtil.emptyIfNull(contexts.get(0).kbContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                if (StrUtil.isNotBlank(context.kbContext())) {
                    appendSection(kbBuilder, ++globalIndex, context.question(), context.kbContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
        }

        return RetrievalContext.builder()
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .build();
    }

    /**
     * 提取知识库意图并执行召回、重排与格式化，保留子问题和证据归因关系。
     */
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalBudget budget) {
        // 先提取当前子问题的知识意图，再调用多通道检索装配上下文、意图分块与准入集合。
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        KbResult kbResult = retrieveAndRerank(intent, kbIntents, budget);
        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(),
                kbResult.intentChunks(), kbResult.eligibleIntentIds());
    }

    /**
     * 用子问题编号、问题和证据正文渲染上下文段落，在多问题结果之间添加换行。
     */
    private void appendSection(StringBuilder builder, int index, String question, String context) {
        // 给各子问题上下文补编号和问题标题，使用统一模板并在相邻段之间添加换行。
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "sub-question-kb-wrapper", Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    /**
     * 调用多通道检索并取得证据准入结果，将切片按意图归组并格式化为模型上下文。
     * 无切片时仍返回可用意图集合，避免把无召回误判为没有意图配置。
     */
    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalBudget budget) {
        // 取得融合与后处理后的证据，根据真实归因决定意图准入，再按上下文预算渲染分组证据。
        KnowledgeRetrievalResult retrievalResult = multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget);
        List<RetrievedChunk> chunks = retrievalResult.chunks();
        // 定向意图必须在召回证据中有归因，不能仅因分类分数高就注入其业务规则。
        Set<String> eligibleIntentIds = retrievalResult.eligibleIntentIds(kbIntents);
        if (CollUtil.isEmpty(chunks)) {
            return new KbResult("", Map.of(), eligibleIntentIds);
        }

        Map<String, List<RetrievedChunk>> intentChunks = retrievalResult.groupByIntent(MULTI_CHANNEL_KEY);
        String groupedContext = contextFormatter.formatKbContext(
                kbIntents, eligibleIntentIds, chunks, budget.contextTopK());
        return new KbResult(groupedContext, intentChunks, eligibleIntentIds);
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      Map<String, List<RetrievedChunk>> intentChunks,
                                      Set<String> eligibleIntentIds) {
        // 单个子问题上下文：绑定问题、格式化知识正文、意图片段归组和准入信息，供并行子任务完成后按原问题顺序合并。
    }
}
