package com.shottuner.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ManualWriteGate] 的单测。
 *
 * 钉的是 1.3.3 那个 bug 背后真正承重的不变量：
 * **「上一次写进相机的值」被清零（0）时，下一帧必须写**。
 *
 * 为什么这条不变量值得单测：`MainActivity` 在**重建引擎**（相机出错重试、重新授权）
 * 之后会把 `appliedIso` / `appliedShutterNs` 清零，指望的就是"清零 ⟹ 下一帧必写"，
 * 从而把界面上的意图重新落回相机。这条路径看不见、摸不着，只能靠这个闸门保证；
 * 一旦有人把 0 也送去走阈值比较，界面上会出现「停止本机写入」而相机在自动曝光 ——
 * 也就是 `applyManual` 写完一次就自我关闭那种病的另一个入口。
 */
class ManualWriteGateTest {

    // ── 零值是"必须写"，不是"没有变化" ─────────────────────────────────────

    @Test
    fun neverWrittenForcesWrite() {
        // 冷启动 / 换了新引擎 / 刚清零：0 就是"不知道相机现在是什么"
        assertTrue(
            "appliedIso == 0 必须写，否则收敛循环永远空转",
            ManualWriteGate.shouldWrite(
                nowMs = 10_000L,
                lastApplyAtMs = 0L,
                appliedIso = 0,
                appliedShutterNs = 0L,
                targetIso = 400,
                targetShutterNs = 8_000_000L
            )
        )
    }

    @Test
    fun rewindToZeroForcesSameWriteAgain() {
        // 这是"重试后要能自愈"的承重用例：同样的目标值，第一次写过 → 被节流；
        // 清零之后 → 又必须写。两次的差别只有一个 0。
        val target = 800
        val targetShutter = 16_000_000L
        val suppressed = ManualWriteGate.shouldWrite(
            nowMs = 100_000L,
            lastApplyAtMs = 99_500L,
            appliedIso = target,
            appliedShutterNs = targetShutter,
            targetIso = target,
            targetShutterNs = targetShutter
        )
        assertFalse("刚写过同样的值，不该再重建 UseCase", suppressed)

        val afterRewind = ManualWriteGate.shouldWrite(
            nowMs = 100_000L,
            lastApplyAtMs = 0L,
            appliedIso = 0,
            appliedShutterNs = 0L,
            targetIso = target,
            targetShutterNs = targetShutter
        )
        assertTrue("清零后同样的目标值也必须重新写进相机", afterRewind)
    }

    @Test
    fun onlyIsoUnknownIsEnoughToForceWrite() {
        // 快门有值、ISO 未知（或反之）也要写：两个数是分别判定的
        assertTrue(
            ManualWriteGate.shouldWrite(50_000L, 0L, 0, 16_000_000L, 500, 16_000_000L)
        )
        assertTrue(
            ManualWriteGate.shouldWrite(50_000L, 0L, 500, 0L, 500, 16_000_000L)
        )
    }

    // ── 节流窗口 ────────────────────────────────────────────────────────────

    @Test
    fun intervalSuppressesEvenHugeDrift() {
        // 抖得再厉害也不许在 1.2s 内重建第二次：一次 bind 就是一帧黑屏
        assertFalse(
            ManualWriteGate.shouldWrite(
                nowMs = 1_000L,
                lastApplyAtMs = 0L,
                appliedIso = 100,
                appliedShutterNs = 1_000_000L,
                targetIso = 6_400,
                targetShutterNs = 100_000_000L
            )
        )
    }

    @Test
    fun intervalBoundaryIsInclusive() {
        val base = 10_000L
        val applied = 400
        val appliedShutter = 8_000_000L
        val target = 6_400
        val targetShutter = 8_000_000L

        assertFalse(
            "刚好差 1199ms：还在窗口内",
            ManualWriteGate.shouldWrite(
                base + 1_199L, base, applied, appliedShutter, target, targetShutter
            )
        )
        assertTrue(
            "正好 1200ms：窗口已过，允许写",
            ManualWriteGate.shouldWrite(
                base + ManualWriteGate.MIN_INTERVAL_MS, base, applied, appliedShutter, target, targetShutter
            )
        )
    }

    // ── 阈值：相对比例 + 硬下限 ──────────────────────────────────────────────

    @Test
    fun smallIsoDriftIsSuppressed() {
        // ISO 800 的 15% 是 120，差 100 不值得写
        assertFalse(
            ManualWriteGate.shouldWrite(50_000L, 0L, 800, 16_000_000L, 900, 16_000_000L)
        )
    }

    @Test
    fun largeIsoDriftPasses() {
        // 差 200 > 120
        assertTrue(
            ManualWriteGate.shouldWrite(50_000L, 0L, 800, 16_000_000L, 1_000, 16_000_000L)
        )
    }

    @Test
    fun isoThresholdHasFloorForBrightScenes() {
        // 白天 ISO 100：15% 只有 15，差 12 就该写吗？下限是 10，所以 12 > 10 → 写。
        // 真正要钉的是"不会因为比例太小而一直写"：差 9 不写。
        assertEquals(15, ManualWriteGate.isoThreshold(100))
        assertFalse(
            ManualWriteGate.shouldWrite(50_000L, 0L, 100, 16_000_000L, 109, 16_000_000L)
        )
        assertTrue(
            ManualWriteGate.shouldWrite(50_000L, 0L, 100, 16_000_000L, 116, 16_000_000L)
        )
        // ISO 40 在若干机型上是下限值：这时 15% 会是 6，必须被 10 兜住，
        // 否则会出现"一直在写"的抖动。
        assertEquals(ManualWriteGate.MIN_ISO_THRESHOLD, ManualWriteGate.isoThreshold(40))
    }

    @Test
    fun shutterThresholdUsesPercentAboveTwoMilliseconds() {
        // 1/60s ≈ 16.6ms，20% ≈ 3.3ms。差 2ms 不写，差 5ms 写。
        val applied = 16_600_000L
        assertEquals(3_320_000L, ManualWriteGate.shutterThreshold(applied))
        assertFalse(
            ManualWriteGate.shouldWrite(50_000L, 0L, 800, applied, 800, applied + 2_000_000L)
        )
        assertTrue(
            ManualWriteGate.shouldWrite(50_000L, 0L, 800, applied, 800, applied + 5_000_000L)
        )
    }

    @Test
    fun shutterThresholdHasFloorForFastShutters() {
        // 1/1000s = 1ms：20% 只有 0.2ms，必须被 2ms 兜住，否则高速快门下会一直写
        assertEquals(
            ManualWriteGate.MIN_SHUTTER_THRESHOLD_NS,
            ManualWriteGate.shutterThreshold(1_000_000L)
        )
        // 差 1.5ms 小于下限 → 不写
        assertFalse(
            ManualWriteGate.shouldWrite(50_000L, 0L, 800, 1_000_000L, 800, 2_500_000L)
        )
    }

    @Test
    fun eitherParamFarEnoughIsEnough() {
        // ISO 没超出阈值、快门超出了 → 也要写（快门糊了救不回来）
        assertTrue(
            ManualWriteGate.shouldWrite(
                nowMs = 50_000L,
                lastApplyAtMs = 0L,
                appliedIso = 800,
                appliedShutterNs = 16_600_000L,
                targetIso = 810,
                targetShutterNs = 40_000_000L
            )
        )
    }
}
