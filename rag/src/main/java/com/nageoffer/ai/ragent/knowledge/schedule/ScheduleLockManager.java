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

package com.nageoffer.ai.ragent.knowledge.schedule;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleLockManager {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeScheduleProperties scheduleProperties;

    private final String instancePrefix = resolveInstancePrefix();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("kb_schedule_lock_heartbeat_")
                    .setDaemon(true)
                    .build()
    );

    public ScheduleLockLease tryAcquire(String scheduleId, Date now) {
        // 生成本轮唯一锁令牌，只更新空闲或过期的调度行，更新成功才返回租约，失败表示其他执行者已占用。
        ScheduleLockLease lease = new ScheduleLockLease(scheduleId, nextLockToken());
        int updated = scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                        .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
        );
        return updated > 0 ? lease : null;
    }

    public boolean renew(ScheduleLockLease lease) {
        // 按调度 ID 和当前锁令牌延长租期，所有者已变化时不续约并返回 false。
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    public boolean release(ScheduleLockLease lease) {
        // 只有令牌仍匹配的执行者才能清空锁字段，避免误释放后来任务持有的锁。
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, null)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, null)
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    public ScheduleLockHeartbeat startHeartbeat(ScheduleLockLease lease) {
        // 创建本轮心跳状态并按固定延迟续约，将调度句柄绑定到心跳以便完成或丢锁时取消。
        long now = System.currentTimeMillis();
        ScheduleLockHeartbeat heartbeat = new ScheduleLockHeartbeat(lease, now, effectiveLockMillis());
        long intervalMillis = computeHeartbeatIntervalMillis();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(
                () -> doHeartbeat(heartbeat),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        heartbeat.bind(future);
        return heartbeat;
    }

    public Date computeLockUntil() {
        // 用当前时间加有效租期，计算本次写入数据库的锁截止时间。
        return new Date(System.currentTimeMillis() + effectiveLockMillis());
    }

    private void doHeartbeat(ScheduleLockHeartbeat heartbeat) {
        // 关闭或丢锁后不再续约；明确续约失败立即标记丢锁，暂时异常则在安全租期内重试。
        if (heartbeat.isClosed() || heartbeat.isLost()) {
            return;
        }
        try {
            if (renew(heartbeat.lease())) {
                heartbeat.markRenewed();
                return;
            }
            heartbeat.markLost();
            log.warn("定时刷新锁已丢失: scheduleId={}, lockToken={}",
                    heartbeat.lease().scheduleId(), heartbeat.lease().lockToken());
        } catch (Exception e) {
            if (heartbeat.isExpiredWithoutConfirmation()) {
                heartbeat.markLost();
                log.warn("定时刷新锁续约失败且已超过安全窗口: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            } else {
                log.warn("定时刷新锁续约失败，将继续重试: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            }
        }
    }

    private long computeHeartbeatIntervalMillis() {
        // 按租期三分之一安排心跳，并限制在五到六十秒之间，再换算为毫秒。
        long effectiveLockSeconds = effectiveLockSeconds();
        long intervalSeconds = Math.max(5, Math.min(effectiveLockSeconds / 3, 60));
        return intervalSeconds * 1000;
    }

    private long effectiveLockMillis() {
        // 把校验后的有效租期秒数换成毫秒，统一心跳与过期判断口径。
        return effectiveLockSeconds() * 1000;
    }

    private long effectiveLockSeconds() {
        // 锁租期至少六十秒，避免过短配置导致任务频繁失去运行权。
        return Math.max(scheduleProperties.getLockSeconds(), 60L);
    }

    private String nextLockToken() {
        // 实例前缀加随机 UUID，区分同一实例发起的不同轮次，防止旧任务操作新锁。
        return instancePrefix + ":" + UUID.randomUUID();
    }

    private static String resolveInstancePrefix() {
        // 用主机名和随机 UUID 标记实例，主机名解析失败时采用 unknown 并仍保留随机区分。
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    @PreDestroy
    public void shutdown() {
        // 关闭心跳执行器并取消待执行任务，供应用停止时释放线程资源。
        heartbeatExecutor.shutdownNow();
    }

    public static final class ScheduleLockHeartbeat implements AutoCloseable {

        private final ScheduleLockLease lease;
        private final long lockTtlMillis;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastConfirmedAt = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private ScheduleLockHeartbeat(ScheduleLockLease lease, long startAt, long lockTtlMillis) {
            // 保存租约、租期与最初确认时间，原子状态用于协调执行线程和心跳线程。
            this.lease = lease;
            this.lockTtlMillis = lockTtlMillis;
            this.lastConfirmedAt.set(startAt);
        }

        private void bind(ScheduledFuture<?> future) {
            // 保存本轮调度句柄，后续关闭或确认丢锁时可取消继续续约。
            this.future = future;
        }

        public ScheduleLockLease lease() {
            // 返回心跳对应的原始租约，所有续约始终使用同一个锁令牌。
            return lease;
        }

        public boolean isLost() {
            // 以原子方式读取是否已确认丢锁，供主处理流程及时终止后续动作。
            return lost.get();
        }

        private boolean isClosed() {
            // 读取心跳是否已经关闭，避免任务结束后继续续约。
            return closed.get();
        }

        private void markRenewed() {
            // 成功续约后更新最近确认时间，重新计算允许暂时网络失败的安全窗口。
            lastConfirmedAt.set(System.currentTimeMillis());
        }

        private boolean isExpiredWithoutConfirmation() {
            // 距最近成功确认已达到完整租期时判为不再安全，停止假定仍拥有锁。
            return System.currentTimeMillis() - lastConfirmedAt.get() >= lockTtlMillis;
        }

        private void markLost() {
            // 只在第一次确认丢锁时改变原子状态并取消后续心跳，避免重复结束动作。
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }

        @Override
        public void close() {
            // 以原子方式只关闭一次并取消后续心跳，数据库锁的释放仍由调用方单独执行。
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }
    }
}
