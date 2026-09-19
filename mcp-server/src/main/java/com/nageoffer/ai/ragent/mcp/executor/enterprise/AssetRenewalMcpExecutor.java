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

package com.nageoffer.ai.ragent.mcp.executor.enterprise;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * IT 资产换新工单提交，写操作示例
 * 资产编号只认名下资产查询给出的格式，逼模型先查再提，不要凭对话内容编号码
 */
@Slf4j
@Component
public class AssetRenewalMcpExecutor {

    private static final String TOOL_ID = "asset_renewal_submit";

    private static final String CURRENT_EMPLOYEE = "张三";

    private static final String APPROVER = "李经理";

    private static final List<String> REASONS = List.of("已达服役年限", "性能不足", "硬件故障", "损坏遗失");

    /**
     * 与名下资产查询的编号格式一致，两侧对不上会让协作链断在编号上
     */
    private static final Pattern ASSET_NO = Pattern.compile("^IT-(NB|PC|MT|DK|HD|MP)-\\d{4}-\\d{4}$");

    @Bean
    public McpServerFeatures.SyncToolSpecification assetRenewalToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("assetNo", "待换新的资产编号，如 IT-NB-2021-0473，需取自名下资产查询结果")
                        .title("资产编号"))
                .required(string("reason", "换新原因：已达服役年限、性能不足、硬件故障、损坏遗失")
                        .title("换新原因")
                        .options(REASONS))
                .optional(string("expectedModel", "期望机型，用户未指定则留空，不要代为推荐")
                        .title("期望机型"))
                .optional(string("remark", "补充说明，如故障现象，用户未提及则留空")
                        .title("补充说明"))
                .build();

        // 显式声明为写操作，客户端可据此识别可能改变业务状态的工具。
        return Tool.builder()
                .name(TOOL_ID)
                .description("为当前登录员工提交 IT 资产换新工单，提交后进入直属经理与 IT 资产管理员审批流程。"
                        + "本工具会产生真实业务副作用，资产编号必须先经名下资产查询确认，"
                        + "换新资格可用该查询的换新资格检查判定")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 规范资产编号并校验换新原因，通过后生成演示申请号和回执；当前不会写入真实资产或审批系统。
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String assetNo = normalize(MapUtil.getStr(args, "assetNo"));
            String reason = MapUtil.getStr(args, "reason");
            String expectedModel = MapUtil.getStr(args, "expectedModel");
            String remark = MapUtil.getStr(args, "remark");

            String rejection = validate(assetNo, reason);
            if (rejection != null) {
                log.info("MCP 工具调用被拒, toolId={}, reason={}, elapsed={}ms",
                        TOOL_ID, rejection, System.currentTimeMillis() - startMs);
                return McpToolResults.error(rejection);
            }

            String ticketNo = nextTicketNo();
            log.info("提交资产换新工单, toolId={}, ticketNo={}, employee={}, assetNo={}, reason={}, "
                            + "expectedModel={}, remark={}, elapsed={}ms",
                    TOOL_ID, ticketNo, CURRENT_EMPLOYEE, assetNo, reason, expectedModel, remark,
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildReceipt(ticketNo, assetNo, reason, expectedModel, remark));
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("提交", e);
        }
    }

    private String validate(String assetNo, String reason) {
        // 校验资产编号格式和支持的换新原因；这里按请求字段验证，不会查询真实资产归属或使用年限。
        if (StrUtil.isBlank(assetNo)) {
            return "资产编号为必填项，请先用名下资产查询确认待换新设备的编号";
        }
        if (!ASSET_NO.matcher(assetNo).matches()) {
            return "资产编号 " + assetNo + " 格式不正确，正确形如 IT-NB-2021-0473，请先用名下资产查询取得真实编号";
        }
        if (reason == null || !REASONS.contains(reason)) {
            return "换新原因缺失或不受支持，请确认后重新提交，可选值：" + String.join("、", REASONS);
        }
        return null;
    }

    private String buildReceipt(String ticketNo, String assetNo, String reason,
                                String expectedModel, String remark) {
        // 使用固定演示员工和审批人组装回执，补充可选型号与备注，供用户核对本次模拟申请内容。
        StringBuilder sb = new StringBuilder();
        sb.append("【资产换新工单已提交】\n\n");
        sb.append(String.format("工单号: %s%n", ticketNo));
        sb.append(String.format("申请人: %s%n", CURRENT_EMPLOYEE));
        sb.append(String.format("资产编号: %s%n", assetNo));
        sb.append(String.format("换新原因: %s%n", reason));
        sb.append(String.format("期望机型: %s%n", StrUtil.isBlank(expectedModel) ? "未指定，由 IT 按标准配置分配" : expectedModel));
        if (StrUtil.isNotBlank(remark)) {
            sb.append(String.format("补充说明: %s%n", remark));
        }
        sb.append(String.format("当前状态: 待审批，审批人 %s%n", APPROVER));
        sb.append("经理通过后转 IT 资产管理员配机，原设备需在新机到位后 5 个工作日内归还");
        return sb.toString();
    }

    private String nextTicketNo() {
        // 将当天日期与四位随机数拼成演示换新单号，这里没有数据库编号分配或唯一性校验。
        return String.format("AR-%s-%04d", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                RandomUtil.randomInt(10000));
    }

    private static String normalize(String assetNo) {
        // 去除首尾空白并统一字母大小写，使同一资产编号的不同输入形式使用一致格式。
        return StrUtil.isBlank(assetNo) ? null : StrUtil.trim(assetNo).toUpperCase();
    }
}
