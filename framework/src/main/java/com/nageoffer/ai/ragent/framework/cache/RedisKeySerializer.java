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

package com.nageoffer.ai.ragent.framework.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Key 序列化
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeySerializer implements RedisSerializer<String> {

    @Value("${framework.cache.redis.prefix:}")
    private String keyPrefix;

    @Override
    public byte[] serialize(String key) throws SerializationException {
        // 先在原始 key 前拼接配置前缀，再使用平台默认字符集生成字节；这里没有额外做空值或编码转换。
        String builderKey = keyPrefix + key;
        // 返回的是带前缀的完整键字节，Redis 中不同业务可借此区分命名空间。
        return builderKey.getBytes();
    }

    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        // 按 UTF-8 把 Redis 返回的字节恢复成字符串；这里只解码，不移除序列化时添加的前缀。
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
