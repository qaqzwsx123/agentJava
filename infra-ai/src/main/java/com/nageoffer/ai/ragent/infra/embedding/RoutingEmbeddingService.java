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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式向量嵌入服务实现类
 * <p>
 * 该服务通过模型路由器选择合适的嵌入模型，并在执行失败时自动进行降级处理
 * 支持单文本和批量文本的向量化操作
 */
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {
    // 选择向量模型和供应商客户端，再执行单条或批量向量化；明确指定模型 ID 时只尝试该模型，避免隐式切换导致向量空间不兼容。

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients) {
        // 保存公共选择器与执行器，并将所有向量客户端按 provider 建表，后续每次调用直接查表分派。
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }

    @Override
    public List<Float> embed(String text) {
        // 未指定模型时按默认模型与优先级选候选，普通失败允许执行器切换到下一可用客户端。
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
        );
    }

    @Override
    public List<Float> embed(String text, String modelId) {
        // 指定模型时只给执行器一个精确目标，不隐式换模型，以免查询向量落入不兼容的向量空间。
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
        );
    }

    /**
     * 按模型选择结果批量生成向量；指定 modelId 的重载仅尝试该模型，避免隐式切换导致向量空间不兼容。
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        // 按当前可用模型列表执行批量向量化，具体供应商的拆批与合并由客户端模板完成。
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    /**
     * 按模型选择结果批量生成向量；指定 modelId 的重载仅尝试该模型，避免隐式切换导致向量空间不兼容。
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        // 显式模型 ID 先解析为唯一目标，整批失败也不会改用另一种向量模型。
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    /**
     * 按提供商标识取得对应的 Embedding 客户端，客户端缺失时由外层执行器跳过。
     */
    private EmbeddingClient resolveClient(ModelTarget target) {
        // 只按候选 provider 查适配器，客户端缺失时返回 null，由执行器记录并跳过。
        return clientsByProvider.get(target.candidate().getProvider());
    }

    /**
     * 校验 Embedding 模型 ID，并从当前可用候选中精确查找，找不到时返回远程调用异常。
     */
    private ModelTarget resolveTarget(String modelId) {
        // 先拒绝空模型 ID，再从当前可用候选中精确匹配；未登记、停用或不可用均不能绕过筛选。
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Embedding 模型ID不能为空");
        }
        return selector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
    }
}
