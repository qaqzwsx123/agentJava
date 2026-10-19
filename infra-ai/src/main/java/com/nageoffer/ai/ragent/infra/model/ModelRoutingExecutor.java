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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {
    // 依次取得候选客户端和健康许可，调用成功就返回，普通失败记录状态并尝试后续候选；任务取消直接向上传播，不计模型故障。

    private final ModelHealthStore healthStore;
    /**
     * 统一的路由执行器：负责遍历候选模型、健康检查、失败切换（Fallback）以及异常处理。
     * 按候选顺序取得客户端和健康许可，调用成功即返回，普通失败记录健康状态并继续尝试。
     * 任务取消直接向上传播，不计入模型故障、不尝试其他模型，并归还半开探测许可。
     * @param <C> Client 的类型（例如 ChatClient）
     * @param <T> 调用返回的结果类型（例如 String 或 StreamCancellationHandle）
     * @param capability 当前业务能力（比如 CHAT、EMBEDDING），用于日志打点和异常提示
     * @param targets    排好序的候选模型目标列表
     * @param clientResolver 函数式接口：给定一个 target，解析出对应的具体 Client 实例
     * @param caller         函数式接口：给定具体的 Client 和 target，执行真正的网络请求
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {

        // 1. 前置校验：如果候选列表为空，直接抛出异常，避免无意义的后续操作。
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null; // 用于记录最后一次失败的异常，方便最终抛出完整错误链

        // 2. 按优先级依次遍历候选模型（Fallback 的核心循环）
        for (ModelTarget target : targets) {
            // 2.1 解析 Client：根据目标模型的 provider 拿到具体的客户端（如 BaiLianChatClient）
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}",
                        label, target.candidate().getProvider(), target.id());
                continue; // 找不到客户端（可能是配置缺失），直接跳过，尝试下一个
            }

            // 2.2 健康检查（熔断机制）：
            // 注意这里的注释：“候选选择之后健康状态仍可能变化，执行前必须再原子取得许可”。
            // 如果该模型最近频繁报错，熔断器可能处于打开状态，这里会返回 null（拿不到调用许可）。
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue; // 模型正在熔断中，跳过，尝试下一个
            }

            try {
                // 3. 真正执行调用（通过 caller 函数式接口，调用 client.chat(...) 或 client.streamChat(...)）
                T response = caller.call(client, target);

                // 3.1 调用成功：标记该模型健康，直接返回结果，结束整个 Fallback 流程。
                healthStore.markSuccess(target.id());
                return response;

            } catch (Exception e) {
                // 4. 异常处理分支
                if (TaskCancellation.isCancellation(e)) {
                    // 4.1 如果是“任务取消”异常（比如用户在前端点击了“停止生成”）
                    // 这属于正常业务行为，不是模型故障。
                    // 归还半开探测许可，抛出取消异常（注意：这里直接 throw，不再继续尝试下一个模型）
                    healthStore.releaseHalfOpenPermit(permit);
                    throw TaskCancellation.asCancellation(e);
                }

                // 4.2 如果是真实的模型故障（超时、HTTP 500、网络断开等）
                // 记录最后一次异常，标记该模型为“失败”（触发熔断计数器），打印日志。
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}",
                        label, target.id(), target.candidate().getProvider(), e);
                // 循环继续，去尝试列表里的下一个模型
            }
        }

        // 5. 如果循环结束都没 return，说明所有候选模型要么找不到 Client、要么被熔断、要么全报错了。
        // 组装最终异常抛出，把最后一次的错误作为 cause 带出去。
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
