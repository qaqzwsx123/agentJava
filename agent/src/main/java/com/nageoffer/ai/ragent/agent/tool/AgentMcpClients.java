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
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式的 MCP 连接和工具发现由 AgentScope 持有
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpClients {

    private final AgentMcpProperties properties;
    private final Map<String, RemoteTool> tools = new LinkedHashMap<>();
    private final List<McpClientWrapper> clients = new ArrayList<>();

    @PostConstruct
    public void init() {
        List<AgentMcpProperties.ServerConfig> servers = properties.getServers();
        if (servers == null) {
            return;
        }
        for (AgentMcpProperties.ServerConfig server : servers) {
            connect(server);
        }
    }

    private void connect(AgentMcpProperties.ServerConfig server) {
        // 先在 try 外部声明 client，便于在 catch 块中进行资源释放
        McpClientWrapper client = null;
        try {
            // 1. 规范化 URL：去掉末尾多余的 "/"，并确保最终以 "/mcp" 结尾
            String baseUrl = StrUtil.removeSuffix(server.getUrl(), "/");
            String url = baseUrl.endsWith("/mcp") ? baseUrl : baseUrl + "/mcp";

            // 2. AgentScope 框架封装好的 API，来快速创建一个 MCP 客户端。
            //    使用 streamableHttpTransport 作为传输方式（基于 HTTP 的流式传输）
            client = McpClientBuilder.create(server.getName())
                    .streamableHttpTransport(url)
                    .buildSync(); // 构建同步客户端

            // 3. 初始化连接（block() 表示阻塞等待初始化完成）
            client.initialize().block();

            // 4. 获取服务端提供的工具列表（同样阻塞等待结果）
            List<Tool> discovered = client.listTools().block();

            // 5. 把客户端加入本地客户端列表，便于后续统一管理和关闭
            clients.add(client);

            // 6. 遍历发现的工具，注册到本地 tools 映射表中
            if (discovered != null) {
                for (Tool tool : discovered) {
                    // 使用 putIfAbsent：如果工具名已存在，不覆盖，保留先连接的服务
                    RemoteTool existing = tools.putIfAbsent(tool.name(), new RemoteTool(tool, client));
                    if (existing != null) {
                        // 如果已有同名工具，记录警告日志（不阻断连接，避免影响其他工具注册）
                        log.warn("MCP 工具重名，保留先连接的服务, toolId={}, server={}", tool.name(), server.getName());
                    }
                }
            }

            // 7. 连接成功，记录日志（工具数可能为 null，需要兜底为 0）
            log.info("AgentScope MCP 服务已连接, server={}, tools={}", server.getName(),
                    discovered == null ? 0 : discovered.size());

        } catch (Exception e) {
            // 8. 异常兜底：如果连接过程中出错，且客户端已创建，必须关闭，防止资源泄漏
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    // 关闭失败也不要抛出，避免影响主流程，只记录警告
                    log.warn("关闭失败的 MCP 连接时发生异常, server={}", server.getName(), closeError);
                }
            }
            // 9. 记录连接失败的日志（打印异常信息，方便排查）
            log.error("AgentScope MCP 服务连接失败, server={}, reason={}", server.getName(), e.getMessage());
        }
    }

    public RemoteTool get(String toolId) {
        return tools.get(toolId);
    }

    @PreDestroy
    public void close() {
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 连接失败, server={}", client.getName(), e);
            }
        }
    }

    public record RemoteTool(Tool definition, McpClientWrapper client) {
    }
}
