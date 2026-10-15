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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpMeta;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.mcp.McpMeta;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 流式对话，负责发起提问与用户确认两条入口
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private final ReActAgentProvider agentProvider;
    private final AgentConversationService conversationService;
    private final StreamTaskManager taskManager;
    private final AgentRunGate runGate;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;

    /**
     * 为用户提问准备一次 Agent 运行，并启动异步 SSE 事件流。
     * 会话 ID 可复用，任务 ID 每次新建；申请运行名额后检查是否还有待确认操作。
     */
    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        // 用户身份必须在当前请求线程中取得，后续通过 RunScope 显式传给异步链路。
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()  // 没传 → 新会话，生成雪花 ID
                : conversationId; // 传了 → 续用旧会话
        String taskId = IdUtil.getSnowflakeNextIdStr();
        //申请一个运行名额（按 userId/conversationId 限流），拿不到就拒绝。
        Runnable releaseGate = runGate.acquire(userId, taskId, actualConversationId);
        try {
            if (StrUtil.isNotBlank(conversationId)
                    && conversationService.hasPendingConfirm(actualConversationId, userId)) {
                throw new ClientException("上一步操作还在等你确认，请先确认或取消");
            }
            // 转交执行：同步完成准备（取 Agent、建会话、落消息、组 RunScope），
            // 真正的异步流式执行在其内部的 launchStream 中触发；
            // releaseGate 一并传下去，由异步收尾时释放运行名额
            startRun(question, userId, actualConversationId, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    /**
     * 确认（批准或拒绝）Agent 挂起的工具调用，并启动对应的任务执行流程。
     * 把用户的批准或拒绝转换为 Agent 续跑输入。
     * 每次确认创建新任务并申请运行名额，启动失败时必须释放名额和任务登记。
     * */
    @Override
    public void confirmPendingTool(String conversationId, String messageId, boolean approved, SseEmitter emitter) {
        // 先校验会话与消息标识完整，再进入确认卡片的只读校验和续跑准备。
        // 从当前线程上下文中获取当前操作用户ID，用于权限校验和并发控制
        String userId = UserContext.getUserId();

        // 基础参数校验：会话ID和消息ID不能为空，否则无法定位到具体的挂起请求
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(messageId)) {
            throw new ClientException("确认参数不完整");
        }

        // 生成唯一的任务ID（雪花算法），用于标识本次确认操作对应的运行实例，
        // 便于后续追踪、日志记录以及在异常时进行精确的资源回收
        String taskId = IdUtil.getSnowflakeNextIdStr();

        // 获取并发控制门控（如用户级限流锁），防止同一用户同时触发过多运行任务。
        // acquire 会尝试获取资源并返回一个释放句柄 releaseGate，任务结束后必须调用以释放资源
        Runnable releaseGate = runGate.acquire(userId, taskId, conversationId);

        try {
            // 启动实际的确认运行流程：将用户的审批结果（approved）传递给底层 Agent 引擎，
            // 解除对应 messageId 的挂起状态，Agent 随后继续执行或跳过该工具调用，
            // 并通过 emitter 持续向客户端推送流式输出
            startConfirmRun(userId, conversationId, messageId, approved, taskId, emitter, releaseGate);
        } catch (RuntimeException | Error e) {
            // 启动失败时的兜底清理：如果 startConfirmRun 抛出运行时异常或错误（如线程中断、
            // 内存不足等），必须立即释放之前获取的并发资源，避免死锁或资源泄漏，
            // 然后将异常向上抛出，由全局异常处理器返回给前端
            cleanupFailedStart(taskId, releaseGate);
            throw e;
        }
    }

    /**
     * 只在启动失败时走：闸门不还，这个用户要被锁到 TTL 到期才能再提问
     * 启动成功后闸门归运行句柄的释放钩子管，这里不碰
     * 两步各自兜异常，前一步失败不能连累后一步
     * 回收尚未成功启动的运行名额和任务登记。
     * 释放闸门与注销任务分别兜底，防止一个清理异常阻断另一个步骤。
     */
    private void cleanupFailedStart(String taskId, Runnable releaseGate) {
        // 启动成功后的资源释放由运行句柄接管，只有失败路径调用本方法。
        try {
            releaseGate.run();
        } catch (Exception e) {
            log.error("Agent 启动失败后释放闸门失败, taskId: {}", taskId, e);
        }
        try {
            taskManager.unregister(taskId);
        } catch (Exception e) {
            log.error("Agent 启动失败后注销任务失败, taskId: {}", taskId, e);
        }
    }

    /**
     * 准备普通提问所需的会话、用户消息和运行范围。
     * 先建立长期记忆抽取基线，再落本轮提问，避免把新消息误归入历史范围。
     */
    private void startRun(String question, String userId, String conversationId, String taskId,
                          SseEmitter emitter, Runnable releaseGate) {
        // 取本次要使用的 Agent（按配置选定，单模型无 fallback）
        ActiveAgent activeAgent = agentProvider.getAgent();

        // 建/更新会话：新会话落库，已存在的刷新活跃时间；返回会话标题
        String title = conversationService.touchConversation(conversationId, userId, question);
        // 必须在 addUserMessage 之前建立基线，否则本轮消息会被划进历史、漏抽
        memoryPipeline.ensureExtractionBaseline(userId);
        // 落库用户提问，返回消息 ID，供 AI 回复关联（replyToMessageId）
        String questionMessageId = conversationService.addUserMessage(conversationId, userId, question);

        // 交给 launchStream 执行：input + agent + 本次运行上下文
        // RunScope 打包异步线程所需的一切（线程内取不到 ThreadLocal，必须显式传）
        launchStream(new UserMessage(question), activeAgent, RunScope.builder()
                .emitter(emitter)                    // SSE 推送通道
                .userId(userId)                      // 用户标识（异步线程无法从 ThreadLocal 获取）
                .conversationId(conversationId)      // 会话标识
                .taskId(taskId)                      // 任务标识，用于登记/取消/追踪
                .title(title)                        // 会话标题
                .replyToMessageId(questionMessageId) // AI 回复挂在这条用户消息下
                .releaseGate(releaseGate)            // 释放运行名额的回调，收尾时调用
                .build());
    }
    /**
     * 用户确认/拒绝后继续执行：先只读校验，启动准备完成后才结算卡片
     * 读取待确认记录及原始工具调用，构造仅携带确认结果的续跑消息。
     * 空正文避免重复追加用户提问，卡片结算延后到运行句柄真正启动时。
     */
    private void startConfirmRun(String userId, String conversationId, String messageId, boolean approved,
                                 String taskId, SseEmitter emitter, Runnable releaseGate) {
        // 先取得运行实例和待确认消息的归属信息，再构造确认结果列表。
        // 获取当前配置对应的缓存 Agent 实例；人设或工具目录变化时由 Provider 按签名懒重建
        ActiveAgent activeAgent = agentProvider.getAgent();

        // 从会话服务中读取挂起的确认结算信息（即前端看到的"确认卡片"），
        // 包含标题、回复目标消息ID等元数据，用于恢复上下文
        AgentConfirmSettlement settlement = conversationService.getPendingConfirm(conversationId, userId, messageId);

        // 解析确认结果：根据用户批准/拒绝的决议，结合挂起的工具调用，生成 ConfirmResult 列表。
        // 若服务端状态中已无 ASKING 工具，则将确认卡片标为过期并报错，避免会话持续被阻塞
        List<ConfirmResult> confirmResults = resolveConfirmResultsOrExpire(activeAgent, userId, conversationId, messageId, approved);

        // 构造恢复消息：正文为空，仅通过 metadata 携带确认结果。
        // 这样设计是为了让 Agent 框架识别到这是一次"恢复执行"而非新的用户输入，
        // 不会把该消息追加到对话上下文中作为一轮新的对话
        Msg resumeMsg = UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();

        // 启动流式执行：将恢复消息交给 Agent，并通过 RunScope 传入完整的运行时上下文，
        // 包括 SSE 发射器、任务ID、会话信息、确认结果、以及并发门控释放句柄
        launchStream(resumeMsg, activeAgent, RunScope.builder()
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(settlement.title())
                .replyToMessageId(settlement.replyToMessageId())
                .confirmMessageId(messageId)      // 记录是哪条消息触发了确认
                .confirmApproved(approved)        // 记录用户的批准/拒绝决议
                .releaseGate(releaseGate)         // 任务结束后调用，释放用户级并发锁
                .build());
    }

    /**
     * 工具入参从 Agent 状态取，前端只传同意/拒绝，防止篡改
     * 从服务端 Agent 状态提取待批准工具，生成同意或拒绝结果。
     * 找不到 ASKING 工具时先让卡片过期，再报错，防止会话永久停在待确认状态。
     */
    private List<ConfirmResult> resolveConfirmResultsOrExpire(ActiveAgent activeAgent, String userId,
                                                              String conversationId, String messageId, boolean approved) {
        // 工具名称及入参使用已保存状态中的原件，不接受前端重传工具参数。
        @SuppressWarnings("resource")
        ReActAgent agent = activeAgent.agent();
        List<ToolUseBlock> asking = askingToolCalls(agent.getAgentState(userId, conversationId).getContext());
        if (asking.isEmpty()) {
            // 状态里没有待确认工具了，先结算卡片再报错，否则会话会一直卡住
            conversationService.expirePendingConfirm(conversationId, userId, messageId);
            throw new ClientException("待确认的操作已失效，请重新提问");
        }
        return asking.stream().map(toolCall -> new ConfirmResult(approved, toolCall)).toList();
    }

    /**
     * 待批工具只在上下文最后一条 assistant 消息上，与框架内部判据一致
     * 从最近一条助手消息提取处于 ASKING 状态的工具调用。
     * 只看最后一条助手消息，与框架识别当前待确认操作的范围保持一致。
     */
    private static List<ToolUseBlock> askingToolCalls(List<Msg> context) {
        // 逆序定位最近助手消息，避免把更早轮次的确认请求重新执行。
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            return msg.getContent().stream()
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                    .toList();
        }
        return List.of();
    }

    /**
     * 首问与确认后继续执行共用的流式启动逻辑
     */
    // 当问题（input）和方法（Agent）都准备好后，如何安全地拉起一条从后端 AI 引擎到前端浏览器的流式管道，
    // launchStream: 负责把一个普通的用户请求（Msg），转化为一条与前端实时通信、可监控、可中断的异步流（Flux<AgentEvent>。
    /**
     * 把普通提问或确认结果接入同一条可取消的 Agent SSE 执行链路。
     * 先绑定生命周期并发送 meta，再登记取消回调、订阅事件流并关联上游中断句柄。
     */
    private void launchStream(Msg input, ActiveAgent activeAgent, RunScope scope) {
        // 1. 提取上下文信息（用户、会话、任务ID），用于标识本次运行的唯一性
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        String taskId = scope.taskId();
        ReActAgent agent = activeAgent.agent();

        // 2. 事实记录器AgentToolExecutionFacts
        // 这里的 Clock 用于记录事件发生的时间戳
        Clock clock = Clock.systemDefaultZone();
        //为本次运行（taskId）创建一个全新的、专属的事实记录器，并用传入的 clock 作为唯一的时间基准。”
        AgentToolExecutionFacts facts = new AgentToolExecutionFacts(taskId, clock);

        // 构建运行时上下文，贯穿整个流处理过程。中断时需要从这里获取根 Span 用于追踪
        RuntimeContext runtimeContext = buildRuntimeContext(scope, facts);
        //给底层的 SSE 连接安装一个‘生命状态监听器’，并把它控制起来
        SseEmitterSender sender = new SseEmitterSender(scope.emitter());
        //一次 AI 运行的操作把手
        AgentRunHandle runHandle = new AgentRunHandle(taskId, sender, taskManager, facts, runtimeContext);

        // 3. 绑定释放钩子（例如：流结束、异常、取消时，清理资源或状态）
        bindReleaseHooks(runHandle, agent, scope);
        try {
            // 4. 绑定 SSE 生命周期，确保前端连接断开时能触发相应的清理逻辑
            bindEmitterLifecycle(scope.emitter(), runHandle, taskId);

            // 5. 在 AI 正式开始生成内容之前, 发送初始 META 事件给前端，告知本次会话和任务 ID
            sender.sendEvent(AgentSSEEventType.META.value(), new AgentMetaPayload(conversationId, taskId));

            // 6. 构建事件桥接器：将 Agent 内部的事件（Event）转换并推送到前端 SSE
            AgentStreamEventBridge bridge = buildBridge(activeAgent, scope, runHandle, facts, clock);

            // 7. 注册任务到任务管理器，并绑定取消时的回调（finishCancelledStream）
            taskManager.register(taskId, userId, bridge::finishCancelledStream);

            // 8. 关键检查点：如果任务在注册瞬间就被标记为“已结算”（例如：在注册前就被取消）
            // 这种情况下不需要再启动 Agent 流，直接返回即可
            if (runHandle.isSettled()) {
                return;
            }

            // 9. 启动 Agent 的事件流（Flux） ,调用 streamEvents()，模型开始准备工具调用
            // Flux<AgentEvent> 就是：一条会不断传来“AI 思考过程”的传送带
            Flux<AgentEvent> events = agent.streamEvents(input, runtimeContext)
                    // 给这条流加一个监听器：无论它是因为正常结束、报错还是被取消而停止，都要通知 runHandle 标记‘上游已终止’，
                    // 以避免后续的取消操作产生死锁或重复断流
                    .doFinally(signal -> runHandle.markUpstreamTerminated());

            // 10. 在 runHandle 中正式启动流处理
            runHandle.start(() -> {
                // 结算确认卡片（通常是 UI 层面的预操作）
                settleConfirmCard(scope);

                // events.subscribe(...)：按下启动按钮。这时候 AI 才真正开始干活，事件开始一个接一个地产生。
               // bridge::onEvent → 接收模型输出、工具调用和确认等事件 bridge::onError：如果流报错了，交给 bridge 处理
               // bridge::onComplete→ 执行结束后保存消息、发送终止事件
                //events.subscribe(...) 返回 Disposable：这是一个“订阅凭证”。有了它，就可以随时取消订阅（也就是“断流”）。
                Disposable disposable = events.subscribe(bridge::onEvent, bridge::onError, bridge::onComplete);

                // 绑定流资源与中断逻辑：把 Disposable 和“中断动作”绑定。用户点取消时，通过 disposable.dispose() 强制切断流。调用 agent.interrupt 停止生成
                runHandle.bindStream(disposable, () -> agent.interrupt(userId, conversationId));

                // 将句柄绑定到任务管理器，允许通过任务ID直接中断上游流
                taskManager.bindHandle(taskId, runHandle::interruptUpstream);
            });
        } catch (RuntimeException | Error e) {
            // 11. 异常兜底：如果启动过程中抛出异常或错误，释放钩子可能不会被执行
            // 因此必须手动清理 Agent 的状态缓存，防止残留坏状态影响下一次请求
            agent.clearStateCache(userId, conversationId);
            throw e; // 重新抛出异常，让上层调用者感知启动失败
        }
    }

    /**
     * 登记流结束后的三件事，注册顺序就是执行顺序，换顺序会出问题
     * 按固定顺序注册流结束后的状态保存、缓存清理和闸门释放动作。
     * 先处理旧流状态，再允许同用户下一轮进入，最后安排长期记忆抽取。
     */
    private void bindReleaseHooks(AgentRunHandle runHandle, ReActAgent agent, RunScope scope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        runHandle.onRelease(() -> {
            // 错误路径与强制断流框架都来不及存盘，驱逐前补存一次，否则本轮工具执行结果会丢
            // 优雅中断已由框架中断分支存盘，不重复保存
            if (runHandle.isStateSaveRequired()) {
                try {
                    agent.saveAgentState(userId, conversationId);
                } catch (Exception e) {
                    log.error("Agent 失败收尾补存盘失败, conversationId: {}", conversationId, e);
                }
            }
            // 只清本次流实际使用的实例，避免重建后旧流误清新 Agent
            agent.clearStateCache(userId, conversationId);
        });
        // 最后再放行同一用户的下一轮，避免新流加载状态后被本轮收尾清掉
        runHandle.onRelease(scope.releaseGate());
        // 放在释放并发锁之后，确保记忆抽取时名额已归还
        runHandle.onRelease(() -> scheduleMemoryExtraction(userId, conversationId));
    }

    /**
     * 组装事件，agent 和 catalog 从同一个 ActiveAgent 取出，保证展示名一致
     */
    private AgentStreamEventBridge buildBridge(ActiveAgent activeAgent, RunScope scope, AgentRunHandle runHandle,
                                               AgentToolExecutionFacts facts, Clock clock) {
        return AgentStreamEventBridge.builder()
                // 1. 绑定运行控制器：桥接器需要感知任务是否被取消/结束，并在必要时中断上游流
                .runHandle(runHandle)
                // 2. 绑定会话服务：AI 生成完最终回复后，桥接器需要调用它把回复落库（存进数据库）
                .conversationService(conversationService)
                // 3. 绑定事件目录/配置：catalog 决定了如何将 Agent 内部事件翻译成前端 SSE 事件
                //    比如：工具调用事件怎么展示、思考过程怎么展示、文本流怎么展示
                .catalog(activeAgent.catalog())
                // 4. 绑定会话 ID：所有事件都要带上它，前端才能把消息渲染到正确的会话窗口
                .conversationId(scope.conversationId())
                // 5. 绑定用户 ID：用于权限校验、个性化处理、以及后续的记忆抽取
                .userId(scope.userId())
                // 6. 绑定会话标题：可能用于在生成回复时携带标题信息，或用于日志记录
                .title(scope.title())
                // 7. 绑定被回复的消息 ID：AI 的回复要挂在这条用户消息下面，确保对话链路正确
                .replyToMessageId(scope.replyToMessageId())
                // 8. 绑定统一时钟：保证所有事件的时间戳基于同一时间基准，避免时间不一致
                .clock(clock)
                // 9. 绑定事实记录器：桥接器在处理工具调用事件时，需要调用 facts 记录起止时间和批次
                .facts(facts)
                // 10. 构建并返回桥接器实例
                .build();
    }
    /**
     * 把确认卡片改写成终态，首问路径没有卡片可结算，直接跳过
     * 只能在启动互斥区里做：先结算后订阅，取消才不会漏掉这次工具执行
     */
    private void settleConfirmCard(RunScope scope) {
        if (scope.confirmMessageId() == null) {
            return;
        }
        conversationService.settlePendingConfirm(scope.conversationId(), scope.userId(),
                scope.confirmMessageId(), scope.confirmApproved());
    }

    /**
     * 事实源在构建时就位，中间件从上下文取它；其余 key 建完再 put——底层 ConcurrentHashMap 不接受 null
     */
    private static RuntimeContext buildRuntimeContext(RunScope scope, AgentToolExecutionFacts facts) {
        // 1. 使用 Builder 模式构建基础上下文
        RuntimeContext runtimeContext = RuntimeContext.builder()
                // 1.1 设置用户 ID：用于权限校验、限流、个性化等
                .userId(scope.userId())
                // 1.2 设置会话 ID：用于关联多轮对话、加载历史记忆
                .sessionId(scope.conversationId())

                // 1.3 放入 MCP 元数据（McpMeta）
                // MCP (Model Context Protocol) 是模型上下文协议，这里封装了用户级别的 MCP 信息
                // 后续工具或中间件可以通过 RuntimeContext.get(McpMeta.class) 取出使用
                .put(McpMeta.class, new McpMeta(AgentMcpMeta.ofUser(scope.userId())))

                // 1.4 放入事实记录器（AgentToolExecutionFacts）
                // 使用静态常量 RUNTIME_CONTEXT_KEY 作为键，确保全局唯一
                // 这样在任何地方都可以通过 AgentToolExecutionFacts.from(ctx) 取出同一个实例
                .put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts)
                .build();

        // 2. 补充链路追踪相关的上下文键值（Trace Context Keys）
        // 这些信息用于日志追踪、监控、问题排查，确保一次请求的所有日志都能串联起来

        // 2.1 放入任务 ID（taskId）
        // 用于在日志中标识本次运行，方便通过 taskId 搜索所有相关日志
        runtimeContext.put(AgentTraceContextKeys.TASK_ID, scope.taskId());

        // 2.2 放入被回复的消息 ID（replyToMessageId）
        // 用于关联 AI 回复与用户提问，方便追踪对话链路
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, scope.replyToMessageId());

        // 2.3 放入确认卡片的消息 ID（confirmMessageId）
        // 如果本次运行涉及用户确认操作（如工具调用前需用户授权），
        // 这个 ID 用于定位具体的确认消息，方便后续处理确认/取消逻辑
        runtimeContext.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, scope.confirmMessageId());

        // 3. 返回构建好的完整上下文
        return runtimeContext;
    }

    /**
     * 单次运行的上下文参数，在 startRun/startConfirmRun 与 launchStream 之间传递
     * 使用java14 record 关键字 进行 类的创建
     */
    @Builder
    private record RunScope(SseEmitter emitter, String userId, String conversationId,
                            String taskId, String title, String replyToMessageId, String confirmMessageId,
                            boolean confirmApproved, Runnable releaseGate) {
    }

    /**
     * 轮次结束后异步抽取长期记忆
     */
    private void scheduleMemoryExtraction(String userId, String conversationId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        Mono.fromCallable(() -> memoryPipeline.extract(userId, conversationId, AgentMemoryTriggerType.BACKGROUND))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> {
                            if (outcome.idle()) {
                                return;
                            }
                            log.info("轮次结束后台记忆抽取完成, userId: {}, conversationId: {}, 状态: {}, 落库: {}",
                                    userId, conversationId, outcome.status(), outcome.applied());
                        },
                        // 用户已拿到答复，失败只记日志
                        e -> log.error("轮次结束后台记忆抽取异常, userId: {}, conversationId: {}",
                                userId, conversationId, e));
    }

    /**
     * SSE 断开（关页/断网/超时）时触发取消，否则 ReAct 循环会空跑到迭代上限
     * 把 SSE 超时、错误及完成回调连接到上游任务回收。
     * 已结算任务跳过取消，AtomicBoolean 限制多个容器回调只触发一次回收。
     */
    private void bindEmitterLifecycle(SseEmitter emitter, AgentRunHandle runHandle, String taskId) {
        AtomicBoolean recycled = new AtomicBoolean(false);
        Runnable recycleUpstream = () -> {
            // 已结算的不再取消，避免往 Redis 留死标记
            if (!runHandle.isSettled() && recycled.compareAndSet(false, true)) {
                taskManager.cancel(taskId);
            }
        };
        emitter.onTimeout(recycleUpstream);
        emitter.onError(e -> recycleUpstream.run());
        // 用户关闭页面走 completeWithError，容器不触发 onTimeout/onError，只有 onCompletion 能兜住
        emitter.onCompletion(recycleUpstream);
    }

    /**
     * 提交当前用户对指定运行任务的取消请求。
     * 任务属主验证与跨节点取消由 StreamTaskManager 处理，不在这里直接删除会话。
     */
    @Override
    public void stopTask(String taskId) {
        // 委托用户级取消入口，保留任务管理器的属主检查和通知机制。
        taskManager.cancelByUser(taskId);
    }
}
