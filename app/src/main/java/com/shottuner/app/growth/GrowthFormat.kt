package com.shottuner.app.growth

/**
 * 成长页的**纯展示逻辑**（G4「进步可见」—— Android 侧界面口径）。
 *
 * ## 这个文件为什么单独存在
 * `ScoreHistory` / `GrowthSummary` 里的分数字段全是 `Int?`，而界面必须把
 * 「未测」与「0 分」显示成两件完全不同的事。如果这段逻辑直接写进 `MainActivity`
 * 的 `setText(...)` 里，它就只能在真机上肉眼验收 —— 本仓没有 Robolectric，
 * 也不允许引第三方测试依赖。抽成纯 JVM 文件之后，「null 不印 0」这条铁律
 * 可以**机器验证**（见 `app/src/test/.../GrowthFormatTest.kt`）。
 *
 * ## 三条铁律（与 iOS `GrowthView.swift` 逐条对齐）
 *  1. **整卡总闸**：[GrowthSummary] 为 null（记录数不够 `MIN_RECORDS_FOR_GROWTH`）
 *     ⇒ 首末对比卡整张不显示。一堆 `--` 只会让用户以为功能坏了。
 *  2. **逐字段判**：`firstTotal` / `bestTotal` / `delta` 各自判空，
 *     **绝不**写 `if (bestTotal != null) 两个一起印` 那种一把兜住 ——
 *     将来 common 侧改口径（例如「只统计最近 90 天」）时这种蕴含关系会悄悄失效。
 *  3. **永不印无效的 0**：本文件里把 `Int?` 变成显示文案的路径**只有**
 *     [GrowthFormat.formatTotal] 与 [GrowthFormat.deltaText] 两条，
 *     两条都在函数体第一行处理 null。想让 null 变成 "0"，必须改这两个函数之一。
 *
 * ## 为什么这里没有 `?: 0`
 * `?: 0` 是本仓被打回过的那个 bug 的写法（成绩卡印假分数 / `hasTotal` 口径）。
 * 未测维度的 0 会被用户读成「这项我拍成了 0 分」，而真实含义是「这项没测到」。
 * 本文件里 `?:` 只出现在 `?: NO_VALUE` 这一种形态上。
 */
object GrowthFormat {

    /** 未测时显示的内容。**全角横线两根**，与 iOS `scoreValue` 的 `--` 完全一致。 */
    const val NO_VALUE: String = "--"

    /**
     * 折线图区块的标题。
     *
     * ★ 刻意**不写**「最近 30 天参考分」：30 天只是**取数窗口**，而横轴跨度
     *   是「窗口内首末两条记录的真实时间」（见 [GrowthChartMath.layout]）。
     *   两天内拍两张，两个点会落在轴的两端，图看起来像「跨越三十天的陡峭进步」。
     *   标题写成「最近 30 天」等于替这张图宣称一根它没有的横轴。
     *   宁可少说，不说错 —— 与 iOS 同一取舍。
     */
    const val CHART_TITLE: String = "参考分变化"

    /** 一条记录都没有时的空状态文案。 */
    const val EMPTY_TEXT: String = "还没记录 · 每拍一张，这里会多一个参考分点"

    /**
     * 总分位：`null` → `--`，有值照印。
     *
     * ★ 这是「null 不印 0」的**唯一落点之一**，参数保持 `Int?` 而不是先在外层
     *   `?: 0` 再传进来 —— 那样 0 分与未测就再也分不开了。
     *
     * @param total 总分；null = 这一张至少有一维没测到，`ShotRecord.of` 就没算总分
     * @return 可显示的分数文案
     */
    fun formatTotal(total: Int?): String = total?.toString() ?: NO_VALUE

    /**
     * 增量段文案：`（+20）` / `（-5）` / `（0）`；**null 返回 null**。
     *
     * ★ 返回 `String?` 而不是返回 `（--）`：调用方拿到 null 就整段不渲染。
     *   「提高了多少」这个说法在起点未知时本来就不成立 —— 印 `（--）` 是在
     *   用一个不存在的量词占位，印 `（+0）` 更糟（会被读成「一点没进步」）。
     *
     * ★ 正数才补 `+`：写 `（20）` 会让人以为「总分是 20」。
     *   `0` 不写成 `（+0）`：「没有变化」是一种有意义的结果，不是进步。
     *
     * @param delta `bestTotal - firstTotal`；任一端未测时为 null
     * @return 带中文括号的增量文案；不可计算时为 null
     */
    fun deltaText(delta: Int?): String? {
        val value = delta ?: return null
        return if (value > 0) "（+$value）" else "（$value）"
    }

    /**
     * 「已记 N / M 张」。
     *
     * ★ 这一行**恒可显示**：`shotCount` 是真实条数（含未测的记录），
     *   与首末对比卡能否显示无关。用户至少能看到「记录在长」。
     *   唯一例外是 N == 0 —— 那时由空状态那一句接管，见 [showEmptyState]。
     *
     * @param shotCount 已记条数
     * @param capacity 环形上限（[ScoreHistory.MAX_RECORDS]）
     * @return 文案；`capacity` 非法时回落到 [ScoreHistory.MAX_RECORDS]
     */
    fun countText(shotCount: Int, capacity: Int): String {
        val safeCount = if (shotCount > 0) shotCount else 0
        val safeCapacity = if (capacity > 0) capacity else ScoreHistory.MAX_RECORDS
        return "已记 $safeCount / $safeCapacity 张"
    }

    /**
     * 是否显示空状态（一条记录都没有）。
     *
     * ★ 空状态一旦显示，**计数行与图表都必须藏起来**：`已记 0 / 500 张`
     *   和一张空网格都属于「用一个 0 假装有数据」，而事实是一条都没记。
     *
     * @param recordCount 已记条数
     */
    fun showEmptyState(recordCount: Int): Boolean = recordCount <= 0

    /**
     * 图表区的占位文案（有记录、但一个可画的点都没有时）。
     *
     * ★ 三种「没图可画」的情形必须说三句不同的话，混成一句会让用户误判：
     *   · 「还没记录」—— 空状态那一句负责，本函数返回 null（不重复说）；
     *   · 「最近 30 天没有新记录」—— 历史里有记录，但都落在取数窗口之外。
     *     这和「还没测到分数」是两件事：用户的记录没丢，只是不在窗口里；
     *   · 「最近 30 天有 N 张记录，但都还没测到完整的参考分」——
     *     记录在长，只是每张都至少有一维没测到，所以算不出总分。
     *     这时**不能画 0 横线**：那会被读成「连着 N 天都拍了 0 分」。
     *
     * @param recordCount 历史总条数
     * @param recentCount 取数窗口内的条数
     * @param windowDays 取数窗口天数（文案里如实写出来）
     * @param hasMeasurablePoint 是否至少有一个 `total` 非 null 的点
     * @return 占位文案；不该显示占位时为 null（能画图时不必说，空状态自己会说）
     */
    fun chartPlaceholderText(
        recordCount: Int,
        recentCount: Int,
        windowDays: Int,
        hasMeasurablePoint: Boolean
    ): String? {
        if (recordCount <= 0) return null
        if (hasMeasurablePoint) return null
        val days = if (windowDays > 0) windowDays else DEFAULT_WINDOW_DAYS
        if (recentCount <= 0) return "最近 $days 天没有新记录"
        return "最近 $days 天有 $recentCount 张记录，但都还没测到完整的参考分"
    }

    /** 折线窗口的默认天数。与 iOS `GrowthDefaults.windowDays` 同值（30）。 */
    const val DEFAULT_WINDOW_DAYS: Int = 30
}

/**
 * 折线图上的一个点，坐标**已归一化到 0..1**。
 *
 * 归一化而不是直接给像素：本文件不碰 Android，画布尺寸由 `GrowthChartView`
 * 在 `onDraw` 里乘上去。这样「点在哪儿」这件事可以在 JVM 上单测断言。
 */
data class GrowthChartPoint(
    /** 横坐标比例：0 = 窗口最早，1 = 窗口最晚 */
    val x: Float,
    /** 纵坐标比例：0 = 顶部（100 分），1 = 底部（0 分） */
    val y: Float
)

/**
 * 折线图的全部几何。
 *
 * ★ 没有单独的 `measurable` 字段：[hasMeasurable] 由 [dots] 推导，
 *   少一个字段就少一处「标志位与数据不一致」的可能。
 */
data class GrowthChartGeometry(
    /** 每个**已测**点一个圆点（未测的点不在这里） */
    val dots: List<GrowthChartPoint>,
    /** 连续已测点组成的折线段，每段 ≥ 2 个点（单点不成线） */
    val segments: List<List<GrowthChartPoint>>
) {
    /**
     * 是否至少有一个可画的点。
     *
     * ★ false ⇒ `GrowthChartView` **什么都不画**（连网格都不画）。
     *   一条贴着底边的 0 分横线会被读成「连续 30 天都是 0 分」，
     *   而事实是一个点都没测到。
     */
    val hasMeasurable: Boolean get() = dots.isNotEmpty()

    companion object {
        /** 空几何：没有点、没有线。 */
        val EMPTY: GrowthChartGeometry = GrowthChartGeometry(emptyList(), emptyList())
    }
}

/**
 * 把 [ShotRecord] 列表折成折线图几何。
 *
 * ## 坐标口径（与 iOS `GrowthChart` 一致）
 *  - **X 轴按时间等距**，不用「索引等距」：索引等距意味着一周内拍 2 张与拍 200 张
 *    在横轴上密度一样，于是「密集练习的那一周」在图上反而看不出更密，趋势失真。
 *    按时间等距之后，斜率才是真的「每天涨多少分」。
 *  - X 的两端是**窗口内首末记录的真实时间**，不是固定的 30 天：
 *    刚用两天的人，所有点会挤在最右边 1/15 的宽度里，看起来像一条竖线。
 *  - **Y 轴固定 0..100**，不自适应：自适应的图会让 78→80 的进步爬满整屏，是视觉欺骗。
 *  - `total == null`（记录里至少有一维没测到）的点**不出现在几何里**，
 *    于是它两侧的折线自然断开 —— 不连线、也不补 0。
 */
object GrowthChartMath {

    /** Y 轴满分（四维满分之和 25+30+25+20 = 100）。 */
    const val MAX_TOTAL: Int = 100

    /**
     * @param records 记录（顺序无所谓，内部按时间升序排；含未测记录）
     * @return 折线几何；一条可画的点都没有时是 [GrowthChartGeometry.EMPTY]
     */
    fun layout(records: List<ShotRecord>): GrowthChartGeometry {
        if (records.isEmpty()) return GrowthChartGeometry.EMPTY
        // 防御性排序：上游 `recentSeries` 已经升序，但 X 比例全靠首末时间戳，
        // 万一来料是倒序，整张图的横轴会整体镜像 —— 这里排一次成本可忽略。
        val ordered = records.sortedBy { it.timestamp }
        val firstAt = ordered.first().timestamp
        val lastAt = ordered.last().timestamp
        val span = lastAt - firstAt

        val placed: List<GrowthChartPoint?> = ordered.map { record ->
            val total = record.total ?: return@map null
            val x = if (span <= 0L) {
                // 所有点同一时刻（例如同一分钟连拍两张）：落在正中而不是左边缘 ——
                // 左边缘会让它看起来像「很久以前」。
                0.5f
            } else {
                ((record.timestamp - firstAt).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
            val clamped = total.coerceIn(0, MAX_TOTAL)
            GrowthChartPoint(x = x, y = 1f - clamped.toFloat() / MAX_TOTAL.toFloat())
        }

        return GrowthChartGeometry(
            dots = placed.filterNotNull(),
            segments = splitRuns(placed)
        )
    }

    /**
     * 把「逐点坐标（含 null）」切成若干段连续的已测点。
     *
     * ★ null 打断当前段：断点处不连线，也不会把两侧连成一条「穿过未测记录」的斜线
     *   —— 那等于凭空编出一段趋势。
     * ★ 只有 ≥ 2 个点的段才返回：单点画不出线，那个点由 `dots` 的圆点负责显示。
     *
     * @param placed 与记录列表等长、逐项对应的坐标（null = 未测）
     * @return 每段是一条折线的顶点序列
     */
    fun splitRuns(placed: List<GrowthChartPoint?>): List<List<GrowthChartPoint>> {
        val runs = ArrayList<List<GrowthChartPoint>>()
        var current = ArrayList<GrowthChartPoint>()
        for (item in placed) {
            if (item == null) {
                if (current.isNotEmpty()) {
                    runs.add(current)
                    current = ArrayList()
                }
            } else {
                current.add(item)
            }
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs.filter { it.size >= 2 }
    }
}
