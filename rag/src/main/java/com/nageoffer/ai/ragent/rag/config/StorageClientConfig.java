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

package com.nageoffer.ai.ragent.rag.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 对象存储客户端配置
 * <p>
 * 依 {@code rag.storage.type} 二选一注册：s3（rustfs / minio，默认）注册 {@link S3Client} + {@link S3Presigner}，
 * oss 注册阿里云 {@link OSS} 客户端。与其对应的 {@code ObjectStorageClient} 实现按同一条件注册
 */
@Configuration
public class StorageClientConfig {
    // 对象存储客户端配置 依 rag.storage.type 二选一注册：s3（rustfs / minio，默认）注册 S3Client + S3Presigner， oss 注册阿里云 OSS 客户端。与其对应的 ObjectStorageClient 实现按同一条件注册。

    @Bean
    @ConditionalOnProperty(name = "rag.storage.type", havingValue = "s3", matchIfMissing = true)
    public S3Client s3Client(RagStorageProperties properties) {
        // 按端点、区域、静态凭证及路径风格创建 S3 兼容客户端，供对象读写复用。
        RagStorageProperties.S3 s3 = properties.getS3();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .forcePathStyle(s3.isPathStyle())
                .build();
    }

    /**
     * S3 预签名器：签名在 URL query 参数中完成，配合 HttpURLConnection 实现零堆内存的流式上传
     */
    @Bean
    @ConditionalOnProperty(name = "rag.storage.type", havingValue = "s3", matchIfMissing = true)
    public S3Presigner s3Presigner(RagStorageProperties properties) {
        // 预签名器与 S3 客户端使用相同端点、区域和寻址风格，保证生成链接对应同一存储服务。
        RagStorageProperties.S3 s3 = properties.getS3();
        return S3Presigner.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyle())
                        .build())
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "rag.storage.type", havingValue = "oss")
    public OSS ossClient(RagStorageProperties properties) {
        // 按 OSS 端点和凭证创建原生客户端，具体存储实现由条件装配选择。
        RagStorageProperties.Oss oss = properties.getOss();
        return new OSSClientBuilder().build(oss.getEndpoint(), oss.getAccessKey(), oss.getSecretKey());
    }
}
