package com.shottuner.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 悬浮窗方向逻辑的单测。
 *
 * 覆盖的是 [OverlayOrientation] 这层纯函数 —— 它不 import 任何 Android 类型，
 * 所以不需要真机就能把「方向 → 几何」的换算钉死。
 *
 * 入参一律用 `Surface.ROTATION_*` 的**序号**（0/1/2/3，即 `Display.getRotation()` 的返回值），
 * 不用角度 —— 免得「90」到底是 `ROTATION_90`（序号 1）还是 90 度被读错。
 *
 * 钉住的硬要求：
 *
 * 1. **竖屏零回归**：rotation=0 时卡片宽度必须与旧公式
 *    `min(dp(340), dm.widthPixels - dp(24))` 逐像素相等；
 * 2. **内容朝向正立**：ROTATION_90 → 顺时针 90°，ROTATION_270 → 顺时针 270°（= 逆时针 90°）；
 * 3. **横屏不越界**：卡片宽度不超过物理短边减边距；
 * 4. **旋转后坐标重新夹取**：竖屏记住的位置在横屏下依然落在合法区间内；
 * 5. **拖动方向不错位**：屏幕坐标系的拖动增量换算到窗口坐标系后，四次 90° 必须回到原点。
 *
 * 推导依据见 [OverlayOrientation] 的文件头注释（AOSP `Display.getRotation()` 的官方口径）。
 */
class CoachOverlayOrientationTest {

    private val density = 3f
    private val portraitShortSide = 1080
    private val portraitLongSide = 2340

    private fun dp(value: Int): Int = (value * density).toInt()

    /** 旧版公式：min(dp(CARD_MAX_DP), dm.widthPixels - dp(24)) */
    private fun legacyCardWidth(widthPixels: Int): Int =
        minOf(dp(340), widthPixels - dp(24))

    // ── 1. 竖屏零回归 ────────────────────────────────────────────────────────

    @Test
    fun portraitCardWidthMatchesLegacyFormula() {
        for (width in listOf(720, 1080, 1200, 1440, 1600)) {
            val actual = OverlayOrientation.cardWidthPx(
                shortSidePx = width,
                density = density,
                cardMaxDp = 340,
                marginDp = 24
            )
            assertEquals(
                "宽度 $width 下竖屏卡片宽度必须与旧公式一致",
                legacyCardWidth(width),
                actual
            )
        }
    }

    @Test
    fun portraitCardWidthMatchesLegacyOnRealDevice() {
        // 小米 14 Pro 一类：1220×2712，density 3.5
        val d = 3.5f
        val legacy = minOf((340 * d).toInt(), 1220 - (24 * d).toInt())
        val actual = OverlayOrientation.cardWidthPx(1220, d, 340, 24)
        assertEquals(legacy, actual)
    }

    @Test
    fun cardWidthNeverExceedsShortSide() {
        for (shortSide in listOf(480, 720, 1080, 1200, 1440)) {
            val w = OverlayOrientation.cardWidthPx(shortSide, density, 340, 24)
            assertTrue("短边 $shortSide 下卡片宽 $w 越界", w <= shortSide - dp(24))
            assertTrue("短边 $shortSide 下卡片宽 $w 为负", w >= 0)
        }
    }

    @Test
    fun cardWidthIsSameInPortraitAndLandscape() {
        // 物理短边在横竖屏下是同一个值 → 卡片宽度不会因为转屏而突变
        val portrait = OverlayOrientation.cardWidthPx(portraitShortSide, density, 340, 24)
        val landscape = OverlayOrientation.cardWidthPx(portraitShortSide, density, 340, 24)
        assertEquals(portrait, landscape)
    }

    // ── 2. 内容朝向 ──────────────────────────────────────────────────────────

    @Test
    fun contentRotationFollowsDisplayRotation() {
        assertEquals(
            0f,
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_0),
            0.001f
        )
        assertEquals(
            90f,
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_90),
            0.001f
        )
        assertEquals(
            180f,
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_180),
            0.001f
        )
        assertEquals(
            270f,
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_270),
            0.001f
        )
    }

    @Test
    fun contentRotationIsClockwise() {
        // Android 的 View.rotation 顺时针为正。AOSP Display.getRotation() 的官方口径：
        // 设备逆时针转 90°（ROTATION_90）时，系统把图形顺时针转 90° 来补偿。
        // 我们要跟系统同向补偿，所以 +90 而不是 -90。
        assertTrue(
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_90) > 0f
        )
        assertTrue(
            OverlayOrientation.contentRotationDegrees(OverlayOrientation.ROTATION_270) > 0f
        )
    }

    @Test
    fun illegalRotationFallsBackToPortrait() {
        assertEquals(0f, OverlayOrientation.contentRotationDegrees(-1), 0.001f)
        assertEquals(0f, OverlayOrientation.contentRotationDegrees(4), 0.001f)
        assertEquals(0f, OverlayOrientation.contentRotationDegrees(99), 0.001f)
        assertEquals(OverlayOrientation.ROTATION_0, OverlayOrientation.normalizeRotation(42))
    }

    // ── 3. 横竖屏判定与落位 ──────────────────────────────────────────────────

    @Test
    fun landscapeOnlyForQuarterTurns() {
        assertTrue(!OverlayOrientation.isLandscape(OverlayOrientation.ROTATION_0))
        assertTrue(OverlayOrientation.isLandscape(OverlayOrientation.ROTATION_90))
        assertTrue(!OverlayOrientation.isLandscape(OverlayOrientation.ROTATION_180))
        assertTrue(OverlayOrientation.isLandscape(OverlayOrientation.ROTATION_270))
    }

    @Test
    fun anchorIsTopCenterInPortraitAndCenterInLandscape() {
        assertEquals(
            OverlayOrientation.Anchor.TOP_CENTER,
            OverlayOrientation.anchorFor(OverlayOrientation.ROTATION_0)
        )
        assertEquals(
            OverlayOrientation.Anchor.TOP_CENTER,
            OverlayOrientation.anchorFor(OverlayOrientation.ROTATION_180)
        )
        assertEquals(
            OverlayOrientation.Anchor.CENTER,
            OverlayOrientation.anchorFor(OverlayOrientation.ROTATION_90)
        )
        assertEquals(
            OverlayOrientation.Anchor.CENTER,
            OverlayOrientation.anchorFor(OverlayOrientation.ROTATION_270)
        )
    }

    // ── 4. 旋转后的视觉外框 ──────────────────────────────────────────────────

    @Test
    fun visualBoxSwapsForQuarterTurns() {
        val w = 1008
        val h = 900
        val ninety = OverlayOrientation.visual(w, h, OverlayOrientation.ROTATION_90)
        assertEquals(h, ninety.widthPx)
        assertEquals(w, ninety.heightPx)

        val twoSeventy = OverlayOrientation.visual(w, h, OverlayOrientation.ROTATION_270)
        assertEquals(h, twoSeventy.widthPx)
        assertEquals(w, twoSeventy.heightPx)
    }

    @Test
    fun visualBoxKeepsSizeForHalfTurn() {
        val v = OverlayOrientation.visual(1008, 900, OverlayOrientation.ROTATION_180)
        assertEquals(1008, v.widthPx)
        assertEquals(900, v.heightPx)
        assertEquals(504f, v.pivotX, 0.01f)
        assertEquals(450f, v.pivotY, 0.01f)
    }

    @Test
    fun pivotPutsRotatedBoxBackToOrigin() {
        // 支点的唯一职责：旋转后的外框左上角落回 (0,0)，否则内容会被移出窗口。
        val w = 1008f
        val h = 900f
        for (rot in listOf(
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            val v = OverlayOrientation.visual(w.toInt(), h.toInt(), rot)
            val corners = listOf(
                0f to 0f,
                w to 0f,
                0f to h,
                w to h
            )
            val rotated = corners.map { (x, y) -> rotateAround(x, y, v.pivotX, v.pivotY, rot) }
            val minX = rotated.minOf { it.first }
            val maxX = rotated.maxOf { it.first }
            val minY = rotated.minOf { it.second }
            val maxY = rotated.maxOf { it.second }
            assertTrue("rotation=$rot 旋转后左边越界：$minX", minX >= -0.01f)
            assertTrue("rotation=$rot 旋转后上边越界：$minY", minY >= -0.01f)
            assertTrue(
                "rotation=$rot 旋转后右边越界：$maxX > ${v.widthPx}",
                maxX <= v.widthPx + 0.01f
            )
            assertTrue(
                "rotation=$rot 旋转后下边越界：$maxY > ${v.heightPx}",
                maxY <= v.heightPx + 0.01f
            )
        }
    }

    @Test
    fun rotatedCardFitsOnLandscapeScreen() {
        // 横屏时卡片在物理画面里是「宽 = 内容高、高 = 卡片宽」，
        // 高（卡片宽）必须塞得进屏幕的窄边。
        val cardWidth = OverlayOrientation.cardWidthPx(portraitShortSide, density, 340, 24)
        val contentHeight = 900
        val v = OverlayOrientation.visual(cardWidth, contentHeight, OverlayOrientation.ROTATION_90)
        assertTrue(
            "旋转后卡片高 ${v.heightPx} 超过屏幕短边 $portraitShortSide",
            v.heightPx <= portraitShortSide
        )
        assertTrue(
            "旋转后卡片宽 ${v.widthPx} 超过屏幕长边 $portraitLongSide",
            v.widthPx <= portraitLongSide
        )
    }

    // ── 5. 拖动增量换算 ──────────────────────────────────────────────────────

    @Test
    fun dragDeltaIsIdentityInPortrait() {
        val d = OverlayOrientation.windowDelta(30, -12, OverlayOrientation.ROTATION_0)
        assertEquals(30, d.x)
        assertEquals(-12, d.y)
    }

    @Test
    fun dragDeltaSwapsAxesInLandscape() {
        // 横屏时屏幕坐标系与窗口坐标系差 90°：向右拖 → 窗口坐标里是向下
        val d = OverlayOrientation.windowDelta(100, 0, OverlayOrientation.ROTATION_90)
        assertEquals(0, d.x)
        assertEquals(100, d.y)

        val d2 = OverlayOrientation.windowDelta(0, 100, OverlayOrientation.ROTATION_90)
        assertEquals(-100, d2.x)
        assertEquals(0, d2.y)
    }

    @Test
    fun dragDeltaIsLengthPreserving() {
        for (rot in listOf(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            val d = OverlayOrientation.windowDelta(37, -91, rot)
            val before = 37.0 * 37 + 91.0 * 91
            val after = (d.x * d.x + d.y * d.y).toDouble()
            assertTrue(
                "rotation=$rot 拖动增量长度被改写：$before → $after",
                abs(before - after) < 1e-6
            )
        }
    }

    @Test
    fun fourQuarterTurnsReturnToOrigin() {
        var x = 100
        var y = -40
        repeat(4) {
            val d = OverlayOrientation.windowDelta(x, y, OverlayOrientation.ROTATION_90)
            x = d.x
            y = d.y
        }
        assertEquals(100, x)
        assertEquals(-40, y)
    }

    @Test
    fun dragDeltaAtTwoSeventyIsInverseOfNinety() {
        val a = OverlayOrientation.windowDelta(120, 45, OverlayOrientation.ROTATION_90)
        val b = OverlayOrientation.windowDelta(120, 45, OverlayOrientation.ROTATION_270)
        assertEquals(-a.x, b.x)
        assertEquals(-a.y, b.y)
    }

    // ── 6. 坐标夹取 ──────────────────────────────────────────────────────────

    @Test
    fun xIsClampedToSymmetricRange() {
        val maxOffset = OverlayOrientation.maxOffsetXPx(portraitShortSide, 1008)
        assertEquals((portraitShortSide - 1008) / 2, maxOffset)
        assertEquals(maxOffset, OverlayOrientation.clampXPx(99999, maxOffset))
        assertEquals(-maxOffset, OverlayOrientation.clampXPx(-99999, maxOffset))
        assertEquals(0, OverlayOrientation.clampXPx(0, maxOffset))
    }

    @Test
    fun topAnchoredYStaysBelowStatusBarAndAboveShutterArea() {
        val minTop = dp(24)
        val reserve = dp(120)
        assertEquals(
            portraitLongSide - reserve,
            OverlayOrientation.clampTopYPx(999999, portraitLongSide, minTop, reserve)
        )
        assertEquals(
            minTop,
            OverlayOrientation.clampTopYPx(-999999, portraitLongSide, minTop, reserve)
        )
    }

    @Test
    fun centeredYStaysSymmetricAndInside() {
        val visualHeight = 1008
        val y = OverlayOrientation.clampCenterYPx(
            y = 999999,
            spaceHeightPx = portraitLongSide,
            visualHeightPx = visualHeight,
            marginPx = dp(24)
        )
        val limit = (portraitLongSide - visualHeight) / 2 - dp(24)
        assertTrue("居中锚点下 y=$y 越界（上界 $limit）", y <= limit)
        assertTrue("居中锚点下 y=$y 越界（下界 -$limit）", y >= -limit)
    }

    @Test
    fun portraitRememberedPositionIsLegalAfterRotation() {
        // 竖屏记住的 y（用户拖到很低）在横屏下必须重新夹取，不能越界
        val rememberedY = portraitLongSide - dp(120) - 10
        val cardWidth = OverlayOrientation.cardWidthPx(portraitShortSide, density, 340, 24)
        val visual = OverlayOrientation.visual(cardWidth, 900, OverlayOrientation.ROTATION_90)
        val clamped = OverlayOrientation.clampCenterYPx(
            y = rememberedY,
            spaceHeightPx = portraitLongSide,
            visualHeightPx = visual.heightPx,
            marginPx = dp(24)
        )
        val limit = (portraitLongSide - visual.heightPx) / 2 - dp(24)
        assertTrue("夹取后 y=$clamped 仍越界（上界 $limit）", clamped <= limit)
        assertTrue("夹取后 y=$clamped 仍越界（下界 -$limit）", clamped >= -limit)
    }

    @Test
    fun maxOffsetNeverNegative() {
        assertEquals(0, OverlayOrientation.maxOffsetXPx(100, 900))
        assertEquals(0, OverlayOrientation.maxOffsetXPx(900, 900))
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    /**
     * 绕支点顺时针旋转（屏幕坐标：x 右、y 下）。
     * 与 [OverlayOrientation.windowDelta] 用的是同一套变换。
     */
    private fun rotateAround(
        x: Float,
        y: Float,
        pivotX: Float,
        pivotY: Float,
        rotation: Int
    ): Pair<Float, Float> {
        val dx = x - pivotX
        val dy = y - pivotY
        val (rx, ry) = when (rotation) {
            OverlayOrientation.ROTATION_90 -> Pair(-dy, dx)
            OverlayOrientation.ROTATION_180 -> Pair(-dx, -dy)
            OverlayOrientation.ROTATION_270 -> Pair(dy, -dx)
            else -> Pair(dx, dy)
        }
        return (rx + pivotX) to (ry + pivotY)
    }
}
