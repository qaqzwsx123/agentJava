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

package com.nageoffer.ai.ragent.rag.core.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 技能缓存管理器
 * 缓存已启用技能的全量清单，每轮对话都要读，不宜每次落库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillCacheManager {
    // 技能缓存管理器 缓存已启用技能的全量清单，每轮对话都要读，不宜每次落库。

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY = "ragent:agent:enabled-skills:v1";

    private static final long CACHE_EXPIRE_HOURS = 1;

    /**
     * @return 已启用技能列表，缓存不存在则返回 null；空列表是有效结果，代表确实一个技能都没配
     */
    public List<AgentSkill> getFromCache() {
        // 读取技能列表 JSON，未命中或缓存解析错误返回 null，供注册表重新查询。
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(CACHE_KEY);
            if (cacheJson == null) {
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("从 Redis 读取技能缓存失败", e);
            return null;
        }
    }

    /**
     * 序列化技能列表并写入带过期时间的 Redis 缓存，失败只记录日志，由注册表保留回源路径。
     */
    public void saveToCache(List<AgentSkill> skills) {
        // 序列化技能列表并设置小时级过期时间，缓存失败仅记录日志。
        try {
            String cacheJson = objectMapper.writeValueAsString(skills);
            stringRedisTemplate.opsForValue().set(CACHE_KEY, cacheJson, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存技能到 Redis 缓存失败", e);
        }
    }

    /**
     * 任何技能写操作后必须调用，否则改动直到过期才生效
     */
    public void clearCache() {
        // 清除技能缓存，管理修改后的下一次加载会重新装配技能内容。
        try {
            stringRedisTemplate.delete(CACHE_KEY);
            log.info("技能缓存已清除");
        } catch (Exception e) {
            log.error("清除技能缓存失败", e);
        }
    }
}
