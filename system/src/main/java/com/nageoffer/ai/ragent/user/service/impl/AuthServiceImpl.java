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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;

    /**
     * 校验登录参数和数据库账号，成功后创建 Sa-Token 登录态。
     * 响应携带账号 ID、角色、Token 和头像，头像为空时使用默认值。
     */
    @Override
    public LoginVO login(LoginRequest requestParam) {
        // 先检查账号密码是否为空，再查询未删除账号并核对当前存储的密码值。
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        if (user == null || !passwordMatches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        // 认证通过后登记登录态，随后从认证框架取得本次 Token。
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    /**
     * 注销当前认证会话。
     * 登录状态由 Sa-Token 管理，不根据请求参数选择其他账号。
     */
    @Override
    public void logout() {
        // 让认证框架失效当前 Token 对应的登录态。
        StpUtil.logout();
    }

    /**
     * 按用户名查找有效账号，供登录校验使用。
     * 空用户名直接返回 null，查询显式排除已删除记录。
     */
    private UserDO findByUsername(String username) {
        // 登录校验只接受尚未删除的用户记录。
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }

    /**
     * 按当前账号存储方式比较输入密码与数据库值。
     * 此处使用字符串相等比较；存储值为空时仅接受同样为空的输入。
     */
    private boolean passwordMatches(String input, String stored) {
        // 比较规则与现有数据保持一致，这里没有额外执行摘要或加密转换。
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }
}
