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
@Primary //RoutingLLMService 上的 @Primary 注解，就是将其指定为 LLMService 接口的默认实现，让系统在绝大多数情况下自动使用这个具备路由与故障转移能力的高级服务
public class RoutingLLMService implements LLMService {

    // LLMService 的主要实现。根据档位、思考开关和健康状态选择候选；同步调用通过执行器处理切换，流式调用还要等待首包探测。
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
        // 保存模型选择、健康状态和执行器依赖，再以 provider 建立客户端索引；重复 provider 会在建表时暴露。
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.firstPacketProbe = firstPacketProbe;
        this.clientsByProvider = clients.stream() //把 List<ChatClient>变成流，将流中的每个元素变成map， 转换成一个 Map<String, ChatClient>，其中 key 是每个客户端的 provider() 返回值，value 是客户端对象本身
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    /**
     * 按思考开关、可选档位及优先模型选择候选，再交给执行器依次尝试同步调用；无优先模型时使用普通档位选择。
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        // 使用默认档位规则选择候选，thinking 显式开启时仍优先走深度思考档，再交统一执行器切换失败候选。
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
        // 传入显式档位覆盖，但思考请求的档位选择仍由选择器统一决定，避免调用点各自实现优先级。
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
        // 未指定优先模型时直接复用档位重载；指定后先尝试该模型，再保留同档位的其余候选作为回退。
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
        // 按当前目标的 provider 查客户端；缺失时返回 null 并记录配置问题，外层跳过这个候选。
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
        // 1. 通过独立探测 Bean 记录首包等待耗时；成功会由桥接器提交已缓存的事件。
        try {
            return firstPacketProbe.awaitFirstPacket(bridge, firstPacketBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 2. 中断属于本次调用被停止，不记模型失败；保留中断标记，取消上游并归还半开许可。
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
        // 按探测结果区分上游报错、等待超时和无内容结束；保留具体原因，交给最终统一异常关联。
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
        // 用最后一次失败构造统一远程异常，先通知流式下游，再把同一异常返回给调用处抛出。
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }
}
