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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;

import java.util.List;

/**
 * 一次提交的全部入参：快照期取到的凭证连同 Judge 决策一起交给提交侧复核
 * merges 只在容量顶到上限那次非空，与决策在同一事务里落地
 *
 * @param userId 本次记忆变更所属用户
 * @param conversationId 产生待抽取消息的会话 ID
 * @param extractionId 已认领的抽取台账 ID，提交时需要一并结算
 * @param attemptCount 当前批次的尝试次数，用于台账结算与失败重试
 * @param expectedRevision 仲裁前读取的用户记忆版本，提交时检查是否已被其他抽取修改
 * @param expectedWatermark 仲裁前读取的会话消息水位，防止同一批消息重复应用
 * @param sourceType 新记忆的来源，区分后台抽取与主动沉淀
 * @param decisions 仲裁模型给出的新增、替换或撤回决策
 * @param merges 可选的容量合并计划；提交事务会再次校验成员与正文
 */
public record AgentMemoryCommit(String userId,
                                String conversationId,
                                String extractionId,
                                int attemptCount,
                                long expectedRevision,
                                String expectedWatermark,
                                AgentMemorySourceType sourceType,
                                List<AgentMemoryDecision> decisions,
                                List<AgentMemoryMerge> merges) {
}
