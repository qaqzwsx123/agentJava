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
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 各 executor 共用的取参与结果封装
 */
@Slf4j
public final class McpToolResults {

    /**
     * 调用方在 {@code _meta} 里透传的当前登录用户
     * <p>
     * 键名与 Agent 发送的用户身份元数据保持一致；服务端独立启动，不依赖 Agent 模块
     */
    public static final String META_USER_ID = "com.nageoffer.ragent/userId";

    private McpToolResults() {
        // 结果构造与参数读取通过静态方法复用，不允许创建工具类实例。
    }

    /**
     * 取调用方透传的用户标识，取不到返回 null
     * <p>
     * {@code _meta} 是 client-supplied 的扩展点，规范明确服务端不得据此做认证决策。本进程只绑本机、
     * 只服务同一个应用，用它圈定数据范围成立；哪天要对外开放，得换成 OAuth 而不是加固这里
     */
    public static String userId(CallToolRequest request) {
        // 从 MCP 请求的 _meta 中读取约定用户标识，只接受非空白字符串并去除首尾空白；缺失时返回 null。
        Map<String, Object> meta = request == null || request.meta() == null ? Map.of() : request.meta();
        Object value = meta.get(META_USER_ID);
        return value instanceof String userId && StrUtil.isNotBlank(userId) ? StrUtil.trim(userId) : null;
    }

    /**
     * 用户态工具取不到身份时的统一回绝
     * <p>
     * 不许降级成「按 null 查」：那条路不报错、返回空，看起来像「你没有订单」，是静默失败里最难查的一种。
     * 文案不提「重新登录」——用户本来就在登录态，断的是调用链，让他去重登只会把排查引到死路上；
     * 真因记进服务端日志并带上工具名，好把「整条链断了」和「这一个工具漏带身份」分开
     */
    public static CallToolResult identityRequired(String toolId) {
        // 记录缺失身份的工具名称，并返回统一错误文本，调用方据此在查询或写入前结束处理。
        log.warn("调用方未透传登录身份，已回绝本次调用, toolId={}", toolId);
        return error("系统暂时无法确认你的身份，这次没有执行，请稍后重试");
    }

    /**
     * 兜底异常的统一出口，{@code action} 是动作名如「下单」「订单查询」
     * <p>
     * 底层异常的原文只进服务端日志：{@code e.getMessage()} 里是 SQL 片段、连接串、空指针栈顶这类实现细节，
     * 拼进返回值就等于交给模型，而模型会把它当业务结论转述给用户。调用方要的是「这次没成功」，
     * 不是「哪一层怎么崩的」——后者在日志里，且日志比返回值更全。
     * {@link McpToolException} 是例外：那是抛出方自己拼的文案，已声明可以给模型看
     */
    public static CallToolResult failure(String action, Exception e) {
        // 仅对明确的业务异常透出原因，其他异常返回统一失败提示；详细堆栈由调用方记录。
        return e instanceof McpToolException
                ? error(action + "失败: " + e.getMessage())
                : error(action + "失败，请稍后重试");
    }

    public static CallToolResult success(String text) {
        // 把业务文本包装成 TextContent，并将 isError 标为 false，作为正常工具结果返回。
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    public static CallToolResult error(String message) {
        // 把失败说明包装成 TextContent，并将 isError 标为 true，供模型识别本次调用失败。
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    public static Map<String, Object> args(CallToolRequest request) {
        // 请求参数为空时返回空 Map，让执行器可以统一按键读取并自行处理默认值。
        return request.arguments() != null ? request.arguments() : Map.of();
    }

    public static LocalDate parseDate(String value) {
        // 对非空文本按 ISO 日期格式解析，非法日期返回 null，交给具体业务校验给出适合的提示。
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(StrUtil.trim(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
