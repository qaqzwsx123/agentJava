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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据清洗流水线业务逻辑实现
 */
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {
    // 摄取流水线管理实现：持久化流水线和节点配置，组装 PipelineDefinition 与前端视图，并处理创建、更新、删除及业务变更记录。

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 在事务中创建流水线主记录和节点配置。
     * 名称唯一键冲突转换为业务异常，返回包含节点列表的完整配置视图。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "创建数据通道：{{#request.name}}",
            fail = "创建数据通道失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_PIPELINE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        // 校验请求并插入流水线主记录，名称冲突转换为业务提示；再保存节点并记录创建后的变更快照。
        // 先保存主记录取得 ID，再把节点与该流水线建立关联。
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            throw new ClientException("流水线名称已存在");
        }
        upsertNodes(pipeline.getId(), request.getNodes());
        IngestionPipelineVO result = toVO(pipeline, fetchNodes(pipeline.getId()));
        bizChangeLogContext.put(String.valueOf(pipeline.getId()), null, result);
        return result;
    }

    /**
     * 修改流水线基本信息，并按请求决定是否整体替换节点。
     * nodes=null 保留旧节点，非 null 则删除原节点后按新列表重建。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "更新数据通道：{{#pipelineId}}",
            fail = "更新数据通道失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_PIPELINE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#pipelineId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        // 先保留主记录和节点的原始快照，供审计比对配置变化。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        IngestionPipelineVO before = toVO(BeanUtil.copyProperties(pipeline, IngestionPipelineDO.class), fetchNodes(pipeline.getId()));

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        // 未提供 nodes 时只改基本信息；提供空列表则表示明确清空节点。
        if (request.getNodes() != null) {
            upsertNodes(pipeline.getId(), request.getNodes());
        }
        IngestionPipelineVO result = toVO(pipelineMapper.selectById(pipelineId), fetchNodes(pipeline.getId()));
        bizChangeLogContext.put(pipelineId, before, result);
        return result;
    }

    /**
     * 查询流水线主记录及全部有效节点。
     * 流水线不存在时抛出业务异常，节点配置按持久化内容转换返回。
     */
    @Override
    public IngestionPipelineVO get(String pipelineId) {
        // 配置读取不启动摄入引擎，可用于编辑或任务准备阶段。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 按名称关键字分页查询有效流水线。
     * 结果按更新时间倒序，并为每条流水线补充节点列表。
     */
    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword) {
        // 按未删除和可选名称关键词分页，逐条加载节点配置，转换为页面需要的流水线视图。
        // 把接口分页对象转换为数据库实体分页对象。
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList());
        return voPage;
    }

    /**
     * 在事务中删除流水线及所属节点。
     * 先确认主记录存在，保留原始主记录和节点信息用于变更审计。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除数据通道：{{#pipelineId}}",
            fail = "删除数据通道失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_PIPELINE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#pipelineId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String pipelineId) {
        // 先保存删除前快照，再删除主记录及其节点配置，最后登记本次删除的变更日志。
        // 主记录和节点配置统一清理，避免留下没有所属流水线的有效节点。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        IngestionPipelineVO before = toVO(BeanUtil.copyProperties(pipeline, IngestionPipelineDO.class), fetchNodes(pipeline.getId()));
        pipeline.setDeleted(1);
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.deleteById(pipeline);

        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipeline.getId());
        nodeMapper.delete(qw);
        bizChangeLogContext.put(pipelineId, before, null);
    }

    /**
     * 将保存的流水线与节点转换为摄入引擎可执行的定义对象。
     * 节点包含类型、设置、条件和后继节点，缺失流水线时拒绝执行。
     */
    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        // 从持久化配置组装执行引擎使用的定义，节点设置和条件恢复为 JSON 结构。
        // 管理端 VO 与引擎 PipelineDefinition 分开构造，避免执行层依赖页面字段。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    /**
     * 按请求列表整体替换流水线节点，而不是逐节点增量合并。
     * nodes=null 直接返回；非 null 时先物理清理旧配置，再逐个插入新节点。
     */
    private void upsertNodes(String pipelineId, List<IngestionPipelineNodeRequest> nodes) {
        // 物理删除旧行以允许相同 nodeId 被新配置重新使用。
        if (nodes == null) {
            return;
        }
        // 这里实际采用全量替换，不能按方法名误认为是保留未提交节点的增量更新。
        nodeMapper.physicalDeleteByPipelineId(pipelineId);
        for (IngestionPipelineNodeRequest node : nodes) {
            if (node == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(node.getNodeId())
                    .nodeType(normalizeNodeType(node.getNodeType()))
                    .nextNodeId(node.getNextNodeId())
                    .settingsJson(toJson(node.getSettings()))
                    .conditionJson(toJson(node.getCondition()))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    /**
     * 查询指定流水线下未删除的节点，供详情展示与执行配置组装使用。
     */
    private List<IngestionPipelineNodeDO> fetchNodes(String pipelineId) {
        // 只查询指定流水线下未删除的节点，避免混入其他流水线配置。
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    /**
     * 将流水线记录和节点列表合并为详情响应，节点配置通过 toNodeVO 统一转换。
     */
    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        // 复制流水线基本字段，并把节点实体逐个转成管理端视图。
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        return vo;
    }

    /**
     * 将节点记录转换为前端结构，统一节点类型并把 settings、condition JSON 还原为树节点。
     */
    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        // 复制节点字段，规范化节点类型，同时把设置和条件文本解析为 JSON。
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        return vo;
    }

    /**
     * 组装执行器所需的节点配置，包含节点类型、参数、执行条件和下一个节点 ID。
     */
    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        // 把数据库节点映射成引擎配置，保留节点 ID、类型、设置、条件及后继节点关系。
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .nextNodeId(node.getNextNodeId())
                .build();
    }

    /**
     * 将非空 JSON 树序列化为数据库文本；null 和 JSON null 都按空值处理。
     */
    private String toJson(JsonNode node) {
        // JSON 空值保存为 null，有效节点直接转换为 JSON 文本。
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    /**
     * 解析节点配置 JSON；配置为空或解析失败时返回 null，由后续配置处理决定默认行为。
     */
    private JsonNode parseJson(String raw) {
        // 空文本或解析失败返回 null，防止历史无效配置文本直接导致展示失败。
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将节点类型归一为摄入引擎识别的枚举值。
     * 空值保持原样，未知的非空类型转换为业务异常。
     */
    private String normalizeNodeType(String nodeType) {
        // 保存时统一节点编码，避免执行引擎读到无法识别的节点类型。
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    /**
     * 把可识别的节点类型转换为标准值；历史未知值保留原文，避免读取详情时直接失败。
     */
    private String normalizeNodeTypeForOutput(String nodeType) {
        // 能识别的旧写法归一为稳定类型值，无法识别的历史值原样保留供诊断。
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }
}
