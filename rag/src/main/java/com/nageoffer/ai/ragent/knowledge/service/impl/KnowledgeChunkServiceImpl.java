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
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import cn.hutool.crypto.SecureUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库 Chunk 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final VectorTargetResolver vectorTargetResolver;
    private final TokenCounterService tokenCounterService;
    private final VectorStoreService vectorStoreService;
    private final TransactionOperations transactionOperations;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 分页读取指定文档的知识分块，可按启用状态过滤。
     * 先检查文档存在，结果按 chunkIndex 升序排列以保持原文顺序。
     */
    @Override
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        // 确认文档存在后按文档 ID 和可选启用状态分页，按块序号保持文档内顺序。
        // 分块查询显式限定文档 ID，不跨文档混合分页。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        LambdaQueryWrapper<KnowledgeChunkDO> queryWrapper = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(requestParam.getEnabled() != null, KnowledgeChunkDO::getEnabled, requestParam.getEnabled())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex);

        Page<KnowledgeChunkDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeChunkDO> result = chunkMapper.selectPage(page, queryWrapper);
        return result.convert(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class));
    }

    /**
     * 新增人工分块，计算内容摘要及长度，并同步写入向量索引。
     * 文档必须启用且不在分块处理中；未传 index 时追加到当前最大序号之后。
     */
    @Override
    @Transactional(rollbackFor = Exception.class) // 开启事务，任何异常都回滚数据库操作
    @LogRecord(  //{{#...}} 都是 SpEL 表达式，由 AOP 切面在方法执行后解析，#_ret 代表返回值
            // 成功日志：记录返回对象 KnowledgeChunkVO 的 id
            success = "新增 Chunk：{{#_ret.id}}",
            // 失败日志：记录错误信息
            fail = "新增 Chunk 失败：{{#_errorMsg}}",
            // 业务变更类型：知识库 Chunk
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            // 业务变更操作类型：创建
            subType = BizChangeOperationType.CREATE,
            // 业务编号：优先使用 bizChangeBizId，否则使用 docId
            bizNo = "{{#bizChangeBizId != null ? #bizChangeBizId : #docId}}",
            // 记录业务变更快照
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            // 满足条件时才记录日志
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam) {
        // 1. 查询文档，确认文档存在
        // 先检查文档状态和分块正文，避免人工编辑与自动分块互相覆盖。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 2. 文档正在自动分块处理中时，禁止人工新增 Chunk，避免并发覆盖
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持新增 Chunk");
        }

        // 3. 文档未启用时，禁止新增 Chunk
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，暂不支持新增 Chunk");
        }

        // 4. 校验 Chunk 正文不能为空
        String content = requestParam.getContent();
        Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

        // 5. 确定 chunkIndex：
        //    - 如果请求指定了 index，则使用指定值；
        //    - 否则查询当前文档下最大的 chunkIndex，并追加到其后；
        //    - 如果当前没有任何 Chunk，则从 0 开始。
        // 未指定序号时追加到最后，人工块的 embeddingText 明确保存为正文。
        KnowledgeChunkDO latest = chunkMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                        .last("LIMIT 1")
        );
        int chunkIndex = requestParam.getIndex() != null
                ? requestParam.getIndex()
                : (latest != null ? latest.getChunkIndex() + 1 : 0);

        // 6. 计算内容 SHA-256 摘要和字符长度
        String contentHash = SecureUtil.sha256(content);
        int charCount = content.length();

        // 7. 查询知识库，获取向量集合名和 embedding 模型信息
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();

        // 8. 解析 token 数量，供检索、统计或模型限制使用
        Integer tokenCount = resolveTokenCount(content);

        // 9. 构造 Chunk 实体
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                // Chunk 主键，允许调用方传入 chunkId
                .id(requestParam.getChunkId())
                // 所属知识库
                .kbId(documentDO.getKbId())
                // 所属文档
                .docId(docId)
                // Chunk 顺序号
                .chunkIndex(chunkIndex)
                // 原始正文
                .content(content)
                // 正文摘要，用于判断内容是否变化
                .contentHash(contentHash)
                // 字符数
                .charCount(charCount)
                // token 数
                .tokenCount(tokenCount)
                // 人工块没有结构信息，向量文本等于正文；
                // 显式写下而不是留空，重建向量索引时才不必猜。
                .embeddingText(content)
                // 启用状态
                .enabled(1)
                // 创建人、更新人来自当前登录上下文
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();

        // 10. 插入数据库
        chunkMapper.insert(chunkDO);
        log.info("新增 Chunk 成功, kbId={}, docId={}, chunkId={}, chunkIndex={}",
                documentDO.getKbId(), docId, chunkDO.getId(), chunkIndex);

        // 11. 文档的 Chunk 数量加 1
        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = chunk_count + 1"));

        // 12. 同步写入向量库：
        //     collectionName 为知识库对应向量集合；  docId 用于向量检索过滤； chunkDO 包含正文和 embeddingText；
        //     vectorTargetResolver.resolve(kbDO) 解析目标向量库配置。
        // 组合出三个参数： 集合名称：知识库记录中的 collectionName 嵌入模型：知识库记录中的 embeddingModel
        //向量维度：YAML 中的 rag.default.dimension
        syncChunkToVector(collectionName, docId, chunkDO, vectorTargetResolver.resolve(kbDO));

        // 13. 写入业务变更日志上下文，保存新增 Chunk 的快照
        bizChangeLogContext.put(String.valueOf(chunkDO.getId()), null, chunkDO);

        // 14. 转换为 VO 返回
        return BeanUtil.toBean(chunkDO, KnowledgeChunkVO.class);
    }

    /**
     * 修改人工分块正文、摘要、统计值及对应向量。
     * 先验证分块属于该文档；正文没有变化时跳过数据库更新与审计记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "更新 Chunk：{{#chunkId}}",
            fail = "更新 Chunk 失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam) {
        // 运行中的文档不允许编辑分块，避免编辑结果随后被自动分块覆盖。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        String newContent = requestParam.getContent();
        Assert.notBlank(newContent, () -> new ClientException("Chunk 内容不能为空"));

        // 正文未变化则无需重新计算嵌入，也不产生空的审计变更。
        if (newContent.equals(chunkDO.getContent())) {
            bizChangeLogContext.skip();
            return;
        }

        chunkDO.setContent(newContent);
        chunkDO.setContentHash(SecureUtil.sha256(newContent));
        chunkDO.setCharCount(newContent.length());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        chunkDO.setTokenCount(resolveTokenCount(newContent));
        // 向量文本必须跟着正文一起改：否则向量按新正文更新、库里那份还是旧文本，下次重建就用错的文本
        chunkDO.setEmbeddingText(newContent);
        chunkDO.setUpdatedBy(UserContext.getUsername());

        chunkMapper.updateById(chunkDO);

        log.info("更新 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        // 同步向量数据库
        vectorStoreService.updateChunk(collectionName, docId,
                embedPersisted(List.of(chunkDO), vectorTargetResolver.resolve(kbDO)).get(0));
        bizChangeLogContext.put(chunkId, before, chunkMapper.selectById(chunkId));
    }

    /**
     * 删除指定分块，并同步减少文档分块数及清理对应向量。
     * 操作前检查文档运行状态和分块归属，分块数不会减成负值。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除 Chunk：{{#chunkId}}",
            fail = "删除 Chunk 失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String docId, String chunkId) {
        // 拒绝运行中文档的修改并校验块归属，删除关系库记录、递减块数，再删除向量并保存变更日志。
        // 先加载文档和分块，保证后续清理使用正确知识库的向量集合。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持删除 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        Assert.notNull(kbDO, () -> new ServiceException("知识库不存在"));
        String collectionName = kbDO.getCollectionName();

        chunkMapper.deleteById(chunkId);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = CASE WHEN chunk_count > 0 THEN chunk_count - 1 ELSE 0 END"));

        log.info("删除 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        deleteChunkFromVector(collectionName, chunkId);
        bizChangeLogContext.put(chunkId, before, null);
    }

    /**
     * 切换单个分块状态，启用时重建向量，停用时移除向量。
     * 启用分块前文档本身必须启用；状态未变时跳过写入和审计。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "{{#enabled ? '启用' : '禁用'}} Chunk：{{#chunkId}}",
            fail = "修改 Chunk 启用状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#chunkId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void enableChunk(String docId, String chunkId, boolean enabled) {
        // 分块启用条件与文档状态保持一致，避免停用文档下出现可检索分块。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));
        KnowledgeChunkDO before = BeanUtil.copyProperties(chunkDO, KnowledgeChunkDO.class);

        // 如果状态没变，直接返回
        int enabledValue = enabled ? 1 : 0;
        if (chunkDO.getEnabled().equals(enabledValue)) {
            bizChangeLogContext.skip();
            return;
        }

        chunkDO.setEnabled(enabledValue);
        chunkDO.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunkDO);

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();
        log.info("{}Chunk 成功, kbId={}, docId={}, chunkId={}", enabled ? "启用" : "禁用", documentDO.getKbId(), docId, chunkId);

        if (enabled) {
            String embeddingModel = kbDO.getEmbeddingModel();
            syncChunkToVector(collectionName, docId, chunkDO, vectorTargetResolver.resolve(kbDO));
        } else {
            deleteChunkFromVector(collectionName, chunkId);
        }
        bizChangeLogContext.put(chunkId, before, chunkMapper.selectById(chunkId));
    }

    /**
     * 批量启停明确选择的分块，并同步对应向量数据。
     * 必须提供 chunkIds，单次不超过 500 个，所有 ID 都必须存在且属于指定文档。
     */
    @Override
    @LogRecord(
            success = "批量{{#enabled ? '启用' : '禁用'}} Chunk：{{#docId}}",
            fail = "批量修改 Chunk 启用状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.KNOWLEDGE_CHUNK,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#docId}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled) {
        // 空选择不是全量操作，全量启停应走文档级启用接口。
        if (requestParam == null || CollUtil.isEmpty(requestParam.getChunkIds())) {
            throw new ClientException("请指定需要操作的 Chunk，全量启用/禁用请使用文档启用接口");
        }
        List<String> requestedIds = requestParam.getChunkIds();
        if (requestedIds.size() > 500) {
            throw new ClientException("单次批量操作 Chunk 数量不能超过 500");
        }

        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持批量修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        // 一次读取全部选择项，数量和归属全部校验通过后才执行批量修改。
        List<KnowledgeChunkDO> found = chunkMapper.selectByIds(requestedIds);
        if (found.size() != requestedIds.size()) {
            throw new ClientException("存在无效的 Chunk ID，请求 " + requestedIds.size() + " 个，实际找到 " + found.size() + " 个");
        }
        found.forEach(c -> {
            if (!c.getDocId().equals(docId)) {
                throw new ClientException("Chunk " + c.getId() + " 不属于文档 " + docId);
            }
        });
        List<String> targetIds = found.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(targetIds)) {
            bizChangeLogContext.skip();
            return;
        }

        int enabledValue = enabled ? 1 : 0;
        // 只更新状态确有变化的分块；全部已是目标状态时向前端说明无需重复操作。
        List<KnowledgeChunkDO> needUpdateChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .in(KnowledgeChunkDO::getId, targetIds)
                        .ne(KnowledgeChunkDO::getEnabled, enabledValue)
        );
        List<String> needUpdateIds = needUpdateChunks.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(needUpdateIds)) {
            throw new ClientException(enabled ? "所有 Chunk 已全部启用，无需重复操作" : "所有 Chunk 已全部禁用，无需重复操作");
        }
        List<KnowledgeChunkDO> before = needUpdateChunks.stream()
                .map(each -> BeanUtil.copyProperties(each, KnowledgeChunkDO.class))
                .collect(Collectors.toList());

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        if (enabled) {
            // 耗时嵌入先在事务外完成，减少数据库事务占用连接和锁的时间。
            List<EmbeddedChunk> vectorChunks = embedPersisted(needUpdateChunks, vectorTargetResolver.resolve(kbDO));

            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 1)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.indexDocumentChunks(collectionName, docId, vectorChunks);
            });
        } else {
            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 0)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.deleteChunksByIds(collectionName, needUpdateIds);
            });
        }

        log.info("批量{}Chunk 成功, kbId={}, docId={}, count={}", enabled ? "启用" : "禁用",
                documentDO.getKbId(), docId, needUpdateIds.size());
        bizChangeLogContext.put(docId, before, chunkMapper.selectByIds(needUpdateIds));
    }

    /**
     * 在文档级启停过程中统一更新该文档全部分块的状态。
     * 本方法只修改关系库标记，向量重建或删除由调用方协调。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabledByDocId(String docId, String kbId, boolean enabled) {
        // 与选中分块批量接口不同，这里按文档范围执行全量状态更新。
        int enabledValue = enabled ? 1 : 0;
        chunkMapper.update(
                Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .set(KnowledgeChunkDO::getEnabled, enabledValue)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
        );
        log.info("根据文档ID更新所有Chunk启用状态, kbId={}, docId={}, enabled={}", kbId, docId, enabled);
    }

    /**
     * 读取文档已经持久化的分块并重新生成向量。
     * 使用保存的 embeddingText，而不是根据展示正文重拼，以保留结构化信息。
     */
    @Override
    public List<EmbeddedChunk> embedPersistedChunks(String docId, VectorTarget target) {
        // 按文档内序号加载已有块，使用指定知识库落点重新生成嵌入，空块列表直接返回。
        // 先验证文档存在，再按分块顺序读取原始向量文本来源。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        List<KnowledgeChunkDO> chunkDOList = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );
        if (CollUtil.isEmpty(chunkDOList)) {
            return List.of();
        }
        return embedPersisted(chunkDOList, target);
    }

    /**
     * 按文档 ID 删除关系库中的分块记录。
     * docId 为 null 时直接返回；本方法本身不删除外部向量。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        // 分块索引扇出链路可复用此方法清理关系库存储落点。
        if (docId == null) {
            return;
        }
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
    }

    // ==================== 私有方法 ====================

    /**
     * 启用 chunk 前必须保证所属文档为启用状态
     * 约束分块只能在文档启用时被启用。
     * 停用分块不受此条件限制；启用请求命中停用文档时抛出异常。
     */
    private void validateDocumentEnabledForChunkEnable(KnowledgeDocumentDO documentDO, boolean enableChunk) {
        // 避免文档级与分块级启用状态产生互相矛盾的检索结果。
        if (!enableChunk) {
            return;
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，无法启用Chunk，请先启用文档");
        }
    }

    /**
     * 将单个 chunk 同步到向量库
     * 将一个已保存分块的向量表示写入目标集合。
     * 向量生成沿用持久化文本，集合名与文档 ID 决定索引范围。
     */
    private void syncChunkToVector(String collectionName, String docId, KnowledgeChunkDO chunkDO,
                                   VectorTarget target) {
        // 关系库记录与检索侧向量写入是两个步骤，这里只处理向量落点。
        // 把一条已保存的知识分块 chunkDO 进行向量化，取得带有向量的 EmbeddedChunk 对象。
        EmbeddedChunk chunk = embedPersisted(List.of(chunkDO), target).get(0);
        // 这个地方由于当rag.vector.type=pg ，所以进入 PgVectorStoreService.indexDocumentChunks()
        vectorStoreService.indexDocumentChunks(collectionName, docId, List.of(chunk));

        log.debug("同步 Chunk 到向量库成功, collectionName={}, docId={}, chunkId={}", collectionName, docId, chunkDO.getId());
    }

    /**
     * 从向量库删除单个 chunk
     */
    private void deleteChunkFromVector(String collectionName, String chunkId) {
        // 将集合名与块 ID 一起交给向量服务，删除对应索引记录并记录调试日志。
        vectorStoreService.deleteChunkById(collectionName, chunkId);
        log.debug("从向量库删除 Chunk, collectionName={}, chunkId={}", collectionName, chunkId);
    }

    /**
     * 已入库块重新向量化：向量文本取库里那一份，块 ID 沿用关系库主键
     * <p>
     * 全系统"行 → 向量"的唯一入口，见 {@link ChunkAssembler#restore}
     * 批量将数据库分块转换为嵌入输入并计算向量。
     * 没有分块时返回空列表，输入文本沿用保存的 embeddingText。
     */
    private List<EmbeddedChunk> embedPersisted(List<KnowledgeChunkDO> rows, VectorTarget target) {
        // 统一人工修改和文档重建的嵌入入口，避免两条路径使用不同文本。
        List<Chunk> chunks = rows.stream()
                .map(each -> ChunkAssembler.restore(each.getId(),
                        each.getChunkIndex() == null ? 0 : each.getChunkIndex(),
                        each.getContent(), each.getEmbeddingText()))
                .toList();
        return chunkEmbeddingService.embed(chunks, target);
    }

    /**
     * 估算非空切片的 token 数；空正文记为零，供切片信息保存和展示使用。
     */
    private Integer resolveTokenCount(String content) {
        // 空白正文无需分词计数，其余统一调用 Token 计数服务。
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        return tokenCounterService.countTokens(content);
    }
}
