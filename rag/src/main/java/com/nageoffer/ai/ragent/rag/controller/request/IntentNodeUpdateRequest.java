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

package com.nageoffer.ai.ragent.rag.controller.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeUpdateRequest {
    // 意图节点更新参数：接收名称、层级、父节点、描述和知识库或工具绑定等配置，供意图树服务校验后更新。

    private String name;
    private Integer level;
    private String parentCode;
    private String description;
    private List<String> examples;
    private String mcpToolId;
    private Integer requireConfirm;
    private String collectionName;
    private List<String> collectionNames;
    private Integer topK;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;
    private String promptSnippet;
    private String promptTemplate;
}
