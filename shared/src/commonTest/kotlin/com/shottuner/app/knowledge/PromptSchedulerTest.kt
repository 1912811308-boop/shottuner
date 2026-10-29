package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 调度器状态机测试（12+ 个用例）。
 *
 * 全部通过 [PromptScheduler.tickWithCandidates] 注入候选，
 * 这样测的是"调度规则"本身，而不是"哪条规则恰好会触发"。
 * 时间一律显式传入，不允许依赖真实时钟。
 */
class PromptSchedulerTest {

    private fun signals(
        roll: Float? = 0f,
        meanLuma: Float = 0.5f,
        shadowClip: Float = 0f,
        highlightClip: Float = 0f,
        centerBias: Float = 0f,
        gridOn: Boolean = true,
        gridOffAgoMs: Long = Long.MAX_VALUE,
        tab: Int = PromptEngine.TAB_PHOTO,
        videoIntentOrdinal: Int = -1,
        stability: Float = 0f
    ): PromptSignals = PromptSignals(
        tech = TechSignals(
            meanLuma = meanLuma,
            shadowClip = shadowClip,
            highlightClip = highlightClip,
            centerBias = centerBias,
            hasStats = true
        ),
        roll = roll,
        gridOn = gridOn,
        gridOffAgoMs = gridOffAgoMs,
        tab = tab,
        videoIntentOrdinal = videoIntentOrdinal,
        stability = stability,
        scene = SceneType.GENERAL
    )

    private fun candidate(
        tipId: String,
        priority: PromptPriority = PromptPriority.CONTEXT,
        weight: Int = 20,
        holdMs: Long = 0L,
        autoResolve: Boolean = false,
        recoverKey: String? = null,
        paced: Boolean = false
    ): PromptCandidate = PromptCandidate(
        ruleId = "R_$tipId",
        tipId = tipId,
        priority = priority,
        weight = weight,
        action = "action $tipId",
        reason = "reason $tipId",
        holdMs = holdMs,
        autoResolve = autoResolve,
        recoverKey = recoverKey,
        paced = paced
    )

    @Test
    fun singlePromptVisible() {
        val scheduler = PromptScheduler()
        val decision = scheduler.tickWithCandidates(
            listOf(
                candidate("a", PromptPriority.URGENT, 30),
                candidate("b", PromptPriority.CONTEXT, 20),
                candidate("c", PromptPriority.GUIDE, 10)
            ),
            signals(),
            0L
        )
        assertNotNull(decision.prompt, "必须有一条上屏")
        assertEquals("a", decision.prompt!!.tipId, "只允许一条可见")
        // 默认密度是 STANDARD，只放行 URGENT+CONTEXT（ARCH S8），GUIDE 那条被过滤
        assertEquals(2, decision.visibleCount, "STANDARD 下候选数用于小圆点")

        // CHATTER 才三条全放
        val chatty = PromptScheduler()
        chatty.setDensity(PromptDensity.CHATTER)
        assertEquals(
            3,
            chatty.tickWithCandidates(
                listOf(
                    candidate("a", PromptPriority.URGENT, 30),
                    candidate("b", PromptPriority.CONTEXT, 20),
                    candidate("c", PromptPriority.GUIDE, 10)
                ),
                signals(),
                0L
            ).visibleCount,
            "CHATTER 下三条全合规"
        )
    }

    @Test
    fun cooldownBlocksRepeat() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        assertNotNull(scheduler.tickWithCandidates(listOf(a), signals(), 0L).prompt)
        // 用户点「下一条」→ 进 8s 冷却
        scheduler.onNext(0L)
        // 7.9s：还在冷却
        assertNull(scheduler.tickWithCandidates(listOf(a), signals(), 7_900L).prompt)
    }

    @Test
    fun cooldownExpires() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        scheduler.tickWithCandidates(listOf(a), signals(), 0L)
        scheduler.onNext(0L)
        // 8.001s：冷却已过，可以重出
        val decision = scheduler.tickWithCandidates(listOf(a), signals(), 8_001L)
        assertNotNull(decision.prompt)
        assertEquals("a", decision.prompt!!.tipId)
    }

    @Test
    fun minDwellBlocksContext() {
        val scheduler = PromptScheduler()
        scheduler.tickWithCandidates(listOf(candidate("a")), signals(), 0L)
        val decision = scheduler.tickWithCandidates(listOf(candidate("b")), signals(), 1_000L)
        assertEquals("a", decision.prompt!!.tipId, "A 必须还在屏上")
        assertFalse(decision.changed, "没换人就不该通知 UI 重绘")
    }

    @Test
    fun minDwellAllowsAfter3s() {
        val scheduler = PromptScheduler()
        scheduler.tickWithCandidates(listOf(candidate("a")), signals(), 0L)
        // 2.999s 还不够
        assertEquals(
            "a",
            scheduler.tickWithCandidates(listOf(candidate("b")), signals(), 2_999L).prompt!!.tipId
        )
        // 3.001s 放行
        val decision = scheduler.tickWithCandidates(listOf(candidate("b")), signals(), 3_001L)
        assertEquals("b", decision.prompt!!.tipId)
        assertTrue(decision.changed)
    }

    @Test
    fun urgentInterruptsImmediately() {
        val scheduler = PromptScheduler()
        scheduler.tickWithCandidates(listOf(candidate("a", PromptPriority.CONTEXT)), signals(), 0L)
        val decision = scheduler.tickWithCandidates(
            listOf(candidate("u", PromptPriority.URGENT, 30)),
            signals(),
            500L
        )
        assertEquals("u", decision.prompt!!.tipId, "urgent 可以立即打断")
    }

    @Test
    fun contextCannotInterruptUrgent() {
        val scheduler = PromptScheduler()
        scheduler.tickWithCandidates(listOf(candidate("u", PromptPriority.URGENT, 30)), signals(), 0L)

        // 500ms：驻留还不够
        assertEquals(
            "u",
            scheduler.tickWithCandidates(listOf(candidate("b")), signals(), 500L).prompt!!.tipId
        )
        // 5s：虽然驻留够了，但低优先级仍然不许把 urgent 顶掉
        assertEquals(
            "u",
            scheduler.tickWithCandidates(listOf(candidate("b")), signals(), 5_000L).prompt!!.tipId,
            "低优先级不能反向打断"
        )
    }

    @Test
    fun blacklistIsPermanent() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        scheduler.tickWithCandidates(listOf(a), signals(), 0L)
        scheduler.onBlock(0L)
        assertTrue(scheduler.snapshot().blacklist.contains("a"))
        assertNull(scheduler.tickWithCandidates(listOf(a), signals(), 99_999L).prompt, "屏蔽后本会话永不再出现")
    }

    @Test
    fun executedIsPermanent() {
        val scheduler = PromptScheduler()
        scheduler.markExecuted("landscape_horizon_level", 0L)
        val executed = candidate("landscape_horizon_level", PromptPriority.URGENT, 30)
        assertNull(scheduler.tickWithCandidates(listOf(executed), signals(), 20_000L).prompt)
        assertTrue(scheduler.snapshot().executed.contains("landscape_horizon_level"))
    }

    @Test
    fun autoResolveGivesFeedback() {
        val scheduler = PromptScheduler()
        val horizon = candidate(
            tipId = "landscape_horizon_level",
            priority = PromptPriority.URGENT,
            weight = 30,
            autoResolve = true,
            recoverKey = "roll"
        )
        // 一开始是歪的
        val first = scheduler.tickWithCandidates(listOf(horizon), signals(roll = 6f), 0L)
        assertEquals("landscape_horizon_level", first.prompt!!.tipId)

        // 用户扶正了 → 立即撤下 + 正向反馈 + 永久拉黑
        val second = scheduler.tickWithCandidates(listOf(horizon), signals(roll = 0.5f), 500L)
        assertNull(second.prompt, "信号恢复后必须立即撤下")
        assertEquals(PromptFeedback.LEVEL_OK, second.feedback)
        assertTrue(scheduler.snapshot().blacklist.contains("landscape_horizon_level"))
    }

    @Test
    fun quietDensityOnlyUrgent() {
        val scheduler = PromptScheduler()
        scheduler.setDensity(PromptDensity.QUIET)
        assertNull(
            scheduler.tickWithCandidates(listOf(candidate("c", PromptPriority.CONTEXT)), signals(), 0L).prompt,
            "安静档不放场景提示"
        )
        val decision = scheduler.tickWithCandidates(
            listOf(candidate("u", PromptPriority.URGENT, 30)),
            signals(),
            100L
        )
        assertNotNull(decision.prompt, "安静档仍然要报硬伤")
    }

    @Test
    fun mutedSuppressesButCounts() {
        val scheduler = PromptScheduler()
        scheduler.setMuted(true)
        val decision = scheduler.tickWithCandidates(
            listOf(candidate("u", PromptPriority.URGENT, 30)),
            signals(),
            0L
        )
        assertNull(decision.prompt, "静音就是一句话都不说，urgent 也不说")
        assertTrue(decision.visibleCount > 0, "但要告诉 UI 有话说")
    }

    @Test
    fun holdMsGate() {
        val scheduler = PromptScheduler()
        val gated = candidate("g", PromptPriority.URGENT, 30, holdMs = 800L)
        assertNull(scheduler.tickWithCandidates(listOf(gated), signals(), 0L).prompt, "刚出现时不满 800ms")
        assertNull(scheduler.tickWithCandidates(listOf(gated), signals(), 799L).prompt)
        assertNotNull(scheduler.tickWithCandidates(listOf(gated), signals(), 800L).prompt, "满 800ms 才出牌")
    }

    @Test
    fun holdMsResetsOnFalse() {
        val scheduler = PromptScheduler()
        val gated = candidate("g", PromptPriority.URGENT, 30, holdMs = 800L)
        scheduler.tickWithCandidates(listOf(gated), signals(), 0L)
        // 条件消失 → 计时清零
        scheduler.tickWithCandidates(emptyList(), signals(), 400L)
        // 再次为真 → 从 600ms 重新计时
        scheduler.tickWithCandidates(listOf(gated), signals(), 600L)
        assertNull(scheduler.tickWithCandidates(listOf(gated), signals(), 1_100L).prompt, "第二次只持续了 500ms")
        assertNotNull(scheduler.tickWithCandidates(listOf(gated), signals(), 1_400L).prompt, "满 800ms（600→1400）才出牌")
    }

    @Test
    fun resetSessionClearsCooldownAndBlacklist() {
        val scheduler = PromptScheduler()
        scheduler.setDensity(PromptDensity.CHATTER)
        val a = candidate("a")
        scheduler.tickWithCandidates(listOf(a), signals(), 0L)
        scheduler.onBlock(0L)
        assertNull(scheduler.tickWithCandidates(listOf(a), signals(), 500L).prompt)

        scheduler.resetSession(1_000L)
        assertTrue(scheduler.snapshot().blacklist.isEmpty())
        assertEquals(PromptDensity.CHATTER, scheduler.snapshot().density, "密度属于用户设置，不能被会话重置清掉")
        assertNotNull(scheduler.tickWithCandidates(listOf(a), signals(), 1_100L).prompt, "重置后可以再出")
    }

    @Test
    fun pauseKeepsCurrent() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        scheduler.tickWithCandidates(listOf(a), signals(), 0L)
        scheduler.pause(true)
        val decision = scheduler.tickWithCandidates(listOf(candidate("b", PromptPriority.URGENT, 30)), signals(), 9_000L)
        assertEquals("a", decision.prompt!!.tipId, "暂停期间不出新提示")
        scheduler.pause(false)
    }

    @Test
    fun cycleIncrementsOnShowOnly() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        scheduler.tickWithCandidates(listOf(a), signals(), 0L)
        assertEquals(1, scheduler.snapshot().cycle)
        // 同一句重复 tick 不再加 cycle
        scheduler.tickWithCandidates(listOf(a), signals(), 100L)
        assertEquals(1, scheduler.snapshot().cycle)
    }

    // ── autoResolve：问题自己解决后永久拉黑（v1.5） ────────────────────────────

    @Test
    fun autoResolveWithdrawsAndBlacklistsPermanently() {
        val scheduler = PromptScheduler()
        // 这个用例测的是 autoResolve 链路，与密度正交。候选在这里被显式标成 GUIDE，
        // 而生产端的 R11 已经归到 CONTEXT（默认 STANDARD 就能出），
        // 所以这里只是为了让构造出来的 GUIDE 候选不被 STANDARD 过滤掉。
        scheduler.setDensity(PromptDensity.CHATTER)
        // 网格规则：recoverKey = "grid"，用户打开网格即视为问题解决
        val grid = candidate(
            tipId = "pitfall_grid_on",
            priority = PromptPriority.GUIDE,
            weight = 14,
            autoResolve = true,
            recoverKey = "grid"
        )

        // t=0 网格关着 → 上屏
        val shown = scheduler.tickWithCandidates(listOf(grid), signals(gridOn = false), 0L)
        assertEquals("pitfall_grid_on", shown.prompt!!.tipId)

        // t=500 用户打开了网格 → 立刻撤下，并给正向反馈
        val resolved = scheduler.tickWithCandidates(listOf(grid), signals(gridOn = true), 500L)
        assertNull(resolved.prompt, "问题解决了必须自动撤卡")
        assertTrue(resolved.changed, "撤卡要通知 UI 重绘")
        assertEquals(PromptFeedback.EXPOSURE_OK, resolved.feedback, "应给出曝光/水平类正向反馈")

        // 之后再怎么久，也不许再出现（markExecuted 会同时写 blacklist）
        val later = scheduler.tickWithCandidates(
            listOf(grid),
            signals(gridOn = false),
            99_999L
        )
        assertNull(later.prompt, "已标记执行的条目必须永久拉黑")
        assertTrue(
            "pitfall_grid_on" in scheduler.snapshot().blacklist,
            "快照里应能看到它进了黑名单"
        )
    }

    @Test
    fun autoResolveDoesNotFireWhileProblemPersists() {
        val scheduler = PromptScheduler()
        // 同上：候选是 GUIDE，加密度豁免只为让它不被 STANDARD 过滤，测的仍是 autoResolve 本身
        scheduler.setDensity(PromptDensity.CHATTER)
        val grid = candidate(
            tipId = "pitfall_grid_on",
            priority = PromptPriority.GUIDE,
            weight = 14,
            autoResolve = true,
            recoverKey = "grid"
        )
        scheduler.tickWithCandidates(listOf(grid), signals(gridOn = false), 0L)
        // 网格仍然关着 → 不撤、不拉黑
        val still = scheduler.tickWithCandidates(listOf(grid), signals(gridOn = false), 600L)
        assertEquals("pitfall_grid_on", still.prompt!!.tipId)
        assertNull(still.feedback)
        assertFalse(
            "pitfall_grid_on" in scheduler.snapshot().blacklist,
            "问题没解决不能进黑名单"
        )
    }

    // ── 节拍回填：只有节奏类候选推进 lastGuideAt（QA P1-1） ────────────────────

    @Test
    fun pacedCandidateAdvancesTheBeat() {
        val scheduler = PromptScheduler()
        assertEquals(-1L, scheduler.snapshot().lastGuideAt, "初始值必须是 -1")
        scheduler.tickWithCandidates(listOf(candidate("move_a", paced = true)), signals(), 4_000L)
        assertEquals(
            4_000L,
            scheduler.snapshot().lastGuideAt,
            "节奏类候选出牌必须推进 lastGuideAt，否则 8 秒节拍门形同不存在"
        )
    }

    @Test
    fun nonPacedCandidateDoesNotAdvanceTheBeat() {
        val scheduler = PromptScheduler()
        scheduler.tickWithCandidates(
            listOf(candidate("tilt", PromptPriority.URGENT, 30)),
            signals(),
            4_000L
        )
        assertEquals(
            -1L,
            scheduler.snapshot().lastGuideAt,
            "硬伤提示不该推进节拍，否则运镜会被无关提示饿死"
        )
    }

    /**
     * 生产链路上的回归：R09 走 `tick()`（真引擎 + 真规则表）出牌后，
     * `lastGuideAt` 必须前进。
     *
     * 这是 QA P1-1 的直接复现条件 —— 曾经 R09 被改成 CONTEXT，而回填条件是
     * `priority == GUIDE`，于是这条断言会拿到 -1。
     */
    @Test
    fun moveRuleAdvancesTheBeatOnTheRealPath() {
        val scheduler = PromptScheduler()
        val video = signals(tab = PromptEngine.TAB_VIDEO, videoIntentOrdinal = 0)
        val decision = scheduler.tick(video, 16_000L)
        assertEquals(
            VideoMovePlanner.nextMove(0, (16_000L / SchedulerConfig.GUIDE_INTERVAL_MS).toInt()),
            decision.prompt!!.tipId,
            "录像页必须能出运镜卡"
        )
        assertEquals(
            16_000L,
            scheduler.snapshot().lastGuideAt,
            "R09 出牌后 lastGuideAt 必须被回填"
        )
        // 卡片本身要继续留在屏上（不是被撤），只是不允许立刻换下一条
        val immediate = scheduler.tick(video, 16_500L)
        assertEquals(
            decision.prompt!!.tipId,
            immediate.prompt!!.tipId,
            "500ms 后还是同一条运镜，不许换"
        )
        assertFalse(immediate.changed, "同一条不算变化，别让 UI 重绘")

        // 真正的节拍门：把回填后的 lastGuideAt 喂回引擎，R09 必须闭嘴
        val gated = PromptEngine.evaluate(video, 16_500L, lastGuideAt = 16_000L)
        assertTrue(
            gated.none { it.ruleId == "R09_move_guide" },
            "8 秒节拍门必须真的挡住 R09（这正是 QA P1-1 说的死逻辑）"
        )
        val openAgain = PromptEngine.evaluate(video, 24_001L, lastGuideAt = 16_000L)
        assertTrue(
            openAgain.any { it.ruleId == "R09_move_guide" },
            "过了 8 秒必须重新开口"
        )
    }

    // ── 密度 / 静音改档后必须立刻撤卡（QA P2-4） ─────────────────────────────

    @Test
    fun densityChangeWithdrawsThePromptImmediately() {
        val scheduler = PromptScheduler()
        scheduler.setDensity(PromptDensity.CHATTER)
        val guide = candidate("guide_a", PromptPriority.GUIDE)
        assertNotNull(scheduler.tickWithCandidates(listOf(guide), signals(), 0L).prompt)

        // 用户把档位调低 → GUIDE 不再合规，current 被内部置空
        scheduler.setDensity(PromptDensity.STANDARD)
        val after = scheduler.tickWithCandidates(listOf(guide), signals(), 500L)
        assertNull(after.prompt, "档位调低后这张卡必须下掉")
        assertTrue(
            after.changed,
            "撤卡必须通知 UI 重绘，否则槽位停在上一句（QA P2-4）"
        )
    }

    @Test
    fun muteWithdrawsThePromptImmediately() {
        val scheduler = PromptScheduler()
        val a = candidate("a", weight = 30)
        val b = candidate("b", weight = 10)
        assertNotNull(scheduler.tickWithCandidates(listOf(a), signals(), 0L).prompt)

        scheduler.setMuted(true)
        // b 还没进过冷却，所以静音时它是「还有 1 条」的那一条
        val after = scheduler.tickWithCandidates(listOf(a, b), signals(), 500L)
        assertNull(after.prompt, "静音后不许再显示")
        assertTrue(after.changed, "静音撤卡同样要通知 UI")
        assertEquals(1, after.visibleCount, "静音时小圆点仍要显示还有几条")
    }

    @Test
    fun samePromptDoesNotReportChangedTwice() {
        val scheduler = PromptScheduler()
        val a = candidate("a")
        assertTrue(scheduler.tickWithCandidates(listOf(a), signals(), 0L).changed)
        assertFalse(
            scheduler.tickWithCandidates(listOf(a), signals(), 500L).changed,
            "同一条连续 tick 不许重复报 changed，否则 2Hz 重刷会闪"
        )
    }

    // ── R10 兜底轮播的可达性（QA P1-2） ───────────────────────────────────────

    /**
     * 端到端：R10 只在 CHATTER 档位下才出得来。
     *
     * 这条用例存在的意义是把「生产 STANDARD 下 GUIDE 全灭」这件事**钉在断言里** ——
     * 之前 108 条用例全绿而 R10 已死，就是因为没有任何一条断言表达过这个事实。
     *
     * 走 `tick()` 而不是 `tickWithCandidates()`：要连规则表归属一起验。
     */
    @Test
    fun fallbackGuideIsReachableOnlyAtChatter() {
        val calm = signals()

        // STANDARD：R10 是 GUIDE，被密度整条过滤 → 一动不动
        val standard = PromptScheduler()
        assertNull(standard.tick(calm, 0L).prompt, "STANDARD 下不该有提示")
        assertNull(
            standard.tick(calm, 6_000L).prompt,
            "STANDARD 下 R10 必须被挡掉（这正是 QA P1-2 的事实链）"
        )

        // CHATTER：R10 放行。holdMs = 6s，所以第 6 秒才出得来
        val chatter = PromptScheduler()
        chatter.setDensity(PromptDensity.CHATTER)
        assertNull(chatter.tick(calm, 0L).prompt, "holdMs 未满足前不许出")
        val shown = chatter.tick(calm, 6_000L)
        assertNotNull(shown.prompt, "CHATTER 档位下 R10 兜底轮播必须出得来")
        assertEquals(
            "light_white_add_black_minus",
            shown.prompt!!.tipId,
            "GENERAL 场景兜底池第一条是「白加黑减」"
        )
        assertEquals(
            6_000L,
            chatter.snapshot().lastGuideAt,
            "兜底轮播是节奏类，出牌后必须推进 lastGuideAt"
        )
    }
}
