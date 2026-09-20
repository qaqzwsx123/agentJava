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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引节点：把已向量化的块写入向量存储
 * <p>
 * 直接写块，元数据统一走块自己的序列化点，管道注入的字段（任务 ID、来源等）落进元数据扩展位；维度
 * 校验不在此重复，向量化阶段已按部署级维度逐条校验，块类型本身也保证向量非空
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final VectorStoreService vectorStoreService;
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       VectorStoreService vectorStoreService,
                       RAGDefaultProperties ragDefaultProperties) {
        // 保存设置转换、向量空间管理、向量写入及默认配置依赖，供索引阶段使用。
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.vectorStoreService = vectorStoreService;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        // 返回索引节点类型，供引擎匹配流水线配置。
        return IngestionNodeType.INDEXER.getValue();
    }

    /**
     * 检查分块与目标分区，附加流水线元数据后按写入开关决定是否真正写向量。
     * 延迟写入模式只准备产物；直接写入模式先确保向量空间存在，再批量写入。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 确认有分块与目标分区后附加流水线元数据；调用方要求延迟写入时只准备数据，否则确保空间后写向量。
        List<EmbeddedChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());
        String partition = resolvePartition(context);
        if (!StringUtils.hasText(partition)) {
            return NodeResult.fail(new ClientException("索引器需要指定集合名称"));
        }

        List<EmbeddedChunk> enriched = attachPipelineMetadata(context, chunks, settings.getMetadataFields());
        context.setChunks(enriched);

        if (context.isSkipIndexerWrite()) {
            // 调用方会在事务中统一写向量，此处只做准备
            return NodeResult.ok("已准备 " + enriched.size() + " 个分块（向量写入由调用方统一完成）");
        }

        ensureVectorSpace(partition);
        vectorStoreService.indexDocumentChunks(partition, context.getTaskId(), enriched);
        log.info("向量写入成功，集合={}，行数={}", partition, enriched.size());
        return NodeResult.ok("已写入 " + enriched.size() + " 个分块到集合 " + partition);
    }

    /**
     * 解析索引节点设置，未配置时使用默认对象。
     */
    private IndexerSettings parseSettings(JsonNode node) {
        // 空设置采用默认索引配置，其余通过 JSON 转为类型化设置。
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    /**
     * 依次从向量目标、向量空间标识和系统默认配置中取得逻辑分区名。
     */
    private String resolvePartition(IngestionContext context) {
        // 优先使用明确绑定的向量落点，其次取逻辑空间名，最后回落默认集合名称。
        if (context.getVectorTarget() != null) {
            return context.getVectorTarget().partition();
        }
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getCollectionName();
    }

    /**
     * 把管道级信息写进块元数据的扩展位
     * <p>
     * {@code metadataFields} 为空时注入全部管道级字段；配置了白名单则只注入命中的字段
     */
    private List<EmbeddedChunk> attachPipelineMetadata(IngestionContext context,
                                                       List<EmbeddedChunk> chunks,
                                                       List<String> metadataFields) {
        // 收集任务、来源和上下文元数据，按配置字段过滤后合并进各块扩展信息，保持原有向量不变。
        Map<String, Object> pipelineMetadata = new LinkedHashMap<>();
        putIfPresent(pipelineMetadata, "task_id", context.getTaskId());
        putIfPresent(pipelineMetadata, "pipeline_id", context.getPipelineId());
        DocumentSource source = context.getSource();
        if (source != null) {
            if (source.getType() != null) {
                pipelineMetadata.put("source_type", source.getType().getValue());
            }
            putIfPresent(pipelineMetadata, "source_location", source.getLocation());
        }
        if (context.getMetadata() != null) {
            pipelineMetadata.putAll(context.getMetadata());
        }
        // 配置了白名单时只保留列出的键，避免把全部上下文元数据无条件传播到每个块。
        if (metadataFields != null && !metadataFields.isEmpty()) {
            pipelineMetadata.keySet().retainAll(metadataFields);
        }
        if (pipelineMetadata.isEmpty()) {
            return chunks;
        }

        // 创建新的分块元数据对象并复用原向量数组，附加业务标签不重新请求嵌入模型。
        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            result.add(new EmbeddedChunk(
                    chunk.chunk().withMetadata(chunk.metadata().withExtras(pipelineMetadata)),
                    chunk.embedding()));
        }
        return result;
    }

    /**
     * 仅将非空白字段加入索引元数据，避免保存无效标识。
     */
    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        // 只有非空白字符串才写入元数据，减少空字段干扰索引。
        if (StringUtils.hasText(value)) {
            map.put(key, value);
        }
    }

    /**
     * 按逻辑分区检查向量空间，不存在时使用默认规格创建，具体后端由 VectorStoreAdmin 处理。
     */
    private void ensureVectorSpace(String partition) {
        // 先检查目标逻辑空间是否存在，不存在时通过管理接口创建，避免每次索引都重复建空间。
        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName(partition).build();
        if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            return;
        }
        vectorStoreAdmin.ensureVectorSpace(VectorSpaceSpec.builder()
                .spaceId(spaceId)
                .remark("RAG向量存储空间")
                .build());
    }
}
