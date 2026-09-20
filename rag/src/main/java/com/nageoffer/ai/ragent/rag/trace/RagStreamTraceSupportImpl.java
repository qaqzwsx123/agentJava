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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨线程 stream trace 实现：解决 @RagTraceNode AOP 在 stream 场景
 * 只测到 runAsync 提交的问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagStreamTraceSupportImpl implements RagStreamTraceSupport {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final RagTraceProperties traceProperties;
    private final RagTraceRecordService traceRecordService;

    /**
     * 在已启用且存在追踪上下文时创建流节点，记录当前父节点与深度，并压入调用线程节点栈。
     * 返回的 StreamSpan 负责异步完成或取消时收尾；追踪未启用时使用空操作句柄。
     */
    @Override
    public StreamSpan beginStreamNode(String name, String type) {
        // 有追踪上下文时创建运行中的流节点并压栈，否则使用空操作 span，避免调用方逐处判断追踪开关。
        if (!traceProperties.isEnabled()) {
            return NOOP_SPAN;
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            return NOOP_SPAN;
        }

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        long startMillis = System.currentTimeMillis();

        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(type, "STREAM"))
                .nodeName(name)
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .build());

        // 调用线程上 push，使后续同步子节点（如 first-packet）能识别父节点
        RagTraceContext.pushNode(nodeId);

        return new StreamSpanImpl(traceId, nodeId, startMillis);
    }

    private final class StreamSpanImpl implements StreamSpan {
        private final String traceId;
        private final String nodeId;
        private final long startMillis;
        private final AtomicBoolean detached = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        StreamSpanImpl(String traceId, String nodeId, long startMillis) {
            // 保存流节点标识和开始时间，结束回调可在不同线程使用这些固定值计算耗时。
            this.traceId = traceId;
            this.nodeId = nodeId;
            this.startMillis = startMillis;
        }

        @Override
        public void detach() {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            // 仅当栈顶为本节点才 pop，防止与并发节点错乱
            if (nodeId.equals(RagTraceContext.currentNodeId())) {
                RagTraceContext.popNode();
            }
        }

        @Override
        public void finishSuccess() {
            // 通过原子标记保证流节点只结束一次，写入成功与耗时，记录失败仅记日志。
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishSuccess 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        @Override
        public void finishError(Throwable error) {
            // 只允许首次结束回调写入失败状态和截断错误，避免完成与异常竞争造成终态覆盖。
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_ERROR,
                        truncateError(error), new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishError 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        @Override
        public void finishCancelledIfRunning() {
            // 只有节点尚未结束时才标记取消，追踪写入异常不再影响流式主流程。
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_CANCELLED, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishCancelled 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }
    }

    private String truncateError(Throwable throwable) {
        // 把异常类型和消息压缩到允许长度，空异常保持 null。
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": "
                + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}
