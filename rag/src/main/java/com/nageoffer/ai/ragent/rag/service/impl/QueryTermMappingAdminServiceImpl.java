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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    private final QueryTermMappingMapper queryTermMappingMapper;
    private final QueryTermMappingCacheManager queryTermMappingCacheManager;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 校验原始词和目标词后新增术语映射。
     * 缺省匹配类型为 1、优先级为 0、状态为启用，保存后清理映射缓存。
     */
    @Override
    @LogRecord(
            success = "创建关键词映射：{{#requestParam.sourceTerm}}",
            fail = "创建关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(QueryTermMappingCreateRequest requestParam) {
        // 校验源词和目标词，补匹配方式、优先级和启用默认值，写库后清缓存并保存创建快照。
        // 先规范化必填词语，空白映射不允许写入数据库。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));

        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? (requestParam.getEnabled() ? 1 : 0) : 1);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));

        queryTermMappingMapper.insert(record);
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(String.valueOf(record.getId()), null, record);
        return String.valueOf(record.getId());
    }

    /**
     * 按非 null 字段局部修改术语映射规则。
     * 原词和目标词更新时不能为空，修改后刷新缓存并记录审计快照。
     */
    @Override
    @LogRecord(
            success = "更新关键词映射：{{#id}}",
            fail = "更新关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        // 保存修改前快照，只更新请求显式提供的字段，再清缓存并记录数据库中的最终结果。
        // 读取原记录供审计比对，再逐项应用请求中的变更字段。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        QueryTermMappingDO record = loadById(id);
        QueryTermMappingDO before = BeanUtil.copyProperties(record, QueryTermMappingDO.class);

        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }

        queryTermMappingMapper.updateById(record);
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(id, before, queryTermMappingMapper.selectById(id));
    }

    /**
     * 删除术语映射并清理运行时规则缓存。
     * 记录不存在时拒绝操作，审计上下文保留删除前配置。
     */
    @Override
    @LogRecord(
            success = "删除关键词映射：{{#id}}",
            fail = "删除关键词映射失败：{{#_errorMsg}}",
            type = BizChangeBizType.QUERY_TERM_MAPPING,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        // 先取得有效记录，避免对不存在的规则生成误导性删除快照。
        QueryTermMappingDO record = loadById(id);
        QueryTermMappingDO before = BeanUtil.copyProperties(record, QueryTermMappingDO.class);
        queryTermMappingMapper.deleteById(record.getId());
        queryTermMappingCacheManager.clearCache();
        bizChangeLogContext.put(id, before, null);
    }

    /**
     * 加载术语映射记录并转换为编辑视图。
     * 不存在的规则由 loadById 统一抛出异常。
     */
    @Override
    public QueryTermMappingVO queryById(String id) {
        // 详情查询只读，不触发运行时缓存清理。
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    /**
     * 按原始词或目标词关键字分页查询映射。
     * 规则按优先级升序、更新时间倒序返回，便于查看实际匹配先后。
     */
    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        // 源词和目标词关键词位于同一 OR 组内，按优先级升序与更新时间倒序分页展示。
        // 关键字条件在同一个 OR 分组内构造，不影响其他过滤范围。
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 加载术语映射规则，不存在时返回业务异常。
     */
    private QueryTermMappingDO loadById(String id) {
        // 按 ID 查规则，不存在时抛出业务错误，供更新或删除入口统一使用。
        QueryTermMappingDO record = queryTermMappingMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    /**
     * 将术语映射记录转换为响应，统一字符串 ID 和布尔启用状态。
     */
    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        // 把实体字段转为管理视图，整数启用标记转换为布尔值，ID 统一输出字符串。
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
