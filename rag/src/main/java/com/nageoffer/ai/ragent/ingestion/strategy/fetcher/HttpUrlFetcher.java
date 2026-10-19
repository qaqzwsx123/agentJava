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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 链接文档获取器
 * 用于从指定的 HTTP/HTTPS 链接地址获取文档内容
 */
@Component
@RequiredArgsConstructor
public class HttpUrlFetcher implements DocumentFetcher {
    // HTTP 链接文档获取器 用于从指定的 HTTP/HTTPS 链接地址获取文档内容。

    private final HttpClientHelper httpClientHelper;

    @Override
    public SourceType supportedType() {
        // 声明支持普通 URL 来源，供获取器节点登记路由。
        return SourceType.URL;
    }

    /**
     * 校验来源地址、组装鉴权头并下载文件，补全文件名和 MIME，返回统一抓取结果。
     */
    @Override
    public FetchResult fetch(DocumentSource source) {
        // 校验链接后携带请求头下载，优先保留指定文件名，缺少有效 Content-Type 时再探测实际 MIME。
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("链接地址不能为空");
        }

        Map<String, String> headers = buildHeaders(source.getCredentials());
        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = normalizeContentType(resp.contentType());
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 把 token 凭证转为 Bearer 鉴权头，其他凭证键原样作为请求头，忽略空键与 null 值。
     */
    private Map<String, String> buildHeaders(Map<String, String> credentials) {
        // 把来源凭证转换为请求头，其中 token 转换为 Bearer 鉴权，其余非空键值直接沿用。
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        credentials.forEach((k, v) -> {
            if (!StringUtils.hasText(k) || v == null) {
                return;
            }
            if ("token".equalsIgnoreCase(k)) {
                headers.put("Authorization", "Bearer " + v);
            } else {
                headers.put(k, v);
            }
        });
        return headers;
    }

    /**
     * 移除媒体类型中的 charset 等分号参数，返回用于解析器匹配的 MIME 主类型。
     */
    private String normalizeContentType(String contentType) {
        // 移除分号后的字符集等附加参数，只保留 MIME 主值，空值返回 null。
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        int idx = contentType.indexOf(';');
        return idx > 0 ? contentType.substring(0, idx).trim() : contentType.trim();
    }
}
