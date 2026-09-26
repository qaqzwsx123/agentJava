/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.nageoffer.ai.ragent.initializer.AgentChatClient.AgentTurnResult;
import com.nageoffer.ai.ragent.initializer.AgentMemoryProbe.Extraction;
import com.nageoffer.ai.ragent.initializer.AgentMemoryProbe.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 长期记忆机制回归：造现场（并发、开关、容量）再验管道行为，与剧本式回归分开跑
 * 每条用例跑完把生效集还原成基线，用例之间互不污染，也不动这个账号本来就有的记忆
 */
final class AgentMemoryMechanismMain {

    /**
     * flush_memory 的工具名，与 MemoryFlushTool.TOOL_NAME 同源，改了那边这里必须同步
     */
    private static final String FLUSH_TOOL = "flush_memory";

    /**
     * 抢占窗口的轮询间隔与总时长；仲裁是一次模型调用，秒级，逮不住多半是没触发抽取
     */
    private static final long POLL_INTERVAL_MILLIS = 200L;
    private static final long RACE_WINDOW_MILLIS = 60_000L;
    private static final long SETTLE_WINDOW_MILLIS = 180_000L;

    /**
     * 后台抽取挂在轮次收尾之后，异步起步，这里给它一点时间落到 PROCESSING
     */
    private static final long BACKGROUND_GRACE_MILLIS = 2_000L;

    /**
     * 逮到抽取再等一下才动库：仲裁读生效集紧跟在抽取建起来之后，改早了模型压根没看见那条记忆
     */
    private static final long MUTATION_DELAY_MILLIS = 600L;

    /**
     * 伪造水位不用等仲裁读完，它只要赶在提交事务读水位之前落地，越早越稳
     * 实测从建起来到结算可以短到 826 毫秒，再等 600 毫秒就骑在窗口边上了
     */
    private static final long NO_MUTATION_DELAY = 0L;

    /**
     * 各用例的判定词，回答或生效集里认它即算命中
     * 一律要躲开账号里已有的记忆，撞上就分不清是本次记住的还是本来就有的
     */
    private static final String DIOPTER = "375";
    private static final String KEYBOARD = "HHKB";
    private static final String PET = "豆豆";
    private static final String OLD_CITY = "杭州";
    private static final String NEW_CITY = "南京";

    private static final List<String> ALL_CASES =
            List.of("M1", "M2", "M3", "M4", "M6", "M7", "M8", "R1", "R2", "R3", "R4", "R5");

    private AgentMemoryMechanismMain() {
    }

    public static void main(String[] args) {
        RegressionContext.run(args, AgentMemoryMechanismMain::execute);
    }

    private static void execute(RegressionContext context) throws Exception {
        String userId = context.login();
        List<String> selected = selectCases(context.argument("case", "all"));
        printEnvironment(context, userId, selected);

        List<Check> checks = new ArrayList<>();
        for (String name : selected) {
            System.out.println();
            System.out.println("[regression] === " + name + " " + caseTitle(name) + " ===");
            List<Item> baseline = context.memory().activeItems(userId);
            try {
                checks.addAll(runCase(name, context, userId));
            } catch (Exception ex) {
                // 用例炸了不中断其余用例，最终判定在报告里给
                System.out.println("    用例执行异常: " + ex.getMessage());
                checks.add(new Check(name, "用例执行", Status.FAIL, "抛出异常: " + ex.getMessage()));
            } finally {
                context.memory().purgeForged(userId);
                int restored = context.memory().restore(userId, baseline);
                System.out.println("    收尾：生效集还原到基线 " + baseline.size() + " 条，改动 " + restored + " 行");
            }
        }

        printChecks(checks);
        long failed = checks.stream().filter(check -> check.status() == Status.FAIL).count();
        long uncovered = checks.stream().filter(check -> check.status() == Status.UNCOVERED).count();
        if (failed > 0) {
            throw new IllegalStateException("机制回归未通过，失败项 " + failed + " 条，详见上方判定表");
        }
        System.out.println();
        System.out.println("[regression] SUCCESS"
                + (uncovered > 0 ? "（有 " + uncovered + " 项未被本次覆盖，见判定表 UNCOVERED）" : ""));
    }

    private static List<Check> runCase(String name, RegressionContext context, String userId) throws Exception {
        return switch (name) {
            case "M1" -> revisionRaceCase(context, userId);
            case "M2" -> watermarkRaceCase(context, userId);
            case "M3" -> flushBelowThresholdCase(context, userId);
            case "M4" -> noopWatermarkCase(context, userId);
            case "M6" -> retractCase(context, userId);
            case "M7" -> staleTargetCase(context, userId);
            case "M8" -> firstMessageCase(context, userId);
            case "R1" -> crossSessionCase(context, userId);
            case "R2" -> retractPersistCase(context, userId);
            case "R3" -> supersedeCase(context, userId);
            case "R4" -> watermarkLedgerCase(context, userId);
            case "R5" -> capacityCase(context, userId);
            default -> throw new IllegalArgumentException("未知用例: " + name);
        };
    }

    // ---------------------------------------------------------------- M1：版本号失配整批拒绝

    /**
     * 第一轮把水位垫起来，第二轮在抽取在飞时把版本号推高一档，再看它怎么结算
     * 要的是整批 CONFLICT、决策不落库、水位不动；后两条只在模型没就地重试时判得动
     */
    private static List<Check> revisionRaceCase(RegressionContext context, String userId) throws Exception {
        String name = "M1";
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();

        // 第一轮先正常结算一次，水位才有个非空的基准值，「没动」这句话才有内容
        String conversationId = ask(context, null,
                "我叫程野，在成都做后端开发。请立刻调用记忆整理工具存下来。").conversationId();
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);

        List<Item> before = probe.activeItems(userId);
        String watermarkBefore = probe.watermark(userId, conversationId);
        long revisionBefore = probe.control(userId).revision();

        Racer racer = Racer.start(probe, userId, RACE_WINDOW_MILLIS, extraction -> {
            System.out.println("    逮到在飞抽取 " + extraction.id() + "，把版本号推高一档");
            probe.bumpRevision(userId);
        });
        AgentTurnResult turn = ask(context, conversationId,
                "我平时的主力语言是 Java，编辑器固定用 IntelliJ IDEA。请立刻调用记忆整理工具存下来。");
        Extraction caught = racer.await();
        if (caught == null) {
            checks.add(new Check(name, "逮住在飞抽取", Status.UNCOVERED,
                    "整个窗口都没逮到 PROCESSING 抽取，构造不出「提交时快照已过期」的现场；实际工具 "
                            + tools(turn)));
            return checks;
        }

        Extraction settled = awaitTerminal(probe, caught.id(), SETTLE_WINDOW_MILLIS);
        List<Item> after = probe.activeItems(userId);
        String watermarkAfter = probe.watermark(userId, conversationId);
        // 模型吃到 CONFLICT 常在同一轮里再调一次工具，那次是合法重试：它会正当地落库、把水位推到同一个末条
        // 生效集与水位是全局量，有重试就归属不到被测那批，这时候判红等于拿别人的成功给它定罪
        List<Extraction> retries = probe.extractions(userId, conversationId).stream()
                .filter(item -> !item.id().equals(caught.id()))
                .filter(item -> item.toMessageId().equals(caught.toMessageId()))
                .toList();

        checks.add(new Check(name, "整批记 CONFLICT",
                settled != null && "CONFLICT".equals(settled.status()) ? Status.PASS : Status.FAIL,
                "结算状态 " + (settled == null ? "始终 PROCESSING" : settled.status())));
        // 归属得到被测那批的只有台账这一格：拒收路径写死 0，写过东西再回滚也留不下这个 0
        checks.add(new Check(name, "被拒那批一条决策都没记",
                settled != null && settled.decisionCount() == 0 ? Status.PASS : Status.FAIL,
                "台账决策数 " + (settled == null ? "-" : settled.decisionCount())));
        if (retries.isEmpty()) {
            checks.add(new Check(name, "决策一条没落库",
                    sameIds(before, after) ? Status.PASS : Status.FAIL,
                    "生效条目 " + before.size() + " -> " + after.size()));
            checks.add(new Check(name, "水位没动",
                    watermarkBefore.equals(watermarkAfter) ? Status.PASS : Status.FAIL,
                    "水位 " + label(watermarkBefore) + " -> " + label(watermarkAfter)));
        } else {
            String detail = "同区间另有 " + retries.size() + " 次抽取（"
                    + String.join("；", retries.stream().map(Extraction::oneLine).toList()) + "）";
            checks.add(new Check(name, "决策一条没落库", Status.UNCOVERED,
                    detail + "，生效条目 " + before.size() + " -> " + after.size() + " 归不到被测那批头上"));
            checks.add(new Check(name, "水位没动", Status.UNCOVERED,
                    detail + "，重试盖的是同一个末条，水位 " + label(watermarkBefore) + " -> "
                            + label(watermarkAfter) + " 本就该推到这里"));
        }
        // 仲裁失败也记 CONFLICT，只有快照失配那条路会把尝试次数退回去，据此认领
        boolean retreated = settled != null && settled.attemptCount() < caught.attemptCount();
        checks.add(new Check(name, "尝试次数退回上一档",
                retreated ? Status.PASS : Status.FAIL,
                "快照过期不是这批内容的错，不该消耗重试额度；尝试次数 " + caught.attemptCount()
                        + " -> " + (settled == null ? "-" : settled.attemptCount())
                        + "，改动前版本号 " + revisionBefore
                        + (retreated ? "" : "；没退回说明这次 CONFLICT 来自仲裁失败而不是快照失配")));
        return checks;
    }

    /**
     * M2：版本号一动不动，只把水位推过去——这正是 revision 单独拦不住的那个洞
     */
    private static List<Check> watermarkRaceCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        String conversationId = ask(context, null,
                "我是李蔚，做数据平台的。请立刻调用记忆整理工具存下来。").conversationId();
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);

        List<Item> before = probe.activeItems(userId);
        long revisionBefore = probe.control(userId).revision();

        String[] forgedId = new String[1];
        Racer racer = Racer.start(probe, userId, RACE_WINDOW_MILLIS, NO_MUTATION_DELAY, extraction -> {
            // 伪造一条已结束的台账，末条取在飞那批的末条：水位越过预期值，版本号纹丝不动
            String forged = probe.forgeSettledExtraction(userId, extraction.conversationId(), extraction.toMessageId());
            forgedId[0] = forged;
            System.out.println("    逮到在飞抽取 " + extraction.id() + "，伪造已结束抽取 " + forged
                    + " 把水位推到 " + extraction.toMessageId());
        });
        AgentTurnResult turn = ask(context, conversationId,
                "我常用的数据库是 PostgreSQL，不喜欢用 ORM。请立刻调用记忆整理工具存下来。");
        Extraction caught = racer.await();
        if (caught == null) {
            checks.add(new Check("M2", "逮住在飞抽取", Status.UNCOVERED,
                    "整个窗口都没逮到 PROCESSING 抽取；实际工具 " + tools(turn)));
            return checks;
        }

        Extraction settled = awaitTerminal(probe, caught.id(), SETTLE_WINDOW_MILLIS);
        List<Item> after = probe.activeItems(userId);
        long revisionAfter = probe.control(userId).revision();

        // 只给红判定补一句现场，不改判定：这条判不准就等于替被测代码打掩护，宁可吵也不许吃掉红
        boolean lateForge = settled != null && !"CONFLICT".equals(settled.status()) && forgedId[0] != null
                && !probe.forgedLandedBefore(forgedId[0], caught.id());
        checks.add(new Check("M2", "整批记 CONFLICT",
                settled != null && "CONFLICT".equals(settled.status()) ? Status.PASS : Status.FAIL,
                "结算状态 " + (settled == null ? "始终 PROCESSING" : settled.status())
                        + (lateForge ? "；伪造行落在结算之后，提交事务多半早把水位读完了，先重跑一次再当回归查" : "")));
        checks.add(new Check("M2", "决策一条没落库",
                sameIds(before, after) ? Status.PASS : Status.FAIL,
                "生效条目 " + before.size() + " -> " + after.size()));
        checks.add(new Check("M2", "版本号确实没变过",
                revisionBefore == revisionAfter ? Status.PASS : Status.FAIL,
                "版本号 " + revisionBefore + " -> " + revisionAfter
                        + (revisionBefore == revisionAfter ? "，全靠水位这一道拦下来"
                        : "，版本号被推过说明这批不是纯靠水位拦下的，现场不成立")));
        return checks;
    }

    // ---------------------------------------------------------------- M3 / M4：flush 门槛与水位

    /**
     * M3：只攒了一轮就调 flush，也得跑起来——轮次门槛只挡后台那条路
     */
    private static List<Check> flushBelowThresholdCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        AgentTurnResult turn = ask(context, null,
                "记一下：我常驻的城市是杭州。请立刻调用记忆整理工具把它存下来。");
        if (!turn.tools().contains(FLUSH_TOOL)) {
            checks.add(new Check("M3", "本轮调到了整理工具", Status.UNCOVERED,
                    "模型本轮没调用 " + FLUSH_TOOL + "，用例没跑到；实际工具 " + tools(turn)));
            return checks;
        }
        List<Extraction> extractions = awaitExtractions(probe, userId, turn.conversationId(), 1, SETTLE_WINDOW_MILLIS);
        Extraction flush = extractions.stream().filter(item -> "FLUSH".equals(item.triggerType())).findFirst().orElse(null);

        checks.add(new Check("M3", "抽取确实建起来了",
                flush != null ? Status.PASS : Status.FAIL,
                flush == null ? "台账里没有 FLUSH 抽取，说明 flush 也被 "
                        + AgentMemoryProbe.EXTRACT_MIN_TURNS + " 轮门槛挡了" : flush.oneLine()));
        boolean singleTurn = flush != null && flush.fromMessageId().equals(flush.toMessageId());
        checks.add(new Check("M3", "素材只有这一轮",
                singleTurn ? Status.PASS : Status.FAIL,
                flush == null ? "-" : "区间 " + flush.fromMessageId() + ".." + flush.toMessageId()
                        + "，门槛是 " + AgentMemoryProbe.EXTRACT_MIN_TURNS + " 轮，这里"
                        + (singleTurn ? "只有 1 轮"
                        : "跨了不止一轮，这批压根不是「只剩一轮也照跑」的现场，门槛豁免没被验到")));
        checks.add(new Check("M3", "抽取已结到终态",
                flush != null && flush.terminal() ? Status.PASS : Status.FAIL,
                flush == null ? "-" : "状态 " + flush.status()));
        return checks;
    }

    /**
     * M4：判完没产出照样推水位，判据是「仲裁跑完了」不是「产出了东西」
     */
    private static List<Check> noopWatermarkCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        AgentTurnResult turn = ask(context, null,
                "帮我算一下 128 乘以 64 等于多少。算完请立刻调用记忆整理工具。");
        if (!turn.tools().contains(FLUSH_TOOL)) {
            checks.add(new Check("M4", "本轮调到了整理工具", Status.UNCOVERED,
                    "模型本轮没调用 " + FLUSH_TOOL + "，用例没跑到；实际工具 " + tools(turn)));
            return checks;
        }
        List<Extraction> extractions = awaitExtractions(probe, userId, turn.conversationId(), 1, SETTLE_WINDOW_MILLIS);
        String watermark = probe.watermark(userId, turn.conversationId());

        // 台账上每一条推水位的抽取都必须被水位覆盖，这条不变量与判没判出东西无关
        List<String> beyond = new ArrayList<>();
        for (Extraction extraction : extractions) {
            if (extraction.advancesWatermark() && extraction.toMessageId().compareTo(watermark) > 0) {
                beyond.add(extraction.oneLine());
            }
        }
        checks.add(new Check("M4", "终态抽取全被水位覆盖",
                beyond.isEmpty() ? Status.PASS : Status.FAIL,
                beyond.isEmpty() ? "水位 " + label(watermark) + "，覆盖 " + extractions.size() + " 条抽取"
                        : "越过水位的抽取: " + String.join(" / ", beyond)));

        // 认「结到终态且一条决策没落」而不是认 NOOP 这个字面量：状态写错了也得看得见，
        // 按字面量挑会让写错状态的抽取直接从视野里消失，报告反倒去怪模型没判出 NOOP
        Extraction empty = extractions.stream()
                .filter(item -> item.terminal() && item.decisionCount() == 0)
                .findFirst().orElse(null);
        boolean settledAsNoop = empty != null && "NOOP".equals(empty.status());
        checks.add(new Check("M4", "NOOP 抽取推进了水位",
                empty == null ? Status.UNCOVERED
                        : settledAsNoop && empty.toMessageId().compareTo(watermark) <= 0
                        ? Status.PASS : Status.FAIL,
                empty == null ? "本轮没判出 NOOP（模型仍从这句里挑出了可记的东西），NOOP 分支没跑到；抽取 "
                        + describe(extractions)
                        : !settledAsNoop ? "判完一条没落却结成了 " + empty.status()
                        + "，只有 NOOP 推水位，这批区间会被重抽；抽取 " + empty.oneLine()
                        : "NOOP 末条 " + empty.toMessageId() + "，水位 " + watermark));
        return checks;
    }

    // ---------------------------------------------------------------- M6：对话撤回

    /**
     * M6：撤回之后旧条目不再注入，且同一次调用里的后续推理看到的已是新视图
     */
    private static List<Check> retractCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        ask(context, null, "记一下：我的眼镜度数是左眼 375 度，右眼 400 度。请立刻调用记忆整理工具存下来。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        Item planted = firstMentioning(probe.activeItems(userId), DIOPTER);
        if (planted == null) {
            checks.add(new Check("M6", "度数已经记住", Status.UNCOVERED,
                    "度数没被记下来，撤回无从谈起；当前生效条目 " + probe.activeItems(userId).size() + " 条"));
            return checks;
        }
        System.out.println("    已植入条目 " + planted.id() + "：" + planted.content());

        AgentTurnResult turn = ask(context, null, "忘掉我的眼镜度数，请立刻调用记忆整理工具处理。"
                + "处理完直接把你现在还记得的我的视力信息逐条列出来，没有就说没有。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        List<Item> after = probe.activeItems(userId);
        boolean survived = after.stream().anyMatch(item -> item.id().equals(planted.id()));

        checks.add(new Check("M6", "旧条目已退出生效集",
                survived ? Status.FAIL : Status.PASS,
                "条目 " + planted.id() + (survived ? " 仍在生效集里" : " 已置失效")));
        // 回答这条通道判不了快照新旧，08-27 回滚实测：注释掉 refreshSnapshot 这条照样 PASS
        checks.add(new Check("M6", "同一次调用里快照已刷新", Status.UNCOVERED,
                "回答判不出来——撤回的事实本轮就在工具结果里，模型据此就能说忘了，用不着看块。"
                        + "实测把 refreshSnapshot 注释掉，块里仍留着度数，本轮回答"
                        + (turn.answer().contains(DIOPTER) ? "复述了度数" : "同样没提度数")
                        + "。这道机制是防模型读到自相矛盾的块，不产生可观测的回答差异"));

        AgentTurnResult fresh = ask(context, null, "我的眼镜度数是多少？");
        boolean stillRecalled = fresh.answer().contains(DIOPTER);
        checks.add(new Check("M6", "新会话里也不再注入",
                stillRecalled ? Status.FAIL : Status.PASS,
                stillRecalled ? "撤回过的度数「" + DIOPTER + "」又被答了出来，新会话上下文是空的，只可能来自注入块"
                        : "新会话上下文是空的，答不出度数说明块里确实没有了"));
        return checks;
    }

    // ---------------------------------------------------------------- M7：指不着的目标

    /**
     * M7：仲裁读到目标时它还生效，提交时已失效——条件比对落空即丢这一条，同批其余照写
     * 用取代而不是撤回造现场：丢没丢得看新正文有没有落地，纯撤回从库上分辨不出来
     */
    private static List<Check> staleTargetCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        ask(context, null, "记一下：我泡茶只喝正山小种。请立刻调用记忆整理工具存下来。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        Item planted = firstMentioning(probe.activeItems(userId), "正山小种");
        if (planted == null) {
            checks.add(new Check("M7", "茶叶偏好已经记住", Status.UNCOVERED, "没记住就没有可取代的目标"));
            return checks;
        }
        System.out.println("    已植入条目 " + planted.id() + "：" + planted.content());

        Racer racer = Racer.start(probe, userId, RACE_WINDOW_MILLIS, extraction -> {
            System.out.println("    逮到在飞抽取 " + extraction.id() + "，抢先把 " + planted.id() + " 置失效");
            probe.invalidate(userId, planted.id());
        });
        AgentTurnResult turn = ask(context, null, "我现在改喝白牡丹了，不再喝正山小种。"
                + "另外记一下我用 Kotlin 写业务代码。请立刻调用记忆整理工具一并处理。");
        Extraction caught = racer.await();
        if (caught == null) {
            checks.add(new Check("M7", "逮住在飞抽取", Status.UNCOVERED,
                    "没逮到在飞抽取，构造不出「仲裁之后目标才失效」的现场；实际工具 " + tools(turn)));
            return checks;
        }

        Extraction settled = awaitTerminal(probe, caught.id(), SETTLE_WINDOW_MILLIS);
        List<Item> after = probe.activeItems(userId);
        Item replacement = firstMentioning(after, "白牡丹");
        Item written = firstMentioning(after, "Kotlin");
        Item stale = probe.invalidItems(userId).stream()
                .filter(item -> item.id().equals(planted.id())).findFirst().orElse(null);

        checks.add(new Check("M7", "抽取正常结到终态",
                settled != null && settled.terminal() ? Status.PASS : Status.FAIL,
                settled == null ? "始终 PROCESSING，坏决策把抽取堵住了" : settled.oneLine()));
        checks.add(new Check("M7", "指不着的那条被丢弃",
                replacement == null ? Status.PASS : Status.FAIL,
                replacement == null ? "取代目标在仲裁之后已失效，新正文「白牡丹」没有落地"
                        : "新正文落库为 " + replacement.id() + "：" + replacement.content()
                        + "；目标都失效了还把取代值写了进去"));
        checks.add(new Check("M7", "同批其余照写",
                written != null ? Status.PASS : Status.FAIL,
                written != null ? "新条目 " + written.id() + "：" + written.content()
                        : "同批的另一条也没落库，说明丢的不是一条而是一批"));
        checks.add(new Check("M7", "落空的取代没留下半截",
                stale != null && stale.supersededBy().isEmpty() ? Status.PASS : Status.FAIL,
                stale == null ? "失效行里找不到原条目 " + planted.id()
                        : "原条目后继为 " + (stale.supersededBy().isEmpty() ? "空（正确）" : stale.supersededBy())));
        return checks;
    }

    // ---------------------------------------------------------------- M8：抽取下界的两面

    /**
     * M8：抹掉控制行重演首次使用，验下界的两面——首条消息进得来，建行之前的历史进不来
     * 后台批会被轮次门槛挡住，所以两轮都让模型当轮调 flush；工具确实调了却没建起抽取，就是下界吃掉了首条
     */
    private static List<Check> firstMessageCase(RegressionContext context, String userId) throws Exception {
        String name = "M8";
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        probe.dropControl(userId);
        System.out.println("    控制行已抹掉，重演该用户首次使用");

        AgentTurnResult turn = ask(context, null,
                "我对花生严重过敏，以后推荐吃的绝对不能带花生。请立刻调用记忆整理工具把这一条存下来。");
        if (!turn.tools().contains(FLUSH_TOOL)) {
            checks.add(new Check(name, "本轮调到了整理工具", Status.UNCOVERED,
                    "模型本轮没调用 " + FLUSH_TOOL + "，用例没跑到；实际工具 " + tools(turn)));
            return checks;
        }
        String firstMessageId = probe.firstUserMessageId(userId, turn.conversationId());
        List<Extraction> extractions = awaitExtractions(probe, userId, turn.conversationId(), 1, SETTLE_WINDOW_MILLIS);
        Extraction flush = extractions.stream()
                .filter(item -> "FLUSH".equals(item.triggerType())).findFirst().orElse(null);

        checks.add(new Check(name, "控制行已重建",
                probe.control(userId).present() ? Status.PASS : Status.FAIL,
                probe.control(userId).present() ? "建行时刻即抽取下界" : "整轮跑完还没有控制行"));
        checks.add(new Check(name, "首条消息进了抽取",
                flush != null && flush.fromMessageId().equals(firstMessageId) ? Status.PASS : Status.FAIL,
                flush == null ? "工具调了却没建起 FLUSH 抽取——首条消息落在下界之外，正是本用例要抓的回归"
                        : "抽取区间 " + flush.fromMessageId() + ".." + flush.toMessageId()
                        + "，首条消息 " + firstMessageId));
        if (flush == null) {
            return checks;
        }
        // 第二面要求首轮水位已推到 msg1，否则第二轮区间天然跨两条，判据失去指向性
        Extraction settled = awaitTerminal(probe, flush.id(), SETTLE_WINDOW_MILLIS);
        if (settled == null || !settled.advancesWatermark()) {
            checks.add(new Check(name, "建行前历史没回灌", Status.UNCOVERED,
                    "首轮抽取结在 " + (settled == null ? "PROCESSING" : settled.status())
                            + "，水位没推到首条，第二轮现场立不起来"));
            return checks;
        }

        // 同一会话补一条早于建行的旧消息：id 比水位大（伪造前缀串序靠后）、时间在下界之前，只有下界拦得住它
        String forgedId = probe.forgeStaleUserMessage(userId, turn.conversationId(),
                "我住在西湖边上（回归伪造的建行前历史消息，不该被抽取）");
        System.out.println("    已伪造建行前旧消息 " + forgedId);
        AgentTurnResult second = ask(context, turn.conversationId(),
                "再记一条：我的主力键盘是静电容轴。请立刻调用记忆整理工具存下来。");
        if (!second.tools().contains(FLUSH_TOOL)) {
            checks.add(new Check(name, "建行前历史没回灌", Status.UNCOVERED,
                    "模型第二轮没调用 " + FLUSH_TOOL + "，回灌现场没跑到；实际工具 " + tools(second)));
            return checks;
        }
        List<Extraction> all = awaitExtractions(probe, userId, turn.conversationId(), 2, SETTLE_WINDOW_MILLIS);
        Extraction secondFlush = all.stream()
                .filter(item -> "FLUSH".equals(item.triggerType()))
                .filter(item -> !item.id().equals(flush.id()))
                .reduce((earlier, later) -> later).orElse(null);
        // 区间恰好一条且不是伪造 id 才算干净；回灌发生时伪造消息按串序垫底，会把 to 顶成伪造 id
        boolean clean = secondFlush != null
                && secondFlush.fromMessageId().equals(secondFlush.toMessageId())
                && !secondFlush.toMessageId().startsWith(AgentMemoryProbe.FORGED_ID_PREFIX);
        checks.add(new Check(name, "建行前历史没回灌",
                secondFlush == null ? Status.UNCOVERED : clean ? Status.PASS : Status.FAIL,
                secondFlush == null ? "第二轮没建起新的 FLUSH 抽取"
                        : "第二轮区间 " + secondFlush.fromMessageId() + ".." + secondFlush.toMessageId()
                        + "，伪造旧消息 " + forgedId));
        return checks;
    }

    // ---------------------------------------------------------------- R1：跨会话可见

    /**
     * R1：一条会话里说的偏好，另一条会话开口就该认得——新会话上下文是空的，认得只能来自注入块
     */
    private static List<Check> crossSessionCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        ask(context, null, "记一下：我常用的机械键盘是 HHKB，红轴我用不惯。请立刻调用记忆整理工具存下来。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        Item planted = firstMentioning(probe.activeItems(userId), KEYBOARD);
        if (planted == null) {
            checks.add(new Check("R1", "偏好已进生效集", Status.UNCOVERED,
                    "这句话没被记住，跨会话无从谈起；当前生效条目 " + probe.activeItems(userId).size() + " 条"));
            return checks;
        }
        checks.add(new Check("R1", "偏好已进生效集", Status.PASS,
                "落库为 " + planted.id() + "：" + planted.content()));

        AgentTurnResult fresh = ask(context, null, "我平时用的是什么键盘？直接说，不用查资料。");
        boolean recalled = fresh.answer().contains(KEYBOARD);
        checks.add(new Check("R1", "新会话认得这条",
                recalled ? Status.PASS : Status.FAIL,
                recalled ? "新会话上下文是空的，答得出只可能来自注入块"
                        : "新会话答不出「" + KEYBOARD + "」，条目在库里但没被带进这次调用"));
        // 块渲染进状态就会被压缩层当历史一路抄下去，撤回也再收不回来
        checks.add(new Check("R1", "注入块没落进会话状态",
                probe.blockLeakedIntoState(userId, fresh.conversationId()) ? Status.FAIL : Status.PASS,
                "块只活在上行副本，t_agent_state 里不该出现 " + AgentMemoryProbe.BLOCK_NAME));
        return checks;
    }

    // ---------------------------------------------------------------- R2：撤回不复活

    /**
     * R2：对话里撤回的记忆不许再被任何一条路捞回来；合并读的是生效集，读成全集就会复活它
     */
    private static List<Check> retractPersistCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        ask(context, null, "记一下：我养了一只叫豆豆的柯基。请立刻调用记忆整理工具存下来。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        Item planted = firstMentioning(probe.activeItems(userId), PET);
        if (planted == null) {
            checks.add(new Check("R2", "宠物那条已记住", Status.UNCOVERED, "没记住就没有可撤回的目标"));
            return checks;
        }
        System.out.println("    已植入条目 " + planted.id() + "：" + planted.content());

        ask(context, null, "忘掉我养宠物那条记忆，请立刻调用记忆整理工具处理。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        boolean retracted = firstMentioning(probe.activeItems(userId), PET) == null;
        checks.add(new Check("R2", "撤回之后不再生效", retracted ? Status.PASS : Status.FAIL,
                retracted ? "条目 " + planted.id() + " 已退出生效集"
                        : "撤回没生效，后面的复活判定无从谈起"));
        if (!retracted) {
            return checks;
        }

        AgentTurnResult fresh = ask(context, null, "我养宠物了吗？直接说，不用查资料。");
        checks.add(new Check("R2", "新会话里也没有",
                fresh.answer().contains(PET) ? Status.FAIL : Status.PASS,
                "新会话还答得出即是撤回只改了库没改注入"));

        // 合并没真跑起来时这条只是「本来就没有」，不能拿来当「合并读的是生效集」的证据
        int merged = forceConsolidation(context, userId);
        boolean revived = firstMentioning(probe.activeItems(userId), PET) != null;
        checks.add(new Check("R2", "合并之后仍没复活",
                merged <= 0 ? Status.UNCOVERED : revived ? Status.FAIL : Status.PASS,
                merged < 0 ? "语料顶不到上限，这次没逼出合并，复活路径没验到"
                        : merged == 0 ? "语料灌到位了但几句新事实都没顶破上限，合并没被叫起来"
                        : "受限合并产出 " + merged + " 条，撤回过的「" + PET + "」"
                        + (revived ? "又冒了出来" : "没有出现")));
        return checks;
    }

    // ---------------------------------------------------------------- R3：改口只剩新值

    /**
     * R3：同一件事改口，注入块里只许留新值；旧行退场还要挂上后继，审计链才连得起来
     */
    private static List<Check> supersedeCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        ask(context, null, "记一下：我常驻城市是杭州。请立刻调用记忆整理工具存下来。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        Item planted = firstMentioning(probe.activeItems(userId), OLD_CITY);
        if (planted == null) {
            checks.add(new Check("R3", "旧值已记住", Status.UNCOVERED, "没记住旧值就构造不出改口"));
            return checks;
        }
        System.out.println("    已植入条目 " + planted.id() + "：" + planted.content());

        ask(context, null, "我搬家了，以后常驻南京，不在杭州了。请立刻调用记忆整理工具处理。");
        awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
        List<Item> after = probe.activeItems(userId);
        Item written = firstMentioning(after, NEW_CITY);
        boolean staleGone = firstMentioning(after, OLD_CITY) == null;
        Item retired = probe.invalidItems(userId).stream()
                .filter(item -> item.id().equals(planted.id())).findFirst().orElse(null);

        checks.add(new Check("R3", "新值已落地", written != null ? Status.PASS : Status.FAIL,
                written != null ? "新条目 " + written.id() + "：" + written.content()
                        : "改口之后新值没进生效集"));
        checks.add(new Check("R3", "生效集里只剩新值", staleGone ? Status.PASS : Status.FAIL,
                staleGone ? "旧值「" + OLD_CITY + "」已不在生效集里"
                        : "新旧两个值同时生效，模型会看到自相矛盾的两条"));
        // 上面三条读的全是库，注入整个关掉也照样绿；块里到底带的哪个值只有另开会话问一句才看得见
        AgentTurnResult fresh = ask(context, null, "我现在常驻哪个城市？直接回答城市名。");
        boolean saysNew = fresh.answer().contains(NEW_CITY);
        boolean saysOld = fresh.answer().contains(OLD_CITY);
        checks.add(new Check("R3", "新会话拿到的是新值",
                saysNew && !saysOld ? Status.PASS : Status.FAIL,
                !saysNew && !saysOld ? "新会话答不出城市，注入块没把新值带过去"
                        : saysOld && saysNew ? "新旧两个值一起冒出来了"
                        : saysOld ? "注入块里还是旧值「" + OLD_CITY + "」"
                        : "新会话上下文是空的，答出「" + NEW_CITY + "」只可能来自注入块"));
        // 仲裁挑撤回加新增也能满足前两条，但那样旧行没有后继，审计追不回它是被谁顶掉的
        checks.add(new Check("R3", "旧行挂上了后继",
                retired == null ? Status.FAIL
                        : retired.supersededBy().isEmpty() ? Status.UNCOVERED : Status.PASS,
                retired == null ? "失效行里找不到旧条目 " + planted.id()
                        : retired.supersededBy().isEmpty()
                        ? "仲裁走的是撤回加新增而不是取代，取代链这次没验到"
                        : "旧行后继指向 " + retired.supersededBy()));
        return checks;
    }

    // ---------------------------------------------------------------- R4：水位不重不漏

    /**
     * R4：后台抽取按轮次门槛结算，相邻两批的消息区间必须首尾严格递增
     * 重叠即同一轮被抽两遍，断档即中间那几轮永远没人处理
     */
    private static List<Check> watermarkLedgerCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        // 都是平铺直叙的闲聊，不说「记一下」：一带上模型就自己叫 flush，结出来的抽取就不是门槛结的了
        List<String> chat = List.of(
                "我最近在读一本讲复杂系统的书，叫《失控》。",
                "书里讲蜂群那一章我来回看了两遍。",
                "我通勤路上一般能看二十来页。",
                "周末我更愿意去图书馆看，比家里安静。",
                "我只看纸质书，电子阅读器用不惯。",
                "看完这本我打算换一本讲城市规划的。");
        int threshold = AgentMemoryProbe.EXTRACT_MIN_TURNS;
        String conversationId = null;
        List<Extraction> firstRound = List.of();
        for (int index = 0; index < chat.size(); index++) {
            conversationId = ask(context, conversationId, chat.get(index)).conversationId();
            awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
            if (index == threshold) {
                firstRound = probe.extractions(userId, conversationId);
                System.out.println("    说满 " + (index + 1) + " 轮，台账 " + describe(firstRound));
            }
        }
        List<Extraction> extractions = awaitExtractions(probe, userId, conversationId, 2, SETTLE_WINDOW_MILLIS);
        System.out.println("    全部说完，台账 " + describe(extractions));

        long background = firstRound.stream()
                .filter(extraction -> "BACKGROUND".equals(extraction.triggerType())).count();
        List<Extraction> ordered = extractions.stream().filter(Extraction::advancesWatermark).toList();
        List<String> overlaps = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            Extraction previous = ordered.get(index - 1);
            Extraction current = ordered.get(index);
            if (current.fromMessageId().compareTo(previous.toMessageId()) <= 0) {
                overlaps.add(previous.id() + " 与 " + current.id());
            }
        }

        checks.add(new Check("R4", "满四轮只结出一次抽取",
                background == 1 ? Status.PASS : background == 0 ? Status.UNCOVERED : Status.FAIL,
                "满 " + (threshold + 1) + " 轮时后台抽取 " + background + " 条；门槛 " + threshold + " 轮，"
                        + (background == 0 ? "抽取压根没跑，后面两条判定也就没有素材"
                        : background == 1 ? "第四轮只剩一轮不够再结一次"
                        : "第四轮只剩一轮本不该再结一次，多出来的那几条意味着水位没把已处理区间挡住")));
        checks.add(new Check("R4", "抽取全部结到终态",
                extractions.stream().allMatch(Extraction::terminal) ? Status.PASS : Status.FAIL,
                describe(extractions)));
        checks.add(new Check("R4", "相邻两批区间不重叠",
                ordered.size() < 2 ? Status.UNCOVERED : overlaps.isEmpty() ? Status.PASS : Status.FAIL,
                ordered.size() < 2 ? "只结出 " + ordered.size() + " 条推水位的抽取，第二次触发没跑到"
                        : overlaps.isEmpty() ? ordered.size() + " 条抽取首尾严格递增"
                        : "区间重叠: " + String.join("、", overlaps)));
        return checks;
    }

    // ---------------------------------------------------------------- R5：容量上界

    /**
     * R5：灌到顶再说几件新事，合并腾不动就淘汰旧条目，块必须回到上限内且不许淘穿停手水位
     * 看护的独立记忆灌成 FLUSH 且排最前：既最旧又最该活，来源分档没生效它们第一个没
     */
    private static List<Check> capacityCase(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        List<Check> checks = new ArrayList<>();
        Path file = context.suiteDir().resolve("memory-capacity.properties");
        if (!Files.isRegularFile(file)) {
            checks.add(new Check("R5", "语料文件就位", Status.FAIL, "缺少语料文件: " + file));
            return checks;
        }
        CapacityCorpus corpus = CapacityCorpus.load(file);
        int maxChars = AgentMemoryProbe.maxChars(contextWindow(context));
        int stopChars = AgentMemoryProbe.stopChars(contextWindow(context));

        List<Item> baseline = probe.activeItems(userId);
        List<String> filler = corpus.fillTo(AgentMemoryProbe.blockChars(baseline), maxChars);
        List<String> flushSeeded = new ArrayList<>();
        List<String> backgroundSeeded = new ArrayList<>();
        for (String entry : filler) {
            (mentionsAny(entry, corpus.independent()) ? flushSeeded : backgroundSeeded).add(entry);
        }
        probe.seed(userId, flushSeeded, "FLUSH");
        probe.seed(userId, backgroundSeeded, "BACKGROUND", flushSeeded.size());
        List<Item> loaded = probe.activeItems(userId);
        int startChars = AgentMemoryProbe.blockChars(loaded);
        System.out.println("    灌入语料 " + filler.size() + "/" + corpus.entries().size() + " 条（FLUSH "
                + flushSeeded.size() + " 条在前），块字符 "
                + AgentMemoryProbe.blockChars(baseline) + " -> " + startChars + "，上限 " + maxChars);
        if (startChars + corpus.longestEntryChars() < maxChars) {
            checks.add(new Check("R5", "语料顶到上限门口", Status.UNCOVERED,
                    "语料只顶到 " + startChars + " 字符，离上限 " + maxChars + " 还差得远，压不出受限合并；"
                            + "按 README 把 agent.memory.context-window-chars 对齐成服务端在用的值再跑"));
            return checks;
        }

        // 先撤回一条带独有标记的语料：合并只许读生效集，撤回过的正文绝不能被并回来
        Item retracted = firstMentioning(loaded, corpus.retractMarker());
        if (retracted != null) {
            probe.invalidate(userId, retracted.id());
            System.out.println("    撤回标记条目 " + retracted.id() + "：" + retracted.content());
        }
        // 看护按关键词而不是按行号：合并本来就要改写行，行号必变，事实不许没
        List<String> guarded = new ArrayList<>();
        for (String keyword : corpus.independent()) {
            if (firstMentioning(probe.activeItems(userId), keyword) != null) {
                guarded.add(keyword);
            }
        }
        System.out.println("    独立记忆 " + guarded.size() + " 条纳入看护：" + String.join("、", guarded));

        String conversationId = null;
        // 下限管的是「不含本批」的存量，量块字符看不见：本批写进去的字符会把块顶回上限，把淘穿那一下盖掉
        Set<String> stockIds = new HashSet<>();
        loaded.forEach(item -> stockIds.add(item.id()));
        int lowestStock = startChars;
        for (String question : corpus.asks()) {
            AgentTurnResult turn = ask(context, conversationId, question);
            conversationId = turn.conversationId();
            awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
            List<Extraction> settled = probe.extractions(userId, conversationId);
            String tail = settled.isEmpty() ? "没有抽取" : settled.get(settled.size() - 1).oneLine();
            List<Item> round = probe.activeItems(userId);
            int stockChars = AgentMemoryProbe.blockChars(stockOf(round, stockIds));
            lowestStock = Math.min(lowestStock, stockChars);
            round.forEach(item -> stockIds.add(item.id()));
            System.out.println("    本轮之后块字符 " + AgentMemoryProbe.blockChars(round)
                    + "（其中存量 " + stockChars + "），末批 " + tail);
        }

        List<Item> after = probe.activeItems(userId);
        int endChars = AgentMemoryProbe.blockChars(after);
        long merged = after.stream().filter(item -> "CONSOLIDATION".equals(item.sourceType())).count();
        List<Item> evicted = evictedItems(loaded, probe.invalidItems(userId), retracted);
        List<String> lost = new ArrayList<>();
        for (String keyword : guarded) {
            if (firstMentioning(after, keyword) == null) {
                lost.add(keyword);
            }
        }
        boolean revived = after.stream().anyMatch(item -> item.mentions(corpus.retractMarker()));
        System.out.println("    合并之后的生效集 " + after.size() + " 条：");
        for (Item item : after) {
            System.out.println("      [" + item.sourceType() + "] " + item.content());
        }

        boolean reclaimed = merged > 0 || !evicted.isEmpty();
        checks.add(new Check("R5", "受限合并确实跑了",
                merged > 0 ? Status.PASS : Status.FAIL,
                "合并产物 " + merged + " 条（source_type=CONSOLIDATION）"
                        + (merged > 0 ? "" : "；计划可能整份作废，去日志翻「成员不可用」那几行")));
        // 合并压缩存量、淘汰直接减存量，两条腾位置的路子跑起来任一条都算数；都没动过这个数就不是它俩摁住的
        checks.add(new Check("R5", "块压回上限之内",
                endChars > maxChars ? Status.FAIL : reclaimed ? Status.PASS : Status.UNCOVERED,
                "块字符 " + startChars + " -> " + endChars + "，上限 " + maxChars + "，停手水位 " + stopChars
                        + "（为上限的 " + (endChars * 100 / Math.max(maxChars, 1)) + "%）"
                        + (endChars > maxChars || reclaimed ? "" : "；合并与淘汰一次都没跑，这个数另有出处")));
        checks.add(new Check("R5", "容量淘汰确实跑了",
                evicted.isEmpty() ? Status.UNCOVERED : Status.PASS,
                evicted.isEmpty() ? "这一跑合并就把位置腾够了，淘汰没轮上，下限与来源分档两条跟着验不到"
                        : "淘汰 " + evicted.size() + " 条：" + contents(evicted)));
        checks.add(new Check("R5", "淘汰没越过停手水位",
                evicted.isEmpty() ? Status.UNCOVERED : lowestStock >= stopChars ? Status.PASS : Status.FAIL,
                evicted.isEmpty() ? "没淘汰，下限验不到"
                        : "全程最低存量 " + lowestStock + "，停手水位 " + stopChars
                        + (lowestStock >= stopChars ? "" : "；存量不含本批新写入，掉下去只可能是淘汰淘穿了")));
        // 验的是分档殿后这个弱优先级：本语料下非 FLUSH 足够腾位，殿后档不该被淘到；殿后不是绝对保护
        checks.add(new Check("R5", "FLUSH 分档殿后生效",
                lost.isEmpty() ? Status.PASS : Status.FAIL,
                lost.isEmpty() ? "看护中的 " + guarded.size() + " 条 FLUSH 记忆全部存活"
                        : "丢掉的独立记忆: " + String.join("、", lost)
                        + "；它们灌成 FLUSH 且最旧，被淘说明来源分档没生效，被并说明合并吞了独立事实"));
        checks.add(new Check("R5", "撤回过的正文没被复活",
                retracted == null ? Status.UNCOVERED : revived ? Status.FAIL : Status.PASS,
                retracted == null ? "语料里没有带撤回标记的条目，复活路径没验到"
                        : "标记「" + corpus.retractMarker() + "」在合并之后"
                        + (revived ? "又冒了出来" : "没有出现")));
        return checks;
    }

    /**
     * 灌语料把块顶到上限，再逐句说新事逼出一次受限合并，返回合并产物条数
     * 一句话顶不动就换下一句：越界那一下要由仲裁自己撞上，撞不撞得上得看它这次挑不挑得出新事实
     * 返回 0 表示语料到位但合并没跑起来，与「压根灌不满」的 -1 分开，判定里两者都只能记 UNCOVERED
     */
    private static int forceConsolidation(RegressionContext context, String userId) throws Exception {
        AgentMemoryProbe probe = context.memory();
        Path file = context.suiteDir().resolve("memory-capacity.properties");
        if (!Files.isRegularFile(file)) {
            System.out.println("    缺少语料文件，逼不出合并: " + file);
            return -1;
        }
        CapacityCorpus corpus = CapacityCorpus.load(file);
        int maxChars = AgentMemoryProbe.maxChars(contextWindow(context));
        List<String> filler = corpus.fillTo(AgentMemoryProbe.blockChars(probe.activeItems(userId)), maxChars);
        probe.seed(userId, filler);
        int startChars = AgentMemoryProbe.blockChars(probe.activeItems(userId));
        System.out.println("    灌入语料 " + filler.size() + " 条，块字符顶到 " + startChars + "，上限 " + maxChars);
        if (startChars + corpus.longestEntryChars() < maxChars) {
            return -1;
        }
        for (String question : corpus.asks()) {
            ask(context, null, question);
            awaitIdle(probe, userId, SETTLE_WINDOW_MILLIS);
            int merged = mergedCount(probe.activeItems(userId));
            System.out.println("    本轮之后块字符 " + AgentMemoryProbe.blockChars(probe.activeItems(userId))
                    + "，合并产物 " + merged + " 条");
            if (merged > 0) {
                return merged;
            }
        }
        return 0;
    }

    private static int mergedCount(List<Item> items) {
        return (int) items.stream().filter(item -> "CONSOLIDATION".equals(item.sourceType())).count();
    }

    // ---------------------------------------------------------------- 赛跑与等待

    /**
     * 轮询到这个用户名下出现在飞抽取为止，逮不到返回 null
     */
    private static Extraction awaitProcessing(AgentMemoryProbe probe, String userId, long windowMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + windowMillis;
        while (System.currentTimeMillis() < deadline) {
            List<Extraction> processing = probe.processing(userId);
            if (!processing.isEmpty()) {
                return processing.get(0);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return null;
    }

    private static Extraction awaitTerminal(AgentMemoryProbe probe, String extractionId, long windowMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + windowMillis;
        while (System.currentTimeMillis() < deadline) {
            Extraction extraction = probe.extraction(extractionId);
            if (extraction != null && extraction.terminal()) {
                return extraction;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return probe.extraction(extractionId);
    }

    private static List<Extraction> awaitExtractions(AgentMemoryProbe probe, String userId, String conversationId,
                                            int atLeast, long windowMillis) throws Exception {
        long deadline = System.currentTimeMillis() + windowMillis;
        List<Extraction> extractions = probe.extractions(userId, conversationId);
        while (System.currentTimeMillis() < deadline
                && (extractions.size() < atLeast || extractions.stream().anyMatch(extraction -> !extraction.terminal()))) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            extractions = probe.extractions(userId, conversationId);
        }
        return extractions;
    }

    /**
     * 等到这个用户名下没有在飞抽取，之后读到的生效集才是稳定的
     */
    private static void awaitIdle(AgentMemoryProbe probe, String userId, long windowMillis) throws Exception {
        Thread.sleep(Math.min(BACKGROUND_GRACE_MILLIS, windowMillis));
        long deadline = System.currentTimeMillis() + windowMillis;
        while (System.currentTimeMillis() < deadline && !probe.processing(userId).isEmpty()) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    /**
     * flush 发生在流中途，逮它必须另起一条线程；后台那条路等 ask 返回再轮询就够
     */
    private static final class Racer {

        private final Thread thread;
        private volatile Extraction caught;

        private Racer(AgentMemoryProbe probe, String userId, long windowMillis,
                      long delayMillis, CaughtAction action) {
            this.thread = new Thread(() -> {
                try {
                    Extraction extraction = awaitProcessing(probe, userId, windowMillis);
                    if (extraction == null) {
                        return;
                    }
                    caught = extraction;
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    action.apply(extraction);
                } catch (Exception ex) {
                    System.out.println("    赛跑线程异常: " + ex.getMessage());
                }
            }, "memory-racer");
            this.thread.setDaemon(true);
        }

        static Racer start(AgentMemoryProbe probe, String userId, long windowMillis, CaughtAction action) {
            return start(probe, userId, windowMillis, MUTATION_DELAY_MILLIS, action);
        }

        static Racer start(AgentMemoryProbe probe, String userId, long windowMillis,
                           long delayMillis, CaughtAction action) {
            Racer racer = new Racer(probe, userId, windowMillis, delayMillis, action);
            racer.thread.start();
            return racer;
        }

        Extraction await() throws InterruptedException {
            thread.join();
            return caught;
        }
    }

    @FunctionalInterface
    private interface CaughtAction {
        void apply(Extraction extraction) throws Exception;
    }

    // ---------------------------------------------------------------- 工具

    private static AgentTurnResult ask(RegressionContext context, String conversationId, String question)
            throws Exception {
        System.out.println("    提问: " + abbreviate(question, 60));
        AgentTurnResult result = context.chat().ask(question, conversationId, context.turnTimeout());
        System.out.println("    回答 " + result.answer().length() + " 字，工具 " + tools(result));
        return result;
    }

    private static Item firstMentioning(List<Item> items, String keyword) {
        return items.stream().filter(item -> item.mentions(keyword)).findFirst().orElse(null);
    }

    private static boolean mentionsAny(String content, List<String> keywords) {
        return keywords.stream().anyMatch(content::contains);
    }

    /**
     * 存量 = 本批之外的部分，正是淘汰下限管的那个量
     * 合并产物算存量不算本批：它在提交事务里先于淘汰落地，淘汰量到的就是含它的那份
     */
    private static List<Item> stockOf(List<Item> items, Set<String> stockIds) {
        return items.stream()
                .filter(item -> stockIds.contains(item.id()) || "CONSOLIDATION".equals(item.sourceType()))
                .toList();
    }

    /**
     * 淘汰的形状：灌进去时还生效、如今失效且没有后继
     * 有后继的是被合并吞的，手动撤回那条按 ID 排掉；仲裁自己判的 RETRACT 混在这里分不开，只能看正文认
     */
    private static List<Item> evictedItems(List<Item> loaded, List<Item> invalid, Item retracted) {
        Set<String> present = new HashSet<>();
        loaded.forEach(item -> present.add(item.id()));
        return invalid.stream()
                .filter(item -> present.contains(item.id()))
                .filter(item -> item.supersededBy().isEmpty())
                .filter(item -> retracted == null || !item.id().equals(retracted.id()))
                .toList();
    }

    private static String contents(List<Item> items) {
        List<String> lines = new ArrayList<>();
        items.forEach(item -> lines.add(abbreviate(item.content(), 24)));
        return String.join("、", lines);
    }

    private static boolean sameIds(List<Item> before, List<Item> after) {
        Set<String> left = new HashSet<>();
        before.forEach(item -> left.add(item.id()));
        Set<String> right = new HashSet<>();
        after.forEach(item -> right.add(item.id()));
        return left.equals(right);
    }

    private static String tools(AgentTurnResult turn) {
        return turn.tools().isEmpty() ? "无" : String.join(",", turn.tools());
    }

    private static String describe(List<Extraction> extractions) {
        if (extractions.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        extractions.forEach(extraction -> lines.add(extraction.oneLine()));
        return String.join(" / ", lines);
    }

    private static int contextWindow(RegressionContext context) {
        return context.config().getInt("agent.memory.context-window-chars", 0);
    }

    private static String label(String value) {
        return value == null || value.isEmpty() ? "(空)" : value;
    }

    private static String abbreviate(String value, int limit) {
        String single = value == null ? "" : value.replace('\n', ' ');
        return single.length() <= limit ? single : single.substring(0, limit) + "...";
    }

    private static List<String> selectCases(String value) {
        if ("all".equalsIgnoreCase(value)) {
            return ALL_CASES;
        }
        List<String> selected = new ArrayList<>();
        for (String item : value.split(",")) {
            String name = item.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            if (!ALL_CASES.contains(name)) {
                throw new IllegalArgumentException("未知用例 " + name + "，可选: " + String.join(",", ALL_CASES));
            }
            selected.add(name);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("--case 没有选中任何用例");
        }
        return List.copyOf(selected);
    }

    private static String caseTitle(String name) {
        return switch (name) {
            case "M1" -> "版本号失配整批拒绝";
            case "M2" -> "水位失配整批拒绝";
            case "M3" -> "flush 不受轮次门槛限制";
            case "M4" -> "NOOP 照样推进水位";
            case "M5" -> "关闭开关作废在飞快照";
            case "M6" -> "对话撤回后不再注入";
            case "M7" -> "指不着的目标只丢这一条";
            case "M8" -> "抽取下界的两面";
            case "R1" -> "跨会话可见";
            case "R2" -> "撤回过的记忆不复活";
            case "R3" -> "改口之后只剩新值";
            case "R4" -> "水位不重不漏";
            case "R5" -> "容量上界与受限合并";
            case "R6" -> "关闭窗口不补抽";
            default -> name;
        };
    }

    private static void printEnvironment(RegressionContext context, String userId, List<String> selected) {
        InitializerConfig config = context.config();
        System.out.println("=== 环境 ===");
        System.out.println("  服务地址        " + config.require("server.base-url"));
        System.out.println("  执行架构        " + config.get("execution.engine-type", "(未知)"));
        System.out.println("  登录用户        " + config.require("auth.username") + " / userId=" + userId);
        System.out.println("  长期记忆开关    " + config.get("agent.memory.long-term-enabled", "(未知)"));
        System.out.println("  上下文预算      " + contextWindow(context) + " 字符 → 记忆上限 "
                + AgentMemoryProbe.maxChars(contextWindow(context)) + "，合并停手 "
                + AgentMemoryProbe.stopChars(contextWindow(context)));
        System.out.println("  后台抽取门槛    " + AgentMemoryProbe.EXTRACT_MIN_TURNS + " 轮（flush 不受它挡）");
        System.out.println("  本次用例        " + String.join(", ", selected));
        System.out.println("  用例会改库      造并发、翻开关、灌语料都要动库；每条跑完把生效集还原到基线，"
                + "假数据按 " + AgentMemoryProbe.FORGED_ID_PREFIX + " 前缀物理删");
    }

    private static void printChecks(List<Check> checks) {
        System.out.println();
        System.out.println("=== 判定 ===");
        for (Check check : checks) {
            System.out.printf("  [%-9s] %-3s %-20s %s%n",
                    check.status().name(), check.scope(), check.name(), check.detail());
        }
        System.out.println("  PASS=通过  FAIL=回归  UNCOVERED=本次没跑到该分支（多半是模型没走预期路径，重跑一次）");
    }

    private enum Status {
        PASS, FAIL, UNCOVERED
    }

    private record Check(String scope, String name, Status status, String detail) {
    }

    /**
     * R5 语料：高度冗余的同主题条目、绝不该被合并的独立条目、以及压垮上限的几句新话
     */
    private record CapacityCorpus(List<String> entries, List<String> independent, List<String> asks,
                                  String retractMarker) {

        static CapacityCorpus load(Path file) throws IOException {
            Properties values = InitializerConfig.loadProperties(file);
            String marker = values.getProperty("retract-marker", "").trim();
            if (marker.isEmpty()) {
                throw new IllegalArgumentException("语料缺少 retract-marker: " + file);
            }
            return new CapacityCorpus(prefixed(values, "entry."), prefixed(values, "independent."),
                    prefixed(values, "ask."), marker);
        }

        /**
         * 灌到再多一条就越界为止：越界那一下该由抽取自己撞上，不由语料替它撞
         */
        List<String> fillTo(int startChars, int maxChars) {
            List<String> filler = new ArrayList<>();
            int chars = startChars == 0 ? AgentMemoryProbe.blockOverheadChars() : startChars;
            for (String entry : entries) {
                int next = chars + AgentMemoryProbe.itemChars(entry);
                if (next >= maxChars) {
                    break;
                }
                filler.add(entry);
                chars = next;
            }
            return List.copyOf(filler);
        }

        int longestEntryChars() {
            int longest = 0;
            for (String entry : entries) {
                longest = Math.max(longest, AgentMemoryProbe.itemChars(entry));
            }
            return longest;
        }

        /**
         * 按键名排序取值，语料文件里的编号即灌入顺序：独立条目排在最前，保证一定进得去
         */
        private static List<String> prefixed(Properties values, String prefix) {
            List<String> keys = new ArrayList<>();
            for (String name : values.stringPropertyNames()) {
                if (name.startsWith(prefix)) {
                    keys.add(name);
                }
            }
            Collections.sort(keys);
            List<String> result = new ArrayList<>();
            for (String key : keys) {
                String value = values.getProperty(key).trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
            return List.copyOf(result);
        }
    }
}
