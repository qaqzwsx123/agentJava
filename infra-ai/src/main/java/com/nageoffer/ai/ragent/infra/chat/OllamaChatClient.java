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
public class OllamaChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        // 返回本地 Ollama 的配置 ID，模型名和服务地址仍从本次目标中读取。
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    protected boolean requiresApiKey() {
        // 关闭 API Key 必填检查与 Bearer 请求头添加，供本地无鉴权的 Ollama 端点使用。
        return false;
    }

    @Override
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 通过 OpenAI 兼容模板调用本地模型，其余响应校验和错误转换与远程供应商共用。
        return doChat(request, target);
    }

    /**
     * 委托共享 HTTP 流方法执行 Ollama 请求并返回取消句柄。
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 复用通用 HTTP/SSE 流实现，无需为本地模型另写线程池或取消逻辑。
        return doStreamChat(request, callback, target);
    }
}
