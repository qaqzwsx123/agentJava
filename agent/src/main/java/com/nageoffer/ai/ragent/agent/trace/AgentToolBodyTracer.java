

package com.nageoffer.ai.ragent.agent.trace;

import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolFact;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 工具执行体的追踪包装：起止时刻在这里写进事实源，span 只是把同一份事实读出来
 * <p>
 * 包住的是工具自己的执行体，框架侧的查表、分组校验、参数合并、排队与重试都不在这段里，
 * 这一点由 ragent.tool.span_scope=body 明说，别让读的人把它当成整次工具调用的耗时
 */
public final class AgentToolBodyTracer {

    /**
     * 名字只留低基数的操作名与工具名，调用 ID 与状态一律进属性
     */
    private static final String SPAN_NAME_PREFIX = "execute_tool ";

    /**
     * 未装配时的兜底：内容一律不采，限额取最小
     * 追踪关掉时本类照样被四把工具调到，缺省值得是「不采」而不是空指针或全量
     */
    private static final AgentTraceSerializer CONTENT_OFF =
            new AgentTraceSerializer(0, 0, false);

    /**
     * 工具体的 span 建在中间件链之外，拿不到注入进来的那份，只能从装配处推过来
     * 与本类已经依赖的 GlobalOpenTelemetry 同属一类全局态，装配点仍只有 AgentTracingConfiguration 一个
     */
    private static volatile AgentTraceSerializer serializer = CONTENT_OFF;

    private AgentToolBodyTracer() {
        // 工具体追踪统一通过静态入口使用，禁止外部实例化。
    }

    /**
     * 传 null 即恢复成不采内容，追踪装配缺席与测试收尾都用它
     */
    static void configure(AgentTraceSerializer configured) {
        // 装配期推入共享序列化器；传 null 则恢复不采集内容的兜底配置。
        serializer = configured == null ? CONTENT_OFF : configured;
    }

    /**
     * 事实源缺失时整段放行：给不出可对账的时刻就不该建这个 span
     */
    public static Mono<ToolResultBlock> trace(AgentTool tool, ToolCallParam param,
                                              Supplier<Mono<ToolResultBlock>> body) {
        // 1. 等到订阅时再读 Reactor 上下文，保证父 span 来自这次实际执行，而不是组装 Mono 的线程。
        return Mono.deferContextual(ctxView -> {
            RuntimeContext runtimeContext = param == null ? null : param.getRuntimeContext();
            AgentToolExecutionFacts facts = AgentToolExecutionFacts.from(runtimeContext);
            if (facts == null) {
                return body.get();
            }
            // 2. 在调用工具体前记录真实起点，再尝试建立 span；没有有效父节点也照常记录事实。
            String toolCallId = toolCallId(param);
            long startedAt = facts.markStarted(toolCallId);
            Span span = openSpan(ctxView, tool, facts, toolCallId, startedAt, runtimeContext, param);
            // 3. 成功、异常和取消共用一次性收口标记，只有一个回调能固定终点并结束 span。
            AtomicBoolean ended = new AtomicBoolean();
            return body.get()
                    // 4. 成功信号仍可能携带业务 ERROR 或 INTERRUPTED 结果，统一交结算函数判断。
                    .doOnSuccess(result -> {
                        if (ended.compareAndSet(false, true)) {
                            settleFinished(span, facts, toolCallId, result, null);
                        }
                    })
                    // 5. 响应式异常与业务错误结果分开记录，保留真实异常类型与堆栈。
                    .doOnError(error -> {
                        if (ended.compareAndSet(false, true)) {
                            settleFinished(span, facts, toolCallId, null, error);
                        }
                    })
                    // 6. 取消没有结果值，使用用户中止事实收口，不伪造正常返回。
                    .doOnCancel(() -> {
                        if (ended.compareAndSet(false, true)) {
                            settleCancelled(span, facts, toolCallId);
                        }
                    });
        });
    }

    /**
     * 追踪关闭或父亲无效时返回 null：事实照写，只是没有节点可挂
     */
    private static Span openSpan(ContextView ctxView, AgentTool tool, AgentToolExecutionFacts facts,
                                 String toolCallId, long startedAt, RuntimeContext runtimeContext,
                                 ToolCallParam param) {
        // 1. 解析当前父节点；没有有效父 span 时不建孤立节点，但外层仍会记录工具执行事实。
        Context parent = AgentRunTracer.otelContext(ctxView);
        if (!Span.fromContext(parent).getSpanContext().isValid()) {
            return null;
        }
        // 2. 用事实源的开始时刻建节点，追踪耗时与数据库、SSE 的工具耗时使用同一时间口径。
        String toolName = tool == null ? null : tool.getName();
        Span span = GlobalOpenTelemetry.getTracer(AgentTracingConfiguration.INSTRUMENTATION_SCOPE)
                .spanBuilder(SPAN_NAME_PREFIX + toolName)
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(Instant.ofEpochMilli(startedAt))
                .startSpan();
        span.setAttribute(AgentTraceAttributes.OBSERVATION_TYPE, AgentTraceAttributes.TYPE_TOOL);
        span.setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OPERATION_EXECUTE_TOOL);
        span.setAttribute(GenAiAttributes.TOOL_TYPE, GenAiAttributes.TOOL_TYPE_FUNCTION);
        if (toolName != null) {
            span.setAttribute(GenAiAttributes.TOOL_NAME, toolName);
        }
        if (toolCallId != null) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_ID, toolCallId);
        }
        if (tool != null && tool.getDescription() != null) {
            span.setAttribute(GenAiAttributes.TOOL_DESCRIPTION, tool.getDescription());
        }
        span.setAttribute(RagentAttributes.TOOL_SPAN_SCOPE, RagentAttributes.SPAN_SCOPE_BODY);
        // 与根、轮次、批同一组身份键：追踪后端不替子节点继承父节点的用户与会话，漏写这里就查不到 TOOL
        AgentRunTracer.writeIdentity(span, runtimeContext);
        // 工具体挂在批下、批与推理是兄弟，按轮聚合工具耗时只能靠这把键
        AgentRunTracer.writeRoundIndex(span, runtimeContext);
        ToolFact fact = facts.toolFact(toolCallId);
        span.setAttribute(RagentAttributes.TOOL_CALL_INDEX, fact.callIndex());
        if (fact.batchId() != null) {
            span.setAttribute(RagentAttributes.TOOL_BATCH_ID, fact.batchId());
        }
        // 3. 身份、轮次、调用与批次已关联后，再按内容开关和字段预算记录工具入参。
        writeArguments(span, param);
        return span;
    }

    /**
     * 规范键给按 GenAI 语义读的一方，LangFuse 键给 UI，同一份内容写两处，别让其中一边看到的是另一种口径
     */
    private static void writeArguments(Span span, ToolCallParam param) {
        // 按当前内容开关和预算序列化入参；返回 null 表示不采集，本方法直接跳过属性写入。
        String arguments = serializer.toolArguments(param == null ? null : param.getInput());
        if (arguments == null) {
            return;
        }
        span.setAttribute(GenAiAttributes.TOOL_CALL_ARGUMENTS, arguments);
        span.setAttribute(AgentTraceAttributes.OBSERVATION_INPUT, arguments);
    }

    /**
     * 成功与失败共用一个终点来源：都取事实源刚写下的那个时刻，不各读一次时钟
     */
    private static void settleFinished(Span span, AgentToolExecutionFacts facts, String toolCallId,
                                       ToolResultBlock result, Throwable error) {
        // 1. 先把结果映射为业务状态；普通结束取实际终点，协作式中断对齐用户停止时刻。
        // 协作式取消会返回 INTERRUPTED 而不是发出 Reactor cancel 信号，终点仍要对齐用户中断时刻
        AgentToolStatus status = AgentToolStatus.of(result == null ? null : result.getState());
        long endedAt = status == AgentToolStatus.INTERRUPTED
                ? facts.markTerminated(toolCallId)
                : facts.markEnded(toolCallId);
        if (span == null) {
            return;
        }
        // 空完成是工具违约，交给同一处状态映射判成失败，不在这里另立一种状态
        span.setAttribute(RagentAttributes.TOOL_STATUS, status.value());
        // 2. 依次区分抛异常、工具主动返回失败与中断，错误来源取结构位置而不是解析文案。
        if (error != null) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofThrowable(error));
            span.setAttribute(RagentAttributes.TOOL_ERROR_SOURCE, RagentAttributes.ERROR_SOURCE_EXCEPTION);
            span.recordException(error);
        } else if (status == AgentToolStatus.FAILED) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.TOOL_ERROR.value());
            if (result != null) {
                // 没结果就没观测到来源，宁可空着也不猜一个
                span.setAttribute(RagentAttributes.TOOL_ERROR_SOURCE, RagentAttributes.ERROR_SOURCE_TOOL_RESULT);
            }
        } else if (status == AgentToolStatus.INTERRUPTED) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofCancelledSpan(facts.terminationAt()));
        }
        // 3. 先写结局正文再按事实终点结束，span 结束后补属性不会生效。
        writeOutcome(span, status, result, error);
        span.end(Instant.ofEpochMilli(endedAt));
    }

    /**
     * 结局要能读：只标 ERROR 不给正文等于把排查推回日志
     * 规范键只在成功时写，它表示的是工具返回值，拿失败正文顶上去会让按它统计的一方读到假成功
     */
    private static void writeOutcome(Span span, AgentToolStatus status, ToolResultBlock result, Throwable error) {
        // 1. 先生成包含状态、结果或异常的 UI 结局，内容采集关闭时不写正文属性。
        String outcome = serializer.toolOutcome(status.value(), result, error);
        if (outcome == null) {
            return;
        }
        span.setAttribute(AgentTraceAttributes.OBSERVATION_OUTPUT, outcome);
        // 2. 失败正文只放 UI 结局，不写到规范的工具返回值字段，避免统计时被误认为正常返回。
        if (error != null || status == AgentToolStatus.FAILED) {
            return;
        }
        String returned = serializer.toolResult(result);
        if (returned != null) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_RESULT, returned);
        }
    }

    /**
     * 断在半路的那条对齐中断时刻，用断流当时的墙上时间会把中断后的等待窗口算进耗时
     */
    private static void settleCancelled(Span span, AgentToolExecutionFacts facts, String toolCallId) {
        // 1. 先固定工具事实终点；即使未建立 span，SSE 和数据库仍可读取同一份结束时间。
        long endedAt = facts.markTerminated(toolCallId);
        if (span == null) {
            return;
        }
        // 2. 取消路径没有工具结果，以中断状态和中止原因补齐后，再按同一事实终点结束 span。
        span.setAttribute(RagentAttributes.TOOL_STATUS, AgentToolStatus.INTERRUPTED.value());
        span.setStatus(StatusCode.ERROR);
        span.setAttribute(AgentErrorTypes.KEY, AgentErrorTypes.ofCancelledSpan(facts.terminationAt()));
        // 断在半路的没有返回值，写这一份是为了让「有入参、没输出」不再等价于一次静默成功
        writeOutcome(span, AgentToolStatus.INTERRUPTED, null, null);
        span.end(Instant.ofEpochMilli(endedAt));
    }

    private static String toolCallId(ToolCallParam param) {
        // 参数或调用块缺失时返回 null，其余情况沿用框架调用 ID，不在追踪侧生成第二个 ID。
        return param == null || param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
    }
}
