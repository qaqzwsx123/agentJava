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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.List;

/**
 * 用户查询改写：将自然语言问题改写成适合 RAG 检索的查询语句
 */
public interface QueryRewriteService {

    /**
     * 将用户问题改写为适合向量 / 关键字检索的简洁查询
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询（如果改写失败，则回退原问题）
     */
    String rewrite(String userQuestion);

    /**
     * 可选：改写 + 拆分多问句
     * 默认实现仅返回改写结果并将其作为单个子问题
     */
    default RewriteResult rewriteWithSplit(String userQuestion) {
        // 默认实现把一次改写作为唯一子问题；带历史的兼容重载仍委托不带历史的方法，需感知历史的实现自行覆盖。
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    /**
     * 可选：改写 + 拆分多问句，支持会话历史
     * 默认实现忽略历史，回退到基础改写逻辑
     */
    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        // 默认实现把一次改写作为唯一子问题；带历史的兼容重载仍委托不带历史的方法，需感知历史的实现自行覆盖。
        return rewriteWithSplit(userQuestion);
    }
}
