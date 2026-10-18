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

package com.nageoffer.ai.ragent.infra.http;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 模型URL解析器
 * 用于解析AI模型的完整URL地址，支持从候选模型配置或提供商配置中获取URL
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class ModelUrlResolver {
    // 决定模型请求的完整地址：优先使用候选模型的独立 URL，否则拼接供应商基础地址和对应能力端点。

    /**
     * 解析模型URL地址
     * 优先级：候选模型URL > 提供商基础URL + 端点路径
     *
     * @param provider   提供商配置，包含基础URL和端点配置
     * @param candidate  候选模型配置，可能包含自定义URL
     * @param capability 模型能力类型，用于确定使用哪个端点
     * @return 完整的模型URL地址
     * @throws IllegalStateException 当提供商基础URL缺失或端点配置缺失时抛出
     */
    public static String resolveUrl(
            AIModelProperties.ProviderConfig provider,
            AIModelProperties.ModelCandidate candidate,
            ModelCapability capability) {
        // 1. 候选显式填写的 URL 是完整请求地址，优先使用它，不再追加供应商端点。
        if (candidate != null && candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl();
        }
        if (provider == null || provider.getUrl() == null || provider.getUrl().isBlank()) {
            throw new IllegalStateException("Provider baseUrl is missing");
        }

        // 2. 没有模型级地址时，按能力的小写键读取端点，再与供应商基础地址拼接。
        Map<String, String> endpoints = provider.getEndpoints();
        String key = capability.name().toLowerCase();
        String path = endpoints == null ? null : endpoints.get(key);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Provider endpoint is missing: " + key);
        }

        return joinUrl(provider.getUrl(), path);
    }

    /**
     * 拼接基础URL和路径
     * 智能处理URL和路径之间的斜杠，确保拼接结果正确
     *
     * @param baseUrl 基础URL
     * @param path    路径
     * @return 拼接后的完整URL
     */
    private static String joinUrl(String baseUrl, String path) {
        // 只调整基础地址和路径交界处的斜杠：重复时去掉一个，缺失时补一个，其余情况直接连接。
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }
}
