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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolBatchFact;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 工具批边界：一次 acting 就是一批，批的开合只发生在这里
 * <p>
 * 它不建任何 span，也不受追踪开关影响——归批是业务事实，不能因为关掉可观测性就变了形状
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
public class AgentToolBatchMiddleware implements MiddlewareBase {

    /**
     * 排在最内层，批的起止尽量贴着真实执行
     */
    @Override
    public int order() {
        // 返回默认顺序值；在 Agent 中紧贴工具执行层挂载，尽量让批次计时贴近实际 acting。
        return 0;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext runtimeContext, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // 1. 取出本次运行共享的执行事实源；缺失时只执行原链路，不额外创建批次。
        AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(runtimeContext);
        if (facts == null) {
            return next.apply(input);
        }
        // 2. 原子认领当前批次；如果上一批尚未结束，本批透传执行，避免两批成员混在一起。
        ToolBatchFact batch = facts.beginBatch();
        if (batch == null) {
            // 上一批没收口，此时开新批会让两批的成员混在一起，宁可这一批不归批
            log.warn("上一批工具尚未收口，本批不再归批, sessionId: {}", runtimeContext.getSessionId());
            return next.apply(input);
        }
        // 3. 执行前固定本批成员；后续并行完成顺序不会改变登记顺序。
        facts.enroll(batch, toolCallIds(input));
        // 成功、异常、取消三条路都要收口，否则批永远停在进行中
        return next.apply(input)
                .doOnNext(event -> markPreExecutionShortCircuit(facts, event))
                .doFinally(signal -> facts.endBatch(batch));
    }

    /**
     * 有结果事件却没有工具体记录，说明框架在进入工具体之前就否了这次调用
     * <p>
     * 事件只有 replyId/toolCallId/toolCallName/state 四个字段，未注册、工具组停用、入参校验失败在这一层完全同形，
     * 细分成三个取值只能靠读错误文案，那既撑爆基数也把业务字段漏进属性空间，所以统一停在一个钝的通用原因上
     * <p>
     * 判定放在最内层且不受追踪开关影响：它是执行事实，SSE 与落库那头同样要凭它把工具显示成「未执行」
     */
    private static void markPreExecutionShortCircuit(AgentToolExecutionFacts facts, AgentEvent event) {
        // 1. 只处理工具结果结束事件，其他事件无法证明某次调用已经结算，直接跳过。
        if (!(event instanceof ToolResultEndEvent end)) {
            return;
        }
        AgentToolStatus status = AgentToolStatus.of(end.getState());
        // 被拒与被中断本来就不进工具体，它们各有自己的表达，不是框架前置失败
        if (status == AgentToolStatus.DENIED || status == AgentToolStatus.INTERRUPTED) {
            return;
        }
        // 2. 结果已结束却没有工具体起点，也没有我方拦截记录，才补记为框架前置短路。
        ToolFact fact = facts.toolFact(end.getToolCallId());
        if (fact.startedAt() == null && fact.shortCircuitReason() == null) {
            facts.markShortCircuit(end.getToolCallId(), AgentToolExecutionFacts.SHORT_CIRCUIT_PRE_EXECUTION, null);
        }
    }

    private static List<String> toolCallIds(ActingInput input) {
        // 按模型给出的调用顺序提取 ID，批次登记沿用这个顺序，不按并行结果的返回时间排序。
        return input.toolCalls().stream().map(ToolUseBlock::getId).toList();
    }
}
