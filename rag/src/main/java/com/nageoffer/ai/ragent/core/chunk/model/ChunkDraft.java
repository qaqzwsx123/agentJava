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

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 块草稿：切分与合并阶段的中间形态，尚未分配 ID、尚未组装向量文本
 * <p>
 * 合并必须发生在装配之前：向量文本带章节路径前缀，合并两个成品块会把前缀重复若干遍；序号与块 ID 同样留到装配时统一分配
 *
 * @param content       展示原貌
 * @param embeddingBody 检索正文，为空时装配阶段回落到 {@code content}
 * @param piece         是否为单个 Block 被切开的其中一片，详见 {@link #pieces}
 * @param heading       是否含标题，详见 {@link #ofHeading}
 */
public record ChunkDraft(String content, String embeddingBody, ChunkMetadata metadata,
                         boolean piece, boolean heading) {

    public ChunkDraft {
        // 展示内容为 null 时用空串，元数据为 null 时使用空对象，简化后续打包逻辑。
        content = content == null ? "" : content;
        metadata = metadata == null ? ChunkMetadata.empty() : metadata;
    }

    /**
     * 创建普通分块草稿，可单独指定嵌入正文；不标记为标题或分段边界。
     */
    public static ChunkDraft of(String content, ChunkMetadata metadata) {
        // 创建普通草稿，可选地独立指定嵌入正文，默认不标记为切片或标题。
        return new ChunkDraft(content, null, metadata, false, false);
    }

    /**
     * 创建普通分块草稿，可单独指定嵌入正文；不标记为标题或分段边界。
     */
    public static ChunkDraft of(String content, String embeddingBody, ChunkMetadata metadata) {
        // 创建普通草稿，可选地独立指定嵌入正文，默认不标记为切片或标题。
        return new ChunkDraft(content, embeddingBody, metadata, false, false);
    }

    /**
     * 标题草稿：打包阶段的分节标记，一个标题起一节
     * <p>
     * 只记有无不记级别：级别由解析器主观判定，MinerU 按字号猜，同一份文档 markdown 三级到了 PDF 全成二级，
     * 任何比较级别的判据都会给出两种切法；位置则是客观的
     */
    public static ChunkDraft ofHeading(String content, String embeddingBody, ChunkMetadata metadata) {
        // 创建标题草稿，保留独立嵌入正文并标记标题，供章节打包识别边界。
        return new ChunkDraft(content, embeddingBody, metadata, false, true);
    }

    /**
     * 标记一个 Block 的切分产物：只有一片说明没切开，原样返回
     * <p>
     * 一个 Block 该怎么分，切它的 chunker 才是权威，合并阶段不得撤销：表格按行数上限切出的片被并回去，
     * 上限就形同虚设；段落切出的片各自带着重叠文本，并回去等于把那段重叠在同一块里复制一遍
     */
    public static List<ChunkDraft> pieces(List<ChunkDraft> drafts) {
        // 只有拆成多段时才统一标记为切片，单段保持原对象列表，避免打包器误判。
        if (drafts.size() <= 1) {
            return drafts;
        }
        List<ChunkDraft> marked = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            marked.add(new ChunkDraft(draft.content(), draft.embeddingBody(), draft.metadata(),
                    true, draft.heading()));
        }
        return marked;
    }

    /**
     * 检索正文：未显式提供时回落到展示原貌
     */
    public String effectiveBody() {
        // 优先取显式嵌入正文，没有有效正文时回落到展示内容。
        return StringUtils.hasText(embeddingBody) ? embeddingBody : content;
    }

    /**
     * 是否显式指定了检索正文：合并时据此区分，否则图片块那份去掉 URL 噪声的检索正文会退化成带 URL 的展示文本
     */
    public boolean hasExplicitBody() {
        // 判断是否提供了有效的独立嵌入正文，供合并时保留语义文本使用。
        return StringUtils.hasText(embeddingBody);
    }
}
