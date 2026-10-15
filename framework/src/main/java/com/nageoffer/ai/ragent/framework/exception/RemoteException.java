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
 * 远程服务调用异常
 * 比如订单调用支付失败，向上抛出的异常应该是远程服务调用异常
 */
public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        // 自定义文案默认归入第三方服务错误码，通过完整入口保持统一异常结构。
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    public RemoteException(String message, IErrorCode errorCode) {
        // 使用调用方指定的远程错误码和文案，未提供原因异常时传 null。
        this(message, null, errorCode);
    }

    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        // 把消息、原因异常和错误码交给基类，保留排查远程失败需要的异常链。
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        // 按统一格式展示远程错误码与业务文案，便于日志定位外部服务调用失败。
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
