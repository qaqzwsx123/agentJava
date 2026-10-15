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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;

/**
 * OpenAI 协议风格 SSE 解析器
 * 支持从 delta/message 中提取 content，以及可选的 reasoning_content
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    /**
     * 解析一行兼容 OpenAI 的 SSE 数据，识别 data 前缀、DONE 标志、正文、可选思考内容和结束原因。
     * 该方法只解析协议，不发送 SSE；解析异常由调用它的读取循环处理。
     */
    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        // 1. 空行不产生业务事件；非空内容先处理可选 data: 前缀，再判断独立的 [DONE] 标记。
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        // 2. 普通数据帧解析 JSON，只读取首个 choice；没有 choice 的统计帧等内容返回空事件。
        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        // 3. 正文、思考和完成可同时出现在一帧，读取循环会先分发文本，再处理终止信号。
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    /**
     * 仅当 finish_reason 字段存在且不为 JSON null 时识别为结束信号。
     */
    private static boolean hasFinishReason(JsonObject choice) {
        // 字段缺失或 JSON null 均不算结束，避免把仍在输出的增量帧提前结算。
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    /**
     * 优先从 delta 读取指定文本，缺少有效增量时兼容 message 结构，均不存在返回 null。
     */
    private static String extractText(JsonObject choice, String fieldName) {
        // 1. 优先从增量 delta 中读取指定字段，字段不存在或为 null 才继续尝试完整 message 结构。
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        // 2. 部分兼容端点返回 message 而非 delta，前一个分支未取到有效值时才走此回退。
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    record ParsedEvent(String content, String reasoning, boolean completed) {

        static ParsedEvent empty() {
            // 表示这一行没有可分发内容，且不能据此结束整个流。
            return new ParsedEvent(null, null, false);
        }

        static ParsedEvent done() {
            // 只携带完成标志，不附加正文或思考，调用方据此触发正常完成回调。
            return new ParsedEvent(null, null, true);
        }

        boolean hasContent() {
            // 正文为 null 或全空白时不分发；是否完成仍由独立的 completed 字段决定。
            return content != null && !content.isBlank();
        }

        boolean hasReasoning() {
            // 思考只排除 null 和空串，保留空白增量以延续供应商返回的片段格式。
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
