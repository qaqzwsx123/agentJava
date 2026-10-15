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

import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;

/**
 * 把 StreamSpan 的 finish 桥接到 StreamCallback 的终态事件，
 * 让 *-stream-chat trace 节点记录从开始到 onComplete / onError 的真实耗时
 */
public final class StreamSpanCallback extends ForwardingStreamCallback {

    private final StreamSpan span;

    public StreamSpanCallback(StreamCallback delegate, StreamSpan span) {
        // 先把业务回调交给转发父类，再绑定跨线程 span，让流终态对应真正的请求结束。
        super(delegate);
        this.span = span;
    }

    @Override
    protected void onFinish(boolean success, Throwable error) {
        // 依据父类统一结算的成功标记结束追踪节点，失败时保留原异常交给追踪实现。
        if (success) {
            span.finishSuccess();
        } else {
            span.finishError(error);
        }
    }

    /**
     * 取消时由调用方触发：若 span 仍 RUNNING，按取消语义结束，避免 trace 行悬挂
     */
    public void onCancel() {
        // 先按取消语义结束仍运行的 span，再标记装饰器已收尾，避免后续完成或错误回调二次结算。
        span.finishCancelledIfRunning();
        finishExternally(false, null);
    }
}
