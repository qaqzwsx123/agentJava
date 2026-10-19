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

package com.nageoffer.ai.ragent.rag.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 系统设置视图对象，只读汇总运行中配置
 * 分组：引擎档位 / 后端选型 / RAG 检索管线 / AI 模型，密钥一律脱敏或省略
 */
@Data
@Builder
public class SystemSettingsVO {
    // 系统设置视图对象，只读汇总运行中配置 分组：引擎档位 / 后端选型 / RAG 检索管线 / AI 模型，密钥一律脱敏或省略；组织检索关键词等返回字段，供前端查询和展示。

    private EngineSettings engine;
    private BackendSettings backends;
    private RagSettings rag;
    private AISettings ai;
    private UploadSettings upload;

    @Data
    @Builder
    public static class EngineSettings {
        // 执行架构返回配置：向前端说明当前使用 workflow 或 agent 模式，供系统设置页展示及标记相应功能。

        /**
         * 执行架构档位：workflow（编排管线）/ agent（ReAct 架构）
         */
        private String type;
    }

    @Data
    @Builder
    public static class UploadSettings {
        // 上传限制返回配置：汇总文件上传相关限制，供前端展示并对上传文件做预检查。
        private Long maxFileSize;
        private Long maxRequestSize;
    }

    /**
     * 后端组件选型，对应 yaml 里 rag.storage/vector/keyword/graph 四个 type 开关
     */
    @Data
    @Builder
    public static class BackendSettings {
        // 基础后端返回配置：分组展示对象存储、向量、关键词和图谱的当前后端选型，供系统设置页观察运行配置。
        private StorageBackend storage;
        private VectorBackend vector;
        private KeywordBackend keyword;
        private GraphBackend graph;

        @Data
        @Builder
        public static class StorageBackend {
            // 对象存储返回配置：展示当前存储类型、文档桶、资产桶及连接信息，不承担文件上传下载操作。

            /**
             * s3（rustfs / minio）/ oss（阿里云），连接信息取当前生效后端的值
             */
            private String type;
            private String kbBucket;
            private String assetBucket;
            private String endpoint;
            private String publicUrl;
            private String region;
        }

        @Data
        @Builder
        public static class VectorBackend {
            // 向量后端返回配置：展示当前使用的 PostgreSQL 或 Milvus 类型，供设置页了解向量存储选型。

            /**
             * pg / milvus
             */
            private String type;
        }

        @Data
        @Builder
        public static class KeywordBackend {
            // 关键词后端返回配置：展示后端类型及 Elasticsearch 地址、物理索引和分词参数，供设置页观察全文检索配置。

            /**
             * none / es，es 连接信息仅 type=es 时有意义
             */
            private String type;
            private String uris;
            private String index;
            private String analyzer;
            private String searchAnalyzer;
        }

        @Data
        @Builder
        public static class GraphBackend {
            // 图谱后端返回配置：展示后端类型、LightRAG 地址、查询模式及图谱服务的独立嵌入模型信息。

            /**
             * none / lightrag
             */
            private String type;
            private String baseUrl;
            private String queryMode;
            private String embeddingModel;
        }
    }

    @Data
    @Builder
    public static class RagSettings {
        // RAG 运行配置返回分组：汇总默认参数、功能开关、检索、限流和记忆配置，供设置页统一展示。

        @JsonProperty("default")
        private DefaultSettings defaultConfig;
        private FeatureSettings features;
        private SearchSettings search;
        private RateLimitSettings rateLimit;
        private MemorySettings memory;
    }

    @Data
    @Builder
    public static class DefaultSettings {
        // RAG 默认参数视图：返回默认集合、向量维度、度量方式和 SSE 超时，供管理页面了解部署约束。
        private String collectionName;
        private Integer dimension;
        private String metricType;
        private Long sseTimeoutMs;
    }

    /**
     * RAG 功能开关合集
     */
    @Data
    @Builder
    public static class FeatureSettings {
        // RAG 功能开关视图：展示问题改写、重排、引用和上下文增强等功能是否启用。
        private Boolean queryRewrite;
        private Boolean rerank;
        private Boolean citation;
        private Boolean contextEnrich;
        private Boolean trace;
    }

    /**
     * 检索漏斗配置：recallBudget → rerankCandidateLimit → defaultTopK 单调收窄
     */
    @Data
    @Builder
    public static class SearchSettings {
        // 检索漏斗配置视图：汇总召回预算、最终条数、检索范围、通道及融合参数，供前端查看检索成本和筛选过程。
        private Integer defaultTopK;
        private Integer recallBudget;
        private ScopeSettings scope;
        private ChannelSettings channels;
        private FusionSettings fusion;

        @Data
        @Builder
        public static class ScopeSettings {
            // 检索作用域参数视图：返回最低意图分、定向置信度和未命中库补充比例，说明本轮范围选择规则。
            private Double minIntentScore;
            private Double confidenceThreshold;
            private Double supplementRatio;
        }

        @Data
        @Builder
        public static class ChannelSettings {
            // 检索通道配置视图：汇总通道超时及向量、关键词、图谱、联网通道的启用状态和展示参数。
            private Long timeoutMs;
            private Channel vector;
            private Channel keyword;
            private Channel graph;
            private WebSearchChannel webSearch;
        }

        /**
         * 权重在 yaml 里配置于 fusion.channel-weights 下，展示上归属各通道
         */
        @Data
        @Builder
        public static class Channel {
            // 基础检索通道视图：返回是否启用及融合贡献权重，供设置页展示各通道状态。
            private Boolean enabled;
            private Double weight;
        }

        @Data
        @Builder
        public static class WebSearchChannel {
            // 联网检索通道视图：展示联网开关、融合权重、返回数量和超时等参数，供设置页观察联网召回配置。
            private Boolean enabled;
            private Double weight;
            private Integer count;
            private Integer timeoutSeconds;
            private Boolean apiKeyConfigured;
        }

        @Data
        @Builder
        public static class FusionSettings {
            // 融合配置视图：返回融合策略、RRF 平滑参数和重排候选上限，说明多通道结果如何合并并收敛。
            private String strategy;
            private Integer rrfK;
            private Integer rerankCandidateLimit;
        }
    }

    @Data
    @Builder
    public static class MemorySettings {
        // 会话记忆配置视图：展示历史保留轮数、摘要开关、压缩触发阈值和摘要长度，供前端了解上下文管理规则。
        private Integer historyKeepTurns;
        private Boolean summaryEnabled;
        private Integer summaryStartTurns;
        private Integer summaryMaxChars;
        private Integer titleMaxLength;
    }

    @Data
    @Builder
    public static class RateLimitSettings {
        // 聊天限流配置视图：组织全局并发限制参数，供设置页展示排队与许可策略。
        private GlobalRateLimit global;
    }

    @Data
    @Builder
    public static class GlobalRateLimit {
        // 全局并发限流视图：返回开关、最大并发、等待和许可租期等参数，供前端了解请求排队上限。
        private Boolean enabled;
        private Integer maxConcurrent;
        private Integer maxWaitSeconds;
        private Integer leaseSeconds;
        private Integer pollIntervalMs;
    }

    @Data
    @Builder
    public static class AISettings {
        // AI 模型配置视图：按供应商、聊天、嵌入、重排和视觉能力组织当前配置，供管理页面观察模型接入与选择策略。
        private Map<String, ProviderConfig> providers;
        private ModelGroup chat;
        private ModelGroup embedding;
        private ModelGroup rerank;
        private ModelGroup vlm;
        private Selection selection;
        private Stream stream;

        @Data
        @Builder
        public static class ProviderConfig {
            // 模型供应商配置视图：展示供应商地址、已脱敏或省略的密钥字段及能力端点映射，供设置页查询。
            private String url;
            private String apiKey;
            private Map<String, String> endpoints;
        }

        @Data
        @Builder
        public static class ModelGroup {
            // 模型能力组配置视图：汇总候选模型、默认模型或聊天档位，并携带选择和流式参数，供前端展示路由配置。

            /**
             * defaultModel 供 embedding/rerank/vlm 使用；chat 组走档位（tiers）字段
             */
            private String defaultModel;
            private List<ModelCandidate> candidates;
            private String defaultTier;
            private String deepThinkingTier;
            private Map<String, TierConfig> tiers;
        }

        @Data
        @Builder
        public static class TierConfig {
            // 聊天模型档位视图：返回该档位的候选模型 ID 列表及超时预算，供前端了解快速、标准等档位的调用规则。
            private List<String> candidates;
            private Long timeoutMs;
        }

        @Data
        @Builder
        public static class ModelCandidate {
            // 模型候选配置视图：返回模型 ID、供应商、实际模型名和相关优先级及连接参数，供设置页展示可选目标。
            private String id;
            private String provider;
            private String model;
            private String url;
            private Integer dimension;
            private Integer priority;
            private Boolean enabled;
            private Boolean supportsThinking;
        }

        @Data
        @Builder
        public static class Selection {
            // 模型健康选择配置视图：展示失败次数阈值和熔断开放时长，供前端了解模型临时不可用后的恢复策略。
            private Integer failureThreshold;
            private Long openDurationMs;
        }

        @Data
        @Builder
        public static class Stream {
            // 模型流式配置视图：返回消息片段大小等流式参数，供设置页观察文本推送配置。
            private Integer messageChunkSize;
        }
    }
}
