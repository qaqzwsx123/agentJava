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

package com.nageoffer.ai.ragent.admin.controller;

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformance;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
//@ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "workflow", matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
@SaCheckRole("admin")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 查询管理看板的用户、会话、消息和活跃度汇总数据。
     * window 指定统计时间窗口，缺省窗口由服务层解析。
     */
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(@RequestParam(required = false) String window) {
        // 让看板服务统一计算统计口径，控制器仅封装 JSON 响应。
        return Results.success(dashboardService.loadOverview(window));
    }

    /**
     * 查询选定时间窗口内的请求性能统计。
     * window 用于限定链路样本范围，返回耗时和成功情况等性能数据。
     */
    @GetMapping("/performance")
    public Result<DashboardPerformance> performance(@RequestParam(required = false) String window) {
        // 性能指标从追踪数据聚合，避免在控制器重复统计逻辑。
        return Results.success(dashboardService.loadPerformance(window));
    }

    /**
     * 按指定指标和时间粒度查询看板趋势序列。
     * metric 指定指标，window 指定范围，granularity 指定时间桶粒度。
     */
    @GetMapping("/trends")
    public Result<DashboardTrendsVO> trends(@RequestParam String metric,
                                            @RequestParam(required = false) String window,
                                            @RequestParam(required = false) String granularity) {
        // 服务层解析指标、窗口与粒度，生成前端绘图所需的时间序列。
        return Results.success(dashboardService.loadTrends(metric, window, granularity));
    }
}
