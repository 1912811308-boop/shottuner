package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import com.shottuner.app.sensors.LevelMath
import com.shottuner.app.text.NumFmt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 提示规则表（11 条）。
 *
 * ## 职责边界（§5.1）
 * - 只回答「现在有哪些话值得说」，**不做**冷却、驻留、拉黑、密度过滤 —— 那些是 [PromptScheduler] 的事；
 * - 纯函数、无状态：同输入必然同输出，所以能逐条单测；
 * - 规则通过**字符串 id** 引用技法。阶段一技法库还是空的，因此 `availableTipIds` 传进来时
 *   找不到的 id 一律静默跳过（不抛异常、不产半成品卡片）。
 *
 * ## 阈值来源
 * - `HIGHLIGHT_CLIP_THRESHOLD = 0.12` / `SHADOW_CLIP_THRESHOLD = 0.245` 是 PRD 口径换算到
 *   shottuner 口径的结果，推导见 `ScoreBoard` 的常量注释（§6.3）；
 * - R03 用的 `SHADOW_CLIP_TRIGGER = 0.28` 是**提示触发**阈值，比评分阈值宽一点：
 *   评分要保守，提示宁可早一点开口。
 *
 * ## 排序（写死，可测）
 * `priority.rank → weight 降序 → ruleId`，然后按 tipId 去重（同一条技巧不会同时出现在两个候选里）。
 * 调度器会在这个基础上再叠加它自己的冷却/执行态排序。
 */
object PromptEngine {

    /** 照片页 tab 序号。 */
    const val TAB_PHOTO = 0

    /** 录像页 tab 序号。 */
    const val TAB_VIDEO = 1

    /** 规则的 `tabScope` 取该值表示"全页适用"（R10 兜底 / R11 网格）。 */
    const val TAB_ANY = -1

    /** 倾斜触发阈值（度）。 */
    const val TILT_THRESHOLD_DEG = 3f

    /** 过曝触发阈值（PRD 0.20 → shottuner 0.12，见 §6.3）。 */
    const val HIGHLIGHT_CLIP_THRESHOLD = 0.12f

    /** 欠曝触发阈值 —— 暗部死黑口径（提示用，比评分宽）。 */
    const val SHADOW_CLIP_TRIGGER = 0.28f

    /** 欠曝触发阈值 —— 平均亮度口径。 */
    const val LOW_LUMA_THRESHOLD = 0.18f

    /** 画面抖动低于该值才算「稳定」，R10 兜底轮播的前提。 */
    const val STABLE_THRESHOLD = 0.35f

    /**
     * 网格关掉后静默多久才允许提醒（毫秒）。
     *
     * 用户主动关网格几乎总是为了看清画面。刚关就提醒等于跟他的显式选择对着干，
     * 而且他一打开就会被 autoResolve 永久拉黑 —— 这条提示只剩一次惹人烦的机会。
     * 60 秒的口径：真忍着不改的人，一分钟内必然已经看清了想看的东西。
     */
    const val GRID_OFF_QUIET_MS = 60_000L

    /** 引擎侧的窗口信息（全部来自调度器，引擎自己不持有状态）。 */
    private class Window(
        val lastGuideAt: Long,
        val lastCandidateAt: Long,
        val produced: MutableList<PromptCandidate>
    )

    /** 一条规则。 */
    private class Rule(
        val id: String,
        val priority: PromptPriority,
        val weight: Int,
        /**
         * 该规则在哪个 tab 生效。默认拍照页 —— 这是模式隔离的唯一实现方式（§5.4）：
         * 不给 83 条技法逐个填白名单，而是让规则自带 tab 条件，
         * 效果等价且不会漏填。取 [TAB_ANY] 表示全页适用。
         */
        val tabScope: Int = TAB_PHOTO,
        /** 条件需持续满足多久才允许出牌 */
        val holdMs: Long = 0L,
        /** 信号恢复判定 key，见 [PromptScheduler] 的 isRecovered */
        val recoverKey: String? = null,
        /** 信号恢复即永久拉黑 */
        val autoResolve: Boolean = false,
        /**
         * 是否为**节奏类**规则（运镜 / 兜底轮播），见 [PromptCandidate.paced]。
         * 只有这两条规则会推进 `lastGuideAt`，其它规则出牌不影响节拍。
         */
        val paced: Boolean = false,
        /** 返回 null 表示该规则当前不适用 */
        val tipId: (PromptSignals, Long, Window) -> String?,
        val action: (PromptSignals) -> String,
        val reason: (PromptSignals) -> String
    )

    // ── 规则用到的技法 id（阶段二会在 TechniqueBookV15 里落地） ──────────────────
    /** 地平线条目的 id：也是调度器判定 LEVEL_OK 正向反馈的依据。 */
    const val TIP_HORIZON = "landscape_horizon_level"
    private const val TIP_PROTECT_HIGHLIGHT = "basic_protect_highlights"
    private const val TIP_NIGHT_STATIC = "night_handheld_static"
    private const val TIP_EXPOSURE_BASICS = "light_white_add_black_minus"
    private const val TIP_BACKLIT_FACE = "portrait_backlit_face_first"
    private const val TIP_WIDE_FACE = "portrait_wide_face"
    private const val TIP_NIGHT_NO_FLASH = "night_portrait_no_direct_flash"
    private const val TIP_FOOD_45 = "food_angle_45_top"
    private const val TIP_GET_CLOSER = "street_get_closer"
    private const val TIP_GRID = "pitfall_grid_on"
    private const val TIP_EYES_THIRDS = "portrait_eyes_thirds"
    private const val TIP_HEADROOM = "portrait_headroom"

    /**
     * R09 运镜 id → 卡片主文案。
     *
     * 键必须与 `docs/TECHNIQUE-v15.md` 里的 id 一字不差，且不含
     * [TechniqueBookV15.BROWSE_ONLY_IDS]（那些不进推送池）。
     * 漏掉的键不会崩，但会退化成 R09 的默认句，用户看到的是泛泛而谈。
     */
    private val MOVE_TEXT = mapOf(
        "move_fixed_support" to "把手机架在栏杆或桌面上再录",
        "move_push_in_slow" to "用 8 到 10 秒匀速推向主体",
        "move_orbit_30deg" to "绕主体慢慢走，每秒转不到 30°",
        "move_hold_ends" to "开录先停 2 秒，收尾再停 2 秒",
        "move_elbow_tucked" to "手肘夹紧肋骨，用身体带动手机",
        "move_small_steps" to "微微屈膝，用脚掌小步挪过去",
        "move_walk_not_zoom" to "想拉近就走过去，别捏屏幕放大"
    )

    /** R10 兜底池的卡片主文案。 */
    private val FALLBACK_TEXT = mapOf(
        TIP_EXPOSURE_BASICS to "白加黑减：画面白多就加曝光，黑多就减曝光",
        TIP_PROTECT_HIGHLIGHT to "先保高光，亮部一糊就再也拉不回来",
        TIP_NIGHT_STATIC to "手肘抵住墙，屏住呼吸再按快门",
        TIP_BACKLIT_FACE to "逆光先保证脸亮，背景亮就让它亮",
        TIP_EYES_THIRDS to "把眼睛放到三分交点上",
        TIP_HEADROOM to "头顶留一小格空白，别顶到画框",
        TIP_HORIZON to "打开网格，把地平线压到横线上"
    )

    /** 兜底轮播池：按场景给两条通用口诀。 */
    private fun fallbackPool(scene: SceneType): List<String> = when (scene) {
        SceneType.NIGHT -> listOf(TIP_NIGHT_STATIC, TIP_EXPOSURE_BASICS)
        SceneType.BACKLIT -> listOf(TIP_BACKLIT_FACE, TIP_EXPOSURE_BASICS)
        SceneType.PORTRAIT -> listOf(TIP_EYES_THIRDS, TIP_HEADROOM)
        SceneType.LANDSCAPE -> listOf(TIP_HORIZON, TIP_EYES_THIRDS)
        SceneType.INDOOR -> listOf(TIP_NIGHT_STATIC, TIP_EXPOSURE_BASICS)
        SceneType.ACTION -> listOf(TIP_PROTECT_HIGHLIGHT, TIP_EXPOSURE_BASICS)
        SceneType.GENERAL -> listOf(TIP_EXPOSURE_BASICS, TIP_PROTECT_HIGHLIGHT)
    }

    /** 百分比格式化，避免各处各写一遍。 */
    private fun pct(value: Float): String = NumFmt.fixed(value.coerceIn(0f, 1f) * 100f, 0)

    /** 11 条规则。顺序即求值顺序，R10 必须最后（它依赖"前面没有硬候选"）。 */
    private val RULES: List<Rule> = listOf(
        // R01 地平线歪了 —— 唯一不需要画面统计就能成立的硬伤
        Rule(
            id = "R01_tilt",
            priority = PromptPriority.URGENT,
            weight = 30,
            holdMs = 800L,
            recoverKey = "roll",
            autoResolve = true,
            tipId = { s, _, _ ->
                val roll = s.roll
                if (roll == null || abs(roll) <= TILT_THRESHOLD_DEG) null else TIP_HORIZON
            },
            action = { s ->
                val roll = s.roll ?: 0f
                val hint = LevelMath.rollHint(roll, s.mirrored)
                "地平线偏了 ${hint.degrees}°，${hint.direction}转一点"
            },
            reason = { s ->
                val deg = abs(s.roll ?: 0f).roundToInt()
                "歪了 ${deg}°，地平线是整张照片的基准"
            }
        ),

        // R02 高光溢出
        Rule(
            id = "R02_overexposed",
            priority = PromptPriority.URGENT,
            weight = 28,
            holdMs = 500L,
            recoverKey = "highlight",
            autoResolve = true,
            tipId = { s, _, _ ->
                if (s.tech.hasStats && s.tech.highlightClip > HIGHLIGHT_CLIP_THRESHOLD) TIP_PROTECT_HIGHLIGHT else null
            },
            action = { "高光糊了，曝光补偿降 0.3" },
            reason = { s -> "亮部已经溢出 ${pct(s.tech.highlightClip)}%，细节拉不回来了" }
        ),

        // R03 欠曝 / 暗部死黑（夜景走"靠稳"，其余走"白加黑减"）
        Rule(
            id = "R03_underexposed",
            priority = PromptPriority.URGENT,
            weight = 28,
            holdMs = 500L,
            recoverKey = "shade",
            autoResolve = true,
            tipId = { s, _, _ ->
                val dark = s.tech.meanLuma < LOW_LUMA_THRESHOLD || s.tech.shadowClip > SHADOW_CLIP_TRIGGER
                if (!s.tech.hasStats || !dark) null
                else if (s.scene == SceneType.NIGHT) TIP_NIGHT_STATIC else TIP_EXPOSURE_BASICS
            },
            action = { s ->
                if (s.scene == SceneType.NIGHT) "手肘抵住墙或栏杆，ISO 再加一档"
                else "ISO 加一档，或者把快门慢一档"
            },
            reason = { s -> "平均亮度只有 ${pct(s.tech.meanLuma)}%，暗部已经死黑" }
        ),

        // R04 逆光脸黑
        Rule(
            id = "R04_backlit_face",
            priority = PromptPriority.URGENT,
            weight = 30,
            holdMs = 500L,
            recoverKey = "backlit",
            autoResolve = true,
            tipId = { s, _, _ ->
                val backlit = s.tech.meanLuma > 0.45f &&
                    s.tech.centerBias < -0.08f &&
                    s.tech.skinRatio > 0.04f
                if (s.tech.hasStats && backlit) TIP_BACKLIT_FACE else null
            },
            action = { "对着脸点一下测光，曝光补偿降 0.7" },
            reason = { s -> "背景比脸亮 ${pct(abs(s.tech.centerBias))}，脸已经压成黑影了" }
        ),

        // R05 人像用广角
        Rule(
            id = "R05_portrait_wide",
            priority = PromptPriority.CONTEXT,
            weight = 22,
            holdMs = 1000L,
            recoverKey = "zoom",
            autoResolve = true,
            tipId = { s, _, _ ->
                val wide = s.scene == SceneType.PORTRAIT &&
                    s.equivFocalMm <= 28 &&
                    s.tech.hasStats &&
                    s.tech.skinRatio > 0.05f &&
                    s.hasTele
                if (wide) TIP_WIDE_FACE else null
            },
            action = { "切到 2x 再拍，脸不会被拉宽" },
            reason = { s -> "${s.equivFocalMm}mm 广角近拍人像会有桶形畸变" }
        ),

        // R06 暗光（有肤色优先劝退直闪）
        Rule(
            id = "R06_night_low_light",
            priority = PromptPriority.CONTEXT,
            weight = 20,
            holdMs = 800L,
            tipId = { s, _, _ ->
                if (!s.tech.hasStats) null
                else {
                    val dim = s.scene == SceneType.NIGHT || s.tech.meanLuma < 0.15f
                    if (!dim) null
                    else if (s.tech.skinRatio > 0.04f) TIP_NIGHT_NO_FLASH else TIP_NIGHT_STATIC
                }
            },
            action = { s ->
                if (s.tech.skinRatio > 0.04f) "别开直闪，让脸转向最近的一盏灯"
                else "手肘抵住墙或栏杆，再按快门"
            },
            reason = { s -> "平均亮度只有 ${pct(s.tech.meanLuma)}%，暗光下手持很难端稳" }
        ),

        // R07 美食 45°（没有俯仰角就闭嘴，不猜）
        Rule(
            id = "R07_food_angle",
            priority = PromptPriority.CONTEXT,
            weight = 18,
            holdMs = 1200L,
            tipId = { s, _, _ ->
                val pitch = s.pitch
                // 没有俯仰角就闭嘴：不猜，宁可不说话
                val foodLike = pitch != null &&
                    abs(pitch + 45f) > 20f &&
                    s.tech.hasStats &&
                    s.tech.saturation > 0.35f &&
                    s.tech.edgeDensity > 0.15f &&
                    s.tech.meanLuma > 0.25f
                if (foodLike) TIP_FOOD_45 else null
            },
            action = { "把手机抬到 45° 再拍这盘菜" },
            reason = { "45° 才同时看得到摆盘和高度" }
        ),

        // R08 暗光别硬拉长焦
        Rule(
            id = "R08_tele_abuse",
            priority = PromptPriority.CONTEXT,
            weight = 16,
            holdMs = 1000L,
            tipId = { s, _, _ ->
                val abuse = s.hasTele &&
                    s.equivFocalMm > 50 &&
                    s.tech.hasStats &&
                    s.tech.meanLuma < 0.25f &&
                    !s.coveredTopics.contains("tele")
                if (abuse) TIP_GET_CLOSER else null
            },
            action = { "走过去，别用长焦硬拉" },
            reason = { "长焦在暗光下进光少，画面会糊" }
        ),

        // R09 录像运镜轮播（与 VideoRecommender 的 plan.tips 互补，不重复）
        //
        // 优先级是 CONTEXT 而不是 GUIDE：录像页的核心功能就是教运镜，它属于
        // 「当前场景强相关」而不是「通用教学轮播」。归到 GUIDE 的话，S8 规定的
        // 默认档位 STANDARD（只放 URGENT+CONTEXT）会把它整条过滤掉，
        // 录像页会退化成一张白卡。
        Rule(
            id = "R09_move_guide",
            priority = PromptPriority.CONTEXT,
            weight = 12,
            tabScope = TAB_VIDEO,
            holdMs = 0L,
            // 节奏类：出牌时推进 lastGuideAt，让下一条运镜至少隔 8 秒
            paced = true,
            tipId = { s, now, w ->
                val ready = w.lastGuideAt < 0L ||
                    now - w.lastGuideAt >= SchedulerConfig.GUIDE_INTERVAL_MS
                if (!ready) null
                else VideoMovePlanner.nextMove(
                    s.videoIntentOrdinal,
                    (now / SchedulerConfig.GUIDE_INTERVAL_MS).toInt()
                )
            },
            // 具体动作依赖运镜 id，由 renderAction() 用 MOVE_TEXT 覆盖这里
            action = { "先找支点，再开机录制" },
            reason = { "运镜先解决稳，再谈好看" }
        ),

        // R10 兜底教学轮播（无硬候选 + 画面稳定 + 空闲够久）
        Rule(
            id = "R10_fallback_guide",
            priority = PromptPriority.GUIDE,
            weight = 10,
            tabScope = TAB_ANY,
            holdMs = 6000L,
            // 节奏类：兜底轮播同样受 8 秒节拍约束，否则它会每 tick 换一句口诀
            paced = true,
            tipId = { s, now, w ->
                val hasHard = w.produced.any { it.priority != PromptPriority.GUIDE }
                val idle = w.lastCandidateAt < 0L ||
                    now - w.lastCandidateAt >= SchedulerConfig.IDLE_TRIGGER_MS
                if (hasHard || !idle || !s.tech.hasStats || s.stability >= STABLE_THRESHOLD) null
                else {
                    val pool = fallbackPool(s.scene)
                    if (pool.isEmpty()) null
                    else pool[((now / SchedulerConfig.GUIDE_INTERVAL_MS).toInt()).mod(pool.size)]
                }
            },
            action = { "先解决画面硬伤，再谈构图" },
            reason = { "还没检测到具体问题，先给你一条通用口诀" }
        ),

        // R11 网格没开（用户打开网格后自动收敛并永久拉黑）
        //
        // 同样是 CONTEXT：这是 `gridOn == false` 精准命中的设置态提示，
        // 不是教学轮播。放 GUIDE 会让它在默认档位下永不出现，而这恰恰是
        // 新手最该被提醒一次的一条（开网格这件事一改长期受用）。
        //
        // 但「关着」不等于「忘了开」：用户常常是主动关掉网格以便看清画面。
        // 所以还有第二道门 `gridOffAgoMs > GRID_OFF_QUIET_MS`（QA P1-3）——
        // 刚关不打扰，长期关着才温和提一次。文案也不能写成「把网格打开」，
        // 那是在命令用户撤销自己刚做的选择。
        Rule(
            id = "R11_grid_off",
            priority = PromptPriority.CONTEXT,
            weight = 14,
            tabScope = TAB_ANY,
            holdMs = 0L,
            recoverKey = "grid",
            autoResolve = true,
            tipId = { s, _, _ ->
                val offLongEnough = s.gridOffAgoMs > GRID_OFF_QUIET_MS
                if (!s.gridOn && offLongEnough) TIP_GRID else null
            },
            action = { "网格关着，构图少了一条参照线" },
            reason = { "把对照线调回来，取景会稳很多" }
        )
    )

    /** 全部规则 id（顺序稳定），供测试遍历穷尽性。 */
    fun ruleIds(): List<String> = RULES.map { it.id }

    /**
     * 引擎**有可能**发射的全部技法 id（结构化的静态清单）。
     *
     * 存在的唯一理由是让"禁推条目不得进推送池"这条纪律可断言、可回归：
     * 把可发射面从一堆 lambda 里抽成显式集合，测试就能直接断言
     * `allEmittableTipIds() ∩ (SELFIE_IDS ∪ BROWSE_ONLY_IDS) == ∅`。
     *
     * 之前的做法是穷举 10 080 组信号去看"实际有没有泄漏"——那个断言**不可能失败**
     * （可发射集合与禁推集合本来就按构造互斥），QA 判定为结构性假阳性（P2-1）。
     *
     * 维护约定：新增规则若点名了新的 tipId，必须加进这里。
     * 漏加的后果由 `TechniqueBookV15Test` 兜住 —— 它会断言这里声明的每一条
     * 都真实存在于技法库且可推送，同时断言规则表不会发射清单之外的东西。
     */
    fun allEmittableTipIds(): Set<String> = EMITTABLE_TIP_IDS

    /**
     * 静态清单本体。
     *
     * 三段来源，缺一不可：
     * - 单条规则的固定 id（R01–R08、R11）；
     * - R09 的运镜池文案表（`MOVE_TEXT` 的键就是它可能吐出的全部 id）；
     * - R10 的兜底池文案表（`FALLBACK_TEXT` 同理）。
     *
     * 注意 `TIP_EYES_THIRDS` / `TIP_HEADROOM` 只出现在兜底池与 `FALLBACK_TEXT` 里，
     * 没有任何单条规则直接点名它们，所以不能只从规则常量里收集。
     */
    private val EMITTABLE_TIP_IDS: Set<String> = setOf(
        TIP_HORIZON,
        TIP_PROTECT_HIGHLIGHT,
        TIP_NIGHT_STATIC,
        TIP_EXPOSURE_BASICS,
        TIP_BACKLIT_FACE,
        TIP_WIDE_FACE,
        TIP_NIGHT_NO_FLASH,
        TIP_FOOD_45,
        TIP_GET_CLOSER,
        TIP_GRID,
        TIP_EYES_THIRDS,
        TIP_HEADROOM
    ) + MOVE_TEXT.keys + FALLBACK_TEXT.keys

    /**
     * 求值。
     *
     * @param availableTipIds 技法库里真实存在的 id 集合。传 null 表示不做过滤（测试用）；
     *        生产环境应传 `TechniqueBookV15.pushEligibleIds`（已剔除自拍与仅浏览条目），
     *        池里没有的 id 会静默跳过。
     * @param lastGuideAt 上一条**节奏类**（[PromptCandidate.paced]）提示的出牌时间，
     *        由调度器回填；<0 表示本次会话还没出过。只有 R09/R10 读它。
     * @param lastCandidateAt 上次有任何硬候选的时间（调度器回填），<0 表示从未
     */
    fun evaluate(
        signals: PromptSignals,
        now: Long,
        availableTipIds: Set<String>? = null,
        lastGuideAt: Long = -1L,
        lastCandidateAt: Long = -1L
    ): List<PromptCandidate> {
        val window = Window(lastGuideAt, lastCandidateAt, ArrayList(RULES.size))
        val out = ArrayList<PromptCandidate>(RULES.size)
        for (rule in RULES) {
            if (!appliesToTab(rule, signals)) continue
            val tipId = rule.tipId(signals, now, window) ?: continue
            if (availableTipIds != null && tipId !in availableTipIds) continue
            val candidate = PromptCandidate(
                ruleId = rule.id,
                tipId = tipId,
                priority = rule.priority,
                weight = rule.weight,
                action = renderAction(rule, tipId, signals),
                reason = rule.reason(signals),
                holdMs = rule.holdMs,
                autoResolve = rule.autoResolve,
                recoverKey = rule.recoverKey,
                paced = rule.paced
            )
            out.add(candidate)
            window.produced.add(candidate)
        }
        return rank(out)
    }

    /**
     * 单独取某条规则的候选（测试与调试用）。
     * 注意：R10 依赖"前面没有任何候选"，单独探测时按"没有其它候选"处理。
     */
    fun candidateFor(ruleId: String, signals: PromptSignals, now: Long): PromptCandidate? {
        val rule = RULES.firstOrNull { it.id == ruleId } ?: return null
        if (!appliesToTab(rule, signals)) return null
        val window = Window(-1L, -1L, ArrayList(0))
        val tipId = rule.tipId(signals, now, window) ?: return null
        return PromptCandidate(
            ruleId = rule.id,
            tipId = tipId,
            priority = rule.priority,
            weight = rule.weight,
            action = renderAction(rule, tipId, signals),
            reason = rule.reason(signals),
            holdMs = rule.holdMs,
            autoResolve = rule.autoResolve,
            recoverKey = rule.recoverKey,
            paced = rule.paced
        )
    }

    /**
     * 模式隔离：规则自带 tab 条件（§5.4）。
     *
     * 拍照规则（R01–R08）只在拍照页说话，运镜规则（R09）只在录像页说话，
     * 兜底与网格（R10/R11）全页适用 —— 一条集中判断，避免每个 lambda 各写一遍
     * 而某一条漏写（漏写的表现就是录像页突然教你"手肘夹紧身体拍照片"）。
     */
    private fun appliesToTab(rule: Rule, signals: PromptSignals): Boolean =
        rule.tabScope == TAB_ANY || signals.tab == rule.tabScope

    /** R09/R10 的主文案依赖 tipId（运镜/兜底条目各有一句话），在这里统一覆盖。 */
    private fun renderAction(rule: Rule, tipId: String, signals: PromptSignals): String {
        if (rule.id == "R09_move_guide") {
            return MOVE_TEXT[tipId] ?: rule.action(signals)
        }
        if (rule.id == "R10_fallback_guide") {
            return FALLBACK_TEXT[tipId] ?: rule.action(signals)
        }
        return rule.action(signals)
    }

    /**
     * 排序 + 按 tipId 去重（同一条技巧不会同时出现在两个候选里）。
     * 去重保留排序更靠前的那条（R03 与 R06 都会指向夜景靠稳条，此时留 URGENT 那条）。
     */
    private fun rank(list: List<PromptCandidate>): List<PromptCandidate> {
        val sorted = list.sortedWith(
            compareBy<PromptCandidate> { it.priority.rank }
                .thenByDescending { it.weight }
                .thenBy { it.ruleId }
        )
        val seen = HashSet<String>(sorted.size)
        return sorted.filter { seen.add(it.tipId) }
    }
}
