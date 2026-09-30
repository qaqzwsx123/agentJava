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

/**
 * 分块产物：不可变，不含向量
 * <p>
 * 构造期强制 {@code embeddingText} 非空，忘记注入章节上下文就构造不出对象
 *
 * @param index         块在文档中的序号，从 0 开始
 * @param content       文档原貌（markdown，标题按原文位置在正文内），回填 LLM 上下文与前端预览用
 * @param embeddingText 向量文本（章节路径 + 正文），不参与展示
 */
public record Chunk(
        String chunkId,
        int index,
        String content,
        String embeddingText,
        ChunkMetadata metadata
) {
    // 正式分块数据：保存稳定 ID、文档内序号、展示正文 content、向量化输入 embeddingText 和元数据；本身不含向量，两种正文分别服务于展示和语义检索。

    public Chunk {
        // 校验块标识、非负序号与两种正文，缺少元数据时补空对象，保证正式块具备完整语义。
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index 必须 >= 0，实际 " + index);
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null，chunkId=" + chunkId);
        }
        if (embeddingText == null || embeddingText.isBlank()) {
            throw new IllegalArgumentException("embeddingText 不能为空，chunkId=" + chunkId
                    + "——向量文本由 chunker 基类统一组装章节上下文");
        }
        metadata = metadata == null ? ChunkMetadata.empty() : metadata;
    }

    /**
     * 复制并替换元数据：块级加工（摘要 / 关键词）写扩展位用，向量文本不受影响，向量在此之前已算完
     */
    public Chunk withMetadata(ChunkMetadata newMetadata) {
        // 保留分块标识、序号和文本，只替换元数据并返回新对象。
        return new Chunk(chunkId, index, content, embeddingText, newMetadata);
    }
}
