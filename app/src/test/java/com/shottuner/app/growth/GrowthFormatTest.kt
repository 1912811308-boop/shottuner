package com.shottuner.app.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成长页显示口径测试（G4「进步可见」—— Android 侧）。
 *
 * ## 这个文件存在的唯一理由
 * 本仓已经被「缺数据伪装成 0」打回两次（成绩卡印假分数、`hasTotal` 口径）。
 * 成长页是第三个入口：`ShotRecord.total` 是 `Int?`，`GrowthSummary` 的
 * `firstTotal` / `bestTotal` / `delta` 也全是 `Int?`，界面上**任何一处**
 * 把 null 当 0 印出去，用户看到的就是「我这项 0 分」。
 *
 * 所以这里的每条用例都钉住一个「不能错」的口径，而不是在测实现细节：
 *   1. 未测 → 占位横线（`GrowthFormat.NO_VALUE`），**且不等于 "0"**；
 *   2. 真的 0 分 → 照印 "0"（0 分是一个判断结果，必须能显示）；
 *   3. 增量不可算 → 返回 null（调用方整段不渲染），不是 `（+0）`；
 *   4. 折线图里未测的点**不存在于几何里** ⇒ 折线在断点处断开，
 *      绝不会被连成一条穿过未测记录的斜线，也不会补出 0 分横线。
 *
 * ## 测不到的那一半（如实写在这里）
 * `GrowthChartView` 的 `Canvas` 绘制、`MainActivity.applyGrowth` 的 `setText`、
 * 页签切换与后台读盘，都只能靠真机手验 —— 本工程没有 Robolectric，也不允许
 * 引第三方测试依赖。本文件覆盖的是它们**全部决策依据**。
 */
class GrowthFormatTest {

    private val HOUR = 60L * 60L * 1000L

    // ── 1. 未测不印 0（本任务的核心铁律）────────────────────────────────────

    @Test
    fun formatTotal_nullIsPlaceholderNeverZero() {
        // 未测：占位横线
        assertEquals(GrowthFormat.NO_VALUE, GrowthFormat.formatTotal(null))
        assertEquals("--", GrowthFormat.formatTotal(null))
        // ★ 反面断言：这条是本文件的立身之本 —— null 绝不能变成 "0"
        assertTrue(
            "未测被印成了 0 分（这正是本仓被打回两次的那个 bug）",
            GrowthFormat.formatTotal(null) != "0"
        )
    }

    @Test
    fun formatTotal_realZeroIsStillPrinted() {
        // 0 分是**有效判断**（真的测到了、真的差），必须照印。
        // 如果为了「别印假 0」而把真 0 也藏掉，等于把用户的差评改成了「未知」。
        assertEquals("0", GrowthFormat.formatTotal(0))
        assertEquals("1", GrowthFormat.formatTotal(1))
        assertEquals("100", GrowthFormat.formatTotal(100))
    }

    @Test
    fun deltaText_nullRendersNothingAtAll() {
        // 增量不可算 → null（调用方整段 GONE），既不是（+0）也不是占位横线：
        // 「提高了多少」这个说法在起点未知时本来就不成立
        assertNull(GrowthFormat.deltaText(null))
    }

    @Test
    fun deltaText_signsAreExplicit() {
        assertEquals("（+20）", GrowthFormat.deltaText(20))
        assertEquals("（-5）", GrowthFormat.deltaText(-5))
        // 0 不写成（+0）：「没有变化」是一种有意义的结果，不是进步
        assertEquals("（0）", GrowthFormat.deltaText(0))
        assertEquals("（+1）", GrowthFormat.deltaText(1))
    }

    // ── 2. GrowthSummary 三个字段分别判空的后果 ─────────────────────────────
    //
    // 用的是**和 MainActivity.applyGrowth 完全一样的调用序列**：
    // formatTotal(firstTotal) / formatTotal(bestTotal) / deltaText(delta)。
    // 这样如果 applyGrowth 里哪天换了口径，这几条用例会成为对照物。

    @Test
    fun summary_allThreeNull_printsTwoPlaceholdersAndNoDelta() {
        val summary = GrowthSummary(firstTotal = null, bestTotal = null, delta = null, shotCount = 3)

        assertEquals("--", GrowthFormat.formatTotal(summary.firstTotal))
        assertEquals("--", GrowthFormat.formatTotal(summary.bestTotal))
        assertNull(GrowthFormat.deltaText(summary.delta))
        // 记录数恒有效：即便三个数值全空，「已记 3 / 500 张」也必须能显示
        assertTrue(GrowthFormat.countText(summary.shotCount, ScoreHistory.MAX_RECORDS).contains("3"))
    }

    @Test
    fun summary_firstNullBestMeasured_printsOnePlaceholderAndNoDelta() {
        // 首张未测 + 后面拍出过 82 分：`firstVsBest` 的 delta 必然是 null
        // （起点是「未知」，任何减法都没有意义）
        val summary = GrowthSummary(firstTotal = null, bestTotal = 82, delta = null, shotCount = 5)

        assertEquals("--", GrowthFormat.formatTotal(summary.firstTotal))
        assertEquals("82", GrowthFormat.formatTotal(summary.bestTotal))
        assertNull(GrowthFormat.deltaText(summary.delta))
    }

    @Test
    fun summary_allMeasured_printsFullTriple() {
        val summary = GrowthSummary(firstTotal = 62, bestTotal = 82, delta = 20, shotCount = 5)

        assertEquals("62", GrowthFormat.formatTotal(summary.firstTotal))
        assertEquals("82", GrowthFormat.formatTotal(summary.bestTotal))
        assertEquals("（+20）", GrowthFormat.deltaText(summary.delta))
    }

    // ── 3. 计数行 / 空状态 / 占位文案 ────────────────────────────────────────

    @Test
    fun countText_showsRealCountAndCapacity() {
        assertEquals("已记 7 / 500 张", GrowthFormat.countText(7, ScoreHistory.MAX_RECORDS))
        // 非法上限回落到环形上限，而不是印出「已记 7 / 0 张」这种让人困惑的东西
        assertEquals("已记 7 / 500 张", GrowthFormat.countText(7, 0))
        assertEquals("已记 7 / 500 张", GrowthFormat.countText(7, -1))
        // 负条数不应出现，真出现了也不能印成「已记 -3 张」
        assertEquals("已记 0 / 500 张", GrowthFormat.countText(-3, ScoreHistory.MAX_RECORDS))
    }

    @Test
    fun emptyState_isOnlyAboutZeroRecords() {
        assertTrue(GrowthFormat.showEmptyState(0))
        assertFalse(GrowthFormat.showEmptyState(1))
    }

    @Test
    fun chartPlaceholder_threeDifferentSituationsGetThreeDifferentSentences() {
        val days = GrowthFormat.DEFAULT_WINDOW_DAYS
        // ① 空状态自己会说 → 这里闭嘴
        assertNull(GrowthFormat.chartPlaceholderText(0, 0, days, false))
        // ② 能画图 → 什么都不说
        assertNull(GrowthFormat.chartPlaceholderText(9, 4, days, true))
        // ③ 有历史但都不在窗口里 —— 记录没丢，只是不在窗口，不能含糊成「还没测到」
        val outsideWindow = GrowthFormat.chartPlaceholderText(9, 0, days, false)
        assertEquals("最近 30 天没有新记录", outsideWindow)
        // ④ 窗口内有记录、但一张都算不出总分
        val noMeasured = GrowthFormat.chartPlaceholderText(9, 3, days, false)
        assertEquals("最近 30 天有 3 张记录，但都还没测到完整的参考分", noMeasured)
    }

    // ── 4. 折线几何：未测的点必须断开，不能补 0 ──────────────────────────────

    @Test
    fun chart_middleUnmeasuredRecordBreaksTheLine() {
        // 时间升序：60 → 62 → (未测) → 80 → 82
        val records = listOf(
            record(0 * HOUR, 60),
            record(1 * HOUR, 62),
            record(2 * HOUR, null),
            record(3 * HOUR, 80),
            record(4 * HOUR, 82)
        )

        val geometry = GrowthChartMath.layout(records)

        assertTrue("有可画的点", geometry.hasMeasurable)
        assertEquals("未测的那条不产生圆点", 4, geometry.dots.size)
        assertEquals("必须断成两段，不能连成一条斜线", 2, geometry.segments.size)
        assertEquals(2, geometry.segments[0].size)
        assertEquals(2, geometry.segments[1].size)
        // ★ 断点两侧的点横坐标必须留出间隔（中间那条记录占了 1/4 的宽度）
        assertTrue(
            "第二段起点必须明显晚于第一段终点",
            geometry.segments[1][0].x - geometry.segments[0][1].x > 0.4f
        )
        // ★ 任何一点的 y 都不能是「0 分所在的底边」：60/62/80/82 都在中间偏上
        for (point in geometry.dots) {
            assertTrue("未测记录被当成了 0 分（y=${point.y}）", point.y < 0.5f)
        }
    }

    @Test
    fun chart_allRecordsUnmeasured_drawsNothingAtAll() {
        // 三张记录，四维全未测 ⇒ 一个点都画不出来
        val geometry = GrowthChartMath.layout(
            listOf(record(0, null), record(HOUR, null), record(2 * HOUR, null))
        )

        assertFalse("没有可画的点", geometry.hasMeasurable)
        assertTrue("不能有圆点", geometry.dots.isEmpty())
        assertTrue("不能有折线（尤其是贴着底边的 0 分直线）", geometry.segments.isEmpty())
        // 与 EMPTY 完全等价：视图拿到它之后 onDraw 直接 return
        assertEquals(GrowthChartGeometry.EMPTY, geometry)
    }

    @Test
    fun chart_emptyRecordsIsEmptyGeometry() {
        assertEquals(GrowthChartGeometry.EMPTY, GrowthChartMath.layout(emptyList()))
    }

    @Test
    fun chart_singleMeasuredPointIsADotWithoutALine() {
        // 一张 70 分 + 两条未测：图上应该只有**一个点**，没有线 ——
        // 用户看到「图上有东西」，而不是一张空白图
        val geometry = GrowthChartMath.layout(
            listOf(record(0, 70), record(HOUR, null), record(2 * HOUR, null))
        )

        assertTrue(geometry.hasMeasurable)
        assertEquals(1, geometry.dots.size)
        assertTrue("单点不成线", geometry.segments.isEmpty())
        // 70 分 → y = 1 - 70/100 = 0.3（分数越大越靠上）
        assertEquals(0.3f, geometry.dots[0].y, 1e-4f)
    }

    @Test
    fun chart_yAxisIsFixedZeroToHundred() {
        // Y 轴固定 0..100，不按数据自适应：自适应的图会让 78→80 的进步爬满整屏
        val geometry = GrowthChartMath.layout(
            listOf(record(0, 0), record(HOUR, 50), record(2 * HOUR, 100))
        )

        assertEquals(1f, geometry.dots[0].y, 1e-4f)   // 0 分在底边
        assertEquals(0.5f, geometry.dots[1].y, 1e-4f) // 50 分在中间
        assertEquals(0f, geometry.dots[2].y, 1e-4f)   // 100 分在顶边
    }

    @Test
    fun chart_xAxisIsTimeProportional() {
        // 时间点：0h / 1h / 5h（间隔 1h 与 4h）→ 横坐标 0 / 0.2 / 1.0
        val geometry = GrowthChartMath.layout(
            listOf(record(0, 60), record(1 * HOUR, 60), record(5 * HOUR, 60))
        )

        assertEquals(3, geometry.dots.size)
        assertEquals(0f, geometry.dots[0].x, 1e-4f)
        assertEquals(0.2f, geometry.dots[1].x, 1e-4f)
        assertEquals(1f, geometry.dots[2].x, 1e-4f)
    }

    @Test
    fun chart_sameInstantFallsInTheMiddleNotTheLeftEdge() {
        // 同一分钟连拍两张：窗口跨度为 0。落到正中，而不是左边缘 ——
        // 左边缘会让它看起来像「很久以前拍的」
        val geometry = GrowthChartMath.layout(listOf(record(1000L, 60), record(1000L, 80)))

        assertEquals(2, geometry.dots.size)
        for (point in geometry.dots) {
            assertEquals(0.5f, point.x, 1e-4f)
        }
        assertEquals("同一时刻的两个点仍成一段（画出来是竖线，但确实是两拍）", 1, geometry.segments.size)
    }

    @Test
    fun chart_unsortedInputIsSortedBeforeMapping() {
        // 上游 `recentSeries` 已升序，但 X 比例全靠首末时间戳 ——
        // 万一来料倒序，整张图会整体镜像。这里钉住「内部先排序」。
        val geometry = GrowthChartMath.layout(
            listOf(record(4 * HOUR, 82), record(0, 60), record(2 * HOUR, null))
        )

        assertEquals(2, geometry.dots.size)
        // 60 分在时间轴最左（x=0），82 分在最右（x=1）
        assertEquals(0f, geometry.dots[0].x, 1e-4f)
        assertEquals(1f, geometry.dots[1].x, 1e-4f)
        assertTrue("60 分的 y 应该在 82 分之下（数值越大越靠上）", geometry.dots[0].y > geometry.dots[1].y)
    }

    @Test
    fun splitRuns_singlePointRunsAreDropped() {
        val a = GrowthChartPoint(0f, 0.4f)
        val b = GrowthChartPoint(0.5f, 0.3f)
        val c = GrowthChartPoint(1f, 0.2f)

        // [a, null, b, c] → 只有 [b, c] 成段（[a] 是单点，由圆点负责显示）
        val runs = GrowthChartMath.splitRuns(listOf(a, null, b, c))
        assertEquals(1, runs.size)
        assertEquals(listOf(b, c), runs[0])

        // 全是 null → 没有任何段
        assertTrue(GrowthChartMath.splitRuns(listOf(null, null)).isEmpty())
        // 全有值 → 一整段
        assertEquals(1, GrowthChartMath.splitRuns(listOf(a, b, c)).size)
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    /**
     * 造一条记录。
     *
     * 刻意直接构造 `ShotRecord` 而不是走 `ShotRecord.of`：`of` 会在任一维为 null
     * 时把 total 也抹成 null，那正好是另一条测试要验的行为；
     * 这里要能造出「total 有值但某维未测」这种不合法组合吗？不 ——
     * 造的是 `of` 的合法产物：total 与四维全有值，或 total 与四维全 null。
     *
     * @param timestamp 拍摄时刻
     * @param total 总分；null = 四维全未测
     */
    private fun record(timestamp: Long, total: Int?): ShotRecord =
        if (total == null) {
            ShotRecord(
                timestamp = timestamp,
                total = null,
                level = null,
                exposure = null,
                composition = null,
                clarity = null
            )
        } else {
            ShotRecord(
                timestamp = timestamp,
                total = total,
                level = 25,
                exposure = 30,
                composition = 25,
                clarity = 20
            )
        }
}
