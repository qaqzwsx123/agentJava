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

package com.nageoffer.ai.ragent.rag.core.keyword;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch 的关键词索引服务，{@code rag.keyword.type=es} 时才装配
 * <p>
 * 只写关键词文本与检索所需元信息，不写向量；文档主键 {@code _id} 取 chunkId，与向量库主键对齐才能保证
 * 跨模态去重与融合一致；所有知识库写同一物理索引、以 {@code collection_name} 区分，与向量库共享
 * collection 同构
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsKeywordIndexService implements KeywordIndexService {

    private static final int MAX_CONTENT_LENGTH = 65535;

    private final ElasticsearchClient esClient;
    private final KeywordProperties keywordProperties;

    /**
     * 启动即幂等确保共享索引存在，与向量共享 collection 的启动初始化对称
     */
    @PostConstruct
    public void initSharedIndex() {
        // 初始化时确保共享关键词索引已存在，具体检查和并发创建容错统一在内部方法处理。
        ensureSharedIndex();
    }

    /**
     * 确保共享关键词索引存在后，按切片 ID 批量写入正文、集合和文档标识。
     * 请求异常向上抛出，批量响应中的部分失败当前只记录告警。
     */
    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        // 把块写入共享索引，以全局块 ID 作为文档主键，集合与文档归属保存在字段中，批量响应逐项失败仅告警。
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        ensureSharedIndex();

        String index = sharedIndex();
        // 共享索引以切片 ID 作为 _id，逻辑集合及文档信息放入索引字段。
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (EmbeddedChunk chunk : chunks) {
            String chunkId = chunk.chunkId();
            Map<String, Object> doc = buildDocument(collectionName, docId, chunk);
            bulk.operations(op -> op.index(idx -> idx.index(index).id(chunkId).document(doc)));
        }

        // 请求整体失败会抛出异常；批量请求成功但某些项失败时仅记录告警，调用方需按现有同步策略处理。
        try {
            BulkResponse resp = esClient.bulk(bulk.build());
            if (resp.errors()) {
                log.warn("ES 关键词索引部分失败, collection={}, docId={}", collectionName, docId);
            } else {
                log.info("ES 关键词索引写入成功, collection={}, docId={}, rows={}", collectionName, docId, chunks.size());
            }
        } catch (Exception e) {
            throw new RuntimeException("ES 关键词索引写入失败, collection=" + collectionName + ", docId=" + docId, e);
        }
    }

    /**
     * 复用单元素批量写入，以相同切片 ID 覆盖关键词索引文档。
     */
    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        // 把单块更新转成单元素批量索引，复用文档构建和主键覆盖写逻辑。
        indexDocumentChunks(collectionName, docId, List.of(chunk));
    }

    /**
     * 同时限定逻辑集合与文档 ID 删除关键词记录，共享索引不存在时按无需删除处理。
     */
    @Override
    public void deleteDocumentIndex(String collectionName, String docId) {
        // 在共享索引中同时限定逻辑集合和文档 ID 删除，索引不存在视为已完成清理。
        try {
            esClient.deleteByQuery(d -> d
                    .index(sharedIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName)))
                            .filter(f -> f.term(t -> t.field("doc_id").value(docId))))));
            log.info("ES 关键词索引按文档删除成功, collection={}, docId={}", collectionName, docId);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过按文档删除, collection={}, docId={}", collectionName, docId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, collection=" + collectionName + ", docId=" + docId, e);
        }
    }

    /**
     * 按全局唯一的切片 ID 删除关键词索引，索引或记录不存在时跳过。
     */
    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        // 按全局唯一块 ID 删除 ES 文档，不存在视为幂等成功，其他错误转换后抛出。
        // chunkId 为全局唯一雪花主键，直接按 _id 删除，无需再限定 collection_name
        try {
            esClient.delete(d -> d.index(sharedIndex()).id(chunkId));
            log.info("ES 关键词索引按 chunk 删除成功, collection={}, chunkId={}", collectionName, chunkId);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引或 chunk 不存在，跳过按 chunk 删除, collection={}, chunkId={}", collectionName, chunkId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, collection=" + collectionName + ", chunkId=" + chunkId, e);
        }
    }

    /**
     * 将切片 ID 组装为批量删除请求，空列表直接返回，共享索引不存在时跳过。
     */
    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        // 空列表不请求，非空列表构造批量主键删除，索引不存在时跳过，其他异常上抛。
        if (CollUtil.isEmpty(chunkIds)) {
            return;
        }
        String index = sharedIndex();
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (String chunkId : chunkIds) {
            bulk.operations(op -> op.delete(del -> del.index(index).id(chunkId)));
        }
        try {
            esClient.bulk(bulk.build());
            log.info("ES 关键词索引批量删除成功, collection={}, count={}", collectionName, chunkIds.size());
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过批量删除, collection={}, count={}", collectionName, chunkIds.size());
                return;
            }
            throw new RuntimeException("ES 关键词索引批量删除失败, collection=" + collectionName, e);
        }
    }

    @Override
    public void deleteByCollection(String collectionName) {
        // 按 collection_name 精确删除某知识库全部关键词记录，不删除其他集合的数据。
        try {
            esClient.deleteByQuery(d -> d
                    .index(sharedIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.term(t -> t.field("collection_name").value(collectionName))));
            log.info("ES 关键词索引按知识库删除成功, collection={}", collectionName);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 共享索引不存在，跳过按知识库删除, collection={}", collectionName);
                return;
            }
            throw new RuntimeException("ES 关键词索引按知识库删除失败, collection=" + collectionName, e);
        }
    }

    /**
     * 识别 Elasticsearch 返回的 404 异常，供删除流程做缺失兼容。
     */
    private boolean isNotFound(Exception e) {
        // 仅把具有 404 响应的 ES 异常识别为不存在，其他错误不应被吞成幂等成功。
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().status() == 404;
    }

    /**
     * 判断是否为「索引已存在」异常
     * 并发首次写入时，多个线程同时 exists()=false 后争相 create()，
     * 落后者会收到 resource_already_exists_exception，此时视作创建成功
     */
    private boolean isAlreadyExists(Exception e) {
        // 识别资源已存在的业务错误，供多线程或多节点同时建索引时容错。
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().error() != null
                && "resource_already_exists_exception".equals(esException.response().error().type());
    }

    /**
     * 确保共享索引存在，不存在则按 ik 分词创建
     * ik_max_word / ik_smart 需安装 IK 分词插件
     */
    private void ensureSharedIndex() {
        // 不存在时按配置创建正文分词与归属字段映射，并发先建成的情况视为成功。
        String index = sharedIndex();
        try {
            boolean exists = esClient.indices().exists(e -> e.index(index)).value();
            if (exists) {
                return;
            }
            String analyzer = keywordProperties.getEs().getAnalyzer();
            String searchAnalyzer = keywordProperties.getEs().getSearchAnalyzer();
            esClient.indices().create(c -> c
                    .index(index)
                    .mappings(m -> m
                            .properties("content", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("collection_name", p -> p.keyword(k -> k))
                            .properties("doc_id", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))));
            log.info("ES 关键词共享索引已创建, index={}, analyzer={}/{}", index, analyzer, searchAnalyzer);
        } catch (Exception e) {
            if (isAlreadyExists(e)) {
                // 并发写入时已由其他线程建好同名索引，视作成功
                log.info("ES 关键词共享索引已由并发写入创建，跳过, index={}", index);
                return;
            }
            throw new RuntimeException("ES 关键词共享索引创建失败, index=" + index, e);
        }
    }

    /**
     * 截断超长正文并附加逻辑集合、文档 ID 和切片序号，生成关键词索引字段。
     */
    private Map<String, Object> buildDocument(String collectionName, String docId, EmbeddedChunk chunk) {
        // 按内容长度上限截取正文，再保存集合、文档和块序号，形成关键词搜索需要的字段集合。
        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("content", content);
        doc.put("collection_name", collectionName);
        doc.put("doc_id", docId);
        doc.put("chunk_index", chunk.index());
        return doc;
    }

    /**
     * 读取关键词检索配置中的共享物理索引名，逻辑集合由文档字段隔离。
     */
    private String sharedIndex() {
        // 统一读取配置的共享索引名称，所有逻辑知识库在该索引中按字段隔离。
        return keywordProperties.sharedIndex();
    }
}
