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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体提示词解析器
 * 优先取激活智能体的槽位，空白则回落内置智能体；面向终端用户的提示词一律从此处读取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPromptResolver {
    // 智能体提示词解析器 优先取激活智能体的槽位，空白则回落内置智能体；面向终端用户的提示词一律从此处读取。

    private final AgentProfileMapper agentProfileMapper;
    private final AgentPromptMapper agentPromptMapper;
    private final AgentPromptCacheManager cacheManager;

    /**
     * @return 槽位提示词，内置智能体也没配时返回空串
     */
    public String resolve(AgentPromptSlot slot) {
        // 按提示词槽位名称查已解析映射，空槽位或无内容时返回空字符串。
        if (slot == null) {
            return "";
        }
        return StrUtil.emptyIfNull(resolveAll().get(slot.name()));
    }

    /**
     * 填充占位符并清理格式，语义与 PromptTemplateLoader#render 一致
     */
    public String render(AgentPromptSlot slot, Map<String, String> slots) {
        // 先解析槽位内容，再替换模板变量并清理多余空行，生成可直接使用的提示词。
        return PromptTemplateUtils.cleanupPrompt(PromptTemplateUtils.fillSlots(resolve(slot), slots));
    }

    /**
     * @return 全部槽位的最终生效内容，缺失的槽位不出现在 map 中
     */
    public Map<String, String> resolveAll() {
        // 缓存命中直接复用，未命中从数据库装配有效提示词后回填缓存。
        Map<String, String> cached = cacheManager.getFromCache();
        if (cached != null) {
            return cached;
        }
        Map<String, String> resolved = loadFromDb();
        cacheManager.saveToCache(resolved);
        return resolved;
    }

    /**
     * 读取某个智能体自身配置的槽位，不做回落，供控制台编辑态展示
     */
    public Map<String, String> loadOwnPrompts(String agentId) {
        // 只读取指定智能体自己保存的槽位内容，不在这里做继承或默认值合并。
        Map<String, String> own = new HashMap<>();
        if (StrUtil.isBlank(agentId)) {
            return own;
        }
        List<AgentPromptDO> prompts = agentPromptMapper.selectList(
                Wrappers.lambdaQuery(AgentPromptDO.class).eq(AgentPromptDO::getAgentId, agentId));
        for (AgentPromptDO prompt : prompts) {
            own.put(prompt.getSlotKey(), StrUtil.emptyIfNull(prompt.getContent()));
        }
        return own;
    }

    /**
     * 先以内置智能体的非空槽位建立基线，再由激活智能体的非空槽位覆盖，支持未配置槽位回落。
     */
    private Map<String, String> loadFromDb() {
        AgentProfileDO builtin = firstByFlag(AgentProfileDO::getBuiltin);
        if (builtin == null) {
            log.warn("未找到内置智能体，空槽位将无提示词可回落");
        }

        // 先铺内置作为基线，再让激活智能体的非空槽位覆盖；两者同为一条时重复覆盖无副作用
        Map<String, String> resolved = new HashMap<>();
        putNonBlank(resolved, builtin);
        putNonBlank(resolved, firstByFlag(AgentProfileDO::getActive));
        return resolved;
    }

    /**
     * 按指定标志查询配置，并按创建时间、ID 取最早一条；无匹配返回 null。
     */
    private AgentProfileDO firstByFlag(SFunction<AgentProfileDO, Integer> flag) {
        // 按指定标记筛选智能体，再按创建时间和 ID 排序，稳定选择第一条。
        List<AgentProfileDO> profiles = agentProfileMapper.selectList(
                Wrappers.lambdaQuery(AgentProfileDO.class)
                        .eq(flag, 1)
                        .orderByAsc(AgentProfileDO::getCreateTime)
                        .orderByAsc(AgentProfileDO::getId));
        return CollUtil.isEmpty(profiles) ? null : profiles.get(0);
    }

    /**
     * 后写入者覆盖前者，空白内容不参与覆盖，以此实现回落
     */
    private void putNonBlank(Map<String, String> target, AgentProfileDO profile) {
        // 将智能体自己的非空白提示词覆盖到目标映射，空内容不抹去已解析的默认值。
        if (profile == null) {
            return;
        }
        loadOwnPrompts(profile.getId()).forEach((slotKey, content) -> {
            if (StrUtil.isNotBlank(content)) {
                target.put(slotKey, content);
            }
        });
    }
}
