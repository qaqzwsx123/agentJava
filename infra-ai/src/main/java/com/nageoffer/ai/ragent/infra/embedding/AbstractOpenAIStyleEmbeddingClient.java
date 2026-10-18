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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议 EmbeddingClient 抽象基类
 * 封装 /v1/embeddings 协议的通用逻辑，子类只需提供 provider 和覆写钩子方法
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {
    // 公共向量请求模板。组装文本、模型、维度和鉴权，发送 HTTP 并解析浮点向量，同时按供应商设置拆分和合并批次。

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        // 复用注入的同步 HTTP 客户端，供应商子类只覆写批量上限和请求差异，不另建连接池。
        this.httpClient = httpClient;
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 是否要求提供商配置 API Key，默认 true
     */
    protected boolean requiresApiKey() {
        // 默认要求密钥并添加 Bearer 头，本地无鉴权实现通过覆写关闭。
        return true;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：添加 encoding_format=float
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // 默认要求返回数值浮点向量，子类可改写或省略不支持的 encoding_format 字段。
        body.addProperty("encoding_format", "float");
    }

    /**
     * 单次请求最大批量大小，0 表示不限制
     */
    protected int maxBatchSize() {
        // 默认 0 表示本客户端不额外拆批，具体供应商适配器可返回正数限制单次输入条数。
        return 0;
    }

    // ==================== 接口实现 ====================

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        // 单条文本复用批量协议发送，随后取返回列表首项，不再维护另一套请求或响应格式。
        List<List<Float>> result = doEmbed(List.of(text), target);
        return result.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        // 1. 空输入直接返回空列表；未设置上限或条数未超限时一次请求完成，不做额外分片。
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }
        int batch = maxBatchSize();
        if (batch <= 0 || texts.size() <= batch) {
            return doEmbed(texts, target);
        }

        // 2. 预留与输入等长的结果位置，按批次起始偏移写回，保持各批之间的输入顺序。
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += batch) {
            int end = Math.min(i + batch, n);
            List<String> slice = texts.subList(i, end);
            // 3. 当前批次同步完成后再处理下一批，批内向量沿用供应商返回顺序，不按响应 index 重新排序。
            List<List<Float>> part = doEmbed(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }
        return results;
    }

    // ==================== 模板方法：核心请求逻辑 ====================

    /**
     * 构建请求、发送 HTTP、解析 OpenAI 格式响应
     */
    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        // 1. 校验提供商与可选密钥，解析 Embedding URL 和模型名，配置不完整时不发远程请求。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        String model = HttpResponseHelper.requireModel(target, provider());

        // 2. 多条文本放进同一 input 数组，向量维度取目标配置，再让供应商钩子补协议字段。
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);
        body.addProperty("dimensions", target.candidate().getDimension());
        customizeRequestBody(body, target);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON));
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request request = requestBuilder.build();

        // embedding 是检索链路上唯一的远端同步调用，慢在这里会被上游按「通道无结果」吞掉
        // 成败两路都记耗时：失败路更要记，慢失败才是吃满上游预算的那一种
        long startTime = System.currentTimeMillis();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} embedding 请求失败 - 模型：{}，条数：{}，耗时：{}ms，status={}, body={}",
                        provider(), model, texts.size(), System.currentTimeMillis() - startTime, response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            json = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            log.warn("{} embedding 请求异常 - 模型：{}，条数：{}，耗时：{}ms，原因：{}",
                    provider(), model, texts.size(), System.currentTimeMillis() - startTime, e.getMessage());
            throw new ModelClientException(
                    provider() + " embedding 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 3. 即使 HTTP 成功，正文里的 error 也表示调用失败，不能继续把它当向量结果解析。
        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " embedding 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        log.info("{} embedding 完成 - 模型：{}，条数：{}，耗时：{}ms",
                provider(), model, texts.size(), System.currentTimeMillis() - startTime);

        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(
                    provider() + " embedding 响应中缺少 data 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 4. 按 data 原顺序将每个 embedding 数组转为 Float 列表；缺少向量时整次请求按无效响应失败。
        List<List<Float>> results = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null || emb.isEmpty()) {
                throw new ModelClientException(
                        provider() + " embedding 响应中缺少 embedding 字段",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(emb.size());
            for (JsonElement v : emb) {
                vector.add(v.getAsFloat());
            }
            results.add(vector);
        }

        return results;
    }
}
