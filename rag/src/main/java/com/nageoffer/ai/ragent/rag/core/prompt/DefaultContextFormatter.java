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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    /**
     * 按证据准入后的意图数量选择无意图、单意图或多意图模板，限制进入上下文的切片数量。
     */
    @Override
    public String formatKbContext(List<NodeScore> kbIntents,
                                  Set<String> eligibleIntentIds,
                                  List<RetrievedChunk> rerankedChunks,
                                  int contextTopK) {
        // 先限定真正获得证据准入的意图，再按零个、单个或多个有效意图选择上下文模板。
        if (CollUtil.isEmpty(rerankedChunks)) {
            return "";
        }

        // 先按证据准入集合筛选，随后根据有效意图数量选择模板分支。
        List<NodeScore> eligibleIntents = eligibleIntents(kbIntents, eligibleIntentIds);
        String branch = switch (eligibleIntents.size()) {
            case 0 -> "无可用意图";
            case 1 -> "单意图";
            default -> "多意图";
        };
        log.info("检索归因 - 提示词分支: {}, 可用意图: {}",
                branch,
                eligibleIntents.stream().map(ns -> ns.getNode().getName()).toList());
        if (eligibleIntents.isEmpty()) {
            return formatChunksWithoutIntent(rerankedChunks, contextTopK);
        }
        if (eligibleIntents.size() > 1) {
            return formatMultiIntentContext(eligibleIntents, rerankedChunks, contextTopK);
        }
        return formatSingleIntentContext(eligibleIntents.get(0), rerankedChunks, contextTopK);
    }

    /**
     * 只保留节点有效且 ID 位于证据准入集合中的意图，避免无依据的意图规则进入提示词。
     */
    private List<NodeScore> eligibleIntents(List<NodeScore> kbIntents,
                                            Set<String> eligibleIntentIds) {
        // 过滤空候选，只保留节点 ID 在证据准入集合中的意图，避免无证据意图注入专属规则。
        if (CollUtil.isEmpty(kbIntents) || CollUtil.isEmpty(eligibleIntentIds)) {
            return List.of();
        }
        return kbIntents.stream()
                .filter(nodeScore -> nodeScore != null && nodeScore.getNode() != null)
                .filter(nodeScore -> eligibleIntentIds.contains(nodeScore.getNode().getId()))
                .toList();
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, List<RetrievedChunk> rerankedChunks, int topK) {
        // 取该意图的提示片段，去重并按文档组织召回块，再一起渲染知识上下文。
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        String docBlocks = renderChunksGroupedByDoc(distinctChunks(rerankedChunks), topK);
        return renderKbSection(renderSnippetRules(snippet), docBlocks);
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents,
                                            List<RetrievedChunk> rerankedChunks,
                                            int topK) {
        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        String snippetSection = "";
        if (!snippets.isEmpty()) {
            String numberedRules = IntStream.range(0, snippets.size())
                    .mapToObj(i -> (i + 1) + ". " + snippets.get(i))
                    .collect(Collectors.joining("\n"));
            snippetSection = renderSnippetRules(numberedRules);
        }

        // 2. 合并所有意图的文档片段（按 chunk id 去重，保持相关性顺序）
        List<RetrievedChunk> allChunks = distinctChunks(rerankedChunks);

        if (allChunks.isEmpty()) {
            return snippetSection;
        }

        String docBlocks = renderChunksGroupedByDoc(allChunks, topK);
        return renderKbSection(snippetSection, docBlocks);
    }

    /**
     * 没有可用意图时仍可将去重后的召回切片按文档渲染，不附加意图规则。
     */
    private String formatChunksWithoutIntent(List<RetrievedChunk> rerankedChunks, int topK) {
        // 没有有效意图时仍可展示去重后的召回证据，但不附加意图专属片段。
        List<RetrievedChunk> chunks = distinctChunks(rerankedChunks);
        if (chunks.isEmpty()) {
            return "";
        }

        String docBlocks = renderChunksGroupedByDoc(chunks, topK);
        return renderKbSection("", docBlocks);
    }

    /**
     * 按稳定切片键去重，保留首次出现顺序，避免同一证据重复进入模型上下文。
     */
    private List<RetrievedChunk> distinctChunks(List<RetrievedChunk> chunks) {
        // 按统一分块键去重，保留首次出现的块与顺序，避免不同通道的同一证据重复进入提示词。
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.forEach(chunk -> distinct.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));
        return new ArrayList<>(distinct.values());
    }

    /**
     * 将规则片段和文档证据渲染为统一知识区段。
     */
    private String renderKbSection(String snippetSection, String docBlocks) {
        // 把意图片段与文档块填入知识区段模板，模板结构集中在资源文件维护。
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "doc_blocks", docBlocks
        ));
    }

    /**
     * 把非空意图规则渲染为规则片段，空规则不生成额外模板文本。
     */
    private String renderSnippetRules(String snippet) {
        // 片段为空时不渲染规则区段，否则把意图补充规则套入约定模板。
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "snippet-rules", Map.of("rules", snippet));
    }

    /**
     * 按文档聚合渲染 chunk 列表
     * <p>
     * 文档之间按相关性排序（各文档首个命中块在原列表中的顺序，即该文档最佳块的排名），
     * 文档内部按 {@code chunkIndex} 升序还原原文顺序；docId 缺失的块各自单独成组、留在原位
     */
    private String renderChunksGroupedByDoc(List<RetrievedChunk> chunks, int topK) {
        // 先按 Top-K 限制总块数，再按文档分组；无文档 ID 的块各自成组，避免错误合并。
        long limit = topK > 0 ? topK : Long.MAX_VALUE;
        List<RetrievedChunk> limited = chunks.stream().limit(limit).toList();
        if (limited.isEmpty()) {
            return "";
        }

        // 按 docId 分组：LinkedHashMap 保持首次出现顺序 = 文档间的相关性排序；docId 为空的块各自单独成组
        LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk chunk : limited) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + (anonymousSeq++);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        return groups.values().stream()
                .map(this::renderDocBlock)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个文档块：组内按序号排序后拼接，只带内部 docId 作为锚点
     * <p>
     * 刻意不注入文档标题：标题一旦进入上下文，模型就会写出"出自《XX》"之类的归因表述，
     * 而提示词层面的禁令压不住。资料之间的区分交给 {@code ref} 编号，文档名只在前端来源列表展示
     */
    private String renderDocBlock(List<RetrievedChunk> group) {
        // 同一文档内按块序号还原阅读顺序，存在有效文档 ID 时渲染可引用的文档块，否则用匿名模板。
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // 文档之间保留相关性顺序，文档内部恢复原块顺序，使模型阅读同文档证据时更连贯。
        String chunks = joinDocBody(ordered);
        String docId = sanitizeAttribute(resolveDocId(group));
        if (StrUtil.isNotBlank(docId)) {
            return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block", Map.of(
                    "doc_id", docId,
                    "chunks", chunks
            ));
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block-anonymous", Map.of(
                "chunks", chunks
        ));
    }

    /**
     * 组内拼接文本：同文档的块按 index 排好后用换行顺次拼接
     */
    private String joinDocBody(List<RetrievedChunk> ordered) {
        // 提取有效块正文并用换行连接，保留同一文档内已排好的顺序。
        return ordered.stream()
                .map(RetrievedChunk::getText)
                .map(StrUtil::emptyIfNull)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清洗属性值里会破坏伪标签结构的字符（引号、尖括号）
     */
    private String sanitizeAttribute(String value) {
        // 移除引号和尖括号再去空白，避免文档 ID 破坏提示词中的属性结构。
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.replaceAll("[\"<>]", "").trim();
    }

    /**
     * 从同组切片中取首个非空文档 ID，供证据分组和引用定位使用。
     */
    private String resolveDocId(List<RetrievedChunk> group) {
        // 从当前文档分组找到首个有效文档 ID，没有时返回空字符串。
        return group.stream()
                .map(RetrievedChunk::getDocId)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
    }

}
