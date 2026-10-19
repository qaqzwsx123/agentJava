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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordRetrieverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 关键词检索通道
 * <p>
 * 基于全文检索引擎（ES）的 BM25 关键词召回，与向量通道互补：擅长精确词、编号、专有名词等
 * 仅当开启 ES 关键词检索（rag.keyword.type=es）时才注册，
 * 否则整个通道不存在，引擎自动退化为纯向量检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分；检索范围读引擎解析好的作用域，与向量通道同源
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class KeywordSearchChannel implements SearchChannel {
    // 关键词召回通道：通过全文后端按共享作用域执行 BM25 检索，适合精确词和编号等查询，与其他启用通道并行后统一融合。

    private final KeywordRetrieverService keywordRetriever;
    private final SearchChannelProperties properties;
    private final Executor innerRetrievalExecutor;

    /**
     * 返回关键词召回通道名称，供通道结果和日志使用。
     */
    @Override
    public String getName() {
        // 返回关键词通道名称，供执行耗时和结果归因日志使用。
        return "KeywordSearch";
    }

    @Override
    public SearchChannelType getType() {
        // 声明 KEYWORD 类型，供融合权重选择和归因统计。
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 以关键词通道配置决定本轮是否参与检索。
        return properties.getChannels().getKeyword().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // 作用域由引擎统一解析：定向为命中库，全局为全部有效库
            RetrievalScope scope = context.getRetrievalScope();
            List<String> collections = scope.targetCollections();
            if (CollUtil.isEmpty(collections)) {
                log.info("关键词检索未解析到目标知识库，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            String question = context.getMainQuestion();
            ScopeQuota quota = ScopeQuota.split(scope, context.getBudget().recallBudget(), supplementRatio());
            // 补充路失败必须只损失自己：它拿到的是兜底名额，而 join() 抛出会让已经取回的命中库证据
            // 一起被通道级 catch 丢掉。当前 ES 实现恰好自己吞了异常，但那是实现的偶然、不是通道的保证
            CompletableFuture<List<RetrievedChunk>> supplementTask = quota.supplement() > 0
                    ? CompletableFuture.supplyAsync(
                    () -> keywordRetriever.search(question, scope.supplementCollections(), quota.supplement()),
                    innerRetrievalExecutor)
                    .exceptionally(e -> {
                        log.warn("关键词补充路检索失败，仅丢弃补充证据: {}", e.getMessage());
                        return List.of();
                    })
                    : CompletableFuture.completedFuture(List.of());

            List<RetrievedChunk> primary = keywordRetriever.search(question, collections, quota.primary());
            List<RetrievedChunk> supplement = supplementTask.join();

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词检索完成，命中 {} 库 {} 条，补充 {} 库 {} 条，耗时 {}ms",
                    collections.size(), primary.size(), scope.supplementCollections().size(), supplement.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD)
                    .channelName(getName())
                    .chunks(ChunkRanking.mergeByScore(primary, supplement))
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    private double supplementRatio() {
        // 读取定向检索之外允许补充的比例，保持与其他知识通道相同配置来源。
        return properties.getScope().getSupplementRatio();
    }
}
