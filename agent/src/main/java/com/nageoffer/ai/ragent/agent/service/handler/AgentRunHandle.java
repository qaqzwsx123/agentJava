
package com.nageoffer.ai.ragent.agent.service.handler;

import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentRunTracer;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.agent.RuntimeContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次 Agent 运行的生命周期句柄，complete/cancel/fail 三条出口只有一条能收尾
 */
@Slf4j
public class AgentRunHandle {

    /**
     * 打断后留给框架存盘的时间, 超过这个时间就转为强制断流
     */
    private static final long GRACEFUL_INTERRUPT_WAIT_MS = 2000L;

    @Getter
    private final String taskId;

    /**
     * SSE 发送器，增量事件由调用方直接写,收尾成功后由本句柄调用 {@code sender.complete()} 关闭 SSE。
     */
    @Getter
    private final SseEmitterSender sender;
    private final StreamTaskManager taskManager; //任务管理器，负责登记 / 注销任务以及取消状态查询。
    private final Object lifecycleLock = new Object();// 启动、收尾、释放三段共用的锁，必须可重入：同步结束的流会在订阅那一刻就回调收尾
    @Getter
    private volatile boolean settled; //是否已收尾，锁内写、锁外有人读,所以用 volatile。
    private boolean interruptTriggered; //取消广播和兜底路径都会调打断，只放行第一次
    private final List<Runnable> releaseHooks = new ArrayList<>();//释放钩子，每个只跑一次,收尾之后再登记的钩子会走“当场补跑”分支。
    private boolean released; //释放钩子是否已经跑过。
    private final CountDownLatch upstreamTerminated = new CountDownLatch(1); //上游流的终止信号，打断后等它来判断框架有没有存完盘
    private Disposable disposable; //上游流订阅对象，用于强制断流。
    private Runnable interruptAction; //打断动作，由调用方在 bindStream 时提供
    @Getter
    private volatile boolean stateSaveRequired;// 是否需要在释放钩子里补一次存盘。失败出口或强制断流时会置位；只置位不清零，避免被优雅中断冲掉
    private final AgentToolExecutionFacts facts; //工具执行事实，用来记录打断 / 取消 / 中止等状态。
    private final RuntimeContext runtimeContext; //打断跑在 HTTP 线程上够不着响应式链，根 span 从这里取
    public AgentRunHandle(String taskId, SseEmitterSender sender, StreamTaskManager taskManager,
                          AgentToolExecutionFacts facts, RuntimeContext runtimeContext) {
        this.taskId = taskId;
        this.sender = sender;
        this.taskManager = taskManager;
        this.facts = facts;
        this.runtimeContext = runtimeContext;
    }

    /**
     * 绑定上游订阅与打断动作，两个字段只在这把锁内读写，所以不用 volatile
     */
    public void bindStream(Disposable disposable, Runnable interruptAction) {
        synchronized (lifecycleLock) {
            this.disposable = disposable; //disposable 上游流的订阅对象，用于强制断流
            this.interruptAction = interruptAction; //interruptAction 打断动作，由调用方提供
        }
    }

    /**
     * 启动本次运行。整段启动放进锁里，否则取消可能先跑完收尾、这边随后才订阅，工具照样被执行一遍
     */
    public void start(Runnable startup) {
        synchronized (lifecycleLock) {
            // 已收尾或已取消 → 不再启动，避免无用执行
            if (isSettled() || isCancelled()) {
                return;
            }
            try {
                startup.run();
            } catch (RuntimeException | Error e) {
                // subscribe 之后才抛的话流已经跑起来且没人管得了，不掐掉会空转到迭代上限
                if (disposable != null) {
                    disposable.dispose();
                }
                throw e;
            }
        }
    }

    /**
     * 登记释放钩子，三条出口都会执行；收尾后再登记的当场补跑
     */
    public void onRelease(Runnable hook) {
        if (hook == null) {
            return;
        }
        synchronized (lifecycleLock) {
            // 还没释放 → 记入列表，等收尾时统一跑
            if (!released) {
                releaseHooks.add(hook);
                return;
            }
        }
        // 已释放 → 当场补跑，保证调用方不会漏掉释放动作
        runReleaseHook(hook);
    }

    /**
     * 上游流走到终点时调用，打断路径靠它判断框架是否已自行收尾
     */
    public void markUpstreamTerminated() {
        upstreamTerminated.countDown();
    }

    /**
     * 先打断框架、等它存盘，超时再 dispose 断流；顺序反了会丢掉本轮 Agent 状态
     */
    public void interruptUpstream() {
        Runnable interrupt;
        Disposable current;
        synchronized (lifecycleLock) {
            // 打断只放行第一次
            if (interruptTriggered) {
                return;
            }
            interruptTriggered = true;
            interrupt = interruptAction;
            current = disposable;
        }
        if (interrupt != null) {
            boolean graceful = false;
            try {
                // 得赶在 interrupt 之前写，框架收尾会 end 掉根 span，之后再写属性没用
                facts.markInterrupted();
                AgentRunTracer.markInterrupted(runtimeContext);
                interrupt.run();  // 执行打断动作
                graceful = awaitUpstreamTermination(); // 等上游流终止信号，判断是否是优雅中断
            } catch (Exception e) {
                // 打断动作出错不能挡住断流，否则 ReAct 循环会空跑到迭代上限
                log.error("打断动作执行异常，转为强制断流, taskId: {}", taskId, e);
            }
            // 只置位不清零，优雅中断不能冲掉失败出口已标记的补存盘需求
            if (!graceful) {
                stateSaveRequired = true;
                // dispose 之后没人能再读 span，只能在这之前写
                facts.markCancelled();
                AgentRunTracer.markAborted(runtimeContext);
            }
        }
        // 框架已自行收尾时这里是空操作，强制断流才真的掐链
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * 在有限时间内等待上游终止信号，给 Agent 框架完成状态保存的机会。
     * 超时或当前线程被中断时返回 false，由调用方转入强制断流；保留线程中断标记。
     */
    private boolean awaitUpstreamTermination() {
        try {
            // 等待的是 doFinally 发出的终止信号；等待有上限，避免停止请求无限阻塞。
            boolean terminated = upstreamTerminated.await(GRACEFUL_INTERRUPT_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!terminated) {
                log.warn("等待上游流响应打断超时，转为强制断流, taskId: {}", taskId);
            }
            return terminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 读取任务管理器中的本地取消状态，供启动和事件回调决定是否继续处理。
     */
    public boolean isCancelled() {
        return taskManager.isCancelled(taskId);
    }

    /**
     * 以正常完成路径申请唯一收尾权，执行回复落库与结束事件回调。
     */
    public void complete(Runnable body) {
        settleAndClose(body);
    }

    /**
     * 取消后的收尾，打断本身由 {@link #interruptUpstream()} 做
     */
    public void cancel(Runnable body) {
        settleAndClose(body);
    }

    /**
     * 标志在收尾体里置，没抢到收尾权的那次不能改它
     */
    public void fail(Runnable body) {
        settleAndClose(() -> {
            stateSaveRequired = true;
            body.run();
        });
    }

    /**
     * 仅在本次取得收尾权并完成资源释放后关闭 SSE；重复回调不会再次关闭或落库。
     */
    private void settleAndClose(Runnable body) {
        // settle 内部负责注销与释放，只有取得收尾权的出口负责关闭 SSE。
        if (settle(body)) {
            sender.complete();
        }
    }

    /**
     * 收尾体只跑一次，不管成没成都注销任务并释放资源
     */
    private boolean settle(Runnable body) {
        synchronized (lifecycleLock) {
            // 已收尾 → 直接返回 false，避免重复收尾
            if (settled) {
                return false;
            }
            // 先占有唯一收尾权，再执行落库和终止事件；其他完成、错误或取消回调会直接跳过。
            settled = true;
            try {
                body.run();
            } catch (Exception e) {
                // 收尾体失败不能挡住后续注销和释放
                log.error("Agent 运行收尾处理失败, taskId: {}", taskId, e);
            } finally {
                try {
                    taskManager.unregister(taskId);
                } catch (Exception e) {
                    // 注销失败不能挡住释放钩子，否则这个用户的并发锁要守到 TTL 过期
                    log.error("Agent 任务注销失败, taskId: {}", taskId, e);
                } finally { //无论前面是否异常，释放钩子必须执行
                    runReleaseHooks();
                }
            }
            return true;
        }
    }

    /**
     * 先置标志再遍历，之后登记的钩子走补跑分支，不会再改这个列表
     */
    private void runReleaseHooks() {
        released = true;
        releaseHooks.forEach(this::runReleaseHook);
        // 清掉 lambda 捕获的引用，帮助回收
        releaseHooks.clear();
    }

    /**
     * 隔离单个释放回调的异常，保证状态清理失败时仍能继续归还名额等后续动作。
     */
    private void runReleaseHook(Runnable hook) {
        try {
            hook.run();
        } catch (Exception e) {
            // 单个钩子失败不能拖累其余释放动作
            log.error("Agent 运行释放钩子执行失败, taskId: {}", taskId, e);
        }
    }
}
