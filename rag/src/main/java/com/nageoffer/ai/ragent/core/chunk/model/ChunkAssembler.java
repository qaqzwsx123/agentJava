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

package com.nageoffer.ai.ragent.core.chunk.model;

import cn.hutool.core.util.IdUtil;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 块装配器：草稿 → 成品块的唯一通道
 * <p>
 * 展示文本原样落地，章节上下文只补进向量文本：{@code content} 是文档原貌，标题按原文位置已在正文里，
 * 再拼一份合成前缀等于篡改原文
 */
public final class ChunkAssembler {

    private static final String CONTEXT_SEPARATOR = "\n";

    private static final String OUTLINE_SEPARATOR = " / ";

    private ChunkAssembler() {
        // 统一使用静态装配方法，私有构造器阻止实例化工具类。
    }

    /**
     * 批量装配：草稿须已完成切分与合并，序号按列表顺序从 0 起分配
     */
    public static List<Chunk> assembleAll(List<ChunkDraft> drafts) {
        // 保持草稿顺序分配连续序号，逐个装配为正式分块，空输入返回空列表。
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            chunks.add(assemble(i, drafts.get(i)));
        }
        return chunks;
    }

    /**
     * 单块装配：分配新的块 ID
     */
    public static Chunk assemble(int index, ChunkDraft draft) {
        // 根据草稿正文与章节元数据生成嵌入文本，再组装正式分块；未显式传入 ID 的重载先生成新 ID。
        return assemble(nextChunkId(), index, draft);
    }

    /**
     * 用既有块 ID 装配：人工编辑单块后重新入库时用，换一个 ID 会让关系库与向量库的主键对不上
     */
    public static Chunk assemble(String chunkId, int index, ChunkDraft draft) {
        // 根据草稿正文与章节元数据生成嵌入文本，再组装正式分块；未显式传入 ID 的重载先生成新 ID。
        ChunkMetadata metadata = draft.metadata();
        return new Chunk(chunkId, index, draft.content(),
                composeEmbeddingText(metadata, draft.effectiveBody(), draft.content()), metadata);
    }

    /**
     * 复原已入库的块：重建向量的唯一正确入口，向量文本取库里那一份而不重新组装
     * <p>
     * 入库时的结构信息（章节路径、表格的 {@code 列名: 值} 渲染、图片去 URL 后的描述）只存在于 {@code embedding_text} 一列，
     * 关系库那边只剩展示文本，重走一遍装配拿到的是裸正文；该列为空时回落展示文本，人工建的块向量文本本就等于正文
     */
    public static Chunk restore(String chunkId, int index, String content, String embeddingText) {
        // 按存储字段还原分块，旧数据缺少嵌入文本时回落到展示正文，元数据先使用空值。
        return new Chunk(chunkId, index, content,
                StringUtils.hasText(embeddingText) ? embeddingText : content, ChunkMetadata.empty());
    }

    /**
     * 块 ID 生成：全系统单点
     */
    public static String nextChunkId() {
        // 使用雪花 ID 生成字符串分块标识，避免依赖列表序号作为全局标识。
        return IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 向量文本：章节路径中正文尚未覆盖的那一段 + 正文
     * <p>
     * 同一节被切成多块时只有首块的正文自带标题，路径前缀是续块唯一的章节词面来源；首块把自带的那几级
     * 再拼一遍只是把章节名连写两次，白占向量里的位置
     */
    private static String composeEmbeddingText(ChunkMetadata metadata, String body, String content) {
        // 先补正文中缺失的章节前缀，再拼接有效嵌入正文，形成供模型向量化的文本。
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, missingOutlinePrefix(metadata, content));
        appendIfPresent(sb, body);
        return sb.toString();
    }

    /**
     * 取正文尚未覆盖的那截章节路径：路径自根向下，块自带的标题必然是它的一个后缀，故截到首个命中即可
     * <p>
     * 从 {@code ### 1.3} 起头的块自带末级标题却不带章级，整条省掉会把章级上下文一并丢掉，
     * 所以按级判断而不是「含标题就不拼」
     */
    private static String missingOutlinePrefix(ChunkMetadata metadata, String content) {
        // 从章节路径起点寻找正文尚未包含的前缀，只返回需要补入嵌入文本的那部分。
        List<String> path = metadata.outlinePath();
        int keep = 0;
        while (keep < path.size() && !content.contains(path.get(keep))) {
            keep++;
        }
        return keep == 0 ? null : String.join(OUTLINE_SEPARATOR, path.subList(0, keep));
    }

    /**
     * 追加去除首尾空白后的有效片段，非首段补上下文分隔符，避免空片段占用嵌入正文。
     */
    private static void appendIfPresent(StringBuilder sb, String part) {
        // 跳过空白片段，片段之间添加统一上下文分隔符，并去除首尾空白。
        if (!StringUtils.hasText(part)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(CONTEXT_SEPARATOR);
        }
        sb.append(part.strip());
    }
}
