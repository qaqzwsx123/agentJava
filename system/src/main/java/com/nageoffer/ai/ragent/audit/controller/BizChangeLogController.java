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

package com.nageoffer.ai.ragent.audit.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BizChangeLogController {

    private final BizChangeLogService bizChangeLogService;

    /**
     * 按业务类型、操作人等条件分页查询变更审计日志。
     * 查询对象包含筛选与分页条件，返回审计摘要列表。
     */
    @GetMapping("/biz-change-logs")
    public Result<IPage<BizChangeLogVO>> page(BizChangeLogPageRequest requestParam) {
        // 控制器只查询审计数据，不修改原始业务记录。
        return Results.success(bizChangeLogService.page(requestParam));
    }

    /**
     * 读取一条业务变更审计日志详情。
     * id 是日志 ID，详情可包含操作前后快照及错误信息。
     */
    @GetMapping("/biz-change-logs/{id}")
    public Result<BizChangeLogVO> get(@PathVariable String id) {
        // 找不到记录时由审计服务抛出业务异常。
        return Results.success(bizChangeLogService.get(id));
    }
}
