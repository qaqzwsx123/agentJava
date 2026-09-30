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

package com.nageoffer.ai.ragent.ingestion.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档源类型枚举
 * 定义支持的文档来源类型，用于标识文档的获取方式
 * 类型值使用小写 snake_case，如 file、url、feishu
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {
    // 流水线文档来源枚举：统一上传文件、HTTP 地址和飞书来源的编码及解析，供 FetcherNode 选择对应获取策略。

    /**
     * 本地文件 - 文档来源为本地文件系统
     */
    FILE("file"),

    /**
     * URL地址 - 文档来源为网络 URL
     */
    URL("url"),

    /**
     * 飞书文档 - 文档来源为飞书文档
     */
    FEISHU("feishu");

    /**
     * 类型值（小写 snake_case）
     */
    private final String value;

    /**
     * 根据字符串值解析类型
     */
    @JsonCreator
    public static SourceType fromValue(String value) {
        // null 保持 null，其余先规范化，再兼容枚举名称和配置值匹配；未知输入明确抛错。
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (SourceType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source type: " + value);
    }

    /**
     * 规范文档来源类型的大小写和分隔符，供来源枚举解析使用。
     */
    private static String normalize(String value) {
        // 去掉首尾空白并转为小写，将短横线统一为下划线以兼容配置命名习惯。
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 获取序列化值
     */
    @JsonValue
    public String getValue() {
        // 返回对外配置使用的稳定值，避免依赖枚举显示名称。
        return value;
    }
}
