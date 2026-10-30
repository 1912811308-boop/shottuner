package com.shottuner.app.text

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * [NumFmt] 的**纯数值**回归测试 —— 「给定输入 → 期望输出」的金标准。
 *
 * 这 11 个用例**不碰任何系统 Locale**，因此可以在 Android 与 iOS 两端
 * 跑同一份源码（Kotlin/Native 的 common 代码里没有 `java.util.Locale`）。
 * iOS 端要靠它们验证 [NumFmt] 的核心行为。
 *
 * ## 与 [NumFmtLocaleTest] 的分工
 *
 * 「切系统 Locale（德语区/法语区）」的 3 个用例是 **Android 专属**的缺陷修复验证
 * —— 它们依赖 JVM-only 的 `java.util.Locale.setDefault()`，
 * 而 iOS 侧不存在「系统语言设置影响 Kotlin 数值格式化」这回事
 * （[NumFmt] 自己不依赖任何 Locale，见它的 KDoc）。
 * 所以那 3 个用例放在 app 侧：`app/src/test/java/com/shottuner/app/text/NumFmtLocaleTest.kt`。
 */
class NumFmtTest {

    @Test
    fun normalValuesKeepFixedDecimalPlaces() {
        assertEquals("1.5", NumFmt.one(1.5f))
        // 固定小数位：1.0 必须写成 "1.0"，不能塌成 "1"
        assertEquals("1.0", NumFmt.one(1.0f))
        assertEquals("0.0", NumFmt.one(0f))
        assertEquals("0.00", NumFmt.fixed(0f, 2))
        assertEquals("1.50", NumFmt.fixed(1.5f, 2))
        assertEquals("2.00", NumFmt.fixed(2f, 2))
    }

    @Test
    fun zeroDecimalsRoundsInsteadOfTruncating() {
        // 1.6 → 2，不能是 1（截断）
        assertEquals("2", NumFmt.fixed(1.6f, 0))
        assertEquals("3", NumFmt.fixed(2.5f, 0))
        assertEquals("1", NumFmt.fixed(1.4f, 0))
        // 0 位小数不输出小数点
        assertFalse(NumFmt.fixed(1.6f, 0).contains("."))
    }

    @Test
    fun negativeValuesKeepTheirSign() {
        assertEquals("-0.5", NumFmt.one(-0.5f))
        assertEquals("-1.5", NumFmt.one(-1.5f))
        assertEquals("-0.04", NumFmt.fixed(-0.04f, 2))
        assertEquals("-2", NumFmt.fixed(-1.6f, 0))
    }

    @Test
    fun negativeZeroBecomesPlainZero() {
        // 浮点误差产生的 -0.0 不该把负号带到界面上
        assertEquals("0.0", NumFmt.one(-0.0f))
        assertEquals("0.00", NumFmt.fixed(-0.0f, 2))
        // 舍入后是零，同样不能残留负号
        assertEquals("0.0", NumFmt.one(-0.04f))
        assertEquals("0.00", NumFmt.fixed(-0.004f, 2))
    }

    @Test
    fun nonFiniteValuesReturnReadablePlaceholder() {
        assertEquals(NumFmt.UNDEFINED, NumFmt.one(Float.NaN))
        assertEquals(NumFmt.UNDEFINED, NumFmt.one(Float.POSITIVE_INFINITY))
        assertEquals(NumFmt.UNDEFINED, NumFmt.one(Float.NEGATIVE_INFINITY))
        assertEquals(NumFmt.UNDEFINED, NumFmt.fixed(Float.NaN, 2))
        // 占位串要短、要能直接显示
        assertEquals("--", NumFmt.UNDEFINED)
        assertTrue(!NumFmt.UNDEFINED.contains(","), "占位串不该含逗号")
    }

    @Test
    fun hugeValuesDoNotOverflow() {
        // 1e20f 超出 Long 精确范围，不能抛异常、不能溢出成乱码
        val huge = NumFmt.fixed(1e20f, 1)
        assertTrue(huge.isNotEmpty(), "大数也要产出可读文本，实际=$huge")
        assertTrue(!huge.contains("NaN"), "大数不该出现 NaN，实际=$huge")
        assertTrue(!huge.contains("Infinity"), "大数不该出现 Infinity，实际=$huge")
        // Float 上限也不能崩
        assertTrue(NumFmt.fixed(Float.MAX_VALUE, 2).isNotEmpty())
        assertTrue(NumFmt.fixed(-Float.MAX_VALUE, 2).isNotEmpty())
    }

    @Test
    fun hugeNegativeValuesKeepTheirSign() {
        // 走科学计数法兜底时也不能把负号弄丢：
        // scientific() 只吃绝对值，负号必须在 fixed() 里补回去
        val negative = NumFmt.fixed(-1e20f, 1)
        assertTrue(negative.startsWith("-"), "大负数必须带负号，实际=$negative")
        assertTrue(!negative.contains("NaN"), "大负数不该出现 NaN，实际=$negative")
        assertTrue(!negative.contains("Infinity"), "大负数不该出现 Infinity，实际=$negative")
        assertTrue(
            negative.removePrefix("-") == NumFmt.fixed(1e20f, 1),
            "大负数的绝对值部分应与正数一致，实际=$negative vs ${NumFmt.fixed(1e20f, 1)}"
        )
    }

    @Test
    fun signedValuesAlwaysShowExplicitSign() {
        assertEquals("+1.5", NumFmt.signed(1.5f, 1))
        assertEquals("-1.5", NumFmt.signed(-1.5f, 1))
        assertEquals("+0.0", NumFmt.signed(0f, 1))
        assertEquals("+2", NumFmt.signed(1.6f, 0))
    }

    @Test
    fun integersAreLocaleFree() {
        assertEquals("78", NumFmt.int(78))
        assertEquals("0", NumFmt.int(0))
        assertEquals("-3", NumFmt.int(-3))
    }

    @Test
    fun decimalsAreClampedToSupportedRange() {
        // 负小数位夹到 0
        assertEquals("2", NumFmt.fixed(1.6f, -3))
        // 过大小数位夹到上限，不能炸
        assertTrue(NumFmt.fixed(1.5f, 999).isNotEmpty())
        assertTrue(NumFmt.fixed(1.5f, Int.MAX_VALUE).isNotEmpty())
    }

    @Test
    fun carryFromRoundedFractionIsPropagated() {
        // 9.999 保留 2 位 → 10.00（进位要带到整数部分）
        assertEquals("10.00", NumFmt.fixed(9.999f, 2))
        assertEquals("1.00", NumFmt.fixed(0.999f, 2))
    }

}
