package com.shottuner.app.analysis

import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 场景融合判定的回归测试。
 *
 * ## 为什么要有这个文件
 *
 * 2026-11-09 用户反馈「识别人像还是风景的精确度不高」。查下来不是模型不行，
 * 是**两条规则把最常见的户外人像判成了风景**（详见下面 `outdoorPortrait…` 的注释）。
 * 这类错在代码里完全看不出来 —— 两个分支单独看都对，只有「人 + 天空」同时出现时才错，
 * 而它恰恰是最常见的拍法。所以把它钉成测试。
 *
 * ## 为什么测 `SceneClassifier` 而不是 `AiVision`
 *
 * `AiVision` 判的是「AI 看到什么」（依赖 ML Kit，真机才能跑），
 * `SceneClassifier` 判的是「AI 与规则的证据怎么融合」——**后者才是决策点**，
 * 且是纯函数（只吃 FrameStats + AiVisionResult），可以在 `commonTest` 里直接跑。
 */
class SceneClassifierTest {

    // ── 素材构造 ──────────────────────────────────────────────────────────────

    /**
     * 构造一份「风景倾向」的统计量：饱和度高、动态范围大、纹理密度中等、亮度居中。
     *
     * ★ 刻意避开逆光的判据（`highlightClip > 0.03` 与 `p05 < 0.20`），
     *   否则会先命中 `SceneClassifier` 里那条「逆光规则自信就不让 AI 改判」，
     *   把要测的分支挡住 —— 那样测试会因为不相干的原因变红/变绿。
     */
    private fun landscapeStats(): FrameStats = FrameStats(
        histogram = FloatArray(256),
        meanLuma = 0.50f,
        p05 = 0.35f,
        p50 = 0.50f,
        p95 = 0.95f,
        highlightClip = 0.0f,
        shadowClip = 0.0f,
        meanR = 0.5f,
        meanG = 0.5f,
        meanB = 0.5f,
        saturation = 0.45f,
        centerLuma = 0.50f,
        edgeDensity = 0.20f,
        skinRatio = 0.02f,
        width = 640,
        height = 480
    )

    /** 一份「什么都测不出」的统计量，用来让 AI 侧成为唯一证据。 */
    private fun neutralStats(): FrameStats = FrameStats(
        histogram = FloatArray(256),
        meanLuma = 0.45f,
        p05 = 0.30f,
        p50 = 0.45f,
        p95 = 0.60f,
        highlightClip = 0.0f,
        shadowClip = 0.0f,
        meanR = 0.5f,
        meanG = 0.5f,
        meanB = 0.5f,
        saturation = 0.20f,
        centerLuma = 0.45f,
        edgeDensity = 0.02f,
        skinRatio = 0.0f,
        width = 640,
        height = 480
    )

    private fun ai(
        scene: AiScene,
        confidence: Float,
        faceCount: Int = 0,
        faceArea: Float = 0f
    ): AiVisionResult = AiVisionResult(
        scene = scene,
        confidence = confidence,
        faceCount = faceCount,
        faceArea = faceArea,
        tags = emptyList(),
        ok = true
    )

    // ── 核心回归 ──────────────────────────────────────────────────────────────

    /**
     * ★ 这一条就是用户报的那个 bug。
     *
     * 场景：户外人像 —— 画面里有人，背景有天空/山。
     * 旧行为：`AiVision` 因为「有人 + 有天空」判成风景 0.93，规则侧也偏风景，
     * 于是 `SceneClassifier` 返回 **风景** —— 用户举着人拍，App 却在教他拍风光。
     * 新行为：标签级人像（≥0.70）直接覆盖规则。
     */
    @Test
    fun outdoorPortraitBeatsLandscapeRules() {
        val result = SceneClassifier.classify(
            landscapeStats(),
            ai(AiScene.PORTRAIT, confidence = 0.76f)
        )
        assertEquals(
            SceneType.PORTRAIT,
            result.scene,
            "户外人像被规则判成了 ${result.scene}（来源 ${result.source}）—— 人像必须压过风景"
        )
    }

    /** 没有人、AI 也说是风景 → 就该是风景。别把修复做过头，风景判成人像同样是错的。 */
    @Test
    fun landscapeWithoutPeopleStaysLandscape() {
        val result = SceneClassifier.classify(
            landscapeStats(),
            ai(AiScene.LANDSCAPE, confidence = 0.93f)
        )
        assertEquals(SceneType.LANDSCAPE, result.scene)
    }

    /** 人脸够大是硬证据：哪怕规则偏风景，也必须是人像。 */
    @Test
    fun bigFaceBeatsEverything() {
        val result = SceneClassifier.classify(
            landscapeStats(),
            ai(AiScene.LANDSCAPE, confidence = 0.93f, faceCount = 1, faceArea = 0.05f)
        )
        assertEquals(SceneType.PORTRAIT, result.scene)
        assertEquals("AI·人脸", result.source)
    }

    /** AI 最弱那一档（0.30，UNKNOWN）不该左右判定 —— 此时应当完全听规则的。 */
    @Test
    fun weakAiDoesNotOverrideRules() {
        val result = SceneClassifier.classify(
            landscapeStats(),
            ai(AiScene.UNKNOWN, confidence = 0.30f)
        )
        assertEquals(SceneType.LANDSCAPE, result.scene)
    }

    /** 没有 AI 结果（首帧、ML Kit 还没回来）时，规则必须能独立工作。 */
    @Test
    fun nullAiFallsBackToRules() {
        val result = SceneClassifier.classify(landscapeStats(), null)
        assertEquals(SceneType.LANDSCAPE, result.scene)
    }

    /** 标签级人像的置信度被抬到 0.55 以上，界面才敢显示「人像 xx%」。 */
    @Test
    fun labelPortraitConfidenceIsUsable() {
        val result = SceneClassifier.classify(
            neutralStats(),
            ai(AiScene.PORTRAIT, confidence = 0.72f)
        )
        assertEquals(SceneType.PORTRAIT, result.scene)
        assertTrue(
            result.confidence >= 0.55f,
            "置信度 ${result.confidence} 太低，界面会显示成不确定"
        )
    }
}
