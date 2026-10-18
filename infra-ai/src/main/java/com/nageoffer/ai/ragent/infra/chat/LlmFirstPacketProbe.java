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

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 把 awaitFirstPacket 拆为独立 bean，便于 AOP 采集 TTFT trace
 * <p>
 * Spring AOP 不拦截类内 self-call，因此 RoutingLLMService 必须依赖外部 bean
 * 调用此方法，{@code @RagTraceNode} 才会生效
 */
@Component
public class LlmFirstPacketProbe {
    // 等待首包的独立 Spring Bean。让首包等待过程可被 AOP 记录，避免同类内部调用无法触发追踪切面的问题。

    /**
     * 委托探测桥在指定时间预算内等待首包、错误或无内容结束信号，并保留线程中断语义。
     */
    @RagTraceNode(name = "llm-first-packet", type = "LLM_TTFT")
    public ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                          long timeout,
                                                          TimeUnit unit) throws InterruptedException {
        // 通过独立 Spring Bean 进入等待逻辑，使首包探测可被 AOP 记录；线程中断继续向调用方传播。
        return bridge.awaitFirstPacket(timeout, unit);
    }
}
