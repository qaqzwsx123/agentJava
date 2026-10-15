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
import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceEnrichmentMiddleware;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 主 Agent 供给器：单例复用，AGENT_MAIN 人设或工具目录变化时懒重建
 * 会话状态在 PgAgentStateStore 中按次加载，重建不丢历史
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class ReActAgentProvider {

    private static final String AGENT_NAME = "RagentAI";
    private volatile CachedAgent cached;

    private final AgentPromptResolver agentPromptResolver;
    private final AgentToolCatalog toolCatalog;
    private final OpenAIChatModel agentChatModel;
    private final PgAgentStateStore agentStateStore;
    private final AgentProperties agentProperties;
    private final AgentUserMemoryMiddleware userMemoryMiddleware;
    private final AgentContextCompactionMiddleware contextCompactionMiddleware;
    private final AgentConfirmDenialMiddleware confirmDenialMiddleware;
    private final AgentSkillMaskingMiddleware skillMaskingMiddleware;
    private final AgentToolBatchMiddleware toolBatchMiddleware;
    private final ObjectProvider<OtelTracingMiddleware> otelTracingMiddleware;
    private final ObjectProvider<AgentTraceEnrichmentMiddleware> traceEnrichmentMiddleware;

    /**
     * 以人设内容和工具目录签名判断重建时机：控制台修改后无需重启，下一次会话生效
     */
    public ActiveAgent getAgent() {
        // 解析全部人设提示词（可能来自配置/控制台，支持运行时变更）
        Map<String, String> prompts = agentPromptResolver.resolveAll();
        // 解析出当前生效的人设内容
        String persona = resolvePersona(prompts);
        // 解析工具目录，得到其签名（用于判断工具配置是否变化）
        ResolvedCatalog catalog = toolCatalog.resolve(prompts);
        // 先读一次缓存（无锁快路径）
        CachedAgent current = cached;
        // 签名一致 → 直接复用缓存的 Agent，无需重建、无需加锁
        if (matches(current, persona, catalog)) {
            return current.activeAgent();
        }
        // 签名不一致，进入加锁路径，避免并发重复重建
        synchronized (this) {
            // 双重检查：可能已被其他线程重建，重新读缓存
            current = cached;
            // 二次校验签名，命中则直接返回，避免重复构建
            if (matches(current, persona, catalog)) {
                return current.activeAgent();
            }

            // 旧实例不主动 close：在途会话仍在其上流式输出，交由 GC 回收
            // 按当前人设与工具目录重建 Agent
            ReActAgent agent = buildAgent(persona, catalog);
            // 包装成 ActiveAgent（附带解析后的工具目录）
            ActiveAgent activeAgent = new ActiveAgent(agent, catalog);

            // 刷新缓存，记录本次生效的签名与新实例
            cached = new CachedAgent(persona, activeAgent);
            // 返回新建的 Agent
            return activeAgent;
        }
    }

    /**
     * 驱逐单个会话的内存状态：只作用于已构建的实例，清理动作不该顺手把 Agent 建起来
     * 仅本节点有效，多节点各持一份缓存，需要时经 Redis 广播补齐
     */
    @SuppressWarnings("resource")
    public void evictStateCache(String userId, String sessionId) {
        // 先读一次当前缓存的 Agent（无锁快路径）
        CachedAgent current = cached;
        // 缓存为空说明还没构建过 Agent，无需清理，直接返回
        if (current == null) {
            return;
        }
        // 转调底层 ReActAgent 清掉该用户该会话的状态缓存
        //取出 CachedAgent里面的ReactAgent 再取出里面的 ReactAgent来调用 自带的clearStateCache
        current.activeAgent().agent().clearStateCache(userId, sessionId);
    }

    /**
     * 比较缓存 Agent 的人设和工具目录指纹；任一配置变化都需要在下一次获取时重建实例。
     */
    private boolean matches(CachedAgent current, String persona, ResolvedCatalog catalog) {
        return current != null
                && current.persona().equals(persona)
                && current.activeAgent().catalog().fingerprint().equals(catalog.fingerprint());
    }

    /*创建一个智能体，指定它的职责、模型、可调用的工具、运行限制和状态存储方式。*/
    private ReActAgent buildAgent(String persona, ResolvedCatalog catalog) {
        // 用建造者装配 ReActAgent 的基础能力
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(AGENT_NAME)                                  // Agent 名称
                .sysPrompt(persona)                                // 系统人设提示词（控制台可变，重建时更新）
                .model(agentChatModel)                             // 底层大模型（OpenAI 兼容）
                .toolkit(toolCatalog.buildToolkit(catalog))        // 按目录快照构建可用工具集
                .maxIters(agentProperties.getMaxIters())           // ReAct 循环上限，超出熔断收尾
                .maxRetries(agentProperties.getMaxRetries())       // 单次模型调用最大尝试次数
                .stateStore(agentStateStore);                      // 状态存储（PG），保证重建不丢历史
        // 追踪包在最外层：span 要盖住记忆与压缩才量得到耗时
        otelTracingMiddleware.ifAvailable(builder::middleware); //给 Agent 运行打追踪 span，接入 OpenTelemetry 体系，把每一步（模型调用、工具调用、记忆、压缩等）的耗时/链路记录下来，用于可观测性（接 Jaeger、Zipkin、SkyWalking 等）。
        traceEnrichmentMiddleware.ifAvailable(builder::middleware); //在追踪基础上补充业务信息（比如把 userId、conversationId、taskId 等塞进 span），让追踪数据更能反映业务上下文。
        // 按顺序挂载业务中间件：洋葱模型，外层先执行，顺序影响行为
        return builder
                // 外层先执行：记忆块插在人设与会话之间，压缩的 offset 比对自然吸收它
                .middleware(userMemoryMiddleware)
                .middleware(contextCompactionMiddleware)
                // 压缩之后：被压进摘要的拒绝结果已不在列表里，改写自然跳过
                .middleware(confirmDenialMiddleware)
                // 手册被压缩带走后遮蔽跟着复位，工具与手册同进同出
                .middleware(skillMaskingMiddleware)
                // 最内层：批起止贴着真实执行，不随追踪开关变形
                .middleware(toolBatchMiddleware)
                // 完成构建
                .build();
    }

    /**
     * 读取 Agent 主提示词作为人设；配置为空直接报错，避免创建缺少业务约束的 Agent。
     */
    private String resolvePersona(Map<String, String> prompts) {
        String persona = prompts.get(AgentPromptSlot.AGENT_MAIN.name());
        if (StrUtil.isBlank(persona)) {
            throw new IllegalStateException("Agent人设内容不允许为空");
        }
        return persona;
    }

    /**
     * 本轮取到的实例与它构建时用的目录快照，成对交出免得调用方各自再取一遍
     */
    public record ActiveAgent(ReActAgent agent, ResolvedCatalog catalog) {
    }

    /**
     * Provider 内部的缓存条目：persona 用于判断缓存是否过期，ActiveAgent 是对外使用的快照
     */
    private record CachedAgent(String persona, ActiveAgent activeAgent) {
    }
}
