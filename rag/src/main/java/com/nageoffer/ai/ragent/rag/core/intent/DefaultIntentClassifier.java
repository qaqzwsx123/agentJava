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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.infra.util.LogSafe;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;

/**
 * LLM 树形意图分类器（串行实现）
 * <p>
 * 核心职责：
 * 1. 从缓存/数据库加载意图树，构建内存结构；
 * 2. 根据当前编排模式（Agent / Workflow）筛选出可用的候选叶子节点；
 * 3. 构造 Prompt，把候选节点的 id、路径、描述、示例发给 LLM；
 * 4. 解析 LLM 返回的 JSON 打分，按 score 降序返回。
 * <p>
 * 适用场景：意图数量较少的场景，一次性发送给 LLM 进行识别打分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIntentClassifier implements IntentClassifier, IntentNodeRegistry {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final OrchestrationProperties orchestrationProperties;

    /**
     * 从 Redis 加载意图树并构建内存结构
     * 每次调用都会重新从 Redis 读取，确保数据是最新的（意图树可能被后台动态修改）
     */
    private IntentTreeData loadIntentTreeData() {
        // 1. 从 Redis 读取（如果不存在会自动从数据库加载并回写缓存）
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();

        // 2. 如果 Redis 也没有，从数据库加载并缓存（兜底逻辑）
        if (CollUtil.isEmpty(roots)) {
            roots = loadIntentTreeFromDB();
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
            }
        }

        // 3. 构建临时内存结构，方便快速查找
        if (CollUtil.isEmpty(roots)) {
            return new IntentTreeData(List.of(), List.of(), Map.of());
        }

        // 展平树结构，拿到所有节点
        List<IntentNode> allNodes = flatten(roots);
        // 筛选出叶子节点（只有叶子节点才参与意图分类）
        List<IntentNode> leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());
        // 构建 id -> 节点 的映射，方便 O(1) 查找
        Map<String, IntentNode> id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.debug("意图树数据加载完成, 总节点数: {}, 叶子节点数: {}", allNodes.size(), leafNodes.size());

        return new IntentTreeData(allNodes, leafNodes, id2Node);
    }

    /**
     * 从已加载的意图树 ID 索引取得节点，空 ID 返回 null。
     */
    @Override
    public IntentNode getNodeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        IntentTreeData data = loadIntentTreeData();
        return data.id2Node.get(id);
    }

    /**
     * 获取所有 MCP 工具节点（供 ToolCatalogResolver 解析 MCP 工具使用）
     */
    @Override
    public List<IntentNode> listMcpToolNodes() {
        return loadIntentTreeData().leafNodes.stream()
                .filter(IntentNode::isMCP)
                .filter(node -> node.getMcpToolId() != null && !node.getMcpToolId().isBlank())
                .sorted(Comparator.comparing(IntentNode::getId))
                .toList();
    }

    /**
     * 意图树数据结构（临时对象，不持久化，仅用于内存传递）
     */
    private record IntentTreeData(
            List<IntentNode> allNodes,
            List<IntentNode> leafNodes,
            Map<String, IntentNode> id2Node
    ) {
    }

    /**
     * 深度优先遍历（DFS）展平树结构
     * 使用 Deque 替代递归，避免意图树过深导致 StackOverflow
     */
    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 对所有"叶子分类节点"做意图识别，由 LLM 输出每个分类的 score
     * 返回结果已按 score 从高到低排序
     */
    @Override
    public List<NodeScore> classifyTargets(String question) {
        // 每次都从 Redis 读取最新数据（保证意图树动态更新后能立即生效）
        IntentTreeData data = loadIntentTreeData();
        if (data.leafNodes.isEmpty()) {
            log.debug("意图树没有可用叶子节点，跳过 LLM 意图识别");
            return List.of();
        }

        // Agent 模式下只识别知识库（KB）意图；
        // Workflow 模式下额外保留 SYSTEM 应答意图
        List<IntentNode> candidates = data.leafNodes.stream()
                .filter(node -> node.isKB()
                        || (orchestrationProperties.getMode() == OrchestrationMode.WORKFLOW && node.isSystem()))
                .toList();
        if (candidates.isEmpty()) {
            log.debug("当前编排模式没有可用意图叶子节点，跳过 LLM 意图识别, mode={}",
                    orchestrationProperties.getMode());
            return List.of();
        }
        Map<String, IntentNode> candidateById = candidates.stream()
                .collect(Collectors.toMap(IntentNode::getId, node -> node));

        // 构造 Prompt：包含所有候选节点的 id、路径、描述、示例
        String systemPrompt = buildPrompt(candidates);

        // 构造 LLM 请求：温度设低（0.1），topP 设低（0.3），关闭思考（thinking=false）
        // 意图分类需要的是确定性输出，不需要模型发挥创造力
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        // 标准档调用；调用失败或 JSON 非法均返回空意图（下游把空意图当作"无意图"兜底）
        String raw;
        try {
            raw = llmService.chat(request);
        } catch (Exception e) {
            log.warn("意图识别 LLM 调用失败，返回空意图", e);
            return List.of();
        }
        return parseScores(raw, candidateById, question);
    }

    /**
     * 解析意图打分，按 score 降序返回；任何解析失败/畸形均返回空列表
     */
    private List<NodeScore> parseScores(String raw, Map<String, IntentNode> candidateById, String question) {
        try {
            // 移除 LLM 可能返回的 Markdown 代码块标记（如 ```json ... ```）
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleanedRaw);
            JsonArray arr;
            if (root.isJsonArray()) {
                // 标准格式：[{"id": "...", "score": 0.9}, ...]
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
                // 容错：如果模型外面又包了一层 { "results": [...] }
                arr = root.getAsJsonObject().getAsJsonArray("results");
            } else {
                log.warn("LLM 返回了非预期的 JSON 格式, 原始响应: {}", LogSafe.preview(raw));
                return List.of();
            }

            List<NodeScore> scores = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();

                // 必须同时包含 id 和 score 字段
                if (!obj.has("id") || !obj.has("score")) continue;

                String id = obj.get("id").getAsString();
                IntentNode node = candidateById.get(id);
                if (node == null) {
                    // 模型可能幻觉出不存在的 ID，跳过并记录警告
                    log.warn("LLM 返回了未知或当前模式不可用的意图节点 ID: {}, 已跳过", id);
                    continue;
                }

                scores.add(new NodeScore(node, obj.get("score").getAsDouble()));
            }

            // 降序排序（分数高的排前面）
            scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

            // 打印详细日志，方便排查意图识别效果
            log.info("当前问题：{}\n意图识别树如下所示：{}\n",
                    question,
                    JSONUtil.toJsonPrettyStr(
                            scores.stream().peek(each -> {
                                IntentNode node = each.getNode();
                                node.setChildren(null); // 日志里不打印 children，避免输出爆炸
                            }).collect(Collectors.toList())
                    )
            );
            return scores;
        } catch (Exception e) {
            log.warn("意图打分解析失败, 原始响应: {}", LogSafe.preview(raw), e);
            return List.of();
        }
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出当前编排模式可用的【候选叶子节点】的 id / 路径 / 描述 / 示例问题
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     * - 特别强调：如果问题里只提到 "OA系统"，不要选 "保险系统" 的分类
     * - 每个节点都带 type 标识，模板据此区分知识库检索和系统交互
     */
    private String buildPrompt(List<IntentNode> leafNodes) {
        StringBuilder sb = new StringBuilder();

        for (IntentNode node : leafNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");
            sb.append("  description=").append(node.getDescription()).append("\n");

            // 标识节点类型：SYSTEM（系统交互）还是 KB（知识库）
            sb.append(node.isSystem() ? "  type=SYSTEM\n" : "  type=KB\n");

            // 拼接示例问题，帮助 LLM 理解意图边界
            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 渲染 Prompt 模板，把动态生成的意图列表塞进去
        return promptTemplateLoader.render(
                INTENT_CLASSIFIER_PROMPT_PATH,
                Map.of("intent_list", sb.toString())
        );
    }

    /**
     * 从数据库加载意图树（扁平结构 -> 树形结构）
     */
    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除且已启用的节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // 2. DO -> IntentNode（第一遍：先把所有节点建出来，放到 map 里）
        Map<String, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);
            // 数据库中的 code 映射到 IntentNode 的 id/parentId
            node.setId(each.getIntentCode());
            node.setParentId(each.getParentCode());
            node.setMcpToolId(each.getMcpToolId());
            node.setRequireConfirm(Objects.equals(each.getRequireConfirm(), 1));
            node.setExamples(parseExamples(each.getExamples()));
            // 处理 collectionNames：兼容单字段和多字段的情况
            if (CollUtil.isEmpty(each.getCollectionNames())) {
                node.setCollectionNames(
                        each.getCollectionName() == null || each.getCollectionName().isBlank()
                                ? List.of()
                                : List.of(each.getCollectionName())
                );
            }
            // 确保 children 不为 null（避免后面 add NPE）
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            id2Node.put(node.getId(), node);
        }

        // 3. 第二遍：根据 parentId 组装 parent -> children
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId，当作根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                // 找不到父节点（数据异常），兜底也当作根节点，避免节点丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // 4. 递归填充 fullPath（例如：集团信息化 > 人事 > 招聘）
        fillFullPath(roots, null);

        return roots;
    }

    /**
     * examples 在库里是 GSON 序列化后的 JSON 数组文本
     * 例如：["怎么查工资", "工资条在哪看"]
     * BeanUtil 只会去掉方括号再按英文逗号切分，元素两侧的引号会原样进到意图识别提示词里
     * 所以这里必须手动用 Gson 解析，保证示例文本干净
     */
    private List<String> parseExamples(String examples) {
        if (examples == null || examples.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(examples);
            if (!root.isJsonArray()) {
                log.warn("意图节点 examples 不是 JSON 数组, 原始值: {}", LogSafe.preview(examples));
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    result.add(el.getAsString());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("意图节点 examples 解析失败, 原始值: {}", LogSafe.preview(examples), e);
            return List.of();
        }
    }

    /**
     * 递归填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 业务系统 > OA系统 > 系统介绍
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            // 递归处理子节点
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}