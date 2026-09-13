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

package com.nageoffer.ai.ragent.rag.core.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 先校验所有向量维度，再构造带逻辑集合字段的记录，批量插入共享 Milvus 集合，正文按字段上限截断。
     */
    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        final int dim = ragDefaultProperties.getDimension();
        // 批量请求发送前统一校验每条向量，避免到存储端才发现某条缺向量或维度错误。
        List<float[]> vectors = extractVectors(chunks, dim);

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddedChunk chunk = chunks.get(i);

            String content = chunk.content() == null ? "" : chunk.content();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = buildMetadata(docId, chunk);

            JsonObject row = new JsonObject();
            row.addProperty("id", chunk.chunkId());
            row.addProperty("collection_name", collectionName);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors.get(i)));

            rows.add(row);
        }

        // 业务 collectionName 已写在记录字段，实际请求统一写入 sharedCollection 物理集合。
        InsertReq req = InsertReq.builder()
                .collectionName(sharedCollection())
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, collection={}, rows={}", collectionName, resp.getInsertCnt());
    }

    /**
     * 校验单个向量后按切片主键 upsert，同时更新正文、元数据和逻辑集合字段。
     */
    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        final int dim = ragDefaultProperties.getDimension();
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.chunkId();

        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = buildMetadata(docId, chunk);

        JsonObject row = new JsonObject();
        row.addProperty("id", chunkPk);
        row.addProperty("collection_name", collectionName);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(sharedCollection())
                .data(List.of(row))
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);
        log.info("Milvus 更新 chunk 向量索引成功, collection={}, docId={}, chunkId={}, upsertCnt={}",
                collectionName, docId, chunkPk, resp.getUpsertCnt());
    }

    /**
     * 在共享集合中同时按逻辑集合与元数据文档 ID 过滤，删除该文档的所有向量。
     */
    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        // 共享 collection 下多库共存，doc_id 不再天然隔离，必须叠加 collection_name 限定
        String filter = "collection_name == \"" + collectionName + "\" && metadata[\"doc_id\"] == \"" + docId + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定文档的所有 chunk 向量索引成功, collection={}, docId={}, deleteCnt={}",
                collectionName, docId, resp.getDeleteCnt());
    }

    /**
     * 按全局唯一切片主键删除向量，不再追加逻辑集合过滤。
     */
    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        // id 为雪花主键，全局唯一，直接按主键删除
        String filter = "id == \"" + chunkId + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定 chunk 向量索引成功, collection={}, chunkId={}, deleteCnt={}",
                collectionName, chunkId, resp.getDeleteCnt());
    }

    /**
     * 把非空切片 ID 列表组合为主键集合过滤条件，批量删除向量。
     */
    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String idList = chunkIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        String filter = "id in [" + idList + "]";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 批量删除 chunk 向量索引成功, collection={}, count={}, deleteCnt={}",
                collectionName, chunkIds.size(), resp.getDeleteCnt());
    }

    /**
     * 逐个取得并校验切片向量，保证提交插入请求前所有向量均符合目标维度。
     */
    private List<float[]> extractVectors(List<EmbeddedChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            vectors.add(extractVector(chunk, expectedDim));
        }
        return vectors;
    }

    /**
     * 拒绝空向量或与配置维度不一致的向量，返回已校验的浮点数组。
     */
    private float[] extractVector(EmbeddedChunk chunk, int expectedDim) {
        float[] vector = chunk.embedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    /**
     * 把浮点向量转成 Milvus JSON 记录需要的数组结构。
     */
    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }

    /**
     * 通过统一元数据序列化入口生成字段，再补文档 ID 和切片序号，逻辑集合单独写在顶层。
     */
    private JsonObject buildMetadata(String docId, EmbeddedChunk chunk) {
        JsonObject metadata = new JsonObject();
        // 结构化元数据统一走唯一序列化点
        chunk.metadata().toMap().forEach((k, v) -> metadata.add(k, GSON.toJsonTree(v)));

        // collection_name 已提升为顶层标量字段，不再冗余写入 metadata
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.index());
        return metadata;
    }

    /**
     * 全 Milvus 共用的物理 collection（所有知识库的 chunk 都写在这里，按 collection_name 标量字段区分）
     */
    private String sharedCollection() {
        return ragDefaultProperties.getCollectionName();
    }
}
