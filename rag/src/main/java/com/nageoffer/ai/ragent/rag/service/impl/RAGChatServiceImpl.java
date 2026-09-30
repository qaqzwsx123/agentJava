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

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {
    // Workflow 模式 RAG 对话服务：装配排队限流、流式回调、追踪和聊天流水线，启动异步问答并提供用户任务停止入口。

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    /**
     * 准备 Workflow/RAG 流式问答的会话、任务与回调，并交给排队限流器执行。
     * conversationId 可复用，taskId 每次新建；实际问答由 chatPipeline 处理。
     */
    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // 先生成运行标识并绑定 SSE 回调，再把执行动作交给队列控制入口。
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 把真正的问答执行放到排队限流入口中，并用 Trace 包装整条编排链路。
        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinking(Boolean.TRUE.equals(deepThinking))
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .build();
                    chatPipeline.execute(ctx);
                }));
    }

    /**
     * 向任务管理器提交当前用户的流式问答取消请求。
     * 属主校验、Redis 标记及跨节点通知由 cancelByUser 统一处理。
     */
    @Override
    public void stopTask(String taskId) {
        // 取消一次运行任务，不等同于删除聊天会话或历史消息。
        taskManager.cancelByUser(taskId);
    }
}
