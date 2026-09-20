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

import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.HtmlTableBlock;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 表格 chunker：按 tr 边界切分，每块重复表头行并包回完整 table
 * <p>
 * 不转成管道表：合并单元格与单元格内的换行在展开成二维表时会失真，展示与检索都用同一份 HTML
 */
@Component
public class HtmlTableChunker implements BlockChunker<HtmlTableBlock> {

    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>.*?</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 值为 1 的 colspan / rowspan 不表达任何合并，MinerU 却逐格都写，一张十来行的表能被它撑到三倍
     */
    private static final Pattern NO_OP_SPAN = Pattern.compile("\\s+(?:colspan|rowspan)\\s*=\\s*[\"']?1[\"']?",
            Pattern.CASE_INSENSITIVE);

    private static final String TABLE_CLOSE = "</table>";

    @Override
    public Class<HtmlTableBlock> blockType() {
        // 声明处理原始 HTML 表格块，避免把 HTML 当普通段落切断。
        return HtmlTableBlock.class;
    }

    /**
     * 按完整表格行分组，重复表头并计入外壳预算，同时限制每块行数。
     * 无可切行边界时保留整表，避免切坏 HTML；分组时允许单行自身超过字符预算。
     */
    @Override
    public List<ChunkDraft> chunk(HtmlTableBlock block, ChunkContext ctx) {
        if (block == null || !StringUtils.hasText(block.html())) {
            return List.of();
        }
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();

        String html = NO_OP_SPAN.matcher(block.html()).replaceAll("");
        List<String> rows = splitRows(html);
        // 只有表头或压根扫不出行：没有可切的边界，原样落块，宁可超预算也不切在标签中间
        if (rows.size() < 2) {
            return List.of(ChunkDraft.of(html, metadata));
        }

        String open = openTag(html);
        String header = rows.get(0);
        int maxRows = Math.max(1, ctx.budget().rowsPerChunk());
        // 整张表撑得住容忍上限就不切，切开后每块虽重带表头，跨块的行间对比仍然做不了
        int budget = rows.size() - 1 <= maxRows && html.length() <= ctx.budget().toleranceChars()
                ? ctx.budget().toleranceChars()
                : Math.max(1, ctx.budget().maxChars());
        // 外壳与表头每块都要重复，先从预算里扣掉，否则渲染出来必然超
        int overhead = open.length() + TABLE_CLOSE.length() + header.length();

        List<ChunkDraft> result = new ArrayList<>();
        List<String> group = new ArrayList<>();
        int groupLen = 0;
        for (String row : rows.subList(1, rows.size())) {
            boolean overCap = group.size() >= maxRows;
            boolean overBudget = !group.isEmpty() && overhead + groupLen + row.length() > budget;
            if (overCap || overBudget) {
                result.add(ChunkDraft.of(render(open, header, group), metadata));
                group = new ArrayList<>();
                groupLen = 0;
            }
            group.add(row);
            groupLen += row.length();
        }
        result.add(ChunkDraft.of(render(open, header, group), metadata));
        return ChunkDraft.pieces(result);
    }

    /**
     * 切出各 tr 片段（含标签本身），首个即表头行
     */
    private static List<String> splitRows(String html) {
        // 按行标签匹配并收集完整 HTML 行，后续切分保持每行结构完整。
        List<String> rows = new ArrayList<>();
        Matcher matcher = ROW.matcher(html);
        while (matcher.find()) {
            rows.add(matcher.group());
        }
        return rows;
    }

    /**
     * 取原始 table 开标签而非写死，作者写在上面的 border / class 等属性得跟着每一块走
     */
    private static String openTag(String html) {
        // 保留原表格起始标签及属性，无法识别结束位置时使用默认 table 标签。
        int end = html.indexOf('>');
        return end < 0 ? "<table>" : html.substring(0, end + 1);
    }

    /**
     * 把表格开始标签、重复表头和当前行组合为独立完整的 HTML 表格。
     */
    private static String render(String open, String header, List<String> rows) {
        // 依次拼接表格起始标签、重复表头和当前行组，最后闭合表格标签。
        StringBuilder sb = new StringBuilder(open).append(header);
        for (String row : rows) {
            sb.append(row);
        }
        return sb.append(TABLE_CLOSE).toString();
    }
}
