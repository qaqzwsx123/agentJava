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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AIHubMixChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        // 返回与 ai.providers 配置一致的提供商 ID，路由器以它索引本客户端。
        return ModelProvider.AI_HUB_MIX.getId();
    }

    @Override
    @RagTraceNode(name = "aihubmix-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // AIHubMix 的同步聊天复用兼容协议模板，目标模型、超时和鉴权由传入目标与父类处理。
        return doChat(request, target);
    }

    /**
     * 将 AIHubMix 流式调用交给共享 HTTP 流实现，返回用于主动停止的取消句柄。
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 委托父类提交流式读取任务，并把可取消 HTTP 上游的句柄交还路由层。
        return doStreamChat(request, callback, target);
    }
}
