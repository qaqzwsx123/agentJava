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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl implements IntentTreeService {

    private final IntentNodeMapper intentNodeMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final BizChangeLogContext bizChangeLogContext;

    private static final Gson GSON = new Gson();

    /**
     * 查询全部有效意图节点并按 parentCode 组装层级树。
     * 节点先按排序号和 ID 排序，parentCode 为空的节点作为根节点。
     */
    @Override
    public List<IntentNodeTreeVO> getFullTree() {
        // 管理端读取完整配置树，运行期是否启用另由节点 enabled 属性决定。
        List<IntentNodeDO> list = intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0)
                .orderByAsc(IntentNodeDO::getSortOrder, IntentNodeDO::getId));

        // 先按 parentCode 分组
        Map<String, List<IntentNodeDO>> parentMap = list.stream()
                .collect(Collectors.groupingBy(node -> {
                    String parent = node.getParentCode();
                    return parent == null ? "ROOT" : parent;
                }));

        // 根节点：parentCode 为空
        List<IntentNodeDO> roots = parentMap.getOrDefault("ROOT", Collections.emptyList());

        // 递归构建树
        List<IntentNodeTreeVO> tree = new ArrayList<>();
        for (IntentNodeDO root : roots) {
            tree.add(buildTree(root, parentMap));
        }
        return tree;
    }

    /**
     * 利用父编码分组递归构建意图树，并把每个节点的集合绑定转换为兼容的新字段。
     */
    private IntentNodeTreeVO buildTree(IntentNodeDO current,
                                       Map<String, List<IntentNodeDO>> parentMap) {
        IntentNodeTreeVO result = BeanUtil.toBean(current, IntentNodeTreeVO.class);
        result.setCollectionNames(effectiveCollectionNames(current));
        List<IntentNodeDO> children = parentMap.getOrDefault(current.getIntentCode(), Collections.emptyList());

        if (!CollectionUtils.isEmpty(children)) {
            List<IntentNodeTreeVO> childVOs = children.stream()
                    .map(child -> buildTree(child, parentMap))
                    .collect(Collectors.toList());

            result.setChildren(childVOs);
        }

        return result;
    }

    /**
     * 创建 KB 或 MCP 意图节点，保存类型对应的配置字段。
     * intentCode 不能重复；TOPIC 级 KB 节点必须至少绑定一个有效知识库。
     */
    @Override
    @LogRecord(
            success = "创建意图节点：{{#requestParam.name}}",
            fail = "创建意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String createNode(IntentNodeCreateRequest requestParam) {
        // 先校验标识并解析知识库绑定，MCP 节点不保存 KB 专属提示词字段。
        // 简单重复校验：intentCode 不允许重复
        long count = intentNodeMapper.selectCount(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, requestParam.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + requestParam.getIntentCode());
        }

        int kind = requestParam.getKind() == null ? IntentKind.KB.getCode() : requestParam.getKind();
        CollectionBinding collectionBinding = Objects.equals(kind, IntentKind.KB.getCode())
                ? resolveCreateCollectionBinding(requestParam)
                : CollectionBinding.empty();
        if (Objects.equals(requestParam.getLevel(), IntentLevel.TOPIC.getCode())
                && Objects.equals(kind, IntentKind.KB.getCode())
                && collectionBinding.collectionNames().isEmpty()) {
            throw new ClientException("TOPIC级别的RAG检索节点必须至少指定一个目标知识库");
        }

        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode(requestParam.getIntentCode())
                .kbId(collectionBinding.primaryKbId())
                .collectionName(firstOrNull(collectionBinding.collectionNames()))
                .collectionNames(collectionBinding.collectionNames())
                .name(requestParam.getName())
                .level(requestParam.getLevel())
                .parentCode(requestParam.getParentCode())
                .description(requestParam.getDescription())
                .mcpToolId(Objects.equals(kind, IntentKind.MCP.getCode())
                        ? StrUtil.trim(requestParam.getMcpToolId()) : null)
                .requireConfirm(normalizeRequireConfirm(requestParam.getRequireConfirm(), kind))
                .examples(
                        requestParam.getExamples() == null ? null : GSON.toJson(requestParam.getExamples())
                )
                .topK(normalizeTopK(requestParam.getTopK()))
                .kind(kind)
                .sortOrder(
                        requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder()
                )
                .enabled(
                        requestParam.getEnabled() == null ? 1 : requestParam.getEnabled()
                )
                .createBy(UserContext.getUsername())
                .updateBy(UserContext.getUsername())
                .promptSnippet(Objects.equals(kind, IntentKind.MCP.getCode()) ? null : requestParam.getPromptSnippet())
                .promptTemplate(Objects.equals(kind, IntentKind.MCP.getCode()) ? null : requestParam.getPromptTemplate())
                .deleted(0)
                .build();

        intentNodeMapper.insert(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();

        bizChangeLogContext.put(String.valueOf(node.getId()), null, node);
        return String.valueOf(node.getId());
    }

    /**
     * 按非 null 请求字段修改意图节点，并清理与节点类型不匹配的配置。
     * 非 MCP 节点清空工具和确认配置，非 KB 节点清空知识库绑定。
     */
    @Override
    @LogRecord(
            success = "更新意图节点：{{#id}}",
            fail = "更新意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void updateNode(String id, IntentNodeUpdateRequest req) {
        // 读取原记录并保留快照，更新完成后统一让意图树缓存失效。
        IntentNodeDO node = intentNodeMapper.selectById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }
        IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);

        if (req.getName() != null) {
            node.setName(req.getName());
        }
        if (req.getLevel() != null) {
            node.setLevel(req.getLevel());
        }
        if (req.getParentCode() != null) {
            node.setParentCode(req.getParentCode());
        }
        if (req.getDescription() != null) {
            node.setDescription(req.getDescription());
        }
        if (req.getExamples() != null) {
            node.setExamples(GSON.toJson(req.getExamples()));
        }
        if (req.getMcpToolId() != null) {
            node.setMcpToolId(StrUtil.trim(req.getMcpToolId()));
        }

        // 同时兼容多集合配置和旧的单集合字段，只有明确提交绑定值时才覆盖。
        CollectionBinding collectionBinding = null;
        if (req.getCollectionNames() != null) {
            collectionBinding = resolveCollectionBinding(req.getCollectionNames());
        } else if (req.getCollectionName() != null) {
            collectionBinding = resolveCollectionBinding(
                    StrUtil.isBlank(req.getCollectionName()) ? List.of() : List.of(req.getCollectionName())
            );
        }
        if (collectionBinding != null) {
            applyCollectionBinding(node, collectionBinding);
        }
        if (req.getTopK() != null) {
            node.setTopK(normalizeTopK(req.getTopK()));
        }
        if (req.getKind() != null) {
            node.setKind(req.getKind());
        }
        if (req.getRequireConfirm() != null) {
            node.setRequireConfirm(normalizeRequireConfirm(req.getRequireConfirm(), node.getKind()));
        }
        if (!Objects.equals(node.getKind(), IntentKind.MCP.getCode())) {
            // 切换节点类型时清除不再适用的工具与确认配置，避免残留旧类型参数。
            node.setMcpToolId(null);
            node.setRequireConfirm(0);
        }
        if (!Objects.equals(node.getKind(), IntentKind.KB.getCode())) {
            applyCollectionBinding(node, CollectionBinding.empty());
        }
        if (Objects.equals(node.getKind(), IntentKind.KB.getCode())
                && Objects.equals(node.getLevel(), IntentLevel.TOPIC.getCode())
                && effectiveCollectionNames(node).isEmpty()) {
            throw new ClientException("TOPIC级别的RAG检索节点必须至少指定一个目标知识库");
        }
        if (req.getSortOrder() != null) {
            node.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            node.setEnabled(req.getEnabled());
        }
        if (!Objects.equals(node.getKind(), IntentKind.MCP.getCode())) {
            if (req.getPromptSnippet() != null) {
                node.setPromptSnippet(req.getPromptSnippet());
            }
            if (req.getPromptTemplate() != null) {
                node.setPromptTemplate(req.getPromptTemplate());
            }
        }
        node.setUpdateBy(UserContext.getUsername());
        intentNodeMapper.updateById(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, intentNodeMapper.selectById(id));
    }

    /**
     * 删除指定有效节点并刷新意图树缓存。
     * 此单节点方法只删除当前 ID，不自动收集或级联删除子树。
     */
    @Override
    @LogRecord(
            success = "删除意图节点：{{#id}}",
            fail = "删除意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void deleteNode(String id) {
        // 节点不存在或已删除时拒绝操作，避免生成无效的审计记录。
        IntentNodeDO node = intentNodeMapper.selectById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }
        IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);
        intentNodeMapper.deleteById(id);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, null);
    }

    /**
     * 对明确选中的有效节点统一设置启用状态。
     * 先规范化、去重并校验所有 ID 存在，完成后刷新意图树缓存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量启用意图节点",
            fail = "批量启用意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.ENABLE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchEnableNodes(List<String> ids) {
        // 批量启用不会自动追加未选择的父节点或子节点。
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(1);
            node.setUpdateBy(operator);
        });
        intentNodeMapper.updateById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, targetNodes);
    }

    /**
     * 停用所选节点，要求其仍启用的后代也包含在选择集合中。
     * 缺少启用子节点时拒绝整次操作，不擅自把未选择的子节点一起停用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量禁用意图节点",
            fail = "批量禁用意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DISABLE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchDisableNodes(List<String> ids) {
        // 先读取目标快照和当前节点树，再验证停用后是否留下未选中的启用后代。
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> enabledButNotSelected = descendants.stream()
                    .filter(item -> Objects.equals(item.getEnabled(), 1) && !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(enabledButNotSelected)) {
                throw new ClientException(
                        String.format(
                                "批量停用失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                targetNode.getName(),
                                summarizeNodeNames(enabledButNotSelected)
                        )
                );
            }
        }
        // 所有后代范围校验完成后才执行实际停用，未选中的节点不会被自动加入。
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(0);
            node.setUpdateBy(operator);
        });
        intentNodeMapper.updateById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, targetNodes);
    }

    /**
     * 删除显式选择的节点，要求每个目标的所有后代都被选中。
     * 不完整子树直接报错，只有通过检查后才批量删除所选 ID。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量删除意图节点",
            fail = "批量删除意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DELETE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchDeleteNodes(List<String> ids) {
        // 完整子树校验用于避免父节点删除后遗留无父级的有效配置。
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> notSelectedDescendants = descendants.stream()
                    .filter(item -> !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(notSelectedDescendants)) {
                List<IntentNodeDO> enabledDescendants = notSelectedDescendants.stream()
                        .filter(item -> Objects.equals(item.getEnabled(), 1))
                        .collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(enabledDescendants)) {
                    throw new ClientException(
                            String.format(
                                    "批量删除失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                    targetNode.getName(),
                                    summarizeNodeNames(enabledDescendants)
                            )
                    );
                }
                throw new ClientException(
                        String.format(
                                "批量删除失败：节点 [%s] 未包含全量子节点（如：%s），请先勾选完整子树后再删除",
                                targetNode.getName(),
                                summarizeNodeNames(notSelectedDescendants)
                        )
                );
            }
        }
        // 完整选择校验全部通过，才集中删除请求中的目标并失效意图树缓存。
        intentNodeMapper.deleteByIds(targetIdSet);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, null);
    }

    /**
     * 规范化节点级 TopK：
     * - null 表示未配置，回退全局默认
     * - 仅允许正整数
     * 校验节点级检索条数配置。
     * null 表示未指定覆盖值，显式设置时必须大于 0。
     */
    private Integer normalizeTopK(Integer topK) {
        // 不把未配置强行变成固定数值，以便下游继续采用默认预算。
        if (topK == null) {
            return null;
        }
        if (topK <= 0) {
            throw new ClientException("节点级 TopK 必须大于 0");
        }
        return topK;
    }

    /**
     * 非 MCP 节点一律落 0：确认标志只在工具调用链路上被读取，留在别的类型上是无效数据
     * 规范化 MCP 节点的执行前确认开关。
     * 只有 MCP 类型可以开启确认，其他节点类型统一返回 0。
     */
    private Integer normalizeRequireConfirm(Integer requireConfirm, Integer kind) {
        // 避免 KB 检索节点残留没有业务意义的工具确认配置。
        if (!Objects.equals(kind, IntentKind.MCP.getCode())) {
            return 0;
        }
        return Objects.equals(requireConfirm, 1) ? 1 : 0;
    }

    /**
     * 创建接口兼容旧的 kbId，同时将新的 collectionNames 作为明确的优先输入
     */
    private CollectionBinding resolveCreateCollectionBinding(IntentNodeCreateRequest request) {
        if (request.getCollectionNames() != null) {
            return resolveCollectionBinding(request.getCollectionNames());
        }
        if (StrUtil.isBlank(request.getKbId())) {
            return CollectionBinding.empty();
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(request.getKbId());
        if (knowledgeBase == null || Objects.equals(knowledgeBase.getDeleted(), 1)) {
            throw new ClientException("知识库不存在或已删除: " + request.getKbId());
        }
        return new CollectionBinding(List.of(knowledgeBase.getCollectionName()), knowledgeBase.getId());
    }

    /**
     * 校验 Collection 均来自有效知识库，并保持前端选择顺序
     * 校验知识库集合列表并形成兼容单库字段的绑定结构。
     * 所有集合必须存在且有效，列表第一项对应主知识库 ID。
     */
    private CollectionBinding resolveCollectionBinding(List<String> requestedCollectionNames) {
        // 先去重归一集合名，再一次查询有效知识库并指出缺失项。
        List<String> collectionNames = normalizeCollectionNames(requestedCollectionNames);
        if (collectionNames.isEmpty()) {
            return CollectionBinding.empty();
        }

        List<KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .in(KnowledgeBaseDO::getCollectionName, collectionNames)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        Map<String, KnowledgeBaseDO> byCollectionName = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBaseDO::getCollectionName, item -> item));
        List<String> missing = collectionNames.stream()
                .filter(collectionName -> !byCollectionName.containsKey(collectionName))
                .toList();
        if (!missing.isEmpty()) {
            throw new ClientException("知识库 Collection 不存在或已删除: " + missing);
        }

        return new CollectionBinding(
                collectionNames,
                byCollectionName.get(collectionNames.get(0)).getId()
        );
    }

    /**
     * 去除空集合名及首尾空白，按首次出现顺序去重，返回不可变列表。
     */
    private List<String> normalizeCollectionNames(List<String> collectionNames) {
        if (collectionNames == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = collectionNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return List.copyOf(normalized);
    }

    /**
     * 回填集合列表、兼容的单集合字段以及主知识库 ID，使新旧绑定字段保持一致。
     */
    private void applyCollectionBinding(IntentNodeDO node, CollectionBinding binding) {
        node.setCollectionNames(binding.collectionNames());
        node.setCollectionName(firstOrNull(binding.collectionNames()));
        node.setKbId(binding.primaryKbId());
    }

    /**
     * 优先读取多集合配置，旧记录仅有单集合时转为列表，两者均无配置时返回空列表。
     */
    private List<String> effectiveCollectionNames(IntentNodeDO node) {
        if (CollectionUtils.isNotEmpty(node.getCollectionNames())) {
            return normalizeCollectionNames(node.getCollectionNames());
        }
        if (StrUtil.isNotBlank(node.getCollectionName())) {
            return List.of(node.getCollectionName().trim());
        }
        return List.of();
    }

    /**
     * 取得集合列表的首项作为兼容字段，空列表返回 null。
     */
    private String firstOrNull(List<String> values) {
        return CollectionUtils.isEmpty(values) ? null : values.get(0);
    }

    private record CollectionBinding(List<String> collectionNames, String primaryKbId) {

        private static CollectionBinding empty() {
            return new CollectionBinding(List.of(), null);
        }
    }

    /**
     * 归一批量节点 ID 并验证每个目标都存在且未删除。
     * null ID 被过滤、重复 ID 去重，缺失目标会返回明确的业务错误。
     */
    private List<IntentNodeDO> listAndValidateTargetNodes(List<String> ids) {
        // 整批目标校验通过后才允许后续批量更新，避免默默跳过无效 ID。
        Assert.notEmpty(ids, () -> new ClientException("请至少选择一个节点"));
        List<String> normalizedIds = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Assert.notEmpty(normalizedIds, () -> new ClientException("节点ID不能为空"));
        List<IntentNodeDO> targetNodes = intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .in(IntentNodeDO::getId, normalizedIds)
                .eq(IntentNodeDO::getDeleted, 0));
        if (targetNodes.size() != normalizedIds.size()) {
            Set<String> existingIds = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
            List<String> missingIds = normalizedIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .limit(5)
                    .toList();
            throw new ClientException("节点不存在或已删除: " + missingIds);
        }
        return targetNodes;
    }

    /**
     * 读取未逻辑删除的意图节点，供构树和绑定校验使用。
     */
    private List<IntentNodeDO> listActiveNodes() {
        return intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0));
    }

    /**
     * 按父意图编码将节点分组；没有父编码的节点归到 ROOT，供递归构树使用。
     */
    private Map<String, List<IntentNodeDO>> buildChildrenMap(List<IntentNodeDO> nodes) {
        return nodes.stream().collect(Collectors.groupingBy(node -> {
            String parentCode = node.getParentCode();
            return parentCode == null ? "ROOT" : parentCode;
        }));
    }

    /**
     * 按意图编码和父子映射展开全部后代节点。
     * 返回集合不包含起始节点本身，用于批量停用和删除的完整子树校验。
     */
    private List<IntentNodeDO> collectDescendants(String intentCode, Map<String, List<IntentNodeDO>> childrenMap) {
        // 使用显式栈遍历子节点，校验结果随后与请求选择的 ID 集合对比。
        if (StrUtil.isBlank(intentCode)) {
            return Collections.emptyList();
        }
        List<IntentNodeDO> result = new ArrayList<>();
        Deque<IntentNodeDO> stack = new ArrayDeque<>(
                childrenMap.getOrDefault(intentCode, Collections.emptyList())
        );
        while (!stack.isEmpty()) {
            IntentNodeDO current = stack.pop();
            result.add(current);
            List<IntentNodeDO> children = childrenMap.getOrDefault(current.getIntentCode(), Collections.emptyList());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    /**
     * 取最多三个节点名称拼成提示文本；缺少名称时使用意图编码。
     */
    private String summarizeNodeNames(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .limit(3)
                .map(item -> StrUtil.blankToDefault(item.getName(), item.getIntentCode()))
                .collect(Collectors.joining("、"));
    }

    /**
     * 复制节点对象，隔离后续对绑定等字段的修改，保留变更前快照。
     */
    private List<IntentNodeDO> copyNodes(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .map(node -> BeanUtil.copyProperties(node, IntentNodeDO.class))
                .toList();
    }
}
