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

    private final ModelHealthStore healthStore;

    /**
     * 按候选顺序取得客户端和健康许可，调用成功即返回，普通失败记录健康状态并继续尝试。
     * 任务取消直接向上传播，不计入模型故障、不尝试其他模型，并归还半开探测许可。
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        // 1. 先确认至少有一个候选；没有目标时直接报告远程能力不可用，不尝试构造客户端。
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        for (ModelTarget target : targets) {
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            // 2. 候选选择之后健康状态仍可能变化，执行前必须再原子取得许可，不能只相信预筛结果。
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue;
            }

            try {
                // 具体同步调用由能力客户端完成，执行器统一维护成功、失败和候选切换。
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                if (TaskCancellation.isCancellation(e)) {
                    // 任务取消不是模型故障：不降级、不累计失败，半开探测只归还占用的名额
                    healthStore.releaseHalfOpenPermit(permit);
                    throw TaskCancellation.asCancellation(e);
                }
                // 3. 只有非取消异常才计模型失败并切换下一候选；保留最后原因以构造最终错误链。
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        // 4. 所有候选均失败、无客户端或被熔断时统一报告不可用，避免返回一个看似成功的空值。
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
