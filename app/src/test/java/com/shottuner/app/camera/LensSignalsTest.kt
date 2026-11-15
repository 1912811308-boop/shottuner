package com.shottuner.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LensSignals] 的单测。
 *
 * 钉住的是 1.3.0 那条硬要求：**前置时不许建议「切到 2 倍」**。
 * 这条要求落在两处输入上 —— `hasTele` 必须为 false（R05/R08 的闸门），
 * `equivFocalMm` 必须用前置自己的数（算不出来就是 0，不许拿后摄的 23mm 冒充）。
 *
 * `LensSignals` 不 import 任何 Android 类型，所以这里不需要 Robolectric/真机。
 */
class LensSignalsTest {

    // ── 前置：这一组是本次要修的东西 ──────────────────────────────────────────

    @Test
    fun frontNeverClaimsTele() {
        // 后置是三摄（backHasTele = true），前置也只有一颗 —— 不能把后置的长焦算到前置头上
        for (backHasTele in listOf(true, false)) {
            val v = LensSignals.of(
                isFront = true,
                backHasTele = backHasTele,
                backEquivFocalMm = 23,
                frontEquivFocalMm = 26
            )
            assertFalse(
                "前置恒无长焦，否则 R05 会劝「切到 2x」（前置 2x 是数字变焦，画质塌方）",
                v.hasTele
            )
        }
    }

    @Test
    fun frontUsesItsOwnEquivalentFocalLength() {
        val v = LensSignals.of(
            isFront = true,
            backHasTele = true,
            backEquivFocalMm = 23,
            frontEquivFocalMm = 26
        )
        assertEquals("前置要报前置的数，不许拿主摄的 23 冒充", 26, v.equivFocalMm)
    }

    @Test
    fun frontWithUnknownFocalLengthStaysUnknown() {
        // 读不到前置的传感器尺寸 → 0（未知）。这时宁可让规则闭嘴，也不要塞主摄的 23mm，
        // 否则 R05 会以为"这是颗 23mm 广角而且有长焦"，两条错误叠在一起放大。
        val v = LensSignals.of(
            isFront = true,
            backHasTele = true,
            backEquivFocalMm = 23,
            frontEquivFocalMm = 0
        )
        assertEquals(0, v.equivFocalMm)
        assertFalse(v.hasTele)
    }

    @Test
    fun frontNegativeFocalLengthTreatedAsUnknown() {
        // 负值只可能来自计算错误；把它当"超广角"喂给规则会得出更离谱的建议
        val v = LensSignals.of(
            isFront = true,
            backHasTele = true,
            backEquivFocalMm = 23,
            frontEquivFocalMm = -7
        )
        assertEquals(0, v.equivFocalMm)
    }

    // ── 后置：零回归 ──────────────────────────────────────────────────────────

    @Test
    fun backPassesProfileValuesThroughUnchanged() {
        val multiCam = LensSignals.of(
            isFront = false,
            backHasTele = true,
            backEquivFocalMm = 23,
            frontEquivFocalMm = 26
        )
        assertTrue("后置多摄必须保留 hasTele，R05/R08 靠它才有资格出牌", multiCam.hasTele)
        assertEquals("后置仍报主摄等效焦距", 23, multiCam.equivFocalMm)

        val singleCam = LensSignals.of(
            isFront = false,
            backHasTele = false,
            backEquivFocalMm = 25,
            frontEquivFocalMm = 26
        )
        assertFalse(singleCam.hasTele)
        assertEquals(25, singleCam.equivFocalMm)
    }

    @Test
    fun backIgnoresFrontValuesEntirely() {
        // 关键：前置档案不得以任何形式"漏"到后置路径上
        val a = LensSignals.of(false, true, 23, 999)
        val b = LensSignals.of(false, true, 23, 0)
        assertEquals(a, b)
    }

    // ── 两条组合语义 ──────────────────────────────────────────────────────────

    @Test
    fun unknownFrontIsNotWideSoWideFaceRulesCannotFire() {
        // R05 的闸门是 `equivFocalMm <= 28 && hasTele`。前置 hasTele=false 已经拦住，
        // 这里再确认 0 本身也不会被当成"广角范围内"的合法读数用出去。
        val v = LensSignals.of(true, true, 23, 0)
        val looksWide = v.equivFocalMm in 14..28
        assertFalse("未知焦距不得被当成广角", looksWide)
        assertFalse("前置永远不该出「切 2x」", v.hasTele && v.equivFocalMm <= 28)
    }
}
