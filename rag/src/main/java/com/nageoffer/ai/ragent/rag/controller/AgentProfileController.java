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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体管理控制器
 */
@RestController
@RequiredArgsConstructor
public class AgentProfileController {
    // 智能体管理 HTTP 入口：提供档案列表、创建更新、激活和提示词槽位配置读写，供后台管理智能体。

    private final AgentProfileAdminService agentProfileAdminService;

    /**
     * 查询智能体列表
     * 查询 Agent 人设配置列表及当前激活项。
     * 返回人设概要和激活配置，供管理端展示与切换。
     */
    @GetMapping("/agents")
    public Result<AgentProfileListVO> list() {
        // 人设和激活状态由配置服务统一读取。
        return Results.success(agentProfileAdminService.list());
    }

    /**
     * 创建智能体
     * 创建一份 Agent 人设配置，返回新配置 ID。
     * 请求体包含名称和说明等配置，具体提示词通过独立接口维护。
     */
    @PostMapping("/agents")
    public Result<String> create(@RequestBody AgentProfileSaveRequest requestParam) {
        // 服务层校验配置后持久化，并记录业务变更快照。
        return Results.success(agentProfileAdminService.create(requestParam));
    }

    /**
     * 更新智能体名称与描述
     * 修改指定 Agent 人设配置的基本信息。
     * id 指定配置，请求体提供更新字段；不等同于激活该配置。
     */
    @PutMapping("/agents/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody AgentProfileSaveRequest requestParam) {
        // 人设编辑与运行时激活分开处理。
        agentProfileAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除智能体
     * 删除指定 Agent 人设配置。
     * id 是配置 ID，内置项或当前激活项能否删除由服务层约束。
     */
    @DeleteMapping("/agents/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 先通过业务规则校验，再执行配置删除。
        agentProfileAdminService.delete(id);
        return Results.success();
    }

    /**
     * 激活智能体，立即对全部会话生效
     * 切换当前使用的 Agent 人设配置。
     * id 是待激活配置 ID，后续提示词解析使用新的激活项。
     */
    @PostMapping("/agents/{id}/activate")
    public Result<Void> activate(@PathVariable String id) {
        // 服务层更新激活配置并清理相关缓存。
        agentProfileAdminService.activate(id);
        return Results.success();
    }

    /**
     * 查询该智能体的槽位配置，含槽位元数据与当前架构下的生效判定
     * 查询指定人设各提示词槽位的配置。
     * id 是人设 ID，结果包含槽位内容、编辑提示及当前模式是否生效等信息。
     */
    @GetMapping("/agents/{id}/prompts")
    public Result<AgentPromptConfigVO> prompts(@PathVariable String id) {
        // 提示词按槽位加载，便于管理端逐项编辑。
        return Results.success(agentProfileAdminService.loadPrompts(id));
    }

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     * 保存指定人设的一个提示词槽位。
     * id 定位人设，slotKey 指定提示词用途，请求体提供内容或回退设置。
     */
    @PutMapping("/agents/{id}/prompts/{slotKey}")
    public Result<Void> savePrompt(@PathVariable String id,
                                   @PathVariable String slotKey,
                                   @RequestBody AgentPromptSaveRequest requestParam) {
        // 服务层验证槽位和可编辑性，保存后让提示词缓存失效。
        agentProfileAdminService.savePrompt(id, slotKey, requestParam);
        return Results.success();
    }

    /**
     * 查询内置智能体的槽位内容，供「从默认复制」
     * 查询指定提示词槽位的系统默认文本。
     * slotKey 指定用途，供编辑器恢复默认值或展示默认内容。
     */
    @GetMapping("/agents/prompt-slots/{slotKey}/default")
    public Result<String> defaultPrompt(@PathVariable String slotKey) {
        // 读取默认内容，不改写当前人设的自定义提示词。
        return Results.success(agentProfileAdminService.defaultPrompt(slotKey));
    }
}
