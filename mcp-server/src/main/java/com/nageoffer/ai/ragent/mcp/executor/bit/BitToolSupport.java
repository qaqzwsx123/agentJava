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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 比特严选各执行器共用的打码、排版与取参
 */
final class BitToolSupport {

    static final String EMPTY_FIELD = "-";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 逗号、顿号、中文逗号和空白都当分隔符：模型写哪一种都得认
     */
    private static final String CSV_SEPARATOR = "[,，、\\s]+";

    /**
     * 地址截到区级时认的收尾字，「区」排在「市」前面是因为要的是区不是市
     */
    private static final List<String> DISTRICT_SUFFIXES = List.of("区", "县", "旗", "市");

    private BitToolSupport() {
        // 公共格式化与结果辅助方法都通过静态方式调用，私有构造器阻止无意义实例化。
    }

    /**
     * 无权访问与不存在回同一句，否则这句话本身就是一个存在性探针
     * <p>
     * 不是 isError：查不到是一个合法的答案，标成错误会让上层当成工具故障去重试
     */
    static CallToolResult notFound(String subject) {
        // 把“没有查到”作为成功的业务查询结果返回，避免让正常空结果被误判为工具执行异常。
        return McpToolResults.success("未找到" + subject);
    }

    /**
     * 写工具的回绝：这次写入没有发生，一律 isError
     * <p>
     * 和只读工具的 notFound 正好相反——那边查不到是合法答案，这边「没写成」是调用方必须知道的事实。
     * 有了这条，写工具返回 success 就等价于库里真的变了；越权与不存在仍共用同一句文案
     */
    static CallToolResult rejected(String message) {
        // 把校验不通过等业务拒绝包装成 MCP 错误结果，让模型知道本次操作没有成功执行。
        return McpToolResults.error(message);
    }

    /**
     * 手机号留前 3 后 4；认不出的短号码退化成只留首尾，不能因为格式陌生就原样吐出去
     */
    static String maskPhone(String phone) {
        // 对号码中间部分脱敏，较长号码保留常用首尾段，短字符串按现有分支保留边缘字符。
        if (StrUtil.isBlank(phone)) {
            return EMPTY_FIELD;
        }
        String trimmed = StrUtil.trim(phone);
        return trimmed.length() > 7
                ? StrUtil.hide(trimmed, 3, trimmed.length() - 4)
                : StrUtil.hide(trimmed, 1, Math.max(1, trimmed.length() - 1));
    }

    /**
     * 地址只到区级，取第一个收尾字而不是最后一个
     * <p>
     * 「浦东新区张江高科技园区」里最后那个「区」已经精确到园区了，按最后一个截等于没打码
     */
    static String maskAddress(String address) {
        // 优先截取到区县等行政区后隐藏详细地址，匹配不到时只保留有限前缀，避免泄露门牌信息。
        if (StrUtil.isBlank(address)) {
            return EMPTY_FIELD;
        }
        String trimmed = StrUtil.trim(address);
        for (String suffix : DISTRICT_SUFFIXES) {
            int index = trimmed.indexOf(suffix);
            if (index > 0) {
                return trimmed.substring(0, index + suffix.length());
            }
        }
        return trimmed.length() > 6 ? trimmed.substring(0, 6) : trimmed;
    }

    static String money(BigDecimal amount) {
        // 空金额按零处理，统一四舍五入到两位小数并输出为普通十进制字符串。
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 金额解析不出来按「没给」处理，不能兜底成 0——那会把够门槛的券判成不可用
     */
    static BigDecimal decimal(String value) {
        // 将去掉首尾空白的文本解析为金额，空值或格式错误返回 null，交给上层决定是否采用默认值。
        String trimmed = StrUtil.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String dateTime(Timestamp value) {
        // 将时间值格式化为统一日期时间文本，空时间用占位说明表示。
        return value == null ? EMPTY_FIELD : DATE_TIME.format(value.toLocalDateTime());
    }

    static String date(Timestamp value) {
        // 将时间值转换为日期文本供有效期等场景展示，空值使用约定占位文本。
        return value == null ? EMPTY_FIELD : value.toLocalDateTime().toLocalDate().toString();
    }

    /**
     * 距今过了几天，七天窗口这类判据靠它现算
     */
    static long daysSince(Timestamp value) {
        // 空时间返回负数表示未知，否则计算距现在经过的完整天数，供签收和售后提示使用。
        return value == null ? -1 : Duration.between(value.toInstant(), Instant.now()).toDays();
    }

    static List<String> csv(String value) {
        // 按支持的分隔符拆分文本，去掉空白与空项，并保持原顺序去重，供型号、标签等参数复用。
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String piece : value.split(CSV_SEPARATOR)) {
            String trimmed = StrUtil.trim(piece);
            if (StrUtil.isNotBlank(trimmed) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 返回条数有上限：模型传个 10000 进来不该把整张表读出去
     */
    static int limit(Integer requested, int defaultValue, int max) {
        // 未提供或非正数时采用默认条数，正数再受最大条数限制，控制数据库查询和返回文本规模。
        return requested == null || requested <= 0 ? defaultValue : Math.min(requested, max);
    }

    /**
     * 在售零库存和只剩几件都要说出来，不然「加购的东西还能不能买」答不了
     */
    /**
     * 规格展示顺序，与写入顺序无关——jsonb 按键长与字典序重排，照它的顺序读出来
     * 会变成「容量 / 尺寸 / 颜色」，同一款的各配置之间对不齐
     */
    private static final List<String> SPEC_ORDER = List.of("容量", "颜色", "尺寸", "表壳尺寸", "表壳材质",
            "连接", "芯片", "核心配置", "面板", "支架", "充电盒", "包装规格", "屏幕");

    /**
     * 规格 JSON 排成「容量 256GB / 颜色 黑色」
     */
    static String specs(String json) {
        // 解析 JSON 规格，优先按常用规格顺序展示，再补充其余字段；异常或空值按现有分支兜底。
        if (StrUtil.isBlank(json)) {
            return EMPTY_FIELD;
        }
        JSONObject obj = JSONUtil.parseObj(json);
        if (obj.isEmpty()) {
            return EMPTY_FIELD;
        }
        List<String> parts = new ArrayList<>();
        for (String key : SPEC_ORDER) {
            Object value = obj.get(key);
            if (value != null) {
                parts.add(key + " " + value);
            }
        }
        // 顺序表里没列到的键仍要出现，否则新加一个维度会静默消失
        obj.forEach((key, value) -> {
            if (!SPEC_ORDER.contains(key)) {
                parts.add(key + " " + value);
            }
        });
        return String.join(" / ", parts);
    }

    static String stockLabel(String status, int stock) {
        // 商品不在售时优先说明状态；在售商品再按库存数量区分缺货、少量库存和有货。
        if (!"在售".equals(status)) {
            return status;
        }
        if (stock <= 0) {
            return "在售缺货";
        }
        return stock <= 5 ? "仅剩 " + stock + " 件" : "有货 " + stock + " 件";
    }
}
