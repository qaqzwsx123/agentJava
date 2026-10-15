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

/**
 * Judge 的一条决策，描述的是「处理完本批之后该长什么样」而不是逐句事件
 *
 * @param action 新增 ADD、取代 SUPERSEDE 或撤回 RETRACT
 * @param targetId 被取代或撤回的旧条目 ID；新增时为 null
 * @param content 新增或取代后的正文；撤回时为 null
 */
public record AgentMemoryDecision(Action action, String targetId, String content) {

    /**
     * 只列真会改库的动作；协议里的 NOOP 在 AgentMemoryJudge.toDecision 就解析成 null，到不了这里
     */
    public enum Action {

        /**
         * 新事实
         */
        ADD,

        /**
         * 新事实取代旧条目，两步在同一事务里
         */
        SUPERSEDE,

        /**
         * 用户明确要求忘掉某条目
         */
        RETRACT
    }

    /**
     * 构造新增记忆决策，只携带新正文，不指定被替换的记忆 ID。
     * <p>
     * 返回 ADD 决策；这里只组装数据，正文长度与容量约束在提交时检查。
     */
    public static AgentMemoryDecision add(String content) {
        return new AgentMemoryDecision(Action.ADD, null, content);
    }

    /**
     * 构造替换记忆决策，将原记忆 ID 与替换后的正文关联。
     * <p>
     * 返回 SUPERSEDE 决策；旧条目失效与新条目插入由持久化层在同一事务执行。
     */
    public static AgentMemoryDecision supersede(String targetId, String content) {
        return new AgentMemoryDecision(Action.SUPERSEDE, targetId, content);
    }

    /**
     * 构造撤回记忆决策，只指定需要失效的条目，不生成新正文。
     * <p>
     * 返回 RETRACT 决策；实际撤回时仍需校验条目归属及有效状态。
     */
    public static AgentMemoryDecision retract(String targetId) {
        return new AgentMemoryDecision(Action.RETRACT, targetId, null);
    }

    /**
     * 会往记忆集里塞正文的两种动作，容量预演只看它们
     * <p>
     * 仅 ADD 和 SUPERSEDE 返回 true，供容量预演及正文合法性检查判断是否有新内容。
     */
    public boolean introducesContent() {
        return action == Action.ADD || action == Action.SUPERSEDE;
    }
}
