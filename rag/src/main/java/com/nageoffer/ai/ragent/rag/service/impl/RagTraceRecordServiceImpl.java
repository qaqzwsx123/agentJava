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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * RAG Trace 记录服务实现
 */
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {
    // RAG 追踪写入实现：通过运行和节点 Mapper 保存开始记录及结束状态，持久化耗时、输出概况和异常，供后台追踪查询。

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;

    /**
     * 插入一次 RAG 请求的追踪主记录。
     * 参数已由追踪上游构造，包含运行标识与初始状态。
     */
    @Override
    public void startRun(RagTraceRunDO run) {
        // 在请求启动阶段保存主记录，供结束时按 traceId 回写结果。
        runMapper.insert(run);
    }

    /**
     * 按 traceId 回写请求级追踪结果。
     * 更新状态、错误、结束时间及总耗时，不新建第二条运行记录。
     */
    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        // 结束回写通过条件更新定位原主记录。
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    /**
     * 保存一个执行阶段的初始追踪节点。
     * 节点承载所属 traceId 与自身 nodeId，供阶段结束时定位。
     */
    @Override
    public void startNode(RagTraceNodeDO node) {
        // 节点开始与节点结束分开落库，便于观察执行中的链路。
        nodeMapper.insert(node);
    }

    /**
     * 更新指定追踪中的一个执行节点的结束结果。
     * 条件同时包含 traceId 和 nodeId，防止跨运行更新同名节点。
     */
    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        // 只补充当前节点的终态和耗时，不影响同一追踪中的其他节点。
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}
