package com.shottuner.app.analysis

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 极简 PNG 解码器：只依赖 JDK 自带的 `java.util.zip.Inflater`，不引入任何第三方库。
 *
 * ## 为什么自己写而不用 BitmapFactory
 * 1. **可测**：本工程没有 Robolectric（也不允许引入第三方依赖），
 *    `android.graphics.BitmapFactory` 在纯 JVM 单测里根本调不通。
 *    自己写之后，整条「字节 → FrameStats」的链路可以直接跑 JVM 单测。
 * 2. **可对照**：PNG 解码是**无损**的，Android 的 BitmapFactory 与 iOS 的 ImageIO
 *    对同一份 PNG 解出来的像素应当逐字节相同。自己写一份之后，
 *    我们能保证「Android 侧喂给分析器的就是 PNG 里的原始像素」，
 *    跨端比对时可以把「解码器差异」这一项彻底排除掉。
 *
 * ## 支持范围
 * - 颜色类型：灰度(0)、真彩(2)、调色板(3)、灰度+透明(4)、真彩+透明(6)
 * - 位深：1 / 2 / 4 / 8 / 16（16 位按高字节降为 8 位）
 * - 非隔行（interlace = 0）。Adam7 隔行不支持，遇到时明确报错而不是给出错误结果。
 *
 * 透明像素按**合成到黑底**处理（与 iOS `premultipliedLast` 位图上下文的语义一致）。
 */
internal object PngDecoder {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 判断这段字节是不是 PNG */
    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE.size) return false
        for (i in SIGNATURE.indices) {
            if (bytes[i] != SIGNATURE[i]) return false
        }
        return true
    }

    /**
     * 解码 PNG。
     *
     * @throws IllegalArgumentException 数据不是合法 PNG，或使用了不支持的特性
     */
    fun decode(bytes: ByteArray): DecodedImage {
        if (!matches(bytes)) throw IllegalArgumentException("不是合法的 PNG：文件头签名不匹配")

        var offset = SIGNATURE.size
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var palette: IntArray? = null
        var paletteAlpha: ByteArray? = null
        var transparentGray = -1
        var transparentRgb = -1
        val idat = ByteArrayOutputStream()

        // 逐块遍历；IHDR 必须第一个出现，IEND 之后不再解析
        var seenIhdr = false
        var seenIend = false
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + 12L + length > bytes.size) {
                throw IllegalArgumentException("PNG 数据块越界（偏移 $offset，长度 $length）")
            }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            when (type) {
                "IHDR" -> {
                    if (seenIhdr) throw IllegalArgumentException("PNG 出现重复的 IHDR 块")
                    if (length != 13) throw IllegalArgumentException("PNG 的 IHDR 块长度非法：$length")
                    width = readInt(bytes, dataStart)
                    height = readInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    val compression = bytes[dataStart + 10].toInt() and 0xFF
                    val filter = bytes[dataStart + 11].toInt() and 0xFF
                    val interlace = bytes[dataStart + 12].toInt() and 0xFF
                    if (compression != 0) throw IllegalArgumentException("不支持的 PNG 压缩方式：$compression")
                    if (filter != 0) throw IllegalArgumentException("不支持的 PNG 过滤方式：$filter")
                    if (interlace != 0) throw IllegalArgumentException("不支持隔行（Adam7）PNG")
                    seenIhdr = true
                }
                "PLTE" -> {
                    if (length % 3 != 0) throw IllegalArgumentException("PNG 的 PLTE 块长度非法：$length")
                    val entries = length / 3
                    val table = IntArray(entries)
                    for (i in 0 until entries) {
                        val p = dataStart + i * 3
                        table[i] = (0xFF shl 24) or
                            ((bytes[p].toInt() and 0xFF) shl 16) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            (bytes[p + 2].toInt() and 0xFF)
                    }
                    palette = table
                }
                "tRNS" -> {
                    when (colorType) {
                        3 -> paletteAlpha = bytes.copyOfRange(dataStart, dataStart + length)
                        0 -> if (length >= 2) {
                            transparentGray = readInt(bytes, dataStart) and 0xFFFF
                        }
                        2 -> if (length >= 6) {
                            transparentRgb = (readInt(bytes, dataStart) and 0xFFFF) shl 16 or
                                (readInt(bytes, dataStart + 2) and 0xFFFF) shl 8 or
                                (readInt(bytes, dataStart + 4) and 0xFFFF)
                        }
                    }
                }
                "IDAT" -> idat.write(bytes, dataStart, length)
                "IEND" -> seenIend = true
            }
            offset = dataStart + length + 4 // 跳过 CRC
            if (seenIend) break
        }

        if (!seenIhdr) throw IllegalArgumentException("PNG 缺少 IHDR 块")
        if (width <= 0 || height <= 0) throw IllegalArgumentException("PNG 尺寸非法：${width}x$height")
        if (idat.size() == 0) throw IllegalArgumentException("PNG 缺少 IDAT 数据")

        val channels = channelCount(colorType)
        val bitsPerPixel = channels * bitDepth
        val stride = (width * bitsPerPixel + 7) / 8
        val raw = inflate(idat.toByteArray(), height * (stride + 1))
        val lines = unfilter(raw, height, stride, bytesPerPixel(bitsPerPixel))

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val row = lines.copyOfRange(y * stride, (y + 1) * stride)
            for (x in 0 until width) {
                out[y * width + x] = readPixel(
                    row = row,
                    x = x,
                    bitDepth = bitDepth,
                    colorType = colorType,
                    palette = palette,
                    paletteAlpha = paletteAlpha,
                    transparentGray = transparentGray,
                    transparentRgb = transparentRgb
                )
            }
        }
        return DecodedImage(width, height, out)
    }

    /** PNG 颜色类型对应的通道数 */
    private fun channelCount(colorType: Int): Int = when (colorType) {
        0 -> 1 // 灰度
        2 -> 3 // 真彩
        3 -> 1 // 调色板索引
        4 -> 2 // 灰度 + Alpha
        6 -> 4 // 真彩 + Alpha
        else -> throw IllegalArgumentException("不支持的 PNG 颜色类型：$colorType")
    }

    /** 每像素占多少字节（滤波器的参考单位） */
    private fun bytesPerPixel(bitsPerPixel: Int): Int = ((bitsPerPixel + 7) / 8).coerceAtLeast(1)

    /** zlib 解压，得到「每行 1 字节滤波标记 + 像素数据」的原始流 */
    private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArray(expected)
        var written = 0
        try {
            while (written < expected && !inflater.finished()) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                written += n
            }
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("PNG 的 IDAT 数据损坏：${e.message}", e)
        } finally {
            inflater.end()
        }
        if (written < expected) {
            throw IllegalArgumentException("PNG 的 IDAT 数据不完整：期望 $expected 字节，实际 $written 字节")
        }
        return out
    }

    /** 逐行撤销 PNG 的五种滤波器，返回连续的像素数据 */
    private fun unfilter(raw: ByteArray, height: Int, stride: Int, bpp: Int): ByteArray {
        val out = ByteArray(height * stride)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src].toInt() and 0xFF
            src++
            val rowStart = y * stride
            val prevStart = rowStart - stride
            for (i in 0 until stride) {
                val x = raw[src + i].toInt() and 0xFF
                val a = if (i >= bpp) out[rowStart + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) out[prevStart + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) out[prevStart + i - bpp].toInt() and 0xFF else 0
                val value = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> x + paeth(a, b, c)
                    else -> throw IllegalArgumentException("不支持的 PNG 行滤波类型：$filter")
                }
                out[rowStart + i] = (value and 0xFF).toByte()
            }
            src += stride
        }
        return out
    }

    /** PNG 的 Paeth 预测器 */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /** 从一行像素数据里取出 (x, y) 对应的 ARGB 像素 */
    private fun readPixel(
        row: ByteArray,
        x: Int,
        bitDepth: Int,
        colorType: Int,
        palette: IntArray?,
        paletteAlpha: ByteArray?,
        transparentGray: Int,
        transparentRgb: Int
    ): Int {
        val channels = channelCount(colorType)
        fun sample(channel: Int): Int {
            if (bitDepth == 8) {
                return row[x * channels + channel].toInt() and 0xFF
            }
            if (bitDepth == 16) {
                val p = (x * channels + channel) * 2
                return (row[p].toInt() and 0xFF) // 取高字节降为 8 位
            }
            // 1 / 2 / 4 位打包
            val bitPos = (x * channels + channel) * bitDepth
            val byte = row[bitPos / 8].toInt() and 0xFF
            val shift = 8 - bitDepth - (bitPos % 8)
            return (byte shr shift) and ((1 shl bitDepth) - 1)
        }

        /** 低位深灰度要拉伸到 0..255 */
        fun scale(v: Int): Int = if (bitDepth >= 8) v else v * 255 / ((1 shl bitDepth) - 1)

        var r: Int
        var g: Int
        var b: Int
        var a = 255

        when (colorType) {
            0 -> {
                val gray = scale(sample(0))
                r = gray; g = gray; b = gray
                if (transparentGray >= 0 && gray == transparentGray) a = 0
            }
            2 -> {
                r = sample(0); g = sample(1); b = sample(2)
                if (transparentRgb >= 0 &&
                    ((r shl 16) or (g shl 8) or b) == transparentRgb
                ) a = 0
            }
            3 -> {
                val index = sample(0)
                val table = palette ?: throw IllegalArgumentException("PNG 使用调色板但缺少 PLTE 块")
                if (index >= table.size) {
                    throw IllegalArgumentException("PNG 调色板索引越界：$index >= ${table.size}")
                }
                val entry = table[index]
                r = (entry shr 16) and 0xFF
                g = (entry shr 8) and 0xFF
                b = entry and 0xFF
                val alphaTable = paletteAlpha
                if (alphaTable != null && index < alphaTable.size) {
                    a = alphaTable[index].toInt() and 0xFF
                }
            }
            4 -> {
                val gray = scale(sample(0))
                r = gray; g = gray; b = gray
                a = sample(1)
            }
            6 -> {
                r = sample(0); g = sample(1); b = sample(2)
                a = sample(3)
            }
            else -> throw IllegalArgumentException("不支持的 PNG 颜色类型：$colorType")
        }

        if (a == 255) {
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // 透明像素合成到黑底（与 iOS premultiplied 语义一致）
        r = (r * a + 127) / 255
        g = (g * a + 127) / 255
        b = (b * a + 127) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 读 4 字节大端整数 */
    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
