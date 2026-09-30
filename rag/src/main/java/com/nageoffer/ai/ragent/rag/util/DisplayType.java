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

package com.nageoffer.ai.ragent.rag.util;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 展示类型：文件在界面上的短标签，唯一产生点 {@link #of(String, String)}，上传时算一次落库
 * <p>
 * 展示语义的权威源是上传时的扩展名，字节语义的权威源是 MIME 探测，两者不得互相导出——
 * {@code .md} 按字节探测通常只得到 {@code text/plain}，展示标签若从 MIME 反推，用户上传的
 * "规范.md"在界面上就会显示成 txt；此枚举永不参与路由，解析器与分块器的选择只认 MIME
 */
public enum DisplayType {
    // 文件展示类型枚举：按原始文件名及必要的 MIME 辅助信息生成界面短标签，不参与解析器路由，避免 Markdown 等文件被错误显示为 txt。

    PDF("pdf"),
    WORD("doc"),
    WORD_X("docx"),
    PPT("ppt"),
    PPT_X("pptx"),
    EXCEL("xls"),
    EXCEL_X("xlsx"),
    CSV("csv"),
    MARKDOWN("markdown"),
    TEXT("txt"),
    HTML("html"),
    JSON("json"),
    XML("xml"),
    RTF("rtf"),
    PNG("png"),
    JPG("jpg"),
    SVG("svg"),

    /**
     * 兜底：认不出一律 other，不回落成原始 MIME 字符串（落库列是 {@code VARCHAR(16)}）
     */
    OTHER("other");

    /**
     * 扩展名 → 展示类型，权威映射
     */
    private static final Map<String, DisplayType> BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", PDF),
            Map.entry("doc", WORD),
            Map.entry("docx", WORD_X),
            Map.entry("ppt", PPT),
            Map.entry("pptx", PPT_X),
            Map.entry("xls", EXCEL),
            Map.entry("xlsx", EXCEL_X),
            Map.entry("csv", CSV),
            Map.entry("md", MARKDOWN),
            Map.entry("markdown", MARKDOWN),
            Map.entry("txt", TEXT),
            Map.entry("text", TEXT),
            Map.entry("html", HTML),
            Map.entry("htm", HTML),
            Map.entry("json", JSON),
            Map.entry("xml", XML),
            Map.entry("rtf", RTF),
            Map.entry("png", PNG),
            Map.entry("jpg", JPG),
            Map.entry("jpeg", JPG),
            Map.entry("svg", SVG)
    );

    /**
     * MIME → 展示类型，仅在没有扩展名时兜底使用
     */
    private static final Map<String, DisplayType> BY_MIME = Map.ofEntries(
            Map.entry("application/pdf", PDF),
            Map.entry("application/x-pdf", PDF),
            Map.entry("application/msword", WORD),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", WORD_X),
            Map.entry("application/vnd.ms-powerpoint", PPT),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", PPT_X),
            Map.entry("application/vnd.ms-excel", EXCEL),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", EXCEL_X),
            Map.entry("text/csv", CSV),
            Map.entry("text/markdown", MARKDOWN),
            Map.entry("text/x-markdown", MARKDOWN),
            Map.entry("text/plain", TEXT),
            Map.entry("text/html", HTML),
            Map.entry("application/json", JSON),
            Map.entry("application/xml", XML),
            Map.entry("application/rtf", RTF),
            Map.entry("image/png", PNG),
            Map.entry("image/jpeg", JPG),
            Map.entry("image/svg+xml", SVG)
    );

    /**
     * 表格类判定：前后端共用这一份，不各自维护扩展名列表
     */
    private static final Set<DisplayType> TABULAR = Set.of(EXCEL, EXCEL_X, CSV);

    private final String code;

    DisplayType(String code) {
        // 保存文件展示类型的稳定编码，供接口与前端选择预览方式。
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        // 返回展示类型配置编码，避免直接暴露枚举内部名称。
        return code;
    }

    /**
     * 是否表格类文件
     */
    public boolean isTabular() {
        // 通过表格类型集合判断是否需要展示表格专属解析设置。
        return TABULAR.contains(this);
    }

    /**
     * 该展示类型的全部扩展名，反查 {@link #BY_EXTENSION} 下发给前端做界面判定
     * <p>
     * 每个类型的 code 本身就是它扩展名集合的成员（{@code markdown ∈ {md, markdown}}、
     * {@code jpg ∈ {jpg, jpeg}}），故拿落库的 {@code fileType} 或原始扩展名来比这份清单结果都对
     */
    public Set<String> extensions() {
        // 从扩展名映射中收集属于当前展示类型的后缀，并使用排序集合保持输出稳定。
        Set<String> result = new TreeSet<>();
        BY_EXTENSION.forEach((extension, type) -> {
            if (type == this) {
                result.add(extension);
            }
        });
        return result;
    }

    /**
     * 唯一产生点：扩展名优先，无扩展名时才看 MIME，都认不出就是 {@link #OTHER}
     */
    public static DisplayType of(String filename, String mimeType) {
        // 先按文件后缀识别展示类型，后缀无法识别时再按 MIME 判断，最后回落 OTHER。
        DisplayType byExtension = BY_EXTENSION.get(extractExtension(filename));
        if (byExtension != null) {
            return byExtension;
        }
        return BY_MIME.getOrDefault(normalizeMime(mimeType), OTHER);
    }

    /**
     * 反解落库值，未知一律 {@link #OTHER}
     */
    public static DisplayType from(String code) {
        // 去空白并统一小写后匹配展示编码，缺省或未知值返回 OTHER。
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (DisplayType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return OTHER;
    }

    /**
     * 去除路径部分后提取小写扩展名，无有效扩展名时返回空字符串。
     */
    private static String extractExtension(String filename) {
        // 先去掉路径部分，再读取最后一个有效后缀并统一小写，缺失后缀时返回空字符串。
        if (filename == null) {
            return "";
        }
        String name = filename.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0 && separator + 1 < name.length()) {
            name = name.substring(separator + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 统一 MIME 大小写并去掉分号参数，供文件预览类型判断使用。
     */
    private static String normalizeMime(String mimeType) {
        // 规范 MIME 大小写并去掉分号后的附加参数，便于按纯类型匹配展示方式。
        if (mimeType == null || mimeType.isBlank()) {
            return "";
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        return semicolon >= 0 ? normalized.substring(0, semicolon).trim() : normalized;
    }
}
