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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AgentScope 状态持久化 Mapper
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentStateMapper {
    //冲突时，用"新数据的 payload"覆盖"旧数据的 payload"，并刷新 update_time。
    /**
     * 按用户、会话和状态键插入 JSON 状态；唯一键冲突时覆盖 payload 并刷新更新时间。
     */
    @Insert("""
            INSERT INTO t_agent_state (user_id, session_id, state_key, payload, create_time, update_time)
            VALUES (#{userId}, #{sessionId}, #{stateKey}, #{payload}::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, session_id, state_key)
            DO UPDATE SET payload = EXCLUDED.payload, update_time = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("userId") String userId,
                @Param("sessionId") String sessionId,
                @Param("stateKey") String stateKey,
                @Param("payload") String payload);

    /**
     * 同时限定用户、会话和状态键读取 JSON 状态，避免不同用户或状态类型之间串读。
     */
    @Select("""
            SELECT payload
            FROM t_agent_state
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    String selectPayload(@Param("userId") String userId,
                         @Param("sessionId") String sessionId,
                         @Param("stateKey") String stateKey);

    //判断会话是否存在状态  用 PG 的 EXISTS 返回布尔，存在任意状态即 true。 比 COUNT(*) > 0 更高效（找到一条就返回）。
    /**
     * 判断指定用户会话是否存在任一状态；SQL EXISTS 命中一条即可返回。
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM t_agent_state
                WHERE user_id = #{userId} AND session_id = #{sessionId}
            )
            """)
    boolean exists(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /**
     * 删除当前用户指定会话的全部状态键，供删除会话及重置上下文使用。
     */
    @Delete("DELETE FROM t_agent_state WHERE user_id = #{userId} AND session_id = #{sessionId}")
    void deleteBySession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /**
     * 仅删除当前用户、会话下指定状态键，保留该会话的其他状态。
     */
    @Delete("""
            DELETE FROM t_agent_state
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    void deleteByKey(@Param("userId") String userId,
                     @Param("sessionId") String sessionId,
                     @Param("stateKey") String stateKey);

    // 列出所有会话 ID   去重并表示顺序
    /**
     * 按用户列出不同会话 ID 并排序，供状态存储枚举会话使用。
     */
    @Select("""
            SELECT DISTINCT session_id
            FROM t_agent_state
            WHERE user_id = #{userId}
            ORDER BY session_id
            """)
    List<String> selectSessionIds(@Param("userId") String userId);
}
