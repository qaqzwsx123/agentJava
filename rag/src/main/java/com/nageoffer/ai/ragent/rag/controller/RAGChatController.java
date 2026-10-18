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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 发起 SSE 流式对话
     * 发起 Workflow/RAG 流式问答，通过 SSE 返回处理过程和答案。
     * question 为提问，conversationId 可选，deepThinking 默认 false。
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    ) //这个IdempotentSubmit是 基于 Spring AOP + Redisson 分布式锁 + 自定义注解”实现的防重复提交
    //在方法执行前，根据特定的“标识”去 Redis 里抢锁。抢到了就执行，抢不到就说明有人在执行同样的操作，直接拒绝
    //括号里面的是一个 Java 类的全限定名，T(com.xxx).a() 请把它当成一个类（Class）来处理，而不是一个普通的字符串变量。
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam @ChatQuestion String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        // 先创建 SSE 通道，再交给 RAG 服务构建上下文并执行编排。
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     * 按任务 ID 请求停止一次 Workflow/RAG 流式问答。
     * taskId 标识一次运行，不是会话 ID；成功响应不表示异步收尾已全部完成。
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        // 用户身份校验及跨节点取消由任务管理器处理。
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
