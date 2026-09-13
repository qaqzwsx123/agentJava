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

package com.nageoffer.ai.ragent.ingestion.util;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 响应解析器，用于解析 LLM 返回的 JSON 字符串
 */
public final class JsonResponseParser {

    private static final Gson GSON = new Gson();

    private JsonResponseParser() {
    }

    /**
     * 解析模型输出中的 JSON 数组并转换为 List，非数组或解析失败时返回空列表。
     * 当前使用通用 List 反序列化，没有逐项校验元素都是字符串。
     */
    public static List<String> parseStringList(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        return GSON.fromJson(element, List.class);
    }

    /**
     * 解析模型输出中的 JSON 对象为有序映射，非对象或解析失败时返回空映射。
     */
    public static Map<String, Object> parseObject(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonObject()) {
            return Collections.emptyMap();
        }
        return GSON.fromJson(element, LinkedHashMap.class);
    }

    /**
     * 去除 Markdown 代码围栏并提取 JSON 主体，语法无效时返回 null，让上层采用空产物。
     */
    private static JsonElement parseJsonElement(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        String trimmed = extractJsonBody(cleaned);
        try {
            return JsonParser.parseString(trimmed);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * 从首个对象或数组开始符到最后结束符截取候选 JSON，兼容模型在 JSON 外附加说明文本。
     */
    private static String extractJsonBody(String raw) {
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        int start;
        if (objStart < 0) {
            start = arrStart;
        } else if (arrStart < 0) {
            start = objStart;
        } else {
            start = Math.min(objStart, arrStart);
        }
        if (start < 0) {
            return raw;
        }
        int objEnd = raw.lastIndexOf('}');
        int arrEnd = raw.lastIndexOf(']');
        int end = Math.max(objEnd, arrEnd);
        if (end < 0 || end <= start) {
            return raw.substring(start);
        }
        return raw.substring(start, end + 1);
    }
}
