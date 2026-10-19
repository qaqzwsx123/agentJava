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
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关键词映射管理控制器
 */
@RestController
@RequiredArgsConstructor
public class QueryTermMappingController {
    // 术语映射管理 HTTP 入口：提供规则分页、详情、新增、更新、删除和启停，供后台维护问题归一化词表。

    private final QueryTermMappingAdminService queryTermMappingAdminService;

    /**
     * 分页查询映射规则
     * 分页查询检索词映射规则。
     * 查询对象包含关键字与分页条件，供管理端筛选规则。
     */
    @GetMapping("/mappings")
    public Result<IPage<QueryTermMappingVO>> pageQuery(QueryTermMappingPageRequest requestParam) {
        // 这里只查询规则，不执行用户问题改写。
        return Results.success(queryTermMappingAdminService.pageQuery(requestParam));
    }

    /**
     * 查询映射规则详情
     * 读取一条检索词映射规则详情。
     * id 是映射记录 ID，返回原词、标准词及启用状态等配置。
     */
    @GetMapping("/mappings/{id}")
    public Result<QueryTermMappingVO> queryById(@PathVariable String id) {
        // 不存在的记录由规则服务统一报错。
        return Results.success(queryTermMappingAdminService.queryById(id));
    }

    /**
     * 创建映射规则
     * 新增检索词映射，用于将用户用词归一到配置词。
     * 请求体提供映射内容和状态，返回新记录 ID。
     */
    @PostMapping("/mappings")
    public Result<String> create(@RequestBody QueryTermMappingCreateRequest requestParam) {
        // 服务层校验原词和目标词，并在保存后刷新规则缓存。
        return Results.success(queryTermMappingAdminService.create(requestParam));
    }

    /**
     * 更新映射规则
     * 修改指定检索词映射规则。
     * id 指定规则，请求体提供待更新字段。
     */
    @PutMapping("/mappings/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody QueryTermMappingUpdateRequest requestParam) {
        // 管理端更新与运行时缓存刷新由同一业务方法处理。
        queryTermMappingAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除映射规则
     * 删除指定检索词映射规则。
     * id 是规则记录 ID，删除后不再用于后续术语归一。
     */
    @DeleteMapping("/mappings/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 服务层清理规则记录并使运行时缓存失效。
        queryTermMappingAdminService.delete(id);
        return Results.success();
    }
}
