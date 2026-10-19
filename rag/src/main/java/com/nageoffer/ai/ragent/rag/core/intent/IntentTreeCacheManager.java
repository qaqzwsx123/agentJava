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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 意图树缓存管理器
 * 负责意图树在Redis中的缓存管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentTreeCacheManager {
    // 意图树 Redis 缓存管理器：提供整棵意图树读取、写入、存在检查及清理，供分类查询复用并在管理变更后刷新。

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Redis缓存Key
     */
    private static final String INTENT_TREE_CACHE_KEY = "ragent:intent:tree";

    /**
     * 缓存过期时间：7天
     */
    private static final long CACHE_EXPIRE_DAYS = 7;

    /**
     * 从Redis获取意图树缓存
     *
     * @return 意图树根节点列表，如果缓存不存在则返回null
     */
    public List<IntentNode> getIntentTreeFromCache() {
        // 从 Redis 读取整棵意图树，未命中或反序列化异常返回 null 触发数据库加载。
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(INTENT_TREE_CACHE_KEY);
            if (cacheJson == null) {
                log.info("意图树缓存不存在，需要从数据库加载");
                return null;
            }

            return objectMapper.readValue(
                    cacheJson,
                    new TypeReference<>() {
                    }
            );
        } catch (Exception e) {
            log.error("从Redis读取意图树缓存失败", e);
            return null;
        }
    }

    /**
     * 将意图树保存到Redis缓存
     *
     * @param roots 意图树根节点列表
     */
    public void saveIntentTreeToCache(List<IntentNode> roots) {
        // 将根节点列表序列化并设置按天计算的有效期，缓存失败只记录日志。
        try {
            String cacheJson = objectMapper.writeValueAsString(roots);
            stringRedisTemplate.opsForValue().set(
                    INTENT_TREE_CACHE_KEY,
                    cacheJson,
                    CACHE_EXPIRE_DAYS,
                    TimeUnit.DAYS
            );
            log.info("意图树已保存到Redis缓存，根节点数: {}", roots.size());
        } catch (Exception e) {
            log.error("保存意图树到Redis缓存失败", e);
        }
    }

    /**
     * 清除意图树缓存
     * 在意图节点发生增删改时调用
     */
    public void clearIntentTreeCache() {
        // 删除缓存并记录是否实际命中，使下一次分类重新加载最新意图配置。
        Boolean deleted = stringRedisTemplate.delete(INTENT_TREE_CACHE_KEY);
        if (deleted) {
            log.info("意图树缓存已清除，Key: {}", INTENT_TREE_CACHE_KEY);
        } else {
            log.info("意图树缓存不存在，无需清除");
        }
    }

    /**
     * 检查缓存是否存在
     *
     * @return true表示缓存存在，false表示不存在
     */
    public boolean isCacheExists() {
        // 检查缓存键是否存在，Redis 异常按不存在返回，让业务能够继续走加载路径。
        try {
            return stringRedisTemplate.hasKey(INTENT_TREE_CACHE_KEY);
        } catch (Exception e) {
            log.error("检查意图树缓存是否存在失败", e);
            return false;
        }
    }
}
