package com.shottuner.app.analysis

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * I420（YUV 4:2:0）平面图像。
 *
 * 相机链路里 CameraX 给的是 `YUV_420_888`，离线链路里我们自建的是 I420：
 * 两者布局一致（Y 全分辨率、U/V 半分辨率、隔行存放），
 * 所以 [FrameStatsCore] 一份代码就能同时吃下这两条链路。
 */
internal class YuvImage(
    val width: Int,
    val height: Int,
    /** Y 平面，长度 = width * height */
    val y: ByteArray,
    /** U 平面，长度 = chromaWidth * chromaHeight */
    val u: ByteArray,
    /** V 平面，长度 = chromaWidth * chromaHeight */
    val v: ByteArray,
    /** 色度平面宽度（= (width + 1) / 2） */
    val chromaWidth: Int,
    /** 色度平面高度（= (height + 1) / 2） */
    val chromaHeight: Int
) {
    init {
        require(y.size == width * height) { "Y 平面长度与尺寸不符" }
        require(u.size == chromaWidth * chromaHeight) { "U 平面长度与尺寸不符" }
        require(v.size == chromaWidth * chromaHeight) { "V 平面长度与尺寸不符" }
    }

    /** Y 平面每行的字节跨度 */
    val yRowStride: Int get() = width

    /** U 平面每行的字节跨度 */
    val uRowStride: Int get() = chromaWidth

    /** V 平面每行的字节跨度 */
    val vRowStride: Int get() = chromaWidth

    /** Y 平面缓冲 */
    fun yBuffer(): ByteBuffer = ByteBuffer.wrap(y)

    /** U 平面缓冲 */
    fun uBuffer(): ByteBuffer = ByteBuffer.wrap(u)

    /** V 平面缓冲 */
    fun vBuffer(): ByteBuffer = ByteBuffer.wrap(v)
}

/**
 * RGB → I420 转换。
 *
 * ## 矩阵为什么是这一组
 * [FrameStatsCore] 内部把 YUV 反算回 RGB 时用的是
 * `r = y + 1.402v`、`g = y - 0.344u - 0.714v`、`b = y + 1.772u`（u/v 已减去 128），
 * 也就是 **BT.601 全范围** 的逆变换。
 * 这里用同一组系数的正向变换，两边刚好互逆，
 * 离线链路的颜色统计与实时链路是同一套口径。
 *
 * ## 量化规则（跨端必须逐位一致）
 * 1. 每个像素先各自换算成 Y/U/V 并四舍五入取整；
 * 2. U/V 再按 2×2 块取平均（四舍五入）得到 4:2:0；
 * 3. 全部结果钳到 0..255。
 *
 * 规则写死在这里，是为了让 iOS 侧能照抄同一段数值规则，
 * 而不是各自「差不多就行」——否则跨端比出来的差异没有意义。
 */
internal object RgbToYuv {

    /** BT.601 全范围：Y = 0.299R + 0.587G + 0.114B */
    private const val Y_R = 0.299
    private const val Y_G = 0.587
    private const val Y_B = 0.114

    /** BT.601 全范围：U = -0.168736R - 0.331264G + 0.5B + 128 */
    private const val U_R = -0.168736
    private const val U_G = -0.331264
    private const val U_B = 0.5

    /** BT.601 全范围：V = 0.5R - 0.418688G - 0.081312B + 128 */
    private const val V_R = 0.5
    private const val V_G = -0.418688
    private const val V_B = -0.081312

    /**
     * 把解码后的 RGB 图像转成 I420。
     *
     * @param image 解码结果（ARGB_8888）
     */
    fun toI420(image: DecodedImage): YuvImage {
        val w = image.width
        val h = image.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2

        val yPlane = ByteArray(w * h)
        val uFull = IntArray(w * h)
        val vFull = IntArray(w * h)

        for (i in 0 until w * h) {
            val argb = image.pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val yv = clamp255(floorHalfUp(Y_R * r + Y_G * g + Y_B * b))
            val uv = clamp255(floorHalfUp(U_R * r + U_G * g + U_B * b + 128.0))
            val vv = clamp255(floorHalfUp(V_R * r + V_G * g + V_B * b + 128.0))
            yPlane[i] = yv.toByte()
            uFull[i] = uv
            vFull[i] = vv
        }

        val uPlane = ByteArray(cw * ch)
        val vPlane = ByteArray(cw * ch)
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var uSum = 0
                var vSum = 0
                var n = 0
                for (dy in 0 until 2) {
                    val py = cy * 2 + dy
                    if (py >= h) continue
                    for (dx in 0 until 2) {
                        val px = cx * 2 + dx
                        if (px >= w) continue
                        val idx = py * w + px
                        uSum += uFull[idx]
                        vSum += vFull[idx]
                        n++
                    }
                }
                val ci = cy * cw + cx
                uPlane[ci] = clamp255(floorHalfUp(uSum.toDouble() / n)).toByte()
                vPlane[ci] = clamp255(floorHalfUp(vSum.toDouble() / n)).toByte()
            }
        }

        return YuvImage(w, h, yPlane, uPlane, vPlane, cw, ch)
    }

    /** 向下取整（等价于四舍五入，因为被取整的值恒为非负） */
    private fun floorHalfUp(v: Double): Int = Math.floor(v + 0.5).toInt()

    /** 钳到 0..255 */
    private fun clamp255(v: Int): Int = max(0, minOf(255, v))
}
