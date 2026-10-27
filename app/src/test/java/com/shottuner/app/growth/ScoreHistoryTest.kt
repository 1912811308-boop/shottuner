package com.shottuner.app.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分数历史测试（G4）。
 *
 * 这里的用例只碰 [ScoreHistory] / [ShotRecord] —— 纯 JVM，不依赖 Android，
 * 也不需要 Robolectric。磁盘读写在 `ScoreHistoryStore`（那个要 `Context`，
 * 只能在真机 / instrumentation 上验）。
 *
 * 每条用例对应一条「绝不能错」的口径：
 *   - 未测必须落 null，不落 0；
 *   - delta 算不出来就是 null，不许拿 0 或假数字兜底；
 *   - 文件坏了宁可整体作废，也不许崩、不许返回半真半假的历史。
 */
class ScoreHistoryTest {

    /**
     * 造一条记录。默认四维全测（total 由 [ShotRecord.of] 自动合成）。
     * 传 null 即模拟「该维度未测」。
     */
    private fun record(
        timestamp: Long,
        level: Int? = 20,
        exposure: Int? = 24,
        composition: Int? = 18,
        clarity: Int? = 14
    ): ShotRecord = ShotRecord.of(
        timestamp = timestamp,
        level = level,
        exposure = exposure,
        composition = composition,
        clarity = clarity
    )

    private fun historyOf(timestamps: LongRange): ScoreHistory {
        val history = ScoreHistory.empty()
        timestamps.forEach { history.add(record(it)) }
        return history
    }

    // 1. 环形覆盖：加到 501 条，最旧的那条被丢

    @Test
    fun ringBuffer_dropsOldestAfterOverflow() {
        val history = historyOf(1L..501L)

        assertEquals(ScoreHistory.MAX_RECORDS, history.size())
        assertEquals(500, history.all().size)
        // 时间戳 1 的那条被丢，2 成为新的起点
        assertEquals(2L, history.all().first().timestamp)
        assertEquals(501L, history.all().last().timestamp)
        // 排序仍然单调
        assertEquals(history.all().sortedBy { it.timestamp }, history.all())
    }

    @Test
    fun ringBuffer_keepsEverythingUnderLimit() {
        val history = historyOf(1L..500L)
        assertEquals(500, history.size())
        assertEquals(1L, history.all().first().timestamp)
    }

    // 2. 未测维度存 null，total 也是 null

    @Test
    fun unmeasuredDimension_isStoredAsNull_notZero() {
        val r = record(timestamp = 1000L, composition = null)

        assertNull("未测的构图必须是 null，不能是 0", r.composition)
        assertEquals(20, r.level)
        assertEquals(24, r.exposure)
        assertEquals(14, r.clarity)
        // 任一维未测 → 总分 null（UI 口径「参考分 --」）
        assertNull("有维度未测时 total 必须是 null", r.total)
    }

    @Test
    fun allMeasured_totalIsSum() {
        val r = record(timestamp = 1000L)
        assertEquals(20 + 24 + 18 + 14, r.total)
        assertNotNull(r.total)
    }

    @Test
    fun unmeasuredSurvivesJson_asNullNotZero() {
        val history = ScoreHistory.empty()
        history.add(record(1000L, composition = null))
        history.add(record(2000L, clarity = null))

        val restored = ScoreHistory.fromJson(history.toJson())

        assertEquals(2, restored.size())
        assertNull(restored.all()[0].composition)
        assertNull(restored.all()[0].total)
        assertNull(restored.all()[1].clarity)
        assertNull(restored.all()[1].total)
    }

    // 3. toJson → fromJson 往返一致

    @Test
    fun jsonRoundTrip_preservesEveryField() {
        val history = ScoreHistory.empty()
        history.add(record(1700000000000L))
        history.add(record(1700000060000L, level = 25, exposure = 30, composition = 25, clarity = 20))
        history.add(record(1700000120000L, level = 0, exposure = 10, composition = null, clarity = 3))

        val json = history.toJson()
        val restored = ScoreHistory.fromJson(json)

        assertEquals("往返后条数必须一致", 3, restored.size())
        assertEquals(history.all(), restored.all())
        assertEquals(100, restored.all()[1].total)
        assertEquals(0, restored.all()[2].level)
        assertNull(restored.all()[2].composition)
    }

    @Test
    fun toJson_isPlainJsonWithoutNullFields() {
        val history = ScoreHistory.empty()
        history.add(record(1700000000000L, clarity = null))
        val json = history.toJson()

        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"records\":["))
        assertTrue(json.contains("\"t\":1700000000000"))
        assertFalse("null 字段必须整体省略，不能写成 0 或 null 字面量", json.contains("clarity"))
    }

    // 4. 损坏 JSON → 空列表，不抛异常

    @Test
    fun corruptJson_returnsEmptyHistoryWithoutThrowing() {
        val broken = listOf(
            "{\"version\":1,\"records\":[{\"t\":1,\"total\":2", // 半截（写到一半被杀）
            "{\"version\":1,\"records\":[",
            "not json at all",
            "{",
            "]",
            "{\"version\":1}", // 没有 records 数组
            "{\"version\":1,\"records\":{}}", // records 不是数组
            "{\"version\":1,\"records\":[{\"total\":50}]}" // 没有时间戳
        )

        for (text in broken) {
            var error: String? = null
            val history = ScoreHistory.fromJson(text) { error = it }
            assertEquals("损坏输入必须降级为空历史：$text", 0, history.size())
            assertNotNull("损坏输入必须回调 onError 以便记 Diag.w：$text", error)
        }
    }

    @Test
    fun outOfRangeValue_invalidatesWholeHistory() {
        var error: String? = null
        val history = ScoreHistory.fromJson(
            "{\"version\":1,\"records\":[{\"t\":1,\"total\":99999}]}"
        ) { error = it }

        assertEquals(0, history.size())
        assertNotNull(error)
    }

    // 5. 少于 2 条记录 → firstVsBest() 返回 null

    @Test
    fun firstVsBest_returnsNullBelowTwoRecords() {
        assertNull("0 条记录不能出成长摘要", ScoreHistory.empty().firstVsBest())

        val one = ScoreHistory.empty()
        one.add(record(1000L))
        assertNull("1 条记录不能出成长摘要", one.firstVsBest())
    }

    @Test
    fun firstVsBest_returnsNonNullAtTwoRecords() {
        val history = historyOf(1000L..1001L)
        assertNotNull(history.firstVsBest())
        assertEquals(2, history.firstVsBest()!!.shotCount)
    }

    // 6. 首张未测 → delta = null，绝不给数字

    @Test
    fun firstVsBest_firstUnmeasured_deltaIsNull() {
        val history = ScoreHistory.empty()
        // 第一张构图没测到 → total = null
        history.add(record(1000L, composition = null))
        history.add(record(2000L))

        val summary = history.firstVsBest()
        assertNotNull(summary)
        assertNull("首张未测时 firstTotal 必须是 null", summary!!.firstTotal)
        assertNull("起点是「--」，delta 绝不能是数字", summary.delta)
        assertEquals(20 + 24 + 18 + 14, summary.bestTotal)
        assertEquals(2, summary.shotCount)
    }

    @Test
    fun firstVsBest_nothingMeasured_deltaAndBestAreNull() {
        val history = ScoreHistory.empty()
        history.add(record(1000L, level = null))
        history.add(record(2000L, clarity = null))

        val summary = history.firstVsBest()
        assertNotNull(summary)
        assertNull(summary!!.firstTotal)
        assertNull("一条有分记录都没有时 bestTotal 必须是 null", summary.bestTotal)
        assertNull(summary.delta)
    }

    // 7. 正常路径算出正确的 delta

    @Test
    fun firstVsBest_computesDelta() {
        val history = ScoreHistory.empty()
        history.add(record(1000L, level = 15, exposure = 18, composition = 14, clarity = 14)) // 61
        history.add(record(2000L, level = 18, exposure = 22, composition = 16, clarity = 14)) // 70
        history.add(record(3000L, level = 20, exposure = 25, composition = 19, clarity = 15)) // 79

        val summary = history.firstVsBest()
        assertNotNull(summary)
        assertEquals(61, summary!!.firstTotal)
        assertEquals(79, summary.bestTotal)
        assertEquals("79 - 61 必须等于 18", 18, summary.delta)
        assertEquals(3, summary.shotCount)
    }

    @Test
    fun firstVsBest_bestIsFirst_deltaIsZero() {
        val history = ScoreHistory.empty()
        history.add(record(1000L, level = 25, exposure = 30, composition = 25, clarity = 20)) // 100
        history.add(record(2000L, level = 10, exposure = 10, composition = 10, clarity = 10)) // 40

        val summary = history.firstVsBest()
        assertEquals(100, summary!!.firstTotal)
        assertEquals(100, summary.bestTotal)
        assertEquals(0, summary.delta)
    }

    // 8. 空文件 / 不存在的文件 → 空列表

    @Test
    fun emptyText_returnsEmptyHistory() {
        assertEquals(0, ScoreHistory.fromJson("").size())
        assertEquals(0, ScoreHistory.fromJson("   \n\t ").size())
        // 「文件不存在」在 ScoreHistoryStore 里就返回空历史，不走到 fromJson；
        // 这里保证等价的空输入也是空历史，且同样不抛异常
    }

    @Test
    fun recentSeries_filtersByWindow() {
        // 固定时间戳：now 由调用方注入后，这个测试**完全可复现**，
        // 不再依赖跑测试那一刻的墙上时钟。
        val now = 1_700_000_000_000L
        val history = ScoreHistory.empty()
        history.add(record(now - 10L * 24 * 3600 * 1000)) // 10 天前
        history.add(record(now - 2L * 24 * 3600 * 1000)) // 2 天前
        history.add(record(now)) // 现在

        assertEquals(3, history.recentSeries(30, now).size)
        assertEquals(2, history.recentSeries(7, now).size)
        assertEquals(1, history.recentSeries(1, now).size)
        // 窗口为 0 / 负数时退化成 1 天，不能返回「未来」的空列表以外的怪结果
        assertEquals(1, history.recentSeries(0, now).size)
    }
}
