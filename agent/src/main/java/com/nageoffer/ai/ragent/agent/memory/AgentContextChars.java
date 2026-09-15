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

package com.nageoffer.ai.ragent.agent.memory;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上下文体量口径：字符数作为 token 的粗代理，非文本块按零计
 * 链路追踪也复用此口径，保证两处数字可比
 */
//这个com.nageoffer.ai.ragent.agent.memory;memory下面的这 20 个类，主要负责上下文裁剪、摘要压缩和长期记忆抽取
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentContextChars {

    /**
     * 累计上下文所有消息的字符数，供裁剪、摘要压缩和链路统计共用。
     * 这里只估算文本体量，不是模型实际使用的 token 数。
     */
    public static int total(List<Msg> context) {
        int sum = 0;
        for (Msg msg : context) {
            sum += of(msg);
        }
        return sum;
    }

    /**
     * 统计一条消息中所有内容块的字符数；消息或内容列表为空时返回 0。
     */
    static int of(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return 0;
        }
        int sum = 0;
        // 同一消息可能包含文本、思考和工具调用，逐块累加才能覆盖完整体量。
        for (ContentBlock block : msg.getContent()) {
            sum += of(block);
        }
        return sum;
    }

    /**
     * 按内容块类型统计字符：文本、思考文本、工具名与入参，以及工具结果中的嵌套输出。
     * 图片等未识别的内容块返回 0，不计入当前字符预算。
     */
    private static int of(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return length(text.getText());
        }
        if (block instanceof ThinkingBlock thinking) {
            return length(thinking.getThinking());
        }
        if (block instanceof ToolUseBlock toolUse) {
            return length(toolUse.getName())
                    + (toolUse.getInput() == null ? 0 : toolUse.getInput().toString().length());
        }
        // 工具结果本身是容器，字符数取决于其中实际输出的内容。
        if (block instanceof ToolResultBlock result) {
            return ofOutput(result);
        }
        return 0;
    }

    /**
     * 递归累计工具结果中各输出块的字符数；没有输出时返回 0。
     */
    static int ofOutput(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return 0;
        }
        int sum = 0;
        for (ContentBlock nested : block.getOutput()) {
            sum += of(nested);
        }
        return sum;
    }

    /**
     * 返回字符串长度；用 0 统一处理 null，避免各内容块重复判空。
     */
    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
