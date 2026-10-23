package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType

/**
 * 提示优先级。
 *
 * 注意：这是「提示卡片」的优先级，与需求池里的 P0/P1 不是同一概念。
 * rank 越小越优先，排序时直接用它。
 */
enum class PromptPriority(val rank: Int, val label: String) {
    URGENT(0, "硬伤"),
    CONTEXT(1, "场景"),
    GUIDE(2, "教学")
}

/**
 * 提示密度。
 *
 * - QUIET：只放画面硬伤，取景时最安静
 * - STANDARD：默认档，硬伤 + 场景
 * - CHATTER：再加上教学轮播
 */
enum class PromptDensity { QUIET, STANDARD, CHATTER }

/**
 * 提示引擎的输入信号快照。
 *
 * 三条设计约束：
 * 1. **只依赖 Kotlin 标准库**：不允许出现任何 Android 类型，否则 JVM 单测跑不了；
 * 2. 全部给默认值：调用方只填自己知道的那几项，避免"新增字段要改十处调用"；
 * 3. 不可变：每次 tick 由 MainActivity 折算出一个新实例。
 *
 * 与 ARCH §5.2 的差异（三处，均为纯增量，默认值保证源兼容）：
 * - `mirrored`：前置预览是镜像的，水平仪的方向文案必须跟着翻转，否则用户照提示转只会越转越歪；
 * - `stability`：R10 兜底轮播需要判断"画面是否稳定"，§5.2 漏了这个字段；
 * - `gridOffAgoMs`：R11 需要区分"刚关掉"与"长期关着"，否则会跟用户的显式选择对着干（QA P1-3）。
 */
class PromptSignals(
    /** 画面统计信号的超集（平均亮度、裁切、肤色、饱和度、边缘密度…） */
    val tech: TechSignals = TechSignals(),
    /** 画面倾角（度），优先取结构张量，取不到用重力传感器；null 表示两个源都没有 */
    val roll: Float? = null,
    /** 俯仰角（度），0 = 手机竖直，负值 = 俯拍 */
    val pitch: Float? = null,
    /** 当前页：0 照片 / 1 录像 / 2 功能 / 3 技法 */
    val tab: Int = 0,
    /** 录像意图序号（VideoIntent.ordinal），非录像页为 -1 */
    val videoIntentOrdinal: Int = -1,
    val scene: SceneType = SceneType.GENERAL,
    /** 是否有独立长焦镜头 */
    val hasTele: Boolean = false,
    /** 当前等效焦距（毫米） */
    val equivFocalMm: Int = 24,
    /** 九宫格辅助线是否打开 */
    val gridOn: Boolean = true,
    /** FeatureAdvisor 本轮已就"长焦/夜景/HDR"发过言的 topic，用于排他，避免两处说同一件事 */
    val coveredTopics: Set<String> = emptySet(),
    /** 本次会话已拍张数（shottuner 不接管拍照，此处恒为 0，留作统计位） */
    val sessionCount: Int = 0,
    /** 预览是否镜像（前置）。镜像时左右方向文案必须翻转 */
    val mirrored: Boolean = false,
    /** 画面抖动程度 0..1，0 = 完全静止。R10 兜底轮播用它判"稳定" */
    val stability: Float = 0f,
    /**
     * 网格已经关掉多久（毫秒）。
     *
     * `Long.MAX_VALUE` 表示"现在开着，或者关掉的时间未知"—— 两种情况都应该让 R11 闭嘴。
     * 由 `MainActivity` 在用户点「网格」关掉的那一刻记一次 `elapsedRealtime()`，
     * 每次 tick 算一次差值传进来。
     *
     * 为什么要这个字段：用户主动关网格通常是为了看清画面。若下一 tick（500ms）就弹
     * 「把九宫格打开」，等于跟用户的显式选择对着干，而且他一打开就会被永久拉黑 ——
     * 提示只来得及惹人烦一次。留一段静默期，只对"长期关着"的人温和提一次。
     */
    val gridOffAgoMs: Long = Long.MAX_VALUE
)

/**
 * 一条提示候选。
 *
 * 由 [PromptEngine] 产出，[PromptScheduler] 负责排序、冷却、驻留与拉黑。
 * 引擎只回答"现在有哪些话值得说"，不回答"现在轮到谁说"。
 */
class PromptCandidate(
    /** 规则 id，稳定不变，测试逐条断言 */
    val ruleId: String,
    /** 关联的技法 id */
    val tipId: String,
    val priority: PromptPriority,
    /** 同优先级内的权重，越大越优先 */
    val weight: Int,
    /** 卡片主文案（已渲染好动态数值） */
    val action: String,
    /** 卡片副文案，一句话说明为什么 */
    val reason: String,
    /** 该规则需要持续满足多久才允许出牌（0 = 立即） */
    val holdMs: Long = 0L,
    /** 信号恢复即视为"已执行"并永久拉黑 */
    val autoResolve: Boolean = false,
    /** 出牌时拍的快照 key，供 autoResolve 判"信号是否已恢复" */
    val recoverKey: String? = null,
    /**
     * 是否为**节奏类**规则（R09 运镜 / R10 兜底轮播）。
     *
     * 节奏类规则出牌时，调度器会推进 `lastGuideAt`，供下一条节奏类规则做 8 秒节拍判断。
     * 这个标记必须显式写在规则上，**不要按 priority 推断** —— v1.5 把 R09 从 GUIDE
     * 升到 CONTEXT 之后，按优先级判会把 R09 漏掉，节拍门就变成死逻辑（QA P1-1）。
     */
    val paced: Boolean = false
) {
    override fun toString(): String =
        "PromptCandidate($ruleId, $tipId, $priority, w=$weight, hold=$holdMs)"
}
