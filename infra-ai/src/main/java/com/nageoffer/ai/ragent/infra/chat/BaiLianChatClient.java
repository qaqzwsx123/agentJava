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
public class BaiLianChatClient extends AbstractOpenAIStyleChatClient {
    // 百炼聊天适配器。复用父类请求流程，并允许父类添加 enable_thinking 思考开关。

    @Override
    public String provider() {
        // 返回百炼配置键，供路由层把候选模型关联到该客户端。
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    protected boolean supportsEnableThinkingParam() {
        // 声明本适配器允许发送 enable_thinking，父类会据请求显式开启或关闭思考。
        return true;
    }

    @Override
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 复用同步聊天模板，并由本类的思考参数钩子补充百炼请求差异。
        return doChat(request, target);
    }

    /**
     * 将百炼流式调用交给共享 HTTP 流实现，提供商差异通过客户端配置及请求构造处理。
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 复用父类的 SSE 读取和取消逻辑，提供商思考开关仍在请求构建时生效。
        return doStreamChat(request, callback, target);
    }
}
