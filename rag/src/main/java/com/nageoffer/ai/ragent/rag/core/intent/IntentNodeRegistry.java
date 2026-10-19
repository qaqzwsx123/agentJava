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

import java.util.List;

/**
 * 意图节点注册表
 * 用于在运行期快速获取意图树和节点信息
 */
public interface IntentNodeRegistry {
    // 运行期意图树访问接口：统一获取根节点和指定节点信息，供工具目录、澄清和其他运行期组件读取。

    /**
     * 根据节点 ID 获取节点
     */
    IntentNode getNodeById(String id);

    /**
     * 获取当前已启用、可参与路由的 MCP 叶子节点
     */
    List<IntentNode> listMcpToolNodes();
}
