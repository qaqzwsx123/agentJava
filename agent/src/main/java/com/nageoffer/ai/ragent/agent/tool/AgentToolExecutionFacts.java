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

import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 run 内执行事实的唯一生成者：run 起点、批次号、组内序号、单工具真起止、两条中断时刻都只在这里产生一次
 * <p>
 * trace、SSE、PG、前端一律读这里，不各自复算——同一个事实有两个算法，迟早会算出两个值
 */
@Slf4j
public final class AgentToolExecutionFacts {

    /**
     * 放进 RuntimeContext 的键，中间件与工具体都从上下文取同一个实例
     */
    public static final String RUNTIME_CONTEXT_KEY = "ragent_tool_execution_facts";

    /**
     * 短路原因：我方技能遮蔽，detail 是触发遮蔽的 skillCode
     */
    public static final String SHORT_CIRCUIT_MASKED = "masked";

    /**
     * 短路原因：框架在进入工具体之前就否了，事件里拿不到具体原因，不细分
     */
    public static final String SHORT_CIRCUIT_PRE_EXECUTION = "pre_execution";

    private final String runId;
    private final Clock clock;

    /**
     * 本轮起点，构造即定格；轮次耗时从这里起算，不由两条消息的落库时刻相减
     */
    private final long runStartedAt;
    private final AtomicLong runEndedAt = new AtomicLong();

    private final Map<String, ToolFact> toolFacts = new ConcurrentHashMap<>();
    private final AtomicInteger callIndexSeq = new AtomicInteger();
    private final List<ToolBatchFact> batches = new CopyOnWriteArrayList<>();
    private final AtomicReference<ToolBatchFact> openBatch = new AtomicReference<>();

    /**
     * 0 表示未写入：中断时刻取自服务端 epoch millis，不可能落在 1970
     */
    private final AtomicLong interruptedAt = new AtomicLong();
    private final AtomicLong cancelledAt = new AtomicLong();

    public AgentToolExecutionFacts(String runId, Clock clock) {
        // 固定本次运行 ID、共享时钟与开始时刻；之后所有工具、批次和运行耗时都基于这份事实。
        this.runId = runId == null || runId.isBlank() ? "run" : runId;
        this.clock = clock;
        this.runStartedAt = clock.millis();
    }

    /**
     * 从框架上下文取本次 run 的事实源，取不到返回 null，调用方按「无追踪」放行
     */
    public static AgentToolExecutionFacts from(RuntimeContext ctx) {
        // 先处理空上下文，再验证对象类型；不存在或类型不符时返回 null，避免错误强转。
        Object facts = ctx == null ? null : ctx.get(RUNTIME_CONTEXT_KEY);
        return facts instanceof AgentToolExecutionFacts value ? value : null;
    }

    /**
     * 从本轮共享时钟读取毫秒时间，统一工具执行与批次统计的计时来源。
     */
    public long now() {
        // 统一通过注入的 Clock 取时间，生产计时与测试中的可控时钟保持同一入口。
        return clock.millis();
    }

    /**
     * 本轮收口，终点 CAS 一次后返回耗时；落库与 SSE 各问一次拿到的必然是同一个数
     * 挂起等确认的那次就在这里收口，续跑是新的一次 run 重新起算——中间用户想了多久不算进任何一段
     */
    public long settleRun() {
        // 终点只允许从 0 写入一次；后续落库、SSE 或追踪重复结算时复用同一个结束时刻。
        runEndedAt.compareAndSet(0L, clock.millis());
        return runEndedAt.get() - runStartedAt;
    }

    /**
     * 首个观测者分配序号，之后无论谁再问都返回原值：推理阶段的 pending 块与确认续跑的开头事件都可能是首个
     */
    public int callIndexOf(String toolCallId) {
        // 第一次见到调用 ID 时分配序号；后续读取复用已分配值，并行返回不会重排它。
        return factOf(toolCallId).callIndex;
    }

    /**
     * 取得或创建指定调用的事实记录，供工具执行体更新状态和耗时。
     */
    public ToolFact toolFact(String toolCallId) {
        // 按调用 ID 返回同一份可更新事实；尚未观察到该调用时懒创建。
        return factOf(toolCallId);
    }

    /**
     * 进入 acting 时开一批，已有未收尾的批时返回 null——谁开的谁负责收，避免两层中间件重复收尾
     */
    public ToolBatchFact beginBatch() {
        // 1. 先生成候选批次 ID 和起始时间；它只有成功占用 openBatch 后才算正式开批。
        ToolBatchFact batch = new ToolBatchFact(runId + "-" + batches.size(), clock.millis());
        // 2. 只有当前无开放批次时才能认领，竞争失败的候选不加入批次历史。
        if (!openBatch.compareAndSet(null, batch)) {
            return null;
        }
        batches.add(batch);
        return batch;
    }

    /**
     * 批终点是 acting 流的真实终止，不取成员最晚结束：权限判定与后处理都算在批里
     */
    public void endBatch(ToolBatchFact batch) {
        // 1. 无批次时直接退出，方便调用方统一处理开批失败的情况。
        if (batch == null) {
            return;
        }
        // 2. 只定格一次终点，并且只清除自己认领的开放批次，避免误关别人的批次。
        batch.endedAt.compareAndSet(0L, clock.millis());
        openBatch.compareAndSet(batch, null);
    }

    /**
     * 把本批名册挂上批次，顺序取自 ActingInput；已分配的序号一律沿用，不重排也不重分配
     */
    public void enroll(ToolBatchFact batch, List<String> toolCallIds) {
        // 1. 没有批次或名册时不登记；有名册则按 ActingInput 的顺序逐个关联已有调用事实。
        if (batch == null || toolCallIds == null) {
            return;
        }
        int previous = Integer.MIN_VALUE;
        boolean ordered = true;
        for (String toolCallId : toolCallIds) {
            ToolFact fact = factOf(toolCallId);
            // 2. 调用第一次归批后保持不变；同时检查已分配序号是否仍与本批名册顺序一致。
            fact.batchId.compareAndSet(null, batch.batchId);
            batch.enroll(fact.toolCallId);
            ordered &= fact.callIndex > previous;
            previous = fact.callIndex;
        }
        if (!ordered) {
            // 只报不改：序号一经分配就是历史，为了对齐名册去改它等于让 PG 里已落的块对不上
            log.warn("acting 名册顺序与已分配的 callIndex 不一致, batchId: {}, toolCalls: {}",
                    batch.batchId, toolCallIds);
        }
    }

    /**
     * 工具体进入时打起点，CAS 一次，重试或重复订阅都不改已定格的时刻
     */
    public long markStarted(String toolCallId) {
        // 1. 取得调用事实，并用 CAS 固定首次进入工具体的时刻；重复订阅或重试不覆盖它。
        ToolFact fact = factOf(toolCallId);
        fact.startedAt.compareAndSet(0L, clock.millis());
        // 2. 工具体开跑时若已有开放批次就补上关联；已有关联则不覆盖。
        ToolBatchFact batch = openBatch.get();
        if (batch != null) {
            fact.batchId.compareAndSet(null, batch.batchId);
        }
        return fact.startedAt.get();
    }

    /**
     * 工具体终止时打终点，成功、异常、取消三条路都收在这里
     */
    public long markEnded(String toolCallId) {
        // 工具自然完成或报错时固定结束时刻；已有结束值则保留，避免重复回调改变耗时。
        ToolFact fact = factOf(toolCallId);
        fact.endedAt.compareAndSet(0L, clock.millis());
        return fact.endedAt.get();
    }

    /**
     * 断在半路的工具收在中断时刻，没有中断时刻才退回当前时刻
     * 与 markEnded 分开是因为终点来源不同：正常终止取此刻，提前收口取全局那个终点
     */
    public long markTerminated(String toolCallId) {
        // 先读取用户中断或强制断流的事实时刻；没有该时刻才用当前时间结束工具记录。
        ToolFact fact = factOf(toolCallId);
        Long termination = terminationAt();
        fact.endedAt.compareAndSet(0L, termination == null ? clock.millis() : termination);
        return fact.endedAt.get();
    }

    /**
     * 未进入工具体就被否掉的调用，只记原因，不给起止——给了就是凭空造出一段执行
     */
    public void markShortCircuit(String toolCallId, String reason, String detail) {
        // 只保存首次观测到的拦截原因与细节，不写执行起止，明确这次调用没有进入工具体。
        ToolFact fact = factOf(toolCallId);
        fact.shortCircuitReason.compareAndSet(null, reason);
        fact.shortCircuitDetail.compareAndSet(null, detail);
    }

    /**
     * 优雅中断的时刻，抢到的那次返回 true
     */
    public boolean markInterrupted() {
        // 只有首次优雅中断能把时刻从 0 写入，返回值告诉调用方是否抢到了本次记录权。
        return interruptedAt.compareAndSet(0L, clock.millis());
    }

    /**
     * 强制断流的时刻，抢到的那次返回 true
     */
    public boolean markCancelled() {
        // 独立记录首次强制断流时刻，保留与优雅中断的区别；重复取消不会覆盖时间。
        return cancelledAt.compareAndSet(0L, clock.millis());
    }

    public Long interruptedAt() {
        // 内部用 0 表示尚未中断，对外转换为 null，避免把未发生的中断当成真实时间。
        return nullIfUnset(interruptedAt.get());
    }

    public Long cancelledAt() {
        // 返回首次强制断流时刻；没有发生过则返回 null。
        return nullIfUnset(cancelledAt.get());
    }

    /**
     * 所有被提前收口的 span 统一读它：两条中断路径互斥，取先写入的那个
     */
    public Long terminationAt() {
        // 1. 分别读取两条中止路径；只出现一条时直接使用它，两条都出现时取更早时刻。
        long interrupted = interruptedAt.get();
        long cancelled = cancelledAt.get();
        if (interrupted == 0L) {
            return nullIfUnset(cancelled);
        }
        if (cancelled == 0L) {
            return interrupted;
        }
        return Math.min(interrupted, cancelled);
    }

    /**
     * 返回当前打开的工具执行批次，供同批工具关联批次信息。
     */
    public ToolBatchFact currentBatch() {
        // 读取当前仍未收口的批次；已经结束或还未进入 acting 时为 null。
        return openBatch.get();
    }

    public ToolBatchFact batchAt(int index) {
        // 批次索引必须落在已登记列表内；越界返回 null，避免追踪收尾反过来打断业务。
        return index >= 0 && index < batches.size() ? batches.get(index) : null;
    }

    public int batchCount() {
        // 返回已开过的批次数，包含已经结束的批次，供外层追踪预先记录本批索引。
        return batches.size();
    }

    /**
     * 将空调用 ID 归入 unknown，并按调用 ID 复用事实对象；首次出现时分配调用顺序号。
     */
    private ToolFact factOf(String toolCallId) {
        // 1. 缺失的调用 ID 统一归入 unknown，再以并发映射确保同一 ID 共享一份事实。
        String key = toolCallId == null || toolCallId.isBlank() ? "unknown" : toolCallId;
        // 2. 只有首次创建事实对象才递增调用序号，避免多处读取把同一调用算成多条。
        return toolFacts.computeIfAbsent(key, id -> new ToolFact(id, callIndexSeq.getAndIncrement()));
    }

    private static Long nullIfUnset(long value) {
        // 将内部未写入标记 0 转为 null；真实毫秒时间原样返回。
        return value == 0L ? null : value;
    }

    /**
     * 一次工具调用的全部事实，起止各自 CAS 一次
     */
    public static final class ToolFact {

        private final String toolCallId;
        private final int callIndex;
        private final AtomicReference<String> batchId = new AtomicReference<>();
        private final AtomicLong startedAt = new AtomicLong();
        private final AtomicLong endedAt = new AtomicLong();
        private final AtomicReference<String> shortCircuitReason = new AtomicReference<>();
        private final AtomicReference<String> shortCircuitDetail = new AtomicReference<>();

        private ToolFact(String toolCallId, int callIndex) {
            // 保存调用 ID 和首次分配的序号；起止、批次与拦截信息由后续执行事件逐步补齐。
            this.toolCallId = toolCallId;
            this.callIndex = callIndex;
        }

        public int callIndex() {
            // 序号构造后固定，读取时不再分配或重新排序。
            return callIndex;
        }

        public String batchId() {
            // 返回已经关联的批次 ID；尚未登记进批时保留 null。
            return batchId.get();
        }

        public Long startedAt() {
            // 只有真正进入工具体才会有开始时刻；未执行的调用返回 null。
            return nullIfUnset(startedAt.get());
        }

        public Long endedAt() {
            // 读取首次结算的结束时刻；仍在执行或未进入工具体时可能为 null。
            return nullIfUnset(endedAt.get());
        }

        /**
         * 任一端缺失就留空：断在半路的那条没有耗时可言
         */
        public Long durationMs() {
            // 起点或终点缺失时不编造耗时；两端都有值才计算毫秒差。
            long start = startedAt.get();
            long end = endedAt.get();
            return start == 0L || end == 0L ? null : end - start;
        }

        public String shortCircuitReason() {
            // 返回未执行的结构性原因，如技能遮蔽或框架前置失败；没有短路记录时为 null。
            return shortCircuitReason.get();
        }

        public String shortCircuitDetail() {
            // 返回短路的补充信息，例如触发遮蔽的技能代码，供业务展示与排查使用。
            return shortCircuitDetail.get();
        }
    }

    /**
     * 一次 acting 的批次事实，名册顺序即模型给出调用的顺序
     */
    public static final class ToolBatchFact {

        private final String batchId;
        private final long startedAt;
        private final AtomicLong endedAt = new AtomicLong();
        private final List<String> members = new CopyOnWriteArrayList<>();

        private ToolBatchFact(String batchId, long startedAt) {
            // 固定批次 ID 和起点；成员和结束时刻随后由批中间件登记。
            this.batchId = batchId;
            this.startedAt = startedAt;
        }

        private void enroll(String toolCallId) {
            // 成员只登记一次，重复观察同一调用不会让批次人数增加。
            if (!members.contains(toolCallId)) {
                members.add(toolCallId);
            }
        }

        public String batchId() {
            // 返回开批时固定的 ID，与工具事实、SSE 和数据库使用的值保持一致。
            return batchId;
        }

        public long startedAt() {
            // 返回批次创建时固定的起点，包含该批权限处理与工具执行的整体区间。
            return startedAt;
        }

        public Long endedAt() {
            // 批次尚未收口时返回 null；已收口则读取固定的结束时间。
            return nullIfUnset(endedAt.get());
        }

        public List<String> members() {
            // 返回成员列表的不可变副本，避免读取方绕过事实源修改批次名册。
            return List.copyOf(members);
        }

        public int size() {
            // 按去重后的调用 ID 数量统计批次成员，不受结果事件重复发送影响。
            return members.size();
        }
    }
}
