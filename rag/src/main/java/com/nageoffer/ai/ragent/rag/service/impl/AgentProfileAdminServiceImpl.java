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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentProfileAdminServiceImpl implements AgentProfileAdminService {

    /**
     * 与 t_agent_profile.avatar 列宽一致
     */
    private static final int AVATAR_MAX_LENGTH = 32;

    private final AgentProfileMapper agentProfileMapper;
    private final AgentPromptMapper agentPromptMapper;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentPromptCacheManager cacheManager;
    private final OrchestrationProperties orchestrationProperties;
    private final BizChangeLogContext bizChangeLogContext;

    /**
     * 查询人设列表并计算每个人设在当前编排模式下的有效槽位数。
     * 内置人设优先展示，响应同时包含激活标记与当前模式。
     */
    @Override
    public AgentProfileListVO list() {
        // 按内置优先展示智能体，结合当前编排模式统计每个人设已配置槽位中生效与未生效的数量。
        // 先加载各人设实际配置的槽位，再按模式区分生效和未生效项。
        OrchestrationMode mode = orchestrationProperties.getMode();
        Map<String, List<AgentPromptSlot>> configuredSlots = loadConfiguredSlots();

        List<AgentProfileVO> agents = agentProfileMapper.selectList(Wrappers.lambdaQuery(AgentProfileDO.class))
                .stream()
                .sorted(Comparator.comparing((AgentProfileDO each) -> !isTrue(each.getBuiltin()))
                        .thenComparing(AgentProfileDO::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(each -> {
                    List<AgentPromptSlot> own = configuredSlots.getOrDefault(each.getId(), List.of());
                    int effective = (int) own.stream().filter(slot -> slot.isEffectiveIn(mode)).count();
                    return AgentProfileVO.builder()
                            .id(each.getId())
                            .name(each.getName())
                            .description(each.getDescription())
                            .avatar(each.getAvatar())
                            .builtin(isTrue(each.getBuiltin()))
                            .active(isTrue(each.getActive()))
                            .effectiveSlots(effective)
                            .inactiveSlots(own.size() - effective)
                            .createTime(each.getCreateTime())
                            .updateTime(each.getUpdateTime())
                            .build();
                })
                .toList();
        return AgentProfileListVO.builder()
                .mode(mode.name())
                .effectiveSlotTotal(AgentPromptSlot.effectiveIn(mode).size())
                .agents(agents)
                .build();
    }

    /**
     * 按智能体聚合已配置的槽位，只投影两个键以免把大段提示词正文全捞回内存
     * <p>
     * isNotNull + ne("") 即视为已配置，与 {@code AgentPromptResolver#putNonBlank} 的
     * {@code StrUtil.isNotBlank} 判定一致：savePrompt 已将空白内容归一化为 null，因此
     * 正常路径不会出现纯空白行，无需为防御性 trim 而把大段 content 捞回内存
     */
    private Map<String, List<AgentPromptSlot>> loadConfiguredSlots() {
        // 只读取已有正文的槽位键并按智能体分组，历史上已移除或无法识别的槽位不参与统计。
        List<AgentPromptDO> prompts = agentPromptMapper.selectList(Wrappers.lambdaQuery(AgentPromptDO.class)
                .select(AgentPromptDO::getAgentId, AgentPromptDO::getSlotKey)
                .isNotNull(AgentPromptDO::getContent)
                .ne(AgentPromptDO::getContent, ""));

        Map<String, List<AgentPromptSlot>> configured = new HashMap<>();
        for (AgentPromptDO prompt : prompts) {
            // 认不出的 slot_key 是删槽位留下的历史数据，读不到也统计不进任何一边
            AgentPromptSlot.find(prompt.getSlotKey()).ifPresent(slot ->
                    configured.computeIfAbsent(prompt.getAgentId(), key -> new ArrayList<>()).add(slot));
        }
        return configured;
    }

    /**
     * 创建未激活的自定义 Agent 人设。
     * 名称必须可用，内置与激活标记均设为 0，返回新的人设 ID。
     */
    @Override
    @LogRecord(
            success = "创建智能体：{{#requestParam.name}}",
            fail = "创建智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(AgentProfileSaveRequest requestParam) {
        // 规范名称和头像并校验名称唯一，创建默认未启用的自定义人设，只保存基本信息与创建快照。
        // 人设基本信息与提示词正文分开维护，创建时不复制全部默认提示词。
        String name = trimmedName(requestParam);
        assertNameAvailable(name, null);

        AgentProfileDO profile = AgentProfileDO.builder()
                .name(name)
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .avatar(trimmedAvatar(requestParam))
                .builtin(0)
                .active(0)
                .build();
        agentProfileMapper.insert(profile);
        bizChangeLogContext.put(profile.getId(), null, profile);
        return profile.getId();
    }

    /**
     * 修改可编辑人设的名称、描述和头像。
     * 使用显式 set 支持把描述或头像清空，避免 NOT_NULL 更新策略忽略 null。
     */
    @Override
    @LogRecord(
            success = "更新智能体：{{#id}}",
            fail = "更新智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, AgentProfileSaveRequest requestParam) {
        // 先拒绝编辑内置人设，再校验新名称未被其他人设占用。
        AgentProfileDO before = mustLoadEditable(id);
        String name = trimmedName(requestParam);
        assertNameAvailable(name, id);

        // 用显式 set 而非 updateById：默认 NOT_NULL 策略会跳过 null，描述就清不掉
        agentProfileMapper.update(Wrappers.lambdaUpdate(AgentProfileDO.class)
                .set(AgentProfileDO::getName, name)
                .set(AgentProfileDO::getDescription, StrUtil.trimToNull(requestParam.getDescription()))
                .set(AgentProfileDO::getAvatar, trimmedAvatar(requestParam))
                .eq(AgentProfileDO::getId, id));
        bizChangeLogContext.put(id, before, agentProfileMapper.selectById(id));
    }

    /**
     * 删除未激活的自定义人设及其提示词记录。
     * 内置人设不可编辑，当前激活人设必须先切换后才能删除。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "删除智能体：{{#id}}",
            fail = "删除智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION
    )
    public void delete(String id) {
        // 删除人设前先读取原配置，用于校验和审计快照。
        AgentProfileDO profile = mustLoadEditable(id);
        if (isTrue(profile.getActive())) {
            throw new ClientException("该智能体正在激活中，请先激活其他智能体再删除");
        }

        agentPromptMapper.delete(Wrappers.lambdaQuery(AgentPromptDO.class).eq(AgentPromptDO::getAgentId, id));
        agentProfileMapper.deleteById(id);
        bizChangeLogContext.put(id, profile, null);
        cacheManager.clearCache();
    }

    /**
     * 把指定人设切换为当前激活项。
     * 先清除原激活标记再激活目标，完成后清理提示词解析缓存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "激活智能体：{{#id}}",
            fail = "激活智能体失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}"
    )
    public void activate(String id) {
        // 激活目标必须存在，配置切换由本方法统一处理。
        AgentProfileDO target = mustLoad(id);

        // 显式 set 清零，不依赖 Builder + update(entity, wrapper) 的 NOT_NULL 策略
        agentProfileMapper.update(null,
                Wrappers.lambdaUpdate(AgentProfileDO.class)
                        .set(AgentProfileDO::getActive, 0)
                        .eq(AgentProfileDO::getActive, 1));

        // 将目标设为唯一激活项，随后让运行期提示词缓存失效。
        target.setActive(1);
        agentProfileMapper.updateById(target);
        cacheManager.clearCache();
    }

    /**
     * 组装人设各提示词槽位的内容、用途和当前模式生效情况。
     * 响应包含必需占位符与编辑提示；未自定义内容保持为空，以表达默认回落。
     */
    @Override
    public AgentPromptConfigVO loadPrompts(String id) {
        // 列出所有槽位的规则和当前模式生效情况，正文只取该人设自有配置，不混入默认继承值。
        // 本接口读取人设自身的提示词，不把默认正文误写成用户自定义内容。
        AgentProfileDO profile = mustLoad(id);
        AgentProfileDO builtin = loadBuiltin();
        OrchestrationMode mode = orchestrationProperties.getMode();
        Map<String, String> own = agentPromptResolver.loadOwnPrompts(id);

        List<AgentPromptConfigVO.Slot> slots = Arrays.stream(AgentPromptSlot.values())
                .map(slot -> AgentPromptConfigVO.Slot.builder()
                        .slotKey(slot.name())
                        .displayName(slot.getDisplayName())
                        .editorHint(slot.getEditorHint())
                        .group(slot.getGroup().name())
                        .groupName(slot.getGroup().getDisplayName())
                        .effective(slot.isEffectiveIn(mode))
                        .inactiveReason(slot.isEffectiveIn(mode) ? null : slot.getInactiveReason())
                        .requiredPlaceholders(slot.getRequiredPlaceholders())
                        .content(StrUtil.emptyIfNull(own.get(slot.name())))
                        .build())
                .toList();

        return AgentPromptConfigVO.builder()
                .agentId(id)
                .agentName(profile.getName())
                .builtin(isTrue(profile.getBuiltin()))
                .defaultAgentName(builtin == null ? null : builtin.getName())
                .mode(mode.name())
                .slots(slots)
                .build();
    }

    /**
     * 保存一个可编辑人设的提示词槽位，并刷新提示词缓存。
     * 非空内容需包含要求的占位符，清空内容表示恢复默认回落。
     */
    @Override
    @LogRecord(
            success = "更新智能体提示词：{{#id}} / {{#slotKey}}",
            fail = "更新智能体提示词失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_PROFILE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}"
    )
    public void savePrompt(String id, String slotKey, AgentPromptSaveRequest requestParam) {
        // 先校验人设可编辑性和槽位名称，避免写入未知用途的提示词。
        mustLoadEditable(id);
        AgentPromptSlot slot = AgentPromptSlot.find(slotKey)
                .orElseThrow(() -> new ClientException("未知的提示词：" + slotKey));

        String content = requestParam == null ? null : requestParam.getContent();
        assertPlaceholdersPresent(slot, content);

        // 已有槽位可写 null 恢复回落；不存在的槽位只有非空内容才需要新增。
        boolean existed = agentPromptMapper.exists(Wrappers.lambdaQuery(AgentPromptDO.class)
                .eq(AgentPromptDO::getAgentId, id)
                .eq(AgentPromptDO::getSlotKey, slot.name()));
        if (existed) {
            // 清空内容必须真写 null 才能恢复回落
            agentPromptMapper.update(Wrappers.lambdaUpdate(AgentPromptDO.class)
                    .set(AgentPromptDO::getContent, StrUtil.isBlank(content) ? null : content)
                    .eq(AgentPromptDO::getAgentId, id)
                    .eq(AgentPromptDO::getSlotKey, slot.name()));
        } else if (StrUtil.isNotBlank(content)) {
            agentPromptMapper.insert(AgentPromptDO.builder()
                    .agentId(id)
                    .slotKey(slot.name())
                    .content(content)
                    .build());
        }
        cacheManager.clearCache();
    }

    /**
     * 读取内置人设指定槽位的默认正文。
     * 槽位未知时拒绝读取，内置人设不存在时返回空字符串。
     */
    @Override
    public String defaultPrompt(String slotKey) {
        // 默认提示词只供回落或编辑器参考，不修改当前激活配置。
        AgentPromptSlot slot = AgentPromptSlot.find(slotKey)
                .orElseThrow(() -> new ClientException("未知的提示词：" + slotKey));
        AgentProfileDO builtin = loadBuiltin();
        if (builtin == null) {
            return "";
        }
        return StrUtil.emptyIfNull(agentPromptResolver.loadOwnPrompts(builtin.getId()).get(slot.name()));
    }

    /**
     * 内置智能体，缺失时返回 null 由调用方降级
     */
    private AgentProfileDO loadBuiltin() {
        // 按内置标记读取默认人设，供提示词编辑页面说明默认来源。
        return agentProfileMapper.selectOne(Wrappers.lambdaQuery(AgentProfileDO.class)
                .eq(AgentProfileDO::getBuiltin, 1));
    }

    /**
     * 占位符缺失会让下游规则静默失效，症状难以归因，故在保存时就拒绝
     * 校验自定义提示词保留槽位要求的模板占位符。
     * 空内容允许走默认回落，非空内容缺少必需变量时拒绝保存。
     */
    private void assertPlaceholdersPresent(AgentPromptSlot slot, String content) {
        // 非空自定义提示词必须包含槽位要求的全部占位符，缺失时逐项列出以免运行时上下文无法注入。
        // 避免提示词编辑后丢失编排阶段需要注入的上下文位置。
        if (StrUtil.isBlank(content) || slot.getRequiredPlaceholders().isEmpty()) {
            return;
        }
        List<String> missing = slot.getRequiredPlaceholders().stream()
                .filter(placeholder -> !content.contains(placeholder))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ClientException(StrUtil.format("「{}」缺少必需占位符：{}", slot.getDisplayName(),
                    String.join("、", missing)));
        }
    }

    /**
     * 去除智能体名称首尾空白并校验非空，供新增、修改共用。
     */
    private String trimmedName(AgentProfileSaveRequest requestParam) {
        // 兼容空请求并清理名称空白，名称仍为空时明确拒绝保存。
        String name = requestParam == null ? null : StrUtil.trim(requestParam.getName());
        if (StrUtil.isBlank(name)) {
            throw new ClientException("智能体名称不能为空");
        }
        return name;
    }

    /**
     * 头像预设表在前端，这里只做长度校验：认不出的取值前端会按 id 哈希兜底，不至于渲染成空白
     */
    private String trimmedAvatar(AgentProfileSaveRequest requestParam) {
        // 空白头像转为 null，非空值限制长度，避免把超长标识写入字段。
        String avatar = requestParam == null ? null : StrUtil.trimToNull(requestParam.getAvatar());
        if (avatar != null && avatar.length() > AVATAR_MAX_LENGTH) {
            throw new ClientException("头像标识过长");
        }
        return avatar;
    }

    /**
     * 检查智能体名称是否重复；修改时排除当前记录，重复则返回业务异常。
     */
    private void assertNameAvailable(String name, String excludeId) {
        // 查询同名人设，更新时排除自身 ID，命中其他记录则报名称冲突。
        boolean duplicated = agentProfileMapper.exists(Wrappers.lambdaQuery(AgentProfileDO.class)
                .eq(AgentProfileDO::getName, name)
                .ne(StrUtil.isNotBlank(excludeId), AgentProfileDO::getId, excludeId));
        if (duplicated) {
            throw new ClientException("智能体名称已存在");
        }
    }

    /**
     * 按 ID 取得智能体配置，空 ID 或记录不存在时统一返回不存在异常。
     */
    private AgentProfileDO mustLoad(String id) {
        // 空 ID 或数据库没有记录都转换为同一个人设不存在提示。
        AgentProfileDO profile = StrUtil.isBlank(id) ? null : agentProfileMapper.selectById(id);
        if (profile == null) {
            throw new ClientException("智能体不存在");
        }
        return profile;
    }

    /**
     * 加载人设并限制只编辑自定义配置。
     * 内置人设用于默认回落，修改或删除时统一拒绝。
     */
    private AgentProfileDO mustLoadEditable(String id) {
        // 更新基本信息、删除和保存提示词时复用相同的内置人设保护规则。
        AgentProfileDO profile = mustLoad(id);
        if (isTrue(profile.getBuiltin())) {
            throw new ClientException("内置智能体不可编辑或删除，如需调整请复制一份新建");
        }
        return profile;
    }

    /**
     * 将数据库整型标志转换为布尔值，仅数值 1 代表开启。
     */
    private static boolean isTrue(Integer flag) {
        // 只有整数一表示启用或内置，null 与其他值均按 false 处理。
        return flag != null && flag == 1;
    }
}
