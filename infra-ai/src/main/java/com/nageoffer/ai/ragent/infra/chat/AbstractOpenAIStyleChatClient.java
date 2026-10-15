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
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 提供商是否认识 enable_thinking
     * <p>
     * 该字段是 DashScope 系的私有扩展，OpenAI 兼容协议本身没有，发给不认识它的网关会被判为
     * unknown_parameter 直接 400，因此默认不发，由具体提供商声明
     */
    protected boolean supportsEnableThinkingParam() {
        return false;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：仅对认识该字段的提供商显式声明思考开关（Qwen3 系不显式关会默认开启思考）
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        if (supportsEnableThinkingParam()) {
            body.addProperty("enable_thinking", Boolean.TRUE.equals(request.getThinking()));
        }
    }

    /**
     * 是否要求提供商配置 API Key
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    /**
     * 构建同步聊天请求，按模型预算选择 HTTP 客户端并校验 HTTP 状态及响应正文。
     * 网络错误和协议错误转换为模型异常，供外层路由判断是否切换候选。
     */
    protected String doChat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

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

        return extractChatContent(respJson);
    }

    /**
     * 取按档位超时预算派生的同步客户端；timeoutMs 为空时用基础客户端（走 HttpClientConfig 默认超时）
     * <p>
     * connect/write 沿用基础客户端（请求体小、连接建立无需占用整段预算），仅覆盖 read/call
     */
    private OkHttpClient resolveSyncClient(Long timeoutMs) {
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
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

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
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
        }

        body.add("messages", buildMessages(request));

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
        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        if (requiresApiKey()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    /**
     * 逐层校验 choices、首条 message 和 content，正文为空白也视为无效响应，避免把空答案当作成功。
     */
    private String extractChatContent(JsonObject respJson) {
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
        String content = message.get("content").getAsString();
        if (content.isBlank()) {
            throw new ModelClientException(provider() + " 响应 content 为空白", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return content;
    }
}
