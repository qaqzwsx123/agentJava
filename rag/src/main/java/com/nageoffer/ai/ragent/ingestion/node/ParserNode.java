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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ParserSettings;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析节点
 * 负责将输入的字节流（如 PDF、Word、Excel 等）解析为结构化的文本或文档对象
 */
@Component
public class ParserNode implements IngestionNode {
    // 文档解析节点 负责将输入的字节流（如 PDF、Word、Excel 等）解析为结构化的文本或文档对象。

    private final ObjectMapper objectMapper;
    private final ParserRegistry parserRegistry;

    public ParserNode(ObjectMapper objectMapper, ParserRegistry parserRegistry) {
        // 保存设置转换器与解析路由表，供按文件格式选择解析实现。
        this.objectMapper = objectMapper;
        this.parserRegistry = parserRegistry;
    }

    @Override
    public String getNodeType() {
        // 返回解析节点类型，供引擎识别这一处理阶段。
        return IngestionNodeType.PARSER.getValue();
    }

    /**
     * 校验原始字节和允许的文件类型，按 MIME 与默认解析档位选解析器，再构建结构化文档。
     * 文件名和任务 ID 注入解析选项，用于溯源及资产目录；无法找到解析器时明确失败。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // 验证文件类型是否符合配置
        validateMimeType(settings, mimeType, fileName);

        // 规则主要提供格式校验与解析选项，真正的解析器仍由 MIME 和档位注册表选择。
        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);

        // 按 (MIME × 档位) 查注册表；不匹配显式抛错，不静默兜底
        // 管道暂无档位入口，走默认档；档位随 IngestionSpec 下发是内核化之后的事
        DocumentParser parser = parserRegistry.find(mimeType, ParseProfile.defaultProfile()).orElse(null);
        if (parser == null) {
            return NodeResult.fail(new ClientException(
                    "未找到 MIME [" + mimeType + "] 对应的解析器,fileName=" + fileName));
        }

        Map<String, Object> ruleOptions = rule == null ? null : rule.getOptions();
        Map<String, Object> options = new HashMap<>(ruleOptions != null ? ruleOptions : Collections.emptyMap());

        // 把 sourceFile 注入 options，供解析器写入 Provenance.sourceFile
        if (StringUtils.hasText(fileName) && !options.containsKey("sourceFile")) {
            options.put("sourceFile", fileName);
        }

        // 把 documentId(=taskId)注入 options，供解析器做资产 key 命名 assets/{documentId}/...
        // 图片解析必需，MinerU 抽图同样受益(资产稳定归属文档目录，不再落随机 UUID)
        if (StringUtils.hasText(context.getTaskId()) && !options.containsKey("documentId")) {
            options.put("documentId", context.getTaskId());
        }

        // v1.1：调 parseStructured 拿结构化 Block 列表
        ParsedDocument parsed = parser.parseStructured(context.getRawBytes(), mimeType, options);
        List<Block> blocks = parsed.blocks() == null ? List.of() : parsed.blocks();

        // 从 blocks 渲染纯文本（给老路径 / ChunkerNode fallback 用）
        String renderedText = BlockTextRenderer.render(blocks);
        context.setRawText(renderedText);

        StructuredDocument document = StructuredDocument.builder()
                .text(renderedText)
                .blocks(blocks)
                .metadata(parsed.metadata())
                .build();
        context.setDocument(document);

        return NodeResult.ok(String.format("解析器=%s, blocks=%d, 文本长度=%d",
                parser.getParserType(), blocks.size(), renderedText.length()));
    }

    /**
     * 验证文件类型是否符合配置的规则
     * 如果配置了规则但文件类型不匹配，则抛出异常
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            // 没有配置规则，允许所有类型
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        // 检查是否有匹配的规则
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            // 构建允许的类型列表用于错误提示
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    /**
     * 将 JSON 配置转为解析设置，未配置时使用空规则列表。
     */
    private ParserSettings parseSettings(JsonNode node) {
        // 空配置采用无规则的默认设置，其余 JSON 转换为解析规则列表。
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    /**
     * 按文件类型匹配首条规则，ALL 可匹配任意类型；无规则或没有匹配时返回 null。
     */
    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        // 先推断文件类型，再按配置顺序寻找首条匹配类型或 ALL 的规则，跳过空规则。
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 优先通过扩展名判断业务文件类型，其次通过 MIME 推断，无法识别返回 UNKNOWN。
     */
    private String resolveType(String mimeType, String fileName) {
        // 文件扩展名优先，无法识别时再按 MIME 归类，最终回落 UNKNOWN。
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    /**
     * 将受支持的文件扩展名映射为统一业务类型，未提供文件名或扩展名未知时返回 null。
     */
    private String resolveTypeByName(String fileName) {
        // 按不区分大小写的扩展名归类常见文档和图片，无法识别时返回 null 供 MIME 判断接续。
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    /**
     * 统一规则中的类型别名，例如 DOCX 转 WORD、MD 转 MARKDOWN，通配配置转 ALL。
     */
    private String normalizeType(String raw) {
        // 统一配置中的类型别名，把扩展名、简称和通配标记映射到约定分类。
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
