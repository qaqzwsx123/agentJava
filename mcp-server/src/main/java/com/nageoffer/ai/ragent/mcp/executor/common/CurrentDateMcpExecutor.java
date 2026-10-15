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

package com.nageoffer.ai.ragent.mcp.executor.common;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 当前日期 MCP 工具，供 Agent 解析“今天、明天、下周”等相对日期。
 */
@Slf4j
@Component
public class CurrentDateMcpExecutor {

    private static final String TOOL_ID = "current_date";

    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter WEEKDAY_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE);

    private final Clock clock;

    public CurrentDateMcpExecutor() {
        // 默认构造使用 UTC 时钟作为时间来源；真正输出哪一天由 handleCall 选择的时区决定。
        this(Clock.systemUTC());
    }

    CurrentDateMcpExecutor(Clock clock) {
        // 接受可替换的 Clock 并检查非空，便于测试固定日期以及处理不同时区的日期边界。
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification currentDateToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("timezone", "IANA 时区名称，如 Asia/Shanghai、UTC；不填默认 Asia/Shanghai")
                        .defaultTo(DEFAULT_ZONE_ID.getId()))
                .build();

        // 显式声明为只读工具，查询结果不会在此方法中触发业务写入。
        return Tool.builder()
                .name(TOOL_ID)
                .description("查询指定时区的当前公历日期和星期，默认使用 Asia/Shanghai；"
                        + "需要理解今天、明天、后天、下周等相对日期时先调用本工具")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        // 缺省使用上海时区，指定值则解析为 ZoneId；用注入时钟计算当地日期，非法时区返回明确的参数错误。
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String timezone = MapUtil.getStr(args, "timezone");
            ZoneId zoneId = StrUtil.isBlank(timezone)
                    ? DEFAULT_ZONE_ID
                    : ZoneId.of(StrUtil.trim(timezone));
            LocalDate currentDate = LocalDate.now(clock.withZone(zoneId));

            String result = String.format("当前日期: %s%n星期: %s%n时区: %s",
                    currentDate,
                    currentDate.format(WEEKDAY_FORMATTER),
                    zoneId.getId());
            log.info("MCP 工具调用完成, toolId={}, timezone={}, currentDate={}, elapsed={}ms",
                    TOOL_ID, zoneId, currentDate, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (DateTimeException e) {
            return McpToolResults.error("无效的时区名称，请使用 IANA 时区，如 Asia/Shanghai、UTC");
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("日期查询", e);
        }
    }
}
