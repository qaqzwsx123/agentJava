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

package com.nageoffer.ai.ragent.framework.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 源数据自动填充类
 */
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) { //插入时填充
        // 1. 插入时按严格填充规则（只有当实体类里的这个字段为 null 时，才会帮你填上。如果你自己代码里已经显式设置了值，它就不会覆盖）
        // 提供创建与更新时间，避免覆盖实体中已经显式设置的非空值。
        strictInsertFill(metaObject, "createTime", Date::new, Date.class);
        strictInsertFill(metaObject, "updateTime", Date::new, Date.class);
        // 2. 逻辑删除标志初始值为 0，后续查询与删除逻辑据此识别有效记录。
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) { //更新时填充
        // 更新时直接把 updateTime 设置为当前时刻，使本次修改有明确的最后更新时间。
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}