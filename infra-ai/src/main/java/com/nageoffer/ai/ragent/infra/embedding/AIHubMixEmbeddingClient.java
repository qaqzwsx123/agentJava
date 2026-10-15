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
public class AIHubMixEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public AIHubMixEmbeddingClient(OkHttpClient syncHttpClient) {
        // 将共享同步客户端交给父类，批量协议、鉴权和响应解析全部沿用模板。
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        // 返回 AIHubMix 的配置 ID，供向量路由定位本客户端。
        return ModelProvider.AI_HUB_MIX.getId();
    }

    @Override
    protected int maxBatchSize() {
        // 将本适配器每次请求限制为 32 条，超出时父类按顺序拆批并合并结果。
        return 32;
    }
}
