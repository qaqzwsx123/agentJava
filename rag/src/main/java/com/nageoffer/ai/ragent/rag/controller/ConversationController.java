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

import com.nageoffer.ai.ragent.rag.controller.request.ConversationUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationVO;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话控制器
 * 提供会话相关的REST API接口，包括会话列表获取、重命名、删除以及会话消息列表获取等功能
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {
    // 会话控制器 提供会话相关的REST API接口，包括会话列表获取、重命名、删除以及会话消息列表获取等功能。

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    /**
     * 获取会话列表
     * 查询当前用户的 Workflow/RAG 会话列表。
     * 该列表与 Agent 引擎会话分开维护，返回标题和最近活跃信息。
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> listConversations() {
        // 使用登录上下文限定会话所属用户。
        return Results.success(conversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 重命名会话
     * 修改 Workflow/RAG 会话标题。
     * conversationId 指定会话，请求体提供新标题。
     */
    @PutMapping("/conversations/{conversationId}")
    public Result<Void> rename(@PathVariable String conversationId,
                               @RequestBody ConversationUpdateRequest request) {
        // 具体会话状态和修改规则由会话服务负责。
        conversationService.rename(conversationId, request);
        return Results.success();
    }

    /**
     * 删除会话
     * 删除 Workflow/RAG 会话及关联历史数据。
     * conversationId 是待删除会话 ID，具体关联清理由会话服务完成。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        // 删除会话数据与停止某一次流式运行是不同操作。
        conversationService.delete(conversationId);
        return Results.success();
    }

    /**
     * 获取会话消息列表
     * 读取当前用户在指定 Workflow/RAG 会话中的历史消息。
     * conversationId 限定会话，消息按升序返回；这里不限制查询条数。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(@PathVariable String conversationId) {
        // 同时传入 userId，避免混入其他用户的聊天记录。
        return Results.success(conversationMessageService.listMessages(conversationId, UserContext.getUserId(), null, ConversationMessageOrder.ASC));
    }
}
