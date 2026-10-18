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

package com.nageoffer.ai.ragent.infra.token;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 轻量 Token 估算服务
 */
@Service
public class HeuristicTokenCounterService implements TokenCounterService {
    // 启发式 Token 估算器：ASCII 约 4 字符一个 Token、中日韩字符约一个字符一个 Token、其他约 2 字符一个 Token，跳过空白，不是模型精确分词。

    /**
     * 按 ASCII 约四字符一 token、CJK 每字符一 token、其他约两字符一 token 估算，空白不计。
     * 这是启发式估算，不是目标模型 tokenizer 的精确分词结果。
     */
    @Override
    public Integer countTokens(String text) {
        // 1. 无有效文本时返回 0，其他文本按 Java char 分类计数，这是字符启发式而非模型精确分词。
        if (!StringUtils.hasText(text)) {
            return 0;
        }

        int asciiCount = 0;
        int cjkCount = 0;
        int otherCount = 0;

        // 2. 跳过空白后分别统计 ASCII、列举的中日韩字符和其他字符，各类使用不同估算比例。
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch <= 0x7F) {
                asciiCount++;
            } else if (isCjk(ch)) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }

        int asciiTokens = (asciiCount + 3) / 4; // 英文等按 4 字符约 1 token
        int otherTokens = (otherCount + 1) / 2; // 其他字符按 2 字符约 1 token
        int total = asciiTokens + cjkCount + otherTokens;
        // 3. 含有效文本时至少返回 1，避免极短输入因比例折算成 0；这里不能替代模型 tokenizer。
        return Math.max(total, 1);
    }

    /**
     * 判断字符是否属于配置列举的中日韩文字、假名、韩文及相关符号 Unicode 区块。
     */
    private boolean isCjk(char ch) {
        // 根据当前 char 所属 Unicode 区块识别列举的中日韩文字及符号，供估算器选择每字符一 token 的口径。
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
