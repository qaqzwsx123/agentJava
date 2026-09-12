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

import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeBatchRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 意图树控制器
 * 提供意图节点树的查询、创建、更新和删除功能
 */
@RestController
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    /**
     * 获取完整的意图节点树
     * 读取完整意图树，供管理端展示和编辑。
     * 返回父子层级结构，节点类型决定其后续知识检索或工具调用用途。
     */
    @GetMapping("/intent-tree/trees")
    public Result<List<IntentNodeTreeVO>> tree() {
        // 树结构由服务层基于数据库节点统一组装。
        return Results.success(intentTreeService.getFullTree());
    }

    /**
     * 创建意图节点
     * 新增一个意图节点，返回节点 ID。
     * 请求体提供父节点、名称、类型和对应业务配置。
     */
    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        // 服务层校验意图标识与知识库绑定，再保存并刷新意图树缓存。
        return Results.success(intentTreeService.createNode(requestParam));
    }

    /**
     * 更新意图节点
     * 修改意图节点的名称、位置或业务配置。
     * id 指定节点，请求体提供修改字段；层级和类型约束由服务层校验。
     */
    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable String id, @RequestBody IntentNodeUpdateRequest requestParam) {
        // 返回类型为 void，业务失败通过异常处理链路反馈。
        intentTreeService.updateNode(id, requestParam);
    }

    /**
     * 删除意图节点
     * 删除指定意图节点及服务层定义的关联范围。
     * id 指定节点，涉及子节点时按意图树业务规则处理。
     */
    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        // 实际删除后刷新意图树缓存，后续匹配读取新结构。
        intentTreeService.deleteNode(id);
    }

    /**
     * 批量启用节点
     * 批量启用所选意图节点。
     * 请求体 ids 是节点 ID 集合，服务层负责有效节点和父子约束。
     */
    @PostMapping("/intent-tree/batch/enable")
    public void batchEnable(@RequestBody IntentNodeBatchRequest requestParam) {
        // 将目标集合交给意图树服务统一更新并清理缓存。
        intentTreeService.batchEnableNodes(requestParam.getIds());
    }

    /**
     * 批量停用节点
     * 批量停用所选意图节点。
     * 请求体 ids 是节点 ID 集合，子树处理规则由服务层决定。
     */
    @PostMapping("/intent-tree/batch/disable")
    public void batchDisable(@RequestBody IntentNodeBatchRequest requestParam) {
        // 停用影响后续意图匹配，不删除节点配置。
        intentTreeService.batchDisableNodes(requestParam.getIds());
    }

    /**
     * 批量删除节点
     * 批量删除显式选中的意图节点，要求所选范围包含完整子树。
     * 请求体 ids 是节点 ID 集合；遗漏任何后代节点时服务层拒绝删除。
     */
    @PostMapping("/intent-tree/batch/delete")
    public void batchDelete(@RequestBody IntentNodeBatchRequest requestParam) {
        // 批量操作集中处理数据库更新及缓存失效。
        intentTreeService.batchDeleteNodes(requestParam.getIds());
    }
}
