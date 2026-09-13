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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.ChunkEnrichType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.EnricherSettings;
import com.nageoffer.ai.ragent.ingestion.prompt.EnricherPromptManager;
import com.nageoffer.ai.ragent.ingestion.util.JsonResponseParser;
import com.nageoffer.ai.ragent.ingestion.util.PromptTemplateRenderer;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本增强节点
 * 该节点通过调用大模型对文档分片进行信息提取或补充，如提取关键词、生成摘要、补充元数据等
 */
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    /**
     * 逐个为有正文的向量分块执行关键词、摘要或元数据增强，按配置合并文档级元数据。
     * 分块不可变，增强后创建带新元数据的分块并保留原向量，最后整体替换上下文列表。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<EmbeddedChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("No chunks to enrich");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("No enricher tasks configured");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();
        // 块不可变：加工产物收集到扩展位，最后整块替换，而不是原地改一个自由 Map
        List<EmbeddedChunk> enriched = new java.util.ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.content())) {
                if (chunk != null) {
                    enriched.add(chunk);
                }
                continue;
            }
            Map<String, Object> extras = new HashMap<>();
            if (attachMetadata && context.getMetadata() != null) {
                extras.putAll(context.getMetadata());
            }
            // 同一分块的多个增强任务依次运行，结果先放入 extras，循环结束后统一生成替换对象。
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue;
                }
                ChunkEnrichType type = task.getType();
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = chat(request, settings.getModelId());
                applyResult(extras, type, response);
            }
            enriched.add(extras.isEmpty()
                    ? chunk
                    : new EmbeddedChunk(chunk.chunk().withMetadata(chunk.metadata().withExtras(extras)),
                            chunk.embedding()));
        }
        context.setChunks(enriched);
        return NodeResult.ok("Enricher completed");
    }

    /**
     * 解析分块增强设置，未配置时返回空任务列表。
     */
    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    /**
     * 以分块正文、序号和任务标识渲染模板；模板为空时直接使用分块正文。
     */
    private String buildUserPrompt(String template, EmbeddedChunk chunk, IngestionContext context) {
        String input = chunk.content();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.index());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    /**
     * 将模型结果转为关键词、摘要或扩展字段，合并到当前分块的待写入元数据中。
     */
    private void applyResult(Map<String, Object> extras, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> extras.put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY -> extras.put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> extras.putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }

    /**
     * 使用 FAST 档及可选优先模型执行分块增强请求。
     */
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, Tier.FAST, modelId);
    }
}
