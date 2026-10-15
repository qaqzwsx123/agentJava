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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.request.ConfirmRequest;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话入口，仅 ragent.engine.type=agent 时注册；RAG v3 接口不受影响
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentProperties agentProperties;
    //produces = "text/event-stream;charset=UTF-8" 就是把这个接口标记成 SSE 流式接口，
    // 返回内容是 UTF-8 编码的事件流，供前端用 EventSource 实时接收 AI 逐字输出。
    @GetMapping(value = "/agent/v1/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam @ChatQuestion String question,
                           @RequestParam(required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        agentChatService.streamChat(question, conversationId, emitter);
        return emitter;
    }

    //用户在 Agent 执行过程中收到「工具调用确认」请求后，通过这个接口确认或拒绝（approved = true/false）Agent 继续执行某个操作
    /**
     * 处理用户对待确认工具调用的批准或拒绝，并通过新的 SSE 连接继续返回 Agent 执行结果。
     * 例如 Agent 准备提交申请时先展示确认卡片，用户点击“同意”或“拒绝”后调用此接口。
     * approved=true 时继续执行待确认工具；false 时跳过该操作，让模型带着拒绝结果继续作答。
     */
    @PostMapping(value = "/agent/v1/chat/confirm", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter confirm(@RequestBody ConfirmRequest requestParam) {
        // 为确认后的续跑创建 SSE 通道，超时时间由 Agent 配置指定（单位：毫秒）。
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        // conversationId 定位会话，messageId 定位待确认消息，approved 表示同意或拒绝。
        // 服务层校验当前用户与待确认状态，并从服务端 Agent 状态读取原始工具及入参，
        // 再把确认结果交给 Agent 续跑，避免前端在确认时修改待执行的工具参数。
        agentChatService.confirmPendingTool(requestParam.conversationId(), requestParam.messageId(),
                requestParam.approved(), emitter);
        // 返回流式通道，后续文本、工具事件等通过该连接推送给前端。
        return emitter;
    }

    // 按 taskId 中止正在运行的 Agent 任务（比如用户点了"停止生成"按钮）
    /**
     * 用户点击“停止生成”时，根据 taskId 请求取消本轮正在执行的 Agent 任务。
     * taskId 来自本轮 SSE 的 meta 事件，标识一次运行，与会话 ID、消息 ID 含义不同。
     */
    @PostMapping("/agent/v1/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        // 服务层委托任务管理器校验任务属主，并通过 Redis 取消标记和 Pub/Sub
        // 通知任务所在节点停止上游执行，由运行收尾逻辑保存已有输出并清理资源。
        agentChatService.stopTask(taskId);
        // 返回普通 JSON 成功响应，表示停止请求已提交；不返回 SSE，也不等待任务收尾完成。
        return Results.success();
    }
}
