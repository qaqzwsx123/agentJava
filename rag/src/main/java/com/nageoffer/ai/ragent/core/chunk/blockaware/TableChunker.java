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
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 chunker：按 key-value 渲染长度累加到预算，每块都带完整表头，单行超预算时整行原子成块
 * <p>
 * {@code rowsPerChunk} 只作硬上限，兼顾宽表不超嵌入上限、窄表不过度碎片化；展示文本是完整
 * markdown 表格，向量文本改用 {@code 列名: 值}，因为 markdown 表格靠位置对齐列名与值、嵌入模型
 * 读不懂位置；表头不拼进向量文本，KV 正文已逐格自带列名，重复前缀会把同一张表各块的向量朝同一
 * 方向拉、压缩块间距离，表身份由 sheet 名经章节路径承载
 */
@Component
public class TableChunker implements BlockChunker<TableBlock> {
    // 结构化表格分块器：按完整数据行分组，同时考虑字符预算和每块行数上限；展示内容生成带表头的 Markdown 表格，向量正文使用“列名: 值”表达字段关系。

    @Override
    public Class<TableBlock> blockType() {
        // 声明处理结构化表格块，后续可以分别生成展示表格和适合检索的键值文本。
        return TableBlock.class;
    }

    /**
     * 以键值形式的嵌入文本计算预算，按完整行及行数上限贪心分组，尽量保留整表关联。
     * 每组同时生成可展示 Markdown 和可嵌入正文；单行超长时不从中间硬切。
     */
    @Override
    public List<ChunkDraft> chunk(TableBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        List<String> headers = block.headers() == null ? List.of() : block.headers();
        List<List<String>> rows = block.rows() == null ? List.of() : block.rows();
        if (headers.isEmpty() && rows.isEmpty()) {
            return List.of();
        }

        // 预算只量 KV 行本身，刻意不扣装配器追加的章节路径前缀：真去扣，深层章节会把可用预算逼近 0，
        // 退化成每行一块、每块大半是逐字相同的前缀
        int maxRows = Math.max(1, ctx.budget().rowsPerChunk());
        // 整张表撑得住容忍上限就不切，切开后每块虽重带表头，跨块的行间对比仍然做不了
        int budget = rows.size() <= maxRows
                && renderKeyValueRows(headers, rows).length() <= ctx.budget().toleranceChars()
                ? ctx.budget().toleranceChars()
                : Math.max(1, ctx.budget().maxChars());

        List<ChunkDraft> result = new ArrayList<>();

        if (rows.isEmpty()) {
            result.add(buildDraft(block, ctx, headers, List.of()));
            return result;
        }

        // 贪心累加：超硬上限或（非空且加入下一行会超预算）则先落块
        List<List<String>> group = new ArrayList<>();
        int groupCost = 0;
        for (List<String> row : rows) {
            int rowCost = renderKeyValueRow(headers, row).length();
            boolean overCap = group.size() >= maxRows;
            boolean overBudget = !group.isEmpty() && groupCost + rowCost > budget;
            if (overCap || overBudget) {
                result.add(buildDraft(block, ctx, headers, group));
                group = new ArrayList<>();
                groupCost = 0;
            }
            group.add(row);
            groupCost += rowCost;
        }
        result.add(buildDraft(block, ctx, headers, group));
        return ChunkDraft.pieces(result);
    }

    /**
     * 为表格组装展示用 Markdown 与嵌入用键值正文，并保留章节路径和原始位置。
     */
    private ChunkDraft buildDraft(TableBlock block, ChunkContext ctx, List<String> headers, List<List<String>> rows) {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .provenance(block.provenance())
                .build();
        // 章节路径由装配器统一拼进向量文本，此处只给 key-value 正文，避免重复前缀
        return ChunkDraft.of(renderMarkdownTable(headers, rows), renderKeyValueRows(headers, rows), metadata);
    }

    /**
     * 逐行渲染表头与单元格的键值正文，跳过空行并用换行连接，供向量嵌入使用。
     */
    private String renderKeyValueRows(List<String> headers, List<List<String>> rows) {
        // 逐行转换成表头与单元格的键值描述，忽略没有有效内容的行。
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            String line = renderKeyValueRow(headers, row);
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 单行渲染成 {@code 列名: 值}，"; " 拼接、跳过空值、整行空返回空串；同时用作预算切分的行体量度量
     */
    private String renderKeyValueRow(List<String> headers, List<String> row) {
        // 跳过空单元格，用对应表头作为键；缺少表头时保留值，并把单元格内部换行压成空格。
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String key = c < headers.size() ? headers.get(c) : "";
            if (!line.isEmpty()) {
                line.append("; ");
            }
            if (!key.isEmpty()) {
                line.append(oneLine(key)).append(": ");
            }
            line.append(oneLine(value));
        }
        return line.toString();
    }

    /**
     * 把 cell 内换行压成空格：key 与 value 之间夹一个断行会影响检索
     */
    private static String oneLine(String text) {
        // 统一把各种换行符替换为空格，避免键值描述被拆成多行。
        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    /**
     * 依次生成表头、分隔线和数据行，去掉末尾换行，供切片展示使用。
     */
    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        // 先写表头和分隔行，再追加数据行，最后去掉多余尾部换行。
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        appendSeparator(sb, headers.size());
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 清理单元格后追加 Markdown 表格行，避免单元格特殊字符破坏行结构。
     */
    private void appendRow(StringBuilder sb, List<String> cells) {
        // 为各单元格补齐竖线边界，并通过转义方法保证内容不会破坏表格列结构。
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(sanitizeCell(cell)).append(" |");
        }
        sb.append('\n');
    }

    /**
     * 清洗 cell 以适配 markdown 表格语法
     * <p>
     * 单元格内换行（Excel Alt+Enter）转 {@code <br>}，裸换行会从中间截断表格行、使整块退化成普通段落；
     * 竖线转义，cell 内的字面 {@code |} 会被误判为列分隔
     */
    private String sanitizeCell(String cell) {
        // 空值转为空字符串，转义单元格中的竖线，并将内部换行转换为 HTML 换行标记。
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        return cell.replace("|", "\\|").replaceAll("\\r\\n|\\r|\\n", "<br>");
    }

    /**
     * 按列数生成 Markdown 表头分隔线。
     */
    private void appendSeparator(StringBuilder sb, int colCount) {
        // 按列数生成 Markdown 表头分隔行，负数列数按零处理。
        sb.append('|');
        sb.append("---|".repeat(Math.max(0, colCount)));
        sb.append('\n');
    }

}
