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
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

@Slf4j
@Component
public class AssetMcpExecutor {

    private static final String TOOL_ID = "asset_query";

    private static final String DEFAULT_EMPLOYEE = "张三";

    private static final List<String> CATEGORIES = List.of("笔记本电脑", "台式机", "显示器", "扩展坞", "移动硬盘", "测试手机");
    private static final List<String> STATUSES = List.of("在用", "维修中", "借用中", "待归还");

    private static final Map<String, Integer> SERVICE_LIMIT_MONTHS = Map.of(
            "笔记本电脑", 48,
            "台式机", 60,
            "显示器", 72,
            "扩展坞", 36,
            "移动硬盘", 36,
            "测试手机", 36
    );
    private static final Map<String, String> CATEGORY_CODES = Map.of(
            "笔记本电脑", "NB",
            "台式机", "PC",
            "显示器", "MT",
            "扩展坞", "DK",
            "移动硬盘", "HD",
            "测试手机", "MP"
    );
    private static final Map<String, List<String>> MODELS = Map.of(
            "笔记本电脑", List.of("ThinkPad X1 Carbon 32G/1T", "MacBook Pro 14 32G/1T", "Dell XPS 15 32G/1T"),
            "台式机", List.of("Dell OptiPlex 7010 16G/512G", "Lenovo ThinkCentre M720 16G/512G"),
            "显示器", List.of("Dell U2723QE 27 寸", "LG 27UP850 27 寸", "AOC Q24V4 24 寸"),
            "扩展坞", List.of("Dell WD19S 130W", "Lenovo ThinkPad Hybrid Dock"),
            "移动硬盘", List.of("Samsung T7 1T", "WD Elements 2T"),
            "测试手机", List.of("小米 13 测试机", "华为 Mate 60 测试机", "iPhone 14 测试机")
    );
    private static final List<String> LOCATIONS = List.of(
            "西溪园区A楼7F", "西溪园区B楼3F", "紫金港园区C楼5F", "居家办公"
    );

    private List<AssetRecord> cachedData;
    private String cacheKey;

    @Bean
    public McpServerFeatures.SyncToolSpecification assetToolSpecification() {
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
                .optional(string("assetType", "资产类别筛选：笔记本电脑、台式机、显示器、扩展坞、移动硬盘、测试手机，不填则查询全部类别")
                        .options(CATEGORIES))
                .optional(string("status", "资产状态筛选：在用、维修中、借用中、待归还，不填则查询全部状态")
                        .options(STATUSES))
                .optional(string("queryType", "查询类型：summary(名下资产汇总)、list(资产明细)、renewal(换新资格检查)")
                        .options(List.of("summary", "list", "renewal"))
                        .defaultTo("summary"))
                .optional(integer("limit", "返回记录数限制，默认20")
                        .defaultTo(20))
                .build();

        // 显式声明为只读工具，查询结果不会在此方法中触发业务写入。
        return Tool.builder()
                .name(TOOL_ID)
                .description("查询员工名下的公司 IT 资产，包括笔记本电脑、台式机、显示器、扩展坞等，支持按类别和状态筛选，"
                        + "可返回资产汇总、资产明细以及是否达到换新年限")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 规范员工、查询类型和条数，取得该员工的模拟资产后筛选，再按概览、清单或换新资格组织结果。
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String employeeName = MapUtil.getStr(args, "employeeName");
            String assetType = MapUtil.getStr(args, "assetType");
            String status = MapUtil.getStr(args, "status");
            String queryType = MapUtil.getStr(args, "queryType");
            Integer limit = MapUtil.getInt(args, "limit");

            if (StrUtil.isBlank(employeeName)) employeeName = DEFAULT_EMPLOYEE;
            if (StrUtil.isBlank(queryType)) queryType = "summary";
            if (limit == null || limit <= 0) limit = 20;

            List<AssetRecord> allData = getOrGenerateData(employeeName);
            List<AssetRecord> filtered = filterData(allData, assetType, status);

            String result = switch (queryType) {
                case "list" -> buildListResult(filtered, employeeName, assetType, status, limit);
                case "renewal" -> buildRenewalResult(filtered, employeeName);
                default -> buildSummaryResult(filtered, employeeName, assetType, status);
            };

            log.info("MCP 工具调用完成, toolId={}, queryType={}, employeeName={}, assetType={}, elapsed={}ms",
                    TOOL_ID, queryType, employeeName, assetType, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("查询", e);
        }
    }

    private String buildSummaryResult(List<AssetRecord> data, String employeeName, String assetType, String status) {
        // 汇总模拟资产的状态、品类和达到使用年限的数量，帮助用户先了解名下资产分布。
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(employeeName).append(" 名下 IT 资产汇总】\n\n");
        List<String> filters = new ArrayList<>();
        if (assetType != null) filters.add("类别: " + assetType);
        if (status != null) filters.add("状态: " + status);
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");

        if (data.isEmpty()) {
            sb.append("名下暂无符合条件的资产记录");
            return sb.toString();
        }

        sb.append(String.format("资产总数: %d 件\n", data.size()));

        // 同一份筛选结果分别按状态和品类分组，概览计数保持相同数据范围。
        Map<String, Long> byStatus = data.stream()
                .collect(Collectors.groupingBy(r -> r.status, Collectors.counting()));
        sb.append("状态分布: ").append(byStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + e.getValue() + " 件")
                .collect(Collectors.joining("，"))).append("\n");

        sb.append("\n【按类别】\n");
        Map<String, Long> byCategory = data.stream()
                .collect(Collectors.groupingBy(r -> r.category, Collectors.counting()));
        byCategory.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(String.format("  %s: %d 件\n", e.getKey(), e.getValue())));

        sb.append("\n【资产清单】\n");
        data.forEach(r -> sb.append(String.format("  %s | %s | %s | 领用于 %s | 已服役 %s\n",
                r.assetNo, r.category, r.status, r.receiveDate, describeMonths(r.servedMonths()))));

        // 换新资格按已服役月数与品类年限比较；这里只列出符合年限的候选资产。
        List<AssetRecord> renewable = data.stream().filter(AssetRecord::reachedServiceLimit).toList();
        sb.append("\n【换新提示】\n");
        if (renewable.isEmpty()) {
            sb.append("  名下资产均未达到服役年限\n");
        } else {
            sb.append(String.format("  已达服役年限: %d 件（%s）\n", renewable.size(),
                    renewable.stream().map(r -> r.category + " " + r.assetNo).collect(Collectors.joining("、"))));
        }
        return sb.toString().trim();
    }

    private String buildListResult(List<AssetRecord> data, String employeeName,
                                   String assetType, String status, int limit) {
        // 按限额逐条展示资产编号、型号、领用日期和使用时长，超过条数的部分只提示未全部列出。
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(employeeName).append(" 名下 IT 资产明细】\n\n");
        List<String> filters = new ArrayList<>();
        if (assetType != null) filters.add("类别: " + assetType);
        if (status != null) filters.add("状态: " + status);
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");

        if (data.isEmpty()) {
            sb.append("名下暂无符合条件的资产记录");
            return sb.toString();
        }

        List<AssetRecord> records = data.stream().limit(limit).toList();
        sb.append(String.format("共 %d 件，显示 %d 件：\n\n", data.size(), records.size()));
        for (int i = 0; i < records.size(); i++) {
            AssetRecord r = records.get(i);
            sb.append(String.format("%d. %s | %s\n", i + 1, r.assetNo, r.category));
            sb.append(String.format("   机型: %s | 状态: %s\n", r.model, r.status));
            sb.append(String.format("   领用日期: %s | 已服役: %s | 服役年限: %d 年%s\n",
                    r.receiveDate, describeMonths(r.servedMonths()), r.serviceLimitMonths / 12,
                    r.reachedServiceLimit() ? "（已达）" : ""));
            sb.append(String.format("   使用地点: %s\n\n", r.location));
        }
        return sb.toString().trim();
    }

    private String buildRenewalResult(List<AssetRecord> data, String employeeName) {
        // 按当前资产的品类使用年限判断可否换新，仅输出资格说明，不在查询过程中创建换新申请。
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(employeeName).append(" 资产换新资格检查】\n\n");
        if (data.isEmpty()) {
            sb.append("名下暂无资产记录");
            return sb.toString();
        }
        for (AssetRecord r : data) {
            int served = r.servedMonths();
            sb.append(String.format("%s | %s | %s\n", r.assetNo, r.category, r.model));
            sb.append(String.format("   已服役 %s，服役年限 %d 年，", describeMonths(served), r.serviceLimitMonths / 12));
            if (r.reachedServiceLimit()) {
                sb.append("已达年限，可提交换新工单\n");
            } else {
                sb.append(String.format("距可申请还有 %d 个月\n", r.serviceLimitMonths - served));
            }
        }
        sb.append("\n本结果仅为年限判定，换新还需本人提交工单并经直属经理审批");
        return sb.toString().trim();
    }

    private List<AssetRecord> filterData(List<AssetRecord> data, String assetType, String status) {
        // 按请求提供的品类和状态筛选资产；没有指定的条件不参与过滤。
        return data.stream()
                .filter(r -> assetType == null || assetType.equals(r.category))
                .filter(r -> status == null || status.equals(r.status))
                .toList();
    }

    private List<AssetRecord> getOrGenerateData(String employeeName) {
        // 用员工和当天日期作为缓存键，同一天重复查询复用模拟数据；缓存未命中时重新生成并替换缓存。
        String key = employeeName + "_" + LocalDate.now();
        if (cachedData != null && key.equals(cacheKey)) return cachedData;
        cachedData = generateMockData(employeeName);
        cacheKey = key;
        return cachedData;
    }

    private List<AssetRecord> generateMockData(String employeeName) {
        // 用员工信息设置随机种子，生成电脑、显示器等模拟资产，按领用时间排序后供各查询类型复用。
        Random random = new Random(employeeName.hashCode());
        String location = LOCATIONS.get(random.nextInt(LOCATIONS.size()));

        List<AssetRecord> records = new ArrayList<>();
        records.add(newRecord(random, "笔记本电脑", 49 + random.nextInt(11), "在用", location));
        records.add(newRecord(random, "显示器", 18 + random.nextInt(24), "在用", location));
        records.add(newRecord(random, "扩展坞", 18 + random.nextInt(15), "在用", location));
        if (random.nextBoolean()) {
            records.add(newRecord(random, "移动硬盘", 38 + random.nextInt(12), "在用", location));
        }
        if (random.nextBoolean()) {
            records.add(newRecord(random, "测试手机", 8 + random.nextInt(20), "借用中", location));
        }
        records.sort((a, b) -> a.receiveDate.compareTo(b.receiveDate));
        return records;
    }

    private AssetRecord newRecord(Random random, String category, int servedMonths, String status, String location) {
        // 根据资产品类选择型号和使用年限，生成编号、领用日期等字段，返回一条内存中的模拟记录。
        AssetRecord record = new AssetRecord();
        record.category = category;
        record.model = MODELS.get(category).get(random.nextInt(MODELS.get(category).size()));
        record.status = status;
        record.location = location;
        record.serviceLimitMonths = SERVICE_LIMIT_MONTHS.get(category);
        LocalDate date = LocalDate.now().minusMonths(servedMonths).minusDays(random.nextInt(28));
        record.receiveDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        record.assetNo = String.format("IT-%s-%d-%04d", CATEGORY_CODES.get(category), date.getYear(),
                1 + random.nextInt(9999));
        return record;
    }

    private static String describeMonths(int months) {
        // 把已使用月数换算成年、月的可读文本，供资产列表和换新判断说明复用。
        int years = months / 12;
        int rest = months % 12;
        if (years == 0) return rest + " 个月";
        if (rest == 0) return years + " 年";
        return years + " 年 " + rest + " 个月";
    }

    private static class AssetRecord {
        String assetNo;
        String category;
        String model;
        String status;
        String receiveDate;
        String location;
        int serviceLimitMonths;

        int servedMonths() {
            // 先计算领用日至今天相差的月份，再根据日号修正尚未满一个月的部分。
            LocalDate received = LocalDate.parse(receiveDate);
            LocalDate now = LocalDate.now();
            return (now.getYear() - received.getYear()) * 12 + now.getMonthValue() - received.getMonthValue()
                    - (now.getDayOfMonth() < received.getDayOfMonth() ? 1 : 0);
        }

        boolean reachedServiceLimit() {
            // 将实际已使用月数与该品类配置的使用年限（月）比较，达到或超过即标记为可按年限换新。
            return servedMonths() >= serviceLimitMonths;
        }
    }
}
