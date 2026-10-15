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

package com.nageoffer.ai.ragent.ingestion.util;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求工具类，用于获取网络资源
 */
@Component
@RequiredArgsConstructor
public class HttpClientHelper {

    @Qualifier("syncHttpClient")
    private final OkHttpClient client;

    /**
     * 发起不设置读取大小上限的 GET，完整读取正文并返回媒体类型、文件名等响应信息。
     */
    public HttpFetchResponse get(String url, Map<String, String> headers) {
        // 复用统一 GET 逻辑，不额外设置读取大小上限。
        return doGet(url, headers, -1);
    }

    public HttpFetchResponse getWithLimit(String url, Map<String, String> headers, long maxBytes) {
        // 把调用方的最大字节数传给统一 GET 逻辑，在下载前与实际读取时双重检查。
        return doGet(url, headers, maxBytes);
    }

    /**
     * 发起 GET 并保留响应流，先按 Content-Length 检查大小，再给实际读取流附加限额。
     * 返回对象持有 HTTP 响应，调用方使用后需要关闭，不能在本方法返回前提前关闭响应。
     */
    public HttpFetchStream openStream(String url, Map<String, String> headers, long maxBytes) {
        // 执行下载并提取响应元数据，预检查长度后返回受限流；响应的关闭责任随返回对象交给调用方。
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try {
            Response response = client.newCall(builder.get().build()).execute();
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                response.close();
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            ResponseBody responseBody = response.body();
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                response.close();
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }
            // 响应长度预检不能覆盖未知或不可信长度，读取包装器继续按实际字节数检查。
            InputStream bodyStream = responseBody == null
                    ? InputStream.nullInputStream()
                    : wrapWithLimit(responseBody.byteStream(), maxBytes);
            // 保持响应打开以供继续读流，调用方使用完毕必须关闭返回的下载对象。
            return new HttpFetchStream(response, bodyStream, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    /**
     * 统一处理 GET 请求头、HTTP 错误与响应元信息，按可选限额读取正文，退出时关闭响应。
     */
    private HttpFetchResponse doGet(String url, Map<String, String> headers, long maxBytes) {
        // 完整下载响应并提取文件元数据，已知长度先检查，受限模式继续累计实际字节数，结束自动关闭响应。
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.get().build()).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }

            // 已知长度先预检，真正读取时再次检查限额；没有限额时直接完整读取。
            byte[] bytes;
            if (response.body() == null) {
                bytes = new byte[0];
            } else if (maxBytes > 0) {
                bytes = readWithLimit(response.body().byteStream(), maxBytes);
            } else {
                bytes = response.body().bytes();
            }
            return new HttpFetchResponse(bytes, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    /**
     * 通过 HEAD 获取 ETag、修改时间、媒体类型和文件大小，不下载正文，用于远程更新和大小预检。
     */
    public HttpHeadResponse head(String url, Map<String, String> headers) {
        // 只请求响应头以获取 ETag、修改时间、类型、长度和文件名，不下载正文。
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.head().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            return new HttpHeadResponse(etag, lastModified, contentType, contentLength, fileName);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    /**
     * 优先解析 Content-Disposition 的 filename 并解码，否则取 URL 路径末段；无法提取返回 null。
     */
    private String resolveFileName(String disposition, String url) {
        // 优先解析 Content-Disposition 中的文件名并解码，没有时取 URL 路径最后一段。
        if (disposition != null) {
            String[] parts = disposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("filename=")) {
                    String raw = trimmed.substring("filename=".length()).trim();
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    return decode(raw);
                }
            }
        }
        // 未从下载响应头拿到文件名时，再从 URL 路径取最后一段。
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 UTF-8 对响应文件名做 URL 解码，解码失败保留原文。
     */
    private String decode(String value) {
        // 按 UTF-8 做 URL 解码，非法编码时保持原字符串，避免文件名推断中断下载流程。
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 将长度响应头转换为 Long，缺失或非数字时返回 null，不能据此假设文件大小为零。
     */
    private Long parseContentLength(String header) {
        // 把响应头转换为长整数，缺失或非数字时返回 null，交给实际读取阶段继续检查。
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /**
     * 分段读取正文，累计实际字节数，超过限额立即抛错；方法负责关闭输入流与缓冲输出。
     */
    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        // 分批读取并在写入内存缓冲前累计大小，一旦超过上限立即失败，同时关闭输入流。
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = in.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    /**
     * 包装输入流，在单字节和批量读取时统一累计实际字节数，超限抛错，close 透传到底层流。
     * maxBytes 非正时直接返回原流，不施加读取限制。
     */
    private InputStream wrapWithLimit(InputStream inputStream, long maxBytes) {
        // 未设置上限时复用原流，否则包装单字节和批量读取入口，共享同一实际字节计数。
        if (maxBytes <= 0) {
            return inputStream;
        }
        // 两种 read 入口共用 total，单字节或批量读取都必须累计，close 透传到底层流。
        return new InputStream() {
            private long total;

            @Override
            public int read() throws IOException {
                // 读取成功后把本次实际字节数计入共享限额，流结束不累计，返回值保持底层读取约定。
                int value = inputStream.read();
                if (value != -1) {
                    ensureWithinLimit(1);
                }
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                // 读取成功后把本次实际字节数计入共享限额，流结束不累计，返回值保持底层读取约定。
                int count = inputStream.read(b, off, len);
                if (count > 0) {
                    ensureWithinLimit(count);
                }
                return count;
            }

            @Override
            public void close() throws IOException {
                // 关闭当前包装对象所持有的底层流或 HTTP 响应，释放下载连接资源。
                inputStream.close();
            }

            private void ensureWithinLimit(int delta) {
                // 累计本次读取量，超过允许字节数时立即抛出大小限制异常。
                total += delta;
                if (total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
            }
        };
    }

    public record HttpFetchResponse(byte[] body,
                                    String contentType,
                                    String fileName,
                                    String etag,
                                    String lastModified,
                                    Long contentLength) {
    }

    public record HttpFetchStream(Response response,
                                  InputStream bodyStream,
                                  String contentType,
                                  String fileName,
                                  String etag,
                                  String lastModified,
                                  Long contentLength) implements AutoCloseable {

        @Override
        public void close() {
            // 关闭当前包装对象所持有的底层流或 HTTP 响应，释放下载连接资源。
            response.close();
        }
    }

    public record HttpHeadResponse(String etag, String lastModified, String contentType, Long contentLength,
                                   String fileName) {
    }
}
