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

package com.nageoffer.ai.ragent.agent.trace;

import io.opentelemetry.api.common.AttributeKey;

/**
 * OTel `error.type` 的全部取值，一律由错误发生的结构位置推导，与错误文案无关
 * <p>
 * 文案里带员工姓名、申请单号这类业务字段，解析它既撑爆基数也把 PII 漏进属性空间
 * 完整判定表见 06a 审计报告 ADR-10，要加取值先回那张表
 */
public enum AgentErrorTypes {

    /**
     * 工具自己报错：MCP 的 isError 与不带异常对象的 ERROR 结果都归这里，细分看 ragent.tool.error.source
     */
    TOOL_ERROR("tool_error"),

    /**
     * 被中断或被强制断流，span 确实没跑完
     */
    CANCELLED("cancelled"),

    /**
     * 上游超时触发的取消，工具侧接入超时后才可达
     */
    TIMEOUT("timeout");

    public static final AttributeKey<String> KEY = AttributeKey.stringKey("error.type");

    private final String value;

    AgentErrorTypes(String value) {
        // 保存用于追踪属性的固定错误取值，枚举名只用于 Java 代码表达。
        this.value = value;
    }

    public String value() {
        // 读取约定的属性值，避免把枚举的大写名称直接写入追踪后端。
        return value;
    }

    /**
     * 异常取全限定类名，形参是异常对象而非文案，从签名上堵死解析文案那条路
     */
    public static String ofThrowable(Throwable error) {
        // 空异常只可能是调用方失误，退回最钝的取值，不编一个类名
        return error == null ? TOOL_ERROR.value : error.getClass().getName();
    }

    /**
     * 取消的两种成因靠中断时刻是否写入区分，不看异常消息里有没有 timeout 字样
     */
    public static String ofCancelledSpan(Long terminationAt) {
        // 有业务中止事实就记 cancelled；没有该事实时按当前约定归为 timeout，不解析异常文案。
        return terminationAt == null ? TIMEOUT.value : CANCELLED.value;
    }
}
