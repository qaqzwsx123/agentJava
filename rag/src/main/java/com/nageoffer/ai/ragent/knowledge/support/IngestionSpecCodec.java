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

package com.nageoffer.ai.ragent.knowledge.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 文档级摄取配置的读写、校验与归一化：单点
 * <p>
 * 取值范围由 {@link ChunkBudget} 与 {@link ParseProfile} 的构造期保证，此处只把构造异常翻译成
 * 用户可读的报错；缺失字段一律回落 {@link IngestionSpec#defaults()}，全系统只此一份默认值；
 * 前端提交什么形状都在此收敛成规整 JSON 落库，读路径不必再探测
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionSpecCodec {

    /**
     * 预算字段在线路上的键名：本类按它读入，{@link IngestionSpecSchemaProvider} 按它下发表单，
     * 必须共用这一份常量——两边各写一份字符串的话，键名一改就是提交了却静默走默认值，
     * 前端照常提交、后端照常入库，没有任何一步会报错
     */
    public static final String KEY_MAX_CHARS = "maxChars";
    public static final String KEY_OVERLAP_CHARS = "overlapChars";
    public static final String KEY_ROWS_PER_CHUNK = "rowsPerChunk";
    public static final String KEY_TOLERANCE_FACTOR = "toleranceFactor";

    private static final String KEY_PARSE_PROFILE = "parseProfile";

    /**
     * 不分块哨兵：沿用前端既有约定的 {@code -1}，线路上（提交、落库、出参、schema 下发）只有这一个表示
     * <p>
     * 领域对象内部用的是 {@link ChunkBudget} 的 {@code Integer.MAX_VALUE}，两者的翻译只发生在本类
     */
    public static final int WHOLE_DOCUMENT_SENTINEL = -1;

    private final ObjectMapper objectMapper;

    /**
     * 读：库里的 JSON → 配置对象，空值或损坏一律回落默认
     * <p>
     * 必须走 {@link SpecWire} 而不是直接 readValue 成领域对象：库里的 {@code maxChars} 是 {@code -1}，
     * 撞上 {@link ChunkBudget} 的"必须 &gt; 0"校验会被下面的 catch 悄悄换成默认配置，
     * 整篇不分块的文档于是无声地按 1024 切开
     */
    public IngestionSpec read(String json) {
        // 空配置直接使用默认值，历史 JSON 读取失败时记日志并回落默认配置，保证旧文档仍能读取。
        if (!StringUtils.hasText(json)) {
            return IngestionSpec.defaults();
        }
        try {
            return objectMapper.readValue(json, SpecWire.class).toDomain();
        } catch (Exception e) {
            log.warn("摄取配置解析失败，回落默认配置：{}", json, e);
            return IngestionSpec.defaults();
        }
    }

    /**
     * 写：配置对象 → 落库 JSON
     */
    public String write(IngestionSpec spec) {
        // 先转换为约定的存储结构再序列化，空规格用默认值，序列化失败给出业务错误。
        try {
            return objectMapper.writeValueAsString(SpecWire.of(spec == null ? IngestionSpec.defaults() : spec));
        } catch (Exception e) {
            throw new ClientException("摄取配置序列化失败");
        }
    }

    /**
     * 校验并归一化前端提交的配置 JSON，返回落库用的规整 JSON
     *
     * @param rawJson 前端提交的原始 JSON，可空（空表示全默认）
     * @return 规整后的 JSON，入参为空时返回 null（列留空即表示走默认）
     */
    public String normalize(String rawJson) {
        // 严格解析用户 JSON，再按领域规格补默认值、校验预算并重新输出，非法配置明确拒绝。
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(rawJson.trim(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("摄取配置 JSON 格式不合法");
        }
        try {
            // 把松散 JSON 还原为规格对象再序列化，使默认值和校验规则统一生效。
            return write(fromMap(raw));
        } catch (IllegalArgumentException e) {
            throw new ClientException("摄取配置不合法：" + e.getMessage());
        }
    }

    /**
     * 从持久化映射还原解析档位及分块预算，统一交给规格对象承载。
     */
    private IngestionSpec fromMap(Map<String, Object> raw) {
        // 从松散映射中读取档位和分块参数，再构造带统一版本的入库规格。
        return IngestionSpec.of(ParseProfile.from(readString(raw, KEY_PARSE_PROFILE)),
                toBudget(readInt(raw, KEY_MAX_CHARS), readInt(raw, KEY_OVERLAP_CHARS),
                        readInt(raw, KEY_ROWS_PER_CHUNK), readInt(raw, KEY_TOLERANCE_FACTOR)));
    }

    /**
     * 四个整数 → 分块预算：哨兵翻译与缺失回落只有这一份
     * <p>
     * 前端提交的扁平键与库里回读的嵌套 {@code budget} 都收在这里；旧数据里的 {@code Integer.MAX_VALUE}
     * 同样落回整篇不分块，它正是 {@link ChunkBudget#wholeDocument()} 的内部取值
     */
    private static ChunkBudget toBudget(Integer maxChars, Integer overlap, Integer rows, Integer tolerance) {
        // 整篇哨兵走整篇模式，其他缺省参数采用默认值，重叠量按实际块大小重新计算。
        if (maxChars != null && maxChars == WHOLE_DOCUMENT_SENTINEL) {
            return ChunkBudget.wholeDocument();
        }
        ChunkBudget defaults = ChunkBudget.defaults();
        int budget = maxChars != null && maxChars > 0 ? maxChars : defaults.maxChars();
        return new ChunkBudget(
                budget,
                // 缺省重叠按块大小等比给：默认预算里那个数是配 1024 的，照搬到小块上会被压到 budget-1，切一片只前进一个字
                overlap != null && overlap >= 0 ? overlap : ChunkBudget.defaultOverlapFor(budget),
                rows != null && rows > 0 ? rows : defaults.rowsPerChunk(),
                tolerance != null && tolerance > 0 ? tolerance : defaults.toleranceFactor());
    }

    /**
     * 兼容配置中的数字和整数字符串，缺失或格式无效返回 null，留给预算构造逻辑处理。
     */
    private static Integer readInt(Map<String, Object> raw, String key) {
        // 兼容数字与数字文本，无法解析或类型不匹配时返回 null，留给预算构造处理默认值。
        Object value = raw.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将配置值转为字符串，键不存在时返回 null。
     */
    private static String readString(Map<String, Object> raw, String key) {
        // 读取映射字段并转成字符串，未提供时保持 null。
        Object value = raw.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 摄取配置的线路形状：与 {@link IngestionSpec} 同构，唯一区别是整篇不分块用
     * {@link #WHOLE_DOCUMENT_SENTINEL} 表达，而非领域内部的 {@code Integer.MAX_VALUE}
     * <p>
     * 线路上进出必须是同一个值：直接序列化领域对象的话，前端提交 {@code -1}、库里躺着
     * {@code 2147483647}、出参再原样送回，重新打开编辑弹窗就会在"块大小"里看见一个天文数字
     *
     * @param version      结构版本，缺失按当前版本
     * @param parseProfile 解析档位
     * @param budget       分块预算
     */
    private record SpecWire(int version, ParseProfile parseProfile, BudgetWire budget) {

        static SpecWire of(IngestionSpec spec) {
            // 将领域规格转换成可序列化结构，整篇模式显式编码为约定哨兵值。
            ChunkBudget budget = spec.budget();
            return new SpecWire(spec.version(), spec.parseProfile(), budget.isWholeDocument()
                    ? new BudgetWire(WHOLE_DOCUMENT_SENTINEL, 0, WHOLE_DOCUMENT_SENTINEL, budget.toleranceFactor())
                    : new BudgetWire(budget.maxChars(), budget.overlapChars(),
                    budget.rowsPerChunk(), budget.toleranceFactor()));
        }

        IngestionSpec toDomain() {
            // 恢复领域规格，历史版本缺失时补当前版本，预算缺失时补默认配置。
            return new IngestionSpec(
                    version > 0 ? version : IngestionSpec.CURRENT_VERSION,
                    parseProfile,
                    budget == null
                            ? ChunkBudget.defaults()
                            : toBudget(budget.maxChars(), budget.overlapChars(),
                            budget.rowsPerChunk(), budget.toleranceFactor()));
        }
    }

    /**
     * 分块预算的线路形状：字段允许缺失，缺失一律由 {@link #toBudget} 回落默认
     */
    private record BudgetWire(Integer maxChars, Integer overlapChars, Integer rowsPerChunk, Integer toleranceFactor) {
    }
}
