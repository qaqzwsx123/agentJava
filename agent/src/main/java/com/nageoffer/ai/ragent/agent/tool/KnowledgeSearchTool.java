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
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库检索工具：RAG 管线在 Agent 模式下的唯一入口，描述由当前 Agent 的提示词槽位提供
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    public static final String TOOL_NAME = "search_knowledge";
    public static final String DISPLAY_NAME = "知识库检索";

    private static final String QUERY_PARAM = "query";
    private static final String QUERY_DESCRIPTION = "用于检索知识库的完整独立问题";
    private static final String SEARCH_ERROR_MESSAGE = "知识库检索异常，请稍后重试";
    private static final String INTERRUPTED_MESSAGE = "用户已停止，本次知识检索未完成";

    private final String description;
    private final KnowledgeSearchFacade knowledgeSearchFacade;

    @Override
    public String getName() {
        // 返回模型调用时使用的固定工具名，与 Toolkit 注册名和结果中的 name 保持一致。
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        // 返回目录快照提供的描述，模型据此判断什么时候需要调用知识库检索。
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        // 声明只接收必填的字符串 query，并禁止额外字段，供框架进行参数校验。
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        QUERY_PARAM, Map.of(
                                "type", "string",
                                "description", QUERY_DESCRIPTION)),
                "required", List.of(QUERY_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        // 知识库检索不修改业务数据，明确标记只读，供框架权限与执行策略判断。
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 先交给工具体追踪包装；真正订阅时才执行同步检索，并在三条终止路径记录事实。
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                // 2. 底层 RAG 检索是同步调用，调度到弹性工作线程，避免阻塞响应式推理线程。
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        // 1. 校验调用参数对象；缺失时直接返回统一错误结果，不发起检索。
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        // 2. 保存调用 ID 供结果关联，再从输入中只接受非空白字符串 query。
        String toolCallId = Optional.ofNullable(param.getToolUseBlock())
                .map(ToolUseBlock::getId)
                .orElse(null);
        Optional<String> query = Optional.ofNullable(param.getInput())
                .map(input -> input.get(QUERY_PARAM))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .filter(StrUtil::isNotBlank);
        if (query.isEmpty()) {
            return buildResult(toolCallId, "工具参数 query 不能为空", true);
        }
        // 3. 参数通过后调用 RAG 门面；返回后还要检查取消和空结果，不能把空内容当成成功。
        String normalizedQuery = query.get();
        try {
            String result = knowledgeSearchFacade.search(normalizedQuery);
            // RAG 逐层降级，取消到这里多半不是异常而是一份空结果，只在 catch 里判会漏掉
            if (TaskCancellation.isCancelled()) {
                return buildInterrupted(toolCallId);
            }
            if (StrUtil.isBlank(result)) {
                log.warn("知识库检索未返回有效内容, toolCallId: {}", toolCallId);
                return buildResult(toolCallId, SEARCH_ERROR_MESSAGE, true);
            }
            return buildResult(toolCallId, result, false);
        } catch (Exception e) {
            // 4. 异常也可能代表用户停止，先识别取消，再处理真实检索故障。
            if (TaskCancellation.isCancellation(e)) {
                return buildInterrupted(toolCallId);
            }
            log.error("知识库检索工具调用异常, toolCallId: {}", toolCallId, e);
            // 工具返回会重新进入模型上下文，不得暴露异常中的内部地址、SQL 或凭据等细节
            return buildResult(toolCallId, SEARCH_ERROR_MESSAGE, true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        // 携带原调用 ID 和固定工具名构造文本结果，按 isError 区分 ERROR 与 SUCCESS。
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(StrUtil.emptyIfNull(text)).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }

    /**
     * 取消不能报成 ERROR：模型见到失败会在下一轮重试检索，而用户已经喊停
     */
    private ToolResultBlock buildInterrupted(String toolCallId) {
        // 明确返回 INTERRUPTED 并关联原调用 ID，让框架识别为用户停止，避免当成检索错误继续重试。
        log.debug("知识库检索被取消, toolCallId: {}", toolCallId);
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(INTERRUPTED_MESSAGE).build())
                .state(ToolResultState.INTERRUPTED)
                .build();
    }
}
