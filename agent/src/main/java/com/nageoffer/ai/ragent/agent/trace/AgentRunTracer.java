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

package com.nageoffer.ai.ragent.agent.trace;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 根 span 的存取口：中间件把它寄存进来，中断链路与轮次回写从这里取
 * 也是 RuntimeContext 上业务身份与轮次落到 span 的唯一写入点，中间件链内外的 span 都走这里
 * <p>
 * 中断发生在响应式链之外的 HTTP 线程上，那里够不着 Reactor 上下文，只能靠 RuntimeContext 这一条路
 */
public final class AgentRunTracer {

    /**
     * 根 span 寄存在调用上下文里，RuntimeContext 由调用方每次新建，天然按 run 隔离
     */
    static final String ROOT_SPAN_ATTRIBUTE = "ragent_trace_root_span";

    /**
     * 轮次计数器与根 span 同住上下文：推理那层递增一次，模型调用、工具批、工具执行体都只读
     */
    static final String ROUND_COUNTER_ATTRIBUTE = "ragent_trace_round_counter";

    private static final String INTERRUPTED_MESSAGE = "用户中止本轮，框架已自行收尾并存盘";

    private static final String ABORTED_MESSAGE = "用户中止本轮，等不到框架收尾，链路被强制断开";

    /**
     * 结局按严重度排序，越靠后越有资格覆盖先写的
     * <p>
     * 不按「先写者胜」：强制断流一定发生在优雅中断之后，先写者胜会把被掐链的运行永久掩盖成正常中断
     */
    private static final List<String> SEVERITY = List.of(
            RagentAttributes.RUN_OUTCOME_COMPLETED,
            RagentAttributes.RUN_OUTCOME_FAILED,
            RagentAttributes.RUN_OUTCOME_INTERRUPTED,
            RagentAttributes.RUN_OUTCOME_ABORTED);

    /**
     * 先读 Reactor 上下文再退回 ThreadLocal，与官方中间件同一套取法，跨线程后仍能找到父节点
     * 三处建 span 的地方共用这一份，各写各的会在某处漏掉退回路径时让那棵子树变成孤儿
     */
    static Context otelContext(ContextView ctxView) {
        // 优先从 Reactor Context 获取跨线程传播的 OTel 上下文；未找到时沿用当前线程上下文。
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(ctxView, Context.current());
    }

    public static void bindRoot(RuntimeContext ctx, Span span) {
        // 把根节点与独立轮次计数器放进本次调用的 RuntimeContext，供链路外的中断处理线程读取。
        if (ctx != null && span != null) {
            ctx.put(ROOT_SPAN_ATTRIBUTE, new RootSpanRef(span, new AtomicReference<>()));
            ctx.put(ROUND_COUNTER_ATTRIBUTE, new AtomicInteger());
        }
    }

    /**
     * 推理那层是唯一的递增者，返回本轮序号
     */
    static int nextRound(RuntimeContext ctx) {
        // 仅推理入口调用此递增操作；追踪未绑定计数器时返回 0，不凭空生成轮次。
        AtomicInteger counter = counter(ctx);
        return counter == null ? 0 : counter.incrementAndGet();
    }

    /**
     * 读当前轮次，0 表示还没进过推理或追踪没生效，写入方据此跳过不写
     */
    private static int currentRound(RuntimeContext ctx) {
        // 只读当前轮次，不修改计数；返回 0 表示尚无有效推理轮次。
        AtomicInteger counter = counter(ctx);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 轮次号只落有意义的节点，0 一律不写：写了会多出一档假轮次
     */
    static void writeRoundIndex(Span span, RuntimeContext ctx) {
        // 先读取共享轮次，再仅在 span 和正轮次均有效时写属性，确认续跑的前置工具批可没有轮次。
        int round = currentRound(ctx);
        if (span != null && round > 0) {
            span.setAttribute(RagentAttributes.REACT_ROUND_INDEX, round);
        }
    }

    private static AtomicInteger counter(RuntimeContext ctx) {
        // 读取本次调用的计数器并检查类型；空上下文或未装配追踪时返回 null。
        Object counter = ctx == null ? null : ctx.get(ROUND_COUNTER_ATTRIBUTE);
        return counter instanceof AtomicInteger value ? value : null;
    }

    /**
     * 用户、会话与三个业务 ID 写在每个 span 上：只写根 span 会让 observation 级的过滤与聚合失准，
     * 追踪后端不会把父节点的这几项回填给子节点，工具体 span 漏了就只能从 trace 逐层下钻
     * 三个业务 ID 是 PG 那侧的主键，链路里带上它们才有从消息记录反查到本条 trace 的路
     */
    static void writeIdentity(Span span, RuntimeContext ctx) {
        // 1. 缺少 span 或调用上下文时不写身份，避免追踪辅助逻辑影响实际运行。
        if (span == null || ctx == null) {
            return;
        }
        // 2. 用户和会话按需写入每个节点，追踪后端不会替子节点继承这些可过滤属性。
        if (StrUtil.isNotBlank(ctx.getUserId())) {
            span.setAttribute(AgentTraceAttributes.USER_ID, ctx.getUserId());
        }
        if (StrUtil.isNotBlank(ctx.getSessionId())) {
            span.setAttribute(AgentTraceAttributes.SESSION_ID, ctx.getSessionId());
        }
        // 3. 再补任务、被回复消息和确认卡消息 ID，让数据库记录能够关联到具体链路。
        writeContextId(span, ctx, AgentTraceContextKeys.TASK_ID, RagentAttributes.TASK_ID);
        writeContextId(span, ctx, AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, RagentAttributes.REPLY_TO_MESSAGE_ID);
        writeContextId(span, ctx, AgentTraceContextKeys.CONFIRM_MESSAGE_ID, RagentAttributes.CONFIRM_MESSAGE_ID);
    }

    /**
     * 首问没有确认消息号，缺哪个跳哪个，不落空串免得过滤时多出一档假值
     */
    private static void writeContextId(Span span, RuntimeContext ctx, String key, AttributeKey<String> attribute) {
        // 只接受非空白字符串 ID；首问没有确认消息 ID 等情况直接跳过。
        Object value = ctx.get(key);
        if (value instanceof String id && StrUtil.isNotBlank(id)) {
            span.setAttribute(attribute, id);
        }
    }

    public static void markCompleted(RuntimeContext ctx) {
        // 只提交正常完成结局；是否允许覆盖已有结局由统一严重度判断决定。
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_COMPLETED, null);
    }

    /**
     * 级别与报错原文由 onAgent 的错误回调写，这里只补结局，免得两处互相盖掉
     */
    public static void markFailed(RuntimeContext ctx) {
        // 只更新运行结局，不覆盖 onAgent 错误回调已经记录的异常级别和原文。
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_FAILED, null);
    }

    /**
     * 优雅中断：框架自己收了尾也存了盘，这是一次正常终止，故不写 error.type 也不动 span 状态
     * <p>
     * 官方那层随后按 onComplete 写 OK，OK 配着一个错误类型是自相矛盾的数据
     */
    public static void markInterrupted(RuntimeContext ctx) {
        // 记录优雅中断的结局和警告说明；它已由框架正常收尾，因此这里不额外设置错误状态。
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_INTERRUPTED, span -> {
            span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_WARNING);
            span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, INTERRUPTED_MESSAGE);
        });
    }

    /**
     * 强制断流：这轮多半没存盘，是真正的异常终止
     * <p>
     * 不写 StatusCode：官方 onAgent 的取消回调已经写过 ERROR + cancelled，这里再写只是重复一遍
     */
    public static void markAborted(RuntimeContext ctx) {
        // 记录强制断流，并将警告提升为错误；根 span 的状态收尾仍由外层追踪负责。
        writeOutcome(ctx, RagentAttributes.RUN_OUTCOME_ABORTED, span -> {
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.CANCELLED.value());
            span.setAttribute(AgentTraceAttributes.LEVEL, AgentTraceAttributes.LEVEL_ERROR);
            span.setAttribute(AgentTraceAttributes.STATUS_MESSAGE, ABORTED_MESSAGE);
        });
    }

    /**
     * 只有更严重的结局才落笔，附带属性也一并跳过：级别降不回来，文案更不该被后到的轻结局改写
     * extra 传 null 表示这个结局只有结局本身，级别与文案另有人写
     */
    private static void writeOutcome(RuntimeContext ctx, String outcome, Consumer<Span> extra) {
        // 1. 找到本轮根节点后原子争抢结局更新权；相同或更轻的结局不再写任何附带属性。
        RootSpanRef ref = rootRef(ctx);
        if (ref == null || !ref.claim(outcome)) {
            return;
        }
        // 2. 只有本次结局赢得更新权才写属性，防止较轻回调覆盖更严重的中断说明。
        ref.span.setAttribute(RagentAttributes.RUN_OUTCOME, outcome);
        if (extra != null) {
            extra.accept(ref.span);
        }
    }

    /**
     * 轮数与工具定义都属于整次调用，写在根 span 上；挂进轮次 span 会随轮数翻倍
     */
    static void writeRootAttribute(RuntimeContext ctx, Consumer<Span> writer) {
        // 只有根 span 已绑定时才执行传入的写入逻辑，追踪关闭时自动跳过。
        RootSpanRef ref = rootRef(ctx);
        if (ref != null) {
            writer.accept(ref.span);
        }
    }

    /**
     * 追踪关掉时上下文里没有根 span，各调用点原样跳过
     */
    private static RootSpanRef rootRef(RuntimeContext ctx) {
        // 按固定键读取并检查根节点引用类型，避免不同上下文属性被误用为追踪对象。
        Object root = ctx == null ? null : ctx.get(ROOT_SPAN_ATTRIBUTE);
        return root instanceof RootSpanRef ref ? ref : null;
    }

    /**
     * 根 span 与它已经落定的结局，两者同生共死才不会被别的 run 串味
     */
    private record RootSpanRef(Span span, AtomicReference<String> outcome) {

        /**
         * CAS 抢占：两条中断路径可能来自不同线程，比大小的读改写必须是原子的
         */
        private boolean claim(String candidate) {
            // 1. 将候选结局转换为严重度；循环中重新读取当前值，处理并发写入造成的竞争。
            int rank = SEVERITY.indexOf(candidate);
            while (true) {
                String current = outcome.get();
                if (current != null && SEVERITY.indexOf(current) >= rank) {
                    return false;
                }
                // 2. 比较后的值若仍未被其他线程改动就提交；竞争失败则重读并重新判断优先级。
                if (outcome.compareAndSet(current, candidate)) {
                    return true;
                }
            }
        }
    }

    private AgentRunTracer() {
        // 工具类只提供静态入口，无需创建实例。
    }
}
