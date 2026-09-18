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

package com.nageoffer.ai.ragent.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        // 返回百炼配置 ID，供重排序路由找到本客户端。
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 1. 没有候选直接返回空列表；有候选先按切片 ID 去重，保持首次出现的顺序。
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        // 不按「候选没超 topN 就不必截断」早退：精排除了截断还负责重排与出分，
        // 候选少恰是库里没料的典型形态，早退会让证据闸门在最该拦的时候无分可读
        if (topN <= 0) {
            return dedup;
        }

        // 2. topN 为正时始终执行精排，即使候选数已不足 topN，也仍需模型重排和给分。
        return doRerank(query, dedup, Math.min(topN, dedup.size()), target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 1. 取得供应商配置并检查有效输入，再按百炼协议组装 query、documents 与 top_n。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        // 2. 使用重排专用端点和 Bearer 鉴权，HTTP 状态失败与网络异常分别转换为统一模型错误。
        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        // 3. 响应 index 指向请求文档数组，校验范围后回取原切片，避免用供应商返回文本重新拼装元数据。
        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 整体拷贝仅覆盖分数：逐字段白名单在 RetrievedChunk 新增字段时会静默漏拷（collectionName 曾因此丢失、意图归属整体失效）
            // 同一个分写两处：score 会被下游覆写，rerankScore 留给证据闸门
            RetrievedChunk hit = score != null
                    ? src.toBuilder()
                    .score(score)
                    .rerankScore(score)
                    .build()
                    : unscored(src);
            reranked.add(hit);
            addedIds.add(src.getId());

            if (reranked.size() >= topN) {
                break;
            }
        }

        // 4. 返回结果不足时按原候选顺序补入未使用条目，通用分数设 0，不伪造新的精排分。
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(unscored(c));
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    /**
     * 精排没出分的候选压到 0 沉底
     * 留着 RRF 分会让一个列表里混两把尺子——名次派生的 0.03 反而压过被判为弱相关的 0.01
     * 不写 {@code rerankScore}，证据闸门据此认出这条没经过精排
     */
    private static RetrievedChunk unscored(RetrievedChunk chunk) {
        // 复制全部切片字段，只把通用 score 设为 0；不新增或覆盖 rerankScore，保留未取得新精排分的事实。
        return chunk.toBuilder().score(0F).build();
    }

    private JsonObject requireOutput(JsonObject respJson) {
        // 逐层要求 output 与 results 字段存在，空结果数组由外层继续校验，不把协议缺项当成正常空召回。
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }
}
