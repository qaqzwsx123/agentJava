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

package com.nageoffer.ai.ragent.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能注册表
 * 运行期只读，Agent 侧从这里取已启用技能
 */
@Component
@RequiredArgsConstructor
public class AgentSkillRegistry {
    // 运行期技能注册表：优先读取缓存，必要时加载数据库中已启用技能，向 Agent 提供可加载的技能目录和正文。

    private final AgentSkillMapper agentSkillMapper;
    private final AgentSkillCacheManager cacheManager;

    /**
     * @return 已启用技能，按 sortOrder 升序；正文一并带出，加载时不必再查库
     */
    public List<AgentSkill> listEnabled() {
        // 先查缓存，未命中只加载启用技能，按排序号与 ID 稳定排序后回填缓存。
        List<AgentSkill> cached = cacheManager.getFromCache();
        if (cached != null) {
            return cached;
        }
        List<AgentSkill> skills = agentSkillMapper.selectList(
                        Wrappers.lambdaQuery(AgentSkillDO.class)
                                .eq(AgentSkillDO::getEnabled, 1)
                                .orderByAsc(AgentSkillDO::getSortOrder)
                                .orderByAsc(AgentSkillDO::getId))
                .stream()
                .map(AgentSkillRegistry::toSkill)
                .toList();
        cacheManager.saveToCache(skills);
        return skills;
    }

    /**
     * @return 指定技能，停用或不存在都返回 null
     */
    public AgentSkill findByCode(String skillCode) {
        // 清理技能编码后从启用列表精确匹配，空编码或没有匹配项时返回 null。
        if (StrUtil.isBlank(skillCode)) {
            return null;
        }
        String target = skillCode.trim();
        return listEnabled().stream()
                .filter(skill -> target.equals(skill.skillCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 把数据库技能记录转换为运行时技能，包含编码、摘要、完整说明及关联工具 ID。
     */
    private static AgentSkill toSkill(AgentSkillDO record) {
        // 把数据库技能记录转换为运行时只需的编码、名称、描述、手册和工具 ID 列表。
        return new AgentSkill(record.getSkillCode(), record.getName(), record.getDescription(),
                record.getContent(), record.getToolIds());
    }
}
