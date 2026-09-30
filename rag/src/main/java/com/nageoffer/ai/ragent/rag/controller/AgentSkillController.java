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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillToolOptionVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillVO;
import com.nageoffer.ai.ragent.rag.service.AgentSkillAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体技能管理控制器
 */
@RestController
@RequiredArgsConstructor
public class AgentSkillController {
    // 智能体技能管理 HTTP 入口：提供技能分页、工具选项、详情和保存删除等操作，将业务校验交给技能管理服务。

    private final AgentSkillAdminService agentSkillAdminService;

    /**
     * 分页查询技能，不返回正文
     * 分页查询业务技能清单，供管理端维护技能。
     * 查询条件包含关键字和分页参数，列表与完整手册详情分开返回。
     */
    @GetMapping("/agent-skills")
    public Result<IPage<AgentSkillVO>> page(AgentSkillPageRequest requestParam) {
        // 读取技能管理视图，不会把技能加载到某个聊天上下文。
        return Results.success(agentSkillAdminService.pageQuery(requestParam));
    }

    /**
     * 可引用的工具选项，来自意图树里已启用的 MCP 节点
     * 列出技能可以关联的 MCP 工具选项。
     * 工具候选来自意图树中配置的 MCP 节点。
     */
    @GetMapping("/agent-skills/tool-options")
    public Result<List<AgentSkillToolOptionVO>> toolOptions() {
        // 这里只提供关联选项，实际工具是否可调用仍受运行时状态限制。
        return Results.success(agentSkillAdminService.listToolOptions());
    }

    /**
     * 查询技能详情，含正文
     * 查询一个业务技能的完整手册及关联工具。
     * id 是数据库技能 ID，不是模型调用 load_skill 使用的 skillCode。
     */
    @GetMapping("/agent-skills/{id}")
    public Result<AgentSkillVO> detail(@PathVariable String id) {
        // 按管理记录 ID 加载详情，用于编辑 Markdown 手册。
        return Results.success(agentSkillAdminService.queryById(id));
    }

    /**
     * 创建技能
     * 创建业务技能，配置适用场景、手册正文及解锁工具。
     * 请求体中的 skillCode 是模型加载标识，toolIds 是需要手册解锁的工具。
     */
    @PostMapping("/agent-skills")
    public Result<String> create(@RequestBody AgentSkillSaveRequest requestParam) {
        // 服务层验证标识和工具来源，保存后清理启用技能缓存。
        return Results.success(agentSkillAdminService.create(requestParam));
    }

    /**
     * 更新技能，技能标识不可改
     * 修改已有技能的场景说明、正文或关联工具。
     * id 定位技能记录，请求体提供修改字段。
     */
    @PutMapping("/agent-skills/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody AgentSkillSaveRequest requestParam) {
        // 更新后的启用技能清单由注册表在缓存失效后重新读取。
        agentSkillAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除技能，它解锁的工具若不再挂在其它启用技能下，就恢复常驻可见
     * 删除一条业务技能配置。
     * id 是技能记录 ID；删除后运行时清单不再包含该技能。
     */
    @DeleteMapping("/agent-skills/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 业务层删除配置并清理缓存，不在控制器注销共享 Toolkit 工具。
        agentSkillAdminService.delete(id);
        return Results.success();
    }

    /**
     * 启用或停用技能
     * 切换业务技能是否出现在运行时启用清单。
     * enabled=true 表示启用，false 表示停用，id 指定技能记录。
     */
    @PostMapping("/agent-skills/{id}/enabled")
    public Result<Void> toggleEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        // 启停技能会刷新技能缓存，不替代业务工具自身的权限校验。
        agentSkillAdminService.toggleEnabled(id, enabled);
        return Results.success();
    }
}
