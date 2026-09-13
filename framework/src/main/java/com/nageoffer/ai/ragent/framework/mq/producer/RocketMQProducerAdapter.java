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

package com.nageoffer.ai.ragent.framework.mq.producer;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 RocketMQ 的消息生产者
 */
@Slf4j
@RequiredArgsConstructor
public class RocketMQProducerAdapter implements MessageQueueProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final DelegatingTransactionListener transactionListener;

    /**
     * 为消息补齐业务键，包装载荷并设置 RocketMQ Keys，执行同步发送并返回结果，发送异常原样传播。
     */
    @Override
    public SendResult send(String topic, String keys, String bizDesc, Object body) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();

        SendResult sendResult;
        try {
            sendResult = rocketMQTemplate.syncSend(topic, message);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 发送结果: {}, 消息ID: {}, Keys: {}", bizDesc, sendResult.getSendStatus(), sendResult.getMsgId(), keys);
        return sendResult;
    }

    /**
     * 先登记本地事务回调，再发送带事务 ID 和主题的事务消息，供监听器执行关联事务。
     * 发送抛错或状态非 SEND_OK 时注销回调，避免留下无法完成的事务登记。
     */
    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        String txId = UUID.randomUUID().toString();

        // 回调必须先登记，后续半消息触发的本地事务监听才能通过 txId 找到业务动作。
        transactionListener.registerLocalTransaction(txId, localTransaction);

        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();

        TransactionSendResult sendResult;
        try {
            sendResult = rocketMQTemplate.sendMessageInTransaction(topic, message, null);
        } catch (Throwable ex) {
            transactionListener.unregisterLocalTransaction(txId);
            log.error("[生产者] {} - 事务消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }
        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
            transactionListener.unregisterLocalTransaction(txId);
        }

        log.info("[生产者] {} - 事务消息发送结果: {}, 本地事务状态: {}, 消息ID: {}, Keys: {}",
                bizDesc, sendResult.getSendStatus(), sendResult.getLocalTransactionState(), sendResult.getMsgId(), keys);
    }
}
