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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final AgentPromptResolver agentPromptResolver;
    private final StreamTaskManager taskManager;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;
    private final CitationContextEnricher citationContextEnricher;

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        // 先读历史并保存当前问题，后面的改写与回答共用这份历史。
        loadMemory(ctx);
        // 结合历史补全问题并拆分；实现内处理术语映射及失败回退。
        rewriteQuery(ctx);
        // 为每个子问题识别候选意图，后续用于澄清、系统路由及定向检索。
        resolveIntents(ctx);

        // 需要用户澄清时结束本轮，不继续使用含歧义的候选检索。
        if (handleGuidance(ctx)) {
            return;
        }
        // 纯系统意图进入系统对话，其余才进入知识召回。
        if (handleSystemOnly(ctx)) {
            return;
        }

        // 并行多路召回、后处理和证据归因封装在检索引擎下游。
        RetrievalContext retrievalCtx = retrieve(ctx);
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        // 有证据时建立来源引用、组装提示词并启动可取消的流式回答。
        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    /**
     * 先读取本轮提问之前的会话历史，再保存用户问题并通知回调其消息 ID，避免提示词重复包含本轮问题。
     */
    private void loadMemory(StreamChatContext ctx) {
        // 先读取本轮之前的历史，再保存当前用户问题并把消息 ID 通知回调，避免当前问题重复进入历史。
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        ctx.getCallback().onReplyToMessageId(questionMessageId);
        ctx.setHistory(history);
    }

    /**
     * 结合历史对问题做术语归一化、改写和拆分，将结果写回本轮上下文。
     */
    private void rewriteQuery(StreamChatContext ctx) {
        // 结合当前问题与历史执行改写拆分，并把结果存到本轮流水线上下文。
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    /**
     * 对改写得到的子问题识别意图，保存每个子问题的候选节点与得分。
     */
    private void resolveIntents(StreamChatContext ctx) {
        // 对改写后的子问题识别意图，保存结果供澄清、系统分流和知识检索共用。
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    /**
     * 判断是否需要意图澄清；需要时直接返回澄清提示并完成流，返回 true 表示主链路应短路。
     */
    private boolean handleGuidance(StreamChatContext ctx) {
        // 发现歧义时直接输出澄清提示并完成本轮，返回 true 让主流程停止后续检索与模型回答。
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    /**
     * 全部子问题均为系统意图时直接调用系统对话模型，并将其取消句柄绑定到任务。
     */
    private boolean handleSystemOnly(StreamChatContext ctx) {
        // 全部子问题都只有系统意图时选专属或默认提示直接生成回答，并把流取消句柄绑定任务。
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        // 本分支不做知识检索，系统提示词、历史和当前问题直接进入模型。
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
        return true;
    }

    /**
     * 将各子问题及其意图交给检索引擎，返回已组装的知识上下文与来源切片。
     */
    private RetrievalContext retrieve(StreamChatContext ctx) {
        // 把已识别子问题交给统一检索引擎，返回组装后的证据上下文。
        return retrievalEngine.retrieve(ctx.getSubIntents());
    }

    /**
     * 检索上下文为空时发送固定无文档回复并完成流，返回 true 阻止继续生成缺少依据的回答。
     */
    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 没有可用证据时输出固定说明并结束本轮，返回 true 表示无需继续知识回答。
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    /**
     * 为检索结果建立统一来源编号，补充引用与推荐追问素材，再启动基于知识的流式回答。
     * 将模型取消句柄交给任务管理器，使主动停止和断连回收能中断上游。
     */
    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 检索完成后建立唯一来源编号：同一列表用于完成事件、来源面板与消息落库，开启引用时还作为行内角标编号
        List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
        ctx.getCallback().onSources(sources);
        // 开关关闭时这一步只负责清掉上下文里的内部 docId，不注入编号
        retrievalCtx.setKbContext(citationContextEnricher.enrich(retrievalCtx.getKbContext(), sources));

        // 装配 grounding 片段随消息落库 供答案后推荐追问生成 grounding（不参与 prompt）
        ctx.getCallback().onGroundingChunks(groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                intentResolver.mergeKbIntents(ctx.getSubIntents()),
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
    }

    // ==================== LLM 响应 ====================

    /**
     * 选择意图自定义或默认系统提示词，依次加入历史与当前问题，发起不启用思考的系统对话。
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        // 按系统提示、历史和当前问题顺序组装消息，关闭思考后发起模型流式调用并返回取消句柄。
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : agentPromptResolver.resolve(AgentPromptSlot.SYSTEM_CHAT);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    /**
     * 把知识证据、可用意图、历史和子问题组装成结构化消息，按深度思考开关发起流式模型调用。
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       List<NodeScore> kbIntents,
                                                       List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .kbContext(ctx.getKbContext())
                .kbIntents(kbIntents)
                .eligibleIntentIds(ctx.getEligibleIntentIds())
                .build();

        // 提示词构造器根据证据准入意图选择规则，并把证据、历史与当前问题放到对应消息中。
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(0D)
                .topP(1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
