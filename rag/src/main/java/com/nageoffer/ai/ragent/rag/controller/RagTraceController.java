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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG Trace 查询接口
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {
    // RAG 调用链查询 HTTP 入口：提供运行分页、单轮详情和节点列表，将过滤条件交给追踪查询服务并返回统一响应。

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     * 按筛选条件分页查询 RAG 请求追踪记录。
     * 查询参数可限定 traceId、conversationId、taskId 和状态，返回运行级概览。
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        // 详细节点和载荷通过单独接口获取，避免列表携带过多数据。
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询链路详情（包含节点）
     * 查询一次 RAG 请求的完整追踪详情。
     * traceId 是追踪 ID，服务层按该标识读取运行记录并补充节点信息。
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String traceId) {
        // 追踪记录用于查看历史执行情况，不会重新执行原请求。
        return Results.success(ragTraceQueryService.detail(traceId));
    }

    /**
     * 仅查询链路节点
     * 查询指定 RAG 追踪下的执行节点。
     * traceId 标识一次追踪，节点列表供前端还原执行链路。
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String traceId) {
        // 追踪查询服务按节点开始时间和 ID 排序，供前端稳定展示执行顺序。
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }
}
