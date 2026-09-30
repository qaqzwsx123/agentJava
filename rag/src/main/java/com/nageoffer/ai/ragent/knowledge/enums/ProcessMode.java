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

package com.nageoffer.ai.ragent.knowledge.enums;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档处理模式枚举
 */
@Getter
@RequiredArgsConstructor
public enum ProcessMode {
    // 文档处理模式枚举：区分普通分块与流水线处理，提供输入值解析和默认归一化，决定后续执行路径。

    /**
     * 分块策略模式（默认）
     */
    CHUNK("chunk"),

    /**
     * Pipeline 管道模式
     */
    PIPELINE("pipeline");

    /**
     * 处理模式值
     */
    private final String value;

    /**
     * 根据值获取枚举
     *
     * @param value 处理模式值
     * @return 对应的枚举，如果未找到返回 null
     */
    public static ProcessMode fromValue(String value) {
        // 去空白并忽略大小写匹配处理模式，空值或未知值返回 null。
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        for (ProcessMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 解析处理模式，空值或非法值抛出异常
     */
    public static ProcessMode normalize(String value) {
        // 严格校验处理模式，空白或无法识别时明确抛错，其余返回对应枚举。
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("处理模式不能为空");
        }
        ProcessMode result = fromValue(value);
        if (result == null) {
            throw new IllegalArgumentException("不支持的处理模式: " + value);
        }
        return result;
    }
}
