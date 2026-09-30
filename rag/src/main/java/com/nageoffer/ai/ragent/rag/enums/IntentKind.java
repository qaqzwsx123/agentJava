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

package com.nageoffer.ai.ragent.rag.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 意图类型枚举
 * 用于区分用户意图的不同类型
 */
@Getter
@RequiredArgsConstructor
public enum IntentKind {
    // 意图业务类型枚举：区分知识库、工具和系统等意图，供候选筛选、知识检索及系统回答分支判断。

    /**
     * 知识库类，走 RAG
     */
    KB(0),

    /**
     * 系统交互类，比如欢迎语、介绍自己
     */
    SYSTEM(1),

    /**
     * MCP，实时数据交互
     */
    MCP(2);

    /**
     * 意图类型编码
     */
    private final int code;

    /**
     * 根据编码获取对应的意图类型
     *
     * @param code 意图类型编码
     * @return 对应的意图类型枚举值，如果编码不存在则返回null
     */
    public static IntentKind fromCode(Integer code) {
        // 按稳定数字编码查找枚举，空编码或没有匹配项时返回 null。
        if (code == null) {
            return null;
        }
        for (IntentKind e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 返回枚举名称
     *
     * @return 枚举的名称字符串
     */
    @Override
    public String toString() {
        // 返回枚举名称，供日志与文本展示使用，不混同数字存储编码。
        return name();
    }
}
