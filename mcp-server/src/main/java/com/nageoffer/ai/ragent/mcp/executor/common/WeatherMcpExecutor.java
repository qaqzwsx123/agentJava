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
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

@Slf4j
@Component
public class WeatherMcpExecutor {

    private static final String TOOL_ID = "weather_query";

    private static final Map<String, double[]> CITY_COORDINATES = new LinkedHashMap<>();

    static {
        CITY_COORDINATES.put("北京", new double[]{39.9, 116.4});
        CITY_COORDINATES.put("上海", new double[]{31.2, 121.5});
        CITY_COORDINATES.put("广州", new double[]{23.1, 113.3});
        CITY_COORDINATES.put("深圳", new double[]{22.5, 114.1});
        CITY_COORDINATES.put("杭州", new double[]{30.3, 120.2});
        CITY_COORDINATES.put("成都", new double[]{30.6, 104.1});
        CITY_COORDINATES.put("武汉", new double[]{30.6, 114.3});
        CITY_COORDINATES.put("南京", new double[]{32.1, 118.8});
        CITY_COORDINATES.put("西安", new double[]{34.3, 108.9});
        CITY_COORDINATES.put("重庆", new double[]{29.6, 106.5});
        CITY_COORDINATES.put("长沙", new double[]{28.2, 112.9});
        CITY_COORDINATES.put("天津", new double[]{39.1, 117.2});
        CITY_COORDINATES.put("苏州", new double[]{31.3, 120.6});
        CITY_COORDINATES.put("郑州", new double[]{34.7, 113.6});
        CITY_COORDINATES.put("青岛", new double[]{36.1, 120.4});
        CITY_COORDINATES.put("大连", new double[]{38.9, 121.6});
        CITY_COORDINATES.put("厦门", new double[]{24.5, 118.1});
        CITY_COORDINATES.put("昆明", new double[]{25.0, 102.7});
        CITY_COORDINATES.put("哈尔滨", new double[]{45.8, 126.5});
        CITY_COORDINATES.put("三亚", new double[]{18.3, 109.5});
    }

    private static final List<String> WEATHER_TYPES_SPRING = List.of("晴", "多云", "阴", "小雨", "阵雨", "多云转晴");
    private static final List<String> WEATHER_TYPES_SUMMER = List.of("晴", "多云", "雷阵雨", "大雨", "暴雨", "多云转阴");
    private static final List<String> WEATHER_TYPES_AUTUMN = List.of("晴", "多云", "阴", "小雨", "晴转多云", "多云转晴");
    private static final List<String> WEATHER_TYPES_WINTER = List.of("晴", "多云", "阴", "小雪", "中雪", "晴转多云", "雾");

    @Bean
    public McpServerFeatures.SyncToolSpecification weatherToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("city", "城市名称，如北京、上海、广州等"))
                .optional(string("queryType", "查询类型：current(当前天气)、forecast(未来预报)")
                        .options(List.of("current", "forecast"))
                        .defaultTo("current"))
                .optional(integer("days", "预报天数，仅forecast模式有效，默认3天，最多7天")
                        .defaultTo(3))
                .build();

        // 显式声明为只读工具，查询结果不会在此方法中触发业务写入。
        return Tool.builder()
                .name(TOOL_ID)
                .description("查询城市天气信息，支持查看当前实时天气和未来多天天气预报，包含温度、湿度、风力、天气状况等信息")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 校验城市是否在支持列表中，再按查询类型选择当前天气或多日预报；当前实现使用确定性的模拟数据。
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String city = MapUtil.getStr(args, "city");
            String queryType = MapUtil.getStr(args, "queryType");
            Integer days = MapUtil.getInt(args, "days");

            if (StrUtil.isBlank(city)) {
                return McpToolResults.error("请提供城市名称");
            }
            if (StrUtil.isBlank(queryType)) queryType = "current";
            if (days == null || days <= 0) days = 3;
            if (days > 7) days = 7;

            if (!CITY_COORDINATES.containsKey(city)) {
                return McpToolResults.error("暂不支持查询该城市，当前支持：" + String.join("、", CITY_COORDINATES.keySet()));
            }

            String result = switch (queryType) {
                case "forecast" -> buildForecastResult(city, days);
                default -> buildCurrentResult(city);
            };

            log.info("MCP 工具调用完成, toolId={}, city={}, queryType={}, elapsed={}ms",
                    TOOL_ID, city, queryType, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("查询", e);
        }
    }

    private String buildCurrentResult(String city) {
        // 根据当前日期生成该城市的模拟天气，组合温湿度、风力、空气质量等展示字段。
        LocalDate today = LocalDate.now();
        WeatherData weather = generateWeatherForDate(city, today);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 今日天气】\n\n", city));
        sb.append(String.format("日期: %s\n", today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))));
        sb.append(String.format("天气: %s\n", weather.weatherType));
        sb.append(String.format("当前温度: %d°C\n", weather.currentTemp));
        sb.append(String.format("最高温度: %d°C\n", weather.highTemp));
        sb.append(String.format("最低温度: %d°C\n", weather.lowTemp));
        sb.append(String.format("相对湿度: %d%%\n", weather.humidity));
        sb.append(String.format("风向: %s\n", weather.windDirection));
        sb.append(String.format("风力: %s\n", weather.windLevel));
        sb.append(String.format("空气质量: %s\n", weather.airQuality));

        if (weather.weatherType.contains("雨") || weather.weatherType.contains("雪")) {
            sb.append("\n提示: 今日有降水，出行请携带雨具。");
        } else if (weather.highTemp >= 35) {
            sb.append("\n提示: 今日高温，注意防暑降温。");
        } else if (weather.lowTemp <= 0) {
            sb.append("\n提示: 今日气温较低，注意防寒保暖。");
        }

        return sb.toString().trim();
    }

    private String buildForecastResult(String city, int days) {
        // 从今天起逐日生成指定天数的模拟预报，并根据首尾温度变化给出趋势提示。
        LocalDate today = LocalDate.now();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 未来%d天天气预报】\n\n", city, days));

        for (int d = 0; d < days; d++) {
            LocalDate date = today.plusDays(d);
            WeatherData weather = generateWeatherForDate(city, date);
            String dayLabel = d == 0 ? "今天" : d == 1 ? "明天" : d == 2 ? "后天" : date.format(DateTimeFormatter.ofPattern("MM月dd日"));

            sb.append(String.format("📅 %s（%s）\n", dayLabel, date.format(DateTimeFormatter.ofPattern("MM-dd"))));
            sb.append(String.format("   天气: %s | 温度: %d°C ~ %d°C\n", weather.weatherType, weather.lowTemp, weather.highTemp));
            sb.append(String.format("   湿度: %d%% | %s %s\n\n", weather.humidity, weather.windDirection, weather.windLevel));
        }

        WeatherData todayWeather = generateWeatherForDate(city, today);
        WeatherData lastDayWeather = generateWeatherForDate(city, today.plusDays(days - 1));
        int tempTrend = lastDayWeather.highTemp - todayWeather.highTemp;
        if (Math.abs(tempTrend) >= 5) {
            sb.append(String.format("趋势: 未来%d天气温%s，注意%s。",
                    days,
                    tempTrend > 0 ? "逐渐升高" : "逐渐下降",
                    tempTrend > 0 ? "防暑" : "保暖"));
        }

        return sb.toString().trim();
    }

    private WeatherData generateWeatherForDate(String city, LocalDate date) {
        // 用城市和日期组合随机种子，使同城同日结果稳定；再结合季节和城市基础信息生成天气指标。
        double[] coords = CITY_COORDINATES.get(city);
        double latitude = coords[0];
        long seed = date.toEpochDay() * 31 + city.hashCode();
        Random random = new Random(seed);

        // 按月份划分季节，结合纬度给出基础温度，再叠加种子随机数形成高低温。
        int month = date.getMonthValue();
        int season = (month >= 3 && month <= 5) ? 0 : (month >= 6 && month <= 8) ? 1 : (month >= 9 && month <= 11) ? 2 : 3;

        double baseTemp = switch (season) {
            case 0 -> 15 - (latitude - 25) * 0.5;
            case 1 -> 30 - (latitude - 25) * 0.3;
            case 2 -> 18 - (latitude - 25) * 0.5;
            default -> 5 - (latitude - 25) * 0.8;
        };

        int highTemp = (int) (baseTemp + 3 + random.nextInt(6));
        int lowTemp = (int) (baseTemp - 3 - random.nextInt(5));
        int currentTemp = lowTemp + random.nextInt(Math.max(1, highTemp - lowTemp));

        // 各季节使用不同的候选天气和湿度范围，雨雪天气额外提高湿度。
        List<String> weatherTypes = switch (season) {
            case 0 -> WEATHER_TYPES_SPRING;
            case 1 -> WEATHER_TYPES_SUMMER;
            case 2 -> WEATHER_TYPES_AUTUMN;
            default -> WEATHER_TYPES_WINTER;
        };
        String weatherType = weatherTypes.get(random.nextInt(weatherTypes.size()));

        int humidity = switch (season) {
            case 1 -> 60 + random.nextInt(30);
            case 3 -> 20 + random.nextInt(30);
            default -> 40 + random.nextInt(30);
        };
        if (weatherType.contains("雨") || weatherType.contains("雪")) humidity = Math.min(95, humidity + 20);

        String[] directions = {"东风", "南风", "西风", "北风", "东南风", "西北风", "东北风", "西南风"};
        String windDirection = directions[random.nextInt(directions.length)];

        int windForce = 1 + random.nextInt(5);
        String windLevel = windForce + "-" + (windForce + 1) + "级";

        // 空气质量也由模拟随机数和纬度修正得到，不代表实际监测数据。
        int aqiBase = 30 + random.nextInt(120);
        if (latitude > 35) aqiBase += 20;
        String airQuality;
        if (aqiBase <= 50) airQuality = "优";
        else if (aqiBase <= 100) airQuality = "良";
        else if (aqiBase <= 150) airQuality = "轻度污染";
        else airQuality = "中度污染";

        // 把本次生成的所有字段封装为一条天气记录，当前天气和预报共用这一结构。
        WeatherData data = new WeatherData();
        data.weatherType = weatherType;
        data.currentTemp = currentTemp;
        data.highTemp = highTemp;
        data.lowTemp = lowTemp;
        data.humidity = humidity;
        data.windDirection = windDirection;
        data.windLevel = windLevel;
        data.airQuality = airQuality;
        return data;
    }

    private static class WeatherData {
        String weatherType;
        int currentTemp;
        int highTemp;
        int lowTemp;
        int humidity;
        String windDirection;
        String windLevel;
        String airQuality;
    }
}
