package com.shottuner.app.growth

import com.shottuner.app.analysis.Recommendation
import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.model.CompositionHint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 任务选择器：把 `ScoreBoard.buildRecommendations()` 的输出过滤成"用户在 15 秒内做得到"的任务。
 *
 * ## 为什么不放在 `ScoreBoard` 里
 * `ScoreBoard` 是纯评分口径，它不知道"用户能不能做到"。把可完成性判断塞进评分会污染评分语义，
 * 也会让 `ScoreBoardTest` 变成交互测试（规格 §2.1）。
 *
 * ## 三层过滤
 * ```
 * recs（已按扣分比例降序）
 *   ↓ ① 白名单（§1.5 的 ✅ 三条）
 *   ↓ ② 可行性窗口（当前状态落在 [TRIGGER, GIVEUP] 内，§3）
 *   ↓ ③ 冷却（该 tipId 在冷却期内 → 跳过）
 *   ↓ 取第一条；一条都不剩 → null（任务卡 GONE，回落 tvAdvice）
 * ```
 *
 * 纯 JVM：import 里没有 `android.*`。
 */
object TaskPicker {

    /** 白名单：只有这三个 tipId 能变成任务卡。见规格 §1.5。 */
    private val ELIGIBLE = mapOf(
        "landscape_horizon_level" to TaskKind.LEVEL,
        "portrait_eyes_thirds" to TaskKind.COMPOSITION,
        "portrait_headroom" to TaskKind.HEADROOM
    )

    /** tipId → 任务卡文案（主句 ≤10 字 / 副句 ≤16 字 / 技法短名）。见规格 §2.3、§4.3。 */
    private val TITLES = mapOf(
        "landscape_horizon_level" to "把手机端平",
        "portrait_eyes_thirds" to "把主体挪到{where}交点",
        "portrait_headroom" to "头顶多留一点空间"
    )
    private val HINTS = mapOf(
        "landscape_horizon_level" to "转一下，橙线压到绿线上",
        "portrait_eyes_thirds" to "让白点进圈，圈变绿就到位",
        "portrait_headroom" to "镜头往下压一指，别顶到画框"
    )
    private val SKILL_NAMES = mapOf(
        "landscape_horizon_level" to "地平线端平",
        "portrait_eyes_thirds" to "三分法",
        "portrait_headroom" to "头顶留白"
    )

    /**
     * 按 recs 原顺序（已按扣分比例降序）挑第一条"可完成"的。
     *
     * @param cooldowns tipId → 冷却解除时刻（elapsedRealtime）；`value > now` 表示仍在冷却
     * @return null 表示这次没有可派发的任务（不要强行派发，也不要提示用户）
     */
    fun pick(
        recs: List<Recommendation>,
        s: MissionSample,
        cooldowns: Map<String, Long>
    ): MissionTask? {
        for (rec in recs) {
            val kind = ELIGIBLE[rec.tipId] ?: continue                     // ① 白名单
            val cooldownUntil = cooldowns[rec.tipId] ?: 0L
            if (cooldownUntil > s.now) continue                            // ③ 冷却
            val feasible = when (kind) {                                   // ② 可行性窗口
                TaskKind.COMPOSITION -> compositionFeasible(s)
                TaskKind.LEVEL -> levelFeasible(s)
                TaskKind.HEADROOM -> headroomFeasible(s)
            }
            if (!feasible) continue
            return taskOf(rec.tipId, kind, s.hint)
        }
        return null
    }

    /**
     * 与 `ScoreBoard.recommendFor` 共用的方位词。
     *
     * 委托给 `ScoreBoard.whereLabel()`，两端共用一份实现 —— 两处各写一份将来必然走样
     * （v1.7 的 `tvFraming` 踩过这个坑）。
     */
    fun whereLabel(targetX: Float, targetY: Float): String =
        ScoreBoard.whereLabel(targetX, targetY)

    /**
     * 构图任务的距离：**画面短边为单位**的像素距离。
     *
     * 不用 `CompositionAnalyzer` 内部那个未做长宽比校正的归一化 hypot —— 竖屏下它会把
     * 纵向系统性低估约 1.78 倍。这里用短边为单位，`d` 与"环在屏幕上的半径 / 屏幕宽"
     * 是同一个单位，**判定线与视觉环严格一致**（规格 §3.1）。
     *
     * @param aspect 画面高宽比 h/w（竖屏 ≥ 1）
     */
    fun distToTarget(hint: CompositionHint, aspect: Float): Float {
        val dx = hint.subjectX - hint.targetX
        val dy = (hint.subjectY - hint.targetY) * aspect
        return hypot(dx, dy)
    }

    /**
     * 水平任务的判定角（度）。与 `ScoreBoard.level()` 同口径但**不做 3° 抑制**：
     * 任务卡要的是"用户转手机时数字跟着动"。
     *
     * @return null 表示两个数据源都没有 → 不派发水平任务
     */
    fun tiltDegrees(s: MissionSample): Float? = when {
        s.hint.horizonDetected -> s.hint.tiltDeg        // 结构张量，最准
        s.roll != null -> s.roll                        // 重力兜底
        else -> null
    }

    /**
     * 当前状态相对达标线的进度 0..1，驱动环的绿意。
     *
     * - `IDLE` → 0（没有环）
     * - `ACHIEVED` / `SCORED` → 1（环已绿，不再回退）
     * - 其余按 `smoothed` 线性映射（构图是"越近越绿"，headroom 是"越下压越绿"）
     */
    fun progressOf(state: MissionState, kind: TaskKind, smoothed: Float): Float = when (state) {
        MissionState.IDLE -> 0f
        MissionState.ACHIEVED, MissionState.SCORED -> 1f
        MissionState.TASK_SHOWN, MissionState.IN_PROGRESS -> when (kind) {
            TaskKind.COMPOSITION ->
                ((MissionThresholds.FADE_START - smoothed) /
                    (MissionThresholds.FADE_START - MissionThresholds.RING_RADIUS)).coerceIn(0f, 1f)

            TaskKind.LEVEL ->
                ((MissionThresholds.LEVEL_TRIGGER_DEG - smoothed) /
                    (MissionThresholds.LEVEL_TRIGGER_DEG - MissionThresholds.LEVEL_ENTER_DEG))
                    .coerceIn(0f, 1f)

            TaskKind.HEADROOM ->
                ((smoothed - MissionThresholds.HEADROOM_TRIGGER_TOP) /
                    (MissionThresholds.HEADROOM_ENTER_TOP - MissionThresholds.HEADROOM_TRIGGER_TOP))
                    .coerceIn(0f, 1f)
        }
    }

    // ── 可行性窗口（§3.1 / §3.2 / §3.3）────────────────────────────────────────

    /** 构图：置信度够 + 距离落在 [TRIGGER, GIVEUP]。 */
    private fun compositionFeasible(s: MissionSample): Boolean {
        if (s.hint.subjectConfidence <= MissionThresholds.MIN_SUBJECT_CONFIDENCE) return false
        val d = distToTarget(s.hint, s.aspect)
        if (!d.isFinite()) return false
        return d >= MissionThresholds.TRIGGER_D && d <= MissionThresholds.GIVEUP_D
    }

    /** 水平：有判定源 + |θ| 落在 [TRIGGER, GIVEUP]。 */
    private fun levelFeasible(s: MissionSample): Boolean {
        val theta = tiltDegrees(s) ?: return false
        val absDeg = abs(theta)
        if (!absDeg.isFinite()) return false
        return absDeg >= MissionThresholds.LEVEL_TRIGGER_DEG &&
            absDeg <= MissionThresholds.LEVEL_GIVEUP_DEG
    }

    /**
     * headroom：与 `CompositionAnalyzer.kt:209` 出这条 tip 的条件一致，
     * 且头顶还没完全出框（出框了就压不回来了）。
     */
    private fun headroomFeasible(s: MissionSample): Boolean {
        val top = s.hint.subjectTop
        if (!top.isFinite()) return false
        return top >= MissionThresholds.HEADROOM_GIVEUP_TOP &&
            top < MissionThresholds.HEADROOM_TRIGGER_TOP &&
            s.stats.skinRatio > MissionThresholds.HEADROOM_MIN_SKIN
    }

    // ── 任务卡构造 ─────────────────────────────────────────────────────────────

    private fun taskOf(tipId: String, kind: TaskKind, hint: CompositionHint): MissionTask {
        val title = (TITLES[tipId] ?: "")
            .replace("{where}", whereLabel(hint.targetX, hint.targetY))
        return MissionTask(
            tipId = tipId,
            kind = kind,
            dimension = when (kind) {
                TaskKind.LEVEL -> ScoreDimension.LEVEL
                TaskKind.COMPOSITION, TaskKind.HEADROOM -> ScoreDimension.COMPOSITION
            },
            title = title,
            hint = HINTS[tipId] ?: "",
            skillName = SKILL_NAMES[tipId] ?: ""
        )
    }
}
