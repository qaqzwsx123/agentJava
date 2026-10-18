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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 官方开放平台 ChatClient
 * 思考方言是 thinking 对象而非 DashScope 系的 enable_thinking 布尔
 */
@Slf4j
@Service
public class DeepSeekChatClient extends AbstractOpenAIStyleChatClient {
    // DeepSeek 聊天适配器。使用 thinking.type 表达思考开关，覆盖父类的参数扩展方法，同步和流式通信仍复用公共模板。

    @Override
    public String provider() {
        // 返回 DeepSeek 的配置 ID，供客户端注册表和模型候选匹配。
        return ModelProvider.DEEP_SEEK.getId();
    }

    /**
     * V4 系思考模式默认开启且默认 effort=high，不显式关会让 standard 档白等一段思考
     * <p>
     * 这里不调用父类实现：DeepSeek 不认识 enable_thinking，发过去会被判为未知参数
     */
    @Override
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        // 使用 thinking.type 表达开关，不调用会生成 enable_thinking 的父类实现，避免混用协议方言。
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", Boolean.TRUE.equals(request.getThinking()) ? "enabled" : "disabled");
        body.add("thinking", thinking);
    }

    @Override
    @RagTraceNode(name = "deepseek-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 同步请求仍走通用模板，DeepSeek 专有思考字段由本类扩展方法负责。
        return doChat(request, target);
    }

    /**
     * 委托共享 HTTP 流方法执行 DeepSeek 请求并返回取消句柄。
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 将 SSE 连接、增量解析和主动停止交给通用实现，返回相同的取消句柄接口。
        return doStreamChat(request, callback, target);
    }
}
