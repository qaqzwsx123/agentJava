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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 分页查询有效用户，并将记录转换为不含密码的管理视图。
     * keyword 同时匹配用户名或角色，结果按更新时间倒序排列。
     */
    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        // 先规范化关键字，再构造可选的组合查询条件。
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 创建普通管理账号，校验用户名、密码、角色及名称唯一性。
     * 默认管理员名称不允许占用，创建后登记审计快照并返回用户 ID。
     */
    @Override
    @LogRecord(
            success = "创建用户：{{#requestParam.username}}",
            fail = "创建用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(UserCreateRequest requestParam) {
        // 先完成参数归一和必填校验，避免保存只有空白字符的账号。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        // 角色只能取系统支持的值；用户名唯一性检查排除逻辑删除账号。
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        bizChangeLogContext.put(String.valueOf(record.getId()), null, toVO(record));
        return String.valueOf(record.getId());
    }

    /**
     * 按请求中非 null 字段局部修改用户信息。
     * 先保护默认管理员，再校验变更用户名与角色，审计记录保存修改前后视图。
     */
    @Override
    @LogRecord(
            success = "更新用户：{{#id}}",
            fail = "更新用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, UserUpdateRequest requestParam) {
        // 读取原记录并保留快照，后续字段为空与字段未提交采用不同语义。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        UserVO before = toVO(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        userMapper.updateById(record);
        bizChangeLogContext.put(id, before, toVO(userMapper.selectById(id)));
    }

    /**
     * 删除指定有效用户，并登记删除前的审计快照。
     * 默认管理员受到保护；用户不存在时由 loadById 抛出异常。
     */
    @Override
    @LogRecord(
            success = "删除用户：{{#id}}",
            fail = "删除用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        // 删除前先检查账号存在性和默认管理员保护规则。
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        UserVO before = toVO(record);
        userMapper.deleteById(record.getId());
        bizChangeLogContext.put(id, before, null);
    }

    /**
     * 校验当前用户原密码后更新新密码。
     * 目标账号来自登录上下文，不能通过入参指定他人的用户 ID。
     */
    @Override
    @LogRecord(
            success = "修改当前用户密码",
            fail = "修改当前用户密码失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void changePassword(ChangePasswordRequest requestParam) {
        // 先校验原密码、新密码均非空，再读取当前登录账号。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        UserVO before = toVO(record);
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
        bizChangeLogContext.put(loginUser.getUserId(), before, toVO(userMapper.selectById(loginUser.getUserId())));
    }

    /**
     * 查询尚未删除的用户记录。
     * 记录不存在时统一抛出业务异常，避免后续更新空记录。
     */
    private UserDO loadById(String id) {
        // 用户管理操作复用此查询，统一有效记录的判定方式。
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    /**
     * 保护系统默认管理员不被普通管理操作修改或删除。
     * 传入的是已查询的用户记录，按用户名进行不区分大小写的判断。
     */
    private void ensureNotDefaultAdmin(UserDO record) {
        // 系统默认账号命中保护规则时直接拒绝本次操作。
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    /**
     * 校验目标用户名没有被其他有效账号占用。
     * 更新场景用 excludeId 排除当前记录，创建场景不排除任何账号。
     */
    private void ensureUsernameAvailable(String username, String excludeId) {
        // 唯一性查询只针对未删除账号，避免把自身记录误判为重复。
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    /**
     * 把角色配置归一为系统认可的管理员或普通用户编码。
     * 未提供角色时使用普通用户，其他未知角色会被拒绝。
     */
    private String normalizeRole(String role) {
        // 角色值先去除首尾空格，再按允许集合匹配。
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    /**
     * 构造用户管理接口使用的展示对象。
     * 只输出 ID、用户名、角色、头像和时间信息，不包含数据库密码字段。
     */
    private UserVO toVO(UserDO record) {
        // 持久化对象与响应对象分离，避免直接返回完整账号记录。
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
