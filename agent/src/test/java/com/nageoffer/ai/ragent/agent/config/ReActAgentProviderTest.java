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

package com.nageoffer.ai.ragent.agent.config;

import com.nageoffer.ai.ragent.agent.confirm.AgentConfirmDenialMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentContextCompactionMiddleware;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AgentUserMemoryMiddleware;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentToolBatchMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.KnowledgeSearchTool;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActAgentProviderTest {

    private IntentNodeRegistry intentNodeRegistry;
    private AgentMcpClients mcpClients;
    private AgentPromptResolver agentPromptResolver;
    private AgentToolCatalog toolCatalog;
    private ReActAgentProvider provider;

    @BeforeEach
    void setUp() {
        intentNodeRegistry = mock(IntentNodeRegistry.class);
        mcpClients = mock(AgentMcpClients.class);
        agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolveAll()).thenReturn(Map.of(
                AgentPromptSlot.AGENT_MAIN.name(), "你是 Ragent",
                AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name(), "当前 Agent 的知识库工具描述"));
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of(
                mcpNode("sales", "销售查询", "sales_query")));
        RemoteTool salesTool = executor("sales_query");
        when(mcpClients.get("sales_query")).thenReturn(salesTool);

        toolCatalog = spy(new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpClients,
                new AgentMemoryProperties(),
                mock(AgentMemoryPipeline.class),
                mock(AgentSkillRegistry.class)));
        AgentProperties agentProperties = new AgentProperties();
        provider = new ReActAgentProvider(
                agentPromptResolver,
                toolCatalog,
                mock(OpenAIChatModel.class),
                mock(PgAgentStateStore.class),
                agentProperties,
                mock(AgentUserMemoryMiddleware.class),
                mock(AgentContextCompactionMiddleware.class),
                mock(AgentConfirmDenialMiddleware.class),
                mock(AgentSkillMaskingMiddleware.class),
                new AgentToolBatchMiddleware(),
                absent(),
                absent());
    }

    /**
     * 追踪关闭时中间件不在容器里，mock ifAvailable 即空操作
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return mock(ObjectProvider.class);
    }

    @Test
    void shouldResolveToolCatalogOncePerRequest() {
        provider.getAgent();

        // 解析两次就有两份现实，指纹与 Toolkit 各信一份，中间注册表一变就长期不再自愈
        verify(toolCatalog, times(1)).resolve(any());
        verify(mcpClients, times(1)).get("sales_query");
        // 提示词一次读全，分两次读会拼出半新半旧的实例
        verify(agentPromptResolver, times(1)).resolveAll();
    }

    @Test
    void shouldBuildToolkitFromTheSnapshotItsFingerprintCameFrom() {
        provider.getAgent();

        verify(toolCatalog, times(1)).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
    }

    @Test
    void shouldReuseCachedAgentWhenCatalogUnchanged() {
        var first = provider.getAgent();
        var second = provider.getAgent();

        assertThat(second.agent()).isSameAs(first.agent());
        assertThat(second.catalog()).isSameAs(first.catalog());
        verify(toolCatalog, times(1)).buildToolkit(any(AgentToolCatalog.ResolvedCatalog.class));
    }

    @Test
    void shouldRebuildWhenMcpToolAppears() {
        var first = provider.getAgent();
        RemoteTool ordersTool = executor("orders_query");
        when(mcpClients.get("orders_query")).thenReturn(ordersTool);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of(
                mcpNode("sales", "销售查询", "sales_query"),
                mcpNode("orders", "订单查询", "orders_query")));

        var second = provider.getAgent();

        assertThat(second.agent()).isNotSameAs(first.agent());
        assertThat(second.catalog().displayNameOf("orders_query")).isEqualTo("订单查询");
    }

    @Test
    void shouldCarryDisplayNamesOnSnapshot() {
        var active = provider.getAgent();

        assertThat(active.catalog().displayNameOf("sales_query")).isEqualTo("销售查询");
        assertThat(active.catalog().displayNameOf(KnowledgeSearchTool.TOOL_NAME))
                .isEqualTo(KnowledgeSearchTool.DISPLAY_NAME);
        assertThat(active.catalog().displayNameOf("unknown_query")).isEqualTo("unknown_query");
    }

    /**
     * 重试会让同一 toolCallId 产生多个 span，事实源 CAS 只保首次起止，节点数对不上
     */
    @Test
    void shouldKeepToolRetriesOffSoOneCallStaysOneObservation() {
        var active = provider.getAgent();

        ExecutionConfig config = active.agent().getToolExecutionConfig();
        Integer maxAttempts = config == null ? null : config.getMaxAttempts();
        assertThat(maxAttempts == null ? 1 : maxAttempts).isLessThanOrEqualTo(1);
        // 盯着框架默认值，升级时如果变了这里会红
        assertThat(ExecutionConfig.TOOL_DEFAULTS.getMaxAttempts()).isEqualTo(1);
    }

    /**
     * 重试加在整条流之上，半程失败即重订阅，已吐出的 chunk 不回滚：正文会重复一遍
     * 闸门只有这一处——调用级这个值会盖住模型 defaultOptions，改那边等于没改
     */
    @Test
    void shouldKeepModelRetriesOffSoHalfStreamFailureIsNotReplayed() {
        var active = provider.getAgent();

        // 框架语义是「最大尝试次数、含首次」，1 即不重试，它也校验了必须大于 0
        assertThat(active.agent().getModelConfig().maxRetries()).isEqualTo(1);
    }

    private IntentNode mcpNode(String id, String name, String toolId) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .description("意图树描述")
                .kind(IntentKind.MCP)
                .mcpToolId(toolId)
                .build();
    }

    private RemoteTool executor(String toolId) {
        Tool tool = Tool.builder()
                .name(toolId)
                .description("MCP 服务端描述")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        return new RemoteTool(tool, client);
    }
}
