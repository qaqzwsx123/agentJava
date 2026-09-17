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

package com.nageoffer.ai.ragent.framework.web;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式任务跨节点取消管理器
 * <p>
 * 机制层与引擎无关：注册表 + Redis 标记 + 广播主题构成统一的取消协议，
 * 各引擎的个性化收尾动作（补发事件、中断上游流等）以回调形式注入
 */
@Slf4j
@Component
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final String OWNER_KEY_PREFIX = "ragent:stream:owner:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    /**
     * 系统侧回收的发起方占位，与任何用户 ID 都不会撞（用户 ID 是雪花数字串）
     */
    private static final String SYSTEM_REQUESTER = "__system__";

    /**
     * 广播载荷 taskId|requester 的分隔符：taskId 与用户 ID 都是雪花数字串，不含竖线
     */
    private static final String PAYLOAD_SEPARATOR = "|";

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient) {
        // 注入共享 Redis 客户端，后续属主查询、取消标记和广播都通过同一客户端访问。
        this.redissonClient = redissonClient;
    }

    /**
     * 启动时订阅跨节点取消主题，解析任务与发起方，再交给 cancelLocal 复核属主并取消本地任务。
     * 兼容旧节点只广播 taskId 的载荷，订阅 ID 留给销毁时注销。
     */
    @PostConstruct
    public void subscribe() {
        // 1. 订阅取消主题并保存监听器 ID；收到广播后仅尝试取消本节点实际持有的任务。
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, payload) -> {
            if (StrUtil.isBlank(payload)) {
                return;
            }
            int separator = payload.indexOf(PAYLOAD_SEPARATOR);
            // 滚动升级期老节点仍广播裸 taskId，它在发布前已做过同样的属主校验，按系统侧收
            String taskId = separator < 0 ? payload : payload.substring(0, separator);
            String requester = separator < 0 ? SYSTEM_REQUESTER : payload.substring(separator + 1);
            cancelLocal(taskId, requester);
        });
    }

    /**
     * 组件销毁时移除本次主题监听器，避免同进程重建后残留重复订阅。
     */
    @PreDestroy
    public void unsubscribe() {
        // 尚未成功订阅时无需注销；已订阅则按本组件记录的 ID 移除监听，不影响其他订阅者。
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册取消收尾回调并记下属主，回调负责补发终止事件并结束响应流
     * 若任务已被属主或系统标记取消，回调立即执行
     */
    public void register(String taskId, String ownerUserId, Runnable onCancelFinalizer) {
        // 1. 获取或创建本地任务信息
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        // 2. 记录属主和收尾回调
        taskInfo.ownerUserId = ownerUserId;
        taskInfo.finalizer = onCancelFinalizer;
        // 3. 把属主写进 Redis，供跨节点权限校验
        redissonClient.getBucket(ownerKey(taskId)).set(ownerUserId, CANCEL_TTL);
        // 4. 检查 Redis 里是否已有取消标记（预埋取消）
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            onCancelFinalizer.run(); // 如果已被取消，立刻执行收尾
        }
    }

    /**
     * 绑定上游流的中断动作，取消时先于收尾回调执行
     * 任务已结算注销时不再创建记录，否则会留下一条无人触发的幽灵任务
     */
    public void bindHandle(String taskId, Runnable cancelAction) {
        // 1. 只给仍在本地登记的任务绑定上游中断动作；不存在的任务不重新创建，避免生成幽灵记录。
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }
        // 2. 停止可能早于上游句柄绑定；若取消标记已经落定，绑定后立即补执行中断动作。
        taskInfo.cancelAction = cancelAction;
        if (taskInfo.cancelled.get() && cancelAction != null) {
            cancelAction.run();
        }
    }

    /**
     * 读取当前节点登记任务的取消标记；不存在的任务返回 false，不在每次检查时访问 Redis。
     */
    public boolean isCancelled(String taskId) {
        // 只读取本地原子标记，避免推理或检索频繁检查时每次都访问 Redis。
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 系统侧回收（SSE 超时、客户端断连），容器回调线程上没有登录用户可比对
     */
    public void cancel(String taskId) {
        // 超时或断连等系统路径没有登录上下文，使用专用系统身份发布取消请求。
        publishCancel(taskId, SYSTEM_REQUESTER);
    }

    /**
     * 用户主动停止：taskId 是雪花 ID，时间有序可预测，不是访问凭证，必须比对属主
     */
    public void cancelByUser(String taskId) {
        // 1. 主动停止必须先取得当前登录用户，再到 Redis 查任务属主，不能只凭任务 ID 操作。
        String requester = UserContext.requireUser().getUserId();
        // 2. 属主信息可由其他节点写入，必须先查 Redis；发起方不匹配时不能发布取消。
        RBucket<String> owner = redissonClient.getBucket(ownerKey(taskId));
        String ownerUserId = owner.get();
        if (StrUtil.isNotBlank(ownerUserId) && !ownerUserId.equals(requester)) {
            log.warn("拒绝越权停止流式任务，taskId：{}，属主：{}，发起方：{}", taskId, ownerUserId, requester);
            // 不区分「不存在」与「非属主」，免得停止接口变成他人任务的探测器
            throw new ClientException("任务不存在或已结束");
        }
        // 属主查不到多半是任务已结束，也可能是注册还没落地，故标记带上发起方交给注册那一刻复核
        publishCancel(taskId, requester);
    }

    /**
     * 先将带发起方身份的取消标记写入 Redis，再广播给所有节点。
     * 标记覆盖取消早于任务注册的窗口，执行节点仍需复核发起方是否有权取消。
     */
    private void publishCancel(String taskId, String requester) {
        // 先设置 Redis 标记，再发布消息
        RBucket<String> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(requester, CANCEL_TTL);

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        // 载荷带上发起方：执行端要靠它复核，只有发布端校验挡不住属主落地前的抢跑
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId + PAYLOAD_SEPARATOR + requester);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消，是则同步状态到本地缓存
     * 标记可能先于注册到达，那一刻还没有属主可比对，只能推到这里复核
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        // 1. 本地已确认取消就立即返回；否则检查 Redis 中可能先于注册到达的预埋标记。
        if (taskInfo.cancelled.get()) {
            return true;
        }

        // 2. 取消标记里存的是发起方身份，不只是布尔值，任务注册后仍要再次核对属主。
        RBucket<String> bucket = redissonClient.getBucket(cancelKey(taskId));
        String requester = bucket.get();
        if (requester == null) {
            return false;
        }
        // 注册后再检查取消标记的发起方，避免属主登记前写入的他人标记生效。
        if (!isRequesterAllowed(taskInfo, requester)) {
            log.warn("忽略非属主埋下的取消标记，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return false;
        }
        // 3. 只有属主复核通过才把取消结果写回本地，后续绑定句柄时也能立刻感知。
        taskInfo.cancelled.set(true);
        return true;
    }

    /**
     * 系统侧回收无条件放行；用户侧只认精确属主，属主还没落地（注册未发生）时一律不认
     * 这正是预埋标记要在 register 那一刻复核的窗口，本地放过去反而绕开了复核
     */
    private boolean isRequesterAllowed(StreamTaskInfo taskInfo, String requester) {
        // 系统回收直接允许；用户请求必须与已经登记的属主精确匹配，未登记属主时不放行。
        if (SYSTEM_REQUESTER.equals(requester)) {
            return true;
        }
        return StrUtil.isNotBlank(taskInfo.ownerUserId) && taskInfo.ownerUserId.equals(requester);
    }

    /**
     * 只处理本节点登记的任务，复核发起方并通过 CAS 抢占取消权。
     * 先执行上游中断，再运行引擎提供的收尾回调；重复广播不会重复取消。
     */
    private void cancelLocal(String taskId, String requester) {
        // 广播会到达所有节点，只有实际持有本地任务记录的节点进入取消流程。
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 执行端复核发起方：taskId 时间有序可预测，越权取消喷得中就成
        // 不匹配时连 cancelled 都不置——置了会让 register 的复核短路，等于把标记复核那道门绕开
        if (!isRequesterAllowed(taskInfo, requester)) {
            log.warn("拒绝越权取消流式任务，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        // 先打断模型或 Agent 上游，再执行个性化收尾，保留已生成内容。
        if (taskInfo.cancelAction != null) {
            taskInfo.cancelAction.run();
        }

        // 在取消时执行收尾回调，保存已累积的内容
        if (taskInfo.finalizer != null) {
            taskInfo.finalizer.run();
        }
    }

    /**
     * 移除本地任务登记，并异步删除 Redis 的取消标记和属主记录；不等待删除完成。
     */
    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
        redissonClient.getBucket(ownerKey(taskId)).deleteAsync();
    }

    /**
     * 构造指定任务的 Redis 取消标记键，与任务属主键分开保存。
     */
    private String cancelKey(String taskId) {
        // 将任务 ID 拼到取消键前缀，用于保存发起方身份和覆盖先取消后注册的时间窗口。
        return CANCEL_KEY_PREFIX + taskId;
    }

    /**
     * 构造指定任务的 Redis 属主键，供跨节点的主动停止请求校验用户身份。
     */
    private String ownerKey(String taskId) {
        // 属主使用独立键保存，任意实例收到用户停止请求时都能先核对身份。
        return OWNER_KEY_PREFIX + taskId;
    }

    /**
     * 从本地缓存取得任务信息，不存在时原子加载空记录，随后由 register 绑定属主与回调。
     */
    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        // 缓存加载保证同一个 taskId 复用同一份取消标志和回调容器。
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile String ownerUserId;
        private volatile Runnable cancelAction;
        private volatile Runnable finalizer;
    }
}
