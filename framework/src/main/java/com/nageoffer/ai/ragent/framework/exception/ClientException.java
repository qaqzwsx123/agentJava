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

/**
 * 客户端异常
 * 用户发起调用请求后因客户端提交参数或其他客户端问题导致的异常
 */
public class ClientException extends AbstractException {

    public ClientException(IErrorCode errorCode) {
        // 仅提供错误码时使用它的默认文案，并复用完整构造入口，原因异常为空。
        this(null, null, errorCode);
    }

    public ClientException(String message) {
        // 只有自定义文案时归入客户端基础错误码，统一交给完整构造入口处理。
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(String message, IErrorCode errorCode) {
        // 保留自定义文案和指定错误码，未提供原因异常时传 null。
        this(message, null, errorCode);
    }

    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        // 由异常基类保存原因链，并处理自定义文案与错误码默认文案之间的取舍。
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        // 输出业务错误码和对外文案，方便日志识别异常类别；详细堆栈仍由异常对象本身保留。
        return "ClientException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
