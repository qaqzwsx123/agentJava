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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 会话最小 CRUD，与 workflow 会话接口两套分立
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentConversationController {

    private final AgentConversationService agentConversationService;

    /**
     * 查询当前登录用户的 Agent 会话列表，供前端会话侧栏展示。
     * 无需前端传用户 ID，返回会话标题、最后活跃时间及提问轮次。
     */
    @GetMapping("/agent/v1/conversations")
    public Result<List<AgentConversationVO>> listConversations() {
        // 使用登录上下文中的用户 ID 限定会话查询范围。
        return Results.success(agentConversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 查询指定 Agent 会话的历史消息，供前端恢复聊天记录。
     * conversationId 标识会话；消息正文、思考内容和工具块一并返回。
     */
    @GetMapping("/agent/v1/conversations/{conversationId}/messages")
    public Result<List<AgentMessageVO>> listMessages(@PathVariable String conversationId) {
        // 同时传入当前用户 ID，服务层按会话和用户过滤消息。
        return Results.success(agentConversationService.listMessages(conversationId, UserContext.getUserId()));
    }

    /**
     * 修改当前用户的 Agent 会话标题。
     * 路径参数定位会话，请求体 title 提供新标题；空标题由服务层拒绝。
     */
    @PutMapping("/agent/v1/conversations/{conversationId}/title")
    public Result<Void> rename(@PathVariable String conversationId, @RequestBody TitleRequest request) {
        // @RequestBody是拿到前端http里面的请求体：然后传给实体 TitleRequest
        // PUT /agent/v1/conversations/conv-123/title
        //Content-Type: application/json
        //{
          //  "title": "新的会话标题"
        //}
        // 空请求体转换为 null 标题，交由业务层统一校验。
        agentConversationService.rename(conversationId, UserContext.getUserId(),
                request == null ? null : request.title());
        return Results.success();
    }

    /**
     * 删除当前用户的一个 Agent 会话及其消息和运行状态。
     * conversationId 标识待删除会话；仍在生成内容的会话需要先停止任务。
     */
    @DeleteMapping("/agent/v1/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        // 服务层在事务中清理会话数据，并在提交后驱逐本地状态缓存。
        agentConversationService.delete(conversationId, UserContext.getUserId());
        return Results.success();
    }

    /**
     * 批量删除当前用户选择的 Agent 会话。
     * 请求体 ids 是会话 ID 集合；空请求按空列表处理，服务层负责去重。
     */
    @PostMapping("/agent/v1/conversations/batch-delete")
    public Result<Void> batchDelete(@RequestBody BatchDeleteRequest request) {
        //接收前端的json请求体的时候，将json自动转换为BatchDeleteRequest对象
        // 将全部删除操作交给同一个业务事务处理。 request不为空，则将List<String>类型的request.ids()拿过来
        agentConversationService.deleteBatch(request == null ? List.of() : request.ids(), UserContext.getUserId());
        return Results.success();
    }

    public record TitleRequest(String title) {
    }

    public record BatchDeleteRequest(List<String> ids) {
    }
}
