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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.core.tool.mcp.McpMeta;
import io.agentscope.core.tool.mcp.McpTool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * 代理远程 MCP 工具调用，复用 AgentScope 的连接、Schema 与结果转换，并补充本项目的执行规则
 */
@Slf4j
public class McpToolProxy extends McpTool {

    private static final String CALL_FAILED_MESSAGE = "工具调用失败，请稍后重试";
    private final boolean needsConfirm;
    private final McpClientWrapper client;

    public McpToolProxy(McpToolBinding binding) {
        super(binding.toolId(), binding.description(), binding.inputSchema(),
                binding.remote().definition().outputSchema(), binding.remote().client(), null,
                binding.remote().client().getName(), binding.readOnly());
        this.needsConfirm = binding.needsConfirm();
        this.client = binding.remote().client();
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(needsConfirm
                ? PermissionDecision.ask("该操作执行前需要你确认")
                : PermissionDecision.allow("该工具无需执行前确认"));
    }

    //callAsync 作为 MCP 工具的统一异步执行入口，负责整个工具调用的生命周期管理。
    // 最多只产生一个结果（成功、失败或被取消），所以用 Mono（0 或 1 个）
    //当 Agent 决定调用这个 MCP 工具时，接收调用参数，通过已有 MCP 客户端执行远程工具，并把结果转换成 AgentScope 能使用的格式
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 尝试从参数中获取运行时上下文（可能为空，取决于调用来源）
        Optional<RuntimeContext> context = Optional.ofNullable(param.getRuntimeContext());

        // 2. 前置检查：判断当前工具是否被某个“技能（Skill）”遮蔽
        //    - 技能遮蔽：工具属于某个技能，但该技能的手册尚未加载到上下文中
        //    - 如果被遮蔽，不能直接调用，必须引导模型先调用 load_skill
        String maskedBy = context.map(this::maskedBySkill).orElse(null);
        if (maskedBy != null) {
            // 2.1 获取当前工具调用的 ID（用于记录事实和返回结果）
            String callId = Optional.ofNullable(param.getToolUseBlock())
                    .map(ToolUseBlock::getId)
                    .orElse(null);

            // 2.2 从上下文中取出事实记录器，标记这是一次“短路”（未执行即被拦截）
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(param.getRuntimeContext());
            if (facts != null) {
                facts.markShortCircuit(callId, AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED, maskedBy);
            }

            // 2.3 构造提示消息，引导模型先调用 load_skill 加载手册
            String message = "这个工具属于技能 %s，手册尚未加载。请先调用 load_skill 获取手册".formatted(maskedBy);

            // 2.4 直接返回一个 ERROR 状态的 ToolResultBlock，不执行真正的远程调用
            return Mono.just(ToolResultBlock.builder()
                    .id(callId)
                    .name(getName())
                    .output(TextBlock.builder().text(message).build())
                    .state(ToolResultState.ERROR)
                    .build());
        }

        // 3. 正常执行路径：使用 AgentToolBodyTracer 包装整个执行过程（用于链路追踪和埋点）
        return AgentToolBodyTracer.trace(this, param, () -> {
            // 3.1 从 RuntimeContext 中提取 MCP 元数据（McpMeta），如果不存在则返回空 Map
            Map<String, Object> meta = context.map(ctx -> ctx.get(McpMeta.class))
                    .map(McpMeta::entries)
                    .orElseGet(Map::of);

            // 3.2 使用 Mono.defer : 现在不执行 client.callTool(...)。 等到这个 Mono 被订阅时，才真正执行。每次订阅都可以重新执行 supplier。
            return Mono.defer(() -> client.callTool(getName(), param.getInput(), meta))
                    // 3.3 将 MCP 原生结果转换为统一的 ToolResultBlock
                    .map(McpContentConverter::convertCallToolResult)
                    // 3.4 状态修正：如果工具返回 RUNNING，说明只是中间态，统一转为 SUCCESS
                    .map(result -> result.getState() == ToolResultState.RUNNING
                            ? result.withState(ToolResultState.SUCCESS) : result)
                    // 3.5 异常兜底：区分“用户取消”与“真实异常”
                    .onErrorResume(e -> {
                        // 3.5.1 如果是用户主动取消（TaskCancellation），返回 INTERRUPTED 状态
                        if (TaskCancellation.isCancellation(e)) {
                            return Mono.just(ToolResultBlock.text("用户已停止，本次工具调用未完成")
                                    .withState(ToolResultState.INTERRUPTED));
                        }
                        // 3.5.2 如果是其他真实异常，记录错误日志，返回统一错误信息
                        log.error("MCP 工具调用异常, toolId={}", getName(), e);
                        return Mono.just(ToolResultBlock.error(CALL_FAILED_MESSAGE));
                    });
        });
    }

    private String maskedBySkill(RuntimeContext context) {
        Object masked = context.get(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE);
        return masked instanceof Map<?, ?> map && map.get(getName()) instanceof String skillCode
                ? skillCode : null;
    }
}
