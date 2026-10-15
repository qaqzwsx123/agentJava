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

package com.nageoffer.ai.ragent.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class ScheduleStateManager {

    private static final String LEASE_LOST_NOTE = "（调度锁已失效，未写回调度状态）";

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper execMapper;

    public boolean markSkippedIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult) {
        // 用锁令牌限制调度主记录更新，同时结束本轮执行记录；未获主状态写入权时在执行说明追加租约提示。
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, fetchResult.message())
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.SKIPPED.getCode());
            execUpdate.setMessage(withLeaseNote(fetchResult.message(), scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markSkippedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String message) {
        // 用锁令牌限制调度主记录更新，同时结束本轮执行记录；未获主状态写入权时在执行说明追加租约提示。
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, message)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SKIPPED.getCode())
                    .message(withLeaseNote(message, scheduleUpdated))
                    .endTime(new Date())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markSuccessIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult,
                                      StoredFileDTO stored) {
        // 按当前锁所有权更新成功时间与远端标识，再保存本轮执行的文件和结果信息。
        Date endTime = new Date();
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastSuccessTime, endTime)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SUCCESS.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, null)
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SUCCESS.getCode())
                    .message(withLeaseNote("刷新成功", scheduleUpdated))
                    .endTime(endTime)
                    .fileName(stored.getOriginalFilename())
                    .fileSize(stored.getSize())
                    .contentHash(fetchResult.contentHash())
                    .etag(fetchResult.etag())
                    .lastModified(fetchResult.lastModified())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean markFailedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String errorMessage) {
        // 先截断错误说明，再按令牌更新调度失败状态，本轮执行记录无论是否仍持锁都保留结束信息。
        String truncatedErrorMessage = truncate(errorMessage);
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncatedErrorMessage)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
            execUpdate.setMessage(withLeaseNote(truncatedErrorMessage, scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    public boolean disableIfOwned(ScheduleLockLease lease, String reason) {
        // 持锁条件命中时关闭调度并清空下次执行时间，记录失败原因，返回是否实际更新。
        return updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getEnabled, 0)
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, null)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncate(reason))
        );
    }

    public void markLeaseLost(ScheduleStateContext ctx, String stage) {
        // 只把当前执行记录标记为租约丢失，不覆盖可能已由新执行者接管的调度主状态。
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        String message = "调度锁已失效，终止执行";
        if (StringUtils.hasText(stage)) {
            message += ": " + stage;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
        execUpdate.setId(ctx.getExecId());
        execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
        execUpdate.setMessage(truncate(message));
        execUpdate.setEndTime(new Date());
        execMapper.updateById(execUpdate);
    }

    public void markSuccessExecOnly(ScheduleStateContext ctx,
                                    StoredFileDTO stored,
                                    String contentHash,
                                    String etag,
                                    String lastModified,
                                    String message) {
        // 文件刷新已完成但调度主状态写回异常时，仅保存本轮执行成功事实与文件元数据。
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                .id(ctx.getExecId())
                .status(ScheduleRunStatus.SUCCESS.getCode())
                .message(truncate(message))
                .endTime(new Date())
                .fileName(stored != null ? stored.getOriginalFilename() : null)
                .fileSize(stored != null ? stored.getSize() : null)
                .contentHash(contentHash)
                .etag(etag)
                .lastModified(lastModified)
                .build();
        execMapper.updateById(execUpdate);
    }

    private boolean updateScheduleIfOwned(ScheduleLockLease lease,
                                          LambdaUpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper) {
        // 为更新附加调度 ID 和锁令牌条件，用受影响行数判断本轮是否仍有写主状态的资格。
        if (lease == null || updateWrapper == null) {
            return false;
        }
        updateWrapper.eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken());
        return scheduleMapper.update(updateWrapper) > 0;
    }

    private String withLeaseNote(String message, boolean scheduleUpdated) {
        // 成功写主状态时沿用原消息，否则追加租约失效说明，并统一限制文本长度。
        if (scheduleUpdated) {
            return truncate(message);
        }
        String baseMessage = StringUtils.hasText(message) ? message.trim() : "执行完成";
        return truncate(baseMessage + LEASE_LOST_NOTE);
    }

    private String truncate(String value) {
        // 清理首尾空白并将说明限制到五百一十二字符，避免调度结果超过字段长度。
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 512) {
            return trimmed;
        }
        return trimmed.substring(0, 512);
    }
}
