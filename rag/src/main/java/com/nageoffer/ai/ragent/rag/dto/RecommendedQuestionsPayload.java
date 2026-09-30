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
    // 推荐追问结果载荷：将生成状态与问题列表一起返回，区分生成成功、无合适问题和调用失败，供前端展示或重试。

    public RecommendedQuestionsPayload {
        // 序列化即弃的传输对象，只做 null 归一，不必防御性拷贝
        questions = questions == null ? List.of() : questions;
    }

    /**
     * 生成推荐成功响应；问题列表为空时统一返回 EMPTY 状态。
     */
    public static RecommendedQuestionsPayload success(List<String> questions) {
        // 非空问题列表标记成功，没有问题时统一转换为空结果状态。
        return questions == null || questions.isEmpty()
                ? empty()
                : new RecommendedQuestionsPayload(Status.SUCCESS, questions);
    }

    /**
     * 构造没有可用推荐问题的响应，与生成失败状态区分。
     */
    public static RecommendedQuestionsPayload empty() {
        // 构造正常但没有推荐问题的载荷，列表保持为空。
        return new RecommendedQuestionsPayload(Status.EMPTY, List.of());
    }

    /**
     * 构造推荐生成失败响应，问题列表保持为空，供前端决定展示与重试。
     */
    public static RecommendedQuestionsPayload failed() {
        // 构造推荐生成失败的载荷，与正常空结果明确区分。
        return new RecommendedQuestionsPayload(Status.FAILED, List.of());
    }

    public enum Status {
        // 推荐追问生成状态：标识有推荐、无推荐或生成失败等结果，与问题列表一起决定前端展示行为。
        SUCCESS,
        EMPTY,
        FAILED
    }
}
