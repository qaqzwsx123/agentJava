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

package com.nageoffer.ai.ragent.agent.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户维度的 Agent 并发闸门：一个用户同一时刻只跑一条流
 * 与 @IdempotentSubmit 的区别是覆盖整个流生命周期，而非控制器返回 emitter 前的同步窗口
 */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentRunGate {

    private static final String RUNNING_KEY_PREFIX = "ragent:agent:running:";

    /**
     * 运行位存 taskId|conversationId：删会话时要凭它认出在跑的是不是这一个，两段都是雪花数字串，不含竖线
     */
    private static final String SLOT_SEPARATOR = "|";

    private final RedissonClient redissonClient;
    private final AgentProperties agentProperties;

    /**
     * 抢运行位，抢不到直接拒绝；返回的释放动作由调用方挂到收尾路上
     * userId 决定竞争范围，taskId 与 conversationId 组成占用值，用于定位运行及校验释放归属。
     * 启动失败由 cleanupFailedStart 执行回调；成功启动后由 AgentRunHandle 的释放钩子执行。
     * Redis 的 TTL 用于无人收尾时的兜底过期，本方法没有启动续期任务。
     */
    public Runnable acquire(String userId, String taskId, String conversationId) {
        // 值同时记录任务和会话；键只使用 userId，因此该用户跨会话共用一个运行位。
        String slotValue = taskId + SLOT_SEPARATOR + conversationId;
        //是按 userId，不是按 conversationId。也就是一个用户全局只能跑一个 Agent，而不是"每个会话各一个"。
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));  //拿到 Redis 桶对象
        // SET NX 与过期时间一次提交，多个节点竞争时只有一个请求取得名额。
        if (!slot.setIfAbsent(slotValue, ttl())) {
            throw new ClientException("当前会话处理中，请稍后再发起新的对话");
        }
        //   () -> release(userId, slotValue) 的意思是： 创建一个 Runnable 对象，它的 run() 方法被调用时，会执行 release(userId, slotValue)。
        return () -> release(userId, slotValue); // 把一段逻辑打包成一个对象返回出去，等以后需要的时候再run()执行。返回类型是 Runnable
    }

    /**
     * 该用户此刻正跑的流若属于这个会话，返回它的 taskId，否则返回 null
     * 运行位的取值格式只有闸门自己知道，外部拿到的始终是 taskId
     */
    public String runningTaskId(String userId, String conversationId) {
        // 根据 userId 拿到该用户对应的 Redis 运行位（Bucket）
        // key 形如 runningKey(userId)，存储的值格式为：taskId + SLOT_SEPARATOR + conversationId
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        // 读取运行位当前的值；如果为空，说明该用户当前没有正在跑的任务
        String slotValue = slot.get();
        if (StrUtil.isBlank(slotValue)) {
            return null;
        }
        // 运行位的值由闸门自己写入，外部只关心 taskId，因此需要按分隔符解析
        // 查找分隔符的位置，分隔符前是 taskId，分隔符后是 conversationId
        int separator = slotValue.indexOf(SLOT_SEPARATOR);
        // 两种情况都返回 null：
        // 1. separator < 0：值格式异常，没有分隔符，无法解析；
        // 2. 分隔符后的 conversationId 与传入的 conversationId 不匹配：
        //    说明当前用户正在跑的任务属于另一个会话，不能误判为当前会话的任务。
        if (separator < 0 || !slotValue.substring(separator + 1).equals(conversationId)) {
            return null;
        }
        // 走到这里说明运行位属于当前会话，返回分隔符前面的 taskId
        return slotValue.substring(0, separator);
    }

    /**
     * 只放自己占的位：运行位若被 TTL 挤掉又被下一轮抢走，无条件删会把别人的闸门放掉
     * 重复调用天然安全，值对不上就是空操作
     */
    private void release(String userId, String slotValue) {
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        // 只有当前值仍属于本轮才清空；TTL 后已被下一轮占用时，此处不会释放新任务的名额。
        slot.compareAndSet(slotValue, null);
    }

    /**
     * 进程崩溃时没人来释放，TTL 是唯一出路
     * 取配置的 SSE 超时时间的两倍作为运行位租期；正常情况下由收尾主动释放。
     */
    private Duration ttl() {
        return Duration.ofMillis(agentProperties.getSseTimeoutMs() * 2);
    }

    /**
     * 按用户 ID 构造 Redis 运行位键，同一用户的不同会话共用一个运行名额。
     */
    private String runningKey(String userId) {
        return RUNNING_KEY_PREFIX + userId;
    }
}
