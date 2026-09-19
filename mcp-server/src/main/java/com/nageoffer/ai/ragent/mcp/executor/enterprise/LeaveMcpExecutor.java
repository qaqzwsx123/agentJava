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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

@Slf4j
@Component
public class LeaveMcpExecutor {

    private static final String TOOL_ID = "leave_query";

    private static final String DEFAULT_EMPLOYEE = "张三";

    private static final List<String> LEAVE_TYPES = List.of("年假", "调休", "病假", "事假");
    private static final List<String> APPROVERS = List.of("李经理", "王总监", "赵主管");

    private LeaveProfile cachedProfile;
    private String cacheKey;

    @Bean
    public McpServerFeatures.SyncToolSpecification leaveToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("employeeName", "员工姓名或工号，不填则查询当前登录员工"))
                .optional(string("leaveType", "假期类型：年假、调休、病假、事假，默认年假")
                        .options(LEAVE_TYPES)
                        .defaultTo("年假"))
                .optional(integer("year", "查询年度，如 2026，不填则查询当前年度"))
                .optional(string("queryType", "查询类型：balance(余额与额度)、detail(请假明细)")
                        .options(List.of("balance", "detail"))
                        .defaultTo("balance"))
                .build();

        // 显式声明为只读工具，查询结果不会在此方法中触发业务写入。
        return Tool.builder()
                .name(TOOL_ID)
                .description("查询员工的假期额度与余额，支持年假、调休、病假、事假，"
                        + "年假返回全年额度、上年结转天数与截止日、已休天数和当前可用余额，也可返回请假明细")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 规范员工、假种与查询类型，从模拟档案取得余额及记录，再选择余额概览或明细展示。
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String employeeName = MapUtil.getStr(args, "employeeName");
            String leaveType = MapUtil.getStr(args, "leaveType");
            Integer year = MapUtil.getInt(args, "year");
            String queryType = MapUtil.getStr(args, "queryType");

            if (StrUtil.isBlank(employeeName)) employeeName = DEFAULT_EMPLOYEE;
            if (leaveType == null || !LEAVE_TYPES.contains(leaveType)) leaveType = "年假";
            if (year == null || year <= 0) year = LocalDate.now().getYear();
            if (StrUtil.isBlank(queryType)) queryType = "balance";

            LeaveProfile profile = getOrGenerateProfile(employeeName, year);

            String result = "detail".equals(queryType)
                    ? buildDetailResult(profile, leaveType)
                    : buildBalanceResult(profile, leaveType);

            log.info("MCP 工具调用完成, toolId={}, queryType={}, employeeName={}, leaveType={}, year={}, elapsed={}ms",
                    TOOL_ID, queryType, employeeName, leaveType, year, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("查询", e);
        }
    }

    private String buildBalanceResult(LeaveProfile profile, String leaveType) {
        // 按假种分派年假、调休或普通假余额的展示逻辑，各类假期采用各自的额度和有效期规则。
        return switch (leaveType) {
            case "调休" -> buildCompensatoryBalance(profile);
            case "病假", "事假" -> buildPlainBalance(profile, leaveType);
            default -> buildAnnualBalance(profile);
        };
    }

    private String buildAnnualBalance(LeaveProfile profile) {
        // 分别展示本年额度、结转额度、已用和过期结转，解释可用天数的组成及结转截止日。
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年年假余额】%n%n", profile.employeeName, profile.year));
        sb.append(String.format("入职日期: %s（工龄 %s）%n", profile.hireDate, profile.serviceLength()));
        sb.append(String.format("全年额度: %s 天，%d-01-01 一次性发放%n", days(profile.quota), profile.year));
        sb.append(String.format("上年结转: %s 天，截止 %s%n", days(profile.carriedIn), profile.carryDeadline()));
        sb.append(String.format("  结转部分已使用: %s 天%n", days(profile.carriedUsed())));
        sb.append(String.format("  结转部分逾期清零: %s 天%n", days(profile.carriedExpired())));
        sb.append(String.format("本年已休: %s 天，共 %d 次%n",
                days(profile.usedDays("年假")), profile.records("年假").size()));
        sb.append(String.format("当前可用: %s 天%n", days(profile.annualAvailable())));

        LeaveRecord last = profile.lastRecord("年假");
        if (last != null) {
            sb.append(String.format("最近一次年假: %s 至 %s，%s 天%n", last.startDate, last.endDate, days(last.days)));
        }
        return sb.toString().trim();
    }

    private String buildCompensatoryBalance(LeaveProfile profile) {
        // 展示调休剩余、已用和已过期天数，并列出仍有效的加班授予记录及失效日期。
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年调休余额】%n%n", profile.employeeName, profile.year));
        sb.append(String.format("当前可用: %s 天%n", days(profile.compensatoryAvailable())));
        sb.append(String.format("本年已休: %s 天%n", days(profile.usedDays("调休"))));
        sb.append(String.format("逾期清零: %s 天%n", days(profile.compensatoryExpired())));

        List<CompensatoryGrant> valid = profile.validGrants();
        sb.append("\n【调休来源】\n");
        if (valid.isEmpty()) {
            sb.append("  当前无未到期的调休额度\n");
        } else {
            valid.forEach(g -> sb.append(String.format("  %s 加班 %s 天，%s 到期，剩余 %s 天%n",
                    g.overtimeDate, days(g.grantedDays), g.expireDate, days(g.remainingDays))));
        }
        return sb.toString().trim();
    }

    private String buildPlainBalance(LeaveProfile profile, String leaveType) {
        // 对没有固定年度额度的假种展示已用天数与最近记录，不人为推算一个剩余额度。
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年%s使用情况】%n%n", profile.employeeName, profile.year, leaveType));
        sb.append(String.format("本年已休: %s 天，共 %d 次%n",
                days(profile.usedDays(leaveType)), profile.records(leaveType).size()));

        LeaveRecord last = profile.lastRecord(leaveType);
        if (last != null) {
            sb.append(String.format("最近一次: %s 至 %s，%s 天%n", last.startDate, last.endDate, days(last.days)));
        }
        sb.append(leaveType).append("不设年度额度，按实际发生逐次登记");
        return sb.toString().trim();
    }

    private String buildDetailResult(LeaveProfile profile, String leaveType) {
        // 筛选所选假种的模拟申请记录，按已有时间顺序展示日期、天数、状态和审批人。
        List<LeaveRecord> records = profile.records(leaveType);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %d 年%s请假明细】%n%n", profile.employeeName, profile.year, leaveType));
        if (records.isEmpty()) {
            sb.append("本年度暂无").append(leaveType).append("记录");
            return sb.toString();
        }
        sb.append(String.format("共 %d 条，合计 %s 天：%n%n", records.size(), days(profile.usedDays(leaveType))));
        for (int i = 0; i < records.size(); i++) {
            LeaveRecord r = records.get(i);
            sb.append(String.format("%d. %s 至 %s | %s 天 | %s | 审批人: %s%n",
                    i + 1, r.startDate, r.endDate, days(r.days), r.status, r.approver));
        }
        return sb.toString().trim();
    }

    private LeaveProfile getOrGenerateProfile(String employeeName, int year) {
        // 用员工、年份和当天日期组成缓存键；命中则复用档案，未命中重新生成模拟数据并替换缓存。
        String key = employeeName + "_" + year + "_" + LocalDate.now();
        if (cachedProfile != null && key.equals(cacheKey)) return cachedProfile;
        cachedProfile = generateProfile(employeeName, year);
        cacheKey = key;
        return cachedProfile;
    }

    private LeaveProfile generateProfile(String employeeName, int year) {
        // 按员工和年份设置随机种子，生成工龄、年度额度、结转及请假记录，再把已用调休分摊到授予记录。
        Random random = new Random(employeeName.hashCode() * 31L + year);

        LeaveProfile profile = new LeaveProfile();
        profile.employeeName = employeeName;
        profile.year = year;
        profile.serviceMonths = 24 + random.nextInt(72);
        profile.hireDate = LocalDate.now().minusMonths(profile.serviceMonths).format(DateTimeFormatter.ISO_LOCAL_DATE);
        profile.quota = Math.min(15, 10 + (profile.serviceMonths / 12 - 1) / 3);
        profile.carriedIn = 1 + random.nextInt(5);

        // 当年只生成到当前月份，历史年份可以覆盖全年，再分别模拟各假种的已用记录。
        int lastMonth = year >= LocalDate.now().getYear() ? LocalDate.now().getMonthValue() : 12;
        double annualBudget = profile.quota + profile.carriedIn;
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "年假",
                roundHalf(annualBudget * (0.35 + random.nextDouble() * 0.35))));
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "病假",
                roundHalf(random.nextDouble() * 4)));
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "事假",
                roundHalf(random.nextDouble() * 3)));

        // 先生成加班授予额度，再模拟调休消耗；consumeGrants 会实际修改这份内存档案的剩余额度。
        profile.grants.addAll(generateGrants(random, year, lastMonth));
        double compensatoryEarned = profile.grants.stream().mapToDouble(g -> g.grantedDays).sum();
        profile.leaveRecords.addAll(generateRecords(random, year, lastMonth, "调休",
                roundHalf(compensatoryEarned * random.nextDouble() * 0.6)));
        profile.consumeGrants();

        // 最后统一按开始日期排序，明细展示和最近一条记录的读取沿用这个顺序。
        profile.leaveRecords.sort((a, b) -> a.startDate.compareTo(b.startDate));
        return profile;
    }

    private List<LeaveRecord> generateRecords(Random random, int year, int lastMonth, String type, double totalDays) {
        // 生成给定假种的演示请假记录，天数使用半天粒度，日期、审批状态和审批人保存在内存中。
        List<LeaveRecord> records = new ArrayList<>();
        double remaining = totalDays;
        int month = 1;
        while (remaining >= 0.5 && month <= lastMonth) {
            double take = Math.min(remaining, 0.5 + 0.5 * random.nextInt(6));
            LocalDate start = LocalDate.of(year, month, 1).plusDays(random.nextInt(24));
            LeaveRecord record = new LeaveRecord();
            record.type = type;
            record.days = take;
            record.startDate = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
            record.endDate = start.plusDays((long) Math.max(0, Math.ceil(take) - 1)).format(DateTimeFormatter.ISO_LOCAL_DATE);
            record.status = "已批准";
            record.approver = APPROVERS.get(random.nextInt(APPROVERS.size()));
            records.add(record);
            remaining -= take;
            month += 1 + random.nextInt(3);
        }
        return records;
    }

    private List<CompensatoryGrant> generateGrants(Random random, int year, int lastMonth) {
        // 生成演示加班调休授予记录，设置授予天数、剩余天数和有效期，供后续抵扣与过期统计。
        List<CompensatoryGrant> grants = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            LocalDate overtime = LocalDate.of(year, 1 + random.nextInt(lastMonth), 1).plusDays(random.nextInt(27));
            CompensatoryGrant grant = new CompensatoryGrant();
            grant.overtimeDate = overtime.format(DateTimeFormatter.ISO_LOCAL_DATE);
            grant.expireDate = overtime.plusDays(90).format(DateTimeFormatter.ISO_LOCAL_DATE);
            grant.grantedDays = 0.5 + 0.5 * random.nextInt(4);
            grant.remainingDays = grant.grantedDays;
            grants.add(grant);
        }
        grants.sort((a, b) -> a.overtimeDate.compareTo(b.overtimeDate));
        return grants;
    }

    private static double roundHalf(double value) {
        // 将数值按半天为单位舍入，保证模拟的假期天数使用 0.5 天粒度。
        return Math.round(value * 2) / 2.0;
    }

    private static String days(double value) {
        // 按展示约定格式化天数，整数与半天值使用便于阅读的文本。
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }

    private static class LeaveProfile {
        String employeeName;
        int year;
        int serviceMonths;
        String hireDate;
        int quota;
        int carriedIn;
        final List<LeaveRecord> leaveRecords = new ArrayList<>();
        final List<CompensatoryGrant> grants = new ArrayList<>();

        String serviceLength() {
            // 把入职至今的月数拆成年和月，生成余额概览中的工龄说明。
            int years = serviceMonths / 12;
            int rest = serviceMonths % 12;
            return rest == 0 ? years + " 年" : years + " 年 " + rest + " 个月";
        }

        String carryDeadline() {
            // 返回当前档案年份的结转截止日文本，年假展示与抵扣判断共用这一日期约定。
            return LocalDate.of(year, 6, 30).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        List<LeaveRecord> records(String type) {
            // 从当前档案已有记录中筛出指定假种，供余额计算和明细展示使用。
            return leaveRecords.stream().filter(r -> r.type.equals(type)).toList();
        }

        LeaveRecord lastRecord(String type) {
            // 取得该假种已有有序记录中的最后一条，没有记录时返回对应的空值结果。
            List<LeaveRecord> records = records(type);
            return records.isEmpty() ? null : records.get(records.size() - 1);
        }

        double usedDays(String type) {
            // 汇总指定假种所有记录的天数，供年假和调休的已用统计复用。
            return records(type).stream().mapToDouble(r -> r.days).sum();
        }

        double carriedUsed() {
            // 只统计结转截止日之前结束的年假，优先视为消耗结转额度，且抵扣量不超过结转总额。
            String deadline = carryDeadline();
            double usedBeforeDeadline = records("年假").stream()
                    .filter(r -> r.endDate.compareTo(deadline) <= 0)
                    .mapToDouble(r -> r.days).sum();
            return Math.min(carriedIn, usedBeforeDeadline);
        }

        boolean carryDeadlinePassed() {
            // 以今天是否晚于当年六月三十日判断结转过期，截止日当天仍按未过期处理。
            return LocalDate.now().isAfter(LocalDate.of(year, 6, 30));
        }

        double carriedExpired() {
            // 超过结转期限后，未被使用的结转额度计为过期；截止前过期量为零。
            return carryDeadlinePassed() ? carriedIn - carriedUsed() : 0;
        }

        double annualAvailable() {
            // 先从本年额度扣除未由结转承担的已用年假，再加尚未过期的结转余额。
            double carriedAvailable = carryDeadlinePassed() ? 0 : carriedIn - carriedUsed();
            return quota - (usedDays("年假") - carriedUsed()) + carriedAvailable;
        }

        void consumeGrants() {
            // 把已用调休按授予记录顺序逐笔抵扣到 remainingDays，优先消耗前面的授予额度，直到已用量分配完。
            double remaining = usedDays("调休");
            for (CompensatoryGrant grant : grants) {
                if (remaining <= 0) break;
                double take = Math.min(remaining, grant.remainingDays);
                grant.remainingDays -= take;
                remaining -= take;
            }
        }

        List<CompensatoryGrant> validGrants() {
            // 筛选剩余天数大于零且截止日不早于今天的授予记录，失效日当天仍可用。
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            return grants.stream()
                    .filter(g -> g.remainingDays > 0 && g.expireDate.compareTo(today) >= 0)
                    .toList();
        }

        double compensatoryAvailable() {
            // 仅对仍有效的授予记录累加剩余天数，得到当前可用调休余额。
            return validGrants().stream().mapToDouble(g -> g.remainingDays).sum();
        }

        double compensatoryExpired() {
            // 累加已过期且尚未消耗完的授予余额，已用掉的天数不会再重复计入过期。
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            return grants.stream()
                    .filter(g -> g.remainingDays > 0 && g.expireDate.compareTo(today) < 0)
                    .mapToDouble(g -> g.remainingDays).sum();
        }
    }

    private static class LeaveRecord {
        String type;
        String startDate;
        String endDate;
        double days;
        String status;
        String approver;
    }

    private static class CompensatoryGrant {
        String overtimeDate;
        String expireDate;
        double grantedDays;
        double remainingDays;
    }
}
