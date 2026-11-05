package com.shottuner.app.growth

import com.shottuner.app.analysis.Recommendation
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 任务状态机测试。
 *
 * 重点是**防"闪绿"**的四条：
 * ① 连续 2 次达标不算；② 中间夹一次不达标就归零；③ 单帧跳变（显著度质心会跳）不算；
 * ④ ACHIEVED 之后单向锁定。这四条任何一条坏了，"到位"这个承诺就不可信。
 *
 * 时间全部用显式的 `now`（elapsedRealtime 语义），一 tick = 400ms，与
 * `renderComposition()` 的节流节拍一致。
 */
class MissionEngineTest {

    private companion object {
        const val ASPECT = 1.7778f
        const val TARGET_X = 0.6667f
        const val TARGET_Y = 0.3333f
        const val TICK = 400L

        val REC_EYES = Recommendation(
            tipId = "portrait_eyes_thirds",
            text = "主体往右上 交点靠一点",
            dimension = ScoreDimension.COMPOSITION
        )
        val REC_LEVEL = Recommendation(
            tipId = "landscape_horizon_level",
            text = "地平线还差 4°，顺时针转一点",
            dimension = ScoreDimension.LEVEL
        )
    }

    private fun stats(skinRatio: Float = 0f): FrameStats = FrameStats(
        histogram = FloatArray(256),
        meanLuma = 0.5f,
        p05 = 0.1f,
        p50 = 0.5f,
        p95 = 0.9f,
        highlightClip = 0f,
        shadowClip = 0f,
        meanR = 0.35f,
        meanG = 0.35f,
        meanB = 0.35f,
        saturation = 0.3f,
        centerLuma = 0.5f,
        edgeDensity = 0.2f,
        skinRatio = skinRatio,
        width = 1080,
        height = 1920
    )

    private fun hint(
        subjectX: Float,
        subjectY: Float,
        subjectTop: Float = 0.30f,
        horizonDetected: Boolean = false,
        tiltDeg: Float = 0f
    ): CompositionHint = CompositionHint(
        score = 60,
        tiltDeg = tiltDeg,
        horizonDetected = horizonDetected,
        horizonY = 0.5f,
        subjectX = subjectX,
        subjectY = subjectY,
        subjectConfidence = 0.8f,
        subjectSize = 0.25f,
        subjectLeft = (subjectX - 0.125f).coerceIn(0f, 1f),
        subjectTop = subjectTop,
        subjectRight = (subjectX + 0.125f).coerceIn(0f, 1f),
        subjectBottom = (subjectY + 0.125f).coerceIn(0f, 1f),
        targetX = TARGET_X,
        targetY = TARGET_Y,
        suggestMove = true,
        tips = emptyList(),
        scoreNoTilt = 60
    )

    /**
     * @param atTarget true → 主体已在三分交点（构图任务达标）；false → 主体在正中（d ≈ 0.34，可派发但不达标）
     */
    private fun sample(
        now: Long,
        atTarget: Boolean = false,
        recs: List<Recommendation> = listOf(REC_EYES),
        roll: Float? = null,
        horizonDetected: Boolean = false,
        tiltDeg: Float = 0f
    ): MissionSample {
        val subjectX = if (atTarget) TARGET_X else 0.5f
        val subjectY = if (atTarget) TARGET_Y else 0.5f
        return MissionSample(
            now = now,
            stats = stats(),
            hint = hint(
                subjectX = subjectX,
                subjectY = subjectY,
                horizonDetected = horizonDetected,
                tiltDeg = tiltDeg
            ),
            roll = roll,
            recs = recs,
            aspect = ASPECT
        )
    }

    /** 派发一条构图任务：主体在中心（d ≈ 0.34，落在 [TRIGGER, GIVEUP] 内）。 */
    private fun dispatchComposition(now: Long = 1_000L): MissionEngine {
        val engine = MissionEngine()
        assertEquals(MissionEvent.SHOW, engine.onSample(sample(now = now)))
        return engine
    }

    /** 从派发开始一直喂"已在目标点"的 sample，返回达成时用了多少帧（不含 SHOW 那一帧）。 */
    private fun ticksNeededToAchieve(): Int {
        val engine = dispatchComposition()
        var now = 1_000L
        var ticks = 0
        var event = MissionEvent.NONE
        while (ticks < 40 && event != MissionEvent.ACHIEVED) {
            now += TICK
            event = engine.onSample(sample(now = now, atTarget = true))
            ticks++
        }
        assertEquals(MissionEvent.ACHIEVED, event, "必须能达成")
        return ticks
    }

    // ── 1~2. 派发 ──────────────────────────────────────────────────────────────

    @Test
    fun startsIdle() {
        val engine = MissionEngine()
        assertEquals(MissionState.IDLE, engine.state)
        assertNull(engine.current)
        assertFalse(engine.occupiesSurface())
        assertEquals(0f, engine.ringProgress(), 0.0001f)
    }

    @Test
    fun centeredSubjectShowsTask() {
        val engine = MissionEngine()
        assertEquals(MissionEvent.SHOW, engine.onSample(sample(now = 1_000L)))
        assertEquals(MissionState.TASK_SHOWN, engine.state)
        assertEquals(TaskKind.COMPOSITION, engine.current!!.kind)
        assertTrue(engine.occupiesSurface())
    }

    // ── 3. 800ms 缓冲 ──────────────────────────────────────────────────────────

    @Test
    fun taskShownBufferBlocksImmediateAchievement() {
        val engine = dispatchComposition(now = 1_000L)

        val duringBuffer = engine.onSample(sample(now = 1_400L, atTarget = true))
        assertNotEquals(MissionEvent.ACHIEVED, duringBuffer, "缓冲期内不能达成")
        assertEquals(MissionState.TASK_SHOWN, engine.state)

        val stillBuffer = engine.onSample(sample(now = 1_799L, atTarget = true))
        assertNotEquals(MissionEvent.ACHIEVED, stillBuffer)
        assertEquals(MissionState.TASK_SHOWN, engine.state)

        engine.onSample(sample(now = 1_800L, atTarget = true))
        assertEquals(MissionState.IN_PROGRESS, engine.state, "800ms 后才进 IN_PROGRESS")
    }

    // ── 4~5. 连续达标 ──────────────────────────────────────────────────────────

    @Test
    fun twoConsecutiveHitsStayInProgress() {
        val engine = dispatchComposition(now = 1_000L)
        assertEquals(MissionEvent.NONE, engine.onSample(sample(now = 1_400L, atTarget = true)))
        assertEquals(MissionEvent.PROGRESS, engine.onSample(sample(now = 1_800L, atTarget = true)))
        assertEquals(MissionState.IN_PROGRESS, engine.state)

        val second = engine.onSample(sample(now = 2_200L, atTarget = true))
        assertNotEquals(MissionEvent.ACHIEVED, second, "2 次达标不能达成（REQUIRED_HITS = 3）")
        assertEquals(MissionState.IN_PROGRESS, engine.state)
    }

    @Test
    fun keepsHittingUntilRequiredHits() {
        val engine = dispatchComposition(now = 1_000L)
        var now = 1_000L
        var ticks = 0
        var event = MissionEvent.NONE
        while (ticks < 25 && event != MissionEvent.ACHIEVED) {
            now += TICK
            event = engine.onSample(sample(now = now, atTarget = true))
            ticks++
        }
        assertEquals(MissionEvent.ACHIEVED, event)
        assertEquals(MissionState.ACHIEVED, engine.state)
        // EMA 预热会吃掉前面几帧：从 d=0.34 出发，smoothed 要衰减到 0.075 以下才开始计 hit
        assertTrue(ticks >= MissionThresholds.REQUIRED_HITS, "不允许少于 3 帧就绿：实际 $ticks 帧")
        assertTrue(ticks * TICK < MissionThresholds.TASK_TIMEOUT_MS, "必须在 20s 超时前达成：实际 $ticks 帧")
    }

    // ── 6. 归零 ────────────────────────────────────────────────────────────────

    @Test
    fun aSingleMissResetsHits() {
        val needed = ticksNeededToAchieve()
        assertTrue(needed >= MissionThresholds.REQUIRED_HITS, "从派发到达成至少要 3 帧，实际 $needed")

        val engine = dispatchComposition(now = 1_000L)
        var now = 1_000L
        repeat(needed - 1) {
            now += TICK
            engine.onSample(sample(now = now, atTarget = true))
        }
        assertEquals(MissionState.IN_PROGRESS, engine.state, "差一帧就达成")

        // 中间夹一次不达标（主体回到中心）→ hits 归零
        now += TICK
        val missed = engine.onSample(sample(now = now, atTarget = false))
        assertNotEquals(MissionEvent.ACHIEVED, missed)
        assertEquals(MissionState.IN_PROGRESS, engine.state)

        // 归零后必须重新攒满 3 帧：只喂 3 帧不能达成
        repeat(3) {
            now += TICK
            val event = engine.onSample(sample(now = now, atTarget = true))
            assertNotEquals(MissionEvent.ACHIEVED, event, "hits 归零生效：不达标后不能靠 1~3 帧达成")
        }
        assertEquals(MissionState.IN_PROGRESS, engine.state)
    }

    // ── 7. 单帧跳变（最重要的一条）────────────────────────────────────────────

    @Test
    fun singleFrameJumpDoesNotAchieve() {
        val engine = MissionEngine()
        // 派发时主体在中心 d ≈ 0.34；TASK_SHOWN 缓冲期内主体没动
        assertEquals(MissionEvent.SHOW, engine.onSample(sample(now = 1_000L)))
        assertEquals(MissionEvent.NONE, engine.onSample(sample(now = 1_400L, atTarget = false)))

        // 第一个 IN_PROGRESS 帧：subjectX/Y 直接跳到目标点（显著度质心的典型跳变）
        val event = engine.onSample(sample(now = 1_800L, atTarget = true))
        assertNotEquals(MissionEvent.ACHIEVED, event, "单帧跳变不能达成")
        assertEquals(MissionState.IN_PROGRESS, engine.state)
        // ringProgress < 1 ⟺ smoothed > RING_RADIUS（构图进度的定义），即仍在圈外
        assertTrue(
            engine.ringProgress() < 1f,
            "EMA 后 smoothed 必须仍在达标圈外：ringProgress=${engine.ringProgress()}"
        )
    }

    // ── 8. 单向锁定 ────────────────────────────────────────────────────────────

    @Test
    fun achievedIsLockedOneWay() {
        val engine = dispatchComposition(now = 1_000L)
        var now = 1_000L
        var event = MissionEvent.NONE
        while (event != MissionEvent.ACHIEVED && now < 20_000L) {
            now += TICK
            event = engine.onSample(sample(now = now, atTarget = true))
        }
        assertEquals(MissionEvent.ACHIEVED, event)
        val achievedAt = now

        // ACHIEVED 之后喂不达标 sample：状态不变
        repeat(2) { index ->
            val e = engine.onSample(sample(now = achievedAt + (index + 1) * TICK, atTarget = false))
            assertEquals(MissionEvent.NONE, e)
            assertEquals(MissionState.ACHIEVED, engine.state)
        }

        // 1.2s 后进 SCORED
        assertEquals(MissionEvent.HIDE, engine.onSample(sample(now = achievedAt + MissionThresholds.ACHIEVE_TO_SCORE_MS)))
        assertEquals(MissionState.SCORED, engine.state)
        assertTrue(engine.occupiesSurface())

        // SCORED 期间喂不达标也不回退
        repeat(3) { index ->
            engine.onSample(sample(now = achievedAt + 1_200L + (index + 1) * TICK, atTarget = false))
            assertEquals(MissionState.SCORED, engine.state)
        }
    }

    // ── 9~10. 超时 ────────────────────────────────────────────────────────────

    @Test
    fun timeoutAfterTwentySecondsAndCooldown() {
        val engine = dispatchComposition(now = 1_000L)
        var now = 1_000L
        var event = MissionEvent.NONE
        while (event != MissionEvent.TIMEOUT && now < 30_000L) {
            now += TICK
            event = engine.onSample(sample(now = now, atTarget = false))     // 一直不达标
        }
        assertEquals(MissionEvent.TIMEOUT, event)
        assertTrue(now - 1_000L > MissionThresholds.TASK_TIMEOUT_MS, "超时必须发生在 20s 之后：now=$now")
        assertEquals(MissionState.IDLE, engine.state, "挑不到新任务 → 回 IDLE")
        val timeoutAt = now

        // 12s 冷却期内不能重新派发同一条
        var probe = timeoutAt + TICK
        while (probe < timeoutAt + MissionThresholds.TIMEOUT_COOLDOWN_MS) {
            assertEquals(MissionEvent.NONE, engine.onSample(sample(now = probe)), "冷却期内不能派发：t=$probe")
            probe += TICK
        }

        // 冷却结束后重新派发
        var late = timeoutAt + MissionThresholds.TIMEOUT_COOLDOWN_MS
        var shown = MissionEvent.NONE
        while (shown != MissionEvent.SHOW && late < timeoutAt + 30_000L) {
            shown = engine.onSample(sample(now = late))
            late += TICK
        }
        assertEquals(MissionEvent.SHOW, shown, "冷却结束后应重新派发")
    }

    @Test
    fun twoConsecutiveTimeoutsEnterQuietPeriod() {
        val recs = listOf(REC_EYES, REC_LEVEL)
        val engine = MissionEngine()
        // 两条都可行：构图 d≈0.34，水平 roll=5°
        assertEquals(MissionEvent.SHOW, engine.onSample(sample(now = 1_000L, recs = recs, roll = 5f)))
        assertEquals(TaskKind.COMPOSITION, engine.current!!.kind)

        var now = 1_000L
        var event = MissionEvent.NONE
        while (event != MissionEvent.TIMEOUT && now < 30_000L) {
            now += TICK
            event = engine.onSample(sample(now = now, recs = recs, roll = 5f))
        }
        assertEquals(MissionEvent.TIMEOUT, event)
        assertEquals(TaskKind.LEVEL, engine.current!!.kind, "第一条冷却中 → 换到水平任务")
        assertEquals(MissionState.TASK_SHOWN, engine.state)

        event = MissionEvent.NONE
        while (event != MissionEvent.TIMEOUT && now < 60_000L) {
            now += TICK
            event = engine.onSample(sample(now = now, recs = recs, roll = 5f))
        }
        assertEquals(MissionEvent.TIMEOUT, event)
        assertEquals(MissionState.IDLE, engine.state)
        val quietAt = now

        // 之后 60s 内一律 NONE（不派发、不进任何状态）
        repeat(20) { index ->
            assertEquals(
                MissionEvent.NONE,
                engine.onSample(sample(now = quietAt + (index + 1) * TICK, recs = recs, roll = 5f)),
                "静默期内必须完全沉默：t=${quietAt + (index + 1) * TICK}"
            )
        }
        assertEquals(MissionState.IDLE, engine.state)
        assertFalse(engine.occupiesSurface())
    }

    // ── 11. abort ──────────────────────────────────────────────────────────────

    @Test
    fun abortBeforeAchievedNeverAchieves() {
        val engine = dispatchComposition(now = 1_000L)
        engine.onSample(sample(now = 1_400L, atTarget = true))
        engine.onSample(sample(now = 1_800L, atTarget = true))
        assertEquals(MissionState.IN_PROGRESS, engine.state)

        engine.abort(AbortReason.CAPTURE)
        assertEquals(MissionState.IDLE, engine.state)
        assertNull(engine.current)
        assertFalse(engine.occupiesSurface())

        // abort 之后即便一直达标也不产生 ACHIEVED（3s 节流内连 pick 都不会跑）
        var now = 1_800L
        repeat(6) {
            now += TICK
            val event = engine.onSample(sample(now = now, atTarget = true))
            assertNotEquals(MissionEvent.ACHIEVED, event, "abort 之后不可能达成")
            assertEquals(MissionState.IDLE, engine.state)
        }

        // 幂等：重复 abort 无副作用
        engine.abort(AbortReason.TAB)
        assertEquals(MissionState.IDLE, engine.state)
    }

    // ── 12~13. 水平任务 ────────────────────────────────────────────────────────

    @Test
    fun levelTaskDispatchesAndAchieves() {
        val engine = MissionEngine()
        val recs = listOf(REC_LEVEL)
        assertEquals(
            MissionEvent.SHOW,
            engine.onSample(sample(now = 1_000L, recs = recs, roll = 4.5f)),
            "|θ| = 4.5° 必须派发"
        )
        assertEquals(TaskKind.LEVEL, engine.current!!.kind)
        assertEquals(0f, engine.ringProgress(), 0.0001f, "派发瞬间环是全灰的")

        // 用户把手机端平到 2.4°
        var now = 1_000L
        var ticks = 0
        var event = MissionEvent.NONE
        while (ticks < 40 && event != MissionEvent.ACHIEVED) {
            now += TICK
            event = engine.onSample(sample(now = now, recs = recs, roll = 2.4f))
            ticks++
        }
        assertEquals(MissionEvent.ACHIEVED, event)
        assertEquals(MissionState.ACHIEVED, engine.state)
        assertTrue(ticks >= MissionThresholds.REQUIRED_HITS, "不允许第一帧就绿：实际 $ticks 帧")
    }

    @Test
    fun noLevelTaskWithoutTiltSource() {
        val engine = MissionEngine()
        val recs = listOf(REC_LEVEL)
        // roll = null 且 horizonDetected = false → 两个源都没有，不派发
        assertEquals(
            MissionEvent.NONE,
            engine.onSample(sample(now = 1_000L, recs = recs, roll = null, horizonDetected = false))
        )
        assertEquals(MissionState.IDLE, engine.state)
        assertNull(engine.current)
    }

    // ── 14. 倒计时口径（remainingMs）───────────────────────────────────────────

    @Test
    fun remainingMsIsZeroWhenIdle() {
        // 一个 tick 都没喂过：lastNowMs = 0，绝不能返回负数或 20s
        assertEquals(0L, MissionEngine().remainingMs(), "IDLE 没有倒计时")
    }

    @Test
    fun remainingMsCountsDownFromDispatchNotFromProgress() {
        val engine = dispatchComposition(now = 1_000L)
        // 派发那一刻：整整 20s。TASK_SHOWN 的 800ms 缓冲也吃这 20s 预算，
        // 所以倒计时从 shownAtMs 起算，而不是从进 IN_PROGRESS 的那一刻起算。
        assertEquals(MissionThresholds.TASK_TIMEOUT_MS, engine.remainingMs())

        engine.onSample(sample(now = 6_000L, atTarget = true))
        assertEquals(15_000L, engine.remainingMs(), "5s 后应该剩 15s")

        // 恰好 20s：仍未超时（判定用的是 `>`），剩余夹到 0，不能是负数
        engine.onSample(sample(now = 21_000L, atTarget = true))
        assertEquals(MissionState.IN_PROGRESS, engine.state, "恰好 20s 还不算超时")
        assertEquals(0L, engine.remainingMs(), "剩余必须夹到 ≥ 0")
    }

    @Test
    fun remainingMsFollowsAchievedAndScoredWindows() {
        val engine = dispatchComposition(now = 1_000L)
        var now = 1_000L
        var event = MissionEvent.NONE
        while (event != MissionEvent.ACHIEVED && now < 20_000L) {
            now += TICK
            event = engine.onSample(sample(now = now, atTarget = true))
        }
        assertEquals(MissionEvent.ACHIEVED, event)
        // ACHIEVED 阶段的"剩余"是「还有多久转成绩卡」，不是任务超时
        assertEquals(MissionThresholds.ACHIEVE_TO_SCORE_MS, engine.remainingMs())
        val achievedAt = now

        engine.onSample(sample(now = achievedAt + MissionThresholds.ACHIEVE_TO_SCORE_MS))
        assertEquals(MissionState.SCORED, engine.state)
        // SCORED 阶段的"剩余"是成绩卡还要停留多久
        assertEquals(MissionThresholds.SCORE_CARD_MS, engine.remainingMs())

        engine.onSample(sample(now = achievedAt + MissionThresholds.ACHIEVE_TO_SCORE_MS + 3_000L))
        assertEquals(5_000L, engine.remainingMs(), "8s 里过了 3s")

        // 成绩卡收完之后回到 IDLE → 0
        engine.onSample(
            sample(now = achievedAt + MissionThresholds.ACHIEVE_TO_SCORE_MS + MissionThresholds.SCORE_CARD_MS)
        )
        assertEquals(MissionState.IDLE, engine.state)
        assertEquals(0L, engine.remainingMs(), "任务结束后回到 0")
    }

    @Test
    fun abortZeroesRemainingMs() {
        val engine = dispatchComposition(now = 1_000L)
        assertEquals(MissionThresholds.TASK_TIMEOUT_MS, engine.remainingMs(), "派发后有倒计时")
        engine.abort(AbortReason.TAB)
        assertEquals(0L, engine.remainingMs(), "中断后没有倒计时")
    }
}
