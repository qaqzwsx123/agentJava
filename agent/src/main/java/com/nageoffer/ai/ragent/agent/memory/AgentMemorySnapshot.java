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

import java.util.List;

/**
 * 记忆视图快照：一次 agent call 内读一次，只管注入
 * 不带版本号——提交期双校验拿的是管道自己读的那份控制行，与模型看到的这份块无关
 *
 * @param items 供本次推理注入的有效记忆列表，空列表表示不注入记忆块
 */
public record AgentMemorySnapshot(List<AgentMemoryItem> items) {

    /**
     * 读库异常与长期记忆开关关闭共用这一个空值，调用方不必区分
     * <p>
     * 创建持有空条目列表的快照，供关闭长期记忆或读取失败时继续对话。
     */
    public static AgentMemorySnapshot empty() {
        return new AgentMemorySnapshot(List.of());
    }

    /**
     * 判断快照是否含有有效记忆条目，决定是否需要向本轮推理输入插入记忆块。
     */
    public boolean hasItems() {
        return !items.isEmpty();
    }
}
