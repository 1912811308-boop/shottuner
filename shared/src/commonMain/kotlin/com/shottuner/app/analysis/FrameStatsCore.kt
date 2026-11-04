package com.shottuner.app.analysis

import com.shottuner.app.model.FrameStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 取景统计量的**纯计算核心**：只吃 YUV_420_888 的三个平面，吐出一个 [FrameStats]。
 *
 * ## ★ 为什么这个类住在 commonMain
 *
 * iOS 侧要和 Android 侧「用同一份图像字节跑出同一套统计量」，
 * 而 [FrameAnalyzer] 的入口是 CameraX 的 ImageProxy —— 喂不了字节。
 * 把「一帧 → FrameStats」这段从相机管道里剥出来之后，两条链路共用同一份代码：
 *
 * - 实时链路：FrameAnalyzer → ImageProxy 平面 → **本核心** → FrameStats
 * - 对齐链路：FrameStatsProducer → PNG/JPEG 字节 → RGB → I420 → **本核心** → FrameStats
 *
 * 因为走的是同一份实现，两端的差异只可能来自**解码器**，
 * 不可能来自**算法**——这正是跨端对齐要证明的事情。
 *
 * ## ★ 为什么输入是 `ByteArray` 而不是 `ByteBuffer`
 *
 * 原版签名吃 `java.nio.ByteBuffer`，而 **Kotlin/Native（iOS 侧）没有 java.nio**，
 * 于是这个类**进不了 commonMain** —— 它只能留在 app 模块里，iOS 侧永远调不到它，
 * 「同一份实现」也就无从谈起。
 *
 * `ByteArray` 是 commonMain 里可用的类型，所以入口改成三个 `ByteArray` 平面。
 *
 * ### java.nio 特有 API 清单与替换方式（逐个列清，不猜）
 *
 * | 原版（app 侧） | 现版（commonMain） | 说明 |
 * |---|---|---|
 * | `java.nio.ByteBuffer`（参数类型） | `ByteArray` | Kotlin/Native 上不存在，前者必须换掉 |
 * | `yPlane.duplicate()` | —— | **视图语义**：ByteBuffer 的 duplicate 用来造一份独立游标的副本。ByteArray 没有游标概念，天生就是按下标寻址，这个调用整体消失 |
 * | `yBuf.rewind()` | —— | **视图语义**：把游标拨回 0，方便顺序读。ByteArray 没有游标，索引本来就是绝对的，这个调用整体消失 |
 * | `yBuf.capacity()` | `yPlane.size` | 缓冲区总长 → 数组长度。两者语义相同（都是「这个平面一共有多少字节」），**越界判定 `idx >= capacity` 原样变成 `idx >= size`** |
 * | `yBuf.get(idx)`（绝对读） | `yPlane[idx]` | 绝对读一个字节。两者都返回**有符号** byte，所以后面那句 `.toInt() and 0xFF` 逐字保留即可，数值完全一致 |
 * | `uBuf.get(uIdx)` / `vBuf.get(vIdx)` | `uPlane[uIdx]` / `vPlane[vIdx]` | 同上 |
 * | `focusLaplacianVariance(yBuf = ...)` | `focusLaplacianVariance(yPlane = ...)` | 内部那处 `yBuf.capacity()` / `yBuf.get(index)` 同步换成 `yPlane.size` / `yPlane[index]` |
 *
 * ★ **算法本体（采样步长、阈值、分位数、肤色判据、拉普拉斯方差口径）逐行未改**，
 * 只有「怎么拿到字节」这一层被换掉了。
 *
 * ### ⚠️ 一处可观察到的边界差异（只在畸形输入下出现）
 *
 * `ByteBuffer.get(idx)` 是绝对读，越界会抛 `IndexOutOfBoundsException`；
 * `ByteArray[idx]` 越界会抛 `IndexOutOfBoundsException` —— 但**本类在读之前先判了边界**
 * （`idx < 0 || idx >= size` 就 `continue`），所以两边都不会抛。
 *
 * 唯一的差别在原版的边界判定用的是 `capacity()`，而 `get()` 的有效性其实由 `limit` 决定
 * （`rewind()` **不改** limit）。若某个平台的平面是 `limit < capacity`（带尾部填充），
 * 原版会读到越界并抛异常（整帧被上层 catch 掉丢弃），现版则把这些采样点跳过。
 * **正常输入下 `limit == capacity`**（CameraX 的 YUV_420_888 平面缓冲区就是
 * `rowStride * height`，limit 与 capacity 相等），两者行为完全一致。
 *
 * ## 与原 FrameAnalyzer 的行为等价性
 *
 * 本类的算法是从 FrameAnalyzer.compute() **逐行搬过来的**，常量、采样步长、
 * 阈值、分位数算法、肤色判据、拉普拉斯方差口径全部保持不变。
 * 唯一的行为差异是**清晰度的更新节奏**：
 * 实时链路每 15 帧才测一次并缓存（FrameAnalyzer 的 lastFocusVar），
 * 离线链路每张图都测一次（单张图没有"下一帧"可等）。
 *
 * 本类**不依赖任何 Android 类型**（无 Bitmap / 无 ImageProxy），
 * 因此可以直接跑 JVM 单测，也是跨端对齐时唯一需要逐字对齐的东西。
 */
object FrameStatsCore {

    /** 采样网格的目标列数 */
    const val TARGET_SAMPLES_X = 160

    /** 采样网格的目标行数 */
    const val TARGET_SAMPLES_Y = 120

    /** 亮度 >= 该值算高光溢出 */
    const val HIGHLIGHT_THRESHOLD = 250

    /** 亮度 <= 该值算暗部死黑 */
    const val SHADOW_THRESHOLD = 8

    /** 拉普拉斯采样步长：中心区按 stride 2 取样，兼顾分辨率与开销 */
    const val FOCUS_STRIDE = 2

    /** 低于该尺寸的图没有统计意义（采样网格会退化成几个点） */
    const val MIN_WIDTH = 32

    /** 低于该尺寸的图没有统计意义 */
    const val MIN_HEIGHT = 32

    /**
     * 核心的产出。
     *
     * @param stats 统计量本身
     * @param rgbGrid 采样得到的 RGB 网格（仅在需要喂端侧 AI 时才非空），**未旋正**
     * @param sensorCols 未旋正网格的列数
     * @param sensorRows 未旋正网格的行数
     * @param focusVariance 本次实测的清晰度方差；0 表示本次没测（调用方应沿用缓存）
     */
    class Result(
        val stats: FrameStats,
        val rgbGrid: IntArray?,
        val sensorCols: Int,
        val sensorRows: Int,
        val focusVariance: Float
    )

    /**
     * 从 YUV_420_888 平面算出统计量。
     *
     * @param yPlane Y 平面字节（长度 >= 任何被访问的下标）
     * @param uPlane U 平面字节
     * @param vPlane V 平面字节
     * @param yRowStride Y 平面每行的字节跨度
     * @param yPixelStride Y 平面相邻像素的字节跨度
     * @param uRowStride U 平面每行的字节跨度
     * @param uPixelStride U 平面相邻像素的字节跨度
     * @param vRowStride V 平面每行的字节跨度
     * @param vPixelStride V 平面相邻像素的字节跨度
     * @param width 图像宽（像素）
     * @param height 图像高（像素）
     * @param rotationDegrees 顺时针旋转多少度才是正立画面；0 表示图像本身已经是正的
     * @param computeFocus 是否本次要实测清晰度（实时链路不是每帧都测）
     * @param focusFallback 当 [computeFocus] 为 false 或实测失败时沿用的清晰度方差
     * @param emitRgb 是否产出 RGB 网格（只有喂端侧 AI 的帧才需要）
     * @return 统计结果；尺寸过小等无法统计的情况返回 null
     */
    fun compute(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        computeFocus: Boolean,
        focusFallback: Float,
        emitRgb: Boolean
    ): Result? {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return null

        val stepX = max(1, width / TARGET_SAMPLES_X)
        val stepY = max(1, height / TARGET_SAMPLES_Y)

        val cols = (width + stepX - 1) / stepX
        val rows = (height + stepY - 1) / stepY
        if (cols < 4 || rows < 4) return null

        val hist = IntArray(256)
        val grid = FloatArray(cols * rows)
        var sumY = 0.0
        var count = 0
        var clipHigh = 0
        var clipLow = 0

        var sumCenter = 0.0
        var countCenter = 0

        val cx0 = (width * 0.28f).toInt()
        val cx1 = (width * 0.72f).toInt()
        val cy0 = (height * 0.28f).toInt()
        val cy1 = (height * 0.72f).toInt()

        for (ry in 0 until rows) {
            val sy = ry * stepY
            if (sy >= height) break
            val rowBase = sy * yRowStride
            for (cx in 0 until cols) {
                val sx = cx * stepX
                if (sx >= width) break
                val idx = rowBase + sx * yPixelStride
                if (idx < 0 || idx >= yPlane.size) continue
                val yv = yPlane[idx].toInt() and 0xFF
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
        val rgbBuf: IntArray? = if (emitRgb) IntArray(cols * rows) else null

        val chromaStepX = stepX * 2
        val chromaStepY = stepY * 2

        var chasedY = 0
        while (chasedY < height) {
            val uvRow = chasedY / 2
            var chasedX = 0
            while (chasedX < width) {
                val uvCol = chasedX / 2
                val uIdx = uvRow * uRowStride + uvCol * uPixelStride
                val vIdx = uvRow * vRowStride + uvCol * vPixelStride
                if (uIdx in 0 until uPlane.size && vIdx in 0 until vPlane.size) {
                    val u = (uPlane[uIdx].toInt() and 0xFF) - 128
                    val v = (vPlane[vIdx].toInt() and 0xFF) - 128
                    val yIdx = chasedY * yRowStride + chasedX * yPixelStride
                    if (yIdx in 0 until yPlane.size) {
                        val yv = (yPlane[yIdx].toInt() and 0xFF).toFloat()
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

        // 清晰度：实时链路按节奏抽测并缓存，离线链路每张图都测。
        // measuredFocus 记录「本次实测值」，focusVar 是「本次实际生效值」。
        var focusVar = focusFallback
        var measuredFocus = 0f
        if (computeFocus) {
            val variance = focusLaplacianVariance(
                yPlane = yPlane,
                width = width,
                height = height,
                rowStride = yRowStride,
                pixelStride = yPixelStride
            )
            measuredFocus = variance
            if (variance > 0f) focusVar = variance
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

        val upright = toUpright(grid, skinGrid, cols, rows, rotationDegrees)

        return Result(
            stats = FrameStats(
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
                width = width,
                height = height,
                grid = upright.luma,
                skinGrid = upright.skin,
                gridCols = upright.cols,
                gridRows = upright.rows,
                focusVar = focusVar
            ),
            rgbGrid = rgbBuf,
            sensorCols = cols,
            sensorRows = rows,
            focusVariance = measuredFocus
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
        yPlane: ByteArray,
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
        val capacity = yPlane.size
        var outRow = 0
        var sy = y0
        while (sy < y1 && outRow < rows) {
            val rowBase = sy * rowStride
            var outCol = 0
            var sx = x0
            while (sx < x1 && outCol < cols) {
                val index = rowBase + sx * pixelStride
                block[outRow * cols + outCol] = if (index in 0 until capacity) {
                    (yPlane[index].toInt() and 0xFF).toFloat()
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
