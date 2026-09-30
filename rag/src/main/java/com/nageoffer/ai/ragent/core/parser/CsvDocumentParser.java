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

package com.nageoffer.ai.ragent.core.parser;

import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.detect.AutoDetectReader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSV 文档解析器：把 CSV 当作一张规整的 key-val 表，首行表头、其余数据行，产出单个 {@link TableBlock}，
 * 下游与 Excel 共用 TableChunker 做行级切分 + key-value 嵌入
 * <p>
 * 认领精确 MIME 以优先于 Tika 的 {@code text/*} 通配，避免 CSV 被当平文本切碎
 */
@Slf4j
@Component
public class CsvDocumentParser implements DocumentParser {
    // CSV 解析器：探测字符编码并处理引号、逗号和字段内换行，过滤空行，以首行为表头整理数据行，产出带文件来源信息的 TableBlock。

    public static final String OPT_SOURCE_FILE = "sourceFile";

    private static final char BOM = '\uFEFF';

    /**
     * 返回 CSV 解析器的注册类型标识。
     */
    @Override
    public String getParserType() {
        // 返回 CSV 解析器标识，供元数据记录和路由诊断使用。
        return ParserType.CSV.getType();
    }

    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        // 登记快速档位的常见 CSV MIME 别名，使不同探测结果落到同一解析器。
        return Map.of(ParseProfile.FAST, Set.of(
                "text/csv",
                "application/csv",
                "text/comma-separated-values"
        ));
    }

    /**
     * 解码并解析 CSV，移除空行，将首行视为表头并按表头宽度补齐数据行，产出带文件溯源的表格块。
     */
    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        // 解码并解析 CSV，去掉全空行后用首行作表头，后续行补足列数并生成带来源信息的表格块。
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text = decode(content);
        List<List<String>> grid = parseCsv(text);
        grid.removeIf(CsvDocumentParser::isBlankRow);
        if (grid.isEmpty()) {
            return ParsedDocument.of(List.of());
        }

        // 首个有效行确定表头和宽度，后续数据行由 padRow 规范为该宽度。
        List<String> headers = grid.get(0);
        int width = headers.size();
        List<List<String>> rows = new ArrayList<>(grid.size() - 1);
        for (int i = 1; i < grid.size(); i++) {
            rows.add(padRow(grid.get(i), width));
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        TableBlock block = new TableBlock(prov, headers, rows);
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "rows", rows.size(),
                "columns", width
        ));
    }

    /**
     * 用 Tika {@link AutoDetectReader} 探测字符集解码（兼容 UTF-8 / GBK / UTF-16 等），
     * 失败回退 UTF-8；统一剥离开头 BOM
     */
    private String decode(byte[] content) {
        // 先自动探测字符集读取，探测失败时记录日志并按 UTF-8 解码，两条路径都会去掉开头 BOM。
        try (Reader reader = new AutoDetectReader(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder(content.length);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return stripBom(sb.toString());
        } catch (Exception e) {
            log.warn("CSV 字符集探测失败，回退 UTF-8", e);
            return stripBom(new String(content, StandardCharsets.UTF_8));
        }
    }

    /**
     * 移除文本开头的 BOM，避免首列表头多出不可见字符。
     */
    private static String stripBom(String text) {
        // 只移除文本开头的 BOM 字符，其余内容保持原样。
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /**
     * RFC4180 解析：引号内的逗号 / 换行视作普通字符，{@code ""} 解析为字面量引号
     */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r' || c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                // 吞掉 CRLF 的第二个字符
                i += (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') ? 2 : 1;
            } else {
                field.append(c);
                i++;
            }
        }
        // 末尾未以换行结束的残留记录
        if (!field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    /**
     * 数据行短于表头时右侧补空串对齐（超出则原样保留）
     */
    private static List<String> padRow(List<String> row, int width) {
        // 短行补空字符串到表头宽度，等宽或更宽的行保留原样，不截断额外列。
        if (row.size() >= width) {
            return row;
        }
        List<String> padded = new ArrayList<>(width);
        padded.addAll(row);
        while (padded.size() < width) {
            padded.add("");
        }
        return padded;
    }

    /**
     * 检查该行所有单元格是否均为空白，供 CSV 解析过滤空行。
     */
    private static boolean isBlankRow(List<String> row) {
        // 只要有一个非空白单元格就保留该行，否则判定为全空行。
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从解析选项取得源文件名，未配置时使用空字符串。
     */
    private String extractSourceFile(Map<String, Object> options) {
        // 从解析选项取得文件名，未传选项或字段时使用空字符串。
        if (options == null) {
            return "";
        }
        Object v = options.get(OPT_SOURCE_FILE);
        return v == null ? "" : v.toString();
    }
}
