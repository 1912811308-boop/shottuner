package com.shottuner.app.analysis

import org.junit.Test
import java.io.File

/**
 * ★ 临时基线采集器（**不提交**）。
 *
 * 用途：在 `FrameStatsCore` 搬进 commonMain **之前**，把当前实现（`ByteBuffer` 版）
 * 在固定测试图集上的输出逐字段打印出来，作为「算法逐行未变」的**对照基线**。
 *
 * 搬完之后，同一个文件改成调 common 版（`ByteArray` 版），再跑一次，
 * 两个 dump 文件必须**逐字节相同**。
 *
 * ★ 为什么用「dump 文件」而不是断言：
 *   断言只能钉住几个字段，而跨端对齐（T12 验收①）要比的是**逐字段 + 网格每一格**，
 *   所以这里直接落全量文本，后面用 `diff` 比。
 */
class FrameStatsBaselineDumpTest {

    @Test
    fun dumpBaseline() {
        val fixtureDir = File("../docs/fixtures/frame-stats")
        check(fixtureDir.isDirectory) { "找不到测试图集目录：${fixtureDir.absolutePath}" }

        val files = fixtureDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()
        check(files.isNotEmpty()) { "测试图集是空的：${fixtureDir.absolutePath}" }

        val out = StringBuilder()
        for (f in files) {
            val bytes = f.readBytes()
            val img = PngDecoder.decode(bytes)
            val yuv = RgbToYuv.toI420(img)

            // ★ 搬之前的调用方式：ByteBuffer 版。
            val result = FrameStatsCore.compute(
                yPlane = yuv.yBuffer(),
                uPlane = yuv.uBuffer(),
                vPlane = yuv.vBuffer(),
                yRowStride = yuv.yRowStride,
                yPixelStride = 1,
                uRowStride = yuv.uRowStride,
                uPixelStride = 1,
                vRowStride = yuv.vRowStride,
                vPixelStride = 1,
                width = img.width,
                height = img.height,
                rotationDegrees = 0,
                computeFocus = true,
                focusFallback = 0f,
                emitRgb = false
            )
            checkNotNull(result) { "${f.name} 算不出统计量" }
            val s = result.stats

            out.append("### ${f.name}\n")
            out.append("width=${s.width} height=${s.height}\n")
            out.append("meanLuma=${s.meanLuma}\n")
            out.append("centerLuma=${s.centerLuma}\n")
            out.append("p05=${s.p05}\n")
            out.append("p50=${s.p50}\n")
            out.append("p95=${s.p95}\n")
            out.append("highlightClip=${s.highlightClip}\n")
            out.append("shadowClip=${s.shadowClip}\n")
            out.append("meanR=${s.meanR}\n")
            out.append("meanG=${s.meanG}\n")
            out.append("meanB=${s.meanB}\n")
            out.append("saturation=${s.saturation}\n")
            out.append("edgeDensity=${s.edgeDensity}\n")
            out.append("skinRatio=${s.skinRatio}\n")
            out.append("focusVar=${s.focusVar}\n")
            out.append("gridCols=${s.gridCols} gridRows=${s.gridRows}\n")
            out.append("histogram=").append(s.histogram.joinToString(",")).append('\n')
            out.append("grid=").append(s.grid.joinToString(",")).append('\n')
            out.append("skinGrid=").append(s.skinGrid.joinToString(",")).append('\n')
        }

        val target = File("build/frame-stats-baseline-before.txt")
        target.parentFile?.mkdirs()
        target.writeText(out.toString(), Charsets.UTF_8)
        println("BASELINE_WRITTEN=${target.absolutePath}")
    }
}
