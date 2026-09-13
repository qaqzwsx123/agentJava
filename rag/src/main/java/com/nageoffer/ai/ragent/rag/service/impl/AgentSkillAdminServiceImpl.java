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
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillToolOptionVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillVO;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.service.AgentSkillAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentSkillAdminServiceImpl implements AgentSkillAdminService {

    /**
     * 技能标识是模型加载正文时报的名字，限定小写字母开头的蛇形串，中文和空格模型报不准
     */
    private static final Pattern SKILL_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    /**
     * 与 t_agent_skill 的列宽一致
     */
    private static final int NAME_MAX_LENGTH = 64;
    private static final int DESCRIPTION_MAX_LENGTH = 512;

    private final AgentSkillMapper agentSkillMapper;
    private final AgentSkillCacheManager cacheManager;
    private final IntentNodeRegistry intentNodeRegistry;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 分页检索技能编码、名称或适用场景。
     * 列表不返回完整 Markdown 正文，按排序号升序和更新时间倒序展示。
     */
    @Override
    public IPage<AgentSkillVO> pageQuery(AgentSkillPageRequest requestParam) {
        // 使用管理视图查询全部有效技能，而非只读运行期启用清单。
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<AgentSkillDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<AgentSkillDO> result = agentSkillMapper.selectPage(
                page,
                Wrappers.lambdaQuery(AgentSkillDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(AgentSkillDO::getSkillCode, keyword)
                                .or()
                                .like(AgentSkillDO::getName, keyword)
                                .or()
                                .like(AgentSkillDO::getDescription, keyword))
                        .orderByAsc(AgentSkillDO::getSortOrder)
                        .orderByDesc(AgentSkillDO::getUpdateTime)
        );
        return result.convert(record -> toVO(record, false));
    }

    /**
     * 读取完整技能详情，包括手册正文和解锁工具集合。
     * 使用数据库记录 ID 定位，和模型加载时使用的 skillCode 区分。
     */
    @Override
    public AgentSkillVO queryById(String id) {
        // 详情转换开启正文输出，供编辑页面回填。
        return toVO(loadById(id), true);
    }

    /**
     * 创建业务技能，校验标识唯一性、场景说明、正文及关联工具。
     * 缺省排序为 0、缺省启用；保存后清理启用技能缓存。
     */
    @Override
    @LogRecord(
            success = "创建技能：{{#requestParam.name}}",
            fail = "创建技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(AgentSkillSaveRequest requestParam) {
        // 先检查请求和技能编码，再组装可供运行时注册表读取的技能记录。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String skillCode = normalizeSkillCode(requestParam.getSkillCode());
        boolean duplicated = agentSkillMapper.exists(Wrappers.lambdaQuery(AgentSkillDO.class)
                .eq(AgentSkillDO::getSkillCode, skillCode));
        if (duplicated) {
            throw new ClientException("技能标识已存在: " + skillCode);
        }

        AgentSkillDO record = AgentSkillDO.builder()
                .skillCode(skillCode)
                .name(requireName(requestParam.getName()))
                .description(requireDescription(requestParam.getDescription()))
                .content(requireContent(requestParam.getContent()))
                .toolIds(validateToolIds(requestParam.getToolIds()))
                .sortOrder(requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder())
                .enabled(Boolean.FALSE.equals(requestParam.getEnabled()) ? 0 : 1)
                .build();
        agentSkillMapper.insert(record);
        // 技能运行期先读缓存，写操作后必须失效缓存才能及时读取新清单。
        cacheManager.clearCache();
        bizChangeLogContext.put(record.getId(), null, record);
        return record.getId();
    }

    /**
     * 局部更新技能正文、展示信息、关联工具及启用状态。
     * skillCode 保持不变，避免破坏手册之间按编码建立的引用关系。
     */
    @Override
    @LogRecord(
            success = "更新技能：{{#id}}",
            fail = "更新技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, AgentSkillSaveRequest requestParam) {
        // 保留更新前快照，非 null 字段才覆盖原配置。
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        AgentSkillDO record = loadById(id);
        AgentSkillDO before = BeanUtil.copyProperties(record, AgentSkillDO.class);

        // 技能标识不跟着改：正文里可能被别的技能按名引用，改名等于换了一个技能
        if (requestParam.getName() != null) {
            record.setName(requireName(requestParam.getName()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(requireDescription(requestParam.getDescription()));
        }
        if (requestParam.getContent() != null) {
            record.setContent(requireContent(requestParam.getContent()));
        }
        if (requestParam.getToolIds() != null) {
            record.setToolIds(validateToolIds(requestParam.getToolIds()));
        }
        if (requestParam.getSortOrder() != null) {
            record.setSortOrder(requestParam.getSortOrder());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }

        // 数据库更新与缓存刷新配合，后续每轮推理会重新判断工具遮蔽集合。
        agentSkillMapper.updateById(record);
        cacheManager.clearCache();
        bizChangeLogContext.put(id, before, agentSkillMapper.selectById(id));
    }

    /**
     * 删除技能配置并使运行时技能缓存失效。
     * 记录不存在时拒绝操作，删除不会在此处动态注销共享 Toolkit。
     */
    @Override
    @LogRecord(
            success = "删除技能：{{#id}}",
            fail = "删除技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        // 管理层维护配置，运行期工具开放范围由逐轮遮蔽机制判断。
        AgentSkillDO record = loadById(id);
        agentSkillMapper.deleteById(record.getId());
        cacheManager.clearCache();
        bizChangeLogContext.put(id, record, null);
    }

    /**
     * 修改技能启用标记并清理启用清单缓存。
     * 缓存失效后注册表重新读取配置，审计保留启停前后状态。
     */
    @Override
    @LogRecord(
            success = "{{#enabled ? '启用' : '停用'}}技能：{{#id}}",
            fail = "切换技能状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void toggleEnabled(String id, boolean enabled) {
        // 先加载原记录，避免对不存在的技能执行无效启停。
        AgentSkillDO record = loadById(id);
        AgentSkillDO before = BeanUtil.copyProperties(record, AgentSkillDO.class);
        record.setEnabled(enabled ? 1 : 0);
        agentSkillMapper.updateById(record);
        cacheManager.clearCache();
        bizChangeLogContext.put(id, before, agentSkillMapper.selectById(id));
    }

    /**
     * 从意图树 MCP 节点构造技能关联工具选项。
     * 按工具 ID 去重，附带已引用该工具的技能名称，便于管理端选择。
     */
    @Override
    public List<AgentSkillToolOptionVO> listToolOptions() {
        // 候选工具来源于业务配置，不允许通过自由输入关联任意工具名。
        Map<String, String> ownerBySkill = referencedToolOwners();
        List<AgentSkillToolOptionVO> options = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) {
            String toolId = StrUtil.trimToNull(node.getMcpToolId());
            // 同一工具挂多个意图时只出一个选项，展示名取先遇到的那个节点
            if (toolId == null || !seen.add(toolId)) {
                continue;
            }
            options.add(AgentSkillToolOptionVO.builder()
                    .toolId(toolId)
                    .name(StrUtil.isBlank(node.getName()) ? toolId : node.getName())
                    .description(node.getDescription())
                    .referencedBySkillName(ownerBySkill.get(toolId))
                    .build());
        }
        return options;
    }

    /**
     * 工具到引用它的技能名，多技能共用时保留先遇到的那个
     */
    private Map<String, String> referencedToolOwners() {
        Map<String, String> owners = new HashMap<>();
        List<AgentSkillDO> skills = agentSkillMapper.selectList(
                Wrappers.lambdaQuery(AgentSkillDO.class).orderByAsc(AgentSkillDO::getSortOrder));
        for (AgentSkillDO skill : skills) {
            if (skill.getToolIds() == null) {
                continue;
            }
            skill.getToolIds().forEach(toolId -> owners.putIfAbsent(toolId, skill.getName()));
        }
        return owners;
    }

    /**
     * 工具必须是意图树里已启用的 MCP 节点：这批工具才是接入方真正开放给 Agent 的
     * 校验并去重技能需要解锁的 MCP 工具 ID。
     * 空白 ID 跳过，任何不在意图树 MCP 节点中的工具都会被拒绝。
     */
    private List<String> validateToolIds(List<String> toolIds) {
        // 先建立业务允许的工具集合，再逐项核对请求中的关联关系。
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> available = new LinkedHashSet<>();
        intentNodeRegistry.listMcpToolNodes().forEach(node -> {
            String toolId = StrUtil.trimToNull(node.getMcpToolId());
            if (toolId != null) {
                available.add(toolId);
            }
        });

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String toolId : toolIds) {
            String trimmed = StrUtil.trimToNull(toolId);
            if (trimmed == null) {
                continue;
            }
            if (!available.contains(trimmed)) {
                throw new ClientException("工具不在意图树的可用 MCP 节点中: " + trimmed);
            }
            selected.add(trimmed);
        }
        return List.copyOf(selected);
    }

    /**
     * 校验模型加载技能所用的稳定编码。
     * 编码需为小写字母开头的字母、数字或下划线，长度满足规定范围。
     */
    private String normalizeSkillCode(String skillCode) {
        // 只去除首尾空白，不自动修改大小写或替换非法字符。
        String trimmed = StrUtil.trimToNull(skillCode);
        Assert.notBlank(trimmed, () -> new ClientException("技能标识不能为空"));
        if (!SKILL_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new ClientException("技能标识只能用小写字母开头的字母、数字与下划线，长度 2~64");
        }
        return trimmed;
    }

    /**
     * 规范技能名称并校验非空及长度上限，避免无效名称进入持久化。
     */
    private String requireName(String name) {
        String trimmed = StrUtil.trimToNull(name);
        Assert.notBlank(trimmed, () -> new ClientException("技能名称不能为空"));
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new ClientException("技能名称不能超过 " + NAME_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    /**
     * 适用场景是模型判断要不要加载正文的唯一依据，留空等于这个技能永远轮不到
     */
    private String requireDescription(String description) {
        String trimmed = StrUtil.trimToNull(description);
        Assert.notBlank(trimmed, () -> new ClientException("适用场景不能为空，模型靠它判断何时该用这个技能"));
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new ClientException("适用场景不能超过 " + DESCRIPTION_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    /**
     * 去除技能正文首尾空白并校验必填，返回用于保存的正文。
     */
    private String requireContent(String content) {
        String trimmed = StrUtil.trimToNull(content);
        Assert.notBlank(trimmed, () -> new ClientException("技能正文不能为空"));
        return trimmed;
    }

    /**
     * 加载技能记录；不存在时抛出业务异常，避免修改或删除路径操作空对象。
     */
    private AgentSkillDO loadById(String id) {
        AgentSkillDO record = agentSkillMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("技能不存在"));
        return record;
    }

    /**
     * 转换技能响应，统一启用标志和空工具列表；仅详情等明确需要正文的场景返回 content。
     */
    private AgentSkillVO toVO(AgentSkillDO record, boolean withContent) {
        return AgentSkillVO.builder()
                .id(record.getId())
                .skillCode(record.getSkillCode())
                .name(record.getName())
                .description(record.getDescription())
                .content(withContent ? record.getContent() : null)
                .toolIds(record.getToolIds() == null ? List.of() : record.getToolIds())
                .sortOrder(record.getSortOrder())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
