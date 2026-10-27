package com.shottuner.app.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 中心区拉普拉斯方差 → 归一化清晰度。
 *
 * ## 为什么不能拿 edgeDensity 顶替
 * `FrameAnalyzer.edgeDensity` 是在 160×120 网格（约 25× 降采样）上算相邻采样点的梯度均值。
 * 25× 降采样已经把"手抖造成的高频模糊"彻底抹平了：手持抖动 2–3px，
 * 降采样后不足 0.1 个采样间距，梯度几乎不变。用它算清晰度，结果会长期满分，
 * 比不评分更糟——用户会认为这个分数是假的。
 *
 * ## 做法
 * 在 Y 平面中心 1/3 区域按 stride 2 取一块灰度，跑 3×3 拉普拉斯：
 * ```
 * [[ 0,  1, 0],
 *  [ 1, -4, 1],
 *  [ 0,  1, 0]]
 * ```
 * 边界像素跳过（不补零，补零会凭空造出边缘）。
 *
 * ## 为什么要做对比度归一化
 * 暗画面的亮度差天然小，方差也小，直接映射会把"暗但锐"误判成"糊"。
 * 因此用 `sqrt(variance) / (meanLuma * 255 + 8)` 归一化：
 * 分子是梯度的标准差（单位同灰度），分母跟随画面整体亮度。
 *
 * 标定断点（需真机 6 张样张：3 张清晰 / 3 张抖动后微调）：
 * - vNorm ≤ [LOW_BREAK] → 0（糊）
 * - vNorm ≥ [HIGH_BREAK] → 1（实）
 * - 中间线性
 *
 * 本文件只 import kotlin.math，可直接跑 JVM 单测。
 */
object SharpnessMeter {

    /** 低于该值视为完全糊。 */
    const val LOW_BREAK = 0.020f

    /** 高于该值视为足够锐利。 */
    const val HIGH_BREAK = 0.075f

    /** 归一化分母的偏置，避免全黑画面除零并把噪声放大。 */
    private const val LUMA_FLOOR = 8f

    /**
     * 中心区拉普拉斯方差。
     *
     * @param luma 灰度块（0..255），行优先，长度为 cols*rows
     * @param cols 块宽（列数）
     * @param rows 块高（行数）
     * @return 拉普拉斯响应的总体方差；块太小或无有效内点时返回 0
     */
    fun laplacianVariance(luma: FloatArray, cols: Int, rows: Int): Float {
        if (cols < 3 || rows < 3 || luma.size < cols * rows) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until rows - 1) {
            val rowUp = (y - 1) * cols
            val row = y * cols
            val rowDown = (y + 1) * cols
            for (x in 1 until cols - 1) {
                val c = luma[row + x]
                val response = luma[rowUp + x] + luma[rowDown + x] +
                    luma[row + x - 1] + luma[row + x + 1] - 4f * c
                sum += response.toDouble()
                sumSq += (response.toDouble() * response.toDouble())
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return if (variance <= 0.0) 0f else variance.toFloat()
    }

    /**
     * 方差 → 0..1 的分段映射。
     *
     * @param variance 拉普拉斯方差（灰度单位）
     * @param meanLuma 全画面平均亮度 0..1，用于对比度归一化
     */
    fun normalize(variance: Float, meanLuma: Float): Float {
        if (variance <= 0f || !variance.isFinite()) return 0f
        val luma = meanLuma.coerceIn(0f, 1f)
        val denom = luma * 255f + LUMA_FLOOR
        val vNorm = sqrt(variance) / denom
        return when {
            vNorm <= LOW_BREAK -> 0f
            vNorm >= HIGH_BREAK -> 1f
            else -> ((vNorm - LOW_BREAK) / (HIGH_BREAK - LOW_BREAK)).coerceIn(0f, 1f)
        }
    }

    /**
     * 一步到位：灰度块 → 归一化清晰度 0..1。
     *
     * @param luma 灰度块（0..255），行优先
     * @param meanLuma 全画面平均亮度 0..1
     */
    fun evaluate(luma: FloatArray, cols: Int, rows: Int, meanLuma: Float): Float =
        normalize(laplacianVariance(luma, cols, rows), meanLuma)

    /** 仅供调试：算归一化前的中间量。 */
    fun normalizedVariance(variance: Float, meanLuma: Float): Float {
        if (variance <= 0f || !variance.isFinite()) return 0f
        val denom = meanLuma.coerceIn(0f, 1f) * 255f + LUMA_FLOOR
        return sqrt(variance) / denom
    }

    /** 绝对值工具，供测试判断两个块"明显不同"。 */
    fun absDiff(a: Float, b: Float): Float = abs(a - b)
}
