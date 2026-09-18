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

package com.nageoffer.ai.ragent.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器
 */
public final class ProbeStreamBridge implements StreamCallback {

    private final StreamCallback downstream;
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    private final Object lock = new Object();
    private final List<Runnable> buffer = new ArrayList<>();
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        // 关联最终业务回调；候选未通过首包探测前，事件只暂存在本桥接器中。
        this.downstream = downstream;
    }

    /**
     * 首个正文到达时完成成功探测，再缓存或转发正文事件。
     */
    @Override
    public void onContent(String content) {
        // 正文回调到达就尝试完成首包成功信号，然后根据候选是否已提交决定缓存还是转发。
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    /**
     * 思考增量也视为首包探测成功，再按候选提交状态缓存或转发给下游。
     */
    @Override
    public void onThinking(String content) {
        // 这里把思考增量也计为有效首包，避免仍在正常思考的模型被当成没有响应。
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    /**
     * 正文到达前结束时完成无内容探测，同时缓存或转发完成事件。
     */
    @Override
    public void onComplete() {
        // 首包信号尚未完成时标记无内容结束；若之前已成功，complete 不会把既有成功结果覆盖掉。
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    /**
     * 将异常交给首包探测结果，再缓存或转发错误事件。
     */
    @Override
    public void onError(Throwable t) {
        // 先把异常作为探测结果交给等待线程，再缓存或转发错误，失败候选不会直接污染下游。
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        // 1. 在给定预算内等待共享 Future，超时转换为 TIMEOUT，Future 异常转换为 ERROR，中断交给上层。
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        // 2. 只有有效首包才提交缓冲；超时、错误或无内容结束仍由路由器取消并切换候选。
        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    private void commit() {
        // 1. 与事件入队使用同一把锁；只允许第一次提交重放缓冲，防止下游收到重复片段。
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            // 2. 标记提交后在当前锁内按缓存顺序重放；重复 commit 不会再次发送同一批缓存。
            buffer.forEach(Runnable::run);
        }
    }

    /**
     * 候选尚未提交时将事件保存在缓冲区；提交后直接转发，实际下游回调在锁外执行。
     */
    private void bufferOrDispatch(Runnable action) {
        // 1. 在锁内读取提交状态并决定是否入队，避免提交与新事件到达之间漏掉某个事件。
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        // 2. 已提交的后续事件在锁外执行实际业务回调，减少回调对入队判断的占用。
        if (dispatchNow) {
            action.run();
        }
    }

    /**
     * 探测结果
     */
    @Getter
    public static class ProbeResult {

        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            // 把探测类型与可选异常绑定为一次结果，调用方根据类型选择成功、切换候选或中断。
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            // 创建成功探测结果，不附带异常；后续等待逻辑会据此提交事件缓冲。
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            // 保留首包阶段观察到的原始异常，供路由器记录最后一次失败原因。
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            // 表示预算内没有完成探测，不在这里直接取消 HTTP；取消由持有句柄的路由器处理。
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            // 表示模型在首个正文或思考回调之前就结束，由路由器决定尝试下一个候选。
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            // 仅 SUCCESS 可以提交缓冲；超时、错误和无内容均不得作为有效输出发送给下游。
            return type == Type.SUCCESS;
        }
    }
}
