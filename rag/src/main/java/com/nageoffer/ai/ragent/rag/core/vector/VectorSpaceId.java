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

package com.nageoffer.ai.ragent.rag.core.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorSpaceId {
    // 物理向量空间标识：保存跨后端使用的空间名称及可选命名空间，供向量空间创建与校验使用，与知识库逻辑分区区分。

    /**
     * 逻辑名称：对业务层暴露的名字，跨引擎保持一致
     * 例如：kb_employee_policy
     */
    String logicalName;

    /**
     * 可选：命名空间 / 数据库 / 索引前缀 等
     * 例如：milvus database / ES 索引前缀
     */
    String namespace;
}
