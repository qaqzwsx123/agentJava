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

package com.nageoffer.ai.ragent.framework.config;

import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.mq.producer.RocketMQProducerAdapter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 消息队列自动装配配置
 */
@Configuration
public class RocketMQAutoConfiguration {

    @Bean
    public DelegatingTransactionListener delegatingTransactionListener() {
        // 创建按事务 ID 分派本地回调、按 topic 分派回查逻辑的共享监听器。
        return new DelegatingTransactionListener();
    }

    @Bean
    public MessageQueueProducer messageQueueProducer(RocketMQTemplate rocketMQTemplate,
                                                     DelegatingTransactionListener transactionListener) {
        // 将 RocketMQTemplate 和同一个事务监听器装配到生产者适配器，业务层只需依赖生产者接口。
        return new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);
    }
}
