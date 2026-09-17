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

package com.nageoffer.ai.ragent.framework.exception;


import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端运行异常
 * 请求运行过程中出现的不符合业务预期的异常
 */
public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        // 未指定错误码时归入服务端基础错误，复用完整构造入口。
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(IErrorCode errorCode) {
        // 只给错误码时传入空消息，后续构造过程会采用该错误码的默认文案。
        this(null, errorCode);
    }

    public ServiceException(String message, IErrorCode errorCode) {
        // 保留自定义文案和错误码，原因链为空时也使用同一完整入口。
        this(message, null, errorCode);
    }

    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        // 消息为 null 时先采用错误码默认文案，再交基类保存消息、原因链与业务错误码。
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    @Override
    public String toString() {
        // 输出服务端异常的业务错误码和最终文案，堆栈及原因链仍由异常机制维护。
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
