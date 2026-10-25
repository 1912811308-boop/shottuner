package com.shottuner.app.analysis

/**
 * 解码后的位图：ARGB_8888 像素 + 尺寸。
 *
 * 之所以单独抽一个类型，是为了让「解码器」和「分析器」之间有一层稳定边界：
 * 上游不管来的是 PNG 还是 JPEG，下游拿到的都是同一种像素表示，
 * 跨端对齐时只需要对齐**这一层**，不需要对齐各自的图像库。
 */
internal class DecodedImage(
    val width: Int,
    val height: Int,
    /** 长度 = width * height，每格一个 ARGB_8888 像素 */
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "图像尺寸非法：${width}x$height" }
        require(pixels.size == width * height) {
            "像素数量与尺寸不符：期望 ${width * height}，实际 ${pixels.size}"
        }
    }

    /** 取 (x, y) 处的红色分量 */
    fun redAt(x: Int, y: Int): Int = (pixels[y * width + x] shr 16) and 0xFF

    /** 取 (x, y) 处的绿色分量 */
    fun greenAt(x: Int, y: Int): Int = (pixels[y * width + x] shr 8) and 0xFF

    /** 取 (x, y) 处的蓝色分量 */
    fun blueAt(x: Int, y: Int): Int = pixels[y * width + x] and 0xFF
}
