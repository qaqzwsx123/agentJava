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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 全局统一返回结果对象
 * 用于规范化所有 API 接口的返回格式，确保前后端交互的一致性
 * 所有接口返回都应使用此对象包装，避免不同开发人员定义不一致的返回结构
 * @param <T> 响应数据的类型
 */
@Data
@Accessors(chain = true)
//开启链式调用  一行代码搞定对象的赋值和初始化：
//        Result<String> result = new Result<String>()
//                .setCode("0")
//                .setMessage("success")
//                .setData("测试数据");
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 5679018624309023727L;

    /**
     * 成功状态码
     * 当接口请求成功时，返回此状态码
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 状态码
     * 标识请求的处理结果，{@code "0"} 表示成功，其他值表示各类错误或异常情况
     */
    private String code;

    /**
     * 响应消息
     * 对本次请求结果的文字描述，成功时可为成功提示，失败时为错误原因说明
     */
    private String message;

    /**
     * 响应数据
     * 接口返回的业务数据，类型由泛型 T 指定。请求失败时可能为 {@code null}
     */
    private T data;

    /**
     * 请求追踪 ID
     * 用于链路追踪和问题排查，每个请求具有唯一的标识符
     */
    private String requestId;

    /**
     * 判断请求是否成功
     * @return 如果状态码为 {@link #SUCCESS_CODE}，返回 {@code true}；否则返回 {@code false}
     */
    public boolean isSuccess() {
        // 从固定成功码一侧调用 equals，code 为 null 时也安全返回 false。
        return SUCCESS_CODE.equals(code);
    }
}
