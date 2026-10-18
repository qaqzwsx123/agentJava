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

import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

@Service
public class SiliconFlowEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {
    // 硅基流动向量适配器。复用公共向量请求模板，当前代码设置单批最多 32 条文本。

    public SiliconFlowEmbeddingClient(OkHttpClient syncHttpClient) {
        // 把共享同步客户端传给向量协议模板，保留连接池与超时等公共配置。
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        // 返回硅基流动配置 ID，路由层按它查找适配器。
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    protected int maxBatchSize() {
        // 限制单次向量请求为 32 条，输入更多时由父类依次切片请求。
        return 32;
    }
}
