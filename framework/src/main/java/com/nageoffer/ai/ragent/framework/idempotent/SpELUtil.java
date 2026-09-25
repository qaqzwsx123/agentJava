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

package com.nageoffer.ai.ragent.framework.idempotent;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * SpEL 表达式解析工具
 */
public final class SpELUtil {

    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 解析 SpEL 表达式（如果存在的话），否则直接返回原字符串。
     *
     * @param spEl       注解里配置的 key 字符串（比如 "10086" 或 "T(...).getUserId()"）
     * @param method     当前被拦截的方法（用于 SpEL 上下文）
     * @param contextObj 当前方法的参数数组（用于从参数中取值，比如 #request.id）
     * @return           解析后的真实值（Object 类型），或者原样返回的固定字符串
     */
    public static Object parseKey(String spEl, Method method, Object[] contextObj) {

        // 1. 定义 SpEL 的“特征密码”。
        // "#" 代表取方法参数（如 #userId）；
        // "T(" 代表调用静态类（如 T(java.lang.Math).random()）。
        List<String> spELFlag = ListUtil.of("#", "T(");

        // 2. 用 Stream 流去匹配：看看传进来的 spEl 字符串里，有没有包含上面这两个特征。
        // 找到第一个包含的特征就返回，找不到就返回一个空的 Optional。
        Optional<String> optional = spELFlag.stream().filter(spEl::contains).findFirst();

        // 3. 如果 optional 里有值，说明这不是一个普通的字符串，而是一个真正的 SpEL 表达式！
        if (optional.isPresent()) {
            // 4. 把方法、参数等上下文塞进真正的解析器（parse 方法），让它计算出真实的业务值。
            // 比如传入 "T(...UserContext).getUserId()"，这里就会返回 Integer 10086。
            return parse(spEl, method, contextObj);
        }

        // 5. 如果没找到任何特征标记，说明开发者写的就是个固定的死字符串（比如 "lock:global"）。
        // 不需要解析，直接把原字符串扔回去。
        return spEl;
    }

    /**
     * 转换参数为字符串（其实是执行 SpEL 表达式并返回计算结果）
     *
     * @param spEl       spEl 表达式，例如 "T(...).getUserId()" 或 "#userId"
     * @param method     当前被拦截的方法（用于反射获取参数名）
     * @param contextObj 当前方法的实际参数数组（例如 [request, 10086]）
     * @return 解析出来的真实值（可能是 String、Integer 等 Object 类型）
     */
    public static Object parse(String spEl, Method method, Object[] contextObj) {

        // 1. 编译表达式：把字符串 "T(...).getUserId()" 解析成 Spring 底层可执行的 Expression 对象。
        // (类似于把 SQL 字符串编译成 PreparedStatement 的预编译过程，提高执行效率)
        Expression exp = EXPRESSION_PARSER.parseExpression(spEl);

        // 2. 获取形参名：通过 Spring 的 ParameterNameDiscoverer 拿到方法签名里的参数名。
        // 比如方法叫 chat(String question, String conversationId)，这里就能拿到 ["question", "conversationId"]。
        // 【注意坑点】：这要求你的 Java 代码在编译时开启了 -parameters 参数，否则这里拿到的可能是 arg0, arg1。
        String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(method);

        // 3. 创建 SpEL 的“运行上下文”：可以理解为一个 Map，用来存放变量。
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 4. 绑定变量：如果成功拿到了形参名，就把【形参名】和【实际传进来的值】一一对应起来，塞进上下文。
        // 比如：params[0] 是 "question"，contextObj[0] 是 "你好"，那就 context.setVariable("question", "你好")。
        // 这样你在表达式里写 "#question" 时，SpEL 才能知道去哪里找这个值。
        if (ArrayUtil.isNotEmpty(params)) {
            for (int len = 0; len < params.length; len++) {
                // 形参名与实参按位置配对，表达式中的 #参数名 由此获得实际业务值。
                context.setVariable(params[len], contextObj[len]);
            }
        }

        // 5. 求值：表达式编译好了，上下文里的变量也备齐了，现在开始真正执行计算！
        // 如果表达式是 "T(...).getUserId()"，这一步会触发反射调用静态方法，返回 10086。
        // 如果表达式是 "#userId"，这一步会直接从 context 中查出 10086。
        // 最终把计算出来的结果返回给外层调用者。
        return exp.getValue(context);
    }
}
