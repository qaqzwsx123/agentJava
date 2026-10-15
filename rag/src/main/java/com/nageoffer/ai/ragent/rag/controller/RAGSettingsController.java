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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.BackendSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.SearchSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 设置控制器，负责系统引擎、后端选型、检索管线与 AI 模型配置的只读查询
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final OrchestrationProperties orchestrationProperties;
    private final RagStorageProperties ragStorageProperties;
    private final KeywordProperties keywordProperties;
    private final GraphProperties graphProperties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RagTraceProperties ragTraceProperties;
    private final SearchChannelProperties searchChannelProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    @Value("${rag.vector.type:milvus}")
    private String vectorType;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    /**
     * 获取系统引擎、后端选型、检索管线与 AI 模型等配置信息
     * 返回管理端需要展示的引擎、存储、检索、模型和上传配置概览。
     * 这是只读配置接口；模型配置经转换后输出，不直接暴露配置对象中的密钥。
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        // 读取当前装配配置并组合页面响应，展示引擎、后端、检索模型和上传限制，不在此接口修改配置。
        // 分别组装后端选择、RAG 参数、AI 模型及上传大小限制。
        SystemSettingsVO response = SystemSettingsVO.builder()
                .engine(SystemSettingsVO.EngineSettings.builder()
                        .type(orchestrationProperties.getMode().name().toLowerCase(Locale.ROOT))
                        .build())
                .backends(toBackendSettings())
                .rag(toRagSettings())
                .ai(toAISettings(aiModelProperties))
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                .build();
        return Results.success(response);
    }

    /**
     * 构造存储、向量、关键词和图谱后端的展示配置。
     * 返回的是管理端所需字段，不直接序列化完整的后端连接配置对象。
     */
    private BackendSettings toBackendSettings() {
        // 分别暴露存储、向量、关键词和图谱后端的选型及连接展示字段。
        // 各后端选择独立组装，便于前端解释当前服务使用的实现。
        return BackendSettings.builder()
                .storage(toStorageBackend(ragStorageProperties))
                .vector(BackendSettings.VectorBackend.builder()
                        .type(vectorType)
                        .build())
                .keyword(BackendSettings.KeywordBackend.builder()
                        .type(keywordProperties.getType())
                        .uris(keywordProperties.getEs().getUris())
                        .index(keywordProperties.getEs().getIndex())
                        .analyzer(keywordProperties.getEs().getAnalyzer())
                        .searchAnalyzer(keywordProperties.getEs().getSearchAnalyzer())
                        .build())
                .graph(BackendSettings.GraphBackend.builder()
                        .type(graphProperties.getType())
                        .baseUrl(graphProperties.getLightrag().getBaseUrl())
                        .queryMode(graphProperties.getLightrag().getQueryMode())
                        .embeddingModel(graphProperties.getEmbeddingModel())
                        .build())
                .build();
    }

    /**
     * 按当前 OSS 或 S3 配置构造存储后端概要。
     * 分别返回知识库桶、资源桶、端点、公开地址和区域，不返回访问凭据。
     */
    private BackendSettings.StorageBackend toStorageBackend(RagStorageProperties props) {
        // 先判断后端类型，再从相应配置分支取值，避免混用两套地址。
        boolean oss = "oss".equalsIgnoreCase(props.getType());
        return BackendSettings.StorageBackend.builder()
                .type(props.getType())
                .kbBucket(props.getKbBucket())
                .assetBucket(props.getAssetBucket())
                .endpoint(oss ? props.getOss().getEndpoint() : props.getS3().getEndpoint())
                .publicUrl(oss ? props.getOss().getPublicUrl() : props.getS3().resolvePublicUrl())
                .region(oss ? props.getOss().getRegion() : props.getS3().getRegion())
                .build();
    }

    /**
     * 汇总 RAG 默认参数、功能开关、检索预算、限流与会话记忆配置。
     * 只读取当前配置，不通过此接口修改运行参数。
     */
    private SystemSettingsVO.RagSettings toRagSettings() {
        // 将默认参数、功能开关、检索、限流和记忆配置组织成页面稳定结构。
        // 不同配置对象转换为稳定的响应结构，避免页面依赖配置类内部字段。
        return SystemSettingsVO.RagSettings.builder()
                .defaultConfig(SystemSettingsVO.DefaultSettings.builder()
                        .collectionName(ragDefaultProperties.getCollectionName())
                        .dimension(ragDefaultProperties.getDimension())
                        .metricType(ragDefaultProperties.getMetricType())
                        .sseTimeoutMs(ragDefaultProperties.getSseTimeoutMs())
                        .build())
                .features(SystemSettingsVO.FeatureSettings.builder()
                        .queryRewrite(ragConfigProperties.getQueryRewriteEnabled())
                        .rerank(ragConfigProperties.getRerankEnabled())
                        .citation(ragConfigProperties.getCitationEnabled())
                        .contextEnrich(ragConfigProperties.getContextEnrichEnabled())
                        .trace(ragTraceProperties.isEnabled())
                        .build())
                .search(toSearchSettings(searchChannelProperties))
                .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                        .global(SystemSettingsVO.GlobalRateLimit.builder()
                                .enabled(ragRateLimitProperties.getGlobalEnabled())
                                .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                .build())
                        .build())
                .memory(SystemSettingsVO.MemorySettings.builder()
                        .historyKeepTurns(memoryProperties.getHistoryKeepTurns())
                        .summaryEnabled(memoryProperties.getSummaryEnabled())
                        .summaryStartTurns(memoryProperties.getSummaryStartTurns())
                        .summaryMaxChars(memoryProperties.getSummaryMaxChars())
                        .titleMaxLength(memoryProperties.getTitleMaxLength())
                        .build())
                .build();
    }

    /**
     * 把多路召回、范围判定和融合排序配置转换为展示结构。
     * 渠道权重来自融合配置，联网搜索只返回密钥是否已配置，不返回原密钥。
     */
    private SearchSettings toSearchSettings(SearchChannelProperties props) {
        // 分别映射上下文数量、召回预算、通道权重与精排上限，联网密钥仅提供是否配置的标记。
        // 默认 Top-K、召回预算和精排候选上限分别展示，不能混为同一个数量。
        SearchChannelProperties.Channels channels = props.getChannels();
        SearchChannelProperties.ChannelWeights weights = props.getFusion().getChannelWeights();
        return SearchSettings.builder()
                .defaultTopK(props.getDefaultTopK())
                .recallBudget(props.resolveRecallBudget(props.getDefaultTopK()))
                .scope(SearchSettings.ScopeSettings.builder()
                        .minIntentScore(props.getScope().getMinIntentScore())
                        .confidenceThreshold(props.getScope().getConfidenceThreshold())
                        .supplementRatio(props.getScope().getSupplementRatio())
                        .build())
                .channels(SearchSettings.ChannelSettings.builder()
                        .timeoutMs(channels.getTimeoutMs())
                        .vector(SearchSettings.Channel.builder()
                                .enabled(channels.getVector().isEnabled())
                                .weight(weights.getVector())
                                .build())
                        .keyword(SearchSettings.Channel.builder()
                                .enabled(channels.getKeyword().isEnabled())
                                .weight(weights.getKeyword())
                                .build())
                        .graph(SearchSettings.Channel.builder()
                                .enabled(channels.getGraph().isEnabled())
                                .weight(weights.getGraph())
                                .build())
                        .webSearch(SearchSettings.WebSearchChannel.builder()
                                .enabled(channels.getWebSearch().isEnabled())
                                .weight(weights.getWebSearch())
                                .count(channels.getWebSearch().getCount())
                                .timeoutSeconds(channels.getWebSearch().getTimeoutSeconds())
                                .apiKeyConfigured(StringUtils.hasText(channels.getWebSearch().getApiKey()))
                                .build())
                        .build())
                .fusion(SearchSettings.FusionSettings.builder()
                        .strategy(props.getFusion().getStrategy())
                        .rrfK(props.getFusion().getRrfK())
                        .rerankCandidateLimit(props.getFusion().getRerankCandidateLimit())
                        .build())
                .build();
    }

    /**
     * 组装模型提供商、候选模型、分层选择和流式配置。
     * 提供商 API Key 经过 maskApiKey 处理后才进入响应。
     */
    private AISettings toAISettings(AIModelProperties props) {
        // 供应商密钥经过掩码后再输出，并兼容模型选择或流式配置缺省的情况。
        // 先构造脱敏后的提供商信息，再映射各类型模型组。
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(maskApiKey(v.getApiKey()))
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                .vlm(toModelGroup(props.getVlm()))
                .selection(props.getSelection() == null
                        ? null
                        : AISettings.Selection.builder()
                          .failureThreshold(props.getSelection().getFailureThreshold())
                          .openDurationMs(props.getSelection().getOpenDurationMs())
                          .build())
                .stream(props.getStream() == null
                        ? null
                        : AISettings.Stream.builder()
                          .messageChunkSize(props.getStream().getMessageChunkSize())
                          .build())
                .build();
    }

    /**
     * 把聊天、嵌入、重排或视觉模型组转换为管理端配置视图。
     * 模型组未配置时保留 null，候选模型与分层配置分别映射。
     */
    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group) {
        // 将候选模型、默认选择和层级信息映射为展示结构，缺少模型组时保持 null。
        // 这里只描述候选项，不在读取配置时执行模型调用或健康探测。
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .candidates(group.getCandidates() == null
                        ? null
                        : group.getCandidates().stream()
                          .map(c -> AISettings.ModelCandidate.builder()
                                    .id(c.getId())
                                    .provider(c.getProvider())
                                    .model(c.getModel())
                                    .url(c.getUrl())
                                    .dimension(c.getDimension())
                                    .priority(c.getPriority())
                                    .enabled(c.getEnabled())
                                    .supportsThinking(c.getSupportsThinking())
                                    .build())
                          .collect(Collectors.toList()))
                .defaultTier(group.getDefaultTier())
                .deepThinkingTier(group.getDeepThinkingTier())
                .tiers(toTiers(group.getTiers()))
                .build();
    }

    /**
     * 转换模型层级名称到候选集合和超时预算的映射。
     * 未配置层级时返回 null，保留未配置与空集合的语义差异。
     */
    private Map<String, AISettings.TierConfig> toTiers(Map<String, AIModelProperties.TierConfig> tiers) {
        // 按层级名映射候选模型列表和超时时间，没有层级配置时不额外构造默认展示值。
        // 层级候选顺序与原始配置保持一致，实际模型选择由运行时组件决定。
        if (tiers == null) {
            return null;
        }
        return tiers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> AISettings.TierConfig.builder()
                                .candidates(e.getValue().getCandidates())
                                .timeoutMs(e.getValue().getTimeoutMs())
                                .build()));
    }

    /**
     * 将模型提供商密钥转换为管理端可展示的掩码。
     * 空值返回 null，短密钥完全遮蔽，较长密钥仅保留前 6 位和后 4 位。
     */
    private String maskApiKey(String apiKey) {
        // 响应仅用于判断配置和区分密钥，不包含完整可用密钥。
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }
}
