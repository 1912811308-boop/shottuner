package com.shottuner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.shottuner.app.R

/**
 * 亮度直方图。每根像素列取该区间内的最大值，保证极窄的尖峰也能被看见。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var histogram: FloatArray = FloatArray(256)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ContextCompat.getColor(context, R.color.st_hist_stroke)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.st_hairline)
    }
    private val path = Path()

    fun setHistogram(values: FloatArray) {
        if (values.size != 256) return
        histogram = values
        invalidate()
    }

    fun clear() {
        histogram = FloatArray(256)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                ContextCompat.getColor(context, R.color.st_hist_top),
                ContextCompat.getColor(context, R.color.st_hist_mid),
                ContextCompat.getColor(context, R.color.st_hist_bottom)
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 竖向参考线：1/4、1/2、3/4 亮度
        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, guidePaint)
        }

        var maxVal = 0f
        for (v in histogram) if (v > maxVal) maxVal = v
        if (maxVal <= 0f) return

        val columns = w.toInt().coerceAtLeast(32)
        path.reset()
        path.moveTo(0f, h)

        for (col in 0 until columns) {
            val from = col * 256 / columns
            val to = ((col + 1) * 256 / columns).coerceAtMost(256)
            var v = 0f
            for (i in from until to) if (histogram[i] > v) v = histogram[i]
            val normalized = (v / maxVal).coerceAtMost(1f)
            // 用开方压缩动态范围，暗部细节不会被高光尖峰吞掉
            val scaled = kotlin.math.sqrt(normalized) * (h * 0.95f)
            val finalY = (h - scaled.coerceAtMost(h * 0.95f)).coerceAtLeast(h - h * 0.95f)
            path.lineTo(col.toFloat(), finalY)
        }

        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, fillPaint)

        // 叠一层描边，让曲线更清晰
        val strokePath = Path()
        strokePath.moveTo(0f, h)
        for (col in 0 until columns) {
            val from = col * 256 / columns
            val to = ((col + 1) * 256 / columns).coerceAtMost(256)
            var v = 0f
            for (i in from until to) if (histogram[i] > v) v = histogram[i]
            val normalized = (v / maxVal).coerceAtMost(1f)
            val scaled = kotlin.math.sqrt(normalized) * (h * 0.95f)
            strokePath.lineTo(col.toFloat(), h - scaled)
        }
        canvas.drawPath(strokePath, linePaint)
    }
}
