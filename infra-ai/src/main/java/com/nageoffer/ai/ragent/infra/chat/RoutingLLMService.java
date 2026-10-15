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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final LlmFirstPacketProbe firstPacketProbe;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            LlmFirstPacketProbe firstPacketProbe,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.firstPacketProbe = firstPacketProbe;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    /**
     * 按思考开关、可选档位及优先模型选择候选，再交给执行器依次尝试同步调用；无优先模型时使用普通档位选择。
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking())),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 按思考开关、可选档位及优先模型选择候选，再交给执行器依次尝试同步调用；无优先模型时使用普通档位选择。
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request, Tier tier) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()), tier),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 按思考开关、可选档位及优先模型选择候选，再交给执行器依次尝试同步调用；无优先模型时使用普通档位选择。
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request, Tier tier, String preferredModelId) {
        if (!StringUtils.hasText(preferredModelId)) {
            return chat(request, tier);
        }
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()), tier, preferredModelId),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 依次尝试健康候选模型，等待首包探测成功后返回取消句柄。
     * 启动失败、首包超时或无正文结束会取消该候选并尝试下一个；全部失败时通知下游错误。
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        // 逐个尝试候选，只有获取健康许可的模型才会真正发起请求。
        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue;
            }

            // 首包探测阶段先隔离候选的回调，避免失败候选的正文或错误直接污染下游。
            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, bridge, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            // 预算用于首包等待；成功后返回上游句柄，后续流事件由桥接回调继续处理。
            long firstPacketBudgetMs = target.timeoutMs();
            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback, firstPacketBudgetMs, permit);

            if (result.isSuccess()) {
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 失败处理
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(callback, lastError);
    }

    /**
     * 按候选模型的 provider 查找聊天客户端，缺失时记录日志并返回 null，让外层跳过该候选。
     */
    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    /**
     * 在候选模型预算内等待首包结果；线程中断时取消上游、归还半开名额并通知下游错误。
     */
    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                           StreamCancellationHandle handle,
                                                           StreamCallback callback,
                                                           long firstPacketBudgetMs,
                                                           ModelHealthStore.CallPermit permit) {
        try {
            return firstPacketProbe.awaitFirstPacket(bridge, firstPacketBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                handle.cancel();
            } finally {
                healthStore.releaseHalfOpenPermit(permit);
            }
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    /**
     * 将首包探测的错误、超时或无正文结果转为具体异常，保留失败原因供最终异常链使用。
     */
    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    /**
     * 构造所有候选失败的统一异常，通知流式回调并返回该异常供调用方抛出。
     */
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }
}
