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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.skill.SkillLoadTool;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 主 Agent 工具目录：固定注册 search_knowledge，并按意图树配置挂载当前可用的 MCP 工具
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolCatalog {

    /**
     * Toolkit 按名注册会覆盖，这几个名字不许 MCP 工具占用
     */
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of(
            KnowledgeSearchTool.TOOL_NAME, MemoryFlushTool.TOOL_NAME, SkillLoadTool.TOOL_NAME);

    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final IntentNodeRegistry intentNodeRegistry;
    //Spring 注入时，会把实现类的对象赋给接口类型的变量。
    //所以 intentNodeRegistry 表面是接口类型，实际指向实现类对象。
    private final AgentMcpClients mcpClients;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;
    private final AgentSkillRegistry skillRegistry;

    /**
     * 解析当前可用工具并生成快照，同一请求内指纹与 Toolkit 都从此快照派生
     *
     * @param prompts 本轮提示词快照，由调用方一次读出
     */
    public ResolvedCatalog resolve(Map<String, String> prompts) {
            // 1. 解析 MCP 工具：拿到可用的 MCP 工具绑定列表，以及不可用的工具 ID 列表
            McpResolution mcp = resolveMcpTools();
            // 2. 组装 ResolvedCatalog，最终返回的 ResolvedCatalog 就是本次解析出的工具配置快照。 包含四部分：
            return new ResolvedCatalog(
                // 2.1 知识库工具的描述：由 prompts 决定，不同提示词可能对应不同的描述
                resolveKnowledgeToolDescription(prompts),
                // 2.2 记忆工具的描述：由 prompts 决定，同理
                resolveMemoryToolDescription(prompts),
                // 2.3 MCP 工具的绑定列表：当前可用的 MCP 工具 （已经完成绑定，可以被 Agent 调用）
                mcp.bindings,
                // 2.4 MCP 工具的不可用列表：当前不可用的 MCP 工具 ID （比如被权限拦截、被技能遮蔽）
                mcp.unavailableToolIds,
                // 2.5 当前启用的技能列表：由 skillRegistry 提供，影响工具的可选范围
                skillRegistry.listEnabled()
        );
    }

    /**
     * 根据快照构建 Toolkit
     * 由技能解锁的工具照常注册，遮蔽交给 AgentSkillMaskingMiddleware 按会话逐轮判定
     */
    public Toolkit buildToolkit(ResolvedCatalog catalog) {
        // 新建工具集，本次构建的所有工具都注册到这里
        Toolkit toolkit = new Toolkit();
        // 知识检索工具：总是挂载，描述来自目录解析结果
        toolkit.registerAgentTool(new KnowledgeSearchTool(
                catalog.knowledgeToolDescription, knowledgeSearchFacade));
        // 记忆工具：目录里有描述才挂
        if (catalog.memoryToolDescription != null) {
            toolkit.registerAgentTool(new MemoryFlushTool(catalog.memoryToolDescription, memoryPipeline));
        } else if (memoryProperties.isLongTermEnabled()) {
            // 开了长期记忆但提示词为空 → 挂不了，告警提示，便于排查配置遗漏
            log.warn("AGENT_MEMORY_TOOL_DESCRIPTION 提示词为空, 本次不挂载 {}", MemoryFlushTool.TOOL_NAME);
        }
        // 技能工具：目录标记有技能时才挂
        if (catalog.hasSkills) {
            toolkit.registerAgentTool(new SkillLoadTool(skillRegistry));
        }
        // MCP 工具：遍历目录里的每个绑定，逐个用代理包装后注册
        catalog.bindings.forEach(binding -> toolkit.registerAgentTool(new McpToolProxy(binding)));
        // 只在构建实例时记，不随每次 resolve 刷日志
        // 意图树里配了但实际没挂上的 MCP 工具，逐个告警（服务端没有或与内置工具重名）
        catalog.unavailableToolIds.forEach(toolId ->
                log.warn("意图树配置的 MCP 工具未挂载: 服务端未发现或与内置工具重名, toolId: {}", toolId));
        // 返回装好的工具集，供 ReActAgent 使用
        return toolkit;
    }

    /**
     * 当前可用的 MCP 工具数量，用于 meta 探活接口
     */
    public int mcpToolCount() {
        // 重新按当前意图树与已发现工具取交集，返回可挂载数量，而不是远程服务的原始工具数。
        return resolveMcpTools().bindings.size();
    }

    /**
     * 读取知识检索工具的描述提示词；缺失时拒绝构建目录，避免注册描述不完整的工具。
     */
    private String resolveKnowledgeToolDescription(Map<String, String> prompts) {
        // 1. 从同一份提示词快照中读取知识检索描述，避免构建目录时混入另一时刻的配置。
        String description = prompts.get(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name());
        // 2. 检索工具始终挂载，缺少说明时立即失败，避免把无语义描述的工具交给模型。
        if (StrUtil.isBlank(description)) {
            throw new IllegalStateException("KNOWLEDGE_TOOL_DESCRIPTION 提示词不允许为空");
        }
        return description;
    }

    /**
     * 长期记忆关闭或提示词为空时返回 null
     */
    private String resolveMemoryToolDescription(Map<String, String> prompts) {
        // 1. 长期记忆开关关闭时不提供描述，Toolkit 据此省略 flush_memory 工具。
        if (!memoryProperties.isLongTermEnabled()) {
            return null;
        }
        // 2. 即使开关已开，空描述仍表示本次不挂载；外层装配会记录对应告警。
        String description = prompts.get(AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION.name());
        return StrUtil.isBlank(description) ? null : description;
    }

    /**
     * 意图树配置与 AgentScope 发现的工具取交集，无工具或与内置工具重名的记入 unavailableToolIds
     * 两处排序都为了指纹稳定，只排组内不够：分组序取决于各组最小的节点 id
     */
    private McpResolution resolveMcpTools() {
        // 1. 从 intentNodeRegistry 意图树节点注册表 取出所有 MCP 工具节点，按工具 ID 分组
        //    - listMcpToolNodes()：获取所有与 MCP 工具相关的意图节点 - sorted(...)：按节点 ID 排序，保证分组时的顺序稳定
        //    - groupingBy(...)：按 mcpToolId 分组，同一个工具可能有多个意图节点 - LinkedHashMap::new：使用 LinkedHashMap 保持插入顺序（即排序后的顺序）
        Map<String, List<IntentNode>> nodesByToolId = intentNodeRegistry.listMcpToolNodes().stream()
                .sorted(Comparator.comparing(IntentNode::getId))
                .collect(Collectors.groupingBy(
                        node -> node.getMcpToolId().trim(),  // 按工具 ID 分组（trim 去除空白）
                        LinkedHashMap::new,                  // 保持插入顺序
                        Collectors.toList()));               // 每个工具 ID 对应一个 IntentNode 列表
        //{ nodesByToolId的结果大概是：weather_tool 挂在两个意图节点下，order_tool 挂在一个意图节点下
        //  "weather_tool": [node1, node2],
        //  "order_tool":   [node3],
        //  "pay_tool":     [node4, node5]
        //}
        // 2. 准备两个列表：可用的绑定列表、不可用的工具 ID 列表
        List<McpToolBinding> bindings = new ArrayList<>();
        List<String> unavailableToolIds = new ArrayList<>();
        // 3. 遍历每个工具 ID，判断是否可用
        // forEach 就是遍历 nodesByToolId 这个 Map 的每一个键值对，然后把 key 和 value 分别传给 toolId 和 nodes 两个参数,依次传进lambda式子
        // 第一次：toolId = "weather_tool"
        //nodes  = [node1, node2]
        nodesByToolId.forEach((toolId, nodes) -> {
            // 3.1 从 mcpClients 中获取该工具对应的远程工具（RemoteTool）
            RemoteTool remote = mcpClients.get(toolId);
            // 3.2 如果远程工具不存在，或者工具 ID 在保留名单中，则标记为不可用
            //     - remote == null：说明该工具没有对应的远程实现（比如服务未启动、配置缺失）
            //     - RESERVED_TOOL_NAMES.contains(toolId)：工具 ID 被系统保留，不允许外部使用
            if (remote == null || RESERVED_TOOL_NAMES.contains(toolId)) {
                unavailableToolIds.add(toolId);
                return;
            }
            // 3.3 否则，组装成 McpToolBinding（绑定对象），加入可用列表
            //     - toBinding(...)：把工具 ID、意图节点列表、远程工具组装成一个绑定对象
            bindings.add(toBinding(toolId, nodes, remote));
        });
        // 4. 对可用列表和不可用列表分别排序，保证输出顺序稳定
        //    - bindings 按 toolId 排序  - unavailableToolIds 按自然顺序排序
        bindings.sort(Comparator.comparing(McpToolBinding::toolId));
        unavailableToolIds.sort(Comparator.naturalOrder());
        // 5. 返回解析结果
        return new McpResolution(bindings, unavailableToolIds);
    }

    /**
     * 把同一工具的业务配置合并成 MCP 绑定：选择展示名、合并去重描述，并汇总确认要求。
     * 多份配置中只要有一份要求确认，合并后的绑定就保留该要求。
     */
    private McpToolBinding toBinding(String toolId, List<IntentNode> nodes, RemoteTool remote) {
        // 1. 提取展示名称（displayName）
        //    从所有意图节点中取出名称，过滤掉空白，取第一个非空名称作为展示名
        //    如果所有节点都没有名称，则退化为使用 toolId 作为展示名
        String displayName = nodes.stream()
                .map(IntentNode::getName)          // 取出每个节点的名称
                .filter(StrUtil::isNotBlank)       // 过滤掉空白名称
                .findFirst()                       // 取第一个非空名称
                .orElse(toolId);                   // 如果都没有，用 toolId 兜底

        // 2. 提取描述（description）
        //    从所有意图节点中取出描述，过滤掉空白，去重后拼接成一个多行字符串
        //    为什么要去重？因为不同意图节点可能有相同的描述，去重后避免重复展示
        //    为什么要拼接？因为同一个工具可能对应多个意图，每个意图的描述可能不同，拼接后能完整表达工具的能力
        String description = nodes.stream()
                .map(IntentNode::getDescription)   // 取出每个节点的描述
                .filter(StrUtil::isNotBlank)       // 过滤掉空白描述
                .distinct()                        // 去重，避免重复描述
                .collect(Collectors.joining("\n")); // 用换行符拼接成一个多行字符串

        // 3. 判断是否需要用户确认（confirmConfigured）
        //    同一个工具可能挂在多个意图下，只要任意一个意图节点要求确认，就认为这个工具需要确认
        //    比如“查天气”不需要确认，但“下单”需要确认，如果同一个工具同时挂在两个意图下，那就需要确认
        boolean confirmConfigured = nodes.stream().anyMatch(IntentNode::isRequireConfirm);

        // 4. 组装成 McpToolBinding 并返回
        //    把工具 ID、展示名、描述、是否需要确认、远程工具打包成一个绑定对象
        return McpToolBinding.of(toolId, displayName, description, confirmConfigured, remote);
    }

    //McpResolution类 里面有两个属性，同时封装好了bindings()和unavailableToolIds()方法
    private record McpResolution(List<McpToolBinding> bindings, List<String> unavailableToolIds) {
    }

    /**
     * MCP 工具的生效值，AgentScope 工具与指纹都读这里
     *
     * @param description  意图树描述为空时回落服务端描述
     * @param needsConfirm 意图树勾选，或服务端未明确声明 readOnlyHint=true
     */
    public record McpToolBinding(
            String toolId,
            String displayName,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean needsConfirm,
            RemoteTool remote) {

        public static McpToolBinding of(String toolId, String displayName, String description,
                                        boolean confirmConfigured, RemoteTool remote) {
            // 1. 读取远程定义与只读声明；只有明确的 true 才按只读处理，缺省值仍要求确认。
            Tool definition = remote.definition();
            ToolAnnotations annotations = definition.annotations();
            Boolean readOnlyHint = annotations == null ? null : annotations.readOnlyHint();
            // 2. 意图树描述优先，未填写时回退远程服务声明；入参 Schema 也来自同一远程定义。
            String effectiveDescription = StrUtil.isNotBlank(description)
                    ? description
                    : StrUtil.emptyIfNull(definition.description());
            // 3. 显式要求确认或未明确声明只读，任一成立都需要人工确认。
            return new McpToolBinding(toolId, displayName, effectiveDescription,
                    McpTool.convertMcpSchemaToParameters(definition.inputSchema(), Set.of()),
                    Boolean.TRUE.equals(readOnlyHint),
                    confirmConfigured || !Boolean.TRUE.equals(readOnlyHint),
                    remote);
        }

        public McpToolFingerprint fingerprint() {
            // 仅取实际生效的展示、描述、Schema 和权限值参与比较，客户端连接对象不进入指纹。
            return new McpToolFingerprint(toolId, displayName, description, inputSchema, readOnly, needsConfirm);
        }

    }

    /**
     * 工具目录快照，展示名、入参标签和指纹在构造时一次算好
     */
    public static final class ResolvedCatalog {

        private final String knowledgeToolDescription;

        /**
         * null 表示本次不挂载记忆整理工具
         */
        private final String memoryToolDescription;

        private final List<McpToolBinding> bindings;
        private final List<String> unavailableToolIds;

        /**
         * 有启用技能时才挂 load_skill，没配技能的接入方看不到这个工具
         */
        private final boolean hasSkills;

        private final Map<String, String> displayNames;
        private final Map<String, Map<String, String>> fieldLabels;
        private final ToolCatalogFingerprint fingerprint;

        public ResolvedCatalog(
                String knowledgeToolDescription,
                String memoryToolDescription,
                List<McpToolBinding> bindings,
                List<String> unavailableToolIds,
                List<AgentSkill> skills) {
            // 1. 冻结本次工具清单，并根据当前技能列表决定是否启用 load_skill，后续构建使用同一份快照。
            this.knowledgeToolDescription = knowledgeToolDescription;
            this.memoryToolDescription = memoryToolDescription;
            this.bindings = List.copyOf(bindings);
            this.unavailableToolIds = List.copyOf(unavailableToolIds);
            this.hasSkills = !skills.isEmpty();

            // 2. 先登记内置工具展示名，再合并远程工具名称和参数标签，供 SSE 与确认卡使用。
            Map<String, String> names = new LinkedHashMap<>();
            names.put(KnowledgeSearchTool.TOOL_NAME, KnowledgeSearchTool.DISPLAY_NAME);
            if (memoryToolDescription != null) {
                names.put(MemoryFlushTool.TOOL_NAME, MemoryFlushTool.DISPLAY_NAME);
            }
            if (hasSkills) {
                names.put(SkillLoadTool.TOOL_NAME, SkillLoadTool.DISPLAY_NAME);
            }
            Map<String, Map<String, String>> labels = new LinkedHashMap<>();
            this.bindings.forEach(binding -> {
                names.put(binding.toolId(), binding.displayName());
                labels.put(binding.toolId(), fieldLabels(binding.remote().definition().inputSchema()));
            });
            this.displayNames = Map.copyOf(names);
            this.fieldLabels = Collections.unmodifiableMap(labels);
            // 3. 指纹只包含影响本次装配的生效值，排除连接实例等无法稳定比较的对象。
            this.fingerprint = new ToolCatalogFingerprint(knowledgeToolDescription, memoryToolDescription,
                    this.bindings.stream().map(McpToolBinding::fingerprint).toList(),
                    this.unavailableToolIds, hasSkills);
        }

        public ToolCatalogFingerprint fingerprint() {
            // 返回构造目录时一次性算好的指纹，调用方用它判断是否需要重建 Agent。
            return fingerprint;
        }

        /**
         * 工具展示名，未收录的返回原始名
         */
        public String displayNameOf(String toolName) {
            // 优先使用目录中的业务展示名；未收录的工具保留原名，避免事件展示丢失标识。
            return displayNames.getOrDefault(toolName, toolName);
        }

        /**
         * 确认卡的入参标签（schema 声明序），未收录的返回空 Map
         */
        public Map<String, String> fieldLabelsOf(String toolName) {
            // 读取确认卡片需要的参数标签；未知工具返回空表，让调用方按原字段名兜底。
            return fieldLabels.getOrDefault(toolName, Map.of());
        }

        /**
         * 从服务端 schema 提取字段标签，用 LinkedHashMap 保持声明序
         */
        private static Map<String, String> fieldLabels(JsonSchema inputSchema) {
            // 1. Schema 或属性定义缺失时没有标签可提取，直接返回空表。
            if (inputSchema == null || inputSchema.properties() == null) {
                return Map.of();
            }
            // 2. 按 Schema 的属性迭代顺序保存字段标题，返回只读视图，防止确认卡顺序被改动。
            Map<String, String> labels = new LinkedHashMap<>();
            inputSchema.properties().forEach((field, spec) ->
                    labels.put(field, titleOf(spec, field)));
            return Collections.unmodifiableMap(labels);
        }

        private static String titleOf(Object spec, String field) {
            // 字段 Schema 是对象且含非空 title 时使用该标题；否则回退字段原名。
            return spec instanceof Map<?, ?> node && node.get("title") instanceof String title
                    && StrUtil.isNotBlank(title) ? title : field;
        }
    }

    /**
     * 指纹只收生效值
     * 技能只记有无，清单逐轮现读注册表，改名改描述不必重建
     */
    public record ToolCatalogFingerprint(
            String knowledgeToolDescription,
            String memoryToolDescription,
            List<McpToolFingerprint> mcpTools,
            List<String> unavailableToolIds,
            boolean hasSkills) {

        public ToolCatalogFingerprint {
            // 冻结指纹内的列表，构造后不会因调用方修改原列表而改变相等比较结果。
            mcpTools = List.copyOf(mcpTools);
            unavailableToolIds = List.copyOf(unavailableToolIds);
        }
    }

    public record McpToolFingerprint(
            String toolId,
            String displayName,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean needsConfirm) {
    }
}
