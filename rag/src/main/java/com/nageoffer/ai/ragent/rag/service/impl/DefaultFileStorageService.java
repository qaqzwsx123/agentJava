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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.util.DisplayType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 后端无关的文件存储实现
 * <p>
 * 负责 namespace/key 组装、桶的语义归属（私有文档 → kbBucket，公共资产 → assetBucket）、Tika 类型探测
 * 与 {@link StoredFileDTO} 装配，底层裸操作委托给 {@link ObjectStorageClient}（S3 或 OSS，由
 * {@code rag.storage.type} 决定）；存储引用只保留裸 key，桶是部署级配置常量、不写进数据，改桶名不会令
 * 历史引用失联
 */
@Slf4j
@Service
public class DefaultFileStorageService implements FileStorageService {

    private static final Tika TIKA = new Tika();
    private static final String LOCK_KEY_PREFIX = "ragent:kb:space:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final ObjectStorageClient objectStorageClient;
    private final RedissonClient redissonClient;
    private final String kbBucket;
    private final String assetBucket;

    public DefaultFileStorageService(ObjectStorageClient objectStorageClient,
                                     RedissonClient redissonClient,
                                     RagStorageProperties properties) {
        this.objectStorageClient = objectStorageClient;
        this.redissonClient = redissonClient;
        this.kbBucket = properties.getKbBucket();
        this.assetBucket = properties.getAssetBucket();
    }

    /**
     * 把表单上传文件流式写入知识库共享桶下的指定命名空间。
     * 先用独立输入流探测 MIME，再重新打开文件流上传，返回对象 key 和检测元信息。
     */
    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, MultipartFile file) {
        // 校验命名空间和文件有效性，避免把空文件写入知识库存储。
        validateNamespace(namespace);
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();

        // TIKA 只读流的前几 KB 来探测类型，不会加载整个文件
        String detectedContentType;
        try (InputStream is = file.getInputStream()) {
            detectedContentType = TIKA.detect(is, originalFilename);
        }

        // MultipartFile.getInputStream() 每次调用都返回新流（从底层临时文件重新打开），无需再创建临时文件
        try (InputStream is = file.getInputStream()) {
            String key = documentKey(namespace, originalFilename);
            objectStorageClient.streamPut(kbBucket, key, is, size, detectedContentType);
            return buildStoredFileDTO(key, originalFilename, detectedContentType, size);
        }
    }

    /**
     * 将已知大小的输入流写入知识库命名空间。
     * 调用方提供长度和类型，未提供类型时按文件名补充推断。
     */
    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, InputStream content, long size, String originalFilename, String contentType) {
        // 本重载不把全部输入流读进内存，实际流式写入交给存储客户端。
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        objectStorageClient.streamPut(kbBucket, key, content, size, detected);
        return buildStoredFileDTO(key, originalFilename, detected, size);
    }

    /**
     * 把已经位于内存中的字节数组写入知识库命名空间。
     * ByteArrayInputStream 只包装现有字节，不额外复制完整文件。
     */
    @Override
    @SneakyThrows
    public StoredFileDTO upload(String namespace, byte[] content, String originalFilename, String contentType) {
        // 统一生成随机对象 key，返回数据保存该 key 供后续文件读取。
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        // byte[] 本身已在内存中，ByteArrayInputStream 不产生额外拷贝
        objectStorageClient.streamPut(kbBucket, key, new ByteArrayInputStream(content), content.length, detected);
        return buildStoredFileDTO(key, originalFilename, detected, content.length);
    }

    /**
     * 使用存储客户端的可靠写入路径保存输入流。
     * 流大小和命名空间必填，具体写入保障由 reliablePut 实现。
     */
    @Override
    @SneakyThrows
    public StoredFileDTO reliableUpload(String namespace, InputStream content, long size, String originalFilename, String contentType) {
        // 与普通上传共用 key 和 MIME 生成方式，但选择不同的客户端写入入口。
        validateNamespace(namespace);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        String key = documentKey(namespace, originalFilename);
        objectStorageClient.reliablePut(kbBucket, key, content, size, detected);
        return buildStoredFileDTO(key, originalFilename, detected, size);
    }

    /**
     * 把头像等公共资源写入独立资源桶。
     * 对象使用随机 key，和知识库文档桶分开保存。
     */
    @Override
    @SneakyThrows
    public StoredFileDTO uploadAsset(byte[] content, String originalFilename, String contentType) {
        // 公共资源不使用知识库命名空间，其访问地址通过 getPublicUrl 生成。
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        String key = randomKey(originalFilename);
        objectStorageClient.streamPut(assetBucket, key, new ByteArrayInputStream(content), content.length, detected);
        return buildStoredFileDTO(key, originalFilename, detected, content.length);
    }

    /**
     * 按知识库桶内的对象 key 打开读取流。
     * 返回的 InputStream 由调用方负责关闭，文档预览和文件响应均复用此入口。
     */
    @Override
    public InputStream openStream(String key) {
        // 参数虽然来自文档 fileUrl 字段，这里的实际语义是对象 key。
        Assert.notBlank(key, "对象 key 不能为空");
        return objectStorageClient.getObject(kbBucket, key);
    }

    /**
     * 按保存的对象 key 删除知识库原文件。
     * 方法名沿用 URL 命名，但当前实现直接把 key 交给知识库桶删除接口。
     */
    @Override
    public void deleteByUrl(String key) {
        // 先校验 key 非空，不在这里解析任意外部下载地址。
        Assert.notBlank(key, "对象 key 不能为空");
        objectStorageClient.deleteObject(kbBucket, key);
    }

    /**
     * 为资源桶中的对象 key 构造公开访问地址。
     * 仅生成地址，不读取文件内容，也不把资源复制到其他存储空间。
     */
    @Override
    public String getPublicUrl(String key) {
        // 知识库文档文件读取与公共资源链接生成使用不同的桶。
        Assert.notBlank(key, "对象 key 不能为空");
        return objectStorageClient.buildPublicUrl(assetBucket, key);
    }

    /**
     * 在共享知识库桶中幂等建立命名空间目录标记。
     * 采用分布式锁加双重检查，只有不存在标记对象时才写入零字节目录标记。
     */
    @Override
    public void createKnowledgeSpace(String namespace) {
        // 这是创建逻辑目录，不是为每个知识库再创建一个物理桶。
        validateNamespace(namespace);
        String markerKey = namespace + "/";
        if (objectStorageClient.objectExists(kbBucket, markerKey)) {
            return;
        }

        // 集群下用分布式锁 + 双重检查保证目录只建一次，替代旧 createBucket 的冲突保证
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + namespace);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("知识库目录初始化获取分布式锁被中断 namespace=" + namespace);
        }
        if (!locked) {
            throw new ServiceException("知识库目录初始化获取分布式锁超时 namespace=" + namespace);
        }

        try {
            if (objectStorageClient.objectExists(kbBucket, markerKey)) {
                return;
            }
            // 写一个 0 字节标记对象，使空知识库目录在控制台可见
            objectStorageClient.streamPut(kbBucket, markerKey, new ByteArrayInputStream(new byte[0]), 0, null);
            log.info("知识库目录创建成功 bucket={}, namespace={}", kbBucket, namespace);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 按命名空间前缀清理一个知识库的全部对象。
     * 命名空间后拼接斜杠，避免前缀相近的其他知识库被误匹配。
     */
    @Override
    public void deleteKnowledgeSpace(String namespace) {
        // 底层删除范围交给对象存储客户端的前缀删除方法。
        validateNamespace(namespace);
        objectStorageClient.deleteByPrefix(kbBucket, namespace + "/");
    }

    /**
     * 组装知识库文档 key：{@code {namespace}/{uuid}.{ext}}
     */
    private String documentKey(String namespace, String originalFilename) {
        return namespace + "/" + randomKey(originalFilename);
    }

    /**
     * 提取文件名最后一个点后的扩展名；无文件名、无点或点位于末尾时返回空字符串。
     */
    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    /**
     * 生成随机对象 key：{@code {32位十六进制}.{ext}}，即去掉连字符的 UUID 加原始后缀
     */
    private String randomKey(String originalFilename) {
        String suffix = extractSuffix(originalFilename);
        String key = UUID.randomUUID().toString().replace("-", "");
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    /**
     * 校验对象存储命名空间非空，避免后续对象键缺少业务隔离前缀。
     */
    private void validateNamespace(String namespace) {
        Assert.notBlank(namespace, "namespace 不能为空");
    }

    /**
     * 构造上传结果，分别保存展示类型与真实 MIME 信息。
     * 展示标签由文件名和类型归一得出，解析路由使用 mimeType，二者用途不同。
     */
    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                             String contentType, long size) {
        // url 字段保存存储对象 key，文档后续通过文件服务读取该对象。
        // 展示标签唯一产生点：扩展名优先，取值集合封闭
        String detectedType = DisplayType.of(originalFilename, contentType).getCode();
        return StoredFileDTO.builder()
                .url(url)
                .detectedType(detectedType)
                .mimeType(contentType)
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    /**
     * 优先使用调用方提供的媒体类型，缺失时根据原始文件名探测，无法判断时返回 null。
     */
    private String resolveContentType(String originalFilename, String contentType) {
        if (contentType != null && !contentType.isBlank()) return contentType;
        if (originalFilename != null && !originalFilename.isBlank()) return TIKA.detect(originalFilename);
        return null;
    }
}
