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
 * 已向量化的块：索引层的唯一入参
 * <p>
 * 与 {@link Chunk} 分开是为了让未向量化的块在类型上就进不了索引层；record 的 equals / hashCode 对数组按引用比较，
 * 此类型仅作数据传输，不应放进 Set 或作为 Map 键
 *
 * @param embedding 向量，维度由部署级配置固定，写入前已在向量化阶段校验
 */
public record EmbeddedChunk(Chunk chunk, float[] embedding) {
    // 已向量化的分块：将正式 Chunk 与向量模型生成的 float[] 绑定，供后续索引写入使用；构造时要求分块和向量有效，确保正文与向量始终关联同一个块 ID。

    public EmbeddedChunk {
        // 必须同时具备原始分块和非空向量，否则在构造阶段立即报错，避免写入不完整索引。
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为 null");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空，chunkId=" + chunk.chunkId());
        }
    }

    /**
     * 读取所包装切片的稳定 ID，作为各索引后端共享的主键。
     */
    public String chunkId() {
        // 直接复用内部原始分块的标识，保证向量和正文关联到同一块。
        return chunk.chunkId();
    }

    /**
     * 读取所包装切片在文档中的序号。
     */
    public int index() {
        // 返回原始分块的文档内序号，不单独维护另一份顺序。
        return chunk.index();
    }

    /**
     * 读取所包装切片的展示正文，不在这里重新生成嵌入文本。
     */
    public String content() {
        // 返回原始分块的展示正文，供存储和回显复用。
        return chunk.content();
    }

    public String embeddingText() {
        // 返回原始分块用于向量化的语义文本。
        return chunk.embeddingText();
    }

    /**
     * 读取所包装切片的结构化元数据，供索引序列化和溯源使用。
     */
    public ChunkMetadata metadata() {
        // 直接返回原始分块的元数据，保持向量结果与分块上下文一致。
        return chunk.metadata();
    }

    public int dimension() {
        // 向量数组长度就是实际嵌入维度，供写入前校验目标空间。
        return embedding.length;
    }
}
