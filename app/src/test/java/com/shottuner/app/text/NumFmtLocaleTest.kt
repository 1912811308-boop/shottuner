package com.shottuner.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * [NumFmt] 的 **Locale 维度**回归测试（Android 专属）。
 *
 * ## 为什么这三个用例留在 app 侧，而不是 `shared/src/commonTest/`
 *
 * 它们验证的是「**Android 上**德语区/法语区不再输出逗号」——
 * 这依赖 `java.util.Locale.setDefault()` 这个 **JVM-only** API。
 * Kotlin/Native 的 common 代码里根本没有 `java.util.Locale`，
 * iOS 侧也不存在「系统语言设置影响 Kotlin 数值格式化」这回事
 * （[NumFmt] 自己不依赖任何 Locale，见它的 KDoc）。
 *
 * 所以：把这三个用例放进 `commonTest` 只会让 commonTest 在 iOS 上编译失败，
 * 却不给 iOS 带来任何验证价值 —— **纯负担、零收益**。
 *
 * ## 与 [NumFmtTest] 的分工
 *
 * - `shared/src/commonTest/.../NumFmtTest.kt`：11 个**纯数值**用例
 *   （给定输入 → 期望输出），是 iOS 端要靠它验证 [NumFmt] 核心行为的金标准；
 * - 本文件：3 个**切系统 Locale**的用例，是 Android 专属缺陷修复的验证。
 *
 * 断言内容与迁移前逐字一致，只换了文件位置与所在模块。
 */
class NumFmtLocaleTest {

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
