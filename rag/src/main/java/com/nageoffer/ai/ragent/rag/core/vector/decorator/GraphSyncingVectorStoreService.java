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

package com.nageoffer.ai.ragent.rag.core.vector.decorator;

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.rag.core.graph.GraphFileSource;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量写入的图谱同步装饰器
 * <p>
 * 与 {@link KeywordSyncingVectorStoreService} 同构，包裹真实的 {@link VectorStoreService}，写入 / 删除
 * 成功后同步维护 LightRAG 知识图谱；图谱抽取是文档级（跨 chunk 合并实体）故按文档同步，写入以在手全量
 * 分块拼成全文，删除按文档清除，重建路径「先删后建」的既有调用顺序天然构成 upsert；单块粒度的
 * updateChunk / deleteChunkById / deleteChunksByIds 不同步图谱，整文重摄即可刷新，不为它引入
 * chunkId→docId 反查；图谱写入 best-effort，失败只记日志、不回滚向量、不中断主链路
 */
@Slf4j
public class GraphSyncingVectorStoreService implements VectorStoreService {

    private final VectorStoreService delegate;
    private final LightRagClient lightRagClient;

    public GraphSyncingVectorStoreService(VectorStoreService delegate, LightRagClient lightRagClient) {
        // 保存原向量服务和图谱客户端，使用装饰器在主操作之后执行辅助图谱同步。
        this.delegate = delegate;
        this.lightRagClient = lightRagClient;
    }

    /**
     * 先写入主向量存储，再通过图谱同步包装逻辑提交整篇正文及文档来源。
     */
    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        // 先完成原向量写入，再合并本次正文同步到图谱，图谱失败不撤销已完成的向量操作。
        delegate.indexDocumentChunks(collectionName, docId, chunks);
        syncGraph(docId, () -> lightRagClient.insertText(concatContent(chunks), fileSource(collectionName, docId)));
    }

    /**
     * 更新主向量记录；当前阶段不单独同步切片级图谱变更。
     */
    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        delegate.updateChunk(collectionName, docId, chunk);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    /**
     * 先删除主存储中的文档向量，再调用图谱同步逻辑删除对应文档。
     */
    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        // 先删除原向量，再按文档清理图谱，辅助同步使用统一日志容错。
        delegate.deleteDocumentVectors(collectionName, docId);
        syncGraph(docId, () -> lightRagClient.deleteByDoc(docId));
    }

    /**
     * 删除主存储的单个切片；当前阶段不单独同步切片级图谱删除。
     */
    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        delegate.deleteChunkById(collectionName, chunkId);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    /**
     * 批量删除主存储切片；当前阶段不单独同步切片级图谱删除。
     */
    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        delegate.deleteChunksByIds(collectionName, chunkIds);
        // 子文档粒度，Phase1 不单独同步图谱（见类注释）
    }

    /**
     * 图谱 file_source 编码，读取侧的归属判定与删除匹配依赖同一格式，见 {@link GraphFileSource}
     */
    private String fileSource(String collectionName, String docId) {
        // 复用图谱来源编码，保证图谱写入后仍可恢复逻辑集合与文档归属。
        return GraphFileSource.encode(collectionName, docId);
    }

    /**
     * 拼接分块正文为文档全文，空白分块跳过
     */
    private String concatContent(List<EmbeddedChunk> chunks) {
        // 去掉空正文后按段落连接所有块，作为图谱服务的一份文档输入。
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        return chunks.stream()
                .map(EmbeddedChunk::content)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * best-effort 执行图谱同步，失败仅告警，不影响向量主链路
     */
    private void syncGraph(String docId, Runnable action) {
        // 执行辅助同步，异常仅记录告警，避免图谱不可用阻断向量主链路。
        try {
            action.run();
        } catch (Exception e) {
            log.warn("图谱同步失败，已跳过 docId={}", docId, e);
        }
    }
}
