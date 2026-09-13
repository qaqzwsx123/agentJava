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

package com.nageoffer.ai.ragent.audit.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mzt.logapi.context.LogRecordContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class BizChangeLogContext {

    private static final String BIZ_ID_VARIABLE = "bizChangeBizId";
    private static final String SNAPSHOT_VARIABLE = "bizChangeSnapshot";
    private static final String SKIP_VARIABLE = "bizChangeSkip";
    private static final String NAME_VARIABLE = "bizChangeName";

    public static final String BIZ_ID_EXPRESSION = "{{#" + BIZ_ID_VARIABLE + " != null ? #" + BIZ_ID_VARIABLE + " : 'UNKNOWN'}}";
    public static final String SNAPSHOT_EXPRESSION = "{{#" + SNAPSHOT_VARIABLE + "}}";
    public static final String RECORD_CONDITION = "{{#" + SKIP_VARIABLE + " == null || !" + "#" + SKIP_VARIABLE + "}}";

    private final ObjectMapper objectMapper;

    /**
     * 把业务 ID 与修改前后快照写入审计上下文，快照同时包含逐字段差异，供日志切面统一读取。
     */
    public void put(String bizId, Object beforeSnapshot, Object afterSnapshot) {
        LogRecordContext.putVariable(BIZ_ID_VARIABLE, bizId);
        LogRecordContext.putVariable(SNAPSHOT_VARIABLE, buildSnapshotPayload(beforeSnapshot, afterSnapshot));
    }

    /**
     * 标记跳过当前业务变更日志，供没有有效变更对象等分支使用。
     */
    public void skip() {
        LogRecordContext.putVariable(SKIP_VARIABLE, true);
    }

    // 写入业务对象名称，供操作描述引用（如文档名），避免描述里只有主键
    /**
     * 将业务对象名称放入日志上下文，供审计描述模板引用。
     */
    public void putName(String name) {
        LogRecordContext.putVariable(NAME_VARIABLE, name);
    }

    /**
     * 序列化非空审计对象，失败抛出明确异常，不静默丢弃快照。
     */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化审计快照失败", e);
        }
    }

    /**
     * 把前后对象转换为 JSON 树，组合完整快照和差异数组，再生成审计载荷。
     */
    private String buildSnapshotPayload(Object beforeSnapshot, Object afterSnapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonNode beforeNode = toJsonNode(beforeSnapshot);
        JsonNode afterNode = toJsonNode(afterSnapshot);
        payload.put("beforeSnapshot", nullIfNullNode(beforeNode));
        payload.put("afterSnapshot", nullIfNullNode(afterNode));
        payload.put("changeDiff", diff(beforeNode, afterNode));
        return toJson(payload);
    }

    /**
     * 将对象转换为 JSON 树，Java null 统一转换为 JSON null 节点。
     */
    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        return objectMapper.valueToTree(value);
    }

    /**
     * 将空节点或 JSON null 转回 Java null，使外层审计载荷统一使用空值。
     */
    private Object nullIfNullNode(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    /**
     * 从根路径递归比较前后 JSON，生成带字段路径及前后值的差异数组。
     */
    private ArrayNode diff(JsonNode beforeNode, JsonNode afterNode) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        collectDiff("", beforeNode, afterNode, result);
        return result;
    }

    /**
     * 相同节点直接跳过；对象按字段名并集、数组按下标逐项递归，叶子或类型变化记录为一条差异。
     * 缺失成员按 null 比较，路径采用 JSON Pointer 转义，字段排序保持日志输出稳定。
     */
    private void collectDiff(String path, JsonNode beforeNode, JsonNode afterNode, ArrayNode result) {
        JsonNode normalizedBefore = beforeNode == null ? NullNode.getInstance() : beforeNode;
        JsonNode normalizedAfter = afterNode == null ? NullNode.getInstance() : afterNode;
        if (Objects.equals(normalizedBefore, normalizedAfter)) {
            return;
        }

        // 对象字段取并集并排序，因此新增、删除字段都能记录，输出顺序也保持稳定。
        if (normalizedBefore.isObject() && normalizedAfter.isObject()) {
            Set<String> fieldNames = new TreeSet<>();
            normalizedBefore.fieldNames().forEachRemaining(fieldNames::add);
            normalizedAfter.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                collectDiff(path + "/" + escapeJsonPointer(fieldName),
                        normalizedBefore.get(fieldName),
                        normalizedAfter.get(fieldName),
                        result);
            }
            return;
        }

        // 数组按下标比较到较长一侧末尾，不做元素重排或相似项匹配。
        if (normalizedBefore.isArray() && normalizedAfter.isArray()) {
            int max = Math.max(normalizedBefore.size(), normalizedAfter.size());
            for (int i = 0; i < max; i++) {
                collectDiff(path + "/" + i,
                        i < normalizedBefore.size() ? normalizedBefore.get(i) : NullNode.getInstance(),
                        i < normalizedAfter.size() ? normalizedAfter.get(i) : NullNode.getInstance(),
                        result);
            }
            return;
        }

        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("field", path.isEmpty() ? "/" : path);
        item.set("before", normalizedBefore);
        item.set("after", normalizedAfter);
        result.add(item);
    }

    /**
     * 按 JSON Pointer 规则转义字段名中的波浪号和斜线，避免差异路径含义混淆。
     */
    private String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
