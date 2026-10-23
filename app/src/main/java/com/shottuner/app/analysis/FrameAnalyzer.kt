package com.shottuner.app.analysis

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.shottuner.app.model.FrameStats
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 逐帧提取测光、色彩与构图所需的降采样网格。
 *
 * 设计要点：
 * 1. 只在 YUV_420_888 上工作，直接读平面，避免任何 Bitmap 转换（单帧 < 8ms）。
 * 2. 采用步长降采样而非 resize，保证在中低端机上也能跑满 30fps。
 * 3. 任何异常都被吞掉：相机分析流水线一旦抛异常会永久停摆。
 * 4. 输出网格按 rotationDegrees 旋正，否则构图分析会把「传感器横躺的画面」当成
 *    用户所见的画面，导致地平线与主体位置全部算错 90 度。
 * 5. 可选地把采样结果拼成一张小位图交给端侧 AI（每 N 帧一次）。
 *    拼图几乎不增加成本——RGB 在色度统计时已经算过了，只是顺手存下来。
 */
class FrameAnalyzer(
    private val callback: (FrameStats) -> Unit,
    private val onBitmap: ((Bitmap) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TARGET_SAMPLES_X = 160
        private const val TARGET_SAMPLES_Y = 120
        private const val HIGHLIGHT_THRESHOLD = 250
        private const val SHADOW_THRESHOLD = 8

        /** AI 不需要逐帧：1.5 秒一次足够，也避免和测光抢 CPU */
        private const val BITMAP_EVERY_N_FRAMES = 45

        /**
         * 中心区拉普拉斯方差（清晰度）的更新间隔。
         * 30fps 下约 0.5 秒一次：清晰度是慢变量，没必要逐帧算。
         */
        private const val FOCUS_EVERY_N_FRAMES = 15

        /** 拉普拉斯采样步长：中心区按 stride 2 取样，兼顾分辨率与开销 */
        private const val FOCUS_STRIDE = 2
    }

    private var frameCount = 0

    /**
     * 最近一次算出的中心区拉普拉斯方差。
     *
     * 为什么缓存而不是"没算的帧填 0"：填 0 会让清晰度在 14/15 的帧里显示「未测」，
     * 分数每 0.5 秒在「未测」和「18」之间跳一次，比不显示还糟。
     * 0 只代表"整条会话还没算过一次"。
     */
    private var lastFocusVar = 0f

    override fun analyze(image: ImageProxy) {
        try {
            frameCount++
            val wantBitmap = onBitmap != null && (frameCount % BITMAP_EVERY_N_FRAMES == 0)
            val stats = compute(image, wantBitmap)
            if (stats != null) callback(stats)
        } catch (_: Throwable) {
            // 静默丢弃坏帧
        } finally {
            image.close()
        }
    }

    private fun compute(image: ImageProxy, emitBitmap: Boolean): FrameStats? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val w = image.width
        val h = image.height
        if (w < 32 || h < 32) return null

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        yBuf.rewind(); uBuf.rewind(); vBuf.rewind()

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val stepX = max(1, w / TARGET_SAMPLES_X)
        val stepY = max(1, h / TARGET_SAMPLES_Y)

        val cols = (w + stepX - 1) / stepX
        val rows = (h + stepY - 1) / stepY
        if (cols < 4 || rows < 4) return null

        val hist = IntArray(256)
        val grid = FloatArray(cols * rows)
        var sumY = 0.0
        var count = 0
        var clipHigh = 0
        var clipLow = 0

        var sumCenter = 0.0
        var countCenter = 0

        val cx0 = (w * 0.28f).toInt()
        val cx1 = (w * 0.72f).toInt()
        val cy0 = (h * 0.28f).toInt()
        val cy1 = (h * 0.72f).toInt()

        for (ry in 0 until rows) {
            val sy = ry * stepY
            if (sy >= h) break
            val rowBase = sy * yRowStride
            for (cx in 0 until cols) {
                val sx = cx * stepX
                if (sx >= w) break
                val idx = rowBase + sx * yPixStride
                if (idx < 0 || idx >= yBuf.capacity()) continue
                val yv = yBuf.get(idx).toInt() and 0xFF
                grid[ry * cols + cx] = yv.toFloat()
                hist[yv]++
                sumY += yv
                count++
                if (yv >= HIGHLIGHT_THRESHOLD) clipHigh++
                if (yv <= SHADOW_THRESHOLD) clipLow++
                if (sx in cx0..cx1 && sy in cy0..cy1) {
                    sumCenter += yv
                    countCenter++
                }
            }
        }
        if (count == 0) return null

        // 色度与肤色统计：步长放大一倍，避免重复读同一对 UV
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumSat = 0.0
        var skin = 0
        var chromaCount = 0

        // 分块肤色占比，供构图分析定位人像主体
        val skinHits = IntArray(cols * rows)
        val skinTotal = IntArray(cols * rows)

        // 只有需要喂 AI 的那一帧才分配颜色缓冲
        val rgbBuf: IntArray? = if (emitBitmap) IntArray(cols * rows) else null

        val chromaStepX = stepX * 2
        val chromaStepY = stepY * 2

        var chasedY = 0
        while (chasedY < h) {
            val uvRow = chasedY / 2
            var chasedX = 0
            while (chasedX < w) {
                val uvCol = chasedX / 2
                val uIdx = uvRow * uRowStride + uvCol * uPixStride
                val vIdx = uvRow * vRowStride + uvCol * vPixStride
                if (uIdx in 0 until uBuf.capacity() && vIdx in 0 until vBuf.capacity()) {
                    val u = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                    val v = (vBuf.get(vIdx).toInt() and 0xFF) - 128
                    val yIdx = chasedY * yRowStride + chasedX * yPixStride
                    if (yIdx in 0 until yBuf.capacity()) {
                        val yv = (yBuf.get(yIdx).toInt() and 0xFF).toFloat()
                        val rf = yv + 1.402f * v
                        val gf = yv - 0.344f * u - 0.714f * v
                        val bf = yv + 1.772f * u
                        val r = rf.coerceIn(0f, 255f)
                        val g = gf.coerceIn(0f, 255f)
                        val b = bf.coerceIn(0f, 255f)
                        sumR += r; sumG += g; sumB += b
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        if (mx > 8f) sumSat += (mx - mn) / mx
                        val isSkin = u in -27..7 && v in 5..45 && yv > 40f
                        if (isSkin) skin++
                        chromaCount++

                        val cell = (chasedY / stepY) * cols + (chasedX / stepX)
                        if (cell in skinHits.indices) {
                            skinTotal[cell]++
                            if (isSkin) skinHits[cell]++
                        }
                        if (rgbBuf != null && cell in rgbBuf.indices) {
                            rgbBuf[cell] = (0xFF shl 24) or
                                (r.toInt().coerceIn(0, 255) shl 16) or
                                (g.toInt().coerceIn(0, 255) shl 8) or
                                b.toInt().coerceIn(0, 255)
                        }
                    }
                }
                chasedX += chromaStepX
            }
            chasedY += chromaStepY
        }

        // 边缘密度：相邻采样点的梯度均值
        var gradSum = 0.0
        var gradCount = 0
        for (ry in 0 until rows - 1) {
            for (cx in 0 until cols - 1) {
                val c = grid[ry * cols + cx]
                val rgt = grid[ry * cols + cx + 1]
                val dwn = grid[(ry + 1) * cols + cx]
                gradSum += abs(c - rgt) + abs(c - dwn)
                gradCount += 2
            }
        }

        val meanY = (sumY / count).toFloat()
        val p05 = percentile(hist, count, 0.05f)
        val p50 = percentile(hist, count, 0.50f)
        val p95 = percentile(hist, count, 0.95f)

        // 清晰度：每 FOCUS_EVERY_N_FRAMES 帧在 Y 平面中心 1/3 区域算一次拉普拉斯方差。
        // 放在 160×120 网格上是量不出来的（25× 降采样把抖动模糊抹平了），必须回原始 Y 平面取。
        if (frameCount % FOCUS_EVERY_N_FRAMES == 0) {
            val variance = focusLaplacianVariance(
                yBuf = yBuf,
                width = w,
                height = h,
                rowStride = yRowStride,
                pixelStride = yPixStride
            )
            if (variance > 0f) lastFocusVar = variance
        }

        val cc = if (chromaCount == 0) 1 else chromaCount
        val edge = if (gradCount == 0) 0f else (gradSum / gradCount / 40.0).toFloat().coerceIn(0f, 1f)

        val normalizedHist = FloatArray(256)
        for (i in 0 until 256) normalizedHist[i] = hist[i].toFloat() / count

        val skinGrid = FloatArray(cols * rows)
        for (i in skinGrid.indices) {
            val t = skinTotal[i]
            if (t > 0) skinGrid[i] = skinHits[i].toFloat() / t
        }

        val upright = toUpright(grid, skinGrid, cols, rows, image.imageInfo.rotationDegrees)

        if (rgbBuf != null && onBitmap != null) {
            buildBitmap(rgbBuf, cols, rows, image.imageInfo.rotationDegrees)
                ?.let { onBitmap.invoke(it) }
        }

        return FrameStats(
            histogram = normalizedHist,
            meanLuma = meanY / 255f,
            p05 = p05 / 255f,
            p50 = p50 / 255f,
            p95 = p95 / 255f,
            highlightClip = clipHigh.toFloat() / count,
            shadowClip = clipLow.toFloat() / count,
            meanR = (sumR / cc / 255.0).toFloat(),
            meanG = (sumG / cc / 255.0).toFloat(),
            meanB = (sumB / cc / 255.0).toFloat(),
            saturation = (sumSat / cc).toFloat().coerceIn(0f, 1f),
            centerLuma = if (countCenter == 0) meanY / 255f else (sumCenter / countCenter / 255.0).toFloat(),
            edgeDensity = edge,
            skinRatio = (skin.toFloat() / cc).coerceIn(0f, 1f),
            width = w,
            height = h,
            grid = upright.luma,
            skinGrid = upright.skin,
            gridCols = upright.cols,
            gridRows = upright.rows,
            focusVar = lastFocusVar
        )
    }

    /**
     * 在原始 Y 平面中心 1/3 区域按 stride 2 取样，跑 3×3 拉普拉斯并返回响应方差。
     *
     * 成本：4000×3000 的 Y 平面 → 中心区 1333×1000 → stride 2 → 666×500 = 333k 采样点，
     * 每 15 帧一次，对 30fps 流水线的平均开销可以忽略。
     *
     * 任何越界都返回 0（调用方保留上一次的有效值），绝不抛异常 ——
     * 相机分析流水线一旦抛异常会永久停摆。
     */
    private fun focusLaplacianVariance(
        yBuf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Float {
        val x0 = width / 3
        val x1 = (width * 2) / 3
        val y0 = height / 3
        val y1 = (height * 2) / 3
        val cols = (x1 - x0) / FOCUS_STRIDE
        val rows = (y1 - y0) / FOCUS_STRIDE
        if (cols < 8 || rows < 8) return 0f

        val block = FloatArray(cols * rows)
        val capacity = yBuf.capacity()
        var outRow = 0
        var sy = y0
        while (sy < y1 && outRow < rows) {
            val rowBase = sy * rowStride
            var outCol = 0
            var sx = x0
            while (sx < x1 && outCol < cols) {
                val index = rowBase + sx * pixelStride
                block[outRow * cols + outCol] = if (index in 0 until capacity) {
                    (yBuf.get(index).toInt() and 0xFF).toFloat()
                } else {
                    0f
                }
                outCol++
                sx += FOCUS_STRIDE
            }
            outRow++
            sy += FOCUS_STRIDE
        }
        return SharpnessMeter.laplacianVariance(block, cols, rows)
    }

    private class Grids(val luma: FloatArray, val skin: FloatArray, val cols: Int, val rows: Int)

    /**
     * 把采样颜色拼成位图并旋正。
     * 尺寸只有 160×120，看起来小，但 ML Kit 内部也会缩到 224×224 跑模型，
     * 判「人还是风景」这类大场景足够；不做面部细节识别，所以不需要更高分辨率。
     */
    private fun buildBitmap(rgb: IntArray, cols: Int, rows: Int, rotation: Int): Bitmap? {
        return try {
            val raw = Bitmap.createBitmap(rgb, cols, rows, Bitmap.Config.ARGB_8888)
            val rot = ((rotation % 360) + 360) % 360
            if (rot == 0) {
                raw
            } else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
                if (out !== raw) raw.recycle()
                out
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把传感器坐标系下的网格旋转成用户所见方向。
     * rotationDegrees 表示「需要顺时针旋转多少度才是正立画面」。
     */
    private fun toUpright(
        luma: FloatArray,
        skin: FloatArray,
        cols: Int,
        rows: Int,
        rotationDegrees: Int
    ): Grids {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0) return Grids(luma, skin, cols, rows)

        val nCols = if (rot == 180) cols else rows
        val nRows = if (rot == 180) rows else cols
        val outL = FloatArray(luma.size)
        val outS = FloatArray(skin.size)

        for (uy in 0 until nRows) {
            for (ux in 0 until nCols) {
                val src = when (rot) {
                    90 -> (nCols - 1 - ux) * cols + uy          // 顺时针 90
                    180 -> (nRows - 1 - uy) * cols + (nCols - 1 - ux)
                    else -> ux * cols + (nRows - 1 - uy)        // 270
                }
                if (src in luma.indices) {
                    outL[uy * nCols + ux] = luma[src]
                    outS[uy * nCols + ux] = skin[src]
                }
            }
        }
        return Grids(outL, outS, nCols, nRows)
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
