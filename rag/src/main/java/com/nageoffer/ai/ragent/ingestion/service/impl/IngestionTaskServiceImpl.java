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

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.core.parser.mime.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据摄入任务服务实现
 */
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {
    // 摄取任务业务实现：校验来源和流水线，创建任务与执行上下文，调用 IngestionEngine 执行并持久化任务状态、各节点日志及结果摘要。

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final ObjectMapper objectMapper;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 把请求中的文档来源转换为内部对象并同步执行摄入任务。
     * 返回任务 ID、状态和分块数量，同时保存任务审计快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "执行采集任务：{{#_ret.taskId}}",
            fail = "执行采集任务失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_TASK,
            subType = BizChangeOperationType.RUN,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        // 先验证请求，再读取指定流水线并交给共同的执行入口。
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        DocumentSource source = toSource(request.getSource());
        IngestionResult result = executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
        putTaskSnapshot(result);
        return result;
    }

    /**
     * 读取上传文件字节，探测 MIME 后同步执行指定流水线。
     * 没有原文件名时使用默认名称，读取或执行异常按当前分支转换为业务异常。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "上传并执行采集任务：{{#_ret.taskId}}",
            fail = "上传并执行采集任务失败：{{#_errorMsg}}",
            type = BizChangeBizType.INGESTION_TASK,
            subType = BizChangeOperationType.RUN,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        // 上传入口与来源请求入口共用 executeInternal，避免重复维护任务落库逻辑。
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            // 根据文件实际字节及名称探测类型，再把原始字节直接交给引擎。
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            IngestionResult result = executeInternal(pipelineId, source, bytes, mimeType, null);
            putTaskSnapshot(result);
            return result;
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 读取摄入任务主记录并还原展示用日志与元数据。
     * 任务 ID 不存在时抛出业务异常，不创建占位任务。
     */
    @Override
    public IngestionTaskVO get(String taskId) {
        // 只查询已有执行结果，不重新运行流水线节点。
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    /**
     * 按可选状态分页查询有效摄入任务。
     * 结果按创建时间倒序，状态值先通过枚举归一化再构造条件。
     */
    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        // 按可选的规范化状态查询未删除任务，按创建时间倒序分页，再转换为任务展示结构。
        // 任务主列表不加载全部节点输出，节点详情另行读取。
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 读取摄入任务的全部有效节点执行明细。
     * 按节点顺序及 ID 升序返回，展示状态、耗时、错误和输出。
     */
    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        // 任务 ID 限定执行范围，不混入其他任务的节点日志。
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * 统一完成流水线加载、任务登记、引擎执行和结果持久化。
     * 先保存 RUNNING 任务，再运行引擎，最后写节点日志并回填最终状态。
     */
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        // 任务记录必须先创建，后续所有节点执行信息才能关联同一个 taskId。
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        // 引擎执行完成后，分别保存节点明细和任务级结果，供管理端分层查看。
        IngestionContext result = engine.execute(pipeline, context);
        saveNodeLogs(task, pipeline, result.getLogs());
        updateTaskFromContext(task, result);
        // 业务成功与失败以 result.status 和 error 为准，HTTP 包装成功不替代任务状态。
        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    /**
     * 把运行完成后的任务记录放入变更日志上下文；结果或任务 ID 缺失时跳过本次日志。
     */
    private void putTaskSnapshot(IngestionResult result) {
        // 只有结果包含任务 ID 才记录数据库快照，否则明确跳过本次变更日志。
        if (result == null || !StringUtils.hasText(result.getTaskId())) {
            bizChangeLogContext.skip();
            return;
        }
        bizChangeLogContext.put(result.getTaskId(), null, taskMapper.selectById(result.getTaskId()));
    }

    /**
     * 把引擎返回的上下文结算到任务主记录。
     * 状态缺失按失败处理，同时保存分块数、错误、完成时间及日志摘要。
     */
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        // 结果状态来自引擎上下文，不根据 HTTP 是否成功返回来判断任务成功。
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 把每个节点的执行日志保存为独立任务明细。
     * 节点状态区分成功、失败和跳过，过大的输出在写入前截断。
     */
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        // 按配置节点顺序逐条保存执行状态、耗时和错误，对详细输出先限长再落库。
        // 先建立配置节点的执行排序映射，便于查询时还原流水线顺序。
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    /**
     * 根据后继节点关系为流水线节点建立展示顺序。
     * 优先从未被引用的入口顺序展开，再补入未覆盖节点，visited 防止重复排序。
     */
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        // 先沿各入口的后继链分配顺序，用 visited 避免重复遍历，剩余孤立或成环节点最后补入展示顺序。
        // 排序用于展示日志，不在这里决定引擎真正执行或条件分支选择。
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        // 先找出被后继引用的节点，剩余节点作为展示顺序的链路入口。
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        // 无法从入口遍历到的节点仍补上顺序，避免成环或孤立配置在日志页中消失。
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    /**
     * 从节点执行日志判定 failed、skipped 或 success；成功但带 Skipped 前缀的节点归为跳过。
     */
    private String resolveNodeStatus(NodeLog log) {
        // 失败优先判定，成功结果再按 Skipped 前缀识别跳过状态，其余记为成功。
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    /**
     * 汇总上下文元数据和增强节点生成的关键词、问题，保存为任务级产物。
     */
    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        // 复制上下文元数据，并在关键词或问题非空时补入同一结果映射。
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    /**
     * 校验必须显式提供流水线 ID，缺失时返回业务异常，不隐式选择默认流水线。
     */
    private String resolvePipelineId(String pipelineId) {
        // 必须明确指定流水线 ID，缺失时抛出客户端异常而不擅自选默认流水线。
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    /**
     * 将可识别的任务状态转换为标准枚举值，未知历史状态保留原文。
     */
    private String normalizeStatus(String status) {
        // 可识别状态转换为统一配置值，未知历史值保持原样供展示或查询。
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    /**
     * 把接口文档来源配置转换为内部 DocumentSource。
     * 来源对象和来源类型必填，地址、文件名及凭据沿请求传入。
     */
    private DocumentSource toSource(DocumentSourceRequest request) {
        // 校验来源请求并复制位置、文件名和凭证，来源类型缺失时提前拒绝执行。
        // 来源配置统一后，后续执行可以复用相同上下文结构。
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    /**
     * 组装任务详情，转换状态、来源类型并解析日志和元数据 JSON，保留运行耗时相关时间字段。
     */
    private IngestionTaskVO toVO(IngestionTaskDO task) {
        // 组装任务基础信息、状态、耗时相关时间、日志摘要和元数据，JSON 字段在转换时恢复。
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(readMap(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    /**
     * 组装节点执行详情，统一类型和状态，并解析节点输出 JSON 供前端查看。
     */
    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        // 把节点记录转成页面详情，规范化类型和状态，并反序列化详细输出字段。
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(readMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    /**
     * 序列化任务日志或元数据；输入为空或序列化失败时返回 null。
     */
    private String writeJson(Object value) {
        // 非空对象序列化为 JSON，失败时回落 null，避免辅助日志序列化打断主流程。
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析任务元数据或节点输出为映射，空值、JSON null 和格式错误均降级为空映射。
     */
    private Map<String, Object> readMap(String raw) {
        // 空文本、JSON null 或反序列化失败都回落空映射，保证详情展示可继续。
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 保留节点状态、耗时与错误等日志摘要，移除体积较大的 output，完整输出由节点记录保存。
     */
    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        // 复制节点日志的状态、消息与耗时，但去掉详细 output，避免主任务记录重复保存大输出。
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    /**
     * 解析数据库中的节点日志数组，缺失或格式错误时返回空列表。
     */
    private List<NodeLog> readLogs(String raw) {
        // 把存储的日志 JSON 还原为节点日志列表，空值或无效内容返回空列表。
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 统一可识别的文件来源类型；未知历史值原样返回。
     */
    private String normalizeSourceType(String sourceType) {
        // 将兼容的来源类型归一为稳定值，未知值原样保留，避免读取历史数据时报错。
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    /**
     * 统一可识别的流水线节点类型；未知历史值原样返回。
     */
    private String normalizeNodeType(String nodeType) {
        // 将节点类型别名归一为稳定值，未知历史值保持原样。
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    /**
     * 去除状态首尾空白、转小写并统一连字符，供节点状态展示使用。
     */
    private String normalizeNodeStatus(String status) {
        // 去首尾空白并转小写，将短横线替换为下划线，统一节点状态输出格式。
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过数据库的 max_allowed_packet 限制
     * 默认限制为 1MB
     * 把节点输出序列化并限制保存长度。
     * 超过当前长度阈值时保留前缀并附加截断提示，无法序列化时返回 null。
     */
    private String truncateOutputJson(Object output) {
        // 日志输出限长用于控制单节点明细体积，不修改引擎真正使用的原始输出。
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
