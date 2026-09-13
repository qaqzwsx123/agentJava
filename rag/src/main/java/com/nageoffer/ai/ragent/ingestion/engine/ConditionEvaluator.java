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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 条件评估器
 * 用于根据给定的 IngestionContext 上下文和 JsonNode 格式的条件配置来评估条件是否满足
 */
@Component
public class ConditionEvaluator {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 先校验条件结构，再执行条件计算，结构无效时直接返回 false。
     */
    public boolean evaluate(IngestionContext context, JsonNode condition) {
        return isStructurallyValid(condition) && evaluateValid(context, condition);
    }

    /**
     * 递归校验空条件、布尔值、表达式及 all、any、not、field 规则的结构，未知结构返回 false。
     */
    private boolean isStructurallyValid(JsonNode condition) {
        if (condition == null || condition.isNull() || condition.isBoolean() || condition.isTextual()) {
            return true;
        }
        if (!condition.isObject()) {
            return false;
        }
        // 组合条件先递归验结构，防止对格式错误的 not 子条件取反后意外放行。
        if (condition.has("all")) {
            return isStructurallyValidGroup(condition.get("all"));
        }
        if (condition.has("any")) {
            return isStructurallyValidGroup(condition.get("any"));
        }
        if (condition.has("not")) {
            return isStructurallyValid(condition.get("not"));
        }
        if (condition.has("field")) {
            return StringUtils.hasText(condition.path("field").asText(null));
        }
        return false;
    }

    /**
     * 要求组合条件是数组，并检查每个子条件结构；任一无效则整组无效。
     */
    private boolean isStructurallyValidGroup(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!isStructurallyValid(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算已通过结构检查的条件：空条件放行，布尔值直返，文本走 SpEL，对象按组合或字段规则分派。
     */
    private boolean evaluateValid(IngestionContext context, JsonNode condition) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (condition.isBoolean()) {
            return condition.asBoolean();
        }
        if (condition.isTextual()) {
            return evalSpel(context, condition.asText());
        }
        if (condition.isObject()) {
            if (condition.has("all")) {
                return evalAll(context, condition.get("all"));
            }
            if (condition.has("any")) {
                return evalAny(context, condition.get("any"));
            }
            if (condition.has("not")) {
                return !evaluateValid(context, condition.get("not"));
            }
            if (condition.has("field")) {
                return evalRule(context, condition);
            }
        }
        return false;
    }

    /**
     * 按顺序短路计算 all 条件，任一为 false 即返回 false；空数组按逻辑与返回 true。
     */
    private boolean evalAll(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!evaluateValid(context, item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按顺序短路计算 any 条件，任一为 true 即返回 true；空数组返回 false。
     */
    private boolean evalAny(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (evaluateValid(context, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取上下文字段及右侧配置值，缺省操作符为 eq，再交给统一比较方法。
     */
    private boolean evalRule(IngestionContext context, JsonNode node) {
        String field = node.path("field").asText(null);
        if (!StringUtils.hasText(field)) {
            return false;
        }
        String operator = node.path("operator").asText("eq");
        JsonNode valueNode = node.get("value");
        // 左侧取实际执行上下文，右侧取配置常量，两侧再按操作符比较。
        Object left = readField(context, field);
        Object right = valueNode == null ? null : objectMapper.convertValue(valueNode, Object.class);
        return compare(left, right, operator);
    }

    /**
     * 通过 BeanWrapper 读取上下文中的属性路径，属性不存在或读取失败时返回 null。
     */
    private Object readField(IngestionContext context, String path) {
        try {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(context);
            return wrapper.getPropertyValue(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按操作符分派相等、包含、正则、数值及存在性判断，未识别操作符按相等处理。
     */
    private boolean compare(Object left, Object right, String operator) {
        return switch (operator.toLowerCase()) {
            case "ne" -> !Objects.equals(normalize(left), normalize(right));
            case "in" -> in(left, right);
            case "contains" -> contains(left, right);
            case "regex" -> regex(left, right);
            case "gt" -> compareNumbers(left, right, result -> result > 0);
            case "gte" -> compareNumbers(left, right, result -> result >= 0);
            case "lt" -> compareNumbers(left, right, result -> result < 0);
            case "lte" -> compareNumbers(left, right, result -> result <= 0);
            case "exists" -> left != null;
            case "not_exists" -> left == null;
            default -> Objects.equals(normalize(left), normalize(right));
        };
    }

    /**
     * 优先使用左右任一列表的成员判断，两侧都不是列表时按规范化后的相等关系比较。
     */
    private boolean in(Object left, Object right) {
        if (right instanceof List<?> list) {
            return list.contains(left);
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return Objects.equals(normalize(left), normalize(right));
    }

    /**
     * 支持字符串子串或列表成员判断；任一操作数为空或左侧类型不支持时返回 false。
     */
    private boolean contains(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String ls) {
            return ls.contains(String.valueOf(right));
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return false;
    }

    /**
     * 把两侧转成字符串执行整串正则匹配，空操作数返回 false。
     */
    private boolean regex(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(left).matches(String.valueOf(right));
    }

    /**
     * 将两侧转为有限浮点数，转换成功后用调用方谓词判断大小关系，否则返回 false。
     */
    private boolean compareNumbers(Object left, Object right, IntPredicate predicate) {
        Double l = toDouble(left);
        Double r = toDouble(right);
        if (l == null || r == null) {
            return false;
        }
        return predicate.test(Double.compare(l, r));
    }

    /**
     * 兼容 Number 和数字文本，排除 NaN 与无穷值，转换失败返回 null。
     */
    private Double toDouble(Object value) {
        Double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else {
            try {
                number = Double.parseDouble(String.valueOf(value));
            } catch (Exception e) {
                return null;
            }
        }
        return Double.isFinite(number) ? number : null;
    }

    /**
     * 比较前去除字符串首尾空白，其他类型保持原值。
     */
    private Object normalize(Object value) {
        if (value instanceof String s) {
            return s.trim();
        }
        return value;
    }

    /**
     * 以摄入上下文为根对象并绑定 ctx 变量计算 SpEL，仅 Boolean true 放行，表达式执行异常返回 false。
     */
    private boolean evalSpel(IngestionContext context, String expression) {
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext(context);
            ctx.setVariable("ctx", context);
            Boolean result = parser.parseExpression(expression).getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
