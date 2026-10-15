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

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.CurrentUserVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制器
 * 提供当前登录用户信息查询接口
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户信息
     * 查询当前登录用户的身份信息，供页面展示与权限界面初始化。
     * 用户身份取自登录上下文，响应只包含用户 ID、用户名、角色和头像。
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        // requireUser 要求已登录，不允许前端通过参数指定其他用户。
        LoginUser user = UserContext.requireUser();
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getAvatar()
        ));
    }

    /**
     * 分页查询用户列表
     * 管理员按条件分页查询用户列表。
     * 查询参数提供用户名、角色等筛选项；响应不包含密码字段。
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        // 先检查 admin 角色，再调用用户管理服务查询。
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建用户
     * 管理员创建用户账号，成功后返回新用户 ID。
     * 请求体提供用户名、密码、角色和头像等信息。
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        // 先验证管理员角色，用户名重复和角色合法性由业务层校验。
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户
     * 管理员修改指定用户的账号信息。
     * id 是用户 ID，请求体只更新已提供的字段；默认管理员受保护。
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
        // 角色校验通过后再执行业务更新，避免普通用户操作管理接口。
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     * 管理员删除指定用户账号。
     * id 指定用户，默认管理员不允许删除。
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 权限和默认管理员保护分别由控制器与服务层处理。
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前用户密码
     * 修改当前登录用户自己的密码。
     * 请求体包含当前密码和新密码，不接受待修改用户 ID。
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        // 服务层根据登录身份查询账号并校验原密码。
        userService.changePassword(requestParam);
        return Results.success();
    }
}
