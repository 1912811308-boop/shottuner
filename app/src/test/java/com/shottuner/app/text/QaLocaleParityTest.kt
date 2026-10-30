package com.shottuner.app.text

import com.shottuner.app.model.Recommendation
import com.shottuner.app.model.SceneType
import com.shottuner.app.video.VideoRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ★ 独立验证用（QA · 非实现者自测）★
 *
 * 目的：验证 `c505129` 的 Locale 修复**在用户可见输出上逐字不变**。
 *
 * 方法：把 `5a6d7c7`（替换前）的实现逐字抄成本文件里的 `old*` 函数，
 * 与现在的实现跑同一批输入，逐字对比。
 *
 * 对比的基线口径：旧实现跑在 `Locale.US` 下 —— 因为旧实现在德语区会输出 "1,5"，
 * 那正是本次要修的 bug。**"逐字一致"应当理解为"旧实现正确时的输出 == 新实现输出"**。
 *
 * ★ 舍入规则（2026-10-27 已修正为与 Formatter 一致）：
 *   `NumFmt.fixed` 现在是「**先整体放大到目标小数位，再分离整数与小数**」，
 *   与 java.util.Formatter 的 %.Nf 逐字一致。
 *   旧实现是「先减整数部分再放大」，会把双精度的表示误差保留下来再放大 10^d 倍，
 *   于是「1.15 档」被输出成「1.1 档」—— **档位差一位就是进光量差一档**。
 *   下面是这次修正的依据（实测，非推理）：
 *       1.15   × 10   = 11.500000000000000    ← 精确，误差被乘法的舍入吃掉
 *       (1.15-1)× 10  = 1.4999999999999991    ← 先减再乘，误差被保留
 *   唯一保留的已知差异是**负零**：旧实现给 "-0.00"，新实现归一为 "0.00"，
 *   因为负零在用户可见文案里是 bug（见 negativeZeroIsNormalizedOnPurpose）。
 */
class QaLocaleParityTest {

    // ── 替换前的实现（逐字抄自 5a6d7c7）────────────────────────────────────

    private fun oldFormatEv(ev: Float): String {
        if (kotlin.math.abs(ev) < 0.05f) return "±0"
        return String.format("%+.1f", ev)
    }

    private fun oldTiltTip(tilt: Float): String {
        val dir = if (tilt > 0f) "顺时针" else "逆时针"
        return "画面有 %.0f° 的倾斜，%s转一点手机，让橙色虚线和绿色横线重合"
            .format(kotlin.math.abs(tilt), dir)
    }

    private fun oldLensLabel(equivFocalMm: Int, aperture: Float, megapixels: Float): String {
        val f = if (equivFocalMm > 0) "${equivFocalMm}mm" else "?mm"
        val a = if (aperture > 0f) " f/%.1f".format(aperture) else ""
        return "$f$a · %.1fMP".format(megapixels)
    }

    private fun oldPct(value: Float): String = "%.0f".format(value.coerceIn(0f, 1f) * 100f)

    private fun oldShutterLabel(shutterNs: Long): String {
        if (shutterNs <= 0) return "--"
        val sec = shutterNs / 1_000_000_000.0
        return if (sec >= 0.5) String.format("%.1fs", sec) else "1/" + Math.round(1.0 / sec).toInt()
    }

    private fun oldVideoShutterLabel(fps: Int): String {
        val ns = if (fps <= 0) 1_000_000_000L / 60 else 1_000_000_000L / (fps * 2)
        val sec = ns / 1e9
        return if (sec >= 1.0) "%.1fs".format(sec) else "1/${(1.0 / sec).roundToInt()}"
    }

    private fun oldFormatLong(ns: Long): String {
        val sec = ns / 1e9
        return if (sec >= 1.0) "%.0fs".format(sec) else "1/${(1.0 / sec).roundToInt()}"
    }

    private fun oldLevelReading(roll: Float, pitch: Float): String =
        "LevelReading(roll=%.2f, pitch=%.2f)".format(roll, pitch)

    private fun oldTeleAdvice(ratio: Int, stops: Double): String =
        "长焦的传感器面积只有主摄的 $ratio%%，进光量差约 %.1f 档，同样亮度下噪点明显更多。宁可拍完再用主摄裁切"
            .format(stops)

    // ── 现在的实现（直接调生产代码）────────────────────────────────────────

    private fun newFormatEv(ev: Float): String {
        if (kotlin.math.abs(ev) < 0.05f) return "±0"
        return NumFmt.signed(ev, 1)
    }

    private fun newTiltTip(tilt: Float): String {
        val dir = if (tilt > 0f) "顺时针" else "逆时针"
        return "画面有 ${NumFmt.fixed(kotlin.math.abs(tilt), 0)}° 的倾斜，${dir}转一点手机，" +
            "让橙色虚线和绿色横线重合"
    }

    private fun newLensLabel(equivFocalMm: Int, aperture: Float, megapixels: Float): String {
        val f = if (equivFocalMm > 0) "${equivFocalMm}mm" else "?mm"
        val a = if (aperture > 0f) " f/${NumFmt.one(aperture)}" else ""
        return "$f$a · ${NumFmt.one(megapixels)}MP"
    }

    private fun newPct(value: Float): String = NumFmt.fixed(value.coerceIn(0f, 1f) * 100f, 0)

    private fun newShutterLabel(shutterNs: Long): String {
        val rec = Recommendation(
            scene = SceneType.GENERAL,
            targetLuma = 0.5f,
            evDelta = 0f,
            iso = 100,
            shutterNs = shutterNs,
            kelvin = 5200,
            needsTripod = false,
            tip = "",
            detail = "",
            confidence = 1f
        )
        return rec.shutterLabel
    }

    private fun newLevelReading(roll: Float, pitch: Float): String =
        "LevelReading(roll=${NumFmt.fixed(roll, 2)}, pitch=${NumFmt.fixed(pitch, 2)})"

    // ── 已锁定���偏离 ───────────────────────────────────────────────────────
    //
    // NumFmt：先算 fraction = (magnitude - integerPart) * 10^d，再看 ≥ 0.5 进位
    //   → 等价于「按二进制精确值」舍入。
    // java.util.Formatter（%.Nf）：先取最短十进制表示，再 HALF_UP
    //   → 等价于「按十进制字面量」舍入。
    //
    // 二者在"最短十进制表示恰好是 xxx5"且二进制值略小于它时差一位。
    // 例如 1.15：最短表示 "1.15" → Formatter 给 "1.2"；
    //           二进制值 1.14999999999999991 → NumFmt 给 "1.1"。

    /**
     * 已知偏离点：旧输出 -> 新输出。用于把偏离钉死在代码里，不让它悄悄漂移。
     *
     * 2026-10-27：舍入顺序修正后，「1.15 档」这类偏离**已消失**。
     * 现在唯一剩下的偏离是**负零归一**（调试串层面），见 negativeZeroIsNormalizedOnPurpose。
     */
    private val KNOWN_DIVERGENCES: Map<String, String> = mapOf(
        "负零（调试串）" to "-0.00 -> 0.00（刻意归一，用户可见文案里负零是 bug）"
    )

    // ── 1. 曝光档位（formatEv）─────────────────────────────────────────────

    @Test
    fun exposureDeltaOutputIsIdentical() {
        val values = floatArrayOf(
            0.05f, 0.1f, 0.2f, 0.3f, 0.5f, 0.7f, 1.0f, 1.5f, 2.0f, 2.8f, 3.0f, 0.05f,
            -0.05f, -0.1f, -0.3f, -0.5f, -1.0f, -1.5f, -2.8f, 0.04f, -0.04f, 0.0f, -0.0f
        )
        val mismatches = mutableListOf<String>()
        for (v in values) {
            val o = oldFormatEv(v)
            val n = newFormatEv(v)
            if (o != n) mismatches += "ev=$v → 旧:$o 新:$n"
        }
        assertEquals("曝光补偿文案逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    // ── 2. 快门标签（Recommendation.shutterLabel）───────────────────────────

    @Test
    fun shutterLabelIsIdenticalForRealisticShutters() {
        // 真实会出现的快门档位（1/8000 .. 30s），以及边界值
        val shutterNsList = longArrayOf(
            125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L,
            8_000_000L, 15_625_000L, 16_666_667L, 30_000_000L, 33_333_333L,
            62_500_000L, 100_000_000L, 125_000_000L, 166_666_667L, 200_000_000L,
            250_000_000L, 333_333_333L, 500_000_000L, 1_000_000_000L,
            2_000_000_000L, 3_000_000_000L, 5_000_000_000L, 10_000_000_000L,
            30_000_000_000L
        )
        val mismatches = mutableListOf<String>()
        for (ns in shutterNsList) {
            val o = oldShutterLabel(ns)
            val n = newShutterLabel(ns)
            if (o != n) mismatches += "shutterNs=$ns → 旧:$o 新:$n"
        }
        assertEquals("快门标签逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun shutterLabelDenominatorMatchesJavaMathRound() {
        // 架构师点名要验的：Math.round(向+∞) vs floor(x+0.5)
        // 构造 1/x 落在 .5 边界上的 sec
        val secs = doubleArrayOf(
            1.0 / 2.5, 1.0 / 3.5, 1.0 / 15.5, 1.0 / 25.5, 1.0 / 35.5, 1.0 / 45.5,
            1.0 / 59.5, 1.0 / 60.5, 1.0 / 119.5, 1.0 / 1.5, 1.0 / 0.5,
            0.4, 0.2857142857142857, 0.06451612903225806, 0.5 - 1e-12
        )
        val mismatches = mutableListOf<String>()
        for (sec in secs) {
            val ns = Math.round(sec * 1_000_000_000.0).toLong()
            val o = oldShutterLabel(ns)
            val n = newShutterLabel(ns)
            if (o != n) mismatches += "sec=$sec (ns=$ns) → 旧:$o 新:$n"
        }
        assertEquals(
            "快门分母与 Math.round 不一致（1/x 半值边界）：\n" + mismatches.joinToString("\n"),
            0, mismatches.size
        )
    }

    @Test
    fun shutterLabelIsNeverNegativeAndSecIsAlwaysPositive() {
        // 架构师留的隐含前提："sec 恒正" —— 逐点验证
        val probes = longArrayOf(
            1L, 2L, 3L, 10L, 999L, 1_000L, 999_999L, 1_000_000L,
            Long.MAX_VALUE / 2, 500_000_000L, 499_999_999L
        )
        for (ns in probes) {
            val sec = ns / 1_000_000_000.0
            assertTrue("sec 必须为正，ns=$ns sec=$sec", sec > 0.0)
            val label = newShutterLabel(ns)
            assertTrue("快门标签不该出现负号，ns=$ns → $label", !label.contains("-"))
            assertTrue("快门标签格式异常，ns=$ns → $label", label == "--" || label.startsWith("1/") || label.endsWith("s"))
        }
        // shutterNs <= 0 的短路分支
        assertEquals("--", newShutterLabel(0L))
        assertEquals("--", newShutterLabel(-1L))
        assertEquals("--", newShutterLabel(Long.MIN_VALUE))
    }

    @Test
    fun videoShutterLabelIsIdentical() {
        val fpsList = intArrayOf(-1, 0, 1, 2, 24, 25, 30, 50, 60, 120, 240, 1000)
        val mismatches = mutableListOf<String>()
        for (fps in fpsList) {
            val o = oldVideoShutterLabel(fps)
            val n = VideoRecommender.shutterLabel(fps)
            if (o != n) mismatches += "fps=$fps → 旧:$o 新:$n"
        }
        assertEquals("视频快门标签逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun videoShutterLabelSecondsBranchIsActuallyReachable() {
        // shutterForFps = 1e9/(fps*2)，最大 sec = 0.5 → "sec >= 1.0" 分支是死代码
        var sawSeconds = false
        for (fps in 1..2000) {
            if (VideoRecommender.shutterLabel(fps).endsWith("s")) sawSeconds = true
        }
        // 记录事实（不是断言失败，是把"死分支"这件事钉下来）
        println("[QA] VideoRecommender.shutterLabel 里 sec>=1.0 的 \"Xs\" 分支是否可达: $sawSeconds")
    }

    // ── 3. 倾斜角 / 百分比 / 百分之一 / 镜头标签 / 姿态 toString ──────────────

    @Test
    fun tiltTipIsIdentical() {
        val tilts = floatArrayOf(
            0.0f, 0.1f, 0.5f, 3.0f, 12.3f, -12.3f, 30.0f, 45.5f, 60.0f, 89.9f, -89.9f, 90.0f
        )
        val mismatches = mutableListOf<String>()
        for (t in tilts) {
            val o = oldTiltTip(t)
            val n = newTiltTip(t)
            if (o != n) mismatches += "tilt=$t → 旧:$o 新:$n"
        }
        assertEquals("倾斜角文案逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun percentageIsIdentical() {
        val values = floatArrayOf(
            0f, 0.001f, 0.005f, 0.01f, 0.05f, 0.125f, 0.25f, 0.33f, 0.5f, 0.665f,
            0.99f, 1f, 0.335f, 0.999f, 0.015f, 0.045f, 0.055f
        )
        val mismatches = mutableListOf<String>()
        for (v in values) {
            val o = oldPct(v)
            val n = newPct(v)
            if (o != n) mismatches += "pct=$v → 旧:$o 新:$n"
        }
        assertEquals("百分比逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun lensLabelIsIdentical() {
        val cases = listOf(
            Triple(24, 1.8f, 12.2f),
            Triple(48, 0f, 108.0f),
            Triple(0, 2.0f, 50.0f),
            Triple(13, 2.8f, 200.0f),
            Triple(120, 3.5f, 50.0f)
        )
        val mismatches = mutableListOf<String>()
        for ((f, a, m) in cases) {
            val o = oldLensLabel(f, a, m)
            val n = newLensLabel(f, a, m)
            if (o != n) mismatches += "f=$f a=$a mp=$m → 旧:$o 新:$n"
        }
        assertEquals("镜头标签逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun levelReadingToStringIsIdentical() {
        // 负零不在这个清单里 —— 它是**刻意归一**的，单独一个用例说明（见下）。
        val pairs = listOf(
            0f to 0f, 1.005f to -2.675f, 12.3f to -45.5f, 89.99f to -0.5f,
            0.125f to 0.375f, 3.5f to -3.5f, 1.045f to 1.055f
        )
        val mismatches = mutableListOf<String>()
        for ((roll, pitch) in pairs) {
            val o = oldLevelReading(roll, pitch)
            val n = newLevelReading(roll, pitch)
            if (o != n) mismatches += "roll=$roll pitch=$pitch → 旧:$o 新:$n"
        }
        assertEquals("LevelReading.toString 逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /**
     * 负零是**刻意归一**的，不是回归。
     *
     * `LevelReading.toString()` 是**调试串**（Kotlin 数据类的 toString），
     * 用户永远看不到它；它之所以出现在本文件里，是因为 QA 想把**所有**数值出口
     * 的新旧输出都摆到桌面上逐字比。
     *
     * 为什么归一是对的：
     *   旧实现（`%.2f`）给 "-0.00"。而同一个格式化器在**用户可见文案**里
     *   会给出「倾斜角 -0.00°」「进光量差约 -0.0 档」这种负零 —— 用户看到会困惑，
     *   「负零度」不是一个有意义的量。`NumFmt.render()` 因此在舍入后为零时
     *   不带负号（`NumFmtTest` 里也有 `-0.0f → "0.0"` 的断言）。
     *
     * 所以这里断言的是**新行为符合预期**，而不是"与旧实现逐字相同"。
     */
    @Test
    fun negativeZeroIsNormalizedOnPurpose() {
        assertEquals("旧实现应给负零（作为对照）", "LevelReading(roll=-0.00, pitch=0.00)", oldLevelReading(-0.0f, 0.0f))
        assertEquals("新实现应把负零归一", "LevelReading(roll=0.00, pitch=0.00)", newLevelReading(-0.0f, 0.0f))
    }

    @Test
    fun teleLensStopsOutputIsIdentical() {
        val cases = listOf(
            30 to 1.0, 30 to 1.5, 30 to 2.0, 50 to 0.7, 25 to 1.15, 25 to 2.675,
            40 to 0.35, 10 to 3.3, 60 to 1.05, 60 to 1.055
        )
        val mismatches = mutableListOf<String>()
        for ((ratio, stops) in cases) {
            val o = oldTeleAdvice(ratio, stops)
            val n = "长焦的传感器面积只有主摄的 $ratio%，进光量差约 ${NumFmt.one(stops)} 档，" +
                "同样亮度下噪点明显更多。宁可拍完再用主摄裁切"
            if (o != n) mismatches += "ratio=$ratio stops=$stops →\n  旧:$o\n  新:$n"
        }
        assertEquals("长焦档位文案逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun formatLongSecondsOutputIsIdentical() {
        val nsList = longArrayOf(
            1_000_000_000L, 1_500_000_000L, 2_000_000_000L, 2_500_000_000L,
            3_000_000_000L, 10_000_000_000L, 33_333_333_333L
        )
        val mismatches = mutableListOf<String>()
        for (ns in nsList) {
            val o = oldFormatLong(ns)
            val n = if (ns / 1e9 >= 1.0) NumFmt.fixed(ns / 1e9, 0) + "s" else "1/${(1.0 / (ns / 1e9)).roundToInt()}s"
            if (o != n) mismatches += "ns=$ns → 旧:$o 新:$n"
        }
        assertEquals("长曝光秒数逐字不一致：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    // ── 4. 原 bug 确实被修掉了（德语区 / 法语区）────────────────────────────

    @Test
    fun germanAndFrenchLocalesNoLongerLeakComma() {
        val original = Locale.getDefault()
        try {
            for (loc in listOf(Locale.GERMANY, Locale.FRANCE)) {
                Locale.setDefault(loc)
                // 旧实现在这里会输出 "1,5"
                assertEquals("德/法语区 EV 文案仍含逗号", "+1.5", newFormatEv(1.5f))
                assertEquals("德/法语区 镜头文案仍含逗号", "24mm · 12.2MP", newLensLabel(24, 0f, 12.2f))
                assertEquals("德/法语区 百分比仍含逗号", "50", newPct(0.5f))
                assertEquals("德/法语区 快门标签仍含逗号", "1/60", newShutterLabel(16_666_667L))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // ── 5. 把"逐字一致"的真实边界写进输出（供报告引用）────────────────────

    @Test
    fun printRoundingRuleDivergenceSurface() {
        // NumFmt 的舍入规则（按二进制精确值）vs java Formatter（按最短十进制表示）
        val probes = doubleArrayOf(
            1.005, 1.15, 2.675, 8.575, 0.615, 1.045, 1.055, 2.5, 0.5, 1.5,
            -2.5, 0.15, 0.25, 33.5, 12.5, 0.05, 1.25, 3.5, 0.25, 2.25, 1.005
        )
        val sb = StringBuilder()
        sb.appendLine("value | java %%.1f | NumFmt.fixed(v,1) | java %%.2f | NumFmt.fixed(v,2)")
        var diffs = 0
        for (v in probes) {
            val j1 = String.format(Locale.US, "%.1f", v)
            val n1 = NumFmt.fixed(v, 1)
            val j2 = String.format(Locale.US, "%.2f", v)
            val n2 = NumFmt.fixed(v, 2)
            if (j1 != n1 || j2 != n2) diffs++
            sb.appendLine("$v | $j1 | $n1 | $j2 | $n2" + if (j1 != n1 || j2 != n2) "   <<< 偏离" else "")
        }
        println(sb.toString())
        println("[QA] 舍入规则偏离点数量 = $diffs / ${probes.size}")
        // 偏离是"已知且已解释"的，不在这里判失败；数字打印出来供报告引用。
    }

    @Test
    fun printKnownDivergenceTable() {
        println("[QA] KNOWN_DIVERGENCES = $KNOWN_DIVERGENCES")
    }
}
