package com.shottuner.app.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * 成绩水印（G2-A）。
 *
 * 拍完的成片在右下角烧两到三行半透明文字 + 一个小 logo 标记：
 *
 * ```
 *                                   ● 光影调参
 *                              参考分 78 · 良好
 *                                   三分法
 * ```
 *
 * ## 这个文件为什么分两半
 * 绘制（[stamp] / [draw]）离不开 `Canvas`，只能真机验证；
 * **尺寸与文案**（[buildLines] / [measure] / [shrinkToWidth] / [shortenTechnique]）
 * 全部是纯 JVM 函数，不碰任何 Android 类型，可以直接跑单测。
 * 「4:3 和 16:9 都不能溢出、不能被裁」这条硬要求就是靠这几个纯函数守住的。
 *
 * ## 颜色为什么不是纯白
 * 纯白压在雪地 / 天空 / 白墙上会整块消失。这里用 91% 白（[TEXT_ALPHA]）
 * + 半透明黑描边 + 一层淡阴影，亮底靠描边压边、暗底靠白字提亮。
 *
 * ## 评分口径
 * 只说「参考分」。任一维度未测时 `total` 传 null，落出来是「参考分 --」，
 * **绝不烧一个算出来的假数字**。
 */
object Watermark {

    /** 第一行：产品名。这是跟着照片跑出应用的唯一传播载体 */
    const val BRAND = "光影调参"

    /** 未测全时的分数文案。不能是数字，也不能是「未测」以外的评价词 */
    const val SCORE_UNKNOWN = "参考分 --"

    /** 水印块总高相对**图片短边**的上限（4%）。4:3 / 16:9 / 竖构图都按它算 */
    const val MAX_BLOCK_HEIGHT_RATIO = 0.04f

    /** 一份水印数据。`total` 为 null = 有维度未测。 */
    data class Mark(
        val total: Int?,
        val grade: String?,
        /** 这条建议关联的技法简称（如「三分法」）。null → 只烧两行 */
        val technique: String?
    )

    /** [measure] 的输出。单位一律是像素（相对原图像素，不是 dp）。 */
    data class WatermarkLayout(
        /** 水印块到图片右边缘的距离 */
        val marginX: Float,
        /** 水印块到图片下边缘的距离 */
        val marginY: Float,
        /** 块内水平留白 */
        val paddingX: Float,
        /** 块内垂直留白 */
        val paddingY: Float,
        /** 行间距 */
        val lineGap: Float,
        /** 第一行（品牌）字号 */
        val brandSize: Float,
        /** 第二行（参考分）字号 */
        val scoreSize: Float,
        /** 第三行（技法）字号 */
        val techSize: Float,
        /** logo 标记直径 */
        val logoSize: Float,
        /** logo 与品牌文字的间距 */
        val logoGap: Float,
        /** 文本可用的最大宽度（已扣掉两边边距与 logo 占位） */
        val maxTextWidth: Float,
        /** 水印块总高 */
        val blockHeight: Float
    ) {
        /** 水印块总宽。按构造等于 `图片宽 - 2 × marginX`，因此永远不会横向溢出 */
        val blockWidth: Float get() = paddingX * 2f + logoSize + logoGap + maxTextWidth
    }

    // ── 纯 JVM：文案 ──────────────────────────────────────────────────────────

    /**
     * 第二行文案。
     *
     * `total` 为 null（有维度未测）或落在 0..100 之外时，一律返回「参考分 --」，
     * 并且**丢掉等级** —— 分数不可信时等级同样不可信，一起显示等于烧假数据。
     */
    fun formatScoreLine(total: Int?, grade: String?): String {
        val score = total?.takeIf { it in 0..100 } ?: return SCORE_UNKNOWN
        val g = grade?.trim()?.takeIf { it.isNotEmpty() }
        return if (g == null) "参考分 $score" else "参考分 $score · $g"
    }

    /**
     * 技法简称。
     *
     * 技法库里的 `title` 是整句（「眼睛放上三分线，别放正中」），直接烧到照片上太长，
     * 所以砍到第一个逗号/冒号/句号为止，再按 [MAX_TECHNIQUE_CHARS] 截断。
     * 取不到就返回 null —— 调用方据此只烧两行。
     */
    fun shortenTechnique(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var end = text.length
        for (i in text.indices) {
            val c = text[i]
            if (c == '，' || c == ',' || c == '：' || c == ':' || c == '。' || c == '；') {
                end = i
                break
            }
        }
        val head = text.substring(0, end).trim()
        if (head.isEmpty()) return null
        return if (head.length <= MAX_TECHNIQUE_CHARS) {
            head
        } else {
            head.substring(0, MAX_TECHNIQUE_CHARS - 1) + "…"
        }
    }

    /**
     * 水印的全部文字行。
     *
     * @return 2 或 3 行：品牌行 + 参考分行（+ 技法行，取不到技法时没有）
     */
    fun buildLines(total: Int?, grade: String?, technique: String?): List<String> {
        val lines = ArrayList<String>(3)
        lines.add(BRAND)
        lines.add(formatScoreLine(total, grade))
        val tech = shortenTechnique(technique)
        if (tech != null) lines.add(tech)
        return lines
    }

    // ── 纯 JVM：尺寸 ──────────────────────────────────────────────────────────

    /**
     * 按图片尺寸算水印排版。
     *
     * 关键约束（测试逐条钉住）：
     * 1. `blockHeight ≤ 短边 × 4%`：三行时字号按 1.0 / 0.82 / 0.66 分配，
     *    行高按 [LINE_HEIGHT_FACTOR] 折算后**刚好**填满 4%，不会超；
     * 2. `blockWidth ≤ 图片宽 - 2 × marginX`：`maxTextWidth` 是反推出来的，
     *    所以横向也不会溢出；
     * 3. 所有比例都取自**短边**，因此 4:3 / 16:9 / 竖构图三种形状
     *    水印在画面里的视觉占比一致，不会被裁。
     */
    fun measure(imageWidth: Int, imageHeight: Int, lineCount: Int): WatermarkLayout {
        val w = if (imageWidth > 0) imageWidth else 1
        val h = if (imageHeight > 0) imageHeight else 1
        val shortSide = minOf(w, h).toFloat()

        val marginX = shortSide * MARGIN_X_RATIO
        val marginY = shortSide * MARGIN_Y_RATIO
        val paddingX = shortSide * PADDING_X_RATIO
        var paddingY = shortSide * PADDING_Y_RATIO
        val maxBlockHeight = shortSide * MAX_BLOCK_HEIGHT_RATIO
        val innerHeight = (maxBlockHeight - paddingY * 2f).coerceAtLeast(MIN_LINE_SIZE)

        val count = lineCount.coerceIn(1, 3)
        val weights = floatArrayOf(W_BRAND, W_SCORE, W_TECH)
        var weightSum = 0f
        for (i in 0 until count) weightSum += weights[i]

        // 行高不是字号本身，而是 ascent..descent（约 1.16 × textSize），
        // 这里先把它算进分母，保证「按权重分完」之后总高仍然落在 innerHeight 内。
        val unit = innerHeight / (weightSum * LINE_HEIGHT_FACTOR + GAP_RATIO * (count - 1))

        var brandSize = (unit * weights[0]).coerceAtLeast(MIN_LINE_SIZE)
        var scoreSize = if (count >= 2) (unit * weights[1]).coerceAtLeast(MIN_LINE_SIZE) else 0f
        var techSize = if (count >= 3) (unit * weights[2]).coerceAtLeast(MIN_LINE_SIZE) else 0f
        var lineGap = unit * GAP_RATIO

        val rawHeight = paddingY * 2f +
            (brandSize + scoreSize + techSize) * LINE_HEIGHT_FACTOR +
            lineGap * (count - 1)
        // 极小图上 MIN_LINE_SIZE 会把总高顶出 4%；这里整体等比回缩，
        // 保证「≤ 短边 4%」在任何尺寸下都成立（宁可字小，也不能越界）。
        val scale = if (rawHeight > maxBlockHeight && rawHeight > 0f) {
            maxBlockHeight / rawHeight
        } else {
            1f
        }
        paddingY *= scale
        brandSize *= scale
        scoreSize *= scale
        techSize *= scale
        lineGap *= scale

        val blockHeight = paddingY * 2f +
            (brandSize + scoreSize + techSize) * LINE_HEIGHT_FACTOR +
            lineGap * (count - 1)

        val logoSize = brandSize * LOGO_SIZE_RATIO
        val logoGap = brandSize * LOGO_GAP_RATIO
        // 反推可用文字宽度：剩下的空间全部给文字，因此整块宽度恒等于 w - 2*marginX
        val maxTextWidth = (w - marginX * 2f - paddingX * 2f - logoSize - logoGap)
            .coerceAtLeast(MIN_LINE_SIZE * 4f)

        return WatermarkLayout(
            marginX = marginX,
            marginY = marginY,
            paddingX = paddingX,
            paddingY = paddingY,
            lineGap = lineGap,
            brandSize = brandSize,
            scoreSize = scoreSize,
            techSize = techSize,
            logoSize = logoSize,
            logoGap = logoGap,
            maxTextWidth = maxTextWidth,
            blockHeight = blockHeight
        )
    }

    /**
     * 文字太宽时按比例缩字号。
     *
     * 只有缩没有放：字号上限由 [measure] 的 4% 规则定死，这个函数只负责
     * 「极端长文案不要被裁掉」，不能反过来把水印撑大。
     */
    fun shrinkToWidth(size: Float, maxWidth: Float, measuredWidth: Float): Float {
        if (maxWidth <= 0f || measuredWidth <= 0f || measuredWidth <= maxWidth) return size
        return (size * (maxWidth / measuredWidth)).coerceAtLeast(MIN_LINE_SIZE)
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────

    /**
     * 给 [src] 烧水印，结果写到 [dst]。
     *
     * **不修改 [src]**：调用点的 `source` 是 CameraX 落盘的临时文件，
     * 成功路径上本来就要被 `delete()` 掉，语义上是临时的。
     *
     * 先落一个 `.tmp` 再 rename，避免写一半失败时在相册里留下一个半截文件。
     * 解码按 1 → 2 → 4 → 8 的 `inSampleSize` 重试兜 OOM：老机器上 4000px 的
     * ARGB_8888 是 48MB，宁可降一档分辨率也不能崩在保存这一步。
     *
     * @return 成功 true；任何一步失败都返回 false，调用方应当**原样保存原图**
     */
    fun stamp(src: File, dst: File, mark: Mark): Boolean =
        stamp(src, dst, mark.total, mark.grade, mark.technique)

    /**
     * @param tip 技法简称（[shortenTechnique] 的产物）。null → 只烧两行
     */
    fun stamp(src: File, dst: File, total: Int?, grade: String?, tip: String?): Boolean {
        if (!src.exists() || src.length() <= 0L) return false
        val parent = dst.absoluteFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val lines = buildLines(total, grade, tip)
        var sample = 1
        while (sample <= MAX_SAMPLE) {
            try {
                val bitmap = BitmapFactory.decodeFile(
                    src.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return false
                try {
                    val canvas = Canvas(bitmap)
                    draw(canvas, bitmap.width, bitmap.height, lines)
                    return writeJpeg(bitmap, dst)
                } finally {
                    bitmap.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                // 换更大的采样率重来一次
                sample *= 2
            } catch (t: Throwable) {
                return false
            }
        }
        return false
    }

    /** 把水印画到 [canvas] 右下角。行数由 [lines] 决定（2 或 3 行）。 */
    private fun draw(canvas: Canvas, width: Int, height: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        val layout = measure(width, height, lines.size)

        // 先定字号：品牌行加粗，三行按 measure 的权重递减
        val sizes = FloatArray(lines.size)
        sizes[0] = layout.brandSize
        if (lines.size >= 2) sizes[1] = layout.scoreSize
        if (lines.size >= 3) sizes[2] = layout.techSize

        // 长文案按可用宽度收一次，保证不被裁
        for (i in lines.indices) {
            val probe = textPaint(sizes[i], i == 0)
            sizes[i] = shrinkToWidth(sizes[i], layout.maxTextWidth, probe.measureText(lines[i]))
        }

        // 用真实字体度量重算总高（shrink 只会让它更小）
        val paints = Array(lines.size) { i -> textPaint(sizes[i], i == 0) }
        var contentHeight = 0f
        for (p in paints) contentHeight += p.descent() - p.ascent()
        contentHeight += layout.lineGap * (lines.size - 1)
        val blockHeight = contentHeight + layout.paddingY * 2f

        val top = (height - layout.marginY - blockHeight).coerceAtLeast(0f)
        val rightEdge = width - layout.marginX - layout.paddingX

        var cursorY = top + layout.paddingY
        for (i in lines.indices) {
            val paint = paints[i]
            val fm = paint.fontMetrics
            val lineHeight = fm.descent - fm.ascent
            val baseline = cursorY + lineHeight / 2f - (fm.ascent + fm.descent) / 2f

            // 先描边再填充：描边是暗色的，压在亮底（雪地/天空）上给白字一圈轮廓
            canvas.drawText(lines[i], rightEdge, baseline, strokePaint(sizes[i], i == 0))
            canvas.drawText(lines[i], rightEdge, baseline, paint)

            if (i == 0) {
                val logoCenterX = rightEdge - paint.measureText(lines[i]) -
                    layout.logoGap - layout.logoSize / 2f
                val logoCenterY = baseline + (fm.ascent + fm.descent) / 2f
                drawLogo(canvas, logoCenterX, logoCenterY, layout.logoSize)
            }
            cursorY += lineHeight + layout.lineGap
        }
    }

    /**
     * 小 logo：一个主色圆环（外圈主色 + 内圈底色），像镜头光圈。
     *
     * 用 canvas 画而不是贴 drawable，是因为 [stamp] 是纯文件到文件的函数、
     * 拿不到 `Context`，也就拿不到资源；而且这样在任意分辨率下都不会糊。
     */
    private fun drawLogo(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f
        paint.color = Color.argb(205, 0x7F, 0xE3, 0xC0)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.argb(225, 0x0E, 0x11, 0x13)
        canvas.drawCircle(cx, cy, radius * 0.42f, paint)
    }

    private fun textPaint(size: Float, bold: Boolean): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = size
        paint.color = Color.argb(TEXT_ALPHA, 255, 255, 255)
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // 淡阴影：描边管边界，阴影管整块的"浮起来"，两者叠起来亮底暗底都读得清
        paint.setShadowLayer(size * 0.28f, 0f, size * 0.06f, Color.argb(170, 0, 0, 0))
        return paint
    }

    private fun strokePaint(size: Float, bold: Boolean): Paint {
        val paint = textPaint(size, bold)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.075f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.argb(SHADOW_ALPHA, 0, 0, 0)
        // 描边层不要再投一次阴影，否则交界处会发灰
        paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        return paint
    }

    /** 先写 .tmp 再 rename，写坏的文件不会顶掉目标 */
    private fun writeJpeg(bitmap: Bitmap, dst: File): Boolean {
        val tmp = File(dst.absoluteFile.parentFile, dst.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
                out.flush()
            }
            if (dst.exists()) dst.delete()
            if (tmp.renameTo(dst)) return true
            tmp.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst.length() > 0L
        } catch (t: Throwable) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────

    private const val MARGIN_X_RATIO = 0.022f
    private const val MARGIN_Y_RATIO = 0.016f
    private const val PADDING_X_RATIO = 0.012f
    private const val PADDING_Y_RATIO = 0.005f
    private const val GAP_RATIO = 0.18f

    /** 行高 / 字号。默认 sans 的 ascent..descent 约 1.16，取值略保守 */
    private const val LINE_HEIGHT_FACTOR = 1.16f

    private const val W_BRAND = 1.0f
    private const val W_SCORE = 0.82f
    private const val W_TECH = 0.66f

    private const val LOGO_SIZE_RATIO = 0.86f
    private const val LOGO_GAP_RATIO = 0.30f

    /** 字号下限（px）。极小图上也不会算出 0 导致文字消失 */
    private const val MIN_LINE_SIZE = 1f

    /** 技法简称的最大字符数（含省略号） */
    private const val MAX_TECHNIQUE_CHARS = 8

    /** 文字填充不透明度。232/255 ≈ 91%，不是纯白 */
    private const val TEXT_ALPHA = 232

    /** 描边不透明度 */
    private const val SHADOW_ALPHA = 150

    /** 重编码质量。92 与 CameraX 默认 JPEG 质量同档，肉眼看不出二次压缩 */
    private const val JPEG_QUALITY = 92

    /** OOM 兜底的最大降采样倍数 */
    private const val MAX_SAMPLE = 8
}
