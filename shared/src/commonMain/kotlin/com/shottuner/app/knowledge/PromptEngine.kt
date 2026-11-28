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
         * 不给 98 条技法逐个填白名单，而是让规则自带 tab 条件，
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

    /** R10 兜底池里**精选**那一部分的卡片主文案（id → 一句话）。 */
    private val FALLBACK_TEXT = mapOf(
        TIP_EXPOSURE_BASICS to "白加黑减：画面白多就加曝光，黑多就减曝光",
        TIP_PROTECT_HIGHLIGHT to "先保高光，亮部一糊就再也拉不回来",
        TIP_NIGHT_STATIC to "手肘抵住墙，屏住呼吸再按快门",
        TIP_BACKLIT_FACE to "逆光先保证脸亮，背景亮就让它亮",
        TIP_EYES_THIRDS to "把眼睛放到三分交点上",
        TIP_HEADROOM to "头顶留一小格空白，别顶到画框",
        TIP_HORIZON to "打开网格，把地平线压到横线上"
    )

    /**
     * 兜底池里**从技法库补进来**的窗口大小（不含前面两条精选，合计最多 8 条）。
     *
     * 为什么要有上限：R10 是轮播，用户每 `GUIDE_INTERVAL_MS` 才看到一句。窗口越大，
     * 同一条再次轮到要等的时间就越长 —— 8 条已经是 64 秒一轮，再大就变成"偶尔说一句话
     * 的陌生卡片"，反而不像提示。上限的存在不是为了省内存。
     *
     * 注意它限制的是**一个窗口**的大小，不是"总共能轮到几条"：库内候选按窗口整体滑动，
     * 见 [buildFallbackWindows]。所以这个 6 不会把技法库里多出来的条目永久挡在外面。
     */
    private const val FALLBACK_LIBRARY_LIMIT = 6

    /**
     * 兜底窗口滑动周期（毫秒）= **一个完整轮播周期** = 引导间隔 × 窗口大小。
     *
     * 为什么是乘积而不是随手拍的数：一个窗口要 6 条 × 8 秒才播完一遍，播完之后再滑，
     * 用户才不会在"还没轮到的条目"上被打断。取 48 秒，语义就是"上一窗播完了，换下一窗"。
     *
     * 为什么每次滑**一整窗**而不是滑 1 条：PORTRAIT 的库内候选有二十来条，滑 1 条要
     * 二十多个周期才能轮完，用户等不到；按窗口步进，3~5 个周期就能把库里同一场景的
     * 条目全轮一遍（15 条批次 C 在 3 个周期内全部可达，`PromptEngineTest` 守着）。
     */
    const val FALLBACK_ROTATE_MS: Long = SchedulerConfig.GUIDE_INTERVAL_MS * FALLBACK_LIBRARY_LIMIT

    /** 每个场景**精选**的两条口诀。id 与顺序都是历史打磨过的，不许动。 */
    private fun curatedFallback(scene: SceneType): List<String> = when (scene) {
        SceneType.NIGHT -> listOf(TIP_NIGHT_STATIC, TIP_EXPOSURE_BASICS)
        SceneType.BACKLIT -> listOf(TIP_BACKLIT_FACE, TIP_EXPOSURE_BASICS)
        SceneType.PORTRAIT -> listOf(TIP_EYES_THIRDS, TIP_HEADROOM)
        SceneType.LANDSCAPE -> listOf(TIP_HORIZON, TIP_EYES_THIRDS)
        SceneType.INDOOR -> listOf(TIP_NIGHT_STATIC, TIP_EXPOSURE_BASICS)
        SceneType.ACTION -> listOf(TIP_PROTECT_HIGHLIGHT, TIP_EXPOSURE_BASICS)
        SceneType.GENERAL -> listOf(TIP_EXPOSURE_BASICS, TIP_PROTECT_HIGHLIGHT)
    }

    /**
     * 场景 → 可以补进兜底池的技法分类（**主判据**）。
     *
     * - **食物没有自己的 `SceneType`**。`food_*` 那几条的 `trigger.scenes` 本来写的就是
     *   `INDOOR`，所以食物分类挂在 INDOOR 上，而不是硬造一个场景值。
     * - **GENERAL 在这里留空**：往通用场景里塞 `BASIC` 会稀释「先做稳基本功」这条主张
     *   （`TechniqueMatcher` 的起步包出于同样的理由只从 BASIC 取）。通用场景靠下面
     *   那条「技法自己声明过这个场景」的从判据取候选，而不是靠分类硬塞。
     *
     * `STREET` / `MACRO` / `PARAMS` / `PITFALL` 在 `SceneType` 里没有同名取值，
     * 这几类靠 [fallbackCandidates] 的从判据（技法自己的 `trigger.scenes`）进来。
     */
    private fun sceneCategories(scene: SceneType): Set<TechCategory> = when (scene) {
        SceneType.NIGHT -> setOf(TechCategory.NIGHT)
        SceneType.BACKLIT -> setOf(TechCategory.LIGHT, TechCategory.SILHOUETTE)
        SceneType.PORTRAIT -> setOf(TechCategory.PORTRAIT)
        SceneType.LANDSCAPE -> setOf(TechCategory.LANDSCAPE)
        SceneType.INDOOR -> setOf(TechCategory.INDOOR, TechCategory.FOOD)
        SceneType.ACTION -> setOf(TechCategory.ACTION)
        SceneType.GENERAL -> emptySet()
    }

    /**
     * 兜底轮播池：**精选两条保底 + 当前窗口的库内候选**。
     *
     * 顺序固定为「精选在前、库内按 id 字典序在后」，且窗口只按 `nowMs` 步进 ——
     * 这是测试能钉住池子的前提：排序一沾上哈希序，今天绿的用例明天就会随机红。
     *
     * `internal` 而不是 `private`：轮换之后「池子有哪些 id」不再能从出牌面反推
     * （一个周期只有 6 个 tick，8 条的池子每周期只能播到 6 条），测试必须直接读池子
     * 才能断言"上限 8 条""精选在最前""窗口真的在滑"。
     *
     * 生产侧有两个调用点，**都在这一份池子上**：
     * R10（Android 的兜底轮播）与 [TechniqueTipFallback]（iOS 在 `ScoreBoard`
     * 没有建议时的兜底）。iOS 那边刻意不另写池子 —— 两套池子必然分叉，
     * 而分叉只表现为"两端教的东西不一样"，不会有任何报错。
     *
     * @param nowMs 当前时刻。同一个 `nowMs` 必然给出同一个池子（纯函数）。
     */
    internal fun fallbackPool(scene: SceneType, nowMs: Long): List<String> {
        val windows = FALLBACK_WINDOWS[scene].orEmpty()
        if (windows.isEmpty()) return emptyList()
        return windows[poolCycle(nowMs).mod(windows.size.toLong()).toInt()]
    }

    /** 当前处在第几个轮播周期。 */
    private fun poolCycle(nowMs: Long): Long = nowMs / FALLBACK_ROTATE_MS

    /**
     * 每个场景的**全部窗口**，按周期序号索引（`窗口序号 = poolCycle mod 窗口数`）。
     *
     * 为什么不按 `(scene, cycle)` 做可变缓存：窗口数是固定的（= 候选数 / gcd(候选数, 窗口大小)），
     * 把整张表一次算出来之后，`fallbackPool` 就退化成一次数组下标 ——
     * 每 tick 零分配、零锁、无并发问题。可变缓存反而要处理"遍历时被改"和"表无限增长"。
     *
     * 表在第一次访问时算一次，之后只读。
     */
    private val FALLBACK_WINDOWS: Map<SceneType, List<List<String>>> by lazy {
        SceneType.values().associateWith { buildFallbackWindows(it) }
    }

    private fun buildFallbackWindows(scene: SceneType): List<List<String>> {
        val curated = curatedFallback(scene)
        val candidates = fallbackCandidates(scene)
        if (candidates.isEmpty()) return listOf(curated)

        val windows = ArrayList<List<String>>(candidates.size)
        var offset = 0
        do {
            val rotated = candidates.drop(offset) + candidates.take(offset)
            windows.add(curated + rotated.take(FALLBACK_LIBRARY_LIMIT))
            offset = (offset + FALLBACK_LIBRARY_LIMIT) % candidates.size
        } while (offset != 0 && windows.size < candidates.size)
        return windows
    }

    /**
     * 某场景的库内候选（**已排好序、已剔除、跨所有窗口共用**）。
     *
     * 入选条件是两个判据取并集：
     * 1. 主判据：分类对得上 （[sceneCategories]）；
     * 2. 从判据：这条技法自己的 `trigger.scenes` 里写了当前场景。
     *
     * 为什么需要从判据：`PARAMS` / `PITFALL` / `STREET` / `MACRO` 四类在 `SceneType` 里
     * 没有同名取值，只看分类永远进不了池 —— 批次 C 里「光比大就开 HDR」「拍前扫一遍四条边」
     * 这些就会变成纯摆设。而技法自己的 `trigger.scenes` 是**它自己声明过的适用场景**
     * （例如 `params_portrait_mode_distance` 写的就是 PORTRAIT），比我在这里替它猜一个
     * 分类映射准确。
     *
     * 三条剔除：仅浏览（缺的是信号，推了也验证不了）、自拍（这里按后置口径，前置的放开
     * 在 `pushEligibleIds` 那侧做）、运镜（录像页的事，进图片页的兜底池是错的）。
     */
    private fun fallbackCandidates(scene: SceneType): List<String> {
        val categories = sceneCategories(scene)
        val curated = curatedFallback(scene).toHashSet()
        val ignored = TechniqueBookV15.BROWSE_ONLY_IDS + TechniqueBookV15.SELFIE_IDS

        val ids = ArrayList<String>()
        for (technique in TechniqueBookV15.combined) {
            if (technique.category == TechCategory.CAMERA_MOVE) continue
            if (technique.category !in categories && scene !in technique.trigger.scenes) continue
            if (technique.id in ignored || technique.id in curated) continue
            ids.add(technique.id)
        }
        return ids.distinct().sorted()
    }

    /**
     * 兜底文案：精选表优先，其次技法库的 [Technique.oneLiner]。
     *
     * 为什么用 `oneLiner` 而不是 `title`：R10 的卡片是「动作 + 为什么」两段式，
     * 第一段必须是**一个能立刻做的动作**。`title` 是带判断力的短标题
     * （「手肘弯出角，人瘦一圈」），`oneLiner` 才是口诀（「手肘别贴着腰」）。
     *
     * 返回空串 = 这条候选解析不出文案。调用方必须**跳过**它，绝不允许推出空卡片。
     *
     * `internal` 而不是 `private`：[TechniqueTipFallback]（iOS 兜底）取文案也走这里。
     * 让它自己写一份 id → 文案的映射就是第二份实现 —— 精选表与 `oneLiner` 的取舍
     * 会立刻分叉，而且分叉只表现为"两端口诀措辞不同"，不会报错。
     */
    internal fun fallbackText(id: String): String {
        FALLBACK_TEXT[id]?.let { return it }
        val technique = TechniqueBookV15.byId[id] ?: return ""
        return technique.oneLiner
    }

    /**
     * 某个 id 是否属于**精选话术**（[FALLBACK_TEXT] 的键）。
     *
     * 存在的唯一理由是给 [TechniqueTipFallback] 用：iOS 兜底要**剔掉精选**再轮换
     * （精选是 Android R10 的保底句，iOS 已有 `ScoreBoard` 的分数建议，不该退化成那几句）。
     * 这里只回答"是不是"，不把整张表交出去 —— 表本身仍是引擎的私有实现，
     * 外部拿到键集就会有人拿它拼第二套池子。
     */
    internal fun isCuratedFallbackText(id: String): Boolean = id in FALLBACK_TEXT

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
                    val pool = fallbackPool(s.scene, now)
                    if (pool.isEmpty()) null
                    else {
                        val id = pool[((now / SchedulerConfig.GUIDE_INTERVAL_MS).toInt()).mod(pool.size)]
                        // 解析不出文案就这一拍不出牌：宁可空着，也不推一张没有动作的空卡片
                        if (fallbackText(id).isEmpty()) null else id
                    }
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
     * - R10 的**整个兜底池并集**（精选 7 条 + 库内候选）。它不能再只写
     *   `FALLBACK_TEXT.keys`：池子从技法库取之后，R10 可以吐出任意一条库内候选，
     *   清单漏登记就会被 `TechniqueBookV15Test.emittedIdsStayWithinTheDeclaredSet` 抓住。
     */
    private val EMITTABLE_TIP_IDS: Set<String> by lazy {
        setOf(
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
        ) + MOVE_TEXT.keys + ALL_FALLBACK_POOL_IDS
    }

    /**
     * 全部场景、**全部窗口**兜底池的并集。
     *
     * 必须是并集而不是"当前窗口"：窗口随 `nowMs` 滑动，某个时刻会推出另一批 id。
     * 若声明集合只覆盖一个窗口，「引擎发射的 id 必须都在声明集合内」那条断言会
     * 在**特定时刻**红，而且极难复现（取决于跑测试的那一帧 `now`）。
     */
    private val ALL_FALLBACK_POOL_IDS: Set<String> by lazy {
        SceneType.values().flatMap { scene ->
            curatedFallback(scene) + fallbackCandidates(scene)
        }.toSet()
    }

    /**
     * 求值。
     *
     * @param availableTipIds 技法库里真实存在的 id 集合。传 null 表示不做过滤（测试用）；
     *        生产环境传 `TechniqueBookV15.pushEligibleIds(includeSelfie = 是否前置)` ——
     *        后置时已剔除自拍与仅浏览条目，前置时只剔除仅浏览条目；池里没有的 id 会静默跳过。
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
            return fallbackText(tipId).ifEmpty { rule.action(signals) }
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
