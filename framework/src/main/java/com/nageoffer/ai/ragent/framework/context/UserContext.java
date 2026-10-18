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

package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 用户上下文容器（基于 TTL 传递当前线程的登录用户）
 */
public final class UserContext {//将 LoginUser 绑定到当前线程，供业务代码随时读取:UserContext.getUsername() 获取操作人

    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        // 把登录用户快照绑定到当前线程；跨线程传递还需要使用支持 TTL 的执行方式。
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        // 读取当前线程绑定的用户，未绑定时返回 null，不在这里发起登录校验。
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        // 先读取用户快照；调用场景要求登录而上下文缺失时，立即抛出客户端异常。
        LoginUser user = CONTEXT.get();
        if (user == null) {
            throw new ClientException("未获取到当前登录用户");
        }
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        // 从当前快照读取用户 ID；未登录时返回 null，避免在可选身份场景中抛空指针。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        // 读取用户名，用户上下文缺失时保持 null，由调用方决定是否必须登录。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        // 读取当前角色快照；这里只取值，不执行角色权限判断。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 获取当前头像（未登录返回 null）
     */
    public static String getAvatar() {
        // 从登录快照取头像地址；没有用户上下文时返回 null。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getAvatar();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        // 请求收尾时移除当前线程的用户引用，防止线程池复用后把身份带入下一次请求。
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        // 仅检查是否绑定用户对象，不重新验证会话有效性或具体角色。
        return CONTEXT.get() != null;
    }
}
