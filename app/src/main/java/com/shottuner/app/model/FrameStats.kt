package com.shottuner.app.model

/**
 * 一帧画面的统计特征。所有亮度值均为 0f..1f 的归一化值。
 * 这里刻意不使用 data class：包含 FloatArray，默认 equals/hashCode 无意义。
 */
class FrameStats(
    /** 256 桶归一化亮度直方图 */
    val histogram: FloatArray,
    /** 平均亮度 */
    val meanLuma: Float,
    /** 5% 分位亮度（暗部代表值） */
    val p05: Float,
    /** 50% 分位亮度（中位亮度） */
    val p50: Float,
    /** 95% 分位亮度（高光代表值） */
    val p95: Float,
    /** 亮度 >= 250 的像素占比（高光溢出） */
    val highlightClip: Float,
    /** 亮度 <= 8 的像素占比（暗部死黑） */
    val shadowClip: Float,
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    /** 平均饱和度 0..1 */
    val saturation: Float,
    /** 画面中心区域平均亮度 */
    val centerLuma: Float,
    /** 边缘密度，表征纹理复杂度 0..1 */
    val edgeDensity: Float,
    /** 肤色像素占比 0..1 */
    val skinRatio: Float,
    val width: Int,
    val height: Int,
    /**
     * 降采样亮度网格（0..255），已按 sensor 方向旋正为「所见画面」朝向。
     * 构图分析需要空间信息，直方图给不了。
     */
    val grid: FloatArray = FloatArray(0),
    /** 与 grid 同尺寸的肤色占比网格，0..1 */
    val skinGrid: FloatArray = FloatArray(0),
    val gridCols: Int = 0,
    val gridRows: Int = 0,
    /**
     * 中心区拉普拉斯方差（灰度单位²），由 [com.shottuner.app.analysis.SharpnessMeter] 计算。
     * 0 = 本帧还没算过（约每 15 帧更新一次），此时清晰度维度标「未测」而不是 0 分。
     *
     * 注意：这里存的是**方差**，不是归一化后的 0..1 清晰度 —— 归一化必须知道全画面平均亮度，
     * 而不同曝光下的同一个方差含义完全不同，所以归一化放在评分阶段做。
     */
    val focusVar: Float = 0f
) {
    /** 动态范围（P95 - P05），成像层次的核心指标 */
    val dynamicRange: Float get() = (p95 - p05).coerceIn(0f, 1f)

    /** 中心与全图亮度差，用于判断主体是否欠曝 */
    val centerBias: Float get() = centerLuma - meanLuma

    /** 灰世界色偏指数：正值偏暖（红多），负值偏冷（蓝多） */
    val colorCastIndex: Float
        get() {
            val r = if (meanR < 1f) 1f else meanR
            val b = if (meanB < 1f) 1f else meanB
            return (log2(r / b))
        }

    /** 对比度偏低的粗略判断 */
    val isFlat: Boolean get() = dynamicRange < 0.35f

    private fun log2(x: Float): Float = (Math.log(x.toDouble()) / Math.log(2.0)).toFloat()

    companion object {
        val EMPTY: FrameStats = FrameStats(
            histogram = FloatArray(256),
            meanLuma = 0f, p05 = 0f, p50 = 0f, p95 = 0f,
            highlightClip = 0f, shadowClip = 0f,
            meanR = 0f, meanG = 0f, meanB = 0f,
            saturation = 0f, centerLuma = 0f, edgeDensity = 0f, skinRatio = 0f,
            width = 0, height = 0
        )
    }
}
