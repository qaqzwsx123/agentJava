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

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

public final class RetrievedChunkKey {

    private RetrievedChunkKey() {
        // 去重键只通过静态工厂生成，工具类不需要实例。
    }

    /**
     * 优先用切片 ID 作为去重和归因键，缺失时对正文计算 SHA-256，空正文按空字符串计算。
     */
    public static String of(RetrievedChunk chunk) {
        // 有有效切片 ID 就直接复用；没有 ID 才对原始正文计算 SHA-256，null 正文按空串处理。
        return StrUtil.isNotBlank(chunk.getId())
                ? chunk.getId()
                : DigestUtil.sha256Hex(chunk.getText() == null ? "" : chunk.getText());
    }
}
