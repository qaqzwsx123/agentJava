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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunGateTest {

    private static final String USER_ID = "u-1001";
    private static final String TASK_ID = "t-9001";
    private static final String CONVERSATION_ID = "c-2001";
    private static final String SLOT_VALUE = TASK_ID + "|" + CONVERSATION_ID;

    private RedissonClient redissonClient;
    private RBucket<String> bucket;
    private AgentRunGate gate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bucket = mock(RBucket.class);
        redissonClient = mock(RedissonClient.class);
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        gate = new AgentRunGate(redissonClient, new AgentProperties());
    }

    @Test
    void shouldHoldSlotWithTtlFallback() {
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

        gate.acquire(USER_ID, TASK_ID, CONVERSATION_ID);

        // 进程崩溃时没人释放，TTL 是唯一出路：默认取 SSE 超时的两倍，改了超时这里会一起动
        verify(redissonClient).getBucket("ragent:agent:running:" + USER_ID);
        verify(bucket).setIfAbsent(SLOT_VALUE, Duration.ofMillis(1_800_000L));
    }

    @Test
    void shouldRejectSecondRunOfSameUser() {
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> gate.acquire(USER_ID, TASK_ID, CONVERSATION_ID))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("当前会话处理中");
    }

    @Test
    void shouldReleaseOnlyOwnSlot() {
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

        gate.acquire(USER_ID, TASK_ID, CONVERSATION_ID).run();

        // 闸门若被 TTL 挤掉又被下一轮抢走，无条件删会把别人的运行位放掉
        verify(bucket).compareAndSet(SLOT_VALUE, null);
    }

    @Test
    void shouldReportRunningTaskOfSameConversation() {
        when(bucket.get()).thenReturn(SLOT_VALUE);

        // 删会话要凭这个认出该停哪条流，运行位的取值格式不外泄
        assertThat(gate.runningTaskId(USER_ID, CONVERSATION_ID)).isEqualTo(TASK_ID);
    }

    @Test
    void shouldIgnoreRunningTaskOfOtherConversation() {
        when(bucket.get()).thenReturn(SLOT_VALUE);

        // 跑的是别的会话，删这个会话不该把那条流杀掉
        assertThat(gate.runningTaskId(USER_ID, "c-other")).isNull();
    }

    @Test
    void shouldReportNoRunningTaskWhenSlotEmpty() {
        when(bucket.get()).thenReturn(null);

        assertThat(gate.runningTaskId(USER_ID, CONVERSATION_ID)).isNull();
    }
}
