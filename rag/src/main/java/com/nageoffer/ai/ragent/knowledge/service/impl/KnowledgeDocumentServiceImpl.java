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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.IngestionKernel;
import com.nageoffer.ai.ragent.core.ingest.IngestionOutcome;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ProcessMode;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.schedule.CronScheduleHelper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.util.DisplayType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ParserRegistry parserRegistry;
    private final IngestionKernel ingestionKernel;
    private final ChunkIndexWriter chunkIndexWriter;
    private final IngestionSpecCodec ingestionSpecCodec;
    private final FileStorageService fileStorageService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final IngestionPipelineService ingestionPipelineService;
    private final IngestionPipelineMapper ingestionPipelineMapper;
    private final IngestionEngine ingestionEngine;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final TransactionOperations transactionOperations;
    private final MessageQueueProducer messageQueueProducer;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final RemoteFileFetcher remoteFileFetcher;
    private final VectorTargetResolver vectorTargetResolver;
    private final BizChangeLogContext bizChangeLogContext;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    /**
     * 接收文件或 URL 来源，校验处理配置后保存待处理文档。
     * 上传只创建 PENDING 文档；真正分块通过 startChunk 另行提交。
     */
    @Override
    @LogRecord(
            success = "上传文档：{{#bizChangeName}}",
            fail = "上传文档失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_DOCUMENT,
            subType = BizChangeOperationType.CREATE,
            bizNo = "{{#bizChangeBizId != null ? #bizChangeBizId : #kbId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file) {
        // 先确认知识库存在并校验来源、定时配置，再执行文件存储副作用。
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        SourceType sourceType = SourceType.normalize(requestParam.getSourceType());
        validateSourceAndSchedule(sourceType, requestParam);
        // 摄取配置的校验排在存文件之前：它只看请求参数，而一旦落了对象再抛异常，
        // 存储里就留下一个没有文档指向它的孤儿。纯校验一律前置到第一个副作用之前
        ProcessModeConfig modeConfig = resolveProcessModeConfig(requestParam);
        StoredFileDTO stored = resolveStoredFile(kbDO.getCollectionName(), sourceType, requestParam.getSourceLocation(), file);
        // 前置拦截：与分块阶段同一套 MIME 路由，无解析器的类型直接拒绝，不落库不发 MQ
        if (!parserRegistry.canParse(stored.getMimeType())) {
            fileStorageService.deleteByUrl(stored.getUrl());
            throw new ClientException("暂不支持的文件类型：" + stored.getDetectedType());
        }

        // 保存的是待处理文档元数据；不会在上传请求中立即完成解析和向量入库。
        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .mimeType(stored.getMimeType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? StrUtil.trimToNull(requestParam.getSourceLocation()) : null)
                .scheduleEnabled(isScheduleEnabled(sourceType, requestParam) ? 1 : 0)
                .scheduleCron(isScheduleEnabled(sourceType, requestParam) ? StrUtil.trimToNull(requestParam.getScheduleCron()) : null)
                .processMode(modeConfig.processMode().getValue())
                .ingestionSpec(modeConfig.ingestionSpec())
                .pipelineId(modeConfig.pipelineId())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        documentMapper.insert(documentDO);
        bizChangeLogContext.put(String.valueOf(documentDO.getId()), null, documentDO);
        bizChangeLogContext.putName(documentDO.getDocName());

        return toVO(documentDO);
    }

    /**
     * 通过事务消息提交文档分块任务，并将文档切换为 RUNNING。
     * 条件更新拒绝重复启动，操作成功返回不代表后台分块已经完成。
     */
    @Override
    @LogRecord(
            success = "开始文档分块：{{#bizChangeName}}",
            fail = "开始文档分块失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_DOCUMENT,
            subType = BizChangeOperationType.RUN,
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void startChunk(String docId) {
        // 事件只携带文档标识和操作人，消费者稍后读取最新文档配置。
        KnowledgeDocumentDO beforeDO = documentMapper.selectById(docId);
        Assert.notNull(beforeDO, () -> new ClientException("文档不存在"));
        bizChangeLogContext.putName(beforeDO.getDocName());
        KnowledgeDocumentDO before = BeanUtil.copyProperties(beforeDO, KnowledgeDocumentDO.class);
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId(docId)
                .operator(UserContext.getUsername())
                .build();

        // 事务回调中条件更新 RUNNING，只有取得执行资格的请求才继续提交分块事件。
        messageQueueProducer.sendInTransaction(
                chunkTopic,
                docId,
                "文档分块",
                event,
                arg -> {
                    // Wrapper 更新不触发 updateTime 自动填充, 显式刷新, 使卡死恢复以分块开始时刻为基准
                    int updated = documentMapper.update(
                            new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                    .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                                    .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                                    .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                                    .eq(KnowledgeDocumentDO::getId, docId)
                                    .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                    );
                    if (updated == 0) {
                        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                        throw new ClientException("文档分块操作正在进行中，请稍后再试");
                    }
                    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                    event.setKbId(documentDO.getKbId());
                    scheduleService.upsertSchedule(documentDO);
                }
        );
        bizChangeLogContext.put(docId, before, documentMapper.selectById(docId));
    }

    /**
     * 消息消费端按文档 ID 执行一次分块任务。
     * 文档已不存在时记录日志并跳过，不继续对失效文档写索引。
     */
    @Override
    public void executeChunk(String docId) {
        // 异步执行前重新读取数据库，避免使用生产消息时的过期文档对象。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        if (documentDO == null) {
            log.warn("文档不存在，跳过分块任务, docId={}", docId);
            return;
        }

        runChunkTask(documentDO);
    }

    /**
     * 执行文档处理内核，并结算文档状态及阶段耗时日志。
     * 当前文档 Pipeline 分支处于重构状态，会明确失败；直接分块使用 ingestionKernel。
     */
    private void runChunkTask(KnowledgeDocumentDO documentDO) {
        // 先建立 RUNNING 处理日志，再运行解析、分块、嵌入和索引写入链路。
        String docId = documentDO.getId();
        ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        VectorTarget target = vectorTargetResolver.resolve(kbDO);
        IngestionSpec spec = ingestionSpecCodec.read(documentDO.getIngestionSpec());
        DocumentRef doc = documentRef(documentDO);

        KnowledgeDocumentChunkLogDO chunkLog = KnowledgeDocumentChunkLogDO.builder()
                .docId(docId)
                .status(DocumentStatus.RUNNING.getCode())
                .processMode(processMode.getValue())
                .parseProfile(spec.parseProfile().getCode())
                .pipelineId(documentDO.getPipelineId())
                .startTime(new Date())
                .build();
        chunkLogMapper.insert(chunkLog);

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;

        try {
            // 管道模式暂停服务：管道将按自定义代码 / 动态脚本重新设计，届时分块沿用同一内核，
            // 下面这段连同 runPipelineProcess 一并重写。此处显式失败，不静默改用默认分块配置
            if (ProcessMode.PIPELINE == processMode) {
                // long start = System.currentTimeMillis();
                // List<EmbeddedChunk> chunks = runPipelineProcess(documentDO, kbDO, target);
                // chunkDuration = System.currentTimeMillis() - start;
                //
                // long persistStart = System.currentTimeMillis();
                // chunkIndexWriter.replaceDocument(target, doc, chunks);
                // persistDuration = System.currentTimeMillis() - persistStart;
                // savedCount = chunks.size();
                throw new ClientException("管道模式重构中，暂不可用，请改用直接分块：docId=" + docId);
            }

            // 直接分块统一走处理内核，再从返回结果回填真实 MIME、分块数量和阶段耗时。
            IngestionOutcome outcome = ingestionKernel.run(doc, readFileBytes(documentDO), spec, target);
            extractDuration = outcome.timings().parseMillis();
            chunkDuration = outcome.timings().chunkMillis();
            embedDuration = outcome.timings().embedMillis();
            persistDuration = outcome.timings().indexMillis();
            int savedCount = outcome.chunkCount();
            // 回填字节探测出的真实 MIME；展示用的 file_type 仍由扩展名决定，两者互不导出
            refreshMimeType(docId, outcome.mimeType());

            markChunkSucceeded(docId, savedCount);
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, null);
            log.info("文档分块任务执行成功：docId={}, chunkCount={}, totalDuration={}ms", docId, savedCount, totalDuration);
        } catch (Exception e) {
            log.error("文档分块任务执行失败：docId={}", docId, e);
            // 失败也要结算文档和处理日志，避免状态一直残留为 RUNNING。
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, e.getMessage());
        }
    }

    /**
     * 提取文档 ID、知识库 ID 和名称，构成后续索引与处理流程使用的文档引用。
     */
    private DocumentRef documentRef(KnowledgeDocumentDO documentDO) {
        // 将文档实体中的文档 ID、知识库 ID 和文件名封装为入库内核引用。
        return new DocumentRef(documentDO.getId(), documentDO.getKbId(), documentDO.getDocName());
    }

    private void markChunkSucceeded(String docId, int chunkCount) {
        // 保存实际块数和成功状态，同时记录当前操作人，供后续列表与调度判断结果。
        documentMapper.updateById(KnowledgeDocumentDO.builder()
                .id(docId)
                .chunkCount(chunkCount)
                .status(DocumentStatus.SUCCESS.getCode())
                .updatedBy(UserContext.getUsername())
                .build());
    }

    private void refreshMimeType(String docId, String mimeType) {
        // 只在 MIME 非空白时更新探测结果，避免用缺失值覆盖已有类型。
        if (!StringUtils.hasText(mimeType)) {
            return;
        }
        documentMapper.updateById(KnowledgeDocumentDO.builder().id(docId).mimeType(mimeType).build());
    }

    private byte[] readFileBytes(KnowledgeDocumentDO documentDO) {
        // 通过存储抽象打开并完整读取文件，自动关闭流，读取失败时转换为带文档 ID 的服务异常。
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new ServiceException("读取文件内容失败：docId=" + documentDO.getId());
        }
    }

    private void updateChunkLog(String logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embedDuration, long persistDuration,
                                long totalDuration, String errorMessage) {
        // 汇总各阶段耗时、块数、状态和错误说明，补结束时间后更新当前分块日志。
        KnowledgeDocumentChunkLogDO update = KnowledgeDocumentChunkLogDO.builder()
                .id(logId)
                .status(status)
                .chunkCount(chunkCount)
                .extractDuration(extractDuration)
                .chunkDuration(chunkDuration)
                .embedDuration(embedDuration)
                .persistDuration(persistDuration)
                .totalDuration(totalDuration)
                .errorMessage(errorMessage)
                .endTime(new Date())
                .build();
        chunkLogMapper.updateById(update);
    }

    private record ProcessModeConfig(ProcessMode processMode, String ingestionSpec, String pipelineId) {
    }

    /**
     * 使用 Pipeline 处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     * 保留的文档 Pipeline 处理实现，构造引擎上下文并返回生成分块。
     * 当前 runChunkTask 不会进入此方法，不能把它当作已启用的文档处理路径。
     */
    private List<EmbeddedChunk> runPipelineProcess(KnowledgeDocumentDO documentDO,
                                                   KnowledgeBaseDO kbDO,
                                                   VectorTarget target) {
        // 要求绑定流水线，读取文件并显式传入向量落点；设置跳过内层索引写入，最终由外层统一持久化。
        // skipIndexerWrite 避免流水线内重复落索引，写入由外层文档处理流程控制。
        String docId = String.valueOf(documentDO.getId());
        String pipelineId = documentDO.getPipelineId();

        if (pipelineId == null) {
            throw new IllegalStateException("Pipeline模式下Pipeline ID为空：docId=" + docId);
        }

        PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(pipelineId);
        byte[] fileBytes = readFileBytes(documentDO);

        IngestionContext context = IngestionContext.builder()
                .taskId(docId)
                .pipelineId(pipelineId)
                .rawBytes(fileBytes)
                .vectorTarget(target)
                .vectorSpaceId(VectorSpaceId.builder()
                        .logicalName(kbDO.getCollectionName())
                        .build())
                .skipIndexerWrite(true)
                .build();

        // 流水线只负责准备块和向量，返回错误会向外抛出，避免把失败上下文当成功结果保存。
        IngestionContext result = ingestionEngine.execute(pipelineDef, context);

        if (result.getError() != null) {
            throw new RuntimeException("Pipeline执行失败：" + result.getError().getMessage(), result.getError());
        }

        List<EmbeddedChunk> chunks = result.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
            return List.of();
        }

        return chunks;
    }

    /**
     * 为已有文档对象执行分块处理，供调度等内部入口复用。
     * 空对象直接返回，其余处理与消息消费端共用 runChunkTask。
     */
    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        // 本入口不再单独发送事务消息，直接进入共享的分块执行流程。
        if (documentDO == null) {
            return;
        }
        runChunkTask(documentDO);
    }

    private void markChunkFailed(String docId) {
        // 通过事务回调将文档标为失败并保存操作人，供分块异常路径统一收尾。
        transactionOperations.executeWithoutResult(status -> {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(update);
        });
    }

    /**
     * 删除非运行中文档，并清理调度、处理日志、索引和原文件。
     * 文档处于 RUNNING 时拒绝删除，文件清理采用容错路径。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除文档：{{#bizChangeName}}",
            fail = "删除文档失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_DOCUMENT,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String docId) {
        // 先检查文档状态并保留审计快照，避免删除与后台分块同时执行。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        bizChangeLogContext.putName(documentDO.getDocName());
        KnowledgeDocumentDO before = BeanUtil.copyProperties(documentDO, KnowledgeDocumentDO.class);

        // 禁止在文档分块运行时删除
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        // 一次调用覆盖全部落点：关系库块与向量都在扇出里，未来加索引后端也自动跟随
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        chunkIndexWriter.deleteDocument(vectorTargetResolver.resolve(kbDO), documentRef(documentDO));
        deleteStoredFileQuietly(documentDO);
        bizChangeLogContext.put(docId, before, null);
    }

    /**
     * 读取文档并转换为展示对象。
     * 文档不存在时抛出异常，转换阶段补充前端需要的状态信息。
     */
    @Override
    public KnowledgeDocumentVO get(String docId) {
        // 详情查询不会重新下载文件、调用解析器或触发向量重建。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return toVO(documentDO);
    }

    /**
     * 修改文档名称及可选的处理模式、来源和定时抓取配置。
     * 运行中文档拒绝修改；直接分块配置与 Pipeline 引用按模式互斥保存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "更新文档：{{#bizChangeName}}",
            fail = "更新文档失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_DOCUMENT,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        // 先校验文档状态与必填名称，再组装显式更新字段。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        bizChangeLogContext.putName(documentDO.getDocName());
        KnowledgeDocumentDO before = BeanUtil.copyProperties(documentDO, KnowledgeDocumentDO.class);

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        LambdaUpdateWrapper<KnowledgeDocumentDO> updateWrapper = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, documentDO.getId())
                .set(KnowledgeDocumentDO::getDocName, docName.trim())
                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername());

        // 如果传了 processMode，校验并更新处理配置
        if (StringUtils.hasText(requestParam.getProcessMode())) {
            ProcessMode processMode = ProcessMode.normalize(requestParam.getProcessMode());
            updateWrapper.set(KnowledgeDocumentDO::getProcessMode, processMode.getValue());

            if (ProcessMode.CHUNK == processMode) {
                String spec = ingestionSpecCodec.normalize(requestParam.getIngestionSpec());
                updateWrapper.setSql("ingestion_spec = CAST({0} AS jsonb)", spec);
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, null);
            } else {
                if (!StringUtils.hasText(requestParam.getPipelineId())) {
                    throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
                }
                try {
                    ingestionPipelineService.get(requestParam.getPipelineId());
                } catch (Exception e) {
                    throw new ClientException("指定的Pipeline不存在: " + requestParam.getPipelineId());
                }
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, requestParam.getPipelineId());
                updateWrapper.set(KnowledgeDocumentDO::getIngestionSpec, null);
            }
        }

        // 处理定时调度相关字段（仅 URL 类型文档支持）
        // 仅 URL 来源处理抓取地址与定时配置，最终启用状态需结合旧值和请求值一起校验。
        boolean scheduleChanged = false;
        if (SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            String newSourceLocation = requestParam.getSourceLocation();
            Integer newScheduleEnabled = requestParam.getScheduleEnabled();
            String newScheduleCron = requestParam.getScheduleCron();

            if (StringUtils.hasText(newSourceLocation)) {
                updateWrapper.set(KnowledgeDocumentDO::getSourceLocation, newSourceLocation.trim());
                scheduleChanged = true;
            }
            if (newScheduleEnabled != null) {
                updateWrapper.set(KnowledgeDocumentDO::getScheduleEnabled, newScheduleEnabled);
                scheduleChanged = true;
            }
            if (StringUtils.hasText(newScheduleCron)) {
                try {
                    CronScheduleHelper.nextRunTime(newScheduleCron, new Date());
                    // 验证 cron 周期不能太短（与 upsertSchedule 保持一致）
                    if (CronScheduleHelper.isIntervalLessThan(newScheduleCron, new Date(), 60)) {
                        throw new ClientException("定时周期不能小于 60 秒");
                    }
                } catch (IllegalArgumentException e) {
                    throw new ClientException("定时表达式不合法: " + e.getMessage());
                }
                updateWrapper.set(KnowledgeDocumentDO::getScheduleCron, newScheduleCron.trim());
                scheduleChanged = true;
            }

            // 验证：启用定时拉取时必须有 cron 和 sourceLocation
            if (scheduleChanged) {
                KnowledgeDocumentDO willBe = documentMapper.selectById(docId);
                Integer finalEnabled = newScheduleEnabled != null ? newScheduleEnabled : willBe.getScheduleEnabled();
                String finalCron = StringUtils.hasText(newScheduleCron) ? newScheduleCron.trim() : willBe.getScheduleCron();
                String finalLocation = StringUtils.hasText(newSourceLocation) ? newSourceLocation.trim() : willBe.getSourceLocation();

                if (finalEnabled != null && finalEnabled == 1) {
                    if (!StringUtils.hasText(finalCron)) {
                        throw new ClientException("启用定时拉取时必须设置定时表达式");
                    }
                    if (!StringUtils.hasText(finalLocation)) {
                        throw new ClientException("启用定时拉取时必须设置来源地址");
                    }
                }
            }
        }

        documentMapper.update(updateWrapper);

        if (scheduleChanged) {
            KnowledgeDocumentDO updated = documentMapper.selectById(docId);
            scheduleService.upsertSchedule(updated);
        }
        bizChangeLogContext.put(docId, before, documentMapper.selectById(docId));
    }

    /**
     * 分页读取知识库文档，并补充是否存在人工编辑分块的标记。
     * 可按名称和状态筛选，人工编辑标记对当前页文档批量查询。
     */
    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam) {
        // 分页查询指定知识库的有效文档，再批量检测本页哪些文档的块曾被编辑，补充展示标记。
        // 列表限定有效文档和知识库范围，按创建时间倒序展示。
        Page<KnowledgeDocumentDO> pageParam = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(requestParam.getKeyword() != null && !requestParam.getKeyword().isBlank(), KnowledgeDocumentDO::getDocName, requestParam.getKeyword())
                .eq(requestParam.getStatus() != null && !requestParam.getStatus().isBlank(), KnowledgeDocumentDO::getStatus, requestParam.getStatus())
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        IPage<KnowledgeDocumentVO> result = documentMapper.selectPage(pageParam, queryWrapper)
                .convert(this::toVO);

        List<String> docIds = result.getRecords().stream()
                .map(KnowledgeDocumentVO::getId)
                .collect(Collectors.toList());
        Set<String> editedDocIds = findEditedDocIds(docIds);
        result.getRecords().forEach(vo -> vo.setChunksEdited(editedDocIds.contains(vo.getId())));

        return result;
    }

    /**
     * DO → VO：摄取配置经 codec 归一化后再出参
     * <p>
     * 库里可能留着旧构建写下的 {@code Integer.MAX_VALUE}（整篇不分块的领域内部哨兵），
     * 而线路上的约定是 {@code -1}。归一化放在出参这一层，旧行不刷库也能正确回显；
     * 列为空表示"走默认"，这个语义要留着，所以空值不在此处物化成一份显式 JSON
     */
    private KnowledgeDocumentVO toVO(KnowledgeDocumentDO documentDO) {
        // 复制文档字段，并将旧摄取配置按当前编码规则读取再输出，使返回配置结构一致。
        KnowledgeDocumentVO vo = BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
        if (StringUtils.hasText(documentDO.getIngestionSpec())) {
            vo.setIngestionSpec(ingestionSpecCodec.write(ingestionSpecCodec.read(documentDO.getIngestionSpec())));
        }
        return vo;
    }

    /**
     * 批量找出存在已编辑切片的文档：以切片更新时间超过创建时间一秒作为判定条件。
     */
    private Set<String> findEditedDocIds(List<String> docIds) {
        // 按修改时间比创建时间晚一秒以上识别编辑过的块，返回其所属文档的去重 ID 集合。
        if (docIds == null || docIds.isEmpty()) {
            return Collections.emptySet();
        }
        QueryWrapper<KnowledgeChunkDO> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT doc_id")
                .in("doc_id", docIds)
                .apply("update_time > create_time + INTERVAL '1 second'");
        return chunkMapper.selectObjs(wrapper).stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }

    /**
     * 按文档名关键字返回少量候选文档及所属知识库名称。
     * 空关键字返回空列表，数量限制在 1 到 20 之间，知识库名称批量补充。
     */
    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        // 管理端按文档名查找，限制最多二十条，再批量补知识库名称；这是文档选择搜索而非语义检索。
        // 该搜索用于管理端文档选择，不是对文档分块做 RAG 内容检索。
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = knowledgeBaseMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    /**
     * 切换文档及其所有分块的检索状态，并同步调度和向量。
     * 启用所需向量在事务外预生成，再在事务回调中更新状态与检索落点。
     */
    @Override
    @LogRecord(
            success = "{{#enabled ? '启用' : '禁用'}}文档：{{#bizChangeName}}",
            fail = "修改文档启用状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_DOCUMENT,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void enable(String docId, boolean enabled) {
        // 运行中文档不能启停，已经是目标状态时跳过重复写入。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        bizChangeLogContext.putName(documentDO.getDocName());
        KnowledgeDocumentDO before = BeanUtil.copyProperties(documentDO, KnowledgeDocumentDO.class);

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        // 如果已经是目标状态，直接返回
        int targetEnabled = enabled ? 1 : 0;
        if (documentDO.getEnabled() != null && documentDO.getEnabled() == targetEnabled) {
            bizChangeLogContext.skip();
            return;
        }

        // 提前查知识库，两个分支都需要，避免重复查询
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        // 启用时：embed 耗时较长，在事务外提前执行，避免长事务占用连接
        List<EmbeddedChunk> vectorChunks = Collections.emptyList();
        if (enabled) {
            // 向量文本取库里那份，不用展示文本重新组装——否则章节路径与表格 KV 渲染会静默丢失
            vectorChunks = knowledgeChunkService.embedPersistedChunks(docId, vectorTargetResolver.resolve(kbDO));
            if (CollUtil.isEmpty(vectorChunks)) {
                log.warn("启用文档时未找到任何 Chunk，仅更新启用状态并跳过向量重建，docId={}", docId);
            }
        }

        final List<EmbeddedChunk> finalEmbeddedChunks = vectorChunks;
        // 嵌入已经准备完成，事务回调负责更新文档、分块、调度以及向量落点。
        transactionOperations.executeWithoutResult(status -> {
            documentDO.setEnabled(targetEnabled);
            documentDO.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(documentDO);
            scheduleService.syncScheduleIfExists(documentDO);
            knowledgeChunkService.updateEnabledByDocId(docId, String.valueOf(kbDO.getId()), enabled);

            if (!enabled) {
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
            } else if (CollUtil.isNotEmpty(finalEmbeddedChunks)) {
                vectorStoreService.indexDocumentChunks(collectionName, docId, finalEmbeddedChunks);
            }
        });
        bizChangeLogContext.put(docId, before, documentMapper.selectById(docId));
    }

    /**
     * 分页读取文档处理日志，补充流水线名称和其他耗时。
     * 其他耗时由总耗时扣除各阶段耗时得到，最小限制为 0。
     */
    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        // 分页取文档分块日志，批量补流水线名称，并按处理模式计算各显式阶段以外的耗时。
        // 先查询日志当前页，再集中查询所引用的流水线名称。
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        Map<String, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<String> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        // 名称映射查询完成后再转换当前页，其他耗时不允许显示为负数。
        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    /**
     * 从总耗时中扣除已单独统计的阶段耗时，计算其他耗时；流水线模式采用不同的阶段口径。
     */
    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        // 流水线模式只减去分块与持久化耗时，普通模式另外减去提取和嵌入，缺失阶段按零处理。
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embed = each.getEmbedDuration() == null ? 0 : each.getEmbedDuration();
        long persist = each.getPersistDuration() == null ? 0 : each.getPersistDuration();
        return pipelineMode
                ? totalDuration - chunk - persist
                : totalDuration - extract - chunk - embed - persist;
    }

    /**
     * 只有 URL 来源且请求显式开启定时更新时才启用调度。
     */
    private boolean isScheduleEnabled(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        // 只有 URL 来源且请求明确启用时才认为定时刷新开启，文件上传来源不参与。
        return SourceType.URL == sourceType && Boolean.TRUE.equals(request.getScheduleEnabled());
    }

    /**
     * 在保存文件前验证来源地址和定时抓取配置。
     * URL 来源必须有地址，启用定时抓取时还需合法且满足最小间隔的 cron。
     */
    private void validateSourceAndSchedule(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        // 先检查远端地址，再对已启用调度校验 Cron 与最小间隔，避免产生文件后才发现参数错误。
        // 把纯参数校验前置，避免校验失败后留下没有文档引用的存储文件。
        String sourceLocation = StrUtil.trimToNull(request.getSourceLocation());
        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (!isScheduleEnabled(sourceType, request)) {
            return;
        }
        String scheduleCron = StrUtil.trimToNull(request.getScheduleCron());
        if (!StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        try {
            if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new java.util.Date(), scheduleProperties.getMinIntervalSeconds())) {
                throw new ClientException("定时周期不能小于 " + scheduleProperties.getMinIntervalSeconds() + " 秒");
            }
        } catch (IllegalArgumentException e) {
            throw new ClientException("定时表达式不合法");
        }
    }

    /**
     * 把请求归一为直接分块配置或 Pipeline 引用。
     * 直接分块时规范化 ingestionSpec；Pipeline 模式要求 ID 对应的配置存在。
     */
    private ProcessModeConfig resolveProcessModeConfig(KnowledgeDocumentUploadRequest request) {
        // 普通分块模式规范化摄取配置；流水线模式要求流水线存在，只保存对应模式需要的字段。
        // 两种模式只保存各自需要的字段，避免下游读取混合配置。
        ProcessMode processMode = ProcessMode.normalize(request.getProcessMode());
        if (ProcessMode.CHUNK == processMode) {
            return new ProcessModeConfig(processMode, ingestionSpecCodec.normalize(request.getIngestionSpec()), null);
        } else {
            if (!StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
            return new ProcessModeConfig(processMode, null, request.getPipelineId());
        }
    }

    /**
     * 文件来源校验并上传 MultipartFile，其他来源交给远程抓取器下载入库，统一返回存储信息。
     */
    private StoredFileDTO resolveStoredFile(String bucketName, SourceType sourceType, String sourceLocation, MultipartFile file) {
        // 本地来源要求文件存在并直接上传，URL 来源交给远端抓取与存储流程。
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, file);
        }
        return remoteFileFetcher.fetchAndStore(bucketName, sourceLocation);
    }


    /**
     * 读取 Markdown 文档原文件并以 UTF-8 文本返回。
     * 当前只支持 MARKDOWN 展示类型，其他格式需要使用原文件预览接口。
     */
    @Override
    public String preview(String docId) {
        // 只允许 Markdown 文档预览，按 UTF-8 读取存储内容并自动关闭流，读取失败转换为客户端提示。
        // 先检查文档和预览类型，使用 try-with-resources 确保输入流释放。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DisplayType.from(documentDO.getFileType()) != DisplayType.MARKDOWN) {
            throw new ClientException("仅支持预览 markdown 格式文档");
        }
        try (InputStream in = fileStorageService.openStream(documentDO.getFileUrl())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("读取文档内容失败: " + e.getMessage());
        }
    }

    /**
     * 尽力删除文档原始文件，文件清理失败时记录日志。
     * 用于文档删除收尾，不让单个外部存储异常替代全部业务清理结果。
     */
    private void deleteStoredFileQuietly(KnowledgeDocumentDO documentDO) {
        // 外部文件清理失败时记录告警，不把清理异常继续向外抛出。
        if (documentDO == null || !StringUtils.hasText(documentDO.getFileUrl())) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        } catch (Exception e) {
            log.warn("删除文档存储文件失败, docId={}, fileUrl={}", documentDO.getId(), documentDO.getFileUrl(), e);
        }
    }
}
