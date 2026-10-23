package com.shottuner.app.growth

import kotlin.math.abs

/**
 * 任务状态机（规格 §6）。纯 JVM：import 里没有 `android.*`。
 *
 * ## 唯一判定入口
 * `onSample()` 由 MainActivity 在 `renderComposition()` 末尾每 400ms 调一次（§3.5）。
 * 不另开 Handler、不在 10Hz 的 `onPitchRoll()` 里判定 —— 否则 hits 计数与渲染错位，
 * 会出现"环还没画出来就已经 ACHIEVED"。
 *
 * ## 三重防抖（§3.0）
 * 1. **EMA 平滑**：判定用 `smoothed`，不用单帧值。`smoothed` 在派发那一刻用当前真实值初始化，
 *    因此"第 1 帧直接跳到达标点"不会立刻进圈 —— `subjectX/Y` 是自适应阈值的显著度质心，会跳，
 *    "进圈又出圈"比没这功能更糟。
 * 2. **连续 N 次达标**：`hits` 只在达标帧 +1，任一帧不达标就归 0（不是减 1）。
 * 3. **单向锁定**：`ACHIEVED` 之后不再判定，直到走完 `SCORED` 回 `IDLE`。
 *
 * ## 时间基准
 * 全部用 `MissionSample.now`（`SystemClock.elapsedRealtime()`，单调）。
 * minSdk 24 没开 desugaring，不能用 `java.time`。
 */
class MissionEngine {

    private var stateValue: MissionState = MissionState.IDLE
    private var taskValue: MissionTask? = null

    private var shownAtMs: Long = 0L
    private var achievedAtMs: Long = 0L
    private var scoredAtMs: Long = 0L

    private var lastNowMs: Long = 0L
    private var nextPickAtMs: Long = 0L
    private var quietUntilMs: Long = 0L

    private var hits: Int = 0
    private var smoothed: Float = 0f
    private var smoothedReady: Boolean = false
    private var consecutiveTimeouts: Int = 0
    private var consecutiveMisses: Int = 0

    private val cooldowns: HashMap<String, Long> = HashMap()

    val state: MissionState get() = stateValue

    val current: MissionTask? get() = taskValue

    /** 最近一次中断原因，只用于诊断。 */
    var lastAbortReason: AbortReason? = null
        private set

    /**
     * 唯一判定入口。返回本次 tick 产生的事件，由 MainActivity 刷 UI。
     *
     * 事件只是提示，**UI 的真相读 [state]**。
     */
    fun onSample(s: MissionSample): MissionEvent {
        lastNowMs = s.now
        return when (stateValue) {
            MissionState.IDLE -> tickIdle(s)
            MissionState.TASK_SHOWN -> tickShown(s)
            MissionState.IN_PROGRESS -> tickProgress(s)
            MissionState.ACHIEVED -> tickAchieved(s)
            MissionState.SCORED -> tickScored(s)
        }
    }

    /**
     * 中断并回到 IDLE。幂等。
     *
     * 中断后加 3s 的重新派发节流：不希望"关掉网格 → 立刻又派一条"。
     * 想允许立刻派发改调 [resume()]。
     */
    fun abort(reason: AbortReason) {
        lastAbortReason = reason
        taskValue = null
        resetJudge()
        stateValue = MissionState.IDLE
        nextPickAtMs = lastNowMs + MissionThresholds.RETRY_IDLE_MS
    }

    /** 从 IDLE 恢复（切回照片页 / 重开网格 / onResume 之后）：允许下一个 tick 立刻 pick。 */
    fun resume() {
        if (stateValue == MissionState.IDLE) {
            nextPickAtMs = lastNowMs
        }
    }

    /** 是否占用"提示表面"（任务卡或成绩卡可见）。 */
    fun occupiesSurface(): Boolean = stateValue != MissionState.IDLE

    /** 当前环的绿意进度 0..1，交给 GuideOverlayView。没有任务时返回 0。 */
    fun ringProgress(): Float {
        val task = taskValue ?: return 0f
        return TaskPicker.progressOf(stateValue, task.kind, smoothed)
    }

    // ── 各状态的 tick ──────────────────────────────────────────────────────────

    private fun tickIdle(s: MissionSample): MissionEvent {
        if (s.now < quietUntilMs) return MissionEvent.NONE          // 静默期：场景不适合，别白耗
        if (s.now < nextPickAtMs) return MissionEvent.NONE          // 3s 重试节流（§2.2）
        nextPickAtMs = s.now + MissionThresholds.RETRY_IDLE_MS

        val picked = TaskPicker.pick(s.recs, s, cooldowns)
        if (picked == null) {
            // 只有"场景本身挑不出任务"才累计 miss。因为冷却挑不出不算 —— 冷却会自己过期，
            // 把它算成 miss 会让"刚超时一次"直接进 60s 静默，任务功能等于自废。
            if (TaskPicker.pick(s.recs, s, emptyMap()) == null) {
                consecutiveMisses++
                if (consecutiveMisses >= 2) {
                    consecutiveMisses = 0
                    quietUntilMs = s.now + MissionThresholds.QUIET_AFTER_TWO_MISS_MS
                }
            }
            return MissionEvent.NONE
        }
        consecutiveMisses = 0
        enterShown(picked, s)
        return MissionEvent.SHOW
    }

    /** TASK_SHOWN：800ms 缓冲。EMA 照常跟上，但**不计 hits**（§6.2）。 */
    private fun tickShown(s: MissionSample): MissionEvent {
        val task = taskValue ?: return abortAndIdle(s)
        if (s.now - shownAtMs < MissionThresholds.TASK_SHOWN_MS) {
            updateSmoothing(task, s)
            return MissionEvent.NONE
        }
        stateValue = MissionState.IN_PROGRESS
        return tickProgress(s)
    }

    private fun tickProgress(s: MissionSample): MissionEvent {
        val task = taskValue ?: return abortAndIdle(s)
        if (s.now - shownAtMs > MissionThresholds.TASK_TIMEOUT_MS) return onTimeout(s, task)

        val raw = rawOf(task, s)
        if (raw == null) {                       // 判定源没了（例如地平线消失且没重力）
            hits = 0
            return MissionEvent.PROGRESS
        }
        updateSmoothing(task, s)
        if (isHit(task)) hits++ else hits = 0    // 任一帧不达标 → 归零（§3.0 b）

        if (hits >= MissionThresholds.REQUIRED_HITS) {
            stateValue = MissionState.ACHIEVED
            achievedAtMs = s.now
            consecutiveTimeouts = 0
            return MissionEvent.ACHIEVED
        }
        return MissionEvent.PROGRESS
    }

    /** ACHIEVED：单向锁定，1.2s 后进 SCORED。期间不再判定（§3.0 c）。 */
    private fun tickAchieved(s: MissionSample): MissionEvent {
        if (s.now - achievedAtMs >= MissionThresholds.ACHIEVE_TO_SCORE_MS) {
            stateValue = MissionState.SCORED
            scoredAtMs = s.now
            return MissionEvent.HIDE             // 任务卡收起，成绩卡由 state=SCORED 驱动
        }
        return MissionEvent.NONE
    }

    /** SCORED：8s 自动收起 → IDLE，3s 后才允许再派发（§4.3 / §6.2）。 */
    private fun tickScored(s: MissionSample): MissionEvent {
        if (s.now - scoredAtMs >= MissionThresholds.SCORE_CARD_MS) {
            val done = taskValue
            if (done != null) {
                cooldowns[done.tipId] = s.now + MissionThresholds.POST_ACHIEVE_QUIET_MS
            }
            toIdle(s.now + MissionThresholds.POST_ACHIEVE_QUIET_MS)
            return MissionEvent.HIDE
        }
        return MissionEvent.NONE
    }

    /** 20s 未达标：原 tipId 进 12s 冷却，立刻尝试换一条（§2.4）。 */
    private fun onTimeout(s: MissionSample, timedOut: MissionTask): MissionEvent {
        cooldowns[timedOut.tipId] = s.now + MissionThresholds.TIMEOUT_COOLDOWN_MS
        consecutiveTimeouts++
        if (consecutiveTimeouts >= 2) {
            consecutiveTimeouts = 0
            quietUntilMs = s.now + MissionThresholds.QUIET_AFTER_TWO_MISS_MS
            toIdle(s.now + MissionThresholds.RETRY_IDLE_MS)
            return MissionEvent.TIMEOUT
        }
        val next = TaskPicker.pick(s.recs, s, cooldowns)
        if (next != null) {
            enterShown(next, s)                  // 同一张卡换句话，不做位移（§2.4）
            return MissionEvent.TIMEOUT
        }
        toIdle(s.now)
        return MissionEvent.TIMEOUT
    }

    // ── 状态迁移 ───────────────────────────────────────────────────────────────

    private fun enterShown(picked: MissionTask, s: MissionSample) {
        taskValue = picked
        stateValue = MissionState.TASK_SHOWN
        shownAtMs = s.now
        hits = 0
        smoothed = 0f
        smoothedReady = false
        // 用派发那一刻的真实值初始化 EMA：否则 smoothed 从 0 起算，第一帧就会误判达标。
        updateSmoothing(picked, s)
    }

    private fun toIdle(nextPickAt: Long) {
        taskValue = null
        resetJudge()
        stateValue = MissionState.IDLE
        nextPickAtMs = nextPickAt
    }

    private fun resetJudge() {
        hits = 0
        smoothed = 0f
        smoothedReady = false
    }

    /** 防御分支：状态机持有任务但任务没了（不应该发生）。 */
    private fun abortAndIdle(s: MissionSample): MissionEvent {
        toIdle(s.now)
        return MissionEvent.HIDE
    }

    // ── 判定 ───────────────────────────────────────────────────────────────────

    /** 本帧的原始量。null = 判定源缺失。 */
    private fun rawOf(task: MissionTask, s: MissionSample): Float? = when (task.kind) {
        TaskKind.COMPOSITION -> TaskPicker.distToTarget(s.hint, s.aspect)
        TaskKind.LEVEL -> TaskPicker.tiltDegrees(s)?.let { abs(it) }
        TaskKind.HEADROOM -> s.hint.subjectTop
    }

    /** EMA：判定用平滑值，不用单帧值（§3.0 a）。 */
    private fun updateSmoothing(task: MissionTask, s: MissionSample) {
        val raw = rawOf(task, s) ?: return
        // 水平任务走更快的 alpha：见 MissionThresholds.LEVEL_SMOOTH_ALPHA 的注释。
        // 收敛慢会让"已经端平了却不绿"，那是比闪绿更常见的失败模式。
        val alpha = if (task.kind == TaskKind.LEVEL) {
            MissionThresholds.LEVEL_SMOOTH_ALPHA
        } else {
            MissionThresholds.SMOOTH_ALPHA
        }
        smoothed = if (!smoothedReady) {
            raw
        } else {
            smoothed + alpha * (raw - smoothed)
        }
        smoothedReady = true
    }

    private fun isHit(task: MissionTask): Boolean = when (task.kind) {
        TaskKind.COMPOSITION -> smoothed <= MissionThresholds.RING_RADIUS
        TaskKind.LEVEL -> smoothed <= MissionThresholds.LEVEL_ENTER_DEG
        TaskKind.HEADROOM -> smoothed >= MissionThresholds.HEADROOM_ENTER_TOP
    }
}
