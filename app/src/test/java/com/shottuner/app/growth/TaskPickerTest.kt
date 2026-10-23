package com.shottuner.app.growth

import com.shottuner.app.analysis.Recommendation
import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务选择器测试。
 *
 * 只验"派发决策"：白名单、可行性窗口、置信度、冷却、文案长度。
 * 判定（EMA / hits / 单向锁定）在 MissionEngineTest 里。
 *
 * 数值断言用区间而不是精确值：阈值常量是真机标定的，改 0.075 → 0.08 不该让用例全红。
 */
class TaskPickerTest {

    private companion object {
        const val ASPECT = 1.7778f              // 16:9 兜底
        const val TARGET_X = 0.6667f            // 就近三分交点
        const val TARGET_Y = 0.3333f
        const val NOW = 1_000L

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
        val REC_HEADROOM = Recommendation(
            tipId = "portrait_headroom",
            text = "退半步，别让主体贴边",
            dimension = ScoreDimension.COMPOSITION
        )
        val REC_STEADY = Recommendation(
            tipId = "move_elbow_tucked",
            text = "手肘夹住身体，按快门时屏住呼吸",
            dimension = ScoreDimension.CLARITY
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
        subjectX: Float = 0.5f,
        subjectY: Float = 0.5f,
        subjectConfidence: Float = 0.8f,
        subjectTop: Float = 0.30f,
        targetX: Float = TARGET_X,
        targetY: Float = TARGET_Y,
        horizonDetected: Boolean = false,
        tiltDeg: Float = 0f,
        suggestMove: Boolean = true,
        scoreNoTilt: Int = 100
    ): CompositionHint = CompositionHint(
        score = scoreNoTilt,
        tiltDeg = tiltDeg,
        horizonDetected = horizonDetected,
        horizonY = 0.5f,
        subjectX = subjectX,
        subjectY = subjectY,
        subjectConfidence = subjectConfidence,
        subjectSize = 0.25f,
        subjectLeft = (subjectX - 0.125f).coerceIn(0f, 1f),
        subjectTop = subjectTop,
        subjectRight = (subjectX + 0.125f).coerceIn(0f, 1f),
        subjectBottom = (subjectY + 0.125f).coerceIn(0f, 1f),
        targetX = targetX,
        targetY = targetY,
        suggestMove = suggestMove,
        tips = emptyList(),
        scoreNoTilt = scoreNoTilt
    )

    private fun sample(
        recs: List<Recommendation> = listOf(REC_EYES),
        skinRatio: Float = 0f,
        roll: Float? = null,
        subjectX: Float = 0.5f,
        subjectY: Float = 0.5f,
        subjectConfidence: Float = 0.8f,
        subjectTop: Float = 0.30f,
        horizonDetected: Boolean = false,
        tiltDeg: Float = 0f,
        aspect: Float = ASPECT
    ): MissionSample = MissionSample(
        now = NOW,
        stats = stats(skinRatio = skinRatio),
        hint = hint(
            subjectX = subjectX,
            subjectY = subjectY,
            subjectConfidence = subjectConfidence,
            subjectTop = subjectTop,
            horizonDetected = horizonDetected,
            tiltDeg = tiltDeg
        ),
        roll = roll,
        recs = recs,
        aspect = aspect
    )

    /** 把主体放在"距目标点 d（短边单位）"的位置：只动纵向，保证坐标都在 0..1 内。 */
    private fun sampleAtDistance(d: Float, recs: List<Recommendation> = listOf(REC_EYES)): MissionSample =
        sample(
            recs = recs,
            subjectX = TARGET_X,
            subjectY = TARGET_Y + d / ASPECT
        )

    // ── 1. 方位词 ──────────────────────────────────────────────────────────────

    @Test
    fun whereLabelMatchesScoreBoardForAllQuadrants() {
        val quadrants = listOf(
            Triple(0.3333f, 0.3333f, "左上"),
            Triple(0.3333f, 0.6667f, "左下"),
            Triple(0.6667f, 0.3333f, "右上"),
            Triple(0.6667f, 0.6667f, "右下")
        )
        for ((targetX, targetY, expected) in quadrants) {
            assertEquals("$targetX,$targetY", expected, TaskPicker.whereLabel(targetX, targetY))
            assertEquals("必须与 ScoreBoard 同一份实现", expected, ScoreBoard.whereLabel(targetX, targetY))

            // 与 ScoreBoard.buildRecommendations 对同一 hint 产出的方位词一致（防两处走样）
            val result = ScoreBoard.score(
                stats = stats(),
                hint = hint(
                    subjectX = 0.5f,
                    subjectY = 0.5f,
                    targetX = targetX,
                    targetY = targetY,
                    suggestMove = true,
                    scoreNoTilt = 40                      // 构图维度扣分 → 必出这条建议
                ),
                roll = 0f,
                scene = SceneType.LANDSCAPE
            )
            val rec = result.recommendations.first { it.tipId == "portrait_eyes_thirds" }
            assertTrue("任务卡与建议的方位词必须一致：${rec.text}", rec.text.contains(expected))
        }
    }

    // ── 2~5. 构图任务的可行性窗口 ──────────────────────────────────────────────

    @Test
    fun compositionBelowTriggerIsNotDispatched() {
        val s = sampleAtDistance(d = 0.10f)
        assertTrue("d 必须真的低于 TRIGGER_D", TaskPicker.distToTarget(s.hint, s.aspect) < MissionThresholds.TRIGGER_D)
        assertNull("主体本来就在圈附近，不值得出任务", TaskPicker.pick(s.recs, s, emptyMap()))
    }

    @Test
    fun compositionInsideWindowIsDispatched() {
        val s = sampleAtDistance(d = 0.20f)
        val task = TaskPicker.pick(s.recs, s, emptyMap())
        assertNotNull(task)
        assertEquals(TaskKind.COMPOSITION, task!!.kind)
        assertEquals("portrait_eyes_thirds", task.tipId)
        assertEquals(ScoreDimension.COMPOSITION, task.dimension)
        assertTrue("主句要带方位：${task.title}", task.title.contains("右上"))
    }

    @Test
    fun compositionBeyondGiveupIsNotDispatched() {
        val s = sampleAtDistance(d = 0.60f)
        assertTrue("d 必须真的高于 GIVEUP_D", TaskPicker.distToTarget(s.hint, s.aspect) > MissionThresholds.GIVEUP_D)
        assertNull("主体在对角，15 秒内做不到", TaskPicker.pick(s.recs, s, emptyMap()))
    }

    @Test
    fun lowSubjectConfidenceIsNotDispatched() {
        val s = sample(
            subjectX = TARGET_X,
            subjectY = TARGET_Y + 0.20f / ASPECT,
            subjectConfidence = 0.20f
        )
        assertNull("置信度低于 0.30 是在指挥噪声", TaskPicker.pick(s.recs, s, emptyMap()))
    }

    // ── 6~7. 白名单过滤 ────────────────────────────────────────────────────────

    @Test
    fun ineligibleFirstRecIsSkipped() {
        val s = sampleAtDistance(d = 0.20f, recs = listOf(REC_STEADY, REC_EYES))
        val task = TaskPicker.pick(s.recs, s, emptyMap())
        assertNotNull("第一条不可完成 → 跳过选下一条", task)
        assertEquals("portrait_eyes_thirds", task!!.tipId)
        assertEquals(TaskKind.COMPOSITION, task.kind)
    }

    @Test
    fun allPerfectRecIsNotDispatched() {
        val perfect = Recommendation(tipId = "", text = ScoreBoard.ALL_PERFECT_TEXT, dimension = ScoreDimension.CLARITY)
        val s = sampleAtDistance(d = 0.20f, recs = listOf(perfect))
        assertNull(TaskPicker.pick(s.recs, s, emptyMap()))
    }

    // ── 8. headroom ────────────────────────────────────────────────────────────

    @Test
    fun headroomNeedsSkinRatio() {
        val lowSkin = sample(recs = listOf(REC_HEADROOM), skinRatio = 0.03f, subjectTop = 0.05f)
        assertNull("没有肤色时不能把天空当头顶", TaskPicker.pick(lowSkin.recs, lowSkin, emptyMap()))

        val withSkin = sample(recs = listOf(REC_HEADROOM), skinRatio = 0.10f, subjectTop = 0.05f)
        val task = TaskPicker.pick(withSkin.recs, withSkin, emptyMap())
        assertNotNull(task)
        assertEquals(TaskKind.HEADROOM, task!!.kind)
        assertEquals("portrait_headroom", task.tipId)
    }

    // ── 9. 冷却 ────────────────────────────────────────────────────────────────

    @Test
    fun coolingDownTipIsSkipped() {
        val recs = listOf(REC_EYES, REC_HEADROOM)
        val s = sample(recs = recs, skinRatio = 0.10f, subjectTop = 0.05f)   // 两条都可行
        assertEquals("不冷却时取第一条", "portrait_eyes_thirds", TaskPicker.pick(s.recs, s, emptyMap())?.tipId)

        val cooled = mapOf("portrait_eyes_thirds" to NOW + 10_000L)
        assertEquals("冷却中的 tipId 必须被跳过", "portrait_headroom", TaskPicker.pick(s.recs, s, cooled)?.tipId)

        val bothCooled = mapOf(
            "portrait_eyes_thirds" to NOW + 10_000L,
            "portrait_headroom" to NOW + 10_000L
        )
        assertNull("全在冷却 → 不派发", TaskPicker.pick(s.recs, s, bothCooled))
    }

    // ── 10. 文案长度 ───────────────────────────────────────────────────────────

    @Test
    fun taskCopyFitsTheCard() {
        val composition = TaskPicker.pick(sampleAtDistance(0.20f).recs, sampleAtDistance(0.20f), emptyMap())!!
        val level = TaskPicker.pick(
            sample(recs = listOf(REC_LEVEL), roll = 5f).recs,
            sample(recs = listOf(REC_LEVEL), roll = 5f),
            emptyMap()
        )!!
        val headroom = TaskPicker.pick(
            sample(recs = listOf(REC_HEADROOM), skinRatio = 0.10f, subjectTop = 0.05f).recs,
            sample(recs = listOf(REC_HEADROOM), skinRatio = 0.10f, subjectTop = 0.05f),
            emptyMap()
        )!!

        for (task in listOf(composition, level, headroom)) {
            assertTrue("主句不能为空", task.title.isNotEmpty())
            assertTrue("主句 ≤10 字：${task.title}(${task.title.length})", task.title.length <= 10)
            assertTrue("副句不能为空", task.hint.isNotEmpty())
            assertTrue("副句 ≤16 字：${task.hint}(${task.hint.length})", task.hint.length <= 16)
            assertTrue("技法短名不能为空", task.skillName.isNotEmpty())
            assertTrue("技法短名 ≤5 字：${task.skillName}", task.skillName.length <= 5)
        }

        assertEquals("把手机端平", level.title)
        assertEquals("头顶多留一点空间", headroom.title)
        assertEquals("三分法", composition.skillName)
    }
}
