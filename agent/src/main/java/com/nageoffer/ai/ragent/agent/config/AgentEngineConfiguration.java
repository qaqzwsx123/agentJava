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

package com.nageoffer.ai.ragent.agent.config;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Agent 引擎装配：模型与状态存储
 */
@Configuration
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentEngineConfiguration {

    private final AgentProperties agentProperties;
    private final AIModelProperties aiModelProperties;

    @Bean
    public OpenAIChatModel agentChatModel() {
        // 取 Agent 的模型配置（provider + model）
        AgentProperties.Chat chat = agentProperties.getChat();
        // 校验 agent.chat 配置齐全：provider 和 model 都不能为空
        if (chat == null || StrUtil.isBlank(chat.getProvider()) || StrUtil.isBlank(chat.getModel())) {
            throw new IllegalStateException("agent.chat.provider / agent.chat.model 未配置");
        }

        // 取出 ai.providers 下所有供应商配置
        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        // 按 agent.chat.provider 指定的 key 找对应供应商
        AIModelProperties.ProviderConfig provider = providers == null ? null : providers.get(chat.getProvider());
        // 找不到供应商 → 配置错误，直接拒绝启动
        if (provider == null) {
            throw new IllegalStateException("agent.chat.provider 在 ai.providers 中不存在: " + chat.getProvider());
        }
        // 取该供应商的 chat 端点路径（endpoints.chat）
        String endpointPath = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        // 校验供应商的 url 和 chat 端点路径齐全
        if (StrUtil.isBlank(provider.getUrl()) || StrUtil.isBlank(endpointPath)) {
            throw new IllegalStateException("供应商缺少 url 或 endpoints.chat: " + chat.getProvider());
        }

        /*把配置中的模型地址、接口路径、模型名称交给 AgentScope，供主 Agent 每一轮推理使用。*/
        // 用建造者装配 OpenAI 兼容模型
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .baseUrl(provider.getUrl())          // 供应商基础 URL
                .endpointPath(endpointPath)          // chat 端点路径
                .apiKey(provider.getApiKey())        // API Key
                .modelName(chat.getModel())          // 模型名（直接传给 OpenAI 兼容端点）
                .stream(true)                        // 开启流式输出（SSE 逐段返回）
                // 兼容端点普遍无法同时处理 response_format 与工具调用，统一走 generate_response 兜底
                .nativeStructuredOutputWithTools(false);
        // 应用供应商特有的请求/响应格式适配（不同厂商字段差异）
        applyProviderFormatter(builder, chat.getProvider());
        // 构建并返回模型实例
        return builder.build();
    }
    /**
     * 按供应商挂官方 formatter：DeepSeek 工具定义不认 strict，且思考模式下 reasoning_content
     * 需按「本轮 + 含工具调用的历史段」保留，通用 formatter 两条都不做
     */
    private void applyProviderFormatter(OpenAIChatModel.Builder builder, String providerId) {
        if (ModelProvider.DEEP_SEEK.matches(providerId)) {
            builder.formatter(new DeepSeekFormatter());
        }
    }

    @Bean
    public PgAgentStateStore agentStateStore(AgentStateMapper agentStateMapper) {
        return new PgAgentStateStore(agentStateMapper);
    }
}
