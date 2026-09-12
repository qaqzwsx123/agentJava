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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库 Chunk 管理接口
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /**
     * 分页查询 Chunk 列表
     * 分页查询指定文档下的知识分块。
     * doc-id 定位文档，查询参数经过校验后传入分块服务。
     */
    @GetMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<IPage<KnowledgeChunkVO>> pageQuery(@PathVariable("doc-id") String docId,
                                                     @Validated KnowledgeChunkPageRequest requestParam) {
        // 按文档范围查询分块记录，返回分块正文及状态等展示信息。
        return Results.success(knowledgeChunkService.pageQuery(docId, requestParam));
    }

    /**
     * 新增 Chunk
     * 为指定文档新增人工维护的知识分块。
     * doc-id 指定文档，请求体提供分块内容及相关属性。
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<KnowledgeChunkVO> create(@PathVariable("doc-id") String docId,
                                           @RequestBody KnowledgeChunkCreateRequest request) {
        // 数据库记录和向量写入由服务层协调处理。
        return Results.success(knowledgeChunkService.create(docId, request));
    }

    /**
     * 更新 Chunk 内容
     * 修改指定文档中的一个知识分块。
     * doc-id 与 chunk-id 共同定位分块，请求体提供修改内容。
     */
    @PutMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> update(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestBody KnowledgeChunkUpdateRequest request) {
        // 服务层确认分块归属，并根据修改内容同步检索侧数据。
        knowledgeChunkService.update(docId, chunkId, request);
        return Results.success();
    }

    /**
     * 删除 Chunk
     * 删除指定文档中的一个知识分块。
     * doc-id 和 chunk-id 限定删除范围，避免只按分块 ID 操作。
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> delete(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        // 分块记录及关联向量的清理由服务层执行。
        knowledgeChunkService.delete(docId, chunkId);
        return Results.success();
    }

    /**
     * 启用或禁用单条 Chunk
     * 切换单个知识分块是否参与检索。
     * value=true 表示启用，false 表示停用；路径参数指定文档和分块。
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable")
    public Result<Void> enable(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestParam("value") boolean enabled) {
        // 启停会影响检索侧状态，不只是前端列表显示。
        knowledgeChunkService.enableChunk(docId, chunkId, enabled);
        return Results.success();
    }

    /**
     * 批量启用或禁用 Chunk
     * 批量切换文档下所选知识分块的启用状态。
     * value 是目标状态；请求体必须提供 chunkIds，空选择会报错，不能用来表示全量操作。
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/batch-enable")
    public Result<Void> batchEnable(@PathVariable("doc-id") String docId,
                                    @RequestParam("value") boolean enabled,
                                    @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        // 批量选择范围和向量状态同步统一交由分块服务处理。
        knowledgeChunkService.batchToggleEnabled(docId, request, enabled);
        return Results.success();
    }
}
