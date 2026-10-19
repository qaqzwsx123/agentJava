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

package com.nageoffer.ai.ragent.core.chunk.blockaware;

import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题处理器：按原始 heading 级别弹栈，维护调度器持有的章节路径
 * <p>
 * 无状态，摄取并发共用同一实例，路径由调用方持有并逐块传入
 */
@Component
public class HeadingHandler {
    // 章节路径维护器：遇到新标题时按原始标题级别移除同级及更深的旧标题，再追加新标题，使后续段落、表格等内容知道自己属于哪个章节。

    /**
     * 章节路径连同各级的原始 heading 级别
     * <p>
     * 级别必须一起留着：只看路径深度无法判断新标题该挂在哪一级下，不以 H1 开头的文档会把同级章节层层嵌套
     */
    public record Outline(List<String> path, List<Integer> levels) {
        // 不可变章节大纲：同时保存标题路径及各标题的原始级别，避免把路径深度误当成标题级别；更新大纲时返回新对象。

        public static final Outline EMPTY = new Outline(List.of(), List.of());

        public Outline {
            // 分别复制章节名称和级别列表，保证后续更新大纲不会反向改变旧上下文。
            path = path == null ? List.of() : List.copyOf(path);
            levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    /**
     * 根据 heading 更新章节路径，入参与返回值都不可变
     */
    public Outline update(Outline current, HeadingBlock heading) {
        // 保留比新标题级别更浅的祖先，再追加当前标题，构造新的不可变章节路径与级别列表。
        Outline base = current == null ? Outline.EMPTY : current;
        if (heading == null) {
            return base;
        }
        int level = Math.max(1, heading.level());

        // 弹掉同级与更深的祖先，真正的父级是最近一个级别更小的标题
        int keep = base.levels().size();
        while (keep > 0 && base.levels().get(keep - 1) >= level) {
            keep--;
        }

        List<String> path = new ArrayList<>(base.path().subList(0, keep));
        List<Integer> levels = new ArrayList<>(base.levels().subList(0, keep));
        path.add(heading.text() == null ? "" : heading.text());
        levels.add(level);
        return new Outline(path, levels);
    }
}
