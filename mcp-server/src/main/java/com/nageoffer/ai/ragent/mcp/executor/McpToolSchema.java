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

package com.nageoffer.ai.ragent.mcp.executor;

import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具入参 schema 构造器，把各执行器手拼的 {@code Map.of("type", "string", ...)} 收成一处
 * <p>
 * 只覆盖本仓工具实际用到的形态：三种标量类型、title/description/default/enum。
 * 需要数组或嵌套对象时再扩，不提前造无人调用的分支
 */
public final class McpToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    private final List<String> required = new ArrayList<>();

    private McpToolSchema() {
        // 通过 object 工厂创建构建器，私有构造器统一收口初始化入口。
    }

    public static McpToolSchema object() {
        // 创建新的 Schema 构建器，每个工具独立维护自己的属性顺序和必填字段列表。
        return new McpToolSchema();
    }

    public static Property string(String name, String description) {
        // 创建字符串属性描述，参数名称和说明交给 Property 构造器统一检查。
        return new Property(name, "string", description);
    }

    public static Property integer(String name, String description) {
        // 创建整数属性描述，后续可继续配置展示标题、候选值或默认值。
        return new Property(name, "integer", description);
    }

    public static Property number(String name, String description) {
        // 创建数值属性描述，供金额等允许小数的参数声明使用。
        return new Property(name, "number", description);
    }

    /**
     * 必填参数，参数名会进 schema 的 required 列表
     */
    public McpToolSchema required(Property property) {
        // 先记录必填属性名，再把属性定义放入有序集合，最终写入 JSON Schema 的 required 列表。
        required.add(property.name);
        return put(property);
    }

    public McpToolSchema optional(Property property) {
        // 仅登记属性定义而不加入必填列表；参数缺省时的处理仍由具体执行器负责。
        return put(property);
    }

    /**
     * {@code additionalProperties} 恒为 false：schema 之外的键一律不认
     * <p>
     * 服务端不据此校验入参（SDK 的 JsonSchemaValidator 只管 outputSchema），这里纯粹是发给模型的约束，
     * 省掉它模型就可能自造参数名，而自造的键在 handleCall 里会被静默忽略
     */
    public JsonSchema build() {
        // 将当前属性和必填项复制为只读结构，并关闭额外属性声明；这些描述不能代替服务端业务校验。
        return new JsonSchema("object", unmodifiableOrdered(properties), List.copyOf(required),
                false, null, null);
    }

    /**
     * 只读但保序：{@code Map.copyOf} 会打乱迭代顺序，参数顺序一乱，发给模型的 schema
     * 与确认卡的字段顺序每次启动都不一样
     */
    private static Map<String, Object> unmodifiableOrdered(Map<String, Object> source) {
        // 先复制到 LinkedHashMap 保留声明顺序，再封装为不可修改视图，避免构建结果随外部 Map 变化。
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private McpToolSchema put(Property property) {
        // 按属性名保存定义，发现重复声明时直接抛错，防止工具 Schema 中同名字段被悄悄覆盖。
        if (properties.put(property.name, property.toMap()) != null) {
            throw new IllegalStateException("MCP 工具参数重名: " + property.name);
        }
        return this;
    }

    /**
     * 单个参数，name 与 description 强制在构造时给出
     * <p>
     * description 是模型判断该不该填这个参数的唯一依据，做成链式可选就会有人漏掉
     */
    public static final class Property {

        private final String name;

        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Property(String name, String type, String description) {
            // 检查名称与说明均非空，再记录字段类型和描述，作为后续链式配置的基础。
            if (StrUtil.isBlank(name)) {
                throw new IllegalArgumentException("MCP 工具参数名不允许为空");
            }
            if (StrUtil.isBlank(description)) {
                throw new IllegalArgumentException("MCP 工具参数缺少描述: " + name);
            }
            this.name = name;
            attributes.put("type", type);
            attributes.put("description", description);
        }

        /**
         * 展示标签，人工确认卡按它渲染字段名，未声明时前端回落成参数名
         * <p>
         * 写工具的参数都该给：确认卡上让用户看 {@code receiverPhone} 不如看「收货手机号」
         */
        public Property title(String title) {
            // 为参数补充面向用户的展示标题，返回当前属性以继续链式配置。
            attributes.put("title", title);
            return this;
        }

        /**
         * 取值白名单
         */
        public Property options(List<String> options) {
            // 复制枚举候选列表后放入字段定义，防止调用者后续修改原列表影响 Schema。
            attributes.put("enum", List.copyOf(options));
            return this;
        }

        public Property defaultTo(Object defaultValue) {
            // 将默认值登记到 Schema，供模型理解缺省选择；运行时是否采用该值仍由执行器处理。
            attributes.put("default", defaultValue);
            return this;
        }

        private Map<String, Object> toMap() {
            // 复制并冻结当前属性定义，保持字段顺序，防止工具发布后属性被继续修改。
            return unmodifiableOrdered(attributes);
        }
    }
}
