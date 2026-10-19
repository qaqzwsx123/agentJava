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

package com.nageoffer.ai.ragent.knowledge.handler;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 远程文件拉取服务
 * 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteFileFetcher {
    // 远程文件拉取服务 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑。

    private final HttpClientHelper httpClientHelper;
    private final FileStorageService fileStorageService;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /**
     * 流式拉取远程文件并上传到存储（用于文档上传场景）
     */
    public StoredFileDTO fetchAndStore(String bucketName, String url) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);
        Long headContentLength = headResponse == null ? null : headResponse.contentLength();
        checkSizeLimit(maxBytes, headContentLength);

        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            String fileName = firstHasText(response.fileName(), headResponse == null ? null : headResponse.fileName(), "remote-file");
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            // 部分源站的 Content-Length/HEAD 响应并不可靠，固定长度上传会在字节数不一致时失败
            // 远程导入统一先落临时文件，以实际读取到的字节数作为上传大小
            return uploadViaTemp(bucketName, response.bodyStream(), fileName, contentType, maxBytes);
        }
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     * 返回的 RemoteFetchResult 实现了 AutoCloseable，调用方必须用 try-with-resources 管理生命周期
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        // 先用 HEAD 比较 ETag 或修改时间，不能确认未变化时再限量下载并计算哈希，确有变化才保留临时文件。
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);

        // 两个 ETag 都有效时优先比较 ETag，否则才回落最后修改时间；确认未变化可直接跳过正文下载。
        if (headResponse != null) {
            checkSizeLimit(maxBytes, headResponse.contentLength());
            String etag = trimOrNull(headResponse.etag());
            String headLastModified = trimOrNull(headResponse.lastModified());
            String previousEtag = trimOrNull(lastEtag);
            boolean etagComparable = StringUtils.hasText(etag) && StringUtils.hasText(previousEtag);
            boolean unchanged = etagComparable
                    ? etag.equals(previousEtag)
                    : StringUtils.hasText(headLastModified) && headLastModified.equals(trimOrNull(lastModified));
            if (unchanged) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        // HEAD 不能确认未变化时限量流式下载，临时落盘的同时计算内容哈希。
        Path tempFile = null;
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            CopyResult copyResult = copyWithLimitAndDigest(response.bodyStream(), tempFile, maxBytes);
            if (copyResult.size == 0) {
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            String hash = copyResult.sha256Hex;
            String etag = firstHasText(trimOrNull(response.etag()), headResponse == null ? null : trimOrNull(headResponse.etag()), null);
            String fetchLastModified = firstHasText(trimOrNull(response.lastModified()), headResponse == null ? null : trimOrNull(headResponse.lastModified()), null);

            // 即使远端头信息变化，内容哈希相同仍可跳过，并及时删除本轮临时文件。
            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : fallbackFileName;
            return RemoteFetchResult.changed(tempFile, copyResult.size, response.contentType(), fileName, hash, etag, fetchLastModified);
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        }
    }

    /**
     * 尝试预读远程元信息，服务器不支持 HEAD 或请求失败时返回 null，让主流程继续下载检查。
     */
    private HttpClientHelper.HttpHeadResponse tryHead(String url) {
        // 尝试只读取远端元数据，接口不支持或请求失败时返回 null，允许后续直接下载比较内容。
        try {
            return httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            log.debug("HEAD 获取失败，改为直接下载: {}", url, e);
            return null;
        }
    }

    /**
     * 对已知文件长度执行下载前预检，未知长度不在此拒绝，仍需依靠实际读取限额。
     */
    private void checkSizeLimit(long maxBytes, Long contentLength) {
        // 已知长度且设置上限时提前拒绝过大文件，未知长度仍由实际读取阶段检查。
        if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
            throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
        }
    }

    /**
     * 先把远程流按限额写入临时文件，取得准确长度后上传对象存储，空文件拒绝上传。
     * finally 删除临时文件，删除失败只记日志；上传输入流使用完立即关闭。
     */
    private StoredFileDTO uploadViaTemp(String bucketName, InputStream remoteStream, String fileName,
                                        String contentType, long maxBytes) {
        // 把远端内容限量写入临时文件，取得准确长度后上传，成功或异常都会清理临时文件。
        Path tempFile = null;
        try {
            // 临时落盘用于获得准确上传长度，复制过程仍按实际字节数限额。
            tempFile = Files.createTempFile("knowledge-upload-", ".tmp");
            long size = copyWithLimit(remoteStream, tempFile, maxBytes);
            if (size == 0) {
                throw new ClientException("远程文件内容为空");
            }
            try (InputStream tempInputStream = Files.newInputStream(tempFile)) {
                return fileStorageService.upload(bucketName, tempInputStream, size, fileName, contentType);
            }
        } catch (IOException e) {
            throw new ServiceException("远程文件上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("删除远程上传临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 把输入流写入指定临时文件并累计字节数，超限立即停止，返回成功复制的总长度。
     */
    private long copyWithLimit(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        // 按缓冲区复制到临时文件，每次写入前累计实际大小并检查上限，返回最终字节数。
        long total = 0;
        try (var outputStream = Files.newOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                }
                outputStream.write(buffer, 0, len);
            }
            return total;
        }
    }

    private CopyResult copyWithLimitAndDigest(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        // 限量落盘的同时逐段更新 SHA-256，最终同时返回字节数与内容哈希，避免再完整读一遍文件。
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    total += len;
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                    }
                    outputStream.write(buffer, 0, len);
                    digest.update(buffer, 0, len);
                }
            }
            return new CopyResult(total, hexEncode(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 算法不可用");
        }
    }

    private record CopyResult(long size, String sha256Hex) {
        // 流式复制结果：记录写入临时文件的实际字节数和 SHA-256 内容哈希，供体积校验和远程文件变更检测使用。
    }

    private static String hexEncode(byte[] hash) {
        // 将哈希的每个无符号字节转成两位十六进制文本，不足两位补零。
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String value = Integer.toHexString(0xff & b);
            if (value.length() == 1) {
                hex.append('0');
            }
            hex.append(value);
        }
        return hex.toString();
    }

    private void deleteTempFileQuietly(Path tempFile) {
        // 临时文件存在时尝试删除，清理失败只记日志，避免掩盖原始业务错误。
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除临时文件失败: {}", tempFile, e);
            }
        }
    }

    /**
     * 按优先顺序取首个非空白文本，用于合并远程响应与调用方提供的文件信息。
     */
    private String firstHasText(String... values) {
        // 按优先级返回第一个非空白字符串，用于下载响应和 HEAD 元数据之间回落。
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    private String trimOrNull(String value) {
        // 有效文本去掉首尾空白，空白值统一转成 null 方便变化比较。
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static final class RemoteFetchResult implements AutoCloseable {
        // 远程拉取结果及资源句柄：保存是否变化、临时文件、大小、类型、文件名、ETag、修改时间和内容哈希，使用完毕通过关闭操作清理临时资源。

        private final boolean changed;
        private Path tempFile;
        private final long size;
        private final String contentType;
        private final String fileName;
        private final String contentHash;
        private final String etag;
        private final String lastModified;
        private final String message;

        private RemoteFetchResult(boolean changed, Path tempFile, long size, String contentType,
                                  String fileName, String contentHash, String etag,
                                  String lastModified, String message) {
            // 保存本次是否变化、临时文件与远端元数据，供调度流程判断跳过或继续刷新。
            this.changed = changed;
            this.tempFile = tempFile;
            this.size = size;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentHash = contentHash;
            this.etag = etag;
            this.lastModified = lastModified;
            this.message = message;
        }

        public static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            // 构造未变化的结果，不持有临时文件，但保留新的元数据和跳过原因。
            return new RemoteFetchResult(false, null, 0, null, null, contentHash, etag, lastModified, message);
        }

        public static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                                 String contentHash, String etag, String lastModified) {
            // 构造确有变化的结果并交出临时文件，后续处理完成后由结果对象的关闭逻辑清理文件。
            return new RemoteFetchResult(true, tempFile, size, contentType, fileName, contentHash, etag, lastModified, null);
        }

        public boolean changed() {
            // 返回本次远端内容是否发生变化，供调度入口选择跳过或处理。
            return changed;
        }
        public Path tempFile() {
            // 返回本次下载保留的临时文件路径，供上传和分块处理读取。
            return tempFile;
        }
        public long size() {
            // 返回实际下载字节数，供准确设置对象存储上传长度。
            return size;
        }
        public String contentType() {
            // 返回下载响应提供的内容类型，供上传和后续 MIME 处理使用。
            return contentType;
        }
        public String fileName() {
            // 返回本次解析出的文件名，供持久化文件元数据。
            return fileName;
        }
        public String contentHash() {
            // 返回实际内容的哈希，供下次刷新检测内容是否变化。
            return contentHash;
        }
        public String etag() {
            // 返回远端 ETag，供后续通过 HEAD 快速判断是否变化。
            return etag;
        }
        public String lastModified() {
            // 返回远端最后修改标识，供没有可比较 ETag 时使用。
            return lastModified;
        }
        public String message() {
            // 返回跳过原因等说明，供调度执行记录展示。
            return message;
        }

        @Override
        public void close() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // best-effort cleanup
                }
                tempFile = null;
            }
        }
    }
}
