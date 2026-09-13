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

package com.nageoffer.ai.ragent.agent.state;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentStateStore 的 PostgreSQL 实现
 * 官方 2.0.2 仅有 in-memory / JSON 文件 / Redis / MySQL，本项目主存储为 PG，故自实现挂 t_agent_state
 * payload 是 AgentScope 自有编解码的不透明 JSON，不与业务表建立结构约定
 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    /**
     * 与官方 JsonFileAgentStateStore 对齐的匿名用户哨兵，PG 主键列不可为空
     */
    private static final String ANONYMOUS_USER = "__anon__";

    // 状态表 Mapper，构造器注入
    private final AgentStateMapper agentStateMapper;

    //value 是 State 对象（AgentScope 的状态对象)  数据库列 payload 是 jsonb 类型,所以必须先把对象序列化成 JSON 字符串再存
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        // 单值写入：把 State 序列化成 JSON，按 (userId, sessionId, key) upsert 进 t_agent_state
        agentStateMapper.upsert(safeUser(userId), sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        // 列表写入：同样序列化成 JSON 存一整条，读取时再按元素类型逐个还原
        agentStateMapper.upsert(safeUser(userId), sessionId, key, JsonUtils.getJsonCodec().toJson(values));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        // 先查原始 JSON payload
        String payload = queryPayload(userId, sessionId, key);
        // 不存在或为空 → 返回空 Optional，不抛异常
        if (StrUtil.isBlank(payload)) {
            return Optional.empty();
        }
        // 反序列化成目标类型；解析失败时 fromJson 可能返回 null，用 ofNullable 兜底
        return Optional.ofNullable(JsonUtils.getJsonCodec().fromJson(payload, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        // 先查原始 JSON payload
        String payload = queryPayload(userId, sessionId, key);
        // 不存在或为空 → 返回空列表
        if (StrUtil.isBlank(payload)) {
            return List.of();
        }
        // 先按 List 反序列化出原始元素（元素类型此时未知）
        List<?> rawItems = JsonUtils.getJsonCodec().fromJson(payload, List.class);
        // 预分配容量，逐个把元素转换成目标类型 itemType
        List<T> result = new ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            result.add(JsonUtils.getJsonCodec().convertValue(item, itemType));
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        // 判断该用户该会话下是否存在任意状态记录
        return agentStateMapper.exists(safeUser(userId), sessionId);
    }

    /**
     * 按用户、会话及可选状态键删除 Agent 持久化状态；缺省用户经 safeUser 统一处理。
     */
    @Override
    public void delete(String userId, String sessionId) {
        // 删除整个会话的全部状态（所有 key）
        agentStateMapper.deleteBySession(safeUser(userId), sessionId);
    }

    /**
     * 按用户、会话及可选状态键删除 Agent 持久化状态；缺省用户经 safeUser 统一处理。
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        // 只删除指定 key 的状态
        agentStateMapper.deleteByKey(safeUser(userId), sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        // 查该用户所有会话 ID，用 LinkedHashSet 去重并保留查询顺序
        return new LinkedHashSet<>(agentStateMapper.selectSessionIds(safeUser(userId)));
    }

    private String queryPayload(String userId, String sessionId, String key) {
        // 统一入口：查原始 JSON payload，userId 先过 safeUser 兜底
        return agentStateMapper.selectPayload(safeUser(userId), sessionId, key);
    }

    /**
     * 将空用户映射到匿名用户占位值，统一状态存储的用户键。
     */
    private String safeUser(String userId) {
        // userId 为空时替换为匿名哨兵，避免 PG 主键列为空
        return StrUtil.isBlank(userId) ? ANONYMOUS_USER : userId;
    }
}