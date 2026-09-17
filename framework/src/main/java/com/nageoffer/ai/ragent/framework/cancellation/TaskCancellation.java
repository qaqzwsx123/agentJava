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

package com.nageoffer.ai.ragent.framework.cancellation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.concurrent.CancellationException;

/**
 * 判定一次失败是不是用户取消，只服务于不该把取消记成故障的出口
 * 依据是中断标记与异常链：用户停止后运行句柄 dispose 上游，Reactor 随之中断执行线程
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskCancellation {

    /**
     * 下游降级返回而非抛出时，出口只剩中断标记可判
     */
    public static boolean isCancelled() {
        // 读取当前线程中断标记而不清除它，供已降级成空结果的调用出口识别用户停止。
        return Thread.currentThread().isInterrupted();
    }

    /**
     * 检查当前任务取消标记以及异常原因链中的中断、取消异常，避免把主动停止当作模型故障。
     */
    public static boolean isCancellation(Throwable error) {
        // 1. 优先读取线程中断标记；取消已发生时即使没有异常对象，也应按取消处理。
        if (isCancelled()) {
            return true;
        }
        // 2. 沿 cause 链检查被框架包装的中断或取消异常，普通业务异常仍按失败处理。
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 还原成取消异常，并补回被 InterruptedException 清掉的中断标记，否则上游判据失灵
     */
    public static CancellationException asCancellation(Throwable cause) {
        // 1. 遍历原因链寻找 InterruptedException，发现后补回线程中断标记，供更上层继续识别取消。
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 2. 最外层本身已是取消异常就直接复用，避免无意义地增加一层包装。
        if (cause instanceof CancellationException cancellation) {
            return cancellation;
        }
        // 3. 统一转换为取消异常并保留原始原因，供响应式上游按取消语义收尾。
        CancellationException cancellation = new CancellationException("Task cancelled");
        if (cause != null) {
            cancellation.initCause(cause);
        }
        return cancellation;
    }
}
