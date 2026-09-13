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
import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图片 chunker：一图一块，展示文本是描述 + markdown 图片链接，向量文本只取描述
 * <p>
 * 图片 URL 进向量是纯噪声，只在无描述时（如 MinerU 抽图）才回落到链接本身；声明为可流动，
 * 让图与它的前导语 / 解释文字同块，检索命中即带图
 */
@Component
public class ImageChunker implements BlockChunker<ImageBlock> {

    @Override
    public Class<ImageBlock> blockType() {
        return ImageBlock.class;
    }

    /**
     * 将图片描述与 Markdown 链接组成展示正文，嵌入正文仅取有效描述，并把资产引用保留在元数据。
     */
    @Override
    public List<ChunkDraft> chunk(ImageBlock block, ChunkContext ctx) {
        if (block == null || block.asset() == null) {
            return List.of();
        }
        AssetRef asset = block.asset();
        String markdown = "![" + pickCaption(block) + "](" + asset.publicUrl() + ")";

        String description = block.description();
        boolean hasDescription = description != null && !description.isBlank();
        String content = hasDescription ? description.strip() + "\n\n" + markdown : markdown;

        ChunkMetadata metadata = ChunkMetadata.builder()
                .outlinePath(ctx.outlinePath())
                .assets(List.of(asset))
                .provenance(block.provenance())
                .build();

        // 链接留在展示正文，嵌入正文使用图片描述，避免将资产 URL 作为检索语义。
        return List.of(ChunkDraft.of(content, hasDescription ? description.strip() : null, metadata));
    }

    /**
     * 图片说明优先使用 caption，其次使用 altText，两者缺失则为空。
     */
    private String pickCaption(ImageBlock block) {
        if (block.caption() != null && !block.caption().isEmpty()) {
            return block.caption();
        }
        if (block.altText() != null && !block.altText().isEmpty()) {
            return block.altText();
        }
        return "";
    }

}
