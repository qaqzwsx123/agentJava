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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书文档抓取器
 * 负责从飞书平台获取文档内容，支持 docx 类型的在线文档和二进制文件
 */
@Component
@RequiredArgsConstructor
public class FeishuFetcher implements DocumentFetcher {
    // 飞书文档抓取器 负责从飞书平台获取文档内容，支持 docx 类型的在线文档和二进制文件。

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/";

    @Qualifier("syncHttpClient")
    private final OkHttpClient okHttpClient;
    private final HttpClientHelper httpClientHelper;

    @Override
    public SourceType supportedType() {
        // 声明处理飞书来源，供获取器按来源类型选择实现。
        return SourceType.FEISHU;
    }

    /**
     * 识别飞书文档链接后调用原始正文接口；其他地址按普通文件下载，并补全鉴权、文件名和 MIME。
     */
    @Override
    public FetchResult fetch(DocumentSource source) {
        // 校验链接并解析访问令牌，飞书文档链接走正文 API，其余地址直接下载，最终返回内容、MIME 和文件名。
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("飞书文档地址不能为空");
        }

        String accessToken = resolveAccessToken(source.getCredentials());
        Map<String, String> headers = new HashMap<>();
        if (StringUtils.hasText(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }

        if (isDocxUrl(location)) {
            String docToken = extractDocToken(location);
            String apiUrl = "https://open.feishu.cn/open-apis/docx/v1/documents/" + docToken + "/raw_content";
            HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(apiUrl, headers);
            String content = extractDocxContent(resp.body());
            if (!StringUtils.hasText(content)) {
                content = new String(resp.body(), StandardCharsets.UTF_8);
            }
            String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : docToken + ".txt";
            return new FetchResult(content.getBytes(StandardCharsets.UTF_8), "text/plain", fileName);
        }

        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = resp.contentType();
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 根据链接中是否包含 docx 或 docs 路径片段判断是否走飞书文档正文接口。
     */
    private boolean isDocxUrl(String location) {
        // 根据链接中的 docx 或 docs 路径判断是否需要走飞书文档正文接口。
        return location.contains("/docx/") || location.contains("/docs/");
    }

    /**
     * 从 docs 或 docx 路径后的段提取文档 token 并去掉查询参数，无法定位时抛错。
     */
    private String extractDocToken(String location) {
        // 找到文档路径后的令牌并去掉查询参数，无法解析时明确报错。
        String[] parts = location.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "docs".equalsIgnoreCase(parts[i])) {
                if (i + 1 < parts.length) {
                    String token = parts[i + 1];
                    int queryIndex = token.indexOf('?');
                    return queryIndex > 0 ? token.substring(0, queryIndex) : token;
                }
            }
        }
        throw new ServiceException("无法从飞书链接解析文档令牌: " + location);
    }

    /**
     * 优先复用传入的租户或访问 token，缺失时用应用凭证申请；凭证不足返回 null。
     */
    private String resolveAccessToken(Map<String, String> credentials) {
        // 优先复用传入的租户或访问令牌，没有时再用应用 ID 与密钥申请租户令牌。
        if (credentials == null) {
            return null;
        }
        String token = credentials.get("tenantAccessToken");
        if (!StringUtils.hasText(token)) {
            token = credentials.get("accessToken");
        }
        if (StringUtils.hasText(token)) {
            return token;
        }
        String appId = credentials.get("app_id");
        String appSecret = credentials.get("app_secret");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return null;
        }
        return requestTenantAccessToken(appId, appSecret);
    }

    /**
     * 向飞书申请租户访问令牌，检查 HTTP 响应并读取 token，失败转换为服务异常。
     */
    private String requestTenantAccessToken(String appId, String appSecret) {
        // 向飞书令牌接口提交应用凭证，检查 HTTP 和响应体后读取令牌，异常转换为服务错误。
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("app_id", appId);
            payload.addProperty("app_secret", appSecret);

            Request request = new Request.Builder()
                    .url(TOKEN_URL)
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new ServiceException("飞书令牌请求失败: " + response.code());
                }
                String raw = response.body().string();
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
                if (json.has("tenant_access_token")) {
                    return json.get("tenant_access_token").getAsString();
                }
                return null;
            }
        } catch (Exception e) {
            throw new ServiceException("飞书令牌请求失败: " + e.getMessage());
        }
    }

    /**
     * 从飞书 JSON 响应读取 data.content，缺失或无法解析时返回 null，由外层决定正文兜底。
     */
    private String extractDocxContent(byte[] bytes) {
        // 从飞书 JSON 的 data.content 中提取正文，缺失或解析失败返回 null 供抓取入口回落。
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("data")) {
                JsonObject data = root.getAsJsonObject("data");
                if (data.has("content")) {
                    return data.get("content").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
