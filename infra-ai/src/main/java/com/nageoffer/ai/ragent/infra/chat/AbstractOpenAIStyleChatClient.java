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
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议 ChatClient 抽象基类
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {
    // 兼容协议的公共实现模板。负责构造消息和请求参数、发送 HTTP、校验响应、读取 SSE、建立取消句柄，子类只补供应商差异。

    @Autowired
    private OkHttpClient syncHttpClient;
    @Autowired
    private OkHttpClient streamingHttpClient;
    @Autowired
    private Executor modelStreamExecutor;
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

    protected Gson gson = new Gson();

    /**
     * 按档位超时预算派生的同步客户端缓存（key=timeoutMs）
     * 档位超时值仅少数几种，派生客户端经 newBuilder 复用连接池/线程池，缓存后避免每次调用重建
     */
    private final Map<Long, OkHttpClient> syncClientByTimeout = new ConcurrentHashMap<>();

    // ==================== 子类钩子方法 ====================

    /**
     * 流式调用时是否启用 reasoning_content 解析，默认根据请求中的 thinking 标志决定
     */
    protected boolean isReasoningEnabledForStream(ChatRequest request) {
        // 只有 thinking 显式为 true 才解析 reasoning_content；未设置开关默认不向下游发送思考增量。
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 提供商是否认识 enable_thinking
     * <p>
     * 该字段是 DashScope 系的私有扩展，OpenAI 兼容协议本身没有，发给不认识它的网关会被判为
     * unknown_parameter 直接 400，因此默认不发，由具体提供商声明
     */
    protected boolean supportsEnableThinkingParam() {
        // 默认不发送供应商私有的 enable_thinking，具体适配器确认支持后再覆写开启。
        return false;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：仅对认识该字段的提供商显式声明思考开关（Qwen3 系不显式关会默认开启思考）
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        // 只向明确支持该扩展的提供商写思考开关；未请求思考时也显式发送 false。
        if (supportsEnableThinkingParam()) {
            body.addProperty("enable_thinking", Boolean.TRUE.equals(request.getThinking()));
        }
    }

    /**
     * 是否要求提供商配置 API Key
     */
    protected boolean requiresApiKey() {
        // 默认要求 API Key；本地 Ollama 等无需鉴权的实现可覆写这个钩子。
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    /**
     * 构建同步聊天请求，按模型预算选择 HTTP 客户端并校验 HTTP 状态及响应正文。
     * 网络错误和协议错误转换为模型异常，供外层路由判断是否切换候选。
     */
    protected String doChat(ChatRequest request, ModelTarget target) {
        // 1. 校验本次目标的提供商配置，再按适配器要求检查 API Key，配置错误在发请求前暴露。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 2. 构造同步协议体与鉴权请求，模型级 URL 和供应商端点选择集中在公共方法处理。
        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        // 3. 档位预算同时限制读取与整段同步调用；派生客户端复用基础连接资源。
        Call httpCall = resolveSyncClient(target.timeoutMs()).newCall(requestHttp);

        JsonObject respJson;
        try (Response response = httpCall.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " 同步请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 4. HTTP 成功不等于答案有效，仍需校验响应结构及非空白正文后才交给路由器记成功。
        return extractChatContent(respJson);
    }

    /**
     * 取按档位超时预算派生的同步客户端；timeoutMs 为空时用基础客户端（走 HttpClientConfig 默认超时）
     * <p>
     * connect/write 沿用基础客户端（请求体小、连接建立无需占用整段预算），仅覆盖 read/call
     */
    private OkHttpClient resolveSyncClient(Long timeoutMs) {
        // 无档位预算时复用基础客户端；有预算则按毫秒值缓存派生客户端，只覆盖 read/call 超时。
        if (timeoutMs == null) {
            return syncHttpClient;
        }
        return syncClientByTimeout.computeIfAbsent(timeoutMs, ms -> syncHttpClient.newBuilder()
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms, TimeUnit.MILLISECONDS)
                .build());
    }

    // ==================== 模板方法：流式调用 ====================

    /**
     * 构建带鉴权的流式请求，创建供应商追踪节点并把读取任务投递到模型流线程池。
     * 返回的取消动作同时取消 HTTP 上游并结算追踪节点；调用线程的节点栈在 finally 中恢复。
     */
    protected StreamCancellationHandle doStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 1. 校验供应商配置及可选鉴权，再构造带 stream=true 的 HTTP 请求，实际读取稍后交给线程池。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        // 2. 创建可取消的上游 Call，并固定本次是否分发思考增量，读取任务与取消句柄共享该 Call。
        Call call = streamingHttpClient.newCall(streamRequest);
        boolean reasoningEnabled = isReasoningEnabledForStream(request);

        // 在调用线程开 stream span，使后续 first-packet 子节点能正确归属父节点；
        // 该 span 由 SSE 终态（onComplete / onError）或 cancel 时收尾，记录真实端到端耗时
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            wrappedCallback = new StreamSpanCallback(callback, span);
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled)
            );
            return () -> {
                try {
                    // 3. 先取消上游，再在 finally 收尾追踪；取消动作抛错也不能留下运行中的节点。
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            // 同步部分结束：把节点从当前线程的 NODE_STACK 弹出，避免污染兄弟节点的父节点链
            span.detach();
        }
    }

    /**
     * 读取上游 SSE 行，解析正文、思考和完成信号，并转发至统一流回调。
     * 取消时退出且不再通知错误；未收到正常结束信号就断流时按无效响应报错，HTTP 响应退出时关闭。
     */
    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        provider() + " 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            BufferedSource source = body.source();
            boolean completed = false;
            // 逐行读取上游事件，cancelled 标记配合 HTTP Call.cancel 终止阻塞读取与后续分发。
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    // 协议解析器只提取字段；这里负责把思考、正文、完成信号转成业务回调。
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("{} 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            if (cancelled.get()) {
                log.info("{} 流式响应已被取消", provider());
                return;
            }
            // EOF 不等于协议正常完成，没有结束标志的提前断流需要交给错误回调。
            if (!completed) {
                throw new ModelClientException(provider() + " 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    // ==================== 公共构建方法 ====================

    /**
     * 组装模型名、消息及可选采样参数，按需启用 stream，最后调用子类扩展补充提供商字段。
     */
    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        // 1. 先写入本次目标的模型名，流式请求才额外设置 stream，消息仍由统一转换方法生成。
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
        }

        body.add("messages", buildMessages(request));

        // 2. 可选采样参数只在调用方给值时写入，缺省值交给供应商决定，避免把 null 当成显式配置。
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

        // 子类扩展只补提供商支持的私有参数，通用协议字段由本方法统一生成。
        customizeRequestBody(body, request);
        return body;
    }

    /**
     * 按输入顺序把模型消息转换为 OpenAI 兼容协议的 role 与 content 数组。
     */
    private JsonArray buildMessages(ChatRequest request) {
        // 保持输入消息顺序，只把角色和正文映射到协议字段，不把来源、思考耗时等业务元数据一并发送。
        JsonArray arr = new JsonArray();
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }
        return arr;
    }

    /**
     * 将内部系统、用户、助手角色映射为 OpenAI 兼容协议角色字符串。
     */
    private String toOpenAiRole(ChatMessage.Role role) {
        // 枚举到协议字符串集中映射，避免供应商子类各自拼写角色；本方法不为 null 角色提供缺省值。
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * 根据提供商和模型解析聊天 URL，按客户端要求附加 Bearer 鉴权头。
     */
    private Request.Builder newAuthorizedRequest(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        // 1. 模型级 URL 优先于供应商基础地址；鉴权是否添加沿用同一个 requiresApiKey 钩子。
        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        // 2. 本地无鉴权客户端可跳过 Bearer 头，其余实现必须与前面的密钥校验保持同一口径。
        if (requiresApiKey()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    /**
     * 逐层校验 choices、首条 message 和 content，正文为空白也视为无效响应，避免把空答案当作成功。
     */
    private String extractChatContent(JsonObject respJson) {
        // 1. 按 choices → 第一项 message → content 逐层校验；任一结构缺失都视为无效响应。
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException(provider() + " 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(provider() + " 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 2. 有字段但只返回空白也不是有效答案，抛出协议异常以便外层尝试其他候选。
        String content = message.get("content").getAsString();
        if (content.isBlank()) {
            throw new ModelClientException(provider() + " 响应 content 为空白", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return content;
    }
}
