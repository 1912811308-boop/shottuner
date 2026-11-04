package com.shottuner.app.analysis

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.shottuner.app.model.FrameStats
import java.nio.ByteBuffer

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
 *
 * ## ★ 算法本体住在 commonMain 的 [FrameStatsCore]
 *
 * 本类**不再自己算**。它现在只做三件平台相关的事：
 *
 * 1. 把 CameraX [ImageProxy] 的三个 `ByteBuffer` 平面拷成 [ByteArray]
 *    （[FrameStatsCore] 是 commonMain 代码，Kotlin/Native 上没有 `java.nio`）；
 * 2. 把 stride / 旋转角 / 清晰度抽测节奏 / 位图抽样节奏这些**平台状态**传给核心；
 * 3. 把核心产出的 RGB 网格用 Android 的 `Bitmap` 拼出来（这一步必须留在 app 模块）。
 *
 * 这样「一帧 → [FrameStats]」在 Android 实时链路与 iOS 对齐链路上是**同一份 Kotlin 代码**：
 * 两端的差异只可能来自解码器，不可能来自算法。
 *
 * ### 与旧版的行为等价性（逐项核对，算法一行未改）
 *
 * | 旧版 | 现版 | 结果 |
 * |---|---|---|
 * | 本类内联的采样步长 / 阈值 / 分位数 / 肤色判据 | [FrameStatsCore] 逐行搬过去，常量不变 | 相同 |
 * | `yBuf.capacity()` 越界判定 | [FrameStatsCore] 用 `yPlane.size` 判定 | 正常输入（limit == capacity）相同 |
 * | 每 15 帧测一次拉普拉斯、> 0 才更新 `lastFocusVar` | `computeFocus = frameCount % 15 == 0` + `focusFallback = lastFocusVar`，用 `focusVariance > 0` 更新缓存 | 相同 |
 * | `FrameStats.focusVar = lastFocusVar`（更新后） | 核心的 `focusVar` = 实测值（> 0 时）否则 `focusFallback` | 相同 |
 * | `rgbBuf` + `buildBitmap(cols, rows, rotation)` | 核心的 `rgbGrid` + `sensorCols` / `sensorRows`（均未旋正） | 相同 |
 * | 尺寸 < 32、`cols/rows < 4`、`count == 0` 返回 null | 核心同样的三道前置判定 | 相同 |
 */
class FrameAnalyzer(
    private val callback: (FrameStats) -> Unit,
    private val onBitmap: ((Bitmap) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        /** AI 不需要逐帧：1.5 秒一次足够，也避免和测光抢 CPU */
        private const val BITMAP_EVERY_N_FRAMES = 45

        /**
         * 中心区拉普拉斯方差（清晰度）的更新间隔。
         * 30fps 下约 0.5 秒一次：清晰度是慢变量，没必要逐帧算。
         */
        private const val FOCUS_EVERY_N_FRAMES = 15
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

    /**
     * 把一帧 [ImageProxy] 交给 [FrameStatsCore] 统计。
     *
     * @param image CameraX 提供的 YUV_420_888 帧
     * @param emitBitmap 本帧是否要顺手产出喂端侧 AI 的小位图
     * @return 统计量；格式不支持 / 尺寸过小 / 核心判定无有效采样点时返回 null
     */
    private fun compute(image: ImageProxy, emitBitmap: Boolean): FrameStats? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val w = image.width
        val h = image.height
        if (w < 32 || h < 32) return null

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        // ByteBuffer → ByteArray：按 remaining() 逐字节拷贝，保留平面原有的
        // rowStride / pixelStride 布局，所以下游按 stride 算出的下标与旧版完全一致。
        val yBytes = yPlane.buffer.toPlaneBytes()
        val uBytes = uPlane.buffer.toPlaneBytes()
        val vBytes = vPlane.buffer.toPlaneBytes()

        val rotation = image.imageInfo.rotationDegrees
        val result = FrameStatsCore.compute(
            yPlane = yBytes,
            uPlane = uBytes,
            vPlane = vBytes,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            width = w,
            height = h,
            rotationDegrees = rotation,
            computeFocus = frameCount % FOCUS_EVERY_N_FRAMES == 0,
            focusFallback = lastFocusVar,
            emitRgb = emitBitmap
        ) ?: return null

        // 核心把「本次实测值」与「本次生效值」分开返回了：
        // 只有实测有效（> 0）才刷新缓存，其余帧沿用上一次的值。
        if (result.focusVariance > 0f) lastFocusVar = result.focusVariance

        val rgbGrid = result.rgbGrid
        if (rgbGrid != null && onBitmap != null) {
            buildBitmap(rgbGrid, result.sensorCols, result.sensorRows, rotation)
                ?.let { onBitmap.invoke(it) }
        }

        return result.stats
    }

    /**
     * 把 [ByteBuffer] 的剩余字节拷成 [ByteArray]。
     *
     * 用**绝对读**（`get(start + i)`）而不是相对读：相对读会推进 position，
     * 而 CameraX 的平面缓冲区是复用对象，改动它的游标会影响其它消费者。
     * 同理也不需要 `duplicate()` / `rewind()`。
     *
     * 拷贝长度取 `remaining()`（= limit - position）。正常输入下 CameraX 的
     * YUV_420_888 平面 `limit == capacity == rowStride * height`，与旧版按
     * `capacity()` 做越界判定的可见字节集合完全相同。
     *
     * @return 与源缓冲区当前 position 对齐、长度等于 remaining() 的字节数组
     */
    private fun ByteBuffer.toPlaneBytes(): ByteArray {
        val start = position()
        val bytes = ByteArray(remaining())
        for (i in bytes.indices) {
            bytes[i] = get(start + i)
        }
        return bytes
    }

    /**
     * 把采样颜色拼成位图并旋正。
     * 尺寸只有 160×120，看起来小，但 ML Kit 内部也会缩到 224×224 跑模型，
     * 判「人还是风景」这类大场景足够；不做面部细节识别，所以不需要更高分辨率。
     *
     * 这一步留在 app 模块：`Bitmap` / `Matrix` 是 Android 类型，commonMain 不能碰。
     *
     * @param rgb 未旋正的采样 RGB 网格（来自 [FrameStatsCore.Result.rgbGrid]）
     * @param cols 未旋正网格列数（[FrameStatsCore.Result.sensorCols]）
     * @param rows 未旋正网格行数（[FrameStatsCore.Result.sensorRows]）
     * @param rotation 需要顺时针旋转多少度才是正立画面
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
}
