package com.shottuner.app.knowledge

/**
 * 运镜方案池。
 *
 * ## 为什么单独有这个文件
 * 现有的 `video/VideoRecommender` 给的 `plan.tips` 讲的是**参数与器材**（快门跟帧率、三脚架、
 * 机位高度、背景距离）。用户在录像页已经看到那些话了，如果运镜提示再说一遍"两腿微屈、贴身体"，
 * 就是同一件事讲两次，非常吵。
 *
 * 这里做的是**互补映射**：每个录像意图只在 `plan.tips` **没有覆盖到**的那几条身体动作里出牌。
 *
 * 池为空时 [nextMove] 返回 null，由调度器回落到 R10 兜底池，不是崩溃。
 */
object VideoMovePlanner {

    // 运镜条目 id 常量。抽出来是为了让「拼错 id」在编译期就暴露，
    // 而不是等到运行期发现某条运镜永远轮不到（会被 availableTipIds 静默过滤）。
    private const val TECH_MOVE_ELBOW = "move_elbow_tucked"
    private const val TECH_MOVE_SMALL_STEPS = "move_small_steps"
    private const val TECH_MOVE_PUSH_IN_SLOW = "move_push_in_slow"
    private const val TECH_MOVE_HOLD_ENDS = "move_hold_ends"
    private const val TECH_MOVE_ORBIT_30DEG = "move_orbit_30deg"
    private const val TECH_MOVE_WALK_NOT_ZOOM = "move_walk_not_zoom"
    private const val TECH_MOVE_FIXED_SUPPORT = "move_fixed_support"

    /**
     * 8 个 [com.shottuner.app.video.VideoIntent] 对应的运镜池。
     * 顺序与 enum 声明顺序严格一致（WALK, TALK, SPORT, CONCERT, CINEMA, SLOWMO, TIMELAPSE, SCAN）。
     * 每个 id 都必须在 [TechniqueBookV15.list] 中存在且分类为 [TechCategory.CAMERA_MOVE]
     * （由 `TechniqueBookV15Test` 断言）。
     *
     * ## 为什么池里没有 `move_no_whip_pan`
     * 它被判定为「仅浏览」（见 [TechniqueBookV15.BROWSE_ONLY_IDS]）：本 App 拿不到角速度信号，
     * 感知不到「在甩镜」。把它留在池里只有一个后果 —— 卡片点开是一句用户无法验证的话。
     * 换成了 [TECH_MOVE_ELBOW] 这类「有身体动作可依」的条目。
     */
    private val POOLS: List<List<String>> = listOf(
        // WALK：plan.tips 已讲「两腿微屈、贴身体」和「头尾停 2 秒」
        listOf(TECH_MOVE_FIXED_SUPPORT, TECH_MOVE_PUSH_IN_SLOW),
        // TALK：plan.tips 已讲三脚架 / 机位高度 / 背景距离
        listOf(TECH_MOVE_FIXED_SUPPORT, TECH_MOVE_ELBOW),
        // SPORT：plan.tips 已讲快门 1/250、提前半秒按、身体转向
        listOf(TECH_MOVE_ORBIT_30DEG, TECH_MOVE_HOLD_ENDS),
        // CONCERT：以参数为主
        listOf(TECH_MOVE_ELBOW, TECH_MOVE_SMALL_STEPS),
        // CINEMA：三脚架 / 缓慢平移
        listOf(TECH_MOVE_PUSH_IN_SLOW, TECH_MOVE_ORBIT_30DEG),
        // SLOWMO：快门 / 帧率
        listOf(TECH_MOVE_HOLD_ENDS, TECH_MOVE_FIXED_SUPPORT),
        // TIMELAPSE：固定机位是唯一正解，兜底给「别用数码变焦」
        listOf(TECH_MOVE_FIXED_SUPPORT, TECH_MOVE_WALK_NOT_ZOOM),
        // SCAN：平铺 / 对焦
        listOf(TECH_MOVE_FIXED_SUPPORT, TECH_MOVE_ELBOW)
    )

    /** 某个录像意图可用的运镜池；序号越界返回空列表。 */
    fun poolFor(intentOrdinal: Int): List<String> {
        if (intentOrdinal !in POOLS.indices) return emptyList()
        return POOLS[intentOrdinal]
    }

    /**
     * 在池里挑一条。
     *
     * @param rotation 轮转序号（调度器用 now / 8s 或自增 cycle 传入），保证"同输入同输出"
     */
    fun nextMove(intentOrdinal: Int, rotation: Int): String? {
        val pool = poolFor(intentOrdinal)
        if (pool.isEmpty()) return null
        val index = ((rotation % pool.size) + pool.size) % pool.size
        return pool[index]
    }

    /** 全部运镜 id（去重），供测试断言没有悬空引用。 */
    fun allMoveIds(): List<String> = POOLS.flatten().distinct().sorted()

    /** 意图数量（= 池数量）。 */
    val intentCount: Int get() = POOLS.size
}
