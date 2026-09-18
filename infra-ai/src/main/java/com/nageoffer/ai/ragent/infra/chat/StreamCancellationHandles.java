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

import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamCancellationHandle 工具类
 * 用于构建常见的取消句柄，统一幂等取消语义
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamCancellationHandles {

    private static final StreamCancellationHandle NOOP = () -> {
    };

    public static StreamCancellationHandle noop() {
        // 返回共享空操作句柄，用于任务未启动或已经拒绝时统一满足取消接口。
        return NOOP;
    }

    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        // 将 HTTP Call 与读取循环的取消标记封装到同一幂等句柄，取消时同时处理两者。
        return new OkHttpCancellationHandle(call, cancelled);
    }

    private static final class OkHttpCancellationHandle implements StreamCancellationHandle {

        private final Call call;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean once = new AtomicBoolean(false);

        private OkHttpCancellationHandle(Call call, AtomicBoolean cancelled) {
            // 保存本次连接与共享取消标记，额外的 once 标记用于限制取消动作最多执行一次。
            this.call = call;
            this.cancelled = cancelled;
        }

        @Override
        public void cancel() {
            // 1. 先用 CAS 争抢取消权，重复停止直接返回，不重复处理底层连接。
            if (!once.compareAndSet(false, true)) {
                return;
            }
            // 2. 先让读取循环看到取消，再调用 OkHttp.cancel 打断可能阻塞中的网络读。
            if (cancelled != null) {
                cancelled.set(true);
            }
            if (call != null) {
                call.cancel();
            }
        }
    }
}
