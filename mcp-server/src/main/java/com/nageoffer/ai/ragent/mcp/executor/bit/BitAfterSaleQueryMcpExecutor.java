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
import com.nageoffer.ai.ragent.mcp.dao.mapper.AfterSaleMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.AfterSaleDetailResult;
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

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 售后单进度查询
 * <p>
 * 三种问法一条 SQL：给售后单号查一张、给订单号查这一单的、都不给列出名下全部
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitAfterSaleQueryMcpExecutor {

    private static final String TOOL_ID = "query_after_sale";

    /**
     * 单个用户的售后单不会多，这个上限只是防御性截断
     */
    private static final int MAX_ROWS = 20;

    private final AfterSaleMapper afterSaleMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification queryAfterSaleToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("afterSaleNo", "售后单号，如 AS88237001"))
                .optional(string("orderNo", "订单号，查这一单下的全部售后单"))
                .build();

        // 显式声明为只读工具，查询结果不会在此方法中触发业务写入。
        return Tool.builder()
                .name(TOOL_ID)
                .description("查询当前登录用户的售后单进度，返回售后单号、对应订单与商品、售后类型、原因、"
                        + "当前状态与时间线。两个参数都不填则列出名下全部售后单。用户说不清是哪一单时先不带参数列一遍，"
                        + "再按用户指认的那张往下问")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 读取当前用户和可选的售后单号、订单号，在所属用户范围内查询，再将数据库结果整理为工具文本。
        long startMs = System.currentTimeMillis();
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String afterSaleNo = StrUtil.trimToNull(MapUtil.getStr(args, "afterSaleNo"));
            String orderNo = StrUtil.trimToNull(MapUtil.getStr(args, "orderNo"));

            List<AfterSaleDetailResult> rows = query(userId, afterSaleNo, orderNo);

            log.info("MCP 工具调用完成, toolId={}, afterSaleNo={}, orderNo={}, matched={}, elapsed={}ms",
                    TOOL_ID, afterSaleNo, orderNo, rows.size(), System.currentTimeMillis() - startMs);
            if (!rows.isEmpty()) {
                return McpToolResults.success(buildResult(rows));
            }
            if (afterSaleNo != null) {
                return BitToolSupport.notFound("售后单 " + afterSaleNo);
            }
            return orderNo != null
                    ? BitToolSupport.notFound("订单 " + orderNo + " 的售后单")
                    : McpToolResults.success("当前账号名下还没有售后单");
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("售后查询", e);
        }
    }

    private List<AfterSaleDetailResult> query(String userId, String afterSaleNo, String orderNo) {
        // 把用户和筛选条件交给关联订单的查询，限制最多返回的记录数，避免一次输出过长的售后历史。
        return afterSaleMapper.selectDetails(userId, afterSaleNo, orderNo, MAX_ROWS);
    }

    private String buildResult(List<AfterSaleDetailResult> rows) {
        // 逐条输出售后单号、关联商品、申请原因和处理进度；没有记录属于正常查询结果。
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【售后单 %d 张】%n", rows.size()));
        for (AfterSaleDetailResult row : rows) {
            sb.append(String.format("%n售后单号: %s%n", row.getAfterSaleNo()));
            sb.append(String.format("对应订单: %s%n", row.getOrderNo()));
            sb.append(String.format("商品: %s（%s）%n",
                    row.getSkuName() == null ? BitToolSupport.EMPTY_FIELD : row.getSkuName(), row.getSkuCode()));
            sb.append(String.format("类型: %s | 当前状态: %s%n", row.getType(), row.getStatus()));
            sb.append(String.format("原因: %s%n", row.getReason() == null ? BitToolSupport.EMPTY_FIELD : row.getReason()));
            sb.append(String.format("申请时间: %s%n", BitToolSupport.dateTime(row.getCreateTime())));
            sb.append(String.format("最近更新: %s%n", BitToolSupport.dateTime(row.getUpdateTime())));
            if (row.getFinishTime() != null) {
                sb.append(String.format("完成时间: %s%n", BitToolSupport.dateTime(row.getFinishTime())));
            }
        }
        return sb.toString().trim();
    }

}
