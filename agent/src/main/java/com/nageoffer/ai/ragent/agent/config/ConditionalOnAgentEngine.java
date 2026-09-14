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

package com.nageoffer.ai.ragent.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 执行架构条件开关：ragent.engine.type=agent 时装配，终身制部署下 workflow 侧零开销
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD}) //这个注解可以标在类或方法上
@Retention(RetentionPolicy.RUNTIME) //运行时保留，Spring 才能通过反射读到它
@ConditionalOnProperty(prefix = "ragent.engine", name = "type", havingValue = "agent")
//检查配置文件里有没有 ragent.engine.type 这个属性，并且它的值是不是 agent。
//      是 → 条件成立，装配这个 Bean。
//    不是或没配置 → 条件不成立，不装配。
//这个 @ConditionalOnAgentEngine 是一个自定义的 Spring 条件注解。它的核心作用是：
//    当配置项 ragent.engine.type=agent 时，被它标记的类或方法才会被 Spring 容器装配（创建 Bean）；否则直接跳过，不加载。
public @interface ConditionalOnAgentEngine {
}
