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

package com.nageoffer.ai.ragent.infra.model;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * 负责根据配置和当前需求选择合适的模型候选列表
 * <p>
 * chat 组走档位机制：任务 → 档位（tier）→ 档位内有序候选；
 * embedding/rerank/vlm 组走 defaultModel + priority 的传统排序
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    private final ModelHealthStore healthStore;

    /**
     * 选择 chat 候选（默认档位）
     * <p>
     * 档位解析：深度思考走 deepThinkingTier，否则兜底 defaultTier
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking) {
        return selectChatCandidates(thinking, null, null);
    }

    /**
     * 选择 chat 候选，并按显式档位覆盖
     * <p>
     * override 语义：非空时使用该档位（想要更快/更强模型时由调用点传入），为空走默认解析
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking, Tier override) {
        return selectChatCandidates(thinking, override, null);
    }

    /**
     * 选择 chat 候选，按显式档位覆盖，并将 preferred 模型置于队首
     * <p>
     * preferred 语义：优先该模型，失败后回退到解析出的档位的其余候选
     *
     * @param override         档位覆盖，为空走默认解析（深度思考→deepThinkingTier，否则 defaultTier）
     * @param preferredModelId 优先模型 id，为空时等同于无 preferred
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking, Tier override, String preferredModelId) {
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }
        String tierName = resolveTierName(group, thinking, override);
        // 用户请求思考时，路由与 preferred 都必须过滤掉不支持思考的模型
        return buildTierTargets(group, tierName, preferredModelId, thinking);
    }

    /**
     * 从 Embedding 模型组筛选、排序并构造当前可用的调用目标。
     */
    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    /**
     * 从视觉模型组筛选、排序并构造当前可用的调用目标。
     */
    public List<ModelTarget> selectVlmCandidates() {
        return selectCandidates(properties.getVlm());
    }

    // ==================== chat：档位机制 ====================

    /**
     * 思考模式优先采用深度思考档位，其次采用显式指定档位，最后使用模型组默认档位。
     */
    private String resolveTierName(AIModelProperties.ModelGroup group, boolean thinking, Tier override) {
        if (thinking && StrUtil.isNotBlank(group.getDeepThinkingTier())) {
            return group.getDeepThinkingTier();
        }
        if (override != null) {
            return override.getKey();
        }
        return group.getDefaultTier();
    }

    /**
     * 按档位构造有序候选：preferred 置队首，随后拼接档位候选（去重），逐个过滤未启用/不健康/未登记
     * <p>
     * requireThinking 为 true 时额外剔除 supportsThinking!=true 的候选（含 preferred），
     * 避免把思考请求路由到无法思考的模型；命中的档位超时预算随每个 target 下沉
     *
     * @param requireThinking 是否要求候选支持思考链
     */
    private List<ModelTarget> buildTierTargets(AIModelProperties.ModelGroup group, String tierName,
                                                  String preferredModelId, boolean requireThinking) {
        Map<String, AIModelProperties.ModelCandidate> registry = buildRegistry(group.getCandidates());

        List<String> orderedIds = new ArrayList<>();
        if (StrUtil.isNotBlank(preferredModelId)) {
            AIModelProperties.ModelCandidate preferred = registry.get(preferredModelId);
            if (preferred == null) {
                log.warn("Chat preferred 模型未在注册表登记，忽略并回退档位候选: preferredModelId={}", preferredModelId);
            } else if (requireThinking && !supportsThinking(preferred)) {
                log.warn("Chat preferred 模型不支持思考，思考请求下忽略: preferredModelId={}", preferredModelId);
            } else {
                orderedIds.add(preferredModelId);
            }
        }

        // 优先模型排在前面，随后按档位配置追加去重候选，统一使用该档位超时预算。
        AIModelProperties.TierConfig tier = group.getTiers() == null ? null : group.getTiers().get(tierName);
        Long timeoutMs = tier == null ? null : tier.getTimeoutMs();
        if (tier == null) {
            log.warn("Chat 档位配置缺失: tier={}", tierName);
        } else {
            for (String id : tier.getCandidates()) {
                if (!orderedIds.contains(id)) {
                    orderedIds.add(id);
                }
            }
        }

        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();
        List<ModelTarget> targets = new ArrayList<>();
        for (String id : orderedIds) {
            AIModelProperties.ModelCandidate candidate = registry.get(id);
            if (candidate == null) {
                log.warn("Chat 档位候选 id 未在注册表登记: id={}, tier={}", id, tierName);
                continue;
            }
            if (Boolean.FALSE.equals(candidate.getEnabled())) {
                continue;
            }
            if (requireThinking && !supportsThinking(candidate)) {
                continue;
            }
            // 过滤停用、缺注册及不支持思考的候选后，再检查健康状态和提供商配置。
            ModelTarget target = buildModelTarget(candidate, providers, timeoutMs);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * 仅当候选明确声明 supportsThinking 为 true 时允许进入思考模式选择。
     */
    private boolean supportsThinking(AIModelProperties.ModelCandidate candidate) {
        return Boolean.TRUE.equals(candidate.getSupportsThinking());
    }

    /**
     * 以解析后的模型 ID 建立配置映射，跳过空候选，相同 ID 后出现的配置覆盖前值。
     */
    private Map<String, AIModelProperties.ModelCandidate> buildRegistry(List<AIModelProperties.ModelCandidate> candidates) {
        Map<String, AIModelProperties.ModelCandidate> registry = new LinkedHashMap<>();
        if (candidates == null) {
            return registry;
        }
        for (AIModelProperties.ModelCandidate candidate : candidates) {
            if (candidate != null) {
                registry.put(resolveId(candidate), candidate);
            }
        }
        return registry;
    }

    // ==================== embedding/rerank/vlm：defaultModel + priority ====================

    /**
     * 处理缺失模型组，按启用及默认模型规则筛选排序，再过滤不可用的调用目标。
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }
        List<AIModelProperties.ModelCandidate> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), group.getDefaultModel());
        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 过滤并排序候选模型列表：首选模型置顶，其余按 priority、id 排序
     */
    private List<AIModelProperties.ModelCandidate> filterAndSortCandidates(List<AIModelProperties.ModelCandidate> candidates,
                                                                           String firstChoiceModelId) {
        return candidates.stream()
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .sorted(Comparator
                        .comparing((AIModelProperties.ModelCandidate c) ->
                                !Objects.equals(resolveId(c), firstChoiceModelId))
                        .thenComparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    /**
     * 为非聊天档位场景构建目标，过滤不可用项；超时参数留空，使用 HTTP 客户端默认设置。
     */
    private List<ModelTarget> buildAvailableTargets(List<AIModelProperties.ModelCandidate> candidates) {
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        // embedding/rerank/vlm 无档位预算，超时走 HTTP 客户端默认
        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers, null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== 通用 ====================

    /**
     * 排除当前不可用模型并校验提供商配置，NOOP 类型允许缺省提供商，其余缺失时跳过。
     */
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate,
                                         Map<String, AIModelProperties.ProviderConfig> providers,
                                         Long timeoutMs) {
        String modelId = resolveId(candidate);

        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}", candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider, timeoutMs);
    }

    /**
     * 优先使用显式模型 ID，缺失时用 provider 与 model 拼接，供健康记录和配置索引共用。
     */
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
