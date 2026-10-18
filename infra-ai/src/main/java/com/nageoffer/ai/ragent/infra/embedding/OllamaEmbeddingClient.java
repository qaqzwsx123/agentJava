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

package com.nageoffer.ai.ragent.infra.embedding;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {
    // Ollama 向量适配器。复用兼容协议请求模板，不要求 API Key，并省略父类默认添加的 encoding_format 字段。

    public OllamaEmbeddingClient(OkHttpClient syncHttpClient) {
        // 复用共享同步客户端调用本地兼容端点，协议解析仍交给父类。
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        // 返回 Ollama 配置 ID，以匹配本地向量模型候选。
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    protected boolean requiresApiKey() {
        // 本地向量接口无需密钥，关闭父类的必填校验和 Authorization 头。
        return false;
    }

    @Override
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // Ollama 不需要 encoding_format 字段
    }
}
