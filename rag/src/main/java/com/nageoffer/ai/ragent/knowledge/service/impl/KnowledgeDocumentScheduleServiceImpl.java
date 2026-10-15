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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.schedule.CronScheduleHelper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleServiceImpl implements KnowledgeDocumentScheduleService {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper scheduleExecMapper;
    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    /**
     * 按文档当前来源与定时配置创建或更新调度记录。
     * 仅 URL 文档参与调度，文档停用或 cron 为空会让调度停用。
     */
    @Override
    public void upsertSchedule(KnowledgeDocumentDO documentDO) {
        // allowCreate=true 允许首次分块时建立调度记录。
        syncSchedule(documentDO, true);
    }

    /**
     * 同步已经存在的文档调度记录，但不主动创建新调度。
     * 用于文档启停等状态同步，避免只操作启用状态就额外生成调度任务。
     */
    @Override
    public void syncScheduleIfExists(KnowledgeDocumentDO documentDO) {
        // allowCreate=false 时找不到调度记录便直接返回。
        syncSchedule(documentDO, false);
    }

    /**
     * 按 URL 文档有效状态计算调度启用标记和下一次运行时间。
     * 启用调度时验证 cron 周期；不存在的记录是否允许创建由 allowCreate 决定。
     */
    private void syncSchedule(KnowledgeDocumentDO documentDO, boolean allowCreate) {
        // 文件来源不参与定时拉取，文档或关键标识为空时直接跳过同步。
        if (documentDO == null) {
            return;
        }
        if (documentDO.getId() == null || documentDO.getKbId() == null) {
            return;
        }
        if (!SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            return;
        }
        boolean docEnabled = documentDO.getEnabled() == null || documentDO.getEnabled() == 1;
        String cron = documentDO.getScheduleCron();
        boolean enabled = documentDO.getScheduleEnabled() != null && documentDO.getScheduleEnabled() == 1;
        if (!StringUtils.hasText(cron)) {
            enabled = false;
        }
        if (!docEnabled) {
            enabled = false;
        }

        Date nextRunTime = null;
        if (enabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(cron, new Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
                nextRunTime = CronScheduleHelper.nextRunTime(cron, new Date());
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        KnowledgeDocumentScheduleDO existing = scheduleMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getDocId, documentDO.getId())
                        .last("LIMIT 1")
        );

        if (existing == null) {
            if (!allowCreate) {
                return;
            }
            KnowledgeDocumentScheduleDO schedule = KnowledgeDocumentScheduleDO.builder()
                    .docId(documentDO.getId())
                    .kbId(documentDO.getKbId())
                    .cronExpr(cron)
                    .enabled(enabled ? 1 : 0)
                    .nextRunTime(nextRunTime)
                    .build();
            scheduleMapper.insert(schedule);
        } else {
            scheduleMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentScheduleDO>()
                            .eq(KnowledgeDocumentScheduleDO::getId, existing.getId())
                            .set(KnowledgeDocumentScheduleDO::getCronExpr, cron)
                            .set(KnowledgeDocumentScheduleDO::getEnabled, enabled ? 1 : 0)
                            .set(KnowledgeDocumentScheduleDO::getNextRunTime, nextRunTime)
            );
        }
    }

    /**
     * 删除文档对应的定时抓取执行记录与调度配置。
     * 空文档 ID 不执行查询，执行明细先于调度主记录清理。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        // 文档删除时复用该方法，防止已删除来源仍被定时任务再次拉取。
        if (!StringUtils.hasText(docId)) {
            return;
        }
        scheduleExecMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleExecDO>()
                .eq(KnowledgeDocumentScheduleExecDO::getDocId, docId));
        scheduleMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                .eq(KnowledgeDocumentScheduleDO::getDocId, docId));
    }
}
