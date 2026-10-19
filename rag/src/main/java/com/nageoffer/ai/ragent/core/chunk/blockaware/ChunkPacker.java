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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 块打包器：以「相邻两个标题之间」为一节，按体量把节装配成块
 * <p>
 * 切口只落在节边界上，故不做块级重叠：重叠是为了防答案被切断，而节边界处没有被切断的句子。
 * 唯一的例外是整节超出容忍上限时的节内切分，那一层的重叠由 {@code TextSplitter} 在切段落时负责
 */
@Component
public class ChunkPacker {
    // 草稿打包器：按标题组织章节并根据长度合并相邻草稿，尽量保留整节、减少过小碎块；超长章节再分组，并尝试让表格、代码与前导说明放在一起。

    /**
     * 合并时块间分隔符，保留段落 / 列表边界
     */
    private static final String SEPARATOR = "\n\n";

    /**
     * 最小块体量取块大小的几分之一：低于它的量不足以独立成块，宁可并进下一节也不单独落地
     */
    private static final int MIN_CHARS_DIVISOR = 4;

    /**
     * 打包成块
     * <p>
     * 一节整体不超容忍上限就是原子的，要么整节并进当前块要么整节自己成块；超出的才在节内按体量切开
     *
     * @param drafts 切分产出的有序草稿
     * @param budget 分块预算，合并上限取 {@link ChunkBudget#maxChars()}
     */
    public List<ChunkDraft> pack(List<ChunkDraft> drafts, ChunkBudget budget) {
        // 先按标题拆成章节，能整体容纳的章节进入缓冲；超限章节逐块打包，最后输出剩余缓冲。
        if (drafts == null || drafts.size() <= 1) {
            return drafts == null ? List.of() : drafts;
        }

        int maxChars = budget.maxChars();
        int minChars = Math.max(1, maxChars / MIN_CHARS_DIVISOR);
        List<ChunkDraft> result = new ArrayList<>();
        List<ChunkDraft> buffer = new ArrayList<>();

        for (List<ChunkDraft> section : splitSections(drafts)) {
            int sectionLen = totalLength(section);
            if (sectionLen > budget.toleranceChars()) {
                buffer = packWithin(buffer, section, budget, minChars, result);
                continue;
            }
            if (!buffer.isEmpty() && breakBefore(totalLength(buffer), sectionLen, minChars, budget)) {
                flush(buffer, result, budget, minChars);
                buffer = new ArrayList<>();
            }
            buffer.addAll(section);
            // 原子节本身可以超预算，落进空缓冲区就是一块超预算的块，不必也不能再拆
            if (totalLength(buffer) > maxChars) {
                flush(buffer, result, budget, minChars);
                buffer = new ArrayList<>();
            }
        }
        flush(buffer, result, budget, minChars);
        return result;
    }

    /**
     * 到了下一节的边界要不要断开
     * <p>
     * minChars 管下限、maxChars 管目标，两条职责不共用一个阈值：让「攒够 minChars 就断」兼任断点判据，
     * 等于把下限变成事实上的目标——标题密集的文档配 1024 也只切得出 300 上下的块
     */
    private static boolean breakBefore(int bufferLen, int sectionLen, int minChars, ChunkBudget budget) {
        // 还不够一块：并进来即便超预算也认，容忍上限才是底线
        if (bufferLen < minChars) {
            return bufferLen + SEPARATOR.length() + sectionLen > budget.toleranceChars();
        }
        // 装得下就继续装：节边界只是候选断点，装不下时才真断
        return bufferLen + SEPARATOR.length() + sectionLen > budget.maxChars();
    }

    /**
     * 按标题切节：标题起一节，标题之前的散块自成一节
     */
    private static List<List<ChunkDraft>> splitSections(List<ChunkDraft> drafts) {
        // 遇到新标题时结束上一节，保持原始块顺序；文首无标题的内容也单独保留。
        List<List<ChunkDraft>> sections = new ArrayList<>();
        List<ChunkDraft> current = new ArrayList<>();
        for (ChunkDraft draft : drafts) {
            if (draft.heading() && !current.isEmpty()) {
                sections.add(current);
                current = new ArrayList<>();
            }
            current.add(draft);
        }
        if (!current.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    /**
     * 节内切分：整节撑破容忍上限时逐草稿贪心累加，返回未落块的残留缓冲区
     * <p>
     * 残留交回上层而不就地落块，让它有机会与下一节合并——否则一节的尾巴总是单独成块
     *
     * @param carried 上一节留下的缓冲区，一并参与本节的累加
     */
    private static List<ChunkDraft> packWithin(List<ChunkDraft> carried, List<ChunkDraft> section,
                                               ChunkBudget budget, int minChars, List<ChunkDraft> result) {
        // 继承上一节的缓冲，大片段单独落块并尝试带上前导语，普通片段按最大字符数累积。
        int maxChars = budget.maxChars();
        List<ChunkDraft> buffer = new ArrayList<>(carried);
        for (ChunkDraft draft : section) {
            int addLen = contentLength(draft);
            // 自身已顶满块大小、或本就是 Block 被切开的一片：原样落块，只把紧邻的前导语捎进去
            if (draft.piece() || addLen >= maxChars) {
                List<ChunkDraft> leadIn = pollLeadIn(buffer, draft, budget);
                flush(buffer, result, budget, minChars);
                List<ChunkDraft> parts = new ArrayList<>(leadIn);
                parts.add(draft);
                result.add(parts.size() == 1 ? draft : merge(parts));
                buffer = new ArrayList<>();
                continue;
            }
            if (!buffer.isEmpty() && totalLength(buffer) + SEPARATOR.length() + addLen > maxChars) {
                flush(buffer, result, budget, minChars);
                buffer = new ArrayList<>();
            }
            buffer.add(draft);
        }
        return buffer;
    }

    /**
     * 取出可并入大块的前导语，取到的草稿已从缓冲区移除，取不到返回空列表
     * <p>
     * 表格的「保证金单位为元」、代码块的用途说明都写在前一段里，甩成孤块等于把检索入口与内容拆开
     */
    private static List<ChunkDraft> pollLeadIn(List<ChunkDraft> buffer, ChunkDraft target, ChunkBudget budget) {
        // 从缓冲尾部向前挑选可带入的前导片段，只有与目标合并后仍在容忍上限内才从缓冲移走。
        int limit = budget.maxChars();
        int taken = 0;
        int from = buffer.size();
        while (from > 0) {
            int next = taken + SEPARATOR.length() + contentLength(buffer.get(from - 1));
            if (next > limit) {
                break;
            }
            taken = next;
            from--;
        }
        // 只有引入片段与目标合计仍在容忍上限内才合并，否则保留缓冲区不动。
        if (from == buffer.size() || contentLength(target) + taken > budget.toleranceChars()) {
            return List.of();
        }
        List<ChunkDraft> leadIn = new ArrayList<>(buffer.subList(from, buffer.size()));
        buffer.subList(from, buffer.size()).clear();
        return leadIn;
    }

    /**
     * 缓冲区落块，不足最小块体量的余量并回上一块
     * <p>
     * 这是「不产出小于 minChars 的块」的兜底：文档结尾、以及一节撑破容忍上限后剩下的尾巴，都可能是
     * 二十来字的碎屑，单独成块既召不回也白占一个 topK 名额，并回去哪怕跨了节也划算
     */
    private static void flush(List<ChunkDraft> buffer, List<ChunkDraft> result,
                              ChunkBudget budget, int minChars) {
        // 把缓冲合成一个草稿；尾块过小时尝试并到前一块，但合并长度不能超过容忍上限。
        if (buffer.isEmpty()) {
            return;
        }
        ChunkDraft packed = buffer.size() == 1 ? buffer.get(0) : merge(buffer);
        if (!result.isEmpty() && contentLength(packed) < minChars) {
            ChunkDraft previous = result.get(result.size() - 1);
            if (contentLength(previous) + SEPARATOR.length() + contentLength(packed) <= budget.toleranceChars()) {
                result.set(result.size() - 1, merge(List.of(previous, packed)));
                return;
            }
        }
        result.add(packed);
    }

    /**
     * 合并多块：展示文本与检索正文分别拼接，资产取并集，章节路径取各块的公共前缀
     * <p>
     * 检索正文按"显式值优先、否则回落展示文本"逐块拼接：图片块的检索正文特意去掉了 URL 噪声，
     * 一律取展示文本会让向量退化成带 URL 的文本；路径取公共前缀而非其中某一块的，
     * 前导语可能来自上一节，取大块那份等于把上一节的内容记到本节名下
     */
    private static ChunkDraft merge(List<ChunkDraft> parts) {
        // 分别拼接展示内容与嵌入正文，汇总资产，章节路径取共同前缀，溯源沿用首个片段。
        StringBuilder content = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean hasExplicitBody = false;
        boolean heading = false;
        List<AssetRef> assets = new ArrayList<>();

        for (ChunkDraft draft : parts) {
            appendPart(content, draft.content());
            appendPart(body, draft.effectiveBody());
            hasExplicitBody |= draft.hasExplicitBody();
            heading |= draft.heading();
            assets.addAll(draft.metadata().assets());
        }

        // 合并内容后仅保留共同章节路径，资产汇总，溯源采用首个片段。
        ChunkMetadata merged = ChunkMetadata.builder()
                .outlinePath(parts.get(0).metadata().outlinePath().subList(0, commonPrefixLength(parts)))
                .assets(assets)
                .provenance(parts.get(0).metadata().provenance())
                .build();
        return new ChunkDraft(content.toString(), hasExplicitBody ? body.toString() : null,
                merged, false, heading);
    }

    /**
     * 计算两个章节路径或一组草稿的共同路径前缀长度，供合并切片保留共同章节信息。
     */
    private static int commonPrefixLength(List<ChunkDraft> drafts) {
        // 从路径起点比较共同章节层级，遇到不同标题即停止，供合并块保留共有上下文。
        List<String> first = drafts.get(0).metadata().outlinePath();
        int common = first.size();
        for (ChunkDraft draft : drafts) {
            common = Math.min(common, commonPrefixLength(first, draft.metadata().outlinePath()));
        }
        return common;
    }

    /**
     * 计算两个章节路径或一组草稿的共同路径前缀长度，供合并切片保留共同章节信息。
     */
    private static int commonPrefixLength(List<String> a, List<String> b) {
        // 从路径起点比较共同章节层级，遇到不同标题即停止，供合并块保留共有上下文。
        int limit = Math.min(a.size(), b.size());
        int i = 0;
        while (i < limit && Objects.equals(a.get(i), b.get(i))) {
            i++;
        }
        return i;
    }

    /**
     * 仅追加非空正文，已有内容时先补统一分隔符，避免空片段产生多余分隔。
     */
    private static void appendPart(StringBuilder sb, String part) {
        // 跳过空白文本，非首段之间补统一分隔符，避免合并时把相邻段落粘连。
        if (!StringUtils.hasText(part)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(SEPARATOR);
        }
        sb.append(part);
    }

    /**
     * 多个草稿拼起来的长度，含它们之间的分隔符
     */
    private static int totalLength(List<ChunkDraft> drafts) {
        // 累加各片段正文长度及片段间分隔符长度，作为打包时的实际预算口径。
        int len = 0;
        for (int i = 0; i < drafts.size(); i++) {
            len += (i == 0 ? 0 : SEPARATOR.length()) + contentLength(drafts.get(i));
        }
        return len;
    }

    /**
     * 取得草稿有效正文的字符长度，空白正文记为零，用于打包预算计算。
     */
    private static int contentLength(ChunkDraft draft) {
        // 空白内容按零计算，其余取展示正文的字符数。
        return StringUtils.hasText(draft.content()) ? draft.content().length() : 0;
    }
}
