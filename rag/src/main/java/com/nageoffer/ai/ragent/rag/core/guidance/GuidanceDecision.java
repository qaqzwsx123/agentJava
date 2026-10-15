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

package com.nageoffer.ai.ragent.rag.core.guidance;

import lombok.Getter;

/**
 * 引导式问答决策结果类
 * 用于表示是否需要向用户输出引导式问答提示
 */
@Getter
public class GuidanceDecision {

    public enum Action {
        NONE,
        PROMPT
    }

    private final Action action;
    private final String prompt;

    private GuidanceDecision(Action action, String prompt) {
        // 保存澄清动作和对应提示文本，构造入口收敛到 none 与 prompt 工厂方法。
        this.action = action;
        this.prompt = prompt;
    }

    /**
     * 构造无需澄清的决策，不携带提示正文。
     */
    public static GuidanceDecision none() {
        // 表示当前不需要向用户澄清，提示文本保持为空。
        return new GuidanceDecision(Action.NONE, null);
    }

    /**
     * 构造需要向用户发送澄清提示的决策。
     */
    public static GuidanceDecision prompt(String prompt) {
        // 保存需要发送给用户的澄清提示，并标记为提示动作。
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    /**
     * 判断当前决策是否要求澄清，供对话流水线决定是否短路。
     */
    public boolean isPrompt() {
        // 只在动作明确为 PROMPT 时返回 true，供流水线决定是否提前输出澄清问题。
        return action == Action.PROMPT;
    }
}
