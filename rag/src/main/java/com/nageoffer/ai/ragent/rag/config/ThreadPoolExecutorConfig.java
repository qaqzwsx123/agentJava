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

package com.nageoffer.ai.ragent.rag.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 线程池执行器配置类
 * 为系统中不同的业务场景配置独立的线程池，提高并发处理能力
 */
@Configuration
public class ThreadPoolExecutorConfig {
    // 线程池执行器配置类 为系统中不同的业务场景配置独立的线程池，提高并发处理能力。

    /**
     * CPU核心数，用于动态计算线程池大小
     */
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * RAG 上下文处理线程池，按子问题并行检索
     */
    @Bean
    public Executor ragContextExecutor() {
        // 上下文任务使用直接交接队列，饱和时由提交线程执行，并包装 TTL 以传播线程上下文。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 2,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_context_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG 检索线程池（用于通道级别的并行）
     */
    @Bean
    public Executor ragRetrievalExecutor() {
        // 为外层检索配置独立线程池，直接交接并在饱和时回到调用线程，TTL 包装保留调用上下文。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 2,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG 内部检索线程池
     */
    @Bean
    public Executor innerRetrievalExecutor() {
        // 内部并行检索使用有界队列和可扩展线程数，饱和后由调用线程执行，限制额外排队规模。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 1,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_inner_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 意图识别并行执行线程池
     */
    @Bean
    public Executor intentClassifyExecutor() {
        // 意图分类线程数按 CPU 配置，采用直接交接与调用线程回退，避免无限积压分类任务。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("intent_classify_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 对话记忆摘要生成线程池
     */
    @Bean
    public Executor memorySummaryExecutor() {
        // 摘要任务使用较小线程池与有界队列，饱和时由提交线程承担，TTL 保留用户和追踪上下文。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                Math.max(2, CPU_COUNT >> 1),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("memory_summary_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 模型流式输出线程池
     */
    @Bean
    public Executor modelStreamExecutor() {
        // 模型流使用独立有界线程池，饱和时明确拒绝，由上层处理，不在提交线程里阻塞执行长流。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("model_stream_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * SSE 排队后执行入口线程池
     */
    @Bean
    public Executor chatEntryExecutor(RAGRateLimitProperties rateLimitProperties) {
        // 线程数与全局聊天并发一致，直接交接且饱和拒绝；允许核心线程超时，避免空闲时长期占用线程。
        int size = rateLimitProperties.getGlobalMaxConcurrent();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size,
                size,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("chat_entry_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 知识库文档分块线程池
     */
    @Bean
    public Executor knowledgeChunkExecutor() {
        // 文档分块使用独立有界队列和拒绝策略，超载时交给调用方处理，避免无限堆积大文件任务。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("kb_chunk_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 对话记忆加载线程池（并行加载摘要与历史记录）
     */
    @Bean
    public Executor memoryLoadExecutor() {
        // 历史加载使用独立有界线程池，饱和时在调用线程执行，所有任务继续使用 TTL 传播上下文。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("memory_load_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }
}
