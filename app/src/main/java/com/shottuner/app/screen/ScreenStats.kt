package com.shottuner.app.screen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import com.shottuner.app.model.FrameStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从屏幕截图的 RGBA 缓冲里算测光统计。
 *
 * 为什么不复用 FrameAnalyzer：那个类吃的是 YUV（相机给的原生格式），
 * 这里吃的是屏幕合成后的 RGBA。为了共享一个 FrameStats 出口，
 * 两边各自解析自己的像素格式，下游（场景判定、参数推荐、构图）完全共用。
 *
 * 三个刻意的处理：
 * 1. **裁掉边缘**。截图里有相机 App 的模式栏、快门按钮、状态栏，
 *    它们的黑色和白色文字会污染直方图。只取中部 5%–95% / 12%–88%。
 * 2. **步长抽样**，不 resize。1080p 全采样在低端机上会拖慢到 10fps 以下。
 * 3. 屏幕截图是正立的，不需要旋转处理——这正是它比相机取景器省事的地方。
 */
object ScreenStats {

    private const val COLS = 160
    private const val ROWS = 120
    private const val CROP_X0 = 0.05f
    private const val CROP_X1 = 0.95f
    private const val CROP_Y0 = 0.12f
    private const val CROP_Y1 = 0.88f

    fun compute(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        onBitmap: ((Bitmap) -> Unit)? = null
    ): FrameStats? {
        if (width < 32 || height < 32) return null
        val bx0 = (width * CROP_X0).toInt()
        val bx1 = (width * CROP_X1).toInt()
        val by0 = (height * CROP_Y0).toInt()
        val by1 = (height * CROP_Y1).toInt()
        val cw = bx1 - bx0
        val ch = by1 - by0
        if (cw < 32 || ch < 32) return null

        val stepX = max(1, cw / COLS)
        val stepY = max(1, ch / ROWS)
        val cols = cw / stepX
        val rows = ch / stepY
        if (cols < 4 || rows < 4) return null

        val hist = IntArray(256)
        val grid = FloatArray(cols * rows)
        val skinGrid = FloatArray(cols * rows)
        // 需要喂 AI 时才分配颜色缓冲，其余帧省掉这次内存写入
        val rgbBuf: IntArray? = if (onBitmap != null) IntArray(cols * rows) else null
        var sumY = 0.0
        var count = 0
        var clipHigh = 0
        var clipLow = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumSat = 0.0
        var skin = 0
        var sumCenter = 0.0
        var countCenter = 0

        val cx0 = bx0 + cw * 28 / 100
        val cx1 = bx0 + cw * 72 / 100
        val cy0 = by0 + ch * 28 / 100
        val cy1 = by0 + ch * 72 / 100

        for (ry in 0 until rows) {
            val sy = by0 + ry * stepY
            val rowBase = sy * rowStride
            for (cx in 0 until cols) {
                val sx = bx0 + cx * stepX
                val idx = rowBase + sx * pixelStride
                if (idx < 0 || idx + 2 >= buffer.capacity()) continue
                val r = buffer.get(idx).toInt() and 0xFF
                val g = buffer.get(idx + 1).toInt() and 0xFF
                val b = buffer.get(idx + 2).toInt() and 0xFF
                val yv = (0.299f * r + 0.587f * g + 0.114f * b)
                val yi = yv.toInt().coerceIn(0, 255)

                val cell = ry * cols + cx
                grid[cell] = yv
                if (rgbBuf != null) {
                    rgbBuf[cell] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                hist[yi]++
                sumY += yv
                count++
                if (yi >= 250) clipHigh++
                if (yi <= 8) clipLow++
                sumR += r; sumG += g; sumB += b
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx > 8) sumSat += (mx - mn).toFloat() / mx
                // RGB 肤色判据：红占优、绿居中、蓝偏低的经典区间
                val isSkin = r > 95 && g > 40 && b > 20 &&
                    (max(r, max(g, b)) - min(r, min(g, b)) > 15) &&
                    abs(r - g) > 15 && r > g && r > b
                if (isSkin) {
                    skin++
                    skinGrid[cell] = 1f
                }
                if (sx in cx0..cx1 && sy in cy0..cy1) {
                    sumCenter += yv
                    countCenter++
                }
            }
        }
        if (count == 0) return null

        var gradSum = 0.0
        var gradCount = 0
        for (ry in 0 until rows - 1) {
            for (cx in 0 until cols - 1) {
                val c = grid[ry * cols + cx]
                gradSum += abs(c - grid[ry * cols + cx + 1]) + abs(c - grid[(ry + 1) * cols + cx])
                gradCount += 2
            }
        }

        val meanY = (sumY / count).toFloat()
        val normalizedHist = FloatArray(256)
        for (i in 0 until 256) normalizedHist[i] = hist[i].toFloat() / count

        // 屏幕截图本来就是正立的，不需要旋转——这是它比相机取景器省事的地方
        if (rgbBuf != null && onBitmap != null) {
            try {
                onBitmap.invoke(Bitmap.createBitmap(rgbBuf, cols, rows, Bitmap.Config.ARGB_8888))
            } catch (_: Throwable) {
            }
        }

        return FrameStats(
            histogram = normalizedHist,
            meanLuma = meanY / 255f,
            p05 = percentile(hist, count, 0.05f) / 255f,
            p50 = percentile(hist, count, 0.50f) / 255f,
            p95 = percentile(hist, count, 0.95f) / 255f,
            highlightClip = clipHigh.toFloat() / count,
            shadowClip = clipLow.toFloat() / count,
            meanR = (sumR / count / 255.0).toFloat(),
            meanG = (sumG / count / 255.0).toFloat(),
            meanB = (sumB / count / 255.0).toFloat(),
            saturation = (sumSat / count).toFloat().coerceIn(0f, 1f),
            centerLuma = if (countCenter == 0) meanY / 255f else (sumCenter / countCenter / 255.0).toFloat(),
            edgeDensity = if (gradCount == 0) 0f else (gradSum / gradCount / 40.0).toFloat().coerceIn(0f, 1f),
            skinRatio = (skin.toFloat() / count).coerceIn(0f, 1f),
            width = width,
            height = height,
            grid = grid,
            skinGrid = skinGrid,
            gridCols = cols,
            gridRows = rows
        )
    }

    private fun percentile(hist: IntArray, total: Int, p: Float): Float {
        val target = (total * p).toInt().coerceAtLeast(1)
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= target) return i.toFloat()
        }
        return 255f
    }
}
