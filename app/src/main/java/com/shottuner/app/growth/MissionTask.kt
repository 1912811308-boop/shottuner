package com.shottuner.app.growth

import com.shottuner.app.analysis.Recommendation
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats

/**
 * 任务类型。
 *
 * 只有三类能当任务（规格 §1.5）：用户做一个物理动作（转 / 挪 / 压），
 * 画面里有一个 400ms 内能测到的连续量会跟着变，且变到达标线时
 * `ScoreBoard` 的对应维度真的涨分。曝光类与「手肘夹紧」类都不满足，不做。
 */
enum class TaskKind { COMPOSITION, LEVEL, HEADROOM }

/** 任务状态机（规格 §6.1）。 */
enum class MissionState { IDLE, TASK_SHOWN, IN_PROGRESS, ACHIEVED, SCORED }

/**
 * `MissionEngine.onSample()` 的返回事件，交给 MainActivity 刷 UI。
 *
 * 事件只是"要不要重绘"的提示，**状态的真相永远读 `MissionEngine.state`**：
 * 同一个事件（例如 `HIDE`）可能对应不同的下一状态，UI 一律以 state 为准。
 *
 * - `SHOW`：派发了新任务（或超时后换了一条），任务卡应出现 / 换文案
 * - `PROGRESS`：`IN_PROGRESS` 期间每个 tick，环的绿意可能变了
 * - `ACHIEVED`：到位（环变绿 + 震动 + 文案变「✓ 到位」）
 * - `TIMEOUT`：本条超时；若已换到新条，`current` 是新的，否则 `current == null`
 * - `HIDE`：任务卡或成绩卡收起（任务卡 → 成绩卡、成绩卡 → 全收起）
 */
enum class MissionEvent { NONE, SHOW, PROGRESS, ACHIEVED, TIMEOUT, HIDE }

/** 中断原因。只用于诊断；状态机对它们一视同仁（一律回 IDLE）。 */
enum class AbortReason { CAPTURE, TAB, PAUSE, CAMERA_OFF, GUIDE_OFF, USER }

/**
 * 一条任务卡。
 *
 * 文案是「动作口吻」，不是 `ScoreBoard` 的「诊断口吻」（规格 §2.3）。
 */
class MissionTask(
    /** 关联技法 id，来自 `Recommendation.tipId`，可追溯回技法库 */
    val tipId: String,
    val kind: TaskKind,
    /** 完成后会涨分的那个评分维度 */
    val dimension: ScoreDimension,
    /** 任务卡主句，≤10 字 */
    val title: String,
    /** 任务卡副句，≤16 字 */
    val hint: String,
    /** 成绩卡上的技法短名 */
    val skillName: String
)

/**
 * 判定用的输入快照。MainActivity 在 `renderComposition()` 末尾每 400ms 组装一次。
 *
 * 全部是纯 JVM 类型：不含任何 `android.*`，可以直接在单测里喂假数据。
 */
class MissionSample(
    /** `SystemClock.elapsedRealtime()`，单调时钟。minSdk 24 不能用 `java.time` */
    val now: Long,
    val stats: FrameStats,
    val hint: CompositionHint,
    /** 重力 roll（度）。没有地平线时作为水平判定的兜底源 */
    val roll: Float?,
    /** `ScoreBoard.buildRecommendations()` 的输出，已按扣分比例降序 */
    val recs: List<Recommendation>,
    /** 取景器叠加层的高宽比 h/w，兜底 16:9 */
    val aspect: Float
)

/**
 * 阈值常量集中在这里，改口径只改这一个文件。
 *
 * 全部是标定值，真机上大概率要微调（尤其是 `RING_RADIUS` 与 `LEVEL_ENTER_DEG`）。
 * 改这里不会影响 `ScoreBoard` 的评分口径 —— 两套阈值刻意分开：
 * 评分是"这张照片好不好"，任务阈值是"用户能不能在 15 秒内做到"。
 */
object MissionThresholds {
    // 构图
    const val RING_RADIUS = 0.075f
    const val FADE_START = 0.16f
    const val TRIGGER_D = 0.16f
    const val GIVEUP_D = 0.55f
    // 水平
    const val LEVEL_ENTER_DEG = 2.5f
    const val LEVEL_TRIGGER_DEG = 4.0f
    const val LEVEL_GIVEUP_DEG = 12f
    // headroom
    const val HEADROOM_TRIGGER_TOP = 0.08f
    const val HEADROOM_ENTER_TOP = 0.12f
    const val HEADROOM_GIVEUP_TOP = 0.02f
    const val HEADROOM_MIN_SKIN = 0.06f
    // 通用
    const val SMOOTH_ALPHA = 0.35f

    /**
     * 水平任务单独的 EMA 系数。**只用于 `TaskKind.LEVEL`**，其余两类仍用 [SMOOTH_ALPHA]。
     *
     * 理由（T1 实测）：`levelRoll` 是 10Hz 输入、400ms 采样，判定量本身比构图距离稳定得多，
     * 用 0.35 的时间常数会让 EMA 预热白白吃掉约 4.0 秒 —— 占掉 20 秒超时的 20%，
     * 用户的主观感受是"我明明端平了，怎么还不绿"。0.5 之后约 2.8 秒，与构图任务的
     * 体感齐平，同时仍保留 ≥ REQUIRED_HITS 帧的连续达标要求（不会因为改快而闪绿）。
     *
     * **真机校准项**：如果实测仍有"端平了不动于衷"的反馈，改这一个常量即可，
     * 不要动 `LEVEL_ENTER_DEG`（那是 deficient 与 reachable 的分界，改了会让
     * 任务达成时画面确实还是歪的）。
     */
    const val LEVEL_SMOOTH_ALPHA = 0.5f

    const val REQUIRED_HITS = 3
    const val MIN_SUBJECT_CONFIDENCE = 0.30f
    // 时序
    const val TASK_SHOWN_MS = 800L
    const val TASK_TIMEOUT_MS = 20_000L
    const val ACHIEVE_TO_SCORE_MS = 1_200L
    const val SCORE_CARD_MS = 8_000L
    const val POST_ACHIEVE_QUIET_MS = 3_000L
    const val RETRY_IDLE_MS = 3_000L
    const val TIMEOUT_COOLDOWN_MS = 12_000L
    const val QUIET_AFTER_TWO_MISS_MS = 60_000L
}
