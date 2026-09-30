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

import java.util.Map;

/**
 * 提示词模板渲染器
 */
public final class PromptTemplateRenderer {
    // 摄取提示词模板渲染工具：根据变量映射替换模板中的占位符，为文档增强和分块富集等模型调用构造具体提示词。

    private PromptTemplateRenderer() {
        // 模板替换通过静态方法使用，私有构造器阻止实例化工具类。
    }

    /**
     * 逐项替换双花括号变量，null 变量替换为空字符串，未提供的变量保持原样。
     */
    public static String render(String template, Map<String, Object> variables) {
        // 逐个替换双大括号变量，null 值替换为空串；没有提供的变量保持模板原样。
        if (template == null || template.isBlank()) {
            return template;
        }
        String out = template;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String key = "{{" + entry.getKey() + "}}";
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                out = out.replace(key, value);
            }
        }
        return out;
    }
}
