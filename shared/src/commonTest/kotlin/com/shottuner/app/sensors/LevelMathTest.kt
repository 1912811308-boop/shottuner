package com.shottuner.app.sensors

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.abs

/**
 * 姿态数学测试。
 *
 * 约定：`roll > 0` = **画面右侧偏低**（与 `CompositionHint.tiltDeg` 同号）。
 * 修正方向：右端偏低 → 手机顺时针转回去。前置镜像必须翻转方向。
 */
class LevelMathTest {

    private val g = 9.81f

    @Test
    fun portraitUprightIsZero() {
        val reading = LevelMath.solve(0f, g, 0f, 0)
        assertNotNull(reading)
        assertTrue(abs(reading.roll) < 0.5f, "竖直握持 roll 应接近 0，实际 ${reading!!.roll}")
        assertTrue(abs(reading.pitch) < 0.5f, "竖直握持 pitch 应接近 0，实际 ${reading.pitch}")
    }

    @Test
    fun rollRightIsPositive() {
        val reading = LevelMath.solve(2f, 9.4f, 0f, 0)
        assertNotNull(reading)
        assertTrue(reading.roll > 0f, "画面右端偏低时 roll 为正，实际 ${reading!!.roll}")
        assertTrue(abs(reading.roll - 12f) < 1.5f, "幅度应约为 12°")
    }

    @Test
    fun rollLeftIsNegative() {
        val reading = LevelMath.solve(-2f, 9.4f, 0f, 0)
        assertNotNull(reading)
        assertTrue(reading!!.roll < 0f, "画面左端偏低时 roll 为负")
    }

    @Test
    fun pitchDownIsNegative() {
        // 手机前倾俯拍：z 分量变正 → pitch 为负
        val reading = LevelMath.solve(0f, 6.9f, 6.9f, 0)
        assertNotNull(reading)
        assertTrue(reading.pitch < 0f, "俯拍 pitch 必须为负，实际 ${reading!!.pitch}")
    }

    @Test
    fun pitchUpIsPositive() {
        val reading = LevelMath.solve(0f, 6.9f, -6.9f, 0)
        assertNotNull(reading)
        assertTrue(reading.pitch > 0f, "仰拍 pitch 必须为正，实际 ${reading!!.pitch}")
    }

    @Test
    fun rotation90SwapsAxes() {
        val portrait = LevelMath.solve(2f, 9.4f, 0f, 0)
        val landscape = LevelMath.solve(2f, 9.4f, 0f, 90)
        assertNotNull(portrait)
        assertNotNull(landscape)
        assertNotEquals(portrait!!.roll, landscape!!.roll, 0.01f, "旋转 90° 后必须换轴计算")
        // 90° 时横滚由 gravity.y 主导 → 绝对值明显变大
        assertTrue(abs(landscape.roll) > 45f, "横屏时该轴成为主倾角")
    }

    @Test
    fun rotation180And270AreStable() {
        // 倒持：重力在设备坐标系里反向，算出来仍应接近 0
        val upsideDown = LevelMath.solve(0f, -g, 0f, 180)
        assertNotNull(upsideDown)
        assertTrue(abs(upsideDown!!.roll) < 0.5f, "倒持也应是 0°")

        // 270° 与 90° 互为镜像，符号相反
        val r90 = LevelMath.solve(2f, 9.4f, 0f, 90)
        val r270 = LevelMath.solve(-2f, -9.4f, 0f, 270)
        assertNotNull(r90)
        assertNotNull(r270)
        assertTrue(abs(abs(r90!!.roll) - abs(r270!!.roll)) < 1f)
    }

    @Test
    fun degenerateZeroGravity() {
        assertNull(LevelMath.solve(0f, 0f, 0f, 0), "零重力必须返回 null，不能返回 NaN")
        assertNull(LevelMath.solve(0.2f, 0.3f, 0.1f, 0))
        assertNull(LevelMath.solve(Float.NaN, g, 0f, 0), "NaN 输入也要挡住")
    }

    @Test
    fun rollHintDirectionIsClockwiseWhenRightSideLow() {
        val hint = LevelMath.rollHint(4.2f, mirrored = false)
        assertEquals(4, hint.degrees, "角度取整")
        assertEquals("顺时针", hint.direction, "画面右端偏低 → 顺时针转")
        assertTrue(hint.text.contains("4°"), "文案要带角度")
    }

    @Test
    fun rollHintMirroredFlipsDirection() {
        val back = LevelMath.rollHint(4.2f, mirrored = false)
        val front = LevelMath.rollHint(4.2f, mirrored = true)
        assertEquals("逆时针", front.direction, "前置镜像后方向必须相反")
        assertNotEquals(back.direction, front.direction, "方向不能一样，否则用户越转越歪")
        assertEquals(back.degrees, front.degrees, "角度不因镜像而变")
    }

    @Test
    fun rollHintNegativeRollIsCounterClockwise() {
        assertEquals("逆时针", LevelMath.rollHint(-5f, mirrored = false).direction)
        assertEquals("顺时针", LevelMath.rollHint(-5f, mirrored = true).direction)
    }

    @Test
    fun isLevelUsesOneDegreeTolerance() {
        assertTrue(LevelMath.isLevel(0f))
        assertTrue(LevelMath.isLevel(1f))
        assertTrue(LevelMath.isLevel(-1f))
        assertFalse(LevelMath.isLevel(1.2f))
        assertFalse(LevelMath.isLevel(null), "没有数据不算平")
    }

    @Test
    fun normalizeDegreesWrapsIntoRange() {
        assertEquals(10f, LevelMath.normalizeDegrees(370f), 0.001f)
        assertEquals(-10f, LevelMath.normalizeDegrees(-370f), 0.001f)
        assertEquals(0f, LevelMath.normalizeDegrees(Float.NaN), 0.001f)
    }
}
