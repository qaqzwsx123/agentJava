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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer.TrimResult;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 记忆接线点：推理前裁剪/压缩上下文并同步上行列表
 * <p>
 * 两层按水位分工：50% 裁工具结果，80% 压缩摘要；实例被单例 Agent 共享，不持有 per-call 字段
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextCompactionMiddleware implements MiddlewareBase {

    private final AgentContextTrimmer trimmer;
    private final AgentContextCompactor compactor;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 在每次模型推理前接入上下文治理，订阅事件流时才执行裁剪或压缩。
     * 使用本次调用的运行上下文取得状态，不在共享中间件实例中缓存会话数据。
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(agent, runtimeContext, input, next));
    }

    /**
     * 读取当前会话的可变上下文，按摘要开关、字符水位和消息引用关系选择处理方式。
     * 状态不可用时透传原输入；不满足压缩条件时尝试轻量的工具结果裁剪。
     */
    private Flux<AgentEvent> dispatch(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> context;
        try {
            AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
            context = state == null ? null : state.contextMutable();
        } catch (Exception e) {
            log.warn("会话状态取不到, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return next.apply(input);
        }
        if (context == null) {
            return next.apply(input);
        }
        // 未到摘要水位仍可尝试较低水位的工具结果裁剪。
        if (!memoryProperties.isSummaryEnabled() || !shouldCompact(context)) {
            return next.apply(trim(context, input, runtimeContext));
        }
        // 先验上行列表与 context 的引用关系，再动手压缩
        List<Msg> prefix = resolvePrefix(input.messages(), context);
        if (prefix == null) {
            log.warn("上行列表与上下文对不上, 本轮不压缩, 上行: {}, 上下文: {}", input.messages().size(), context.size());
            return next.apply(trim(context, input, runtimeContext));
        }
        return compact(context, input, prefix, runtimeContext).flatMapMany(next::apply);
    }

    /**
     * 末条是用户消息才压缩，保证工具循环已闭合
     * <p>
     * 只有最后一条为用户消息且字符数超过压缩水位时才返回 true，避免在工具执行中途压缩。
     */
    private boolean shouldCompact(List<Msg> context) {
        if (context.isEmpty() || context.get(context.size() - 1).getRole() != MsgRole.USER) {
            return false;
        }
        return AgentContextChars.total(context) > memoryProperties.resolveCompactTriggerChars();
    }

    /**
     * 压缩含同步模型调用，切到 boundedElastic 避免占推理线程
     * <p>
     * 异步调度同步摘要调用，成功后保留框架前缀并重建输入；未压缩或出现异常时退回工具结果裁剪。
     */
    private Mono<ReasoningInput> compact(List<Msg> context, ReasoningInput input, List<Msg> prefix,
                                         RuntimeContext runtimeContext) {
        return Mono.fromCallable(() -> compactor.compactInPlace(context, userId(runtimeContext), sessionId(runtimeContext))
                        ? rebuild(input, context, prefix)
                        : trim(context, input, runtimeContext))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("上下文压缩异常, 本轮退回工具结果裁剪, sessionId: {}", sessionId(runtimeContext), e);
                    return Mono.fromCallable(() -> trim(context, input, runtimeContext));
                });
    }

    /**
     * 裁剪失败走原列表
     * <p>
     * 将裁剪生成的旧消息到新消息映射同步到本次推理输入；没有变化时复用原输入。
     */
    private ReasoningInput trim(List<Msg> context, ReasoningInput input, RuntimeContext runtimeContext) {
        try {
            TrimResult result = trimmer.trimInPlace(context);
            if (!result.changed()) {
                return input;
            }
            // 上下文已替换消息对象，上行副本也必须换成同一批新对象。
            List<Msg> messages = input.messages().stream()
                    .map(msg -> result.replacements().getOrDefault(msg, msg))
                    .toList();
            return new ReasoningInput(messages, input.tools(), input.options());
        } catch (Exception e) {
            log.warn("上下文裁剪异常, 本轮按原列表推理, sessionId: {}", sessionId(runtimeContext), e);
            return input;
        }
    }

    /**
     * 按引用逐条比对，取出上行列表头部的框架前缀；失配返回 null
     * <p>
     * 把上行消息视为“框架前缀 + 会话上下文”，逐项按对象引用验证后返回前缀副本。
     * 长度不足或任一引用不同都返回 null，避免压缩后拼接错误的消息列表。
     */
    private List<Msg> resolvePrefix(List<Msg> messages, List<Msg> context) {
        int offset = messages.size() - context.size();
        if (offset < 0) {
            return null;
        }
        for (int i = 0; i < context.size(); i++) {
            if (messages.get(offset + i) != context.get(i)) {
                return null;
            }
        }
        return List.copyOf(messages.subList(0, offset));
    }

    /**
     * 压缩改了消息条数，需整段重建上行列表
     * <p>
     * 将框架前缀与压缩后的上下文重新拼接，同时沿用原输入的工具定义和模型选项。
     */
    private ReasoningInput rebuild(ReasoningInput input, List<Msg> context, List<Msg> prefix) {
        List<Msg> rebuilt = new ArrayList<>(prefix.size() + context.size());
        rebuilt.addAll(prefix);
        rebuilt.addAll(context);
        return new ReasoningInput(rebuilt, input.tools(), input.options());
    }

    /**
     * 从本次运行上下文读取会话 ID；上下文缺失时返回 null，供日志和压缩审计使用。
     */
    private String sessionId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    /**
     * 从本次运行上下文读取用户 ID；上下文缺失时返回 null，供压缩审计记录归属。
     */
    private String userId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getUserId();
    }
}
