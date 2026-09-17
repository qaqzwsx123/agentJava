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

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG Trace 上下文
 * 使用 TTL 在异步线程池中透传 traceId 与节点栈
 */
public final class RagTraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    // 父线程 -> 子线程的 NODE_STACK 必须深拷贝
    // 默认 copy() 返回父值引用，并发子任务会共用同一个 Deque，
    // 互相 push/pop 时父子节点 ID 串挂，trace 层级紊乱
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
        @Override
        public Deque<String> copy(Deque<String> parentValue) {
            // 复制父线程当前的节点栈快照；节点 ID 字符串可共享，但可变 Deque 必须独立，防止子任务互相 push/pop。
            return parentValue == null ? null : new ArrayDeque<>(parentValue);
        }
    };

    private RagTraceContext() {
        // 上下文只通过静态方法操作，不创建独立容器实例。
    }

    /**
     * 读取当前线程绑定的追踪 ID；跨线程传播由调用方或上下文包装器负责。
     */
    public static String getTraceId() {
        // 从当前线程的 TTL 槽位取追踪 ID，尚未绑定时返回 null。
        return TRACE_ID.get();
    }

    /**
     * 绑定当前线程的追踪 ID，供本线程内嵌套节点记录关联运行。
     */
    public static void setTraceId(String traceId) {
        // 把本轮追踪 ID 绑定到当前线程，后续嵌套节点可用它归属同一条链路。
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        // 读取任务 ID 以关联流式任务；它与 traceId 分开存储，未设置时返回 null。
        return TASK_ID.get();
    }

    /**
     * 绑定当前线程的任务 ID，供追踪节点和日志关联本轮执行。
     */
    public static void setTaskId(String taskId) {
        // 绑定本轮流式任务 ID，供后续日志或节点记录引用。
        TASK_ID.set(taskId);
    }

    /**
     * 读取当前线程节点栈深度，供新追踪节点记录层级。
     */
    public static int depth() {
        // 没有节点栈时深度为 0，否则使用栈中节点数表示当前嵌套层级。
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    /**
     * 读取节点栈顶作为新节点的父节点 ID，没有栈时返回 null。
     */
    public static String currentNodeId() {
        // 只查看栈顶而不出栈；顶端节点就是新建嵌套节点应关联的父节点。
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    /**
     * 必要时创建当前线程的节点栈，再压入新节点，供后续嵌套调用确定父子关系。
     */
    public static void pushNode(String nodeId) {
        // 1. 当前线程首次进入追踪节点时懒创建独立栈，再把新节点 ID 放到栈顶。
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        // 2. 压栈后同步子调用就能读取这个 ID 作为父节点，退出该节点时需要对应出栈。
        stack.push(nodeId);
    }

    public static void popNode() {
        // 1. 没有栈或栈已空时直接返回，让重复清理不会抛出空栈异常。
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.pop();
        // 2. 最后一个节点弹出后移除 TTL 槽位，避免在复用线程上长期保留空容器。
        if (stack.isEmpty()) {
            NODE_STACK.remove();
        }
    }

    /**
     * 清除追踪 ID、任务 ID 和节点栈，避免线程池复用时串入下一次请求。
     */
    public static void clear() {
        // 同时移除追踪 ID、任务 ID 和节点栈，避免线程复用时残留上一条链路的信息。
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
