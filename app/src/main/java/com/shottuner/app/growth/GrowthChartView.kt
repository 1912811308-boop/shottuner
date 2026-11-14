package com.shottuner.app.growth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.shottuner.app.R

/**
 * 参考分折线图（`Canvas` 自绘，**零第三方依赖**）。
 *
 * ## 为什么自己画
 * 本仓硬规则是零第三方依赖。而这张图的全部需求就是「网格 + 一两条折线 + 几个圆点」，
 * `Canvas` 五十行就够；引一个图表库换来的是一整棵依赖树的审计成本，
 * 还会和已经调好的 R8 规则、40MB 包体预算打架。iOS 侧同理（`GrowthChart.swift` 用 `Path`）。
 *
 * ## 三条不可违反的绘制规则
 *  1. **没有可画的点时什么都不画**：不画网格、不画边框、不画任何 0 横线。
 *     写这一条的起因很具体 —— 一条贴着底边的 0 分横线会被读成
 *     「连续 30 天都是 0 分」，而事实是连一个完整参考分都没测到。
 *  2. **折线只在连续已测点之间连**：断点由 [GrowthChartMath.splitRuns] 切好，
 *     本类不做任何插值或补点。
 *  3. **Y 轴固定 0..100**：口径写死在 [GrowthChartMath.MAX_TOTAL]，
 *     不按数据自适应 —— 自适应会让 78→80 的进步爬满整屏，那是视觉欺骗。
 *
 * ## 线程
 * 只在主线程被 `invalidate()` / `onDraw` 触碰。数据由 [setGeometry] 从主线程灌入
 * （`MainActivity.applyGrowth` 跑在 `runOnUiThread` 里），因此这里不需要任何同步。
 */
class GrowthChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前几何。默认 [GrowthChartGeometry.EMPTY]，即「什么都不画」。 */
    private var geometry: GrowthChartGeometry = GrowthChartGeometry.EMPTY

    private val density: Float = resources.displayMetrics.density

    /** 网格（也是边框）：必须能看见但不能抢戏 —— 它是参照物，不是数据。 */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(GRID_STROKE_DP)
        color = ContextCompat.getColor(context, R.color.st_hairline)
    }

    /** 折线：主色，2dp，圆头圆角，避免折点处出现尖刺。 */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.st_accent)
    }

    /** 圆点：主色实心。只有一个点时全靠它让用户看到「图上有东西」。 */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.st_accent)
    }

    /** 复用一条 `Path`，避免 `onDraw` 里按段新建对象。 */
    private val linePath = Path()

    /**
     * 灌入新几何并重绘。
     *
     * @param value 由 [GrowthChartMath.layout] 算好的几何
     */
    fun setGeometry(value: GrowthChartGeometry) {
        geometry = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // ★ 规则 1：一个可画的点都没有 → 连网格都不画（空状态文案由 XML 里那个
        //   TextView 负责，见 MainActivity.applyGrowth）。
        if (!geometry.hasMeasurable) return

        val left = paddingLeft + dp(PLOT_INSET_X_DP)
        val right = width - paddingRight - dp(PLOT_INSET_X_DP)
        val top = paddingTop + dp(PLOT_INSET_Y_DP)
        val bottom = height - paddingBottom - dp(PLOT_INSET_Y_DP)
        if (right <= left || bottom <= top) return

        drawGrid(canvas, left, top, right, bottom)
        drawSegments(canvas, left, top, right, bottom)
        drawDots(canvas, left, top, right, bottom)
    }

    /** 网格：一条外框 + 三条内部横线（25 / 50 / 75 分）。0 与 100 由外框本身充当。 */
    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        canvas.drawRect(left, top, right, bottom, gridPaint)
        for (level in INNER_GRID_LEVELS) {
            val y = top + (bottom - top) * (1f - level.toFloat() / GrowthChartMath.MAX_TOTAL)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
    }

    /** 每一段连续已测点画一条折线。段与段之间**不连接**（断点就是断点）。 */
    private fun drawSegments(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        for (segment in geometry.segments) {
            val first = segment.firstOrNull() ?: continue
            linePath.reset()
            linePath.moveTo(xOf(first, left, right), yOf(first, top, bottom))
            for (index in 1 until segment.size) {
                val point = segment[index]
                linePath.lineTo(xOf(point, left, right), yOf(point, top, bottom))
            }
            canvas.drawPath(linePath, linePaint)
        }
    }

    /** 每个已测点一个小圆点。 */
    private fun drawDots(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val radius = dp(DOT_RADIUS_DP)
        for (point in geometry.dots) {
            canvas.drawCircle(xOf(point, left, right), yOf(point, top, bottom), radius, dotPaint)
        }
    }

    private fun xOf(point: GrowthChartPoint, left: Float, right: Float): Float =
        left + (right - left) * point.x.coerceIn(0f, 1f)

    private fun yOf(point: GrowthChartPoint, top: Float, bottom: Float): Float =
        top + (bottom - top) * point.y.coerceIn(0f, 1f)

    private fun dp(value: Float): Float = value * density

    private companion object {
        /** 折线粗细（dp）。 */
        const val LINE_STROKE_DP = 2f

        /** 网格/边框粗细（dp）。 */
        const val GRID_STROKE_DP = 1f

        /** 圆点半径（dp）。 */
        const val DOT_RADIUS_DP = 2.5f

        /** 横向留白：让首末两点不被边框切掉一半描边。 */
        const val PLOT_INSET_X_DP = 4f

        /** 纵向留白：100 分与 0 分的点同样不该贴边。 */
        const val PLOT_INSET_Y_DP = 8f

        /** 内部横线刻度。0 与 100 由外框承担，不重复画。 */
        val INNER_GRID_LEVELS = intArrayOf(25, 50, 75)
    }
}
