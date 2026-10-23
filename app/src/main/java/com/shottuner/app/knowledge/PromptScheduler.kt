package com.shottuner.app.knowledge

import kotlin.math.abs

/** 一条正在展示的提示。 */
class ActivePrompt(
    val candidate: PromptCandidate,
    val shownAt: Long,
    val cycle: Int
) {
    val tipId: String get() = candidate.tipId
    override fun toString(): String = "ActivePrompt(${candidate.tipId}@$shownAt, cycle=$cycle)"
}

/**
 * 一次 tick 的结果。
 *
 * `changed == false` 时 UI **不要碰任何控件** —— 这是防闪烁的唯一手段：
 * 2Hz 重刷同一句话会让整条底栏都在抖。
 *
 * `changed` 的判定口径是「**界面上应该看到的 tipId 变了没有**」，而不是「调度器内部
 * `current` 变了没有」。这两者必须区分开：`setDensity` / `setMuted` 会把 `current`
 * 直接置空却不返还 `PromptDecision`，若只比内部状态，下一次 tick 会算出
 * `changed = false`，槽位就停在上一句不撤（QA P2-4）。
 */
class PromptDecision(
    val prompt: ActivePrompt?,
    val changed: Boolean,
    val feedback: PromptFeedback? = null,
    /** 当前有多少条合规候选（静音时 UI 用它显示小圆点） */
    val visibleCount: Int = 0
)

/** 正向反馈。 */
enum class PromptFeedback { LEVEL_OK, EXPOSURE_OK }

/** 调度器快照，供测试与调试。 */
class SchedulerSnapshot(
    val currentTipId: String?,
    val cooldowns: Map<String, Long>,
    val blacklist: Set<String>,
    val executed: Set<String>,
    val cycle: Int,
    val muted: Boolean,
    val density: PromptDensity,
    /** 上次节奏类（[PromptCandidate.paced]）提示的出牌时间，<0 = 还没出过。供节拍测试断言。 */
    val lastGuideAt: Long
)

/** 时间参数集中在一处，标定期只改这里。 */
object SchedulerConfig {
    /** 同一条技法 8 秒内不重复 */
    const val COOLDOWN_MS = 8_000L

    /** 单条提示的最短驻留，urgent 可打断 */
    const val MIN_DWELL_MS = 3_000L

    /** 运镜 / 教学轮播节拍 */
    const val GUIDE_INTERVAL_MS = 8_000L

    /** 空闲多久后开始兜底轮播 */
    const val IDLE_TRIGGER_MS = 6_000L
}

/**
 * 提示调度器（有状态机）。
 *
 * ## 铁律
 * **所有时间都必须由外部传进来的 `now` 决定，内部一次都不许调 `System.currentTimeMillis()`。**
 * 这是整条提示链路可单测的前提：只要时间可控，冷却、驻留、打断就都能断言到毫秒。
 *
 * ## 它解决什么问题
 * 引擎只会说「这些话都值得说」，调度器决定「现在轮到谁说、说多久、什么时候闭嘴」：
 * 单条可见、三级优先级仲裁、同条 8s 冷却、最短驻留 3s、urgent 可打断且反向不允许、
 * 已执行永久拉黑、用户长按屏蔽、密度过滤、holdMs 持续时长门槛、静音。
 *
 * ## 为什么候选要"吞掉"而不是排队
 * 画面已经变了，3 秒前那条候选描述的是旧画面。排队放出来 = 对着新画面说旧问题，
 * 比不说更糟。
 */
class PromptScheduler(
    /**
     * 技法库里真实存在的 id。生产环境传 `TechniqueBookV15.list.map { it.id }.toSet()`，
     * 阶段一列表为空时会让引擎静默不出牌；测试传 null 表示不过滤。
     */
    private val availableTipIds: Set<String>? = null
) {

    private var current: ActivePrompt? = null
    /** tipId → 允许再次展示的时间戳 */
    private val cooldowns = HashMap<String, Long>()
    /** 本会话永不展示（长按屏蔽 + 已执行） */
    private val blacklist = HashSet<String>()
    /** 已执行集合（供 UI 显示"已照做 ✓"，同时并入 blacklist） */
    private val executed = HashSet<String>()
    /** ruleId → 条件首次为真的时间（算 holdMs） */
    private val ruleFirstTrue = HashMap<String, Long>()

    private var lastGuideAt = -1L
    private var lastCandidateAt = -1L
    /**
     * 上一次**报告给 UI** 的可见 tipId（不是内部 `current`）。
     *
     * `changed` 一律由它比对得出，见 [reconcile]。`setDensity` / `setMuted` 这类
     * 「偷改内部状态、不返还 decision」的入口因此也能让 UI 及时撤卡。
     */
    private var lastReportedTipId: String? = null
    private var muted = false
    private var density = PromptDensity.STANDARD
    private var paused = false
    private var cycle = 0

    /** 主入口：引擎求值 + 状态机裁决。 */
    fun tick(signals: PromptSignals, now: Long): PromptDecision {
        val candidates = PromptEngine.evaluate(
            signals = signals,
            now = now,
            availableTipIds = availableTipIds,
            lastGuideAt = lastGuideAt,
            lastCandidateAt = lastCandidateAt
        )
        return tickWithCandidates(candidates, signals, now)
    }

    /**
     * 用现成候选推进状态机。
     *
     * 单独暴露出来是为了让调度器可以脱离引擎做纯状态测试 —— 否则测"8s 冷却"就得先构造
     * 一整套能触发某条规则的假信号，测试会长得没人愿意维护。
     */
    fun tickWithCandidates(
        candidates: List<PromptCandidate>,
        signals: PromptSignals,
        now: Long
    ): PromptDecision = reconcile(advance(candidates, signals, now))

    /**
     * `changed` 的唯一裁定处：只比「界面上应该看到的 tipId」有没有变。
     *
     * 抽出来是因为状态机里有 9 个 return 点，逐个手写 `changed` 必然漏。
     * 统一在这里把内部裁决的 `changed` **丢掉重算**，语义就只有一条。
     */
    private fun reconcile(raw: PromptDecision): PromptDecision {
        val tipId = raw.prompt?.tipId
        val changed = tipId != lastReportedTipId
        lastReportedTipId = tipId
        return PromptDecision(tipId?.let { raw.prompt }, changed, raw.feedback, raw.visibleCount)
    }

    /** 状态机本体：只决定「现在该显示什么」，不判 `changed`。 */
    private fun advance(
        candidates: List<PromptCandidate>,
        signals: PromptSignals,
        now: Long
    ): PromptDecision {
        if (paused) return PromptDecision(current, false, null, 0)

        // ---- 1. holdMs 计时：条件为真的规则累加，条件消失的清零（S9）----
        val presentRules = HashSet<String>(candidates.size)
        for (candidate in candidates) presentRules.add(candidate.ruleId)
        ruleFirstTrue.keys.retainAll(presentRules)
        for (candidate in candidates) {
            if (!ruleFirstTrue.containsKey(candidate.ruleId)) {
                ruleFirstTrue[candidate.ruleId] = now
            }
        }

        // ---- 2. 硬候选刷新空闲计时 ----
        // GUIDE 不刷新：否则兜底轮播会把自己饿死（它的存在本身会重置 6s 空闲门槛）
        if (candidates.any { it.priority != PromptPriority.GUIDE }) {
            lastCandidateAt = now
        }

        // ---- 3. autoResolve：异常已解除 → 立即撤下 + 永久拉黑 + 正向反馈（S4）----
        val cur = current
        if (cur != null && cur.candidate.autoResolve) {
            val key = cur.candidate.recoverKey
            if (key != null && isRecovered(key, signals)) {
                val tipId = cur.candidate.tipId
                markExecuted(tipId, now)
                current = null
                val feedback = if (tipId == PromptEngine.TIP_HORIZON) {
                    PromptFeedback.LEVEL_OK
                } else {
                    PromptFeedback.EXPOSURE_OK
                }
                return PromptDecision(null, true, feedback, 0)
            }
        }

        // ---- 4. 入库前过滤：密度 / 黑名单 / 已执行 / 冷却 / holdMs（S8 S9 S11）----
        val allowed = candidates.filter { candidate ->
            densityAllows(candidate.priority) &&
                !blacklist.contains(candidate.tipId) &&
                !executed.contains(candidate.tipId) &&
                cooldownOk(candidate.tipId, now) &&
                holdSatisfied(candidate, now)
        }

        if (allowed.isEmpty()) {
            return PromptDecision(current, false, null, 0)
        }

        val ranked = allowed.sortedWith(orderFor(now))

        // ---- 5. 静音：一句都不说，但把数量给 UI 显示小圆点（S7）----
        if (muted) {
            val changed = current != null
            current = null
            return PromptDecision(null, changed, null, ranked.size)
        }

        val best = ranked[0]
        val active = current

        // ---- 6. 当前无提示：直接展示（S1）----
        if (active == null) return show(best, now, ranked.size)

        // 还是同一句：什么都不用做
        if (active.candidate.tipId == best.tipId) {
            return PromptDecision(active, false, null, ranked.size)
        }

        // ---- 7. 反向不允许：低优先级不能打断高优先级（S2 的反向约束）----
        if (best.priority.rank > active.candidate.priority.rank) {
            return PromptDecision(active, false, null, ranked.size)
        }

        // ---- 8. URGENT 立即抢占；其余必须满足最短驻留 3s（S2 S3）----
        if (best.priority != PromptPriority.URGENT &&
            now - active.shownAt < SchedulerConfig.MIN_DWELL_MS
        ) {
            return PromptDecision(active, false, null, ranked.size)
        }

        cooldowns[active.candidate.tipId] = now + SchedulerConfig.COOLDOWN_MS
        return show(best, now, ranked.size)
    }

    /** 用户点「下一条」：当前条进 8s 冷却，不拉黑（S5）。 */
    fun onNext(now: Long): PromptDecision {
        val active = current ?: return PromptDecision(null, false, null, 0)
        cooldowns[active.candidate.tipId] = now + SchedulerConfig.COOLDOWN_MS
        current = null
        return reconcile(PromptDecision(null, true, null, 0))
    }

    /** 用户长按「别再说了」：当前条进本会话黑名单（S6）。 */
    fun onBlock(now: Long): PromptDecision {
        val active = current ?: return PromptDecision(null, false, null, 0)
        blacklist.add(active.candidate.tipId)
        cooldowns[active.candidate.tipId] = now + SchedulerConfig.COOLDOWN_MS
        current = null
        return reconcile(PromptDecision(null, true, null, 0))
    }

    /** 外部检测到用户已完成动作（真的切了 2x、真的扶正了）：永久拉黑。 */
    fun markExecuted(tipId: String, now: Long) {
        executed.add(tipId)
        blacklist.add(tipId)
        cooldowns[tipId] = now + SchedulerConfig.COOLDOWN_MS
        if (current?.candidate?.tipId == tipId) current = null
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) current = null
        // 不返还 PromptDecision，UI 不会立刻知道。这里只改内部状态，
        // 撤卡靠下一次 tick 的 reconcile（lastReportedTipId 比对）兜住。
    }

    fun setDensity(density: PromptDensity) {
        if (this.density == density) return
        this.density = density
        val active = current
        // 新档位不允许这条 → 立刻下掉。UI 的撤卡同样由下一次 tick 的 reconcile 负责：
        // 这就是 QA P2-4 —— 若 `changed` 仍按内部 `current` 判，槽位会停在上一句不撤。
        if (active != null && !densityAllows(active.candidate.priority)) current = null
    }

    fun pause(paused: Boolean) {
        this.paused = paused
    }

    /** 切页 / 退出时清会话态；密度与静音保留。 */
    fun resetSession(now: Long) {
        current = null
        cooldowns.clear()
        blacklist.clear()
        executed.clear()
        ruleFirstTrue.clear()
        cycle = 0
        lastGuideAt = now
        lastCandidateAt = now
    }

    fun snapshot(): SchedulerSnapshot = SchedulerSnapshot(
        currentTipId = current?.candidate?.tipId,
        cooldowns = HashMap(cooldowns),
        blacklist = HashSet(blacklist),
        executed = HashSet(executed),
        cycle = cycle,
        muted = muted,
        density = density,
        lastGuideAt = lastGuideAt
    )

    // ── 内部工具 ────────────────────────────────────────────────────────────────

    private fun show(candidate: PromptCandidate, now: Long, visibleCount: Int): PromptDecision {
        cycle++
        cooldowns[candidate.tipId] = now + SchedulerConfig.COOLDOWN_MS
        val active = ActivePrompt(candidate, now, cycle)
        current = active
        // 节拍回填：只有**节奏类**候选（R09 运镜 / R10 兜底轮播）推进 lastGuideAt，
        // 由候选自带的 [PromptCandidate.paced] 标记决定，**不看 priority**。
        //
        // 历史教训（QA P1-1）：v1.5 把 R09 从 GUIDE 升到 CONTEXT 后，这里原本按
        // `priority == GUIDE` 判，于是唯一还能回填的只剩 R10；而 R10 在 STANDARD 档位下
        // 又被密度过滤掉，结果 lastGuideAt 永远停在 -1，R09 的 8 秒节拍门
        // （`PromptEngine` 里 `R09_move_guide` 的 `ready`）恒真 —— 机制还在跑，语义已经死了。
        // 现在改成按显式标记判定，谁改了优先级都不会再悄悄弄坏节拍。
        if (candidate.paced) lastGuideAt = now
        return PromptDecision(active, true, null, visibleCount)
    }

    private fun cooldownOk(tipId: String, now: Long): Boolean {
        val until = cooldowns[tipId] ?: return true
        return until <= now
    }

    private fun holdSatisfied(candidate: PromptCandidate, now: Long): Boolean {
        if (candidate.holdMs <= 0L) return true
        val since = ruleFirstTrue[candidate.ruleId] ?: return false
        return now - since >= candidate.holdMs
    }

    private fun densityAllows(priority: PromptPriority): Boolean = when (density) {
        PromptDensity.QUIET -> priority == PromptPriority.URGENT
        PromptDensity.STANDARD -> priority != PromptPriority.GUIDE
        PromptDensity.CHATTER -> true
    }

    /**
     * 排序键（§5.3 写死）：
     * `priority.rank → 已执行 → 冷却已过期 → weight 降序 → ruleId`
     * 全部确定性，不使用随机数，保证"同输入同输出"。
     */
    private fun orderFor(now: Long): Comparator<PromptCandidate> =
        compareBy<PromptCandidate> { it.priority.rank }
            .thenBy { if (executed.contains(it.tipId)) 1 else 0 }
            .thenBy { if (cooldownOk(it.tipId, now)) 0 else 1 }
            .thenByDescending { it.weight }
            .thenBy { it.ruleId }

    /** 信号是否已恢复正常。新增 recoverKey 必须在这里补分支，否则 autoResolve 永远不生效。 */
    private fun isRecovered(key: String, signals: PromptSignals): Boolean = when (key) {
        "roll" -> signals.roll == null || abs(signals.roll) <= 1f
        "highlight" -> signals.tech.highlightClip <= PromptEngine.HIGHLIGHT_CLIP_THRESHOLD
        "shade" -> signals.tech.meanLuma >= 0.20f &&
            signals.tech.shadowClip <= PromptEngine.SHADOW_CLIP_TRIGGER
        "backlit" -> signals.tech.centerBias >= -0.04f
        "zoom" -> signals.equivFocalMm > 28
        "grid" -> signals.gridOn
        else -> false
    }
}
