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

package com.nageoffer.ai.ragent.agent.enums;

/**
 * 抽取状态，哪几个值推进水位见 AgentMemoryExtractionMapper.selectWatermark
 */
public enum AgentMemoryExtractionStatus {

    /**
     * 在飞，部分唯一索引保证同会话只有一条
     */
    PROCESSING,

    /**
     * 判完有写入
     */
    WRITTEN,

    /**
     * 判完无产出，同样算处理过
     */
    NOOP,

    /**
     * 重试耗尽或容量拒收，坏抽取不许永久堵塞水位
     */
    DROPPED,

    /**
     * 提交期快照失配，本批作废重来，不计入尝试次数
     */
    CONFLICT
}
