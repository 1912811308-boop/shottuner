package com.shottuner.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [NumFmt] 的回归测试。
 *
 * 核心价值只有一条：**小数点永远是 `.`**，跟系统语言设置无关。
 * 德语区（逗号作小数点）和法语区（逗号 + 窄不换行空格作千分位）都被显式钉死。
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
        assertTrue("占位串不该含逗号", !NumFmt.UNDEFINED.contains(","))
    }

    @Test
    fun hugeValuesDoNotOverflow() {
        // 1e20f 超出 Long 精确范围，不能抛异常、不能溢出成乱码
        val huge = NumFmt.fixed(1e20f, 1)
        assertTrue("大数也要产出可读文本，实际=$huge", huge.isNotEmpty())
        assertTrue("大数不该出现 NaN，实际=$huge", !huge.contains("NaN"))
        assertTrue("大数不该出现 Infinity，实际=$huge", !huge.contains("Infinity"))
        // Float 上限也不能崩
        assertTrue(NumFmt.fixed(Float.MAX_VALUE, 2).isNotEmpty())
        assertTrue(NumFmt.fixed(-Float.MAX_VALUE, 2).isNotEmpty())
    }

    @Test
    fun hugeNegativeValuesKeepTheirSign() {
        // 走科学计数法兜底时也不能把负号弄丢：
        // scientific() 只吃绝对值，负号必须在 fixed() 里补回去
        val negative = NumFmt.fixed(-1e20f, 1)
        assertTrue("大负数必须带负号，实际=$negative", negative.startsWith("-"))
        assertTrue("大负数不该出现 NaN，实际=$negative", !negative.contains("NaN"))
        assertTrue("大负数不该出现 Infinity，实际=$negative", !negative.contains("Infinity"))
        assertTrue(
            "大负数的绝对值部分应与正数一致，实际=$negative vs ${NumFmt.fixed(1e20f, 1)}",
            negative.removePrefix("-") == NumFmt.fixed(1e20f, 1)
        )
    }

    @Test
    fun germanLocaleStillUsesDotDecimalSeparator() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("切换到德语区后生效", Locale.GERMANY, Locale.getDefault())

            assertEquals("1.5", NumFmt.one(1.5f))
            assertEquals("1.50", NumFmt.fixed(1.5f, 2))
            assertEquals("-0.5", NumFmt.one(-0.5f))
            assertEquals("0.0", NumFmt.one(-0.0f))
            assertEquals("2", NumFmt.fixed(1.6f, 0))
            assertEquals("3.0", NumFmt.one(3f))

            assertLocaleFree("德语区", NumFmt.one(1.5f))
            assertLocaleFree("德语区", NumFmt.fixed(1234.5f, 2))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun frenchLocaleStillUsesDotDecimalSeparator() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("切换到法语区后生效", Locale.FRANCE, Locale.getDefault())

            assertEquals("1.5", NumFmt.one(1.5f))
            assertEquals("1.50", NumFmt.fixed(1.5f, 2))
            assertEquals("-0.5", NumFmt.one(-0.5f))
            assertEquals("0.0", NumFmt.one(-0.0f))
            assertEquals("2", NumFmt.fixed(1.6f, 0))
            assertEquals("3.0", NumFmt.one(3f))

            // 法语区还牵涉窄不换行空格（U+202F）作千分位分隔符，
            // 一旦实现里不小心用了 NumberFormat，这里就会露出
            assertLocaleFree("法语区", NumFmt.one(1.5f))
            assertLocaleFree("法语区", NumFmt.fixed(1234.5f, 2))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun outputIsIndependentOfDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val us = NumFmt.fixed(12.34f, 2)
            Locale.setDefault(Locale.GERMANY)
            val de = NumFmt.fixed(12.34f, 2)
            Locale.setDefault(Locale.FRANCE)
            val fr = NumFmt.fixed(12.34f, 2)
            assertEquals("同一数值在不同 Locale 下必须完全一致", us, de)
            assertEquals("同一数值在不同 Locale 下必须完全一致", us, fr)
        } finally {
            Locale.setDefault(original)
        }
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

    /**
     * 断言输出里没有任何 locale 相关字符：
     * 逗号（德语/法语的小数点）、普通空格、不换行空格 U+00A0、
     * 窄不换行空格 U+202F（法语千分位分隔符）。
     */
    private fun assertLocaleFree(context: String, text: String) {
        assertFalse("$context：输出不该含逗号，实际=$text", text.contains(","))
        assertFalse("$context：输出不该含普通空格，实际=$text", text.contains(" "))
        assertFalse("$context：输出不该含不换行空格，实际=$text", text.contains("\u00A0"))
        assertFalse("$context：输出不该含窄不换行空格，实际=$text", text.contains("\u202F"))
        assertFalse("$context：输出不该含任意空白字符，实际=$text", text.any { it.isWhitespace() })
    }
}
