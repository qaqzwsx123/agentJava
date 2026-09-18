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

package com.nageoffer.ai.ragent.infra.http;

import lombok.Getter;

/**
 * 模型客户端异常类
 * 用于封装模型调用过程中的各类异常信息
 */
@Getter
public class ModelClientException extends RuntimeException {

    /**
     * 错误类型
     */
    private final ModelClientErrorType errorType;

    /**
     * HTTP状态码
     */
    private final Integer statusCode;

    /**
     * 构造带原因的模型客户端异常
     *
     * @param message    异常消息
     * @param errorType  错误类型
     * @param statusCode HTTP状态码
     * @param cause      原始异常
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode, Throwable cause) {
        // 保留原始消息与原因链，并单独记录错误类别和可空 HTTP 状态，便于上层统一处理模型失败。
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * 构造模型客户端异常
     *
     * @param message    异常消息
     * @param errorType  错误类型
     * @param statusCode HTTP状态码
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode) {
        // 没有底层原因时只保存协议错误信息、类别和 HTTP 状态，例如已收到明确的非成功响应。
        super(message);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }
}
