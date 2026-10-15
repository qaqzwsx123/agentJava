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
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库采集任务控制层
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionTaskController {

    private final IngestionTaskService taskService;

    /**
     * 创建并执行采集任务
     * 按指定文档来源同步执行摄入流水线，返回本次任务结果。
     * 请求体提供 pipelineId、来源配置及可选向量空间，结果中包含 taskId 和状态。
     */
    @PostMapping("/ingestion/tasks")
    public Result<IngestionResult> create(@RequestBody IngestionTaskCreateRequest request) {
        // 这里调用流水线执行引擎，不把接口返回成功等同于所有节点业务成功。
        return Results.success(taskService.execute(request));
    }

    /**
     * 上传文件并触发采集任务
     * 上传文件并使用指定摄入流水线处理文件内容。
     * multipart 表单中 pipelineId 指定流水线，file 为待处理文件。
     */
    @SneakyThrows
    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<IngestionResult> upload(@RequestParam(value = "pipelineId") String pipelineId,
                                          @RequestPart("file") MultipartFile file) {
        // 文件字节和 MIME 类型由服务层解析后传给摄入引擎。
        return Results.success(taskService.upload(pipelineId, file));
    }

    /**
     * 根据任务 ID 获取任务详情
     * 查询一条摄入任务的状态、日志摘要和元数据。
     * id 是任务 ID，便于前端查看成功结果或失败原因。
     */
    @GetMapping("/ingestion/tasks/{id}")
    public Result<IngestionTaskVO> get(@PathVariable String id) {
        // 读取已保存的任务记录，不重新执行流水线。
        return Results.success(taskService.get(id));
    }

    /**
     * 根据任务 ID 获取任务节点运行记录
     * 查询摄入任务各节点的执行详情。
     * id 是任务 ID，返回节点状态、耗时、错误及输出信息。
     */
    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Result<List<IngestionTaskNodeVO>> nodes(@PathVariable String id) {
        // 服务层按节点执行顺序排列明细，便于定位失败步骤。
        return Results.success(taskService.listNodes(id));
    }

    /**
     * 分页查询采集任务
     * 按执行状态分页查询摄入任务。
     * pageNo 默认 1、pageSize 默认 10；status 不传时查询全部状态。
     */
    @GetMapping("/ingestion/tasks")
    public Result<IPage<IngestionTaskVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                               @RequestParam(value = "status", required = false) String status) {
        // 任务结果与节点详情分开查询，列表接口只返回任务级信息。
        return Results.success(taskService.page(new Page<>(pageNo, pageSize), status));
    }
}
