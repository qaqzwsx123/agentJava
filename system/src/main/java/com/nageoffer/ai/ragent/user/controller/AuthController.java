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

package com.nageoffer.ai.ragent.user.controller;

import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * 处理用户登录和登出相关的请求
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录接口
     * 校验用户名密码并创建登录态，返回前端后续请求使用的 Token。
     * 请求体提供账号密码，响应包含用户 ID、角色、Token 和头像。
     */
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@RequestBody LoginRequest requestParam) {
        // 认证细节由 AuthService 处理，失败通过统一异常响应返回。
        return Results.success(authService.login(requestParam));
    }

    /**
     * 用户登出接口，清除用户的认证信息和会话
     * 注销当前登录态，使当前会话的认证信息失效。
     * 无需提交用户 ID，操作对象由认证上下文确定。
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        // 委托 Sa-Token 注销当前登录会话，再返回普通 JSON 成功响应。
        authService.logout();
        return Results.success();
    }
}
