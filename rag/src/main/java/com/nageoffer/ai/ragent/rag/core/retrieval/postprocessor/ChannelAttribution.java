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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索通道归因工具
 * <p>
 * {@link RetrievedChunk} 不携带来源通道字段（框架层 DTO 保持纯净），故按 chunk key 从不可变的
 * {@link SearchChannelResult} 反查每条证据来自哪些通道，供融合 / Rerank 打印「各通道贡献 / 存活率」的可观测日志
 * <p>
 * 归因键统一由 {@link RetrievedChunkKey} 生成，避免通道去重、融合和归因采用不同的身份规则
 */
final class ChannelAttribution {
    // 检索通道归因工具：使用统一块键从各通道原始结果反查证据来源，统计融合和重排后的贡献与存活情况，不修改片段模型。

    private ChannelAttribution() {
        // 通道归因统计通过静态方法复用，私有构造器阻止实例化。
    }

    /**
     * 反查每个 chunk key 命中的通道集合（一条证据可被多路命中，故值为集合）
     */
    static Map<String, Set<SearchChannelType>> index(List<SearchChannelResult> results) {
        // 按统一块键收集所有原始召回通道，同一块可以同时属于多个通道。
        Map<String, Set<SearchChannelType>> index = new HashMap<>();
        if (results == null) {
            return index;
        }
        for (SearchChannelResult result : results) {
            if (result == null || result.getChunks() == null) {
                continue;
            }
            for (RetrievedChunk chunk : result.getChunks()) {
                index.computeIfAbsent(RetrievedChunkKey.of(chunk), k -> EnumSet.noneOf(SearchChannelType.class))
                        .add(result.getChannelType());
            }
        }
        return index;
    }

    /**
     * 统计给定 chunks 按通道的分布（多路命中的 chunk 在每个命中通道各计一次），如 {意图定向=4, 图谱=8}
     */
    static Map<SearchChannelType, Integer> countByChannel(List<RetrievedChunk> chunks,
                                                          Map<String, Set<SearchChannelType>> index) {
        // 遍历当前阶段保留的块，依据原始归因给每个来源通道分别计数。
        Map<SearchChannelType, Integer> counts = new EnumMap<>(SearchChannelType.class);
        for (RetrievedChunk chunk : chunks) {
            Set<SearchChannelType> channels = index.get(RetrievedChunkKey.of(chunk));
            if (channels == null) {
                continue;
            }
            for (SearchChannelType channel : channels) {
                counts.merge(channel, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 命中给定通道的 chunk 数，用于「图谱存活率」这类单通道口径的前后对比
     */
    static long countOfChannel(List<RetrievedChunk> chunks,
                               Map<String, Set<SearchChannelType>> index,
                               SearchChannelType channel) {
        // 统计当前块列表中曾由指定通道召回的数量，多来源块仍可计入该通道。
        return chunks.stream()
                .map(RetrievedChunkKey::of)
                .map(index::get)
                .filter(set -> set != null && set.contains(channel))
                .count();
    }

    /**
     * 通道分布转中文可读串，如「意图定向=4 图谱=8 关键词=6」
     */
    static String format(Map<SearchChannelType, Integer> counts) {
        // 将通道计数转成简洁日志文本，空映射显示无。
        if (counts.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((type, n) -> sb.append(label(type)).append('=').append(n).append(' '));
        return sb.toString().trim();
    }

    /**
     * 通道类型中文标签
     */
    static String label(SearchChannelType type) {
        // 把通道类型映射为中文标签，供统一归因日志展示。
        return switch (type) {
            case VECTOR -> "向量";
            case KEYWORD -> "关键词";
            case GRAPH -> "图谱";
            case WEB_SEARCH -> "联网";
            case HYBRID -> "混合";
        };
    }
}
