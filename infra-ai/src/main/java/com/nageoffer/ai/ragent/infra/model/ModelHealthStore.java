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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型健康状态存储器
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {
    // 在当前进程内记录模型连续失败、熔断和半开探测状态，控制调用许可，持续失败时暂停调用，探测成功后恢复。

    private final AIModelProperties properties;

    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    private final AtomicLong probeTokenSeq = new AtomicLong();

    /**
     * 模型调用许可，halfOpenToken 为 0 时不持有半开探测名额
     */
    public record CallPermit(String modelId, long halfOpenToken) {
        // 保存一次模型调用的许可及半开探测令牌，用于在取消时只释放当前请求持有的探测名额。
    }

    /**
     * 判断模型是否处于未到期熔断或已有探测请求的半开状态，供候选筛选使用。
     */
    public boolean isUnavailable(String id) {
        // 候选筛选只读取当前状态，不在此认领半开探测名额；真正调用前仍要执行 allowCall。
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    /**
     * 返回 null 表示拒绝调用
     */
    public CallPermit allowCall(String id) {
        // 1. 空 ID 不放行；其余调用按模型 ID 进入原子 compute，避免多个线程同时取得半开探测权。
        if (id == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        AtomicReference<CallPermit> granted = new AtomicReference<>();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            // 熔断未到期不放行，到期时原子转换为半开并仅分配一个探测令牌。
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    return v;
                }
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            // 已有半开探测在飞时拒绝并发试探，探测取消后可由归还许可的路径再次放行。
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v;
                }
                v.halfOpenInFlight = true;
                v.halfOpenToken = probeTokenSeq.incrementAndGet();
                granted.set(new CallPermit(id, v.halfOpenToken));
                return v;
            }
            // CLOSED 正常放行但不占半开名额，因此令牌设为 0，后续取消无需归还探测槽位。
            granted.set(new CallPermit(id, 0L));
            return v;
        });
        return granted.get();
    }

    /**
     * 调用成功后关闭熔断、清空连续失败次数及半开占用；未知模型创建初始健康记录。
     */
    public void markSuccess(String id) {
        // 成功后原子重置为 CLOSED，清空连续失败、熔断截止时间和半开占用，让后续请求恢复正常调用。
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 普通失败累加计数，达到阈值后打开熔断；半开探测失败立即重新熔断并设置恢复时间。
     */
    public void markFailure(String id) {
        // 1. 失败记录按模型 ID 原子更新，未知模型先建立正常初始记录，再累计本次失败。
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            // 半开仅用于试探恢复，探测再次失败立即打开熔断，不等待累计到普通阈值。
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            // 2. 普通连续失败达到配置阈值才熔断；半开失败已在上一个分支立即重新打开。
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 仅释放当前凭证持有的半开探测名额
     */
    public void releaseHalfOpenPermit(CallPermit permit) {
        // 只归还带正数令牌的半开许可，普通 CLOSED 调用没有探测名额可释放。
        if (permit == null || permit.halfOpenToken() <= 0L) {
            return;
        }
        healthById.computeIfPresent(permit.modelId(), (k, v) -> {
            // 只有状态、占用和令牌都仍匹配才归还，旧请求的取消不能释放新一轮探测的名额。
            if (v.state == State.HALF_OPEN && v.halfOpenInFlight && v.halfOpenToken == permit.halfOpenToken()) {
                v.halfOpenInFlight = false;
            }
            return v;
        });
    }

    private static class ModelHealth {
        // 保存单个模型的可变健康记录，包括连续失败次数、熔断状态、恢复时间和半开探测占用信息。
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private long halfOpenToken;
        private State state;

        private ModelHealth() {
            // 新模型默认 CLOSED 且无失败或探测占用，只有实际失败才逐步触发熔断。
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.halfOpenToken = 0L;
            this.state = State.CLOSED;
        }
    }

    private enum State {
        // 定义熔断器的正常放行 CLOSED、暂停调用 OPEN 和恢复探测 HALF_OPEN 三种状态。
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
