package com.shottuner.app.analysis

import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import com.shottuner.app.sensors.LevelMath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 四个评分维度。 */
enum class ScoreDimension { LEVEL, EXPOSURE, COMPOSITION, CLARITY }

/** 单个维度的得分。 */
class DimensionScore(
    val dimension: ScoreDimension,
    val label: String,
    val score: Int,
    val max: Int,
    /** false → UI 显示「未测」而不是 0。缺数据不扣用户分。 */
    val measured: Boolean
) {
    override fun toString(): String = "$label $score/$max${if (measured) "" else "(未测)"}"
}

/** 一条可追溯到技法的改进建议。 */
class Recommendation(
    /** 关联技法 id；空串表示"没有具体条目"（例如全满分时的那句） */
    val tipId: String,
    val text: String,
    val dimension: ScoreDimension
)

/** 一次复盘结果。 */
class ScoreResult(
    val total: Int,
    /** 优秀 / 良好 / 及格 / 待改进 */
    val grade: String,
    val dimensions: List<DimensionScore>,
    /** ≤3 条，每维度最多 1 条 */
    val recommendations: List<Recommendation>
)

/**
 * 四维参考分合成。
 *
 * ## 为什么叫「参考分」
 * shottuner 没有 `ImageCapture`，拿不到"按下快门那一刻"的画面，只有取景时的连续统计量。
 * 因此这里的分数是**实时参考值**（1 Hz 刷新），不是"专业评测"。
 * 文案口径必须守住：只用「参考分」，不出现"正确/错误""专业评测"。
 *
 * ## 四个维度与权重
 * | 维度 | 满分 | 数据源 |
 * |---|---:|---|
 * | 水平 | 25 | 结构张量（优先）→ 重力传感器 → 都没有则记满分并标未测 |
 * | 曝光 | 30 | FrameStats 的裁切占比 + 平均亮度 |
 * | 构图 | 25 | `CompositionHint.scoreNoTilt × 0.25`（**先剔除倾斜分**，否则倾斜被扣两次） |
 * | 清晰（估） | 20 | `SharpnessMeter` 的中心区拉普拉斯方差 |
 *
 * 本文件只 import kotlin.math 与纯 JVM 类，可直接跑单测。
 */
object ScoreBoard {

    const val MAX_LEVEL = 25
    const val MAX_EXPOSURE = 30
    const val MAX_COMPOSITION = 25
    const val MAX_CLARITY = 20

    // ── 口径换算常量（§6.3，标定期只改这里）────────────────────────────────────
    //
    // PRD（Web 版）口径：clipHigh = 亮度 > 0.95 的占比，clipLow = 亮度 < 0.05 的占比。
    // shottuner 口径：highlightClip = y >= 250（0.98+），shadowClip = y <= 8（0.031-）。
    // 后者的窗口更窄 → 同一画面在 shottuner 里的 clip 值系统性偏小 → 直接套 PRD 阈值会
    // 导致"过曝永远报不出来"。按下表换算：
    //   过曝：0.20 × 0.6 ≈ 0.12   （窗口宽度比 0.02/0.05 = 0.4，取 0.6 留余量）
    //   欠曝：0.35 × 0.7 ≈ 0.245  （窗口宽度比 0.031/0.05 = 0.62，取 0.7）

    /** PRD 的过曝阈值（仅作换算基准，不直接参与判定） */
    const val CLIP_HIGH_PRD = 0.20f

    /** 换算后可直接用于 shottuner 数据源的过曝阈值 */
    const val CLIP_HIGH_CONVERTED = 0.12f

    /** PRD 的欠曝阈值（换算基准） */
    const val CLIP_LOW_PRD = 0.35f

    /** 换算后可直接用于 shottuner 数据源的欠曝阈值 */
    const val CLIP_LOW_CONVERTED = 0.245f

    // ── 曝光计分阈值 ───────────────────────────────────────────────────────────
    private const val EXPOSURE_CLIP_HIGH_STRICT = 0.02f
    private const val EXPOSURE_CLIP_LOW_STRICT = 0.06f
    private const val EXPOSURE_LUMA_MIN_STRICT = 0.32f
    private const val EXPOSURE_LUMA_MAX_STRICT = 0.68f

    private const val EXPOSURE_CLIP_HIGH_RELAXED = 0.08f
    private const val EXPOSURE_CLIP_LOW_RELAXED = 0.14f
    private const val EXPOSURE_LUMA_MIN_RELAXED = 0.14f
    private const val EXPOSURE_LUMA_MAX_RELAXED = 0.80f

    /** 严重溢出 / 严重欠曝的硬顶 */
    private const val EXPOSURE_HARD_CAP_CLIP = 0.30f
    private const val EXPOSURE_HARD_CAP_LUMA = 0.10f
    private const val EXPOSURE_HARD_CAP_SCORE = 10

    /** 每项不达标扣的分数 */
    private const val EXPOSURE_PENALTY = 10

    /** |θ| ≤ 1° 视为已平 */
    private const val LEVEL_TOLERANCE = 1f

    /** 1–5° 线性扣到 0；> 5° 记 0 */
    private const val LEVEL_FULL_PENALTY_DEG = 5f

    /** 重力传感器路径：只在 |roll| > 3° 时才认为"画面真的歪了" */
    private const val LEVEL_SENSOR_TRIGGER = 3f

    // ── 建议触发阈值（§6.6）────────────────────────────────────────────────────
    private const val REC_HIGHLIGHT = 0.10f
    private const val REC_LOW_LUMA = 0.28f
    private const val REC_CENTER_BIAS = -0.10f
    private const val REC_EDGE_MARGIN = 0.05f
    private const val REC_CLARITY_LOW = 0.5f

    /** 全满分时的唯一一句话。 */
    const val ALL_PERFECT_TEXT = "这张没什么可挑的，去拍下一张"

    // ── 技法 id（建议要能追溯回知识库）────────────────────────────────────────
    private const val TIP_HORIZON = "landscape_horizon_level"
    private const val TIP_HIGHLIGHT = "basic_protect_highlights"
    private const val TIP_EXPOSURE = "light_white_add_black_minus"
    private const val TIP_BACKLIT = "portrait_backlit_face_first"
    private const val TIP_EYES_THIRDS = "portrait_eyes_thirds"
    private const val TIP_HEADROOM = "portrait_headroom"
    private const val TIP_STEADY = "move_elbow_tucked"

    /** 水平度：双数据源。 */
    fun level(roll: Float?, hint: CompositionHint): DimensionScore {
        val theta: Float
        when {
            hint.horizonDetected -> {
                // 结构张量测的是"画面里的地平线真歪没歪"，最准
                theta = hint.tiltDeg
            }

            roll != null -> {
                // 没有地平线时，手机歪不等于画面歪：只有超过 3° 才扣分，避免误判
                theta = if (abs(roll) > LEVEL_SENSOR_TRIGGER) roll else 0f
            }

            else -> {
                // 两个源都没有 → 记满分 + 未测。不因缺传感器惩罚用户
                return DimensionScore(ScoreDimension.LEVEL, "水平", MAX_LEVEL, MAX_LEVEL, false)
            }
        }
        return DimensionScore(
            dimension = ScoreDimension.LEVEL,
            label = "水平",
            score = levelScoreFromDegrees(abs(theta)),
            max = MAX_LEVEL,
            measured = true
        )
    }

    /** |θ| ≤ 1 → 25；1 < |θ| ≤ 5 → 线性扣到 0；> 5 → 0。 */
    fun levelScoreFromDegrees(absDeg: Float): Int = when {
        !absDeg.isFinite() -> MAX_LEVEL
        absDeg <= LEVEL_TOLERANCE -> MAX_LEVEL
        absDeg <= LEVEL_FULL_PENALTY_DEG ->
            (MAX_LEVEL * (1f - (absDeg - LEVEL_TOLERANCE) / 4f)).roundToInt()

        else -> 0
    }

    /** 是否走宽松曝光档。 */
    fun isRelaxedExposure(scene: SceneType): Boolean =
        scene == SceneType.NIGHT || scene == SceneType.BACKLIT

    /** 曝光 30：基线 30，三项各扣 10，严重溢出/欠曝落 10 分以内。 */
    fun exposure(stats: FrameStats, scene: SceneType): DimensionScore {
        val relaxed = isRelaxedExposure(scene)
        val clipHighLimit = if (relaxed) EXPOSURE_CLIP_HIGH_RELAXED else EXPOSURE_CLIP_HIGH_STRICT
        val clipLowLimit = if (relaxed) EXPOSURE_CLIP_LOW_RELAXED else EXPOSURE_CLIP_LOW_STRICT
        val lumaMin = if (relaxed) EXPOSURE_LUMA_MIN_RELAXED else EXPOSURE_LUMA_MIN_STRICT
        val lumaMax = if (relaxed) EXPOSURE_LUMA_MAX_RELAXED else EXPOSURE_LUMA_MAX_STRICT

        var score = MAX_EXPOSURE
        if (stats.highlightClip >= clipHighLimit) score -= EXPOSURE_PENALTY
        if (stats.shadowClip >= clipLowLimit) score -= EXPOSURE_PENALTY
        if (stats.meanLuma < lumaMin || stats.meanLuma > lumaMax) score -= EXPOSURE_PENALTY
        if (stats.highlightClip > EXPOSURE_HARD_CAP_CLIP || stats.meanLuma < EXPOSURE_HARD_CAP_LUMA) {
            score = min(score, EXPOSURE_HARD_CAP_SCORE)
        }
        return DimensionScore(
            dimension = ScoreDimension.EXPOSURE,
            label = "曝光",
            score = score.coerceIn(0, MAX_EXPOSURE),
            max = MAX_EXPOSURE,
            measured = true
        )
    }

    /** 构图 25：直接 ×0.25，不重新标定（保住既有构图回归基线）。 */
    fun composition(hint: CompositionHint): DimensionScore = DimensionScore(
        dimension = ScoreDimension.COMPOSITION,
        label = "构图",
        score = (hint.scoreNoTilt * 0.25f).roundToInt().coerceIn(0, MAX_COMPOSITION),
        max = MAX_COMPOSITION,
        measured = true
    )

    /**
     * 清晰度 20。
     * `focusVar == 0`（本帧没算）时标「未测」，不显示 0 —— 0 分会被误读成"你糊了"。
     * 文案用「清晰（估）」，因为这是相对指标，绝对值不可跨设备比较。
     */
    fun clarity(stats: FrameStats): DimensionScore {
        if (stats.focusVar <= 0f) {
            return DimensionScore(ScoreDimension.CLARITY, "清晰（估）", 0, MAX_CLARITY, false)
        }
        val norm = SharpnessMeter.normalize(stats.focusVar, stats.meanLuma)
        return DimensionScore(
            dimension = ScoreDimension.CLARITY,
            label = "清晰（估）",
            score = (norm * MAX_CLARITY).roundToInt().coerceIn(0, MAX_CLARITY),
            max = MAX_CLARITY,
            measured = true
        )
    }

    /** 总分 → 等级。 */
    fun gradeOf(total: Int): String = when {
        total >= 85 -> "优秀"
        total >= 70 -> "良好"
        total >= 60 -> "及格"
        else -> "待改进"
    }

    /**
     * 完整复盘。
     *
     * @param mirrored 预览是否镜像（只有建议文案用得到，默认 false）
     */
    fun score(
        stats: FrameStats,
        hint: CompositionHint,
        roll: Float?,
        scene: SceneType,
        mirrored: Boolean = false
    ): ScoreResult {
        val dimensions = listOf(
            level(roll, hint),
            exposure(stats, scene),
            composition(hint),
            clarity(stats)
        )
        val total = dimensions.sumOf { it.score }
        return ScoreResult(
            total = total,
            grade = gradeOf(total),
            dimensions = dimensions,
            recommendations = buildRecommendations(stats, hint, roll, dimensions, mirrored)
        )
    }

    /** 建议：按扣分比例降序，每维度最多 1 条，总 ≤3；全满分只给一句话。 */
    fun buildRecommendations(
        stats: FrameStats,
        hint: CompositionHint,
        roll: Float?,
        dimensions: List<DimensionScore>,
        mirrored: Boolean = false
    ): List<Recommendation> {
        // 「未测」的维度不出建议：没有数据就不该指挥用户
        val weak = dimensions
            .filter { it.measured && it.score < it.max }
            .sortedWith(
                compareByDescending<DimensionScore> { 1f - it.score.toFloat() / it.max.toFloat() }
                    .thenBy { it.dimension.ordinal }
            )
            .take(3)

        if (weak.isEmpty()) {
            return listOf(
                Recommendation(tipId = "", text = ALL_PERFECT_TEXT, dimension = ScoreDimension.CLARITY)
            )
        }
        return weak.mapNotNull { dimension -> recommendFor(dimension, stats, hint, roll, mirrored) }
    }

    /**
     * 三分交点的方位词。
     *
     * 抽成 public 是因为任务卡（G1 `TaskPicker`）要用同一份象限判断：
     * 两处各写一份将来必然走样（v1.7 的 `tvFraming` 踩过这个坑，见 MainActivity.kt:1557-1565）。
     */
    fun whereLabel(targetX: Float, targetY: Float): String = when {
        targetX < 0.5f && targetY < 0.5f -> "左上"
        targetX < 0.5f -> "左下"
        targetY < 0.5f -> "右上"
        else -> "右下"
    }

    private fun recommendFor(
        dimension: DimensionScore,
        stats: FrameStats,
        hint: CompositionHint,
        roll: Float?,
        mirrored: Boolean
    ): Recommendation? = when (dimension.dimension) {
        ScoreDimension.LEVEL -> {
            val theta = if (hint.horizonDetected) hint.tiltDeg else (roll ?: 0f)
            if (abs(theta) <= LEVEL_TOLERANCE) null
            else {
                val text = LevelMath.rollHint(theta, mirrored).text
                Recommendation(TIP_HORIZON, text, ScoreDimension.LEVEL)
            }
        }

        ScoreDimension.EXPOSURE -> when {
            stats.highlightClip > REC_HIGHLIGHT ->
                Recommendation(TIP_HIGHLIGHT, "亮部糊了，曝光补偿降 0.3", ScoreDimension.EXPOSURE)

            stats.meanLuma < REC_LOW_LUMA ->
                Recommendation(TIP_EXPOSURE, "画面偏暗，慢一档快门或 ISO 加一档", ScoreDimension.EXPOSURE)

            stats.centerBias < REC_CENTER_BIAS ->
                Recommendation(TIP_BACKLIT, "对着脸点一下测光再拍", ScoreDimension.EXPOSURE)

            else ->
                Recommendation(TIP_HIGHLIGHT, "亮部或暗部快到头了，EV 微调 0.3 试试", ScoreDimension.EXPOSURE)
        }

        ScoreDimension.COMPOSITION -> {
            val nearEdge = hint.subjectX < REC_EDGE_MARGIN || hint.subjectX > 1f - REC_EDGE_MARGIN ||
                hint.subjectY < REC_EDGE_MARGIN || hint.subjectY > 1f - REC_EDGE_MARGIN
            when {
                hint.suggestMove -> {
                    val where = whereLabel(hint.targetX, hint.targetY)
                    Recommendation(TIP_EYES_THIRDS, "主体往$where 交点靠一点", ScoreDimension.COMPOSITION)
                }

                nearEdge ->
                    Recommendation(TIP_HEADROOM, "退半步，别让主体贴边", ScoreDimension.COMPOSITION)

                else ->
                    Recommendation(TIP_EYES_THIRDS, "把主体挪离正中，留点呼吸空间", ScoreDimension.COMPOSITION)
            }
        }

        ScoreDimension.CLARITY -> {
            val norm = if (stats.focusVar > 0f) {
                SharpnessMeter.normalize(stats.focusVar, stats.meanLuma)
            } else {
                1f
            }
            if (dimension.measured && norm < REC_CLARITY_LOW) {
                Recommendation(TIP_STEADY, "手肘夹住身体，按快门时屏住呼吸", ScoreDimension.CLARITY)
            } else {
                Recommendation(TIP_STEADY, "端稳一点，画质还能再实一档", ScoreDimension.CLARITY)
            }
        }
    }
}
