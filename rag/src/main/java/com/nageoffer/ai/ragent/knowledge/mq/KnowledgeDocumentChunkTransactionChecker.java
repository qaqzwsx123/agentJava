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

package com.nageoffer.ai.ragent.knowledge.mq;

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档分块事务消息回查器
 * <p>
 * 按 topic 注册，Broker 回查时可路由到任意实例，通过查询 DB 中文档状态判断本地事务是否已提交
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkTransactionChecker implements TransactionChecker<KnowledgeDocumentChunkEvent> {
    // 文档分块事务消息回查器 按 topic 注册，Broker 回查时可路由到任意实例，通过查询 DB 中文档状态判断本地事务是否已提交。

    private final KnowledgeDocumentMapper documentMapper;
    private final DelegatingTransactionListener transactionListener;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    @PostConstruct
    public void init() {
        // 把文档分块事务回查器注册到分块消息主题。
        transactionListener.registerChecker(chunkTopic, this);
    }

    @Override
    public Class<KnowledgeDocumentChunkEvent> bodyType() {
        // 声明事务消息体类型，供统一监听器正确解析分块事件。
        return KnowledgeDocumentChunkEvent.class;
    }

    @Override
    public boolean check(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        // 按文档 ID 查询本地事务结果，只有文档存在且仍为 RUNNING 才认为分块消息应继续投递。
        log.info("[事务回查] 文档分块，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeDocumentChunkEvent event = message.getBody();
        String docId = event.getDocId();
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);

        return documentDO != null
                && DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus());
    }
}
