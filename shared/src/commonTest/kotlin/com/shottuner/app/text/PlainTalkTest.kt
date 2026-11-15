package com.shottuner.app.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 人话层的回归测试。
 *
 * 这一层的产物是**用户唯一会读的那句话**，所以它错了不是「显示不好看」，
 * 是「用户照着做了但照片更糟」。下面钉住三类错：
 * ① 优先级错（糊掉的画面去说「亮度正好」）；
 * ② 方向错（该提亮说成压暗，用户越调越远）；
 * ③ 编数据（没读数时凭空给结论）。
 */
class PlainTalkTest {

    // ── 亮度 ──────────────────────────────────────────────────────────────────

    @Test
    fun highlightClipsBeatEverything() {
        // 大面积过曝：evDelta 说「正好」也不行，糊掉的像素救不回来
        assertEquals(
            "亮的地方已经糊了，压暗一点",
            PlainTalk.brightness(evDelta = 0f, highlightClip = 0.2f)
        )
        // 即使 evDelta 表示偏暗，过曝优先级更高
        assertEquals(
            "亮的地方已经糊了，压暗一点",
            PlainTalk.brightness(evDelta = 0.9f, highlightClip = 0.2f)
        )
    }

    @Test
    fun deadShadowsBeatEvDelta() {
        assertEquals(
            "暗的地方糊成一片，提亮一点",
            PlainTalk.brightness(evDelta = 0f, shadowClip = 0.5f)
        )
    }

    @Test
    fun directionIsNeverInverted() {
        // ★ 这条是本文件最重要的一条：正 evDelta = 偏暗 = 要提亮。
        //   反了的话用户越调越远，而且界面上看不出错。
        assertTrue(PlainTalk.brightness(evDelta = 0.5f).contains("提亮"))
        assertTrue(PlainTalk.brightness(evDelta = 1.5f).contains("提亮"))
        assertTrue(PlainTalk.brightness(evDelta = -0.5f).contains("压暗"))
        assertTrue(PlainTalk.brightness(evDelta = -1.5f).contains("压暗"))
    }

    @Test
    fun thresholdsAreGraduated() {
        assertEquals("亮度正好", PlainTalk.brightness(evDelta = 0f))
        assertEquals("亮度正好", PlainTalk.brightness(evDelta = 0.3f))
        assertEquals("有点暗，提亮一点", PlainTalk.brightness(evDelta = 0.31f))
        assertEquals("太暗了，提亮", PlainTalk.brightness(evDelta = 0.71f))
        assertEquals("有点亮，压暗一点", PlainTalk.brightness(evDelta = -0.31f))
        assertEquals("太亮了，压暗", PlainTalk.brightness(evDelta = -0.71f))
    }

    @Test
    fun fineFlagMatchesTheSentence() {
        // 绿标必须和文案一致，否则会出现「绿字说太暗了」
        assertTrue(PlainTalk.brightnessIsFine(0f))
        assertTrue(PlainTalk.brightnessIsFine(0.3f))
        assertFalse(PlainTalk.brightnessIsFine(0.31f))
        assertFalse(PlainTalk.brightnessIsFine(0f, highlightClip = 0.2f))
        assertFalse(PlainTalk.brightnessIsFine(0f, shadowClip = 0.5f))
    }

    @Test
    fun noJargonInTheVerdict() {
        // 结论里不许出现术语（术语只允许出现在括号里的原始数字）
        val all = listOf(
            PlainTalk.brightness(0f),
            PlainTalk.brightness(1f),
            PlainTalk.brightness(-1f),
            PlainTalk.brightness(0f, highlightClip = 0.2f),
            PlainTalk.brightness(0f, shadowClip = 0.5f)
        )
        val banned = listOf("EV", "ev", "截断", "直方图", "档", "%")
        for (s in all) {
            for (b in banned) {
                assertFalse(s.contains(b), "「$s」里出现了术语「$b」")
            }
        }
    }

    // ── 水平 ──────────────────────────────────────────────────────────────────

    @Test
    fun levelWithoutReadingSaysNothing() {
        // ★ 没读到传感器就返回空串，绝不编一个「水平了」
        assertEquals("", PlainTalk.level(isLevel = true, rollDeg = 0f, hasReading = false))
        assertEquals("", PlainTalk.level(isLevel = false, rollDeg = 3f, hasReading = false))
    }

    @Test
    fun levelGivesDirectionAndAmount() {
        assertEquals("水平了，按快门就行", PlainTalk.level(isLevel = true, rollDeg = 0.2f))
        // 顺时针歪（正角）要往左抬
        assertTrue(PlainTalk.level(isLevel = false, rollDeg = 3.4f).contains("往左抬"))
        assertTrue(PlainTalk.level(isLevel = false, rollDeg = -3.4f).contains("往右抬"))
        // 1° 以内不催人、也不给假精度
        assertEquals("快平了，微调一下", PlainTalk.level(isLevel = false, rollDeg = 0.6f))
        // 超过 1° 给整数度数，去掉小数（"约 3°" 比 "约 3.4°" 更像人话）
        assertEquals("歪了约 3°，往左抬一点", PlainTalk.level(isLevel = false, rollDeg = 3.4f))
    }

    // ── 参数 ──────────────────────────────────────────────────────────────────

    @Test
    fun tripodHintWinsOverEverything() {
        // 手持长曝一定糊 —— 这件事比任何参数都重要，必须说在最前面
        val s = PlainTalk.params(
            evDelta = 0f, iso = 3200, shutterLabel = "1/4s", kelvin = 3200, needsTripod = true
        )
        assertTrue(s.startsWith("光太暗了"), s)
        assertTrue(s.contains("3200"), "仍要让用户能照抄档位：$s")
    }

    @Test
    fun paramsStillCarryTheNumbers() {
        // 结论是人话，但档位必须留着 —— 那是产品的立身之本
        val s = PlainTalk.params(
            evDelta = 0f, iso = 400, shutterLabel = "1/60s", kelvin = 5600, needsTripod = false
        )
        assertEquals("不用调，直接拍 · 400 · 1/60s · 5600K", s)
    }

    @Test
    fun paramsDirectionMatchesBrightness() {
        // 同一个 evDelta，两处的方向必须一致，否则屏幕上自相矛盾
        val bright = PlainTalk.params(0.9f, 200, "1/30s", 5000, false)
        val dark = PlainTalk.params(-0.9f, 200, "1/500s", 5000, false)
        assertTrue(bright.contains("提"), bright)
        assertTrue(dark.contains("压"), dark)
        assertTrue(PlainTalk.brightness(0.9f).contains("提亮"))
        assertTrue(PlainTalk.brightness(-0.9f).contains("压暗"))
    }
}
