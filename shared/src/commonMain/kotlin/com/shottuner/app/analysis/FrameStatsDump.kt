package com.shottuner.app.analysis

import com.shottuner.app.model.FrameStats
import com.shottuner.app.text.NumFmt

/**
 * 跨端对齐用的 [FrameStats] 文本快照。
 *
 * ## ★ 为什么文本必须由 Kotlin 侧生成
 *
 * T12 的验收方式是「同一批固定测试图（无损 PNG）分别喂给两端，把统计量
 * 逐字段落盘后 diff」。如果 Android 用 Kotlin 拼文本、iOS 用 Swift 拼文本，
 * 那么 **Float 的字符串化** 就成了第二个变量：Kotlin 的 `"$float"` 与 Swift 的
 * `String(describing:)` 在尾数位数、科学计数法阈值、`-0.0`、`0.0` 的写法上都可能
 * 不同，于是**算法明明一致，diff 却永远对不上**，还得反过来排查是不是格式化问题。
 *
 * 所以这里把「统计量 → 文本」也收进 commonMain：iOS 的 `dumpFrameStats` 与将来
 * Android 侧的 dump 工具都调 [computeDump] / [render]，Swift 只负责
 * 「传字节 → 拿字符串 → 写文件」。格式一致性由**共用同一份代码**保证，
 * 而不是靠两边对着文档手工对齐。
 *
 * ## ★ 为什么数值走 [NumFmt] 而不是字符串插值
 *
 * - [NumFmt] 是 commonMain 里的定点格式化器：小数点恒为 `.`，不跟随系统 Locale；
 *   舍入规则（半值远离零）自己用 IEEE-754 双精度实现，不依赖
 *   `String.format` / `DecimalFormat`（这些在 Kotlin/Native 上不存在）。
 * - 插值 `"$float"` 走的是各平台自己的 Float.toString 实现，JVM 与 Kotlin/Native
 *   不保证逐字一致；[NumFmt] 把浮点先转成整数再拼字符串，两端逐位一致。
 * - 换行固定写 `\n`（LF），不用 `System.lineSeparator()` —— 后者是 JVM-only，
 *   且在 Windows 上会给出 CRLF，跨平台 diff 又会对不上。
 *
 * ## 输出格式（每行一个字段，数组用逗号分隔；末尾带一个换行便于多张图拼接）
 *
 * ```text
 * ### <label>
 * width=.. height=..
 * meanLuma=..
 * centerLuma=..
 * p05=..
 * p50=..
 * p95=..
 * highlightClip=..
 * shadowClip=..
 * meanR=..
 * meanG=..
 * meanB=..
 * saturation=..
 * edgeDensity=..
 * skinRatio=..
 * focusVar=..
 * gridCols=.. gridRows=..
 * histogram=<逗号分隔>
 * grid=<逗号分隔>
 * skinGrid=<逗号分隔>
 * ```
 *
 * 所有浮点统一保留 6 位小数（精度远高于验收要求的 2% 差异阈值，
 * 又足以暴露真实的末位差异）；整数直接十进制输出，两端无歧义。
 */
object FrameStatsDump {

    /** 浮点字段统一的小数位数。 */
    private const val DECIMALS: Int = 6

    /**
     * 「三平面字节 → dump 文本」的**唯一跨端入口**。
     *
     * 参数口径在这里写死，两端不可能各传各的：
     *
     * | 项 | 取值 | 理由 |
     * |---|---|---|
     * | yPixelStride / uPixelStride / vPixelStride | 1 | 固定测试图集转出的 I420 是紧凑平面，8bit 每像素 1 字节 |
     * | vRowStride | = [uRowStride] | I420 的 U/V 两个色度平面尺寸相同，行跨度自然相同 |
     * | rotationDegrees | 0 | 测试图本身就是正立的，不需要旋正 |
     * | computeFocus | true | 单张离线图没有「下一帧」，每张都要实测清晰度 |
     * | focusFallback | 0f | 离线链路没有上一帧缓存可沿用 |
     * | emitRgb | false | dump 只要统计量，不要喂 AI 的位图网格 |
     *
     * @param yPlane Y 平面字节
     * @param uPlane U 平面字节
     * @param vPlane V 平面字节
     * @param yRowStride Y 平面每行字节跨度
     * @param uRowStride U 平面每行字节跨度（V 平面沿用同一个值）
     * @param width 图像宽（像素）
     * @param height 图像高（像素）
     * @param label 写入首行 `###` 标题的图名，例如 `01-bright`
     * @return 可直接落盘、可与另一端逐字节 diff 的文本
     */
    fun computeDump(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        width: Int,
        height: Int,
        label: String
    ): String {
        val result = FrameStatsCore.compute(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = yRowStride,
            yPixelStride = 1,
            uRowStride = uRowStride,
            uPixelStride = 1,
            vRowStride = uRowStride,
            vPixelStride = 1,
            width = width,
            height = height,
            rotationDegrees = 0,
            computeFocus = true,
            focusFallback = 0f,
            emitRgb = false
        )
        return if (result == null) {
            renderUnavailable(label, width, height)
        } else {
            render(label, result.stats)
        }
    }

    /**
     * 把一组统计量渲染成固定格式的文本。
     *
     * @param label 图名，写入 `### ` 标题行
     * @param stats 由 [FrameStatsCore] 算出的统计量
     * @return 以 `\n` 结尾的文本快照
     */
    fun render(label: String, stats: FrameStats): String = buildString {
        append("### ").append(label).append('\n')
        append("width=").append(stats.width).append(" height=").append(stats.height).append('\n')
        append("meanLuma=").append(f(stats.meanLuma)).append('\n')
        append("centerLuma=").append(f(stats.centerLuma)).append('\n')
        append("p05=").append(f(stats.p05)).append('\n')
        append("p50=").append(f(stats.p50)).append('\n')
        append("p95=").append(f(stats.p95)).append('\n')
        append("highlightClip=").append(f(stats.highlightClip)).append('\n')
        append("shadowClip=").append(f(stats.shadowClip)).append('\n')
        append("meanR=").append(f(stats.meanR)).append('\n')
        append("meanG=").append(f(stats.meanG)).append('\n')
        append("meanB=").append(f(stats.meanB)).append('\n')
        append("saturation=").append(f(stats.saturation)).append('\n')
        append("edgeDensity=").append(f(stats.edgeDensity)).append('\n')
        append("skinRatio=").append(f(stats.skinRatio)).append('\n')
        append("focusVar=").append(f(stats.focusVar)).append('\n')
        append("gridCols=").append(stats.gridCols).append(" gridRows=").append(stats.gridRows).append('\n')
        append("histogram=").append(csv(stats.histogram)).append('\n')
        append("grid=").append(csv(stats.grid)).append('\n')
        append("skinGrid=").append(csv(stats.skinGrid)).append('\n')
    }

    /**
     * 尺寸过小 / 无有效采样点导致 [FrameStatsCore.compute] 返回 null 时的占位文本。
     *
     * 必须和 [render] 一样由 Kotlin 生成，而且在两端一致：否则一边写「跳过」、
     * 一边写 `null`，diff 里就分不清「算法产出不同」与「表述不同」。
     *
     * @param label 图名
     * @param width 图像宽
     * @param height 图像高
     * @return 以 `\n` 结尾的占位文本
     */
    fun renderUnavailable(label: String, width: Int, height: Int): String = buildString {
        append("### ").append(label).append('\n')
        append("width=").append(width).append(" height=").append(height).append('\n')
        append("stats=UNAVAILABLE").append('\n')
    }

    /** 定点 6 位小数；走 [NumFmt]，不经过任何平台相关的浮点字符串化。 */
    private fun f(value: Float): String = NumFmt.fixed(value, DECIMALS)

    /** 浮点数组 → 逗号分隔文本；空数组得到空串（不是 `[]`）。 */
    private fun csv(values: FloatArray): String = buildString {
        for (i in values.indices) {
            if (i > 0) append(',')
            append(f(values[i]))
        }
    }
}
