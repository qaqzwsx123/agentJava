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

package com.nageoffer.ai.ragent.ingestion.controller.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class IngestionPipelineNodeVO {
    // 摄取流水线节点返回对象：向前端提供节点 ID、类型、参数、执行条件和后继节点，使编辑页面能够还原流水线连线。

    private String id;

    private String nodeId;

    private String nodeType;

    private JsonNode settings;

    private JsonNode condition;

    private String nextNodeId;
}
