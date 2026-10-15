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

package com.nageoffer.ai.ragent.sample.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.sample.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.sample.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 示例问题控制器
 * 前台各引擎欢迎页取随机若干条，后台走标准 CRUD
 */
@RestController
@RequestMapping("/sample-questions")
@RequiredArgsConstructor
public class SampleQuestionController {

    private final SampleQuestionService sampleQuestionService;

    /**
     * 随机获取示例问题列表，条数由调用方决定
     * 字面量段优先于 /{id} 模板匹配，两者不会打架
     * 随机读取首页示例问题，供用户快速发起问答。
     * limit 默认 3，服务层限制数量并只返回未删除的问题。
     */
    @GetMapping("/random")
    public Result<List<SampleQuestionVO>> listRandom(@RequestParam(defaultValue = "3") int limit) {
        // 随机展示与管理端分页维护使用不同查询方法。
        return Results.success(sampleQuestionService.listRandomQuestions(limit));
    }

    /**
     * 分页查询示例问题列表
     * 分页查询示例问题配置。
     * 查询对象包含关键字与分页条件，可匹配标题、描述或问题正文。
     */
    @GetMapping
    public Result<IPage<SampleQuestionVO>> pageQuery(SampleQuestionPageRequest requestParam) {
        // 用于后台维护示例问题，不触发真正的聊天请求。
        return Results.success(sampleQuestionService.pageQuery(requestParam));
    }

    /**
     * 查询示例问题详情
     * 读取一个示例问题的配置详情。
     * id 是示例问题记录 ID，供编辑表单回填。
     */
    @GetMapping("/{id}")
    public Result<SampleQuestionVO> queryById(@PathVariable String id) {
        // 记录不存在时由服务层统一报错。
        return Results.success(sampleQuestionService.queryById(id));
    }

    /**
     * 创建示例问题
     * 创建示例问题，返回数据库记录 ID。
     * 请求体提供问题正文、标题和描述，问题正文不能为空。
     */
    @PostMapping
    public Result<String> create(@RequestBody SampleQuestionCreateRequest requestParam) {
        // 示例问题合法性和变更快照由服务层处理。
        return Results.success(sampleQuestionService.create(requestParam));
    }

    /**
     * 更新示例问题
     * 修改已有示例问题。
     * id 定位记录，请求体提供待更新字段。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SampleQuestionUpdateRequest requestParam) {
        // 服务层更新数据库并记录修改前后状态。
        sampleQuestionService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除示例问题
     * 删除指定示例问题。
     * id 是示例问题记录 ID，成功后不再出现在首页候选列表中。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        // 仅删除问题配置，不影响用户已创建的聊天记录。
        sampleQuestionService.delete(id);
        return Results.success();
    }
}
