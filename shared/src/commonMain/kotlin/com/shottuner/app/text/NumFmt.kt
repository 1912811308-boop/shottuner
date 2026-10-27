package com.shottuner.app.text

import kotlin.math.abs
import kotlin.math.floor

/**
 * 定点数值格式化器：**永远**用 `.` 作小数点，不跟随系统 Locale。
 *
 * ## 为什么不能直接用 `String.format`
 *
 * `String.format("%.1f", 1.5f)` 走的是 `Locale.getDefault()`：在德语/法语系统上
 * 会输出 `1,5`。技术类 App 的数值惯例是永远用 `.` —— 用户系统是德语时，
 * 看到「1.5 档」是正常的，看到「1,5 档」才是 bug。
 *
 * 但改成 `String.format(Locale.US, ...)` 也不对：
 *
 * - `java.lang.String.format` / `java.text.DecimalFormat` 在 Kotlin/Native 上不可用，
 *   未来的 KMP `commonMain` 不能依赖它们；
 * - 更要紧的是，**留一个能传 Locale 的入口等于把 bug 的后门重新打开** —— 将来有人
 *   写 `NumFmt.format(Locale.getDefault(), v)`，问题就原样回来了；而且因为
 *   「看起来已经支持 locale 了」，比现在更难被发现。
 *
 * 所以这里刻意只暴露 locale 无关的入口（[one] / [fixed] / [signed] / [int]）：
 * 小数点写死为 `.`，调用方根本无从选择别的 Locale。
 *
 * ## 舍入
 *
 * 显式四舍五入，规则写死：**半值远离零**（`2.5 → 3`，`-2.5 → -3`）。
 * 先把小数部分按 `10^decimals` 放大，再看余数是否 ≥ 0.5 决定进位；
 * 不依赖 `String.format` / 浮点字符串化那套各平台不一致的实现
 * （`"%.1f".format(1.05f)` 在不同平台上结果就可能不同）。
 *
 * 全部计算走 IEEE-754 双精度，Android 与 iOS 逐位一致。
 *
 * ## 边界
 *
 * - [Float.NaN] / ±∞ → [UNDEFINED]（`"--"`）：主链路不能因为一个脏数字崩掉；
 * - `-0.0` → `"0.0"`；`-0.04` 保留 1 位 → `"0.0"`：舍入后是零就不残留负号；
 * - 绝对值 ≥ 2^63 的极端值退回科学计数法（`1.0E20`），避免整数部分溢出丢精度。
 */
object NumFmt {

    /** 无法表示的数值（非有限）占位文案。 */
    const val UNDEFINED: String = "--"

    /** 支持的最大小数位数。再多没有实际意义，放大后也会超出双精度有效位。 */
    const val MAX_DECIMALS: Int = 9

    /** 2^63：超过它，整数部分就无法用 [Long] 精确承载了。 */
    private const val TWO_POW_63 = 9.223372036854775808E18

    private val POW10_DOUBLE = doubleArrayOf(
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9
    )

    private val POW10_LONG = longArrayOf(
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L
    )

    /** 1 位小数，例如 `1.5`。 */
    fun one(value: Float): String = fixed(value, 1)

    /** 1 位小数，例如 `1.5`。 */
    fun one(value: Double): String = fixed(value, 1)

    /**
     * 定点小数：小数点固定为 `.`，小数位数固定为 [decimals]（尾随 0 不会丢）。
     *
     * @param decimals 小数位数，超出 [MAX_DECIMALS] 会被夹到范围内
     */
    fun fixed(value: Float, decimals: Int): String = fixed(value.toDouble(), decimals)

    /**
     * 定点小数：小数点固定为 `.`，小数位数固定为 [decimals]（尾随 0 不会丢）。
     *
     * @param decimals 小数位数，超出 [MAX_DECIMALS] 会被夹到范围内
     */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return UNDEFINED
        val d = decimals.coerceIn(0, MAX_DECIMALS)
        val magnitude = abs(value)

        // ★★ 顺序不能反：先整体放大到目标小数位 → 一次性舍入 → 再拆整数/小数 ★★
        //
        // 旧写法是 `(magnitude - integerPart) * 10^d` —— **先做一次减法**，
        // 于是双精度的表示误差被保留下来、再放大 10^d 倍：
        //     1.15   × 10   = 11.500000000000000      （精确：误差被乘法的舍入吃掉）
        //     (1.15-1)× 10  = 1.4999999999999991      （误差被保留）
        // 结果「1.15 档」被输出成「1.1 档」—— 与用户直觉不符，
        // 而**档位差一位就是进光量差一档**。
        //
        // 现在的做法（先放大 → 整体舍入 → 再拆）与 java.util.Formatter 的 %.Nf
        // **逐字一致**（实测，非推理）：
        //     Double 1.15   ×10  = 11.5              → 12 → whole=1 frac=2 → "1.2"
        //     Float  1.005f ×100 = 100.49999952316284 → 100 → whole=1 frac=0 → "1.00"
        //     Float  1.15f  ×10  = 11.49999976158142  → 11  → whole=1 frac=1 → "1.1"
        //     Float  0.125f ×100 = 12.5              → 13  → whole=0 frac=13 → "0.13"
        //
        // 减法不在关键路径上（放大后的值直接整体舍入），所以整个算法只依赖
        // IEEE-754 基础运算，**Android 与 iOS 输出完全一样**。
        val scaled = magnitude * POW10_DOUBLE[d]

        // 放大后超出 Long 能精确承载的范围，退回科学计数法
        // ⚠️ 这里必须传**带符号的原值**，不能传 magnitude：科学计数法分支自己
        // 不处理符号（它只管量级），传绝对值会把负号吃掉 —— 大负数会输出成
        // "1.0E20" 而不是 "-1.0E20"。这正是 NumFmtTest.hugeNegativeValuesKeepTheirSign
        // 钉住的那个 bug。
        if (scaled >= TWO_POW_63) return scientific(value, d)

        // 先整体舍入到「目标小数位」这个整数，再拆成整数部分与小数部分。
        // 进位在这一步自然发生（9.999 → scaled=999.9 → 1000 → whole=10 frac=0），
        // 不需要额外的进位分支。
        val rounded = roundHalfAwayFromZero(scaled)
        val whole = rounded / POW10_LONG[d]
        val fraction = rounded % POW10_LONG[d]
        return render(whole, fraction, d, isNegative(value))
    }

    /**
     * 带显式正负号的定点小数，例如 `+0.5` / `-0.3`。
     *
     * 曝光补偿这类数值必须带号，用户要一眼看出「加还是减」。
     */
    fun signed(value: Float, decimals: Int): String = signed(value.toDouble(), decimals)

    /** 带显式正负号的定点小数，例如 `+0.5` / `-0.3`。 */
    fun signed(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return UNDEFINED
        val d = decimals.coerceIn(0, MAX_DECIMALS)
        val sign = if (isNegative(value)) "-" else "+"
        return sign + fixed(abs(value), d)
    }

    /** 整数文案。整数本身没有小数点问题，这里只是给调用方一个统一入口。 */
    fun int(value: Int): String = value.toString()

    /**
     * 是否为负。
     *
     * 单独判断 `-0.0`：它在 IEEE-754 里等于 `0.0`，用 `<` 判会漏掉负零。
     * 用 `1.0 / value` 的符号来探负零，两个平台行为一致。
     */
    private fun isNegative(value: Double): Boolean =
        value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)

    /**
     * 显式四舍五入：余数 ≥ 0.5 进位（半值向 +∞ 取整）。
     *
     * 不使用 `roundToInt`：不同平台对「恰好一半」的处理并不一致
     * （JVM 的 `Math.round` 与 Kotlin 的 `round` 语义不同），
     * 自己写死这条规则才能保证 Android / iOS 输出完全一样。
     *
     * ★ 入参是**已经放大到目标小数位**的值（[fixed] 里的 `scaled`），
     *   所以这里就是「整体放大后按 0.5 进位」—— 与 java.util.Formatter 的 %.Nf 一致。
     *   减法不在关键路径上，**不需要任何 epsilon 补偿**：
     *   补偿反而会让 1.005f 错误地进位成 "1.01"（Formatter 给的是 "1.00"）。
     */
    private fun roundHalfAwayFromZero(value: Double): Long {
        val floorValue = floor(value)
        return if (value - floorValue >= 0.5) (floorValue + 1.0).toLong() else floorValue.toLong()
    }

    /** 拼装最终文案。 */
    private fun render(whole: Long, fraction: Long, decimals: Int, negative: Boolean): String {
        // 舍入后是零就不带负号：`-0.0` / `-0.04` 保留 1 位都应该是 `0.0`
        val sign = if (negative && (whole != 0L || fraction != 0L)) "-" else ""
        if (decimals == 0) return sign + whole.toString()
        val fractionText = fraction.toString().padStart(decimals, '0')
        return sign + whole.toString() + "." + fractionText
    }

    /**
     * 极端量级的兜底：科学计数法。
     *
     * 指数用十进制逐级比较算出来，不走 `log10` / `pow` —— 各平台 libm 的末位
     * 可能有差异，而这条路径必须两端完全一致。
     *
     * @param value **带符号的原值**。量级用绝对值算，但符号必须保留。
     */
    private fun scientific(value: Double, decimals: Int): String {
        val magnitude = abs(value)
        var exponent = 0
        var power = 1.0
        while (exponent < 308 && power * 10.0 <= magnitude) {
            power *= 10.0
            exponent++
        }
        var mantissa = magnitude / power
        // 归一化到 [1, 10)。累积乘法的末位误差最多让尾数偏一点点，一次修正即可。
        if (mantissa >= 10.0) {
            mantissa /= 10.0
            exponent++
        }
        if (mantissa < 1.0) {
            mantissa *= 10.0
            exponent--
        }
        val wholeValue = floor(mantissa)
        var whole = wholeValue.toLong()
        var fraction = roundHalfAwayFromZero((mantissa - wholeValue) * POW10_DOUBLE[decimals])
        if (fraction >= POW10_LONG[decimals]) {
            fraction -= POW10_LONG[decimals]
            whole += 1
            // 9.9 进位成 10.0：归一化回 1.0 并抬一位指数
            if (whole >= 10L) {
                whole = 1L
                exponent++
            }
        }
        return render(whole, fraction, decimals, isNegative(value)) + "E" + exponent
    }
}
