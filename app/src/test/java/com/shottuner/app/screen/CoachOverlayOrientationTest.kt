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
                spaceWidthPx = width,
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

    /**
     * 真机确认后的模型（FOLLOW_DISPLAY）下，**横屏也必须是 TOP_CENTER**。
     *
     * ★ 这条是 2026-11-09 用户反馈「横屏悬浮卡侧躺、用不了」的直接回归测试：
     *   窗口跟随 display 时，横屏的「上边」就是真的上边，用 CENTER 会把卡片
     *   顶到画面正中、正好压住主体。之前 `anchorFor` 只看 rotation、不看模型，
     *   切模型时这一处没跟着切 —— 这类「改了一个开关，另一处硬编码没跟上」的错，
     *   只能靠测试钉住。
     */
    @Test
    fun followDisplayKeepsTopCenterInLandscape() {
        for (rotation in intArrayOf(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            assertEquals(
                "FOLLOW_DISPLAY 下 rotation=$rotation 应落在顶部居中",
                OverlayOrientation.Anchor.TOP_CENTER,
                OverlayOrientation.anchorFor(
                    rotation,
                    OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
                )
            )
        }
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

    // ── 7. 两种窗口坐标系模型（真机判错时的降级路径） ───────────────────────

    @Test
    fun lockedPortraitSpaceIsPortraitSized() {
        val (w, h) = OverlayOrientation.windowSpaceSize(
            model = OverlayOrientation.WindowSpaceModel.LOCKED_PORTRAIT,
            realWidthPx = 2340,
            realHeightPx = 1080,
            shortSidePx = 1080,
            longSidePx = 2340
        )
        assertEquals(1080, w)
        assertEquals(2340, h)
    }

    @Test
    fun followDisplaySpaceIsLogicalSized() {
        val (w, h) = OverlayOrientation.windowSpaceSize(
            model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY,
            realWidthPx = 2340,
            realHeightPx = 1080,
            shortSidePx = 1080,
            longSidePx = 2340
        )
        assertEquals(2340, w)
        assertEquals(1080, h)
    }

    @Test
    fun followDisplayModelNeedsNoContentRotation() {
        // 窗口跟随 display ⇒ 框架不再替我们旋转 ⇒ 内容不用预旋转
        for (rot in listOf(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            assertEquals(
                "rotation=$rot 在 FOLLOW_DISPLAY 模型下不该预旋转",
                0f,
                OverlayOrientation.contentRotationDegrees(
                    rot,
                    OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
                ),
                0.001f
            )
        }
    }

    @Test
    fun lockedPortraitModelStillRotatesContent() {
        assertEquals(
            90f,
            OverlayOrientation.contentRotationDegrees(
                OverlayOrientation.ROTATION_90,
                OverlayOrientation.WindowSpaceModel.LOCKED_PORTRAIT
            ),
            0.001f
        )
    }

    @Test
    fun followDisplayModelDragDeltaIsIdentity() {
        // 窗口坐标系 = 屏幕坐标系 ⇒ 不换算
        for (rot in listOf(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            val d = OverlayOrientation.windowDelta(
                screenDx = 77,
                screenDy = -33,
                rotation = rot,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            )
            assertEquals("rotation=$rot 增量 x 被换算了", 77, d.x)
            assertEquals("rotation=$rot 增量 y 被换算了", -33, d.y)
        }
    }

    @Test
    fun portraitCardWidthIsIdenticalUnderBothModels() {
        // 竖屏时两个模型的窗口坐标系宽度同为 1080 ⇒ 卡片宽度必须一致（旧行为零回归）
        val locked = OverlayOrientation.cardWidthPx(1080, density, 340, 24)
        val follow = OverlayOrientation.cardWidthPx(1080, density, 340, 24)
        assertEquals(locked, follow)
        assertEquals(legacyCardWidth(1080), locked)
    }

    @Test
    fun landscapeCardWidthFollowsModelSpace() {
        // 锁在竖屏坐标系 → 宽度受物理短边约束（1008）；
        // 跟随 display → 宽度受逻辑宽约束，但仍是 dp(340) 上限（1020）
        val locked = OverlayOrientation.cardWidthPx(1080, density, 340, 24)
        val follow = OverlayOrientation.cardWidthPx(2340, density, 340, 24)
        assertEquals(1008, locked)
        assertEquals(dp(340), follow)
        assertTrue("跟随 display 时卡片宽不能超过逻辑宽", follow <= 2340 - dp(24))
    }

    // ── 8. 双信号朝向判定（v3.1.2：横屏不跟随的修复） ────────────────────────

    /**
     * ① display 说自己转了 ⇒ 窗口已经跟着 display 转好，内容不补。
     *
     * 这条是**老行为不能变**的回归线：真机横屏（display 真的转了）时，
     * 若还额外汇 90°，卡片会侧躺 —— 那正是 v3.1.1 修过的另一个 bug。
     */
    @Test
    fun followDisplayTrustsRotatedDisplay() {
        for (rot in listOf(
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.ROTATION_270
        )) {
            assertEquals(
                "FOLLOW_DISPLAY + display=$rot 不该预旋转",
                0f,
                OverlayOrientation.effectiveContentDegrees(
                    displayRotation = rot,
                    deviceRollDeg = 0f,
                    model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
                ),
                0.001f
            )
            // display 已经转了，设备角度再离谱也不该改变结论（display 优先）
            assertEquals(
                "FOLLOW_DISPLAY + display=$rot 时设备角度不得覆盖 display",
                0f,
                OverlayOrientation.effectiveContentDegrees(
                    displayRotation = rot,
                    deviceRollDeg = 90f,
                    model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
                ),
                0.001f
            )
        }
    }

    /**
     * ② **本次 bug 的场景**：系统相机界面转了，`Display.getRotation()` 仍是 0，
     * 设备物理上横过来（roll = +90，顶部朝左）⇒ 内容顺时针补 90°。
     *
     * 小米 14 Pro / MIUI 的系统相机就是这一条：竖屏锁定 + 界面内部旋转。
     * 修之前这里返回 0f，用户看到的就是「字还是竖排」。
     */
    @Test
    fun followDisplayUsesDeviceRollWhenDisplaySaysPortrait() {
        assertEquals(
            "display 没转、设备顶部朝左 ⇒ 补 +90°",
            90f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 90f,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            ),
            0.001f
        )
    }

    /** ③ 镜像场景：顶部朝右（roll = -90）⇒ 补 -90°（视觉上等于 270°） */
    @Test
    fun deviceRollMirrorsForTopRight() {
        assertEquals(
            -90f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = -90f,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            ),
            0.001f
        )
    }

    /** ④ 竖持手抖不许触发：roll = ±20 是正常握持抖动，必须还是 0° */
    @Test
    fun portraitHandJitterDoesNotRotateContent() {
        for (roll in listOf(0f, 5f, -5f, 20f, -20f, 30f, -30f, 54f, -54f)) {
            assertEquals(
                "roll=$roll 属竖持抖动，不该预旋转",
                0f,
                OverlayOrientation.effectiveContentDegrees(
                    displayRotation = OverlayOrientation.ROTATION_0,
                    deviceRollDeg = roll,
                    model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
                ),
                0.001f
            )
        }
    }

    /** ⑤ 刚过阈值：55 是「≥ 55 判定为横」，56 必须触发，55 本身也触发 */
    @Test
    fun thresholdBoundaryIsInclusive() {
        assertEquals(
            90f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 56f,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            ),
            0.001f
        )
        assertEquals(
            "阈值本身算横屏（>=）",
            90f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = OverlayOrientation.LANDSCAPE_ROLL_THRESHOLD_DEG,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            ),
            0.001f
        )
        assertEquals(
            "阈值下方一点点不算横屏",
            0f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = OverlayOrientation.LANDSCAPE_ROLL_THRESHOLD_DEG - 0.1f,
                model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
            ),
            0.001f
        )
    }

    /** ⑥ 被锁在竖屏坐标系时同样要用设备角度兜底（display 说不出话的情况） */
    @Test
    fun lockedPortraitAlsoFallsBackToDeviceRoll() {
        assertEquals(
            90f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 90f,
                model = OverlayOrientation.WindowSpaceModel.LOCKED_PORTRAIT
            ),
            0.001f
        )
        // display 转了时老逻辑一字不改
        assertEquals(
            270f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_270,
                deviceRollDeg = 0f,
                model = OverlayOrientation.WindowSpaceModel.LOCKED_PORTRAIT
            ),
            0.001f
        )
        // 竖持 → 0
        assertEquals(
            0f,
            OverlayOrientation.effectiveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = -12f,
                model = OverlayOrientation.WindowSpaceModel.LOCKED_PORTRAIT
            ),
            0.001f
        )
    }

    /** 脏数据不许把卡片翻掉：NaN / Inf / 超过一圈的 roll 都要能安全处理 */
    @Test
    fun dirtyRollAndRotationAreSafe() {
        val follow = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
        assertEquals(
            0f,
            OverlayOrientation.effectiveContentDegrees(
                OverlayOrientation.ROTATION_0, Float.NaN, follow
            ),
            0.001f
        )
        assertEquals(
            0f,
            OverlayOrientation.effectiveContentDegrees(
                OverlayOrientation.ROTATION_0, Float.POSITIVE_INFINITY, follow
            ),
            0.001f
        )
        // 一圈以外的角度先规整：450° == 90° ⇒ 横持
        assertEquals(
            90f,
            OverlayOrientation.effectiveContentDegrees(
                OverlayOrientation.ROTATION_0, 450f, follow
            ),
            0.001f
        )
        // -270° == +90° ⇒ 顶部朝左
        assertEquals(
            90f,
            OverlayOrientation.effectiveContentDegrees(
                OverlayOrientation.ROTATION_0, -270f, follow
            ),
            0.001f
        )
        // 非法 rotation 序号按 0 处理（沿用 normalizeRotation 的约定）
        assertEquals(
            90f,
            OverlayOrientation.effectiveContentDegrees(99, 90f, follow),
            0.001f
        )
    }

    /**
     * ⑦ **手动档位优先于自动判定** —— 自救开关必须压得住自动结果。
     *
     * 场景：自动认为「竖屏、不用转」，用户明确选了「强制横屏（顶部朝左）」⇒ 必须 90°。
     * 反过来也一样：自动认为该转 90°，用户选「竖屏」⇒ 必须 0°。
     */
    @Test
    fun manualOverrideBeatsAutomaticDecision() {
        val follow = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
        val manual = OverlayOrientation.ManualOrientation.FORCE_LANDSCAPE_TOP_LEFT

        // 自动说 0°，手动说 90° ⇒ 90°
        assertEquals(
            90f,
            OverlayOrientation.resolveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 0f,
                model = follow,
                manual = manual
            ),
            0.001f
        )
        // 自动说 90°（设备横着），手动说竖屏 ⇒ 0°
        assertEquals(
            0f,
            OverlayOrientation.resolveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 90f,
                model = follow,
                manual = OverlayOrientation.ManualOrientation.FORCE_PORTRAIT
            ),
            0.001f
        )
        // 自动（AUTO）时与 effectiveContentDegrees 逐值一致
        assertEquals(
            OverlayOrientation.effectiveContentDegrees(
                OverlayOrientation.ROTATION_0, 90f, follow
            ),
            OverlayOrientation.resolveContentDegrees(
                displayRotation = OverlayOrientation.ROTATION_0,
                deviceRollDeg = 90f,
                model = follow,
                manual = OverlayOrientation.ManualOrientation.AUTO
            ),
            0.001f
        )
    }

    /** 手动档位的四个取值 → 固定角度；AUTO 返回 null 表示「不强制」 */
    @Test
    fun manualModesMapToFixedAngles() {
        assertEquals(
            null,
            OverlayOrientation.manualContentDegrees(OverlayOrientation.ManualOrientation.AUTO)
        )
        assertEquals(
            0f,
            OverlayOrientation.manualContentDegrees(
                OverlayOrientation.ManualOrientation.FORCE_PORTRAIT
            )
        )
        assertEquals(
            90f,
            OverlayOrientation.manualContentDegrees(
                OverlayOrientation.ManualOrientation.FORCE_LANDSCAPE_TOP_LEFT
            )
        )
        // 270° 与 -90° 视觉等价，这里统一用正的 270°（与 Surface.ROTATION_270 对齐）
        assertEquals(
            270f,
            OverlayOrientation.manualContentDegrees(
                OverlayOrientation.ManualOrientation.FORCE_LANDSCAPE_TOP_RIGHT
            )
        )
    }

    /** 档位 ↔ 字符串：脏值一律回落「自动」，否则一次读错就把用户锁死在固定朝向 */
    @Test
    fun manualOrientationPersistsByNameAndFallsBackToAuto() {
        for (mode in OverlayOrientation.ManualOrientation.entries) {
            assertEquals(
                "档位 $mode 存盘再读必须还原",
                mode,
                OverlayOrientation.parseManualOrientation(
                    OverlayOrientation.manualOrientationKey(mode)
                )
            )
        }
        assertEquals(
            OverlayOrientation.ManualOrientation.AUTO,
            OverlayOrientation.parseManualOrientation(null)
        )
        assertEquals(
            OverlayOrientation.ManualOrientation.AUTO,
            OverlayOrientation.parseManualOrientation("")
        )
        assertEquals(
            OverlayOrientation.ManualOrientation.AUTO,
            OverlayOrientation.parseManualOrientation("FORCE_LANDSCAPE_LEFT")
        )
        assertEquals(
            OverlayOrientation.ManualOrientation.AUTO,
            OverlayOrientation.parseManualOrientation("2")
        )
    }

    /** 预旋转角 → 旋转壳用的序号：-90 必须换回 ROTATION_270，否则外壳尺寸算反 */
    @Test
    fun rotationIndexOfRoundTrips() {
        assertEquals(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.rotationIndexOf(0f)
        )
        assertEquals(
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.rotationIndexOf(90f)
        )
        assertEquals(
            OverlayOrientation.ROTATION_180,
            OverlayOrientation.rotationIndexOf(180f)
        )
        assertEquals(
            OverlayOrientation.ROTATION_270,
            OverlayOrientation.rotationIndexOf(270f)
        )
        assertEquals(
            "负角按顺时针折算",
            OverlayOrientation.ROTATION_270,
            OverlayOrientation.rotationIndexOf(-90f)
        )
        assertEquals(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.rotationIndexOf(360f)
        )
        assertEquals(
            "非 90° 倍数就近取整",
            OverlayOrientation.ROTATION_90,
            OverlayOrientation.rotationIndexOf(100f)
        )
        assertEquals(
            OverlayOrientation.ROTATION_0,
            OverlayOrientation.rotationIndexOf(Float.NaN)
        )
    }

    /** 四分之一转的判定：90 / 270 才要宽高对调（旋转壳的尺寸算法据此走） */
    @Test
    fun quarterTurnDetection() {
        assertTrue(OverlayOrientation.isQuarterTurn(90f))
        assertTrue(OverlayOrientation.isQuarterTurn(-90f))
        assertTrue(OverlayOrientation.isQuarterTurn(270f))
        assertTrue(!OverlayOrientation.isQuarterTurn(0f))
        assertTrue(!OverlayOrientation.isQuarterTurn(180f))
    }

    /**
     * 本次修复与外壳几何的接缝：display 说竖屏、设备横着（roll = 90）时，
     * 旋转 90° 后的视觉外框必须仍然塞得进屏幕（否则卡片会被裁掉一截）。
     *
     * 这一条把「内容角度」与「visual() 的宽高对调」两处口径钉在一起 ——
     * 分开测都对、合起来错的 bug 只能这么抓。
     */
    @Test
    fun compensatedLandscapeBoxFitsScreen() {
        val degrees = OverlayOrientation.effectiveContentDegrees(
            displayRotation = OverlayOrientation.ROTATION_0,
            deviceRollDeg = 90f,
            model = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY
        )
        val idx = OverlayOrientation.rotationIndexOf(degrees)
        val cardWidth = OverlayOrientation.cardWidthPx(portraitShortSide, density, 340, 24)
        val contentHeight = 900
        val v = OverlayOrientation.visual(cardWidth, contentHeight, idx)
        assertTrue("旋转后宽度 ${v.widthPx} 超过物理长边", v.widthPx <= portraitLongSide)
        assertTrue("旋转后高度 ${v.heightPx} 超过物理短边", v.heightPx <= portraitShortSide)
        // 内容确实落在窗口原点右上角那块区域里（支点没算错）
        assertEquals(contentHeight, v.widthPx)
        assertEquals(cardWidth, v.heightPx)
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
