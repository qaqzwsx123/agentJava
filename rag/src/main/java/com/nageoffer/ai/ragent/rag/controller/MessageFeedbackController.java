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

import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话消息反馈控制器
 */
@RestController
@RequiredArgsConstructor
public class MessageFeedbackController {
    // 消息反馈 HTTP 入口：接收用户对指定消息的点赞点踩或撤销，交给反馈服务校验并异步处理。

    private final MessageFeedbackService feedbackService;

    /**
     * 提交点赞/踩反馈（异步，通过 MQ 持久化）
     * 提交用户对某条回答的评价，异步保存反馈。
     * messageId 指定回答，请求体提供赞踩或反馈内容；成功表示反馈事件已发送。
     */
    @PostMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        // 反馈服务发送消息队列事件，数据库状态由消费者随后更新。
        feedbackService.submitFeedbackAsync(messageId, request);
        return Results.success();
    }

    /**
     * 取消点赞/踩反馈（异步，通过 MQ 持久化）
     * 撤销当前用户对指定回答的评价。
     * messageId 是回答消息 ID；接口只提交撤销事件，不等待消费落库。
     */
    @DeleteMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> cancelFeedback(@PathVariable String messageId) {
        // 撤销与提交反馈共用异步消息链路。
        feedbackService.cancelFeedbackAsync(messageId);
        return Results.success();
    }
}
