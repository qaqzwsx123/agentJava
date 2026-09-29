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

package com.nageoffer.ai.ragent.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewGroupVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewKpiVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendSeriesVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "workflow", matchIfMissing = true) // 如果配置文件里没写这个配置，是否默认生效
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String NO_DOC_REPLY = "未检索到与问题相关的文档内容。";
    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_HOUR = "hour";
    private static final long SLOW_LATENCY_THRESHOLD_MS = 20000L;
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final RagTraceRunMapper traceRunMapper;

    /**
     * 统计 Workflow/RAG 用户、会话、消息和活跃度概览。
     * 默认窗口为 24 小时，并计算前一等长窗口的对比值；这里不聚合 Agent 独立会话表。
     */
    @Override
    public DashboardOverviewVO loadOverview(String window) {
        // 先统一当前与对比时间范围，再对相同口径的业务表分别计数。
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));

        long totalUsers = userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class));
        long usersInWindow = countUsers(range.start, range.end);

        long totalSessions = conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class));
        long sessionsInWindow = countConversations(range.start, range.end);
        long sessionsPrevWindow = countConversations(range.prevStart, range.prevEnd);

        long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class));
        long messagesInWindow = countMessages(range.start, range.end);
        long messagesPrevWindow = countMessages(range.prevStart, range.prevEnd);

        long activeUsers = countActiveUsers(range.start, range.end);
        long activeUsersPrev = countActiveUsers(range.prevStart, range.prevEnd);
        long activeSessions = countActiveSessions(range.start, range.end);
        long activeSessionsPrev = countActiveSessions(range.prevStart, range.prevEnd);

        // 累计值、窗口新增量及活跃度对比使用不同口径，组装时保留各自含义。
        DashboardOverviewGroupVO group = DashboardOverviewGroupVO.builder()
                .totalUsers(buildKpi(totalUsers, usersInWindow, null))
                .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrev, calcPct(activeUsers, activeUsersPrev)))
                .totalSessions(buildKpi(totalSessions, sessionsInWindow, null))
                .sessions24h(buildKpi(sessionsInWindow, sessionsInWindow - sessionsPrevWindow, calcPct(sessionsInWindow, sessionsPrevWindow)))
                .totalMessages(buildKpi(totalMessages, messagesInWindow, null))
                .messages24h(buildKpi(messagesInWindow, messagesInWindow - messagesPrevWindow, calcPct(messagesInWindow, messagesPrevWindow)))
                .activeSessions(buildKpi(activeSessions, activeSessions - activeSessionsPrev, calcPct(activeSessions, activeSessionsPrev)))
                .build();

        return DashboardOverviewVO.builder()
                .engine("workflow")
                .window(range.windowLabel)
                .compareWindow(range.compareLabel)
                .updatedAt(System.currentTimeMillis())
                .kpis(group)
                .build();
    }

    /**
     * 根据运行追踪及助手回复统计延迟、成功率、错误率和无知识率。
     * 默认窗口为 24 小时；空样本的比例和耗时返回 0，避免除零。
     */
    @Override
    public DashboardPerformanceVO loadPerformance(String window) {
        // 耗时样本、终态运行样本和助手消息样本使用各自的统计分母。
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));
        List<Long> durations = listDurations(range.start, range.end);
        long avgLatency = average(durations);
        long p95Latency = percentile(durations);

        long success = countTraceRuns(range.start, range.end, STATUS_SUCCESS);
        long error = countTraceRuns(range.start, range.end, STATUS_ERROR);
        long total = success + error;
        long assistantCount = countAssistantMessages(range.start, range.end);
        long noDocCount = countNoDocMessages(range.start, range.end);
        long slowCount = durations.stream().filter(duration -> duration > SLOW_LATENCY_THRESHOLD_MS).count();

        // 没有样本时用 0 展示，避免把空窗口误写成 100% 成功。
        double successRate = total == 0 ? 0.0 : round1((success * 100.0) / total);
        double errorRate = total == 0 ? 0.0 : round1((error * 100.0) / total);
        double noDocRate = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
        double slowRate = durations.isEmpty() ? 0.0 : round1((slowCount * 100.0) / durations.size());

        return DashboardPerformanceVO.builder()
                .window(range.windowLabel)
                .avgLatencyMs(avgLatency)
                .p95LatencyMs(p95Latency)
                .successRate(successRate)
                .errorRate(errorRate)
                .noDocRate(noDocRate)
                .slowRate(slowRate)
                .sampleCount(total)
                .build();
    }

    /**
     * 按指标和时间粒度组装看板趋势序列。
     * 默认窗口为 7 天；支持会话、消息、活跃用户、延迟及质量指标，未知指标返回空序列。
     */
    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        // 先解析时间窗口和粒度，再进入小时或天两种聚合路径。
        String normalizedMetric = metric == null ? "" : metric.trim().toLowerCase();
        Duration windowDuration = parseWindow(window, Duration.ofDays(7));
        WindowRange range = resolveWindowRange(window, Duration.ofDays(7));
        String resolvedGranularity = resolveTrendGranularity(granularity, windowDuration);
        ZoneId zoneId = ZoneId.systemDefault();
        List<DashboardTrendSeriesVO> series = new ArrayList<>();

        // 小时聚合先按小时对齐边界，再为所选指标构造连续时间点。
        if (GRANULARITY_HOUR.equals(resolvedGranularity)) {
            LocalDateTime endHourExclusive = toLocalDateTime(range.end, zoneId)
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1);
            LocalDateTime startHour = endHourExclusive.minusHours(Math.max(1, windowDuration.toHours()));

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countConversationsByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countMessagesByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countActiveUsersByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDateTime, Double> averages = averageLatencyByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> successMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_SUCCESS);
                Map<LocalDateTime, Long> errorMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_ERROR);
                Map<LocalDateTime, Long> assistantCountMap = countAssistantMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Long> noDocCountMap = countNoDocMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Double> errorRate = new HashMap<>();
                Map<LocalDateTime, Double> noDocRate = new HashMap<>();
                for (LocalDateTime hour = startHour; hour.isBefore(endHourExclusive); hour = hour.plusHours(1)) {
                    long total = successMap.getOrDefault(hour, 0L) + errorMap.getOrDefault(hour, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(hour, 0L);
                    long error = errorMap.getOrDefault(hour, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(hour, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(hour, err);
                    noDocRate.put(hour, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, noDocRate))
                        .build());
            }
        } else {
            // 日粒度使用自然日期边界，后续补点让没有数据的日期也保留在序列中。
            LocalDate startDay = toLocalDate(range.start, zoneId);
            LocalDate endExclusiveDay = toLocalDate(range.end, zoneId).plusDays(1);

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countConversationsByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countMessagesByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countActiveUsersByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDate, Double> averages = averageLatencyByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDate, Long> successMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_SUCCESS);
                Map<LocalDate, Long> errorMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_ERROR);
                Map<LocalDate, Long> assistantCountMap = countAssistantMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Long> noDocCountMap = countNoDocMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Double> errorRate = new HashMap<>();
                Map<LocalDate, Double> noDocRate = new HashMap<>();
                for (LocalDate day = startDay; day.isBefore(endExclusiveDay); day = day.plusDays(1)) {
                    long total = successMap.getOrDefault(day, 0L) + errorMap.getOrDefault(day, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(day, 0L);
                    long error = errorMap.getOrDefault(day, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(day, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(day, err);
                    noDocRate.put(day, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, noDocRate))
                        .build());
            }
        }

        // 将实际使用的窗口与粒度一并返回，便于前端解释统计口径。
        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.windowLabel)
                .granularity(resolvedGranularity)
                .series(series)
                .build();
    }

    /**
     * 统计左闭右开时间区间内创建的用户数。
     */
    private long countUsers(Date start, Date end) {
        // 按创建时间的左闭右开区间统计新增用户，避免相邻窗口在边界重复计数。
        return userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                .ge(UserDO::getCreateTime, start)
                .lt(UserDO::getCreateTime, end));
    }

    /**
     * 统计左闭右开时间区间内创建的会话数。
     */
    private long countConversations(Date start, Date end) {
        // 按创建时间统计窗口内新增会话，结束时刻不包含在当前窗口中。
        return conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                .ge(ConversationDO::getCreateTime, start)
                .lt(ConversationDO::getCreateTime, end));
    }

    /**
     * 统计左闭右开时间区间内创建的消息数，包含各角色消息。
     */
    private long countMessages(Date start, Date end) {
        // 统计指定时间段内创建的全部消息，时间边界使用大于等于开始、小于结束。
        return messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class)
                .ge(ConversationMessageDO::getCreateTime, start)
                .lt(ConversationMessageDO::getCreateTime, end));
    }

    /**
     * 按区间内消息的 user_id 去重统计活跃用户，不以用户注册时间判断活跃。
     */
    private long countActiveUsers(Date start, Date end) {
        // 对窗口内消息的 user_id 去重计数，同一用户发多条消息仍只计一次。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct user_id) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按区间内消息的会话 ID 与用户 ID 组合去重，统计发生过消息的活跃会话。
     */
    private long countActiveSessions(Date start, Date end) {
        // 按会话 ID 与用户 ID 的组合去重，统计窗口内实际出现消息的会话。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct (conversation_id, user_id)) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    /**
     * 统计区间内开始的追踪运行次数；status 非空时只统计指定状态。
     */
    private long countTraceRuns(Date start, Date end, String status) {
        // 先限定追踪开始时间，需要时再按状态筛选，返回满足条件的运行次数。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.ge("start_time", start).lt("start_time", end);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return traceRunMapper.selectCount(wrapper);
    }

    /**
     * 统计区间内助手角色的消息数，作为回复相关指标的统计基础。
     */
    private long countAssistantMessages(Date start, Date end) {
        // 在时间窗口内只统计助手角色消息，作为回答量的统计口径。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT);
        return messageMapper.selectCount(wrapper);
    }

    /**
     * 统计正文等于固定无文档回复的助手消息数，作为当前实现的无命中口径。
     */
    private long countNoDocMessages(Date start, Date end) {
        // 同时匹配助手角色和无文档固定回复，统计未找到知识证据时的回答量。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY);
        return messageMapper.selectCount(wrapper);
    }

    /**
     * 查询区间内成功运行的 duration_ms，只保留数值型且大于零的样本用于耗时统计。
     */
    private List<Long> listDurations(Date start, Date end) {
        // 仅取成功追踪的耗时字段，过滤空结果、非数值及非正耗时，供性能指标计算。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("duration_ms")
                .ge("start_time", start)
                .lt("start_time", end)
                .eq("status", STATUS_SUCCESS);
        List<Object> results = traceRunMapper.selectObjs(wrapper);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> durations = new ArrayList<>();
        for (Object value : results) {
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    durations.add(duration);
                }
            }
        }
        return durations;
    }

    /**
     * 读取聚合查询第一行的 cnt 数值；无结果或非数值时按零处理。
     */
    private long extractCount(List<Map<String, Object>> maps) {
        // 读取聚合查询第一行的 cnt 数字，空结果或非数值结果按零处理。
        if (maps == null || maps.isEmpty()) {
            return 0L;
        }
        Object value = maps.get(0).get("cnt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /**
     * 计算相对上周期的百分比变化并保留一位小数；上周期非正数时返回 null。
     */
    private Double calcPct(long current, long prev) {
        // 上期值非正时不计算增长率，否则按上期为分母计算百分比并保留一位小数。
        if (prev <= 0) {
            return null;
        }
        return round1(((current - prev) * 100.0) / prev);
    }

    /**
     * 封装指标当前值、增减量和变化百分比，统一看板概览响应结构。
     */
    private DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        // 把当前值、变化量和变化率装配成仪表盘统一的指标结构。
        return DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    /**
     * 在指定日期区间内按创建日期聚合会话数，返回日期到数量的映射。
     */
    private Map<LocalDate, Long> countConversationsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 在指定时间范围按数据库格式化日期分组计数，再转换为日期到数量的映射。
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(conversationMapper.selectMaps(wrapper));
    }

    /**
     * 在指定日期区间内按创建日期聚合全部消息数。
     */
    private Map<LocalDate, Long> countMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 按天汇总窗口内消息数，日期边界先按传入时区转换为数据库查询时间。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按创建日期聚合助手消息数，限定 role 为助手。
     */
    private Map<LocalDate, Long> countAssistantMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 限定助手角色后按天分组，得到每日回答量。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按日期统计固定无文档回复的助手消息数，用于每日无命中趋势。
     */
    private Map<LocalDate, Long> countNoDocMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 按天统计助手的固定无文档回复，供无命中趋势图使用。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按日期分别对消息用户去重，得到每日活跃用户数。
     */
    private Map<LocalDate, Long> countActiveUsersByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 每日分别对用户 ID 去重，同一用户可以在不同日期分别计为活跃。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按运行开始日期计算成功追踪的平均耗时，并转换为保留一位小数的日期映射。
     */
    private Map<LocalDate, Double> averageLatencyByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        // 只统计成功运行的平均耗时，跳过无法解析的日期，结果保留一位小数。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("d");
        List<Map<String, Object>> maps = traceRunMapper.selectMaps(wrapper);
        Map<LocalDate, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(date, round1(avg));
        }
        return result;
    }

    /**
     * 按日期聚合追踪运行次数，允许额外按状态过滤。
     */
    private Map<LocalDate, Long> countTraceRunsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId, String status) {
        // 按日期汇总追踪运行数，传入状态时再附加状态过滤。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("d");
        return mapLongResults(traceRunMapper.selectMaps(wrapper));
    }

    /**
     * 按创建时间所在小时聚合会话数，查询范围采用左闭右开区间。
     */
    private Map<LocalDateTime, Long> countConversationsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 按数据库格式化的整点小时分组，统计每小时新建会话数。
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(conversationMapper.selectMaps(wrapper));
    }

    /**
     * 按创建时间所在小时聚合全部消息数。
     */
    private Map<LocalDateTime, Long> countMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 按整点小时统计消息数量，再转换为小时到数量的映射。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按小时聚合助手角色的消息数。
     */
    private Map<LocalDateTime, Long> countAssistantMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 限定助手角色并按小时分组，得到每小时回答量。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按小时聚合正文匹配固定无文档提示的助手消息数。
     */
    private Map<LocalDateTime, Long> countNoDocMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 按小时汇总助手的无文档固定回复，保持与回答量相同的时间范围。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /**
     * 在每个小时内分别对消息用户去重，得到小时活跃用户数。
     */
    private Map<LocalDateTime, Long> countActiveUsersByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 在各小时内分别去重用户 ID，统计每小时活跃用户数。
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    /**
     * 按运行开始小时计算成功追踪的平均耗时，交给统一结果转换方法处理。
     */
    private Map<LocalDateTime, Double> averageLatencyByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        // 仅对成功追踪按小时计算平均耗时，再整理成趋势点需要的映射。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("h");
        return mapDoubleResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    /**
     * 按小时统计追踪运行次数；status 非空时仅统计该状态。
     */
    private Map<LocalDateTime, Long> countTraceRunsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId, String status) {
        // 按小时汇总运行次数，并按可选状态区分成功或失败等运行。
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("h");
        return mapLongResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    /**
     * 把 SQL 返回的日期 d 和数量 cnt 转换为日期映射，跳过缺少日期或无有效数量的行。
     */
    private Map<LocalDate, Long> mapLongResults(List<Map<String, Object>> maps) {
        // 把 SQL 的 d、cnt 字段转成日期映射，跳过空日期或无法转为整数的数量。
        Map<LocalDate, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(date, value);
            }
        }
        return result;
    }

    /**
     * 把 SQL 的小时桶 h 和数量 cnt 转换为小时映射，跳过无有效键值的行。
     */
    private Map<LocalDateTime, Long> mapLongResultsByHour(List<Map<String, Object>> maps) {
        // 把 SQL 的 h、cnt 字段转成小时映射，无效日期和无效计数不写入结果。
        Map<LocalDateTime, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(dateTime, value);
            }
        }
        return result;
    }

    /**
     * 转换小时平均值查询结果；非数值按零处理，耗时统一保留一位小数。
     */
    private Map<LocalDateTime, Double> mapDoubleResultsByHour(List<Map<String, Object>> maps) {
        // 读取小时和平均耗时，数值缺失按零处理，并将均值保留一位小数。
        Map<LocalDateTime, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(dateTime, round1(avg));
        }
        return result;
    }

    /**
     * 逐日生成连续的整数指标趋势点，没有数据库记录的日期补零，并按指定时区转换时间戳。
     */
    private List<DashboardTrendPointVO> buildPoints(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Long> values) {
        // 逐日补齐整个查询区间，缺失日期使用零值，避免趋势图因无记录而断开。
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    /**
     * 逐日生成小数指标趋势点，缺失日期补零，保证前端时间轴连续。
     */
    private List<DashboardTrendPointVO> buildPointsDouble(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Double> values) {
        // 逐日生成浮点趋势值，缺失日期补零，时间戳按指定时区的零点转换。
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    /**
     * 逐小时生成整数指标趋势点，缺失小时补零，并按指定时区生成时间戳。
     */
    private List<DashboardTrendPointVO> buildPointsByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Long> values) {
        // 按小时推进游标生成连续趋势点，没有统计记录的小时补零。
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    /**
     * 逐小时生成小数指标趋势点，缺失小时补零，结束时间不包含在输出中。
     */
    private List<DashboardTrendPointVO> buildPointsDoubleByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Double> values) {
        // 逐小时补齐浮点指标，按指定时区计算每个小时对应的时间戳。
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    /**
     * 计算耗时样本的平均值并四舍五入为整数，空样本返回零。
     */
    private long average(List<Long> values) {
        // 空集合返回零，否则累加后计算平均值并四舍五入为整数。
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return Math.round(sum / (double) values.size());
    }

    /**
     * 计算耗时样本的 P95，返回毫秒值。
     * 复制样本后排序，按 ceil(n*0.95)-1 取值，不改变调用方列表顺序。
     */
    private long percentile(List<Long> values) {
        // 空样本返回 0，索引经过边界限制后再访问排序列表。
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * 按四舍五入规则将数值保留一位小数。
     */
    private double round1(double value) {
        // 先放大十倍取整再缩小，将指标数值保留到一位小数。
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 将 SQL 聚合结果中的日期文本转换为 LocalDate，null 保持为空。
     */
    private LocalDate parseLocalDate(Object value) {
        // 空字段返回 null，其余字段按字符串解析为日期，格式错误由调用链处理。
        if (value == null) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /**
     * 按小时桶的固定格式解析聚合时间，null 保持为空。
     */
    private LocalDateTime parseLocalDateTime(Object value) {
        // 使用小时统计约定的格式解析时间字段，空值保持 null。
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value), HOUR_FORMATTER);
    }

    /**
     * 兼容数据库数值对象与数字文本；空值或无法解析的文本返回 null。
     */
    private Long toLongValue(Object value) {
        // 数值直接转 long，字符串尝试解析；空值或非法数字返回 null 供上层跳过。
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 按指定时区将本地日期或时间转为 Date；日期重载使用当天零点。
     */
    private Date toDate(LocalDate date, ZoneId zoneId) {
        // 按传入时区把本地日期或时间转换为 Date，统一查询参数和趋势时间戳的时区口径。
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }

    /**
     * 按指定时区将本地日期或时间转为 Date；日期重载使用当天零点。
     */
    private Date toDate(LocalDateTime time, ZoneId zoneId) {
        // 按传入时区把本地日期或时间转换为 Date，统一查询参数和趋势时间戳的时区口径。
        return Date.from(time.atZone(zoneId).toInstant());
    }

    /**
     * 将 Date 按统计时区转换为本地日期，用于构造每日查询窗口。
     */
    private LocalDate toLocalDate(Date date, ZoneId zoneId) {
        // 先把 Date 还原为时间点，再按指定时区提取当地日期。
        return date.toInstant().atZone(zoneId).toLocalDate();
    }

    /**
     * 将 Date 按统计时区转换为本地时间，用于构造小时查询窗口。
     */
    private LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        // 按指定时区把时间点转换成本地日期时间，供小时窗口计算。
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }

    /**
     * 把时间窗口解析为当前区间及前一等长对比区间。
     * 统计终点取当前时刻，前一窗口终点与当前窗口起点相接。
     */
    private WindowRange resolveWindowRange(String window, Duration fallback) {
        // 统一生成范围，避免同一看板不同指标各自取时造成边界漂移。
        Duration duration = parseWindow(window, fallback);
        Instant now = Instant.now();
        Instant start = now.minus(duration);
        Instant prevStart = start.minus(duration);
        return new WindowRange(Date.from(start), Date.from(now), Date.from(prevStart), Date.from(start),
                window == null ? formatDuration(fallback) : window, "prev_" + (window == null ? formatDuration(fallback) : window));
    }

    /**
     * 解析以 h 或 d 结尾的窗口字符串。
     * 空值或未知后缀使用传入 fallback，数值部分交给 parseNumber 处理。
     */
    private Duration parseWindow(String window, Duration fallback) {
        // 先规范窗口文本，识别小时 h 和天 d 后缀；缺省或不支持的表达式使用回落时长。
        // 只在这里转换窗口表达，不直接执行数据库统计。
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String normalized = window.trim().toLowerCase();
        if (normalized.endsWith("h")) {
            long hours = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toHours());
            return Duration.ofHours(hours);
        }
        if (normalized.endsWith("d")) {
            long days = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toDays());
            return Duration.ofDays(days);
        }
        return fallback;
    }

    /**
     * 确定趋势按小时还是按天聚合。
     * 认可显式 hour/day；未提供有效粒度时，48 小时以内按小时，其余按天。
     */
    private String resolveTrendGranularity(String granularity, Duration windowDuration) {
        // 粒度先归一再使用，避免前端拼写差异影响时间桶选择。
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toLowerCase();
            if (GRANULARITY_HOUR.equals(normalized) || GRANULARITY_DAY.equals(normalized)) {
                return normalized;
            }
        }
        return windowDuration.toHours() <= 48 ? GRANULARITY_HOUR : GRANULARITY_DAY;
    }

    /**
     * 解析窗口参数中的整数，格式无效时返回调用方提供的默认值。
     */
    private long parseNumber(String value, long fallback) {
        // 尝试解析整数，输入格式不合法时返回调用方提供的默认值。
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * 将窗口时长转换为天或小时标签；完整天数优先使用 d，其余使用 h。
     */
    private String formatDuration(Duration duration) {
        // 整天时长优先用 d 表示，其余按整小时用 h 表示，供窗口标签展示。
        long hours = duration.toHours();
        if (hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        return hours + "h";
    }

    private static class WindowRange {
        private final Date start;
        private final Date end;
        private final Date prevStart;
        private final Date prevEnd;
        private final String windowLabel;
        private final String compareLabel;

        WindowRange(Date start, Date end, Date prevStart, Date prevEnd, String windowLabel, String compareLabel) {
            // 保存本期与上期的时间边界及展示标签，后续各指标复用同一统计窗口。
            this.start = start;
            this.end = end;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
            this.windowLabel = windowLabel;
            this.compareLabel = compareLabel;
        }
    }
}
