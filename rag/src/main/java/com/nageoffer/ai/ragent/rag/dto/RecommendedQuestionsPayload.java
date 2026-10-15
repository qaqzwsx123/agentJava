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

package com.nageoffer.ai.ragent.rag.dto;

import java.util.List;

/**
 * 推荐追问生成结果
 */
public record RecommendedQuestionsPayload(Status status, List<String> questions) {

    public RecommendedQuestionsPayload {
        // 序列化即弃的传输对象，只做 null 归一，不必防御性拷贝
        questions = questions == null ? List.of() : questions;
    }

    /**
     * 生成推荐成功响应；问题列表为空时统一返回 EMPTY 状态。
     */
    public static RecommendedQuestionsPayload success(List<String> questions) {
        return questions == null || questions.isEmpty()
                ? empty()
                : new RecommendedQuestionsPayload(Status.SUCCESS, questions);
    }

    /**
     * 构造没有可用推荐问题的响应，与生成失败状态区分。
     */
    public static RecommendedQuestionsPayload empty() {
        return new RecommendedQuestionsPayload(Status.EMPTY, List.of());
    }

    /**
     * 构造推荐生成失败响应，问题列表保持为空，供前端决定展示与重试。
     */
    public static RecommendedQuestionsPayload failed() {
        return new RecommendedQuestionsPayload(Status.FAILED, List.of());
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        FAILED
    }
}
