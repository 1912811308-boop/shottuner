package com.shottuner.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩水印测试。
 *
 * 只测**纯 JVM 那半边**（文案 + 尺寸计算）—— 绘制要 `Canvas`，留给真机。
 * 钉住的硬要求：
 * 1. 未测全时烧「参考分 --」，绝不烧假数字，且等级一起丢掉；
 * 2. 水印块高度 ≤ 图片短边的 4%，4:3 / 16:9 / 竖构图 / 极小图都不越界；
 * 3. 水印块不会横向溢出（宽 ≤ 图片宽 - 2 × 边距）。
 *
 * 本文件不碰任何 Android 类型，与 `analysis` / `knowledge` 的 suite 同构。
 */
class WatermarkTest {

    // ── 文案：口径 ────────────────────────────────────────────────────────────

    @Test
    fun scoreLineWithGrade() {
        assertEquals("参考分 78 · 良好", Watermark.formatScoreLine(78, "良好"))
    }

    @Test
    fun scoreLineWithoutGrade() {
        assertEquals("参考分 63", Watermark.formatScoreLine(63, null))
        assertEquals("参考分 63", Watermark.formatScoreLine(63, "  "))
    }

    @Test
    fun scoreLineNotMeasuredIsUnknown() {
        assertEquals("参考分 --", Watermark.formatScoreLine(null, null))
        // 分数不可信时等级同样不可信，必须一起丢掉 —— 烧「-- · 良好」是假数据
        assertEquals("参考分 --", Watermark.formatScoreLine(null, "良好"))
    }

    @Test
    fun scoreLineOutOfRangeIsUnknown() {
        assertEquals("参考分 --", Watermark.formatScoreLine(-1, "良好"))
        assertEquals("参考分 --", Watermark.formatScoreLine(101, "良好"))
        assertEquals("参考分 0", Watermark.formatScoreLine(0, null))
        assertEquals("参考分 100", Watermark.formatScoreLine(100, null))
    }

    @Test
    fun brandIsAlwaysFirstLine() {
        assertEquals("光影调参", Watermark.buildLines(78, "良好", "三分法").first())
    }

    @Test
    fun threeLinesWhenTechniqueKnown() {
        val lines = Watermark.buildLines(78, "良好", "三分法")
        assertEquals(3, lines.size)
        assertEquals("光影调参", lines[0])
        assertEquals("参考分 78 · 良好", lines[1])
        assertEquals("三分法", lines[2])
    }

    @Test
    fun twoLinesWhenTechniqueMissing() {
        for (missing in listOf(null, "", "   ")) {
            val lines = Watermark.buildLines(78, "良好", missing)
            assertEquals("技法取不到时只烧两行（入参=$missing）", 2, lines.size)
            assertEquals("参考分 78 · 良好", lines[1])
        }
    }

    @Test
    fun twoLinesWhenScoreNotMeasured() {
        val lines = Watermark.buildLines(null, "良好", "三分法")
        assertEquals(3, lines.size)
        assertEquals("参考分 --", lines[1])
    }

    // ── 文案：技法简称 ────────────────────────────────────────────────────────

    @Test
    fun techniqueShortIsKeptAsIs() {
        assertEquals("三分法", Watermark.shortenTechnique("三分法"))
        assertEquals("三分法", Watermark.shortenTechnique("  三分法  "))
    }

    @Test
    fun techniqueCutsAtFirstPause() {
        // 技法库里的 title 是整句，砍到第一个逗号为止
        assertEquals("眼睛放上三分线", Watermark.shortenTechnique("眼睛放上三分线，别放正中"))
        assertEquals("地平线一定端平", Watermark.shortenTechnique("地平线一定端平，歪一点就废"))
        assertEquals("手肘夹紧身体", Watermark.shortenTechnique("手肘夹紧身体，画面才不抖"))
    }

    @Test
    fun techniqueCapsLength() {
        val short = Watermark.shortenTechnique("一二三四五六七八九十十一")
        assertNotNull(short)
        assertTrue("超长技法要截断，实际 ${short!!.length} 字：$short", short.length <= 8)
        assertTrue("截断要有省略号，实际 $short", short.endsWith("…"))
    }

    @Test
    fun techniqueBlankIsNull() {
        assertNull(Watermark.shortenTechnique(null))
        assertNull(Watermark.shortenTechnique(""))
        assertNull(Watermark.shortenTechnique("  "))
    }

    // ── 尺寸：4% 高度上限 ─────────────────────────────────────────────────────

    private fun assertHeightWithinBudget(w: Int, h: Int, lines: Int) {
        val layout = Watermark.measure(w, h, lines)
        val shortSide = minOf(w, h).toFloat()
        val ceiling = shortSide * Watermark.MAX_BLOCK_HEIGHT_RATIO + 0.01f
        assertTrue(
            "${w}x${h} / ${lines}行 水印高 ${layout.blockHeight} 超过短边 4%（上限 $ceiling）",
            layout.blockHeight <= ceiling
        )
    }

    @Test
    fun heightWithinFourPercent_4x3() {
        assertHeightWithinBudget(4000, 3000, 2)
        assertHeightWithinBudget(4000, 3000, 3)
        assertHeightWithinBudget(1600, 1200, 3)
    }

    @Test
    fun heightWithinFourPercent_16x9() {
        assertHeightWithinBudget(1920, 1080, 2)
        assertHeightWithinBudget(1920, 1080, 3)
        assertHeightWithinBudget(3840, 2160, 3)
    }

    @Test
    fun heightWithinFourPercent_portrait() {
        assertHeightWithinBudget(1080, 1920, 3)
        assertHeightWithinBudget(3024, 4032, 3)
    }

    @Test
    fun heightWithinFourPercent_tinyAndSquare() {
        assertHeightWithinBudget(240, 320, 3)
        assertHeightWithinBudget(1080, 1080, 3)
        assertHeightWithinBudget(1, 1, 3)
    }

    @Test
    fun lineCountIsClampedToOneToThree() {
        // 少于 1 行 / 多于 3 行都不该算出 0 或负数
        for (count in -1..6) {
            val layout = Watermark.measure(4000, 3000, count)
            assertTrue("行数 $count 的块高必须为正，实际 ${layout.blockHeight}", layout.blockHeight > 0f)
            assertTrue("行数 $count 的品牌字号必须为正，实际 ${layout.brandSize}", layout.brandSize > 0f)
        }
    }

    // ── 尺寸：不溢出 ──────────────────────────────────────────────────────────

    @Test
    fun blockNeverOverflowsWidth() {
        for ((w, h) in listOf(4000 to 3000, 1920 to 1080, 1080 to 1920, 240 to 320)) {
            for (lines in 2..3) {
                val layout = Watermark.measure(w, h, lines)
                val available = w.toFloat() - layout.marginX * 2f
                assertTrue(
                    "${w}x${h}/${lines}行 水印宽 ${layout.blockWidth} 超过可用宽 $available",
                    layout.blockWidth <= available + 0.01f
                )
                // 右边缘不能被裁：整块右边必须落在图片内
                assertTrue(
                    "${w}x${h}/${lines}行 右边距 ${layout.marginX} 不够",
                    layout.marginX >= 0f && layout.marginX * 2f < w.toFloat()
                )
                // 下边缘不能被裁
                assertTrue(
                    "${w}x${h}/${lines}行 下边距越界",
                    layout.marginY + layout.blockHeight <= h.toFloat() + 0.01f
                )
            }
        }
    }

    @Test
    fun textWidthIsPositive() {
        for ((w, h) in listOf(4000 to 3000, 1920 to 1080, 240 to 320)) {
            val layout = Watermark.measure(w, h, 3)
            assertTrue("可用文字宽必须为正，实际 ${layout.maxTextWidth}", layout.maxTextWidth > 0f)
        }
    }

    @Test
    fun lineSizesDescend() {
        val three = Watermark.measure(4000, 3000, 3)
        assertTrue("品牌行应不小于参考分行", three.brandSize > three.scoreSize)
        assertTrue("参考分行应不小于技法行", three.scoreSize > three.techSize)

        val two = Watermark.measure(4000, 3000, 2)
        assertTrue("两行时不应有技法行", two.techSize == 0f)
        assertTrue("两行时品牌行应更大", two.brandSize > two.scoreSize)
        // 两行的总高预算和三行一样，所以两行的字号可以更大
        assertTrue("两行时参考分行应比三行时大", two.scoreSize > three.scoreSize)
    }

    @Test
    fun scalesWithImageSize() {
        val small = Watermark.measure(1000, 1000, 3)
        val large = Watermark.measure(4000, 4000, 3)
        // 等比放大：4000 的品牌字号应约为 1000 的 4 倍
        val ratio = large.brandSize / small.brandSize
        assertTrue("字号应随图片等比缩放，实际比例 $ratio", ratio > 3.9f && ratio < 4.1f)
    }

    // ── 尺寸：长文案收缩 ──────────────────────────────────────────────────────

    private fun assertClose(message: String, expected: Float, actual: Float) {
        assertTrue("$message（期望 $expected，实际 $actual）", kotlin.math.abs(expected - actual) < 1e-4f)
    }

    @Test
    fun shrinkKeepsSizeWhenItFits() {
        assertClose("放得下就不应改字号", 30f, Watermark.shrinkToWidth(30f, 500f, 200f))
        assertClose("刚好放下也不应改字号", 30f, Watermark.shrinkToWidth(30f, 500f, 500f))
    }

    @Test
    fun shrinkScalesDownWhenTooWide() {
        // 超出一倍 → 字号减半
        assertClose("超宽一倍应减半", 15f, Watermark.shrinkToWidth(30f, 500f, 1000f))
        // 超出四倍 → 字号四分之一
        assertClose("超宽四倍应减到 1/4", 7.5f, Watermark.shrinkToWidth(30f, 500f, 2000f))
    }

    @Test
    fun shrinkNeverGoesBelowOnePixel() {
        assertTrue(Watermark.shrinkToWidth(30f, 1f, 100000f) >= 1f)
        // 退化输入不能产生 NaN / 负数
        assertClose("maxWidth=0 视为不限制", 30f, Watermark.shrinkToWidth(30f, 0f, 1000f))
        assertClose("measuredWidth=0 视为不限制", 30f, Watermark.shrinkToWidth(30f, 500f, 0f))
    }

    @Test
    fun shrinkResultFitsBudget() {
        // 收缩后按真实度量重算，宽度必须落回预算内
        val layout = Watermark.measure(1920, 1080, 3)
        val raw = layout.brandSize * 20f // 模拟一条极长的文案
        val fitted = Watermark.shrinkToWidth(raw, layout.maxTextWidth, raw * 20f)
        assertTrue("收缩结果必须落在预算内：fitted=$fitted", fitted <= raw)
        assertTrue("收缩结果不能小于 1px", fitted >= 1f)
    }
}
