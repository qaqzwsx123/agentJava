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

package com.nageoffer.ai.ragent.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Agent 会话表，与 rag 的 t_conversation 系列两套分立
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_agent_conversation") //作用是告诉框架：这个 Java 类对应数据库里的哪张表
public class AgentConversationDO {

    @TableId(type = IdType.ASSIGN_ID) //字段 → 主键；ASSIGN_ID = 由 MyBatis-Plus 自动生成雪花 ID 赋值
    private String id;

    private String conversationId;

    private String userId;

    private String title;

    private Date lastTime;

    @TableField(fill = FieldFill.INSERT) //字段 → 插入时自动填充（如 createTime）
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE) //插入和更新时都自动填充（如 updateTime）
    private Date updateTime;

    @TableLogic //字段 → 逻辑删除（delete 时改为 update 标记，不物理删）
    private Integer deleted;
}
