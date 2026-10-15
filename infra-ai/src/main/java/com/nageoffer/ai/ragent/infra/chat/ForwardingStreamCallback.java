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

import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 透传式 StreamCallback 装饰器
 * <p>
 * onContent / onThinking 直接透传 delegate，onComplete / onError 透传后 CAS-once
 * 触发 {@link #onFinish(boolean, Throwable)}，便于 trace、计量等场景在流式终态做收尾
 */
public abstract class ForwardingStreamCallback implements StreamCallback {

    private final StreamCallback delegate;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean firstContentSeen = new AtomicBoolean(false);

    protected ForwardingStreamCallback(StreamCallback delegate) {
        // 保存业务回调作为下游，装饰器只补首正文与终态钩子，不另建一份业务输出。
        this.delegate = delegate;
    }

    /**
     * 首次正文触发一次首内容钩子，然后转发给下游；钩子异常不阻断正文推送。
     */
    @Override
    public final void onContent(String content) {
        // 首次正文通过 CAS 触发扩展钩子，后续正文只透传；钩子异常不会阻止这一片正文送达。
        if (firstContentSeen.compareAndSet(false, true)) {
            try {
                onFirstContent();
            } catch (Throwable ex) {
                // 钩子异常不能影响正常推流
            }
        }
        delegate.onContent(content);
    }

    /**
     * 直接转发思考增量，首正文钩子仍仅由 onContent 触发。
     */
    @Override
    public final void onThinking(String content) {
        // 思考增量原样转发，不触发 onFirstContent，因此首正文指标不会被思考片段提前结束。
        delegate.onThinking(content);
    }

    @Override
    public final void onReplyToMessageId(String messageId) {
        // 转发被回复的用户消息 ID，避免包装回调后丢失消息关联信息。
        delegate.onReplyToMessageId(messageId);
    }

    @Override
    public final void onSources(List<SourceRef> sources) {
        // 文档级来源直接透传给原业务回调，装饰层不重新编号或改写列表。
        delegate.onSources(sources);
    }

    @Override
    public final void onGroundingChunks(List<GroundingChunk> chunks) {
        // 推荐追问使用的证据片段也要透传，不能因加入追踪包装而丢失后续落库信息。
        delegate.onGroundingChunks(chunks);
    }

    /**
     * 流式响应到达「第一个 onContent」时触发一次，常用于记录用户感知首包 TTFT
     * 默认空实现
     */
    protected void onFirstContent() {
        // 预留给首正文计量等扩展，默认不执行任何额外动作。
    }

    /**
     * 先通知下游正常完成，再在 finally 中触发一次成功收尾钩子。
     */
    @Override
    public final void onComplete() {
        // 先通知业务正常完成，再用 finally 确保业务回调抛错时仍能进入本装饰器的终态收尾。
        try {
            delegate.onComplete();
        } finally {
            finishOnce(true, null);
        }
    }

    /**
     * 先通知下游异常，再在 finally 中触发一次失败收尾钩子。
     */
    @Override
    public final void onError(Throwable error) {
        // 把原异常交给业务回调，并在 finally 中以失败语义收尾，避免追踪节点长期处于运行中。
        try {
            delegate.onError(error);
        } finally {
            finishOnce(false, error);
        }
    }

    /**
     * 外部路径（如 cancel）触发收尾，不再透传 delegate
     */
    protected final void finishExternally(boolean success, Throwable error) {
        // 取消等外部路径只触发装饰层收尾，不再向业务下游额外发送一次完成或错误事件。
        finishOnce(success, error);
    }

    /**
     * 通过原子标志保证完成和错误回调竞争时，最终收尾钩子只执行一次。
     */
    private void finishOnce(boolean success, Throwable error) {
        // 完成、错误与外部取消共用同一原子标记，只有首个取得收尾权的路径能调用 onFinish。
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        // 只有赢得终态 CAS 的路径才能执行扩展收尾，其他路径已经在上面直接返回。
        onFinish(success, error);
    }

    /**
     * 流式终态收尾，仅会触发一次
     *
     * @param success 是否成功结束
     * @param error   失败时的异常，成功为 null
     */
    protected abstract void onFinish(boolean success, Throwable error);
}
