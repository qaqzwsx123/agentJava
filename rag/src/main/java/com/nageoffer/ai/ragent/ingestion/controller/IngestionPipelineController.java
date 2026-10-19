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

package com.nageoffer.ai.ragent.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据摄入流水线控制层
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionPipelineController {
    // 摄取流水线管理入口：接收流水线创建、更新、详情、分页和删除请求，将节点与连线配置交给 IngestionPipelineService 处理并返回统一结果。

    private final IngestionPipelineService pipelineService;

    /**
     * 创建数据摄入流水线
     * 创建数据摄入流水线，保存流水线基本信息和节点配置。
     * JSON 请求体包含流水线名称、描述及节点定义，返回创建后的详情。
     */
    @PostMapping("/ingestion/pipelines")
    public Result<IngestionPipelineVO> create(@RequestBody IngestionPipelineCreateRequest request) {
        // 节点配置与流水线主记录由服务层在同一事务中保存。
        return Results.success(pipelineService.create(request));
    }

    /**
     * 更新数据摄入流水线
     * 修改指定摄入流水线及其节点配置。
     * id 是流水线 ID；是否替换节点取决于请求体是否提供 nodes。
     */
    @PutMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> update(@PathVariable String id,
                                              @RequestBody IngestionPipelineUpdateRequest request) {
        // 业务层保留更新前快照，更新配置后返回最新流水线详情。
        return Results.success(pipelineService.update(id, request));
    }

    /**
     * 获取单个数据摄入流水线详情
     * 查询一条摄入流水线及其节点定义。
     * id 是流水线 ID；流水线不存在时由服务层抛出业务异常。
     */
    @GetMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> get(@PathVariable String id) {
        // 返回的节点信息可用于前端恢复流水线编辑界面。
        return Results.success(pipelineService.get(id));
    }

    /**
     * 分页查询数据摄入流水线
     * 分页查询摄入流水线，可按名称关键字筛选。
     * pageNo 默认 1、pageSize 默认 10；keyword 不传则不按名称过滤。
     */
    @GetMapping("/ingestion/pipelines")
    public Result<IPage<IngestionPipelineVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        // 将前端分页参数转换为 MyBatis-Plus 分页对象。
        return Results.success(pipelineService.page(new Page<>(pageNo, pageSize), keyword));
    }

    /**
     * 删除数据摄入流水线
     * 删除指定摄入流水线及关联节点配置。
     * id 用于定位流水线，成功响应表示本次配置删除调用已完成。
     */
    @DeleteMapping("/ingestion/pipelines/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 服务层统一处理主记录、节点和审计快照。
        pipelineService.delete(id);
        return Results.success();
    }
}
