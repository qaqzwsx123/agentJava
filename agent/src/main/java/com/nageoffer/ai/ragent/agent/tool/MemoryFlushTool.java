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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * 记忆整理工具：模型只有触发权没有内容写入权，记什么忘什么由服务端仲裁
 * 身份取自 RuntimeContext 而非入参，模型改不了自己是谁
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryFlushTool implements AgentTool {

    public static final String TOOL_NAME = "flush_memory";
    public static final String DISPLAY_NAME = "记忆整理";

    private final String description;
    private final AgentMemoryPipeline memoryPipeline;

    @Override
    public String getName() {
        // 返回固定工具名 flush_memory，模型通过它触发整理，具体内容由服务端仲裁决定。
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        // 返回目录中的记忆整理描述，用于提示模型何时调用该工具。
        return description;
    }

    /**
     * 无参：给了参数就等于把内容写入权交出去，模型能凭空捏造要记的事实
     */
    @Override
    public Map<String, Object> getParameters() {
        // 工具不接收模型提供的记忆正文；空属性表并禁止额外字段，避免把内容决定权交给调用参数。
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 用同一套工具体追踪记录执行事实，订阅时再在弹性线程池运行包含 JDBC 和模型调用的整理流程。
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        // 1. 检查工具调用参数，缺失时直接返回错误，不进入记忆抽取流程。
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        // 2. 调用 ID 用于结果配对；用户与会话身份只从服务端 RuntimeContext 取，模型不能指定。
        String toolCallId = Optional.ofNullable(param.getToolUseBlock())
                .map(ToolUseBlock::getId)
                .orElse(null);
        RuntimeContext runtimeContext = param.getRuntimeContext();
        String userId = Optional.ofNullable(runtimeContext)
                .map(RuntimeContext::getUserId)
                .orElse(null);
        String conversationId = Optional.ofNullable(runtimeContext)
                .map(RuntimeContext::getSessionId)
                .orElse(null);
        // 3. 身份不完整时拒绝整理，避免把记忆写到错误用户或会话。
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(conversationId)) {
            log.warn("记忆整理工具拿不到会话身份, 本次不整理");
            return buildResult(toolCallId, "当前会话无法整理记忆，本次内容未能写入", true);
        }
        try {
            // 4. 主动 FLUSH 绕过后台消息数门槛，但仍执行相同的仲裁、容量与事务校验。
            AgentMemoryOutcome outcome = memoryPipeline.extract(userId, conversationId, AgentMemoryTriggerType.FLUSH);
            // 刷新认「记忆集变没变」不认「落了几条决策」：合并落库而决策全灭时 applied 为零、库已经变了
            if (outcome.mutated()) {
                refreshSnapshot(runtimeContext, userId);
            }
            log.info("记忆整理工具调用完成, userId: {}, conversationId: {}, 结局: {}, 落库: {}",
                    userId, conversationId, outcome.status(), outcome.applied());
            return render(toolCallId, outcome);
        // 5. 兜底把未处理异常转成工具错误结果，不将异常直接抛回模型执行链。
        } catch (Exception e) {
            log.error("记忆整理工具调用异常, userId: {}, conversationId: {}", userId, conversationId, e);
            return buildResult(toolCallId, "记忆整理异常，本次内容未能写入", true);
        }
    }

    /**
     * 落库已成事实，刷新失败只让本轮后续推理看到旧视图，不该把成功报成失败
     */
    private void refreshSnapshot(RuntimeContext runtimeContext, String userId) {
        // 刷新的是本次运行上下文中的记忆视图，使后续推理可读到更新内容；异常只记录，不撤销已完成的提交。
        try {
            runtimeContext.put(AgentUserMemoryMiddleware.SNAPSHOT_KEY, memoryPipeline.reloadSnapshot(userId));
        } catch (Exception e) {
            log.warn("长期记忆快照刷新失败, 本轮沿用旧视图, userId: {}", userId, e);
        }
    }

    /**
     * 没写进去的一律 ERROR，不留 default 确保新增枚举值编译期报错
     */
    private ToolResultBlock render(String toolCallId, AgentMemoryOutcome outcome) {
        // 按管道结局生成可供模型理解的文本和状态；已处理但没有新增内容也属于正常完成。
        return switch (outcome.status()) {
            case WRITTEN -> buildResult(toolCallId, "记忆已更新，本次生效 " + outcome.applied() + " 条", false);
            case SETTLED_EMPTY -> buildResult(toolCallId, outcome.mutated()
                    ? "本批对话没有需要新记住的内容，已顺手合并精简了既有记忆"
                    : "本批对话已整理完，其中没有需要长期记住的内容", false);
            case NOTHING_PENDING -> buildResult(toolCallId, "最近的对话都已整理过，这次没有新内容需要处理", false);
            // 门槛只挡后台批，flush 走到这里说明管道分叉错了
            case BELOW_THRESHOLD -> throw new IllegalStateException("记忆整理工具收到只属于后台抽取的门槛结局");
            case BUSY -> buildResult(toolCallId, "记忆整理进行中，本次未能处理，请稍后再试", true);
            case CAPACITY_REJECTED -> buildResult(toolCallId, "记忆容量已达上限，本次内容未能写入", true);
            case DISABLED -> buildResult(toolCallId, "记忆功能当前未开启，本次内容不会被记住", true);
            // 快照过期是良性的：水位没推，同一区间下一轮 turn release 会重新抽一次
            case CONFLICT -> buildResult(toolCallId, "记忆刚被另一次整理改动，本次未写入，下次对话时会再试一次", true);
            case FAILED -> buildResult(toolCallId, "记忆整理失败，本次内容未能写入", true);
        };
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        // 统一设置调用 ID、工具名、文本和成功/失败状态，便于框架关联到原始工具调用。
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
