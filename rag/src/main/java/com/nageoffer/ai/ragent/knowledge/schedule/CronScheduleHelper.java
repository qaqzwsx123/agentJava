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

package com.nageoffer.ai.ragent.knowledge.schedule;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Cron 工具类
 */
public final class CronScheduleHelper {

    private CronScheduleHelper() {
    }

    /**
     * 按系统时区计算给定时间之后的下一次 Cron 触发时间；缺少参数或无后续触发返回 null。
     */
    public static Date nextRunTime(String cron, Date from) {
        if (!StringUtils.hasText(cron) || from == null) {
            return null;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime next = expression.next(fromTime);
        if (next == null) {
            return null;
        }
        return Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 取接下来两次 Cron 触发时间比较间隔，判断是否小于允许值；缺少参数或无法计算按不合格处理。
     */
    public static boolean isIntervalLessThan(String cron, Date from, long minSeconds) {
        if (!StringUtils.hasText(cron) || from == null) {
            return true;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime first = expression.next(fromTime);
        if (first == null) {
            return true;
        }
        LocalDateTime second = expression.next(first);
        if (second == null) {
            return true;
        }
        long diffSeconds = Duration.between(first, second).getSeconds();
        return diffSeconds < minSeconds;
    }
}
