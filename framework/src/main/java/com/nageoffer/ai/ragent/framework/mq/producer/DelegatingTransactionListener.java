

package com.nageoffer.ai.ragent.framework.mq.producer;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用的 RocketMQ 事务消息监听器
 */
@Slf4j
@RocketMQTransactionListener
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {
    //事务消息监听器，负责执行本地数据库事务，以及将回查请求交给对应业务的检查器

    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    /**
     * 本地事务执行逻辑，per-message，仅当前实例有效
     */
    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();

    /**
     * 事务回查逻辑，per-topic，所有实例共享（Spring Bean 注册）
     */
    private final ConcurrentMap<String, TransactionChecker<?>> checkerMap = new ConcurrentHashMap<>();

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 按事务 ID 保存本地事务回调，供 RocketMQ 事务监听器定位本次业务动作。
     */
    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        // 按本次事务 ID 暂存业务回调，稍后的本地事务监听会取走它；这份回调仅存在当前进程。
        localTransactionMap.put(txId, localTransaction);
    }

    void unregisterLocalTransaction(String txId) {
        // 半消息发送失败时清除尚未消费的回调，避免内存里留下无法触发的事务动作。
        localTransactionMap.remove(txId);
    }

    public void registerChecker(String topic, TransactionChecker<?> checker) {
        // 按主题保存可重用的回查器，Broker 回查时使用消息载荷查询业务提交结果。
        checkerMap.put(topic, checker);
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        // 1. 从消息头取事务 ID，并以 remove 方式取走本次回调，避免执行完成后继续保留引用。
        String txId = (String) message.getHeaders().get(HEADER_TX_ID);
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        // 2. 缺少本地动作无法证明业务已提交，直接回滚半消息，不凭空向消费者投递。
        if (localTransaction == null) {
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 3. 在数据库事务中执行回调，使用框架传入的 arg；正常提交返回 COMMIT，异常则由事务模板回滚。
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        // 1. 从消息头读取 topic 并查找回查器；找不到实现时无法确认提交，按当前策略返回回滚。
        String topic = (String) message.getHeaders().get(HEADER_TOPIC);
        TransactionChecker<?> checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 2. 基于持久化业务状态回查，不依赖发送实例的临时回调表；明确 true/false 决定提交或回滚。
            boolean committed = check(checker, (byte[]) message.getPayload());
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            // 3. 回查异常不等于业务已回滚，返回 UNKNOWN，让 Broker 后续仍可再次回查。
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private <T> boolean check(TransactionChecker<T> checker, byte[] payload) throws IOException {
        // 1. 使用回查器声明的业务类型构造 MessageWrapper<T> 的 Jackson 类型，避免载荷只反序列化成普通 Map。
        JavaType messageType = objectMapper.getTypeFactory()
                .constructParametricType(MessageWrapper.class, checker.bodyType());
        // 2. 先还原带正确泛型载荷的包装器，再交给具体业务检查器查库判断是否提交。
        MessageWrapper<T> wrapper = objectMapper.readValue(payload, messageType);
        return checker.check(wrapper);
    }
}
