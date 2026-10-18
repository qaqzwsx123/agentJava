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

package com.nageoffer.ai.ragent.framework.convention;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐问题 grounding 片段
 * 由检索片段按文档取最高分、截断文本后得到，随 assistant 消息落库，
 * 供推荐追问问题生成时 grounding：保证追问落在系统已掌握的证据面内（可答）、与已答内容发散（不集中）
 * 与 {@link SourceRef} 职责分离：SourceRef 面向来源面板/预览（摘录 100 字），
 * 本类面向推荐生成 grounding（片段文本更长）。两者均不参与模型回答上下文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
//在将 Java 对象转换成 JSON 字符串（序列化）时，如果某个字段的值为 null，则直接忽略该字段，不将其输出到最终的 JSON 结果中
public class  GroundingChunk {//生成推荐追问时使用的证据片段

    /**
     * 文档名称 供生成追问时识别证据所属文档
     */
    private String docName;

    /**
     * 片段全文 作为追问 grounding 的证据内容
     */
    private String text;
}
