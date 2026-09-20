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

package com.nageoffer.ai.ragent.core.parser.mineru;

/**
 * MinerU 任务状态快照
 *
 * @param state        当前状态
 * @param zipUrl       结果 zip 下载 URL,仅 {@link MinerUTaskState#DONE} 时非空
 * @param errorMessage 失败原因,仅 {@link MinerUTaskState#FAILED} 时非空
 */
public record MinerUStatus(
        MinerUTaskState state,
        String zipUrl,
        String errorMessage
) {

    /**
     * 仅当远端任务状态为 DONE 时判定解析完成。
     */
    public boolean completed() {
        // 只有远端状态映射为 DONE 才视为成功完成。
        return state == MinerUTaskState.DONE;
    }

    /**
     * 仅当远端任务状态为 FAILED 时判定解析失败。
     */
    public boolean failed() {
        // 只有远端状态映射为 FAILED 才视为明确失败，其余状态继续由轮询决定。
        return state == MinerUTaskState.FAILED;
    }
}
