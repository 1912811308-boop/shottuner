package com.shottuner.app.analysis

import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四维参考分测试。
 *
 * 每个用例只验一件事：分项边界、阈值口径、未测降级、建议数量。
 * 数值断言尽量用区间而不是精确值 —— 标定常数未来会被真机样张微调，
 * 用例不该因为把 0.12 调成 0.11 就全红。
 */
class ScoreBoardTest {

    private fun stats(
        meanLuma: Float = 0.5f,
        highlightClip: Float = 0f,
        shadowClip: Float = 0f,
        centerLuma: Float = 0.5f,
        focusVar: Float = 0f,
        edgeDensity: Float = 0.2f,
        skinRatio: Float = 0f,
        saturation: Float = 0.3f
    ): FrameStats = FrameStats(
        histogram = FloatArray(256),
        meanLuma = meanLuma,
        p05 = 0.1f,
        p50 = 0.5f,
        p95 = 0.9f,
        highlightClip = highlightClip,
        shadowClip = shadowClip,
        meanR = 0.35f,
        meanG = 0.35f,
        meanB = 0.35f,
        saturation = saturation,
        centerLuma = centerLuma,
        edgeDensity = edgeDensity,
        skinRatio = skinRatio,
        width = 1920,
        height = 1080,
        focusVar = focusVar
    )

    private fun hint(
        score: Int = 100,
        tiltDeg: Float = 0f,
        horizonDetected: Boolean = false,
        subjectX: Float = 0.5f,
        subjectY: Float = 0.5f,
        suggestMove: Boolean = false,
        scoreNoTilt: Int = score
    ): CompositionHint = CompositionHint(
        score = score,
        tiltDeg = tiltDeg,
        horizonDetected = horizonDetected,
        horizonY = 0.5f,
        subjectX = subjectX,
        subjectY = subjectY,
        subjectConfidence = 0.8f,
        subjectSize = 0.25f,
        subjectLeft = (subjectX - 0.125f).coerceIn(0f, 1f),
        subjectTop = (subjectY - 0.125f).coerceIn(0f, 1f),
        subjectRight = (subjectX + 0.125f).coerceIn(0f, 1f),
        subjectBottom = (subjectY + 0.125f).coerceIn(0f, 1f),
        targetX = 0.3333f,
        targetY = 0.3333f,
        suggestMove = suggestMove,
        tips = emptyList(),
        scoreNoTilt = scoreNoTilt
    )

    // ── 满分与总分 ─────────────────────────────────────────────────────────────

    @Test
    fun perfectScores100() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.5f, focusVar = 5_000f),
            hint = hint(score = 100, tiltDeg = 0f, horizonDetected = true),
            roll = 0f,
            scene = SceneType.LANDSCAPE
        )
        assertEquals("四个维度都满分应得 100", 100, result.total)
        assertEquals("优秀", result.grade)
        assertEquals(4, result.dimensions.size)
        assertEquals(
            listOf(
                ScoreDimension.LEVEL,
                ScoreDimension.EXPOSURE,
                ScoreDimension.COMPOSITION,
                ScoreDimension.CLARITY
            ),
            result.dimensions.map { it.dimension }
        )
    }

    @Test
    fun totalIsSumOfDimensions() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.2f, shadowClip = 0.2f, focusVar = 41f),
            hint = hint(score = 60, tiltDeg = 4f, horizonDetected = true),
            roll = null,
            scene = SceneType.LANDSCAPE
        )
        assertEquals(result.dimensions.sumOf { it.score }, result.total)
        assertEquals(ScoreBoard.gradeOf(result.total), result.grade)
        for (dimension in result.dimensions) {
            assertTrue("${dimension.label} 不能是负数", dimension.score >= 0)
            assertTrue("${dimension.label} 不能超过上限", dimension.score <= dimension.max)
        }
    }

    // ── 水平度 ─────────────────────────────────────────────────────────────────

    @Test
    fun levelBoundaries() {
        // 有地平线时用 tiltDeg
        assertEquals(25, ScoreBoard.level(null, hint(tiltDeg = 0f, horizonDetected = true)).score)
        assertEquals(25, ScoreBoard.level(null, hint(tiltDeg = 1f, horizonDetected = true)).score)
        // 3° → 25*(1-0.5) = 12.5，四舍五入 13，允许 ±1
        val three = ScoreBoard.level(null, hint(tiltDeg = 3f, horizonDetected = true)).score
        assertTrue("3° 应落在 11..13，实际 $three", three in 11..13)
        assertEquals(0, ScoreBoard.level(null, hint(tiltDeg = 5f, horizonDetected = true)).score)
        assertEquals(0, ScoreBoard.level(null, hint(tiltDeg = 6f, horizonDetected = true)).score)
    }

    @Test
    fun levelUnmeasuredGivesFull() {
        val dimension = ScoreBoard.level(null, hint(horizonDetected = false))
        assertEquals("两个数据源都没有 → 记满分", 25, dimension.score)
        assertFalse("必须标未测，UI 不能显示 0", dimension.measured)
    }

    @Test
    fun gravityRollOnlyPenalizesBeyondThreeDegrees() {
        // 没有地平线时，手机歪 2° 不代表画面歪 → 不扣分
        val mild = ScoreBoard.level(2f, hint(horizonDetected = false))
        assertEquals(25, mild.score)
        assertTrue(mild.measured)

        // 超过 3° 才按公式扣
        val severe = ScoreBoard.level(6f, hint(horizonDetected = false))
        assertEquals(0, severe.score)
    }

    // ── 曝光 ───────────────────────────────────────────────────────────────────

    @Test
    fun exposureStrictVsRelaxed() {
        // 同一张暗调画面：风景按严格档扣光，夜景走宽松档少扣
        val dim = stats(meanLuma = 0.20f, shadowClip = 0.10f)
        val strict = ScoreBoard.exposure(dim, SceneType.LANDSCAPE).score
        val relaxed = ScoreBoard.exposure(dim, SceneType.NIGHT).score
        assertEquals(10, strict)
        assertEquals(30, relaxed)
        assertTrue("夜间必须比白天宽容", relaxed > strict)
    }

    @Test
    fun exposureHardCap() {
        val blown = ScoreBoard.exposure(stats(meanLuma = 0.7f, highlightClip = 0.5f), SceneType.LANDSCAPE)
        assertTrue("严重过曝必须落 10 分以内，实际 ${blown.score}", blown.score <= 10)

        val pitchBlack = ScoreBoard.exposure(stats(meanLuma = 0.05f, shadowClip = 0.9f), SceneType.LANDSCAPE)
        assertTrue("严重欠曝必须落 10 分以内", pitchBlack.score <= 10)
    }

    @Test
    fun exposureThresholdsAreConvertedFromPrd() {
        // PRD 口径 → shottuner 口径的换算结果必须写死在常量里（§6.3）
        assertEquals(0.20f, ScoreBoard.CLIP_HIGH_PRD, 0.0001f)
        assertEquals(0.12f, ScoreBoard.CLIP_HIGH_CONVERTED, 0.0001f)
        assertEquals(0.35f, ScoreBoard.CLIP_LOW_PRD, 0.0001f)
        assertEquals(0.245f, ScoreBoard.CLIP_LOW_CONVERTED, 0.0001f)
        assertTrue("换算后的过曝阈值必须比 PRD 更敏感", ScoreBoard.CLIP_HIGH_CONVERTED < ScoreBoard.CLIP_HIGH_PRD)
        assertTrue("换算后的欠曝阈值必须比 PRD 更敏感", ScoreBoard.CLIP_LOW_CONVERTED < ScoreBoard.CLIP_LOW_PRD)
    }

    // ── 构图 ───────────────────────────────────────────────────────────────────

    @Test
    fun compositionScalesByQuarter() {
        assertEquals(25, ScoreBoard.composition(hint(scoreNoTilt = 100)).score)
        assertEquals(20, ScoreBoard.composition(hint(scoreNoTilt = 80)).score)
        assertEquals(0, ScoreBoard.composition(hint(scoreNoTilt = 0)).score)
    }

    @Test
    fun compositionIgnoresTiltPenalty() {
        // score 已经被倾斜扣过，scoreNoTilt 才是给四维分用的
        val tilted = hint(score = 70, tiltDeg = 5f, horizonDetected = true, scoreNoTilt = 90)
        // 90 * 0.25 = 22.5，四舍五入取 23（与水平度 12.5 → 13 同一套口径）
        assertEquals(23, ScoreBoard.composition(tilted).score)
    }

    // ── 清晰度 ─────────────────────────────────────────────────────────────────

    @Test
    fun clarityFromSharpness() {
        val unmeasured = ScoreBoard.clarity(stats(focusVar = 0f))
        assertEquals("没算过就是 0 分", 0, unmeasured.score)
        assertFalse("但必须标未测", unmeasured.measured)

        val mid = ScoreBoard.clarity(stats(focusVar = 41f))
        assertTrue("中等清晰度应落在 5..15，实际 ${mid.score}", mid.score in 5..15)
        assertTrue(mid.measured)

        val sharp = ScoreBoard.clarity(stats(focusVar = 5_000f))
        assertEquals(20, sharp.score)
    }

    @Test
    fun sharpnessFlatVsCheckerboard() {
        val flat = FloatArray(16 * 16) { 128f }
        assertEquals(0f, SharpnessMeter.laplacianVariance(flat, 16, 16), 0.0001f)
        assertEquals(0f, SharpnessMeter.normalize(0f, 0.5f), 0.0001f)

        val checker = FloatArray(16 * 16) { index ->
            val x = index % 16
            val y = index / 16
            if ((x + y) % 2 == 0) 255f else 0f
        }
        val checkerVar = SharpnessMeter.laplacianVariance(checker, 16, 16)
        assertTrue("棋盘格的拉普拉斯方差必须显著更大，实际 $checkerVar", checkerVar > 1_000f)
        assertTrue("棋盘格应判为足够锐利", SharpnessMeter.normalize(checkerVar, 0.5f) > 0.5f)
    }

    @Test
    fun sharpnessCompareNormalizesByLuma() {
        // 同一块纹理的原始方差与亮度无关，但归一化分母 `meanLuma*255+8` 更小
        // → 暗画面的归一化值更大，因此"暗"不会被误判成"糊"。
        // 注意要断言归一化前的中间量：`normalize()` 在 HIGH_BREAK 处封顶，
        // 这块棋盘纹理两档都会顶到 1.0，封顶后就看不出差异了。
        val pattern = FloatArray(16 * 16) { index ->
            val x = index % 16
            if (x % 2 == 0) 60f else 20f
        }
        val variance = SharpnessMeter.laplacianVariance(pattern, 16, 16)
        val dark = SharpnessMeter.normalizedVariance(variance, 0.15f)
        val bright = SharpnessMeter.normalizedVariance(variance, 0.60f)
        assertTrue("对比度归一化后，暗画面的归一化方差必须更大，dark=$dark bright=$bright", dark > bright)

        // 落到 0..1 判级时，暗画面也不能比亮画面更容易判糊（含封顶后相等）
        assertTrue(
            "暗画面不能被判得更糊",
            SharpnessMeter.normalize(variance, 0.15f) >= SharpnessMeter.normalize(variance, 0.60f)
        )
    }

    // ── 建议 ───────────────────────────────────────────────────────────────────

    @Test
    fun recommendationsAtMostThree() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.15f, shadowClip = 0.2f, highlightClip = 0.2f, focusVar = 0f),
            hint = hint(score = 40, tiltDeg = 6f, horizonDetected = true, subjectX = 0.02f, suggestMove = true),
            roll = 6f,
            scene = SceneType.LANDSCAPE
        )
        assertTrue("最多 3 条，实际 ${result.recommendations.size}", result.recommendations.size <= 3)
        assertEquals("三个维度都扣分 → 3 条", 3, result.recommendations.size)
        val dimensions = result.recommendations.map { it.dimension }
        assertEquals("每维度最多 1 条", dimensions.size, dimensions.distinct().size)
        for (recommendation in result.recommendations) {
            assertTrue("建议文案不能为空", recommendation.text.isNotEmpty())
        }
    }

    @Test
    fun recommendationsAllPerfect() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.5f, focusVar = 5_000f),
            hint = hint(score = 100, tiltDeg = 0f, horizonDetected = true),
            roll = 0f,
            scene = SceneType.LANDSCAPE
        )
        assertEquals("满分只给一句话", 1, result.recommendations.size)
        assertEquals(ScoreBoard.ALL_PERFECT_TEXT, result.recommendations[0].text)
    }

    @Test
    fun recommendationPointsToRealTipId() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.5f, highlightClip = 0.3f, focusVar = 5_000f),
            hint = hint(score = 100, horizonDetected = false),
            roll = null,
            scene = SceneType.LANDSCAPE
        )
        val exposure = result.recommendations.firstOrNull { it.dimension == ScoreDimension.EXPOSURE }
        assertNotNull("过曝必须给一条曝光建议", exposure)
        assertEquals("basic_protect_highlights", exposure!!.tipId)
    }

    @Test
    fun levelRecommendationCarriesDirection() {
        val result = ScoreBoard.score(
            stats = stats(meanLuma = 0.5f, focusVar = 5_000f),
            hint = hint(score = 100, tiltDeg = 4f, horizonDetected = true),
            roll = null,
            scene = SceneType.LANDSCAPE
        )
        val level = result.recommendations.firstOrNull { it.dimension == ScoreDimension.LEVEL }
        assertNotNull(level)
        assertTrue("建议要带角度", level!!.text.contains("4°"))
        assertTrue(
            "建议要带方向",
            level.text.contains("顺时针") || level.text.contains("逆时针")
        )
        assertEquals("landscape_horizon_level", level.tipId)
    }

    // ── 等级 ───────────────────────────────────────────────────────────────────

    @Test
    fun gradeBoundaries() {
        assertEquals("优秀", ScoreBoard.gradeOf(100))
        assertEquals("优秀", ScoreBoard.gradeOf(85))
        assertEquals("良好", ScoreBoard.gradeOf(84))
        assertEquals("良好", ScoreBoard.gradeOf(70))
        assertEquals("及格", ScoreBoard.gradeOf(69))
        assertEquals("及格", ScoreBoard.gradeOf(60))
        assertEquals("待改进", ScoreBoard.gradeOf(59))
        assertEquals("待改进", ScoreBoard.gradeOf(0))
    }
}
