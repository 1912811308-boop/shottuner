package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType
import kotlin.math.roundToInt

/**
 * 把当前画面信号和技法库接起来。
 *
 * 规则简单但硬：数值条件不满足直接出局，场景只加分；同一分类最多出一条，
 * 保证三张卡从不同角度看同一个画面，而不是把同一类话重复三遍。
 *
 * ## v1.5 改动（三条硬约束都落在这里）
 * 1. **自拍不进推荐池**：本 App 只有后摄通路（`CameraEngine` 只用 `DEFAULT_BACK_CAMERA`），
 *    后摄拍人一样会命中 `PORTRAIT + minSkinRatio`，推「手臂伸直自拍」就荒谬了；
 * 2. **仅浏览条目不进推荐池**：见 [TechniqueBookV15.BROWSE_ONLY_IDS]；
 * 3. **运镜按 tab 隔离**：拍照页不出现 `CAMERA_MOVE`，录像页只出现 `CAMERA_MOVE` + 通用条目。
 *    这三条只在 [poolFor] 里判断一次，其他入口一律走它，避免某个入口漏了条件。
 */
object TechniqueMatcher {

    private const val SCENE_BONUS = 40
    private const val CONDITION_BONUS = 8

    /**
     * 某页签可用的候选条目。
     *
     * @param tab [PromptEngine.TAB_PHOTO] / [PromptEngine.TAB_VIDEO]
     * @param scene 当前场景。**本版不按它收窄候选**，原因见下，参数保留是为了调用方能
     *        在不改签名的前提下把上下文带进来（`suggest` 已经在传）。
     * @return 已经剔除「自拍」「仅浏览」「跨页」三类条目的候选池
     *
     * ## 为什么 `scene` 不参与过滤
     * 「场景只负责加分，不做硬过滤」是这套匹配器从 1.0 起就有的约定：通用条目不该因为
     * 当前场景被判成 GENERAL 而整条消失，那是把「不确定」当成了「不适用」。
     * 想收窄请改 `SCENE_BONUS` 的权重，不要在这里加 if —— 加了会让
     * `typicalScenesGetThreeDistinctCategories` 那类用例变成在测排序巧合。
     */
    @Suppress("UNUSED_PARAMETER")
    fun poolFor(tab: Int, scene: SceneType? = null): List<Technique> {
        val browseOnly = TechniqueBookV15.BROWSE_ONLY_IDS
        val selfie = TechniqueBookV15.SELFIE_IDS
        return TechniqueBookV15.combined.filter { tech ->
            if (tech.id in selfie || tech.id in browseOnly) return@filter false
            val isMove = tech.category == TechCategory.CAMERA_MOVE
            if (tab == PromptEngine.TAB_VIDEO) {
                // 录像页：运镜 + 通用条目（不绑场景的），别把「人像收下巴」塞给录像的人
                isMove || tech.trigger.scenes.isEmpty()
            } else {
                !isMove
            }
        }
    }

    /** 按当前画面挑最该看的三条。hasStats=false 时返回新手起步包。 */
    fun suggest(
        scene: SceneType,
        signals: TechSignals,
        limit: Int = 3,
        tab: Int = PromptEngine.TAB_PHOTO
    ): List<Technique> {
        if (limit <= 0) return emptyList()

        if (!signals.hasStats) {
            // 起步包只从老库取：新 44 条没有 BASIC 分类，混进来只会稀释「先做稳基本功」这条主张
            return PhotographyBook.all
                .filter { it.category == TechCategory.BASIC }
                .sortedWith(
                    compareByDescending<Technique> { it.trigger.weight }
                        .thenBy { it.id }
                )
                .take(limit)
        }

        val ranked = poolFor(tab, scene)
            .mapNotNull { tech ->
                val score = scoreOf(tech, scene, signals) ?: return@mapNotNull null
                tech to score
            }
            .sortedWith(
                compareByDescending<Pair<Technique, Int>> { it.second }
                    .thenByDescending { it.first.trigger.weight }
                    .thenBy { it.first.id }
            )

        val picked = ArrayList<Technique>(limit)
        val usedCategories = HashSet<TechCategory>()
        for ((tech, _) in ranked) {
            if (!usedCategories.add(tech.category)) continue
            picked.add(tech)
            if (picked.size >= limit) break
        }
        return picked
    }

    /** 某分类下的全部技法，按基础权重降序。技法页的分类浏览用它，所以取合并库。 */
    fun byCategory(category: TechCategory): List<Technique> =
        TechniqueBookV15.combined
            .filter { it.category == category }
            .sortedWith(
                compareByDescending<Technique> { it.trigger.weight }
                    .thenBy { it.id }
            )

    /**
     * 命中原因，一句话，用于卡片副标题。
     *
     * 每条数值条件都可能在 [TechTrigger] 里出现，这里必须逐个覆盖 ——
     * 漏掉的条件不会报错，只会让卡片退化成「适合当前画面的通用提醒」，
     * 用户看不出为什么给他看这条。`TechniqueMatcherTest` 逐分类守着这件事。
     */
    fun reasonFor(tech: Technique, signals: TechSignals): String {
        val trigger = tech.trigger
        val reasons = ArrayList<String>(4)

        val maxLuma = trigger.maxLuma
        if (maxLuma != null && signals.meanLuma <= maxLuma) {
            reasons.add("画面偏暗 ${pct(signals.meanLuma)}%")
        }
        val minLuma = trigger.minLuma
        if (minLuma != null && signals.meanLuma >= minLuma) {
            reasons.add("亮度够 ${pct(signals.meanLuma)}%")
        }
        val minHighlight = trigger.minHighlightClip
        if (minHighlight != null && signals.highlightClip >= minHighlight) {
            reasons.add("有高光溢出 ${pct(signals.highlightClip)}%")
        }
        val minShadow = trigger.minShadowClip
        if (minShadow != null && signals.shadowClip >= minShadow) {
            reasons.add("暗部死黑 ${pct(signals.shadowClip)}%")
        }
        val maxHighlight = trigger.maxHighlightClip
        if (maxHighlight != null && signals.highlightClip <= maxHighlight) {
            reasons.add("高光还压得住")
        }
        val minRange = trigger.minDynamicRange
        if (minRange != null && signals.dynamicRange >= minRange) {
            reasons.add("明暗层次够用")
        }
        val maxRange = trigger.maxDynamicRange
        if (maxRange != null && signals.dynamicRange <= maxRange) {
            reasons.add("光比不大")
        }
        val minSaturation = trigger.minSaturation
        if (minSaturation != null && signals.saturation >= minSaturation) {
            reasons.add("颜色够饱和")
        }
        val minSkin = trigger.minSkinRatio
        if (minSkin != null && signals.skinRatio >= minSkin) {
            reasons.add("画面里有肤色")
        }
        val minEdge = trigger.minEdgeDensity
        if (minEdge != null && signals.edgeDensity >= minEdge) {
            reasons.add("细节纹理多")
        }
        val maxEdge = trigger.maxEdgeDensity
        if (maxEdge != null && signals.edgeDensity <= maxEdge) {
            reasons.add("背景够干净")
        }
        if (trigger.requiresTele && signals.hasTele) reasons.add("有长焦可用")
        if (trigger.requiresManual && signals.supportsManual) reasons.add("可手动曝光")
        if (trigger.requiresLongExposure && signals.supportsLongExposure) {
            reasons.add("能上长曝光")
        }

        if (reasons.isEmpty()) {
            return when (tech.category) {
                TechCategory.BASIC -> "先把这条基本功做稳"
                TechCategory.LIGHT -> "先判断现场光线性格"
                // ── v1.5 新增的 4 个分类 ──
                TechCategory.SELFIE -> "自拍专用的距离和机位"
                TechCategory.CAMERA_MOVE -> "运镜先解决稳，再谈好看"
                TechCategory.PARAMS -> "按这套起手参数去拨"
                TechCategory.PITFALL -> "这条是习惯问题，改一次长期受益"
                else -> "适合当前画面的通用提醒"
            }
        }
        return reasons.take(3).joinToString(" 且 ")
    }

    /**
     * 返回 null 表示该条被硬性条件淘汰。
     *
     * 可见性说明（KMP 搬迁后改过）：
     * 原本是 `internal` —— 单测要验 `minShadowClip` / `maxHighlightClip`
     * 这两个 v1.5 新条件的淘汰边界，而现在没有任何一条真实技法用到它们 ——
     * 只能拿合成条目直接打这个函数。为了可测放宽一档可见性，逻辑不动。
     *
     * ★ 搬迁到 `shared` 模块后**不能保持 internal**：Kotlin 的 `internal` 是
     *   **模块级**可见性，而单测还在 `app` 模块里 —— 跨模块访问不到，
     *   编译报 "Cannot access 'scoreOf': it is internal in 'TechniqueMatcher'"。
     *   所以这里放宽为 public。
     *   待办：T07 把这批单测迁进 `shared/src/commonTest/`（iOS 端复用同一份）之后，
     *   可以改回 `internal`。见 `docs/IOS-ARCH-EXEC.md` 的 T07。
     */
    fun scoreOf(tech: Technique, scene: SceneType, signals: TechSignals): Int? {
        val trigger = tech.trigger

        if (trigger.requiresTele && !signals.hasTele) return null
        if (trigger.requiresManual && !signals.supportsManual) return null
        if (trigger.requiresLongExposure && !signals.supportsLongExposure) return null

        var score = trigger.weight

        if (trigger.scenes.isNotEmpty() && trigger.scenes.contains(scene)) {
            score += SCENE_BONUS
        }

        val maxLuma = trigger.maxLuma
        if (maxLuma != null) {
            if (signals.meanLuma > maxLuma) return null
            score += CONDITION_BONUS
        }
        val minLuma = trigger.minLuma
        if (minLuma != null) {
            if (signals.meanLuma < minLuma) return null
            score += CONDITION_BONUS
        }
        val minHighlight = trigger.minHighlightClip
        if (minHighlight != null) {
            if (signals.highlightClip < minHighlight) return null
            score += CONDITION_BONUS
        }
        // v1.5：欠曝类条目（「白加黑减」等）靠它才真正可触发
        val minShadow = trigger.minShadowClip
        if (minShadow != null) {
            if (signals.shadowClip < minShadow) return null
            score += CONDITION_BONUS
        }
        // v1.5：过曝类条目（「先保高光」等）反过来，高光溢出太多时不该再谈「保住高光」
        val maxHighlight = trigger.maxHighlightClip
        if (maxHighlight != null) {
            if (signals.highlightClip > maxHighlight) return null
            score += CONDITION_BONUS
        }
        val minRange = trigger.minDynamicRange
        if (minRange != null) {
            if (signals.dynamicRange < minRange) return null
            score += CONDITION_BONUS
        }
        val maxRange = trigger.maxDynamicRange
        if (maxRange != null) {
            if (signals.dynamicRange > maxRange) return null
            score += CONDITION_BONUS
        }
        val minSaturation = trigger.minSaturation
        if (minSaturation != null) {
            if (signals.saturation < minSaturation) return null
            score += CONDITION_BONUS
        }
        val minSkin = trigger.minSkinRatio
        if (minSkin != null) {
            if (signals.skinRatio < minSkin) return null
            score += CONDITION_BONUS
        }
        val minEdge = trigger.minEdgeDensity
        if (minEdge != null) {
            if (signals.edgeDensity < minEdge) return null
            score += CONDITION_BONUS
        }
        val maxEdge = trigger.maxEdgeDensity
        if (maxEdge != null) {
            if (signals.edgeDensity > maxEdge) return null
            score += CONDITION_BONUS
        }

        return score
    }

    private fun pct(value: Float): Int = (value * 100f).roundToInt()
}
