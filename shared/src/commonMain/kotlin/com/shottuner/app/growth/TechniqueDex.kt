package com.shottuner.app.growth

import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.knowledge.PhotographyBook
import com.shottuner.app.knowledge.Technique
import com.shottuner.app.knowledge.TechniqueBookV15

/**
 * 技法图鉴（G3「未完成的册子」）——**纯 JVM**。
 *
 * 本文件不 import 任何 `android.*`：83 条索引、维度归属、解锁判定、日期格式化
 * 全部能在 `src/test` 下直接跑。真正的读写在 [DexStore]，那个才碰 Android。
 * （分家理由与 `ScoreHistory` / `ScoreHistoryStore` 一致，这里不重复。）
 *
 * ## 83 这个数是怎么来的
 * `PhotographyBook.all = legacy(39) + TechniqueBookV15.list(44)`，
 * 全部遍历过一遍，**没有重复 id**（`DexStoreTest.idsAreUniqueAcrossSources` 守着）。
 * 注意 `TechniqueBookV15.byId` 是 `associateBy` 建出来的 Map —— 万一将来真的
 * 出现重复 id，那个 Map 会**静默丢掉一条**（后者覆盖前者），所以排重不能依赖它，
 * 必须像本文件这样直接数原始列表。
 *
 * ## 顺序
 * [all] 严格按 `PhotographyBook.all` 的顺序（39 条老库在前，44 条 v15 在后），
 * 不改不排。图鉴是"册子"，页码要稳定：加新条目只会追加到末尾，不会让读者
 * 昨天看的第 12 条今天变成第 13 条。
 *
 * ## 解锁的上限（重要，产品侧必须知道）
 * 解锁要同时满足「照做」与「达标」两个条件（见 [shouldUnlock]），而"照做"依赖
 * G1 的 `MissionEngine` 派发任务 —— 目前 `ScoreBoard` 只产得出 7 个 tipId：
 * `landscape_horizon_level` / `basic_protect_highlights` / `light_white_add_black_minus` /
 * `portrait_backlit_face_first` / `portrait_eyes_thirds` / `portrait_headroom` /
 * `move_elbow_tucked`。
 * 也就是说**当前可解锁的只有 7 条**，首页写「已解锁 12 / 83」在今天的工程上是走不到的。
 * 图鉴入口想做到 12，得先让 G1 的 tip 词表扩到 12 条以上 —— 这是数据层说不了的，
 * 但把它写在这里，免得有人对着 83 干瞪眼。
 */
object TechniqueDex {

    /** `TechniqueBookV15` 那 44 条的来源标记。 */
    const val SOURCE_V15 = "v15"

    /** `PhotographyBook.legacy` 那 39 条的来源标记。 */
    const val SOURCE_BOOK = "book"

    private val v15Ids: Set<String> by lazy {
        TechniqueBookV15.list.mapTo(HashSet(64)) { it.id }
    }

    private val entries: List<DexEntry> by lazy {
        PhotographyBook.all.map { technique ->
            DexEntry(
                id = technique.id,
                title = technique.title,
                body = technique.whenTo,
                dimension = DIMENSION_OF[technique.id],
                source = if (v15Ids.contains(technique.id)) SOURCE_V15 else SOURCE_BOOK
            )
        }
    }

    private val index: Map<String, DexEntry> by lazy {
        // 键是遍历原始列表得来的，不是 associateBy —— 万一将来出现重复 id，
        // 这里要让事情暴露出来（见 idsAreUniqueAcrossSources），而不是静默吞掉一条
        val map = HashMap<String, DexEntry>(128)
        for (entry in entries) {
            if (map.put(entry.id, entry) != null) {
                throw IllegalStateException("技法图鉴出现重复 id：${entry.id}")
            }
        }
        map
    }

    /** 83 条全量，顺序与知识库一致。 */
    fun all(): List<DexEntry> = entries

    /** 按 id 查；查不到返回 null（tipId 为空串或不在库里都会走到这里）。 */
    fun byId(id: String): DexEntry? = index[id]

    /** 某一维下的全部条目；`null` 维度的纯知识条目不在任何一组里。 */
    fun byDimension(dimension: ScoreDimension): List<DexEntry> =
        entries.filter { it.dimension == dimension }

    /**
     * 该维度下第一条尚未解锁的条目。维度通道解锁（`DexStore.tryUnlockByDimension`）
     * 用它来选目标。
     *
     * 顺序与 [all] 一致（知识库原顺序），保证「同一个维度反复达标时，先解锁哪一条、
     * 再解锁哪一条」是可预测的，用户不会觉得顺序在乱跳。
     */
    fun nextUnlockable(dimension: ScoreDimension, unlocked: Set<String>): DexEntry? =
        entries.firstOrNull { it.dimension == dimension && it.id !in unlocked }

    /** 按来源分组，用于排重与统计。 */
    fun bySource(source: String): List<DexEntry> = entries.filter { it.source == source }

    /** 总条数。图鉴的分母；首页那个「/ 83」就用它，别写死数字。 */
    fun total(): Int = entries.size

    /** 全部 id（顺序同 [all]）。 */
    fun ids(): List<String> = entries.map { it.id }

    /**
     * 知识库里存在、但 [DexEntry.dimension] 没给归类的条目。
     *
     * 新加了一条技法却忘了在 `DIMENSION_OF` 里登记时，它会变成 null 从而永远
     * 解锁不了 —— 这种"静默少一条"是最难查的 bug。所以不做成"缺了就崩"，
     * 而是暴露成这个查询，交给 `DexStoreTest.everyTechniqueHasADimensionEntry` 断言为空。
     * **数据层不许崩，但也不许悄悄丢东西。**
     */
    fun unmappedIds(): List<String> =
        PhotographyBook.all.map(Technique::id).filter { !DIMENSION_OF.containsKey(it) }

    /**
     * 每条技法归属哪一维度。
     *
     * 这是**本文件里唯一的人工判断**，83 条逐条写死，理由如下：
     * - 按内容分，不按分类分。`landscape_golden_blue_hour` 在 LANDSCAPE 类里，
     *   但它讲的是光比，归 EXPOSURE；`landscape_horizon_thirds` 讲的是地平线放哪条线，
     *   归 COMPOSITION。按分类一刀切会让 `byDimension` 退化成第二个分类视图，没有意义。
     * - 能测到才归。只归那些真做了之后 `ScoreBoard` 对应维度**会长分**的条目。
     *   `street_vertical_lines` 讲"竖边拍直"，长分的是水平这一维，所以是 LEVEL。
     * - 测不到的留 null。自拍 / 运镜 / 参数 / 避坑这四类是"知道就好"的知识，
     *   没有对应的画面信号，全部为 null。
     *
     * 例外：`move_elbow_tucked` 虽在 CAMERA_MOVE 类里，但它是 `ScoreBoard` 的
     * 清晰度建议（`TIP_STEADY`），是真能解锁的，因此归 CLARITY。
     *
     * 值全部手写而不推导，是为了让"某条归哪一维"变成**可以逐行评审的事实**，
     * 而不是散落在 if / when 里的规则。
     */
    private val DIMENSION_OF: Map<String, ScoreDimension?> = mapOf(
        // ── PhotographyBook.legacy（39 条）────────────────────────────────────
        "portrait_backlit_face_first" to ScoreDimension.EXPOSURE,
        "portrait_window_45" to ScoreDimension.EXPOSURE,
        "portrait_eyes_thirds" to ScoreDimension.COMPOSITION,
        "portrait_low_angle_full" to ScoreDimension.COMPOSITION,
        "portrait_background_layers" to ScoreDimension.COMPOSITION,
        "portrait_wide_face" to ScoreDimension.COMPOSITION,
        "portrait_hands_busy" to ScoreDimension.COMPOSITION,
        "landscape_golden_blue_hour" to ScoreDimension.EXPOSURE,
        "landscape_three_layers" to ScoreDimension.COMPOSITION,
        "landscape_leading_lines" to ScoreDimension.COMPOSITION,
        "landscape_horizon_thirds" to ScoreDimension.COMPOSITION,
        "landscape_polarizer" to ScoreDimension.EXPOSURE,
        "landscape_long_exposure_water" to ScoreDimension.EXPOSURE,
        "night_handheld_static" to ScoreDimension.CLARITY,
        "night_car_trails" to ScoreDimension.CLARITY,
        "night_blue_hour" to ScoreDimension.EXPOSURE,
        "night_portrait_no_direct_flash" to ScoreDimension.EXPOSURE,
        "food_angle_45_top" to ScoreDimension.COMPOSITION,
        "food_side_light" to ScoreDimension.EXPOSURE,
        "food_no_flash_soft" to ScoreDimension.EXPOSURE,
        "street_compose_then_wait" to ScoreDimension.COMPOSITION,
        "street_peak_action" to ScoreDimension.CLARITY,
        "street_get_closer" to ScoreDimension.COMPOSITION,
        "action_freeze_250" to ScoreDimension.CLARITY,
        "action_get_low" to ScoreDimension.COMPOSITION,
        "action_prefocus_burst" to ScoreDimension.CLARITY,
        "macro_close_steady_manual" to ScoreDimension.CLARITY,
        "macro_backlit_translucent" to ScoreDimension.EXPOSURE,
        "silhouette_meter_sky" to ScoreDimension.EXPOSURE,
        "silhouette_clean_shape" to ScoreDimension.COMPOSITION,
        "light_hard_soft" to ScoreDimension.EXPOSURE,
        "light_side_backlit" to ScoreDimension.EXPOSURE,
        "light_white_add_black_minus" to ScoreDimension.EXPOSURE,
        "indoor_one_color_temp" to ScoreDimension.EXPOSURE,
        "indoor_lock_white_balance" to ScoreDimension.EXPOSURE,
        "basic_shutter_then_iso" to ScoreDimension.EXPOSURE,
        "basic_protect_highlights" to ScoreDimension.EXPOSURE,
        "basic_clean_lens" to ScoreDimension.CLARITY,
        "basic_ev_order" to ScoreDimension.EXPOSURE,

        // ── TechniqueBookV15（44 条）─────────────────────────────────────────
        // 自拍 5 条：本 App 只有后摄通路，纯浏览
        "selfie_arm_extended" to null,
        "selfie_hold_above_eye" to null,
        "selfie_tongue_palate" to null,
        "selfie_eyes_to_lens" to null,
        "selfie_side_light" to null,

        // 运镜 8 条：讲的是录像，四维是照片口径。
        // 唯一例外是 move_elbow_tucked —— 它是 ScoreBoard 的清晰度建议
        "move_elbow_tucked" to ScoreDimension.CLARITY,
        "move_small_steps" to null,
        "move_push_in_slow" to null,
        "move_hold_ends" to null,
        "move_orbit_30deg" to null,
        "move_no_whip_pan" to null,
        "move_walk_not_zoom" to null,
        "move_fixed_support" to null,

        // 参数 3 条：起手参数，不是画面结果
        "params_portrait_plus_third" to null,
        "params_white_balance_mood" to null,
        "params_food_plus_third" to null,

        // 避坑 4 条：命中的是设置与习惯，画面上测不到
        "pitfall_grid_on" to null,
        "pitfall_auto_everything" to null,
        "pitfall_beauty_overdo" to null,
        "pitfall_filter_overdo" to null,

        "portrait_headroom" to ScoreDimension.COMPOSITION,
        "portrait_feet_anchor" to ScoreDimension.COMPOSITION,
        "portrait_half_body" to ScoreDimension.COMPOSITION,
        "portrait_side_45_body" to ScoreDimension.COMPOSITION,
        "portrait_phone_slightly_high" to ScoreDimension.COMPOSITION,
        "portrait_chin_down" to ScoreDimension.COMPOSITION,
        "portrait_catch_motion" to ScoreDimension.CLARITY,
        "portrait_group_low_wide" to ScoreDimension.COMPOSITION,
        "portrait_negative_space" to ScoreDimension.COMPOSITION,
        "portrait_diagonal_body" to ScoreDimension.COMPOSITION,
        "street_vertical_lines" to ScoreDimension.LEVEL,
        "street_doorway_frame" to ScoreDimension.COMPOSITION,
        "landscape_horizon_level" to ScoreDimension.LEVEL,
        "landscape_ultrawide_center" to ScoreDimension.COMPOSITION,
        "landscape_low_ground" to ScoreDimension.COMPOSITION,
        "landscape_reflection" to ScoreDimension.COMPOSITION,
        "night_protect_highlights" to ScoreDimension.EXPOSURE,
        "night_iso_ceiling" to ScoreDimension.CLARITY,
        "night_slow_shutter" to ScoreDimension.CLARITY,
        "light_avoid_noon" to ScoreDimension.EXPOSURE,
        "light_front_light_clear" to ScoreDimension.EXPOSURE,
        "light_overcast_soft" to ScoreDimension.EXPOSURE,
        "food_tele_compress" to ScoreDimension.COMPOSITION,
        "food_steam_backlight" to ScoreDimension.EXPOSURE
    )
}

/** 解锁阈值。改口径只改这一个地方。 */
object DexThresholds {

    /**
     * 得分要达到维度满分的百分之多少才解锁（连续分维度：水平 / 构图 / 清晰）。
     *
     * 曝光维度不在这里判，见 [EXPOSURE_PASS] —— 它的分值是阶梯，百分比会卡在空档。
     */
    const val PASS_PERCENT = 80

    /**
     * 曝光维度的**绝对**达标线。
     *
     * `ScoreBoard.exposure` 是「基线 30，三项各扣 10，严重溢出硬顶 10」的阶梯，
     * 只会产出 30 / 20 / 10 / 0 四档。`80% × 30 = 24` 落在 20 与 30 之间，
     * 那个分值**根本不存在**，用百分比判会让曝光类技法永久锁死。
     * 所以曝光单独定 20：三项扣分里只扣了一项，就算「基本掌握了曝光」。
     * 满分 30（三项全不扣）当然也达标。
     */
    const val EXPOSURE_PASS = 20

    /**
     * 某维度满分 [dimMax] 对应的最低通过分。
     *
     * 用整数算，不用浮点：`25 * 0.8f = 20.0f` 在边界上可能落成 `19.999999f`，
     * 与 20 比较就变成 false。整数运算没有这个雷。
     */
    fun passScore(dimension: ScoreDimension, dimMax: Int): Int {
        if (dimMax <= 0) return Int.MAX_VALUE
        return if (dimension == ScoreDimension.EXPOSURE) {
            EXPOSURE_PASS
        } else {
            (dimMax * PASS_PERCENT + 99) / 100
        }
    }
}

/**
 * 是否该解锁一条技法。**纯 JVM**，签名里没有 `Context`，可直接单测。
 *
 * ## 为什么要两个条件
 * 只认"照做"会被刷：站在原地晃两下就能把 83 条推平，册子当天集满，第二天没人再来。
 * 只认"得分"也不行：分是取景时的连续量，用户没动手也可能撞上高分。
 * 两个都要，解锁才同时代表"我做过"和"我做成了"。
 *
 * ## 三个条件的顺序
 * 先查身份（tipId / id 在不在库里），再查去重（已解锁 / 今天已解锁过），最后才查分。
 * 前两组是纯记账，不需要任何传感器数据；把最贵、最依赖当帧状态的判定放最后，
 * 绝大多数调用在前两步就短路了。
 *
 * @param tipId 完成的技法 id（`MissionTask.tipId`）。空串 = `ScoreBoard` 全满分时的
 *              "这张没什么可挑的"，那句话不对应任何技法
 * @param dimension 该技法归属的维度；`null` = 纯知识条目，永远不解锁
 * @param dimScore 该维度本次得分；`< 0` = 该维度未测（`ScoreBoard` 的"未测"约定）
 * @param dimMax 该维度满分。取 `ScoreBoard.MAX_*`；`<= 0` 一律不解锁
 * @param alreadyUnlocked 这条是否已经解锁过
 * @param lastUnlockDate 上次解锁的日期（`yyyyMMdd`）；从未解锁过传 null
 * @param today 今天的日期（`yyyyMMdd`）
 * @return true = 本次该解锁（调用方据此放动效）
 */
fun shouldUnlock(
    tipId: String,
    dimension: ScoreDimension?,
    dimScore: Int,
    dimMax: Int,
    alreadyUnlocked: Boolean,
    lastUnlockDate: String?,
    today: String
): Boolean {
    // 1. 没有 tipId → 没有"照做"这件事。全满分那句话不解锁任何条目
    if (tipId.isBlank()) return false

    // 2. tipId 必须在图鉴里。解锁一个不存在的条目会让计数与 83 对不上
    if (TechniqueDex.byId(tipId) == null) return false

    // 3. 已解锁过 → 不重复计数，也不重复放动效
    if (alreadyUnlocked) return false

    // 4. 日期拿不到 → 宁可不解锁，也不让"每天一条"这条规则失效
    if (today.isBlank()) return false

    // 5. 同一天最多解锁一条：图鉴是长线钩子，一天推平 83 条等于没有钩子
    if (!lastUnlockDate.isNullOrBlank() && lastUnlockDate == today) return false

    // 6. 纯知识条目（自拍 / 运镜 / 参数 / 避坑）：没有可判定的维度，不能解锁
    if (dimension == null) return false

    // 7. 维度未测 → 不给分也不解锁。「没测到」≠「得 0 分」
    if (dimScore < 0) return false

    // 8. 满分非法：宁可不解锁，也不让除零/怪值变成一次误解锁
    if (dimMax <= 0) return false

    // 9. 得分达标（曝光维度走绝对分 20，其余走 80%，见 DexThresholds）
    return dimScore >= DexThresholds.passScore(dimension, dimMax)
}
