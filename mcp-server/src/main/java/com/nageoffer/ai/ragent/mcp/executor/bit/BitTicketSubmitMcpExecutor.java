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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.dao.entity.TicketDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.TicketMapper;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 转人工工单，Agent 办不下去时的逃生门
 * <p>
 * 重复提交会多出一张工单，这是全套写工具里唯一没被唯一约束盖住的口子，已接受
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitTicketSubmitMcpExecutor {

    private static final String TOOL_ID = "submit_ticket";

    private static final String STATUS_PENDING = "待受理";

    private static final List<String> CATEGORIES = List.of("订单问题", "商品问题", "售后问题", "投诉建议", "其他");

    private static final int MAX_CONTENT_LENGTH = 500;

    private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TicketMapper ticketMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification submitTicketToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("category", "问题分类")
                        .title("问题分类")
                        .options(CATEGORIES))
                .required(string("content", "问题描述，把用户原话的诉求、涉及的订单号或商品型号、已经尝试过的处理一并写清楚，"
                        + "人工接手时不用再问一遍")
                        .title("问题描述"))
                .build();

        // 显式声明为写操作，客户端可据此识别可能改变业务状态的工具。
        return Tool.builder()
                .name(TOOL_ID)
                .description("为当前登录用户提交人工客服工单，提交后由人工在工作时间内跟进。"
                        + "只在自己确实办不下去时才用：用户明确要求转人工、现有工具都不覆盖该诉求、"
                        + "或同一问题已经处理失败。问题描述取自用户原话，不要代为编造或夸大")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 确认用户身份并校验分类和内容，限制内容长度后写入待处理工单，再返回生成的工单号。
        long startMs = System.currentTimeMillis();
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String category = StrUtil.trimToNull(MapUtil.getStr(args, "category"));
            String content = StrUtil.trimToNull(MapUtil.getStr(args, "content"));

            if (category == null || !CATEGORIES.contains(category)) {
                return BitToolSupport.rejected("问题分类缺失或不受支持，可选值：" + String.join("、", CATEGORIES));
            }
            if (content == null) {
                return BitToolSupport.rejected("问题描述为必填项，请把用户的诉求整理清楚后再提交");
            }
            // 限制入库内容长度，超出部分按现有规则截断，避免模型传入过长描述。
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            // 全部校验通过后生成工单号，将用户标识与待处理状态一起保存到业务库。
            String ticketNo = nextTicketNo();
            ticketMapper.insert(TicketDO.builder().ticketNo(ticketNo).userId(userId)
                    .category(category).content(content).status(STATUS_PENDING).build());

            log.info("MCP 工具调用完成, toolId={}, ticketNo={}, category={}, elapsed={}ms",
                    TOOL_ID, ticketNo, category, System.currentTimeMillis() - startMs);
            return McpToolResults.success(String.format("""
                    已提交人工客服工单

                    工单号: %s
                    分类: %s
                    状态: 待受理
                    描述: %s

                    人工客服会在工作时间内跟进，请把工单号告知用户以便后续查询""", ticketNo, category, content));
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("工单提交", e);
        }
    }

    private String nextTicketNo() {
        // 用当天日期和当前日期前缀的已有工单数生成序号，拼成新的业务工单编号。
        String prefix = "TK" + TICKET_DATE.format(LocalDate.now());
        long today = ticketMapper.countByNoPrefix(prefix + "%");
        return String.format("%s%04d", prefix, today + 1);
    }
}
