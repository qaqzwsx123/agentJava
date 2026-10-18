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

import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器
 * 统一处理线程池提交、拒绝兜底和取消句柄构建逻辑
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamAsyncExecutor {
    // 将模型流读取任务提交到线程池并构造取消句柄；线程池拒绝任务时取消 HTTP 请求并通知错误。

    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    /**
     * 在线程池执行读取流任务，共享 cancelled 标记用于停止读取，并返回取消 HTTP 请求的句柄。
     * 线程池拒绝任务时立即取消 HTTP 调用、通知回调错误，并返回空操作句柄。
     */
    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        // 1. 为本次读取任务创建共享取消标记，再把实际的流读取循环提交到指定 Executor。
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            CompletableFuture.runAsync(() -> streamTask.accept(cancelled), executor);
        } catch (RejectedExecutionException ex) {
            // 2. 线程池拒绝意味着无人读取这个请求，立即取消 Call 并通知错误，避免悬挂连接。
            call.cancel();
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        // 3. 正常提交后返回幂等句柄，同时置取消标记并取消底层 HTTP，以终止循环与阻塞读取。
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}
