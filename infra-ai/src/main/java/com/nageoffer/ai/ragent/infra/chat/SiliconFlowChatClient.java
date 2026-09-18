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
public class SiliconFlowChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        // 返回硅基流动的配置 ID，供模型路由选择该供应商适配器。
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    protected boolean supportsEnableThinkingParam() {
        // 允许父类写入 enable_thinking，并以请求的 thinking 标志决定具体布尔值。
        return true;
    }

    @Override
    @RagTraceNode(name = "siliconflow-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 同步聊天交给父类模板，保留本适配器声明的思考参数差异。
        return doChat(request, target);
    }

    /**
     * 委托共享 HTTP 流方法执行 SiliconFlow 请求并返回取消句柄。
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 复用父类 SSE 读取实现，返回句柄供首包失败切换或用户停止时取消上游。
        return doStreamChat(request, callback, target);
    }
}
