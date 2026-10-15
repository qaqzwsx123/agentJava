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

package com.nageoffer.ai.ragent.agent.skill;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 技能工具遮蔽：手册加载前不让模型看到那些不看手册就会办错的工具，避免绕过前置步骤直接动手
 * 技能的 tool_ids 是「加载后才解锁的工具」，不是「正文会用到的工具」，正文按名字引用任何工具都不受影响。
 * 记 G 为所有启用技能 tool_ids 的并集、L 为上下文里已加载技能 tool_ids 的并集，本轮遮蔽 = G − L：
 * 不在任何 tool_ids 里的工具始终可见，一个工具可挂多份手册，任一加载即放行
 * 解锁只认上下文里那条 load_skill 结果，手册被压缩带走时工具一并收回，两者同生共死
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentSkillMaskingMiddleware implements MiddlewareBase {

    /**
     * 本次推理算出的遮蔽映射寄存在调用上下文里，工具执行前复查同一份结论
     */
    public static final String MASKED_TOOLS_ATTRIBUTE = "ragent_masked_tools";

    private final AgentSkillRegistry skillRegistry;

    /**
     * 排在最内层，看到的是压缩与改写之后的最终消息列表
     */
    @Override
    public int order() {
        return 0;
    }

    /**
     * 推理前过滤本轮不可见的工具，并把遮蔽映射存入运行时上下文
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        Map<String, String> masked;
        try {
            // 根据当前消息列表计算需要遮蔽的工具映射
            masked = maskedTools(input.messages());
        } catch (Exception e) {
            // 计算异常时保守处理：本轮不遮蔽任何工具，全量放行，避免阻断推理
            log.warn("技能遮蔽计算异常, 本轮按全量工具推理", e);
            return next.apply(input);
        }

        // 将遮蔽映射存入运行时上下文，工具执行前会读取它做二次校验
        if (runtimeContext != null) {
            runtimeContext.put(MASKED_TOOLS_ATTRIBUTE, masked);
        }

        // 没有需要遮蔽的工具，直接放行原始输入
        if (masked.isEmpty()) {
            return next.apply(input);
        }

        // 从工具列表中过滤掉被遮蔽的工具，只保留可见工具
        List<ToolSchema> tools = input.tools().stream()
                .filter(tool -> !masked.containsKey(tool.getName()))
                .toList();

        // 用过滤后的工具列表构造新的 ReasoningInput，继续后续推理
        return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
    }

    /**
     * 计算本轮需要遮蔽的工具，返回 toolId → 所属技能标识
     */
    private Map<String, String> maskedTools(List<Msg> messages) {
        // 获取所有已启用的技能
        List<AgentSkill> skills = skillRegistry.listEnabled();
        if (skills.isEmpty()) {
            // 没有启用任何技能，无需遮蔽
            return Map.of();
        }

        // 从上下文中解析已加载的技能代码
        Set<String> loaded = loadedSkillCodes(messages);

        // 存放待遮蔽的工具：toolId -> 所属技能代码
        Map<String, String> masked = new LinkedHashMap<>();

        // 遍历所有未加载的技能，把它们的 toolIds 加入遮蔽集合
        skills.stream()
                .filter(skill -> !loaded.contains(skill.skillCode()))
                .forEach(skill -> skill.toolIds().forEach(toolId -> masked.putIfAbsent(toolId, skill.skillCode())));

        // 遍历已加载的技能，把它们包含的 toolIds 从遮蔽集合中移除（任一加载即放行）
        skills.stream()
                .filter(skill -> loaded.contains(skill.skillCode()))
                .forEach(skill -> skill.toolIds().forEach(masked::remove));

        return masked;
    }

    /**
     * 从上下文消息里捡出已加载的技能代码，标记由 load_skill 写在结果 metadata 上
     */
    private static Set<String> loadedSkillCodes(List<Msg> messages) {
        Set<String> loaded = new HashSet<>();
        for (Msg msg : messages) {
            // 只检查 TOOL 角色的消息
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            // 遍历消息中的所有工具结果块
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                // 条件：工具执行成功、未被上下文裁剪驱逐、metadata 中带有已加载技能标记
                // 裁剪只换正文不动 metadata，占位块要当成没加载：手册不在了工具就得收回去
                if (result.getState() == ToolResultState.SUCCESS
                        && !AgentContextTrimmer.isEvicted(result)
                        && result.getMetadata().get(SkillLoadTool.LOADED_SKILL_METADATA_KEY) instanceof String code) {
                    loaded.add(code);
                }
            }
        }
        return loaded;
    }
}