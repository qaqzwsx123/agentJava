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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.IngestionSpecSchemaVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档管理控制器
 * 提供文档的上传、分块、删除、查询、启用/禁用等功能
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {
    // 知识库文档管理控制器 提供文档的上传、分块、删除、查询、启用/禁用等功能。

    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final IngestionSpecSchemaProvider ingestionSpecSchemaProvider;

    private static final Map<String, String> CONTENT_TYPE_MAP = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("md", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv;charset=utf-8"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml")
    );

    /**
     * 查询摄取配置的表单 schema：描述的正是上传与更新接口里的 {@code ingestionSpec} 字段
     * 返回文档解析、增强和分块配置的描述结构。
     * 供前端动态生成摄入配置表单，不执行实际文档处理。
     */
    @GetMapping("/knowledge-base/docs/ingestion-spec-schema")
    public Result<IngestionSpecSchemaVO> getIngestionSpecSchema() {
        // 配置结构来自 IngestionSpecSchemaProvider，避免前后端各维护一套字段。
        return Results.success(ingestionSpecSchemaProvider.describe());
    }

    /**
     * 上传文档：入库记录 + 文件落盘，返回文档ID
     * 接收文件或外部来源信息，在指定知识库下创建文档记录。
     * kb-id 是知识库 ID；file 可选，其余字段通过 multipart 表单绑定。
     */
    @PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                              @RequestPart(value = "file", required = false) MultipartFile file,
                                              @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
        // 文件上传和来源配置校验由文档服务完成。
        return Results.success(documentService.upload(kbId, requestParam, file));
    }

    /**
     * 开始分块：抽取文本 -> 分块 -> 嵌入并写入向量库
     * 为指定文档发起分块和索引处理。
     * doc-id 定位文档，服务层校验文档状态并发送事务消息。
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunk")
    public Result<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
        // 返回成功表示处理任务已提交，执行进度需通过文档状态和日志查询。
        documentService.startChunk(docId);
        return Results.success();
    }

    /**
     * 删除文档：逻辑删除。可选同时删除向量库中该文档的所有 chunk
     * 删除指定知识文档及其关联数据。
     * doc-id 是文档 ID，服务层负责协调分块、索引、文件与调度记录清理。
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}")
    public Result<Void> delete(@PathVariable(value = "doc-id") String docId) {
        // 只在业务删除调用完成后返回统一成功响应。
        documentService.delete(docId);
        return Results.success();
    }

    /**
     * 查询文档详情
     * 读取知识文档的元数据和当前处理状态。
     * docId 是文档 ID，返回文件来源、处理配置和状态等信息。
     */
    @GetMapping("/knowledge-base/docs/{docId}")
    public Result<KnowledgeDocumentVO> get(@PathVariable String docId) {
        // 查询详情不会触发重新解析或重新分块。
        return Results.success(documentService.get(docId));
    }

    /**
     * 更新文档信息
     * 修改文档元数据、来源或摄入配置。
     * docId 指定文档，请求体提供待更新字段；受文档当前状态约束。
     */
    @PutMapping("/knowledge-base/docs/{docId}")
    public Result<Void> update(@PathVariable String docId,
                               @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
        // 由文档服务区分普通元数据修改和需要影响摄入配置的更新。
        documentService.update(docId, requestParam);
        return Results.success();
    }

    /**
     * 分页查询文档列表（支持状态/关键字过滤）
     * 分页查询指定知识库中的文档。
     * kb-id 限定知识库，查询对象提供分页和筛选条件。
     */
    @GetMapping("/knowledge-base/{kb-id}/docs")
    public Result<IPage<KnowledgeDocumentVO>> page(@PathVariable(value = "kb-id") String kbId,
                                                   KnowledgeDocumentPageRequest requestParam) {
        // 列表返回文档级状态，具体分块内容通过分块接口查询。
        return Results.success(documentService.page(kbId, requestParam));
    }

    /**
     * 搜索文档（全局检索建议）
     * 按关键字搜索知识文档，供选择器快速定位文档。
     * keyword 为搜索词，limit 默认 8，由服务层限制返回规模。
     */
    @GetMapping("/knowledge-base/docs/search")
    public Result<List<KnowledgeDocumentSearchVO>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                                          @RequestParam(value = "limit", defaultValue = "8") int limit) {
        // 返回文档检索结果，不执行问答或向量内容召回。
        return Results.success(documentService.search(keyword, limit));
    }

    /**
     * 启用/禁用文档
     * 切换知识文档是否参与检索。
     * value=true 为启用，false 为停用；docId 指定文档。
     */
    @PatchMapping("/knowledge-base/docs/{docId}/enable")
    public Result<Void> enable(@PathVariable String docId,
                               @RequestParam("value") boolean enabled) {
        // 服务层同步文档及分块的有效状态，防止列表状态与检索状态脱节。
        documentService.enable(docId, enabled);
        return Results.success();
    }

    /**
     * 查询文档分块日志列表
     * 分页查询指定文档的分块执行日志。
     * docId 是文档 ID，page 提供分页参数，便于追踪每次处理结果。
     */
    @GetMapping("/knowledge-base/docs/{docId}/chunk-logs")
    public Result<IPage<KnowledgeDocumentChunkLogVO>> getChunkLogs(@PathVariable String docId,
                                                                   Page<KnowledgeDocumentChunkLogVO> page) {
        // 读取处理日志，不在此接口重试分块任务。
        return Results.success(documentService.getChunkLogs(docId, page));
    }

    /**
     * 预览 markdown 文档内容
     * 获取文档可供前端展示的预览文本。
     * docId 定位文档，当前只支持读取 Markdown 原文件的 UTF-8 文本。
     */
    @GetMapping("/knowledge-base/docs/{docId}/preview")
    public Result<String> preview(@PathVariable String docId) {
        // 接口返回 JSON 中的文本，原始文件流由 file 接口提供。
        return Results.success(documentService.preview(docId));
    }

    /**
     * 获取文档源文件（用于 PDF/图片等浏览器原生支持的格式直接渲染）
     * 将文档原始文件流输出到 HTTP 响应，支持浏览器内联预览。
     * docId 指定文档；本接口直接写响应体，不使用统一 Result 包装。
     */
    @GetMapping("/knowledge-base/docs/{docId}/file")
    public void file(@PathVariable String docId, HttpServletResponse response) throws Exception {
        // 先查询文件地址与类型，再设置 Content-Type 和文件名响应头。
        var doc = documentService.get(docId);
        String fileType = doc.getFileType() != null ? doc.getFileType().toLowerCase() : "";
        String contentType = CONTENT_TYPE_MAP.getOrDefault(fileType, "application/octet-stream");
        // 按文件类型设置响应头，未知类型使用二进制类型，文件名以 UTF-8 编码供内联展示。
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "inline; filename=\"" + URLEncoder.encode(doc.getDocName(), StandardCharsets.UTF_8) + "\"");
        // 直接复制存储输入流到 HTTP 响应，完成或异常后由 try-with-resources 关闭输入流。
        try (InputStream in = fileStorageService.openStream(doc.getFileUrl())) {
            StreamUtils.copy(in, response.getOutputStream());
        }
    }
}
