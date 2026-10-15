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
        // 1. 调用方未给业务键时生成随机值，再把相同键同时放入消息包装器与 RocketMQ 消息头。
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        // 2. 业务载荷统一包进 MessageWrapper，业务 key 同时写入消息头以支持消息检索。
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();

        SendResult sendResult;
        try {
            // 3. 同步等待 Broker 发送结果；调用抛错时保留原异常并交给业务方决定重试。
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
        // 1. 为业务键提供缺省值，并生成独立事务 ID，后者用于定位本次消息对应的本地回调。
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        String txId = UUID.randomUUID().toString();

        // 回调必须先登记，后续半消息触发的本地事务监听才能通过 txId 找到业务动作。
        transactionListener.registerLocalTransaction(txId, localTransaction);

        // 2. 业务载荷统一包进 MessageWrapper，业务 key 同时写入消息头以支持消息检索。
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();

        TransactionSendResult sendResult;
        try {
            // 3. 发送半消息并触发本地事务监听；回调使用消息头里的事务 ID 找到刚登记的业务动作。
            sendResult = rocketMQTemplate.sendMessageInTransaction(topic, message, null);
        } catch (Throwable ex) {
            // 4. 发送异常时主动撤销尚未触发的回调，避免临时表持续积累。
            transactionListener.unregisterLocalTransaction(txId);
            log.error("[生产者] {} - 事务消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }
        // 5. 即使没有抛异常，发送状态失败也要清理回调；具体结果仍记录在日志中。
        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
            transactionListener.unregisterLocalTransaction(txId);
        }

        log.info("[生产者] {} - 事务消息发送结果: {}, 本地事务状态: {}, 消息ID: {}, Keys: {}",
                bizDesc, sendResult.getSendStatus(), sendResult.getLocalTransactionState(), sendResult.getMsgId(), keys);
    }
}
