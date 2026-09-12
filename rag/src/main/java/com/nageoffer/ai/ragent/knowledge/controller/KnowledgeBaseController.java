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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     * 创建知识库，同时准备其文件存储空间和向量空间。
     * 请求体提供名称、集合名及嵌入模型，成功后返回知识库 ID。
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        // 具体重名校验和底层空间初始化由知识库服务处理。
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     * 修改指定知识库的展示名称。
     * kb-id 是路径中的知识库 ID；请求体 name 是新名称。
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        // 重命名不重新创建向量空间，名称合法性由服务层校验。
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库
     * 删除知识库，并提交底层资源的异步清理任务。
     * kb-id 标识知识库；仍包含有效文档时服务层会拒绝删除。
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        // 返回成功表示删除事务消息已处理，物理资源由消费者继续清理。
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     * 查询指定知识库的基本信息。
     * kb-id 是知识库 ID，返回名称、嵌入模型和集合信息等字段。
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        // 不存在或已删除的知识库由业务层统一报错。
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     * 按名称和分页条件查询知识库列表。
     * 查询参数包含页码、每页条数和可选名称，返回列表及文档数量。
     */
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        // 文档数量由服务层批量聚合，控制器不逐库发起查询。
        return Results.success(knowledgeBaseService.pageQuery(requestParam));
    }
}
