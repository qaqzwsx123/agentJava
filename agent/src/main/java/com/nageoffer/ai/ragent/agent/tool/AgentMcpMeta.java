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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.util.StrUtil;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 调用扩展元数据
 * 身份只通过 _meta 传递，不进入模型可控制的工具参数
 * 仅适用于可信内部调用链，不作为开放网络认证凭据
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class AgentMcpMeta {

    /**
     * 当前登录主体标识
     */
    public static final String USER_ID_KEY = "com.nageoffer.ragent/userId";

    /**
     * 构造传给 MCP 工具的用户元数据；用户为空时返回空映射。
     */
    public static Map<String, Object> ofUser(String userId) {
        // 用户身份来自服务端登录上下文；为空时不带身份，有值时放入 MCP 的扩展元数据。
        return StrUtil.isBlank(userId)
                ? Map.of()
                : Map.of(USER_ID_KEY, userId);
    }
}
