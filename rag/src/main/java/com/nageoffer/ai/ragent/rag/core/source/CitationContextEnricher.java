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

package com.nageoffer.ai.ragent.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为已格式化的知识库上下文注入请求级引用编号
 * <p>
 * 上下文格式化阶段只写入内部 {@code data-ragent-doc-id}，来源装配完成后再依据
 * {@link SourceRef#getIndex()} 替换为模型可见的 {@code ref}。这样 Prompt、SSE、落库和前端
 * 始终复用同一份来源编号，同时不把内部文档 ID 暴露给模型
 * <p>
 * 引用开关关闭时按「无来源」处理：只抹掉内部锚点、不注入编号。无论开关如何，
 * 上下文都必须先过这一道，内部 docId 才不会漏进模型可见文本
 */
@Component
@RequiredArgsConstructor
public class CitationContextEnricher {

    private static final Pattern CONTENT_TAG = Pattern.compile(
            "(?m)^<content([^>]*) data-ragent-doc-id=\"([^\"]*)\">$");

    private final RAGConfigProperties ragConfigProperties;

    /**
     * 按引用开关建立文档到来源编号的映射，随后替换上下文内部文档锚点。
     */
    public String enrich(String kbContext, List<SourceRef> sources) {
        // 引用开启时建立文档到来源序号的映射，再统一替换内部锚点；关闭时仍清除内部文档 ID。
        Map<String, Integer> indexByDocId = Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())
                ? indexByDocId(sources)
                : Map.of();
        return replaceAnchors(kbContext, indexByDocId);
    }

    /**
     * 只抹内部锚点不注入编号，供没有角标渲染能力的调用方使用（如 Agent 模式的检索工具）
     */
    public String stripDocIdAnchors(String kbContext) {
        // 使用空引用映射处理锚点，只删除内部文档标识，不生成外部引用序号。
        return replaceAnchors(kbContext, Map.of());
    }

    /**
     * 移除 content 标签中的内部文档 ID，有来源编号时写入 ref；未映射或关闭引用时只去除内部标识。
     */
    private String replaceAnchors(String kbContext, Map<String, Integer> indexByDocId) {
        // 扫描 content 标签，只给来源列表中存在的文档补 ref，其余去掉内部 ID 并保持标签其他属性。
        if (StrUtil.isBlank(kbContext)) {
            return StrUtil.emptyIfNull(kbContext);
        }

        Matcher matcher = CONTENT_TAG.matcher(kbContext);
        StringBuilder result = new StringBuilder(kbContext.length());
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String docId = matcher.group(2);
            // 只有映射到来源列表的文档才写 ref，其他锚点仍会去掉内部 docId。
            Integer index = indexByDocId.get(docId);
            String replacement = index == null
                    ? "<content" + attributes + ">"
                    : "<content" + attributes + " ref=\"" + index + "\">";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 按来源列表建立文档到引用序号的映射，过滤无效项，同一文档保留首个编号。
     */
    private Map<String, Integer> indexByDocId(List<SourceRef> sources) {
        // 过滤空来源和缺失文档 ID、序号的项，同文档首次出现的来源序号优先。
        if (CollUtil.isEmpty(sources)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (SourceRef source : sources) {
            if (source == null
                    || StrUtil.isBlank(source.getDocId())
                    || source.getIndex() == null) {
                continue;
            }
            result.putIfAbsent(source.getDocId(), source.getIndex());
        }
        return result;
    }
}
