package com.shottuner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.shottuner.app.R
import com.shottuner.app.growth.MissionThresholds
import com.shottuner.app.model.CompositionHint
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 取景器上的构图引导层。
 *
 * 三个视觉元素对应三条构图原则：
 * - 三分线：把画面切成九宫格，交点是最容易放稳主体的位置；
 * - 水平参考线 + 实际地平线：一眼看出歪了几度，不用读文字；
 * - 主体标记 + 目标交点：把「该往哪挪」变成两个点对齐的动作。
 *
 * 坐标系说明：PreviewView 用 FILL_CENTER 缩放，画面会被裁切，
 * 所以这里的归一化坐标与画面真实位置的对应是近似的。构图提示允许这个误差，
 * 因为它的用途是「引导用户移动手机」，而不是精确测量。
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 色值统一走 colors.xml（v1.7.1 P2-a）：自定义 View 拿不到 @color 的编译期展开，
    // 但运行时用 ContextCompat 读同一份资源，调色板仍然只有一处。
    private val accent = ContextCompat.getColor(context, R.color.st_accent)
    private val warn = ContextCompat.getColor(context, R.color.st_warn)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(89, 255, 255, 255)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }

    /**
     * 网格线的"底描边"（v1.7 P0 连带修复）。
     *
     * 原来只有一条白 18% 的 1px 线：叠在亮度 0.9 的天空上时
     * (0.9×0.82 + 0.18) = 0.918，与背景只差 0.018 —— 等于没有网格。
     * 这是功能性失效，不是审美问题：用户以为开了网格，实际看不见线。
     *
     * 双描边（黑底 + 白面）是相机网格的标准做法，因为它对**任何**背景
     * 都保证约 ±40% 的局部反差：亮背景靠黑底显形，暗背景靠白面显形。
     * 代价是每帧多 12 条 drawLine，可忽略。
     */
    private val gridShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = dp(1.5f)
    }

    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = warn
        strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
    }

    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val subjectDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 127, 227, 192)
        style = Paint.Style.FILL
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }

    /** 俯仰角刻度：主刻度线 / 指针用 accent，当前角度标记用 warn */
    private val pitchScalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 127, 227, 192)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }

    private val pitchTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = warn
        strokeWidth = dp(2.2f)
        style = Paint.Style.STROKE
    }

    private val pitchLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 127, 227, 192)
        // TypedValue.applyDimension 是 SP→px 的正确算法（会跟随用户字体缩放）。
        // 旧写法用 resources.displayMetrics 里的字体缩放系数直接乘，那个字段已废弃（v1.7.1 P2-g）
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.RIGHT
    }

    /**
     * G1 任务环：未到位是灰圈，越接近达标越绿。
     *
     * 绿意由 `MissionEngine.ringProgress()` **连续**喂进来（[setMissionProgress]），
     * 到点之前的每一帧都在变 —— 这是"再挪一点点就到了"的唯一线索。
     * 到点突变会让用户以为自己没动对，然后反过来更用力地晃。
     */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }

    /** 环底的黑描边：亮背景（天空 / 白墙）上的环必须靠它显形，与 gridShadowPaint 同理 */
    private val ringShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
    }

    /** 环心实心点：颜色之外的第二个"绿意"载体（不单独靠颜色承载信息） */
    private val ringDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }

    /** 当前任务类型；[TASK_NONE] = 无任务 */
    private var taskKind: Int = TASK_NONE
    /** 目标交点的归一化坐标，由 [setMission] 写入 */
    private var targetNx = 0f
    private var targetNy = 0f
    /** 主体位置，由 [setMissionProgress] 每 tick 写入；任务态画引导连线用 */
    private var subjectNx = 0.5f
    private var subjectNy = 0.5f
    /** 0 = 全灰，1 = 全绿 */
    private var ringProgress = 0f
    /** >= 0 表示正在播到位动画（`elapsedRealtime` 基准） */
    private var achievedAtMs = -1L
    /** 重力 roll（度）。没有地平线时作为水平判定的兜底源 */
    private var rollDeg: Float? = null

    private var hint: CompositionHint? = null
    /** null 表示还没收到姿态数据；此时不画俯仰刻度，避免出现一根假的 0° 线 */
    private var pitchDeg: Float? = null

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showGuides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 俯仰刻度（v1.9 档位预设用）。
     *
     * 沉浸档关掉它：刻度是"专业用户调机位"时才看得懂的东西，默认档位下
     * 它只是取景器右侧一段没人读的刻度线，属于典型的"信息过载"。
     */
    var showPitchGauge: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 主体圈 + 目标交点（v1.9 档位预设用）。
     *
     * 沉浸档关掉：这两个圈是"往哪挪"的几何提示，和相机 App 的取景器语义重叠，
     * 而且会在人脸/主体上压出两个圆，抢画面。专业档放开。
     */
    var showSubjectMarks: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun submit(hint: CompositionHint?) {
        this.hint = hint
        invalidate()
    }

    /**
     * P0-2：送入手机会话的俯仰角（0° = 平放，90° = 竖持）。
     * 与 submit(hint) 完全解耦：即使构图分析还没出结果，刻度也能先跟手。
     */
    fun submitPitch(pitchDeg: Float) {
        if (!pitchDeg.isFinite()) return
        this.pitchDeg = pitchDeg
        invalidate()
    }

    // ---------------- G1 任务卡：可视化部分 ----------------

    /**
     * 派发 / 换一条任务。
     *
     * @param kind [TASK_COMPOSITION] / [TASK_LEVEL] / [TASK_HEADROOM] / [TASK_NONE]
     * @param targetX 目标交点归一化 x（构图任务用它画环与高亮交点）
     * @param targetY 目标交点归一化 y
     */
    fun setMission(kind: Int, targetX: Float, targetY: Float) {
        taskKind = kind
        targetNx = if (targetX.isFinite()) targetX.coerceIn(0f, 1f) else 0f
        targetNy = if (targetY.isFinite()) targetY.coerceIn(0f, 1f) else 0f
        achievedAtMs = -1L
        ringProgress = 0f
        invalidate()
    }

    /**
     * 每 tick（400ms）更新环的绿意。
     *
     * @param progress 0..1，来自 `MissionEngine.ringProgress()`。**不是到点突变** ——
     *                 环必须跟着用户的手连续地变色，否则" Near Miss "没法被感知。
     */
    fun setMissionProgress(subjectX: Float, subjectY: Float, progress: Float) {
        if (subjectX.isFinite()) subjectNx = subjectX.coerceIn(0f, 1f)
        if (subjectY.isFinite()) subjectNy = subjectY.coerceIn(0f, 1f)
        ringProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        invalidate()
    }

    /** 到位：起播 160ms 的变绿动画（[RING_ANIM_MS]）。 */
    fun markAchieved() {
        achievedAtMs = SystemClock.elapsedRealtime()
        invalidate()
    }

    /** 收任务：清空任务态绘制。UI 侧由 `MainActivity` 在回 IDLE 时调用。 */
    fun clearMission() {
        taskKind = TASK_NONE
        achievedAtMs = -1L
        ringProgress = 0f
        invalidate()
    }

    /**
     * 重力 roll（度）。
     *
     * 只用于一件事：`horizonDetected == false` 时，水平参考线的"端平了没"要靠它判。
     * 改之前这里写死 `horizonDetected && |tilt| <= 1.8`，于是对着一面没有地平线的墙
     * 拍照时，参考线**永远是白的** —— 用户端平了也得不到任何确认（规格 §3.2）。
     */
    fun submitRoll(rollDeg: Float) {
        this.rollDeg = if (rollDeg.isFinite()) rollDeg else null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        if (showGrid) drawThirds(canvas, w, h)
        if (!showGuides) return

        if (showPitchGauge) drawPitchGauge(canvas, w, h)

        val hint = hint ?: return

        // 水平参考：画面被判断为「平」的位置
        //
        // 判定源改成「有地平线用地平线，没有就用重力 roll」（G1 §3.2）：
        // 改前写死 `horizonDetected && |tilt| <= 1.8`，纯墙面这类没有结构线的画面
        // 会永远拿到 `levelOk == false`，参考线一直是白的 —— 用户明明端平了，
        // App 却什么都不说，比没有这条线更糟。
        // 阈值同步到 [LEVEL_OK_DEG]（2.5°）：与 MissionThresholds.LEVEL_ENTER_DEG 同值，
        // "绿了就代表任务能达标"，两处必须一致，否则会出现"线绿了但环没绿"。
        val refY = if (hint.horizonDetected) hint.horizonY * h else h * 0.5f
        val theta = if (hint.horizonDetected) hint.tiltDeg else (rollDeg ?: Float.NaN)
        val levelOk = theta.isFinite() && abs(theta) <= LEVEL_OK_DEG
        levelPaint.color = if (levelOk) accent else Color.argb(110, 255, 255, 255)
        val arm = w * 0.09f
        canvas.drawLine(w * 0.5f - arm * 2.2f, refY, w * 0.5f - arm, refY, levelPaint)
        canvas.drawLine(w * 0.5f + arm, refY, w * 0.5f + arm * 2.2f, refY, levelPaint)

        // 实际地平线：倾斜时用橙色虚线画出来，和绿色参考线对比
        if (hint.horizonDetected && !levelOk) {
            val px = w * 0.5f
            val py = hint.horizonY * h
            canvas.save()
            canvas.rotate(hint.tiltDeg, px, py)
            canvas.drawLine(px - w * 0.46f, py, px + w * 0.46f, py, horizonPaint)
            canvas.restore()
        }

        // 任务态必须强制显示主体/目标标记（G1 T2）：沉浸档 `showSubjectMarks == false`
        // 会把目标环一起藏掉，而任务环是整个 Aha 的判定载体 —— 没有它，用户不知道
        // 「往哪儿挪」和「挪到了没」。任务期间不再由档位开关决定。
        if ((showSubjectMarks || taskKind != TASK_NONE) && hint.subjectConfidence > 0.30f) {
            val sx = hint.subjectX * w
            val sy = hint.subjectY * h
            canvas.drawCircle(sx, sy, dp(3.2f), subjectDotPaint)
            canvas.drawCircle(sx, sy, dp(13f), subjectPaint)

            if (hint.suggestMove) {
                val tx = hint.targetX * w
                val ty = hint.targetY * h
                canvas.drawCircle(tx, ty, dp(17f), targetPaint)
                // 构图任务的连线交给 drawMissionRing：它用任务记下的 target，
                // 保证"用户看到的圈"和"判定用的圈"是同一个坐标源。
                if (taskKind != TASK_COMPOSITION) canvas.drawLine(sx, sy, tx, ty, targetPaint)
            }
        }
        if (taskKind == TASK_COMPOSITION) drawMissionRing(canvas, w, h)
    }

    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        // 先画一遍黑底再加白面：亮背景靠黑底显形，暗背景靠白面显形（见 gridShadowPaint）
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, gridShadowPaint)
            canvas.drawLine(0f, y, w, y, gridShadowPaint)
        }
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        val r = dp(7f)
        val rTarget = dp(10f)
        val highlightTarget = taskKind == TASK_COMPOSITION
        for (i in 1..2) {
            for (j in 1..2) {
                // 交点同样双描边，否则四条线都看得见、只有交点"消失"
                val cx = w * j / 3f
                val cy = h * i / 3f
                val isTarget = highlightTarget && isTargetIntersection(j / 3f, i / 3f)
                val rr = if (isTarget) rTarget else r
                canvas.drawCircle(cx, cy, rr, gridShadowPaint)
                canvas.drawCircle(cx, cy, rr, crossPaint)
                // 目标交点再加一圈外环（10dp + 3dp）：只靠"粗一点"在取景画面上不够找，
                // 这是"是这一个、不是那三个"的快速识别信号。
                if (isTarget) canvas.drawCircle(cx, cy, rTarget + dp(3f), crossPaint)
            }
        }
    }

    /** 该交点是不是当前任务的落点。留 0.02 容差，避免浮点那一位的误差导致不高亮。 */
    private fun isTargetIntersection(nx: Float, ny: Float): Boolean =
        targetNx.isFinite() && targetNy.isFinite() &&
            abs(nx - targetNx) <= 0.02f && abs(ny - targetNy) <= 0.02f

    /**
     * 任务环： adherence 程度的可视化。
     *
     * 半径取 `MissionThresholds.RING_RADIUS * min(w, h)`，与 `TaskPicker.distToTarget()`
     * 的"画面短边为单位"口径一致 —— **判定用的圈就是画出来的这个圈**（规格 §4.1），
     * 这里绝不能再算一套自己的半径，否则"点进圈了但没达标"。
     *
     * 到位动画 160ms：颜色（灰→绿）+ 线宽（1.5→3dp）+ 中心点（无→实心）
     * 三者同时变化，再叠一次 6dp 的胀缩。刻意不用 `ValueAnimator` —— View 保持
     * 无动画对象状态，`invalidate` 完全由数据驱动。
     */
    private fun drawMissionRing(canvas: Canvas, w: Float, h: Float) {
        val tx = targetNx * w
        val ty = targetNy * h
        if (!tx.isFinite() || !ty.isFinite()) return

        val now = SystemClock.elapsedRealtime()
        val anim = if (achievedAtMs < 0L) {
            0f
        } else {
            ((now - achievedAtMs).toFloat() / RING_ANIM_MS).coerceIn(0f, 1f)
        }
        // DecelerateInterpolator 的等价手写形式
        val ease = 1f - (1f - anim) * (1f - anim) * (1f - anim)
        val p = maxOf(ringProgress, ease)                  // 到位动画期间强制拉满

        val shortSide = min(w, h)
        val r0 = MissionThresholds.RING_RADIUS * shortSide

        ringPaint.color = lerpArgb(IDLE_RING_COLOR, accent, p)
        ringPaint.strokeWidth = dp(1.5f + 1.5f * p)
        ringShadowPaint.strokeWidth = ringPaint.strokeWidth + dp(1.5f)

        // 主体 → 环心的连线：任务态下即便 suggestMove 为假也要有，否则用户不知道往哪挪
        if (shortSide > 0f) {
            canvas.drawLine(subjectNx * w, subjectNy * h, tx, ty, targetPaint)
        }

        val grow = if (achievedAtMs >= 0L) {
            (dp(6f) * sin(Math.PI * anim.toDouble())).toFloat()
        } else {
            0f
        }
        canvas.drawCircle(tx, ty, r0 + grow, ringShadowPaint)
        canvas.drawCircle(tx, ty, r0 + grow, ringPaint)

        if (p > 0.01f) {
            ringDotPaint.alpha = (255 * p).toInt()
            canvas.drawCircle(tx, ty, dp(4f), ringDotPaint)
        }
        // 动画期间持续重绘；动画结束后停（不做一个永远不会停的自刷循环）
        if (achievedAtMs >= 0L && anim < 1f) postInvalidateOnAnimation()
    }

    /**
     * ARGB 逐通道插值。
     *
     * 手写 10 行而不是引 `androidx.core.graphics.ColorUtils`：虽然 androidx.core 已在依赖里，
     * 但手写零风险，也不增加 R8 需要保留的类。
     */
    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * t).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * t).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    /**
     * 右侧俯仰角刻度：0° 在底部（腰以下），45° 在中点，90° 在顶部（竖持）。
     * 橙色短横线随 pitch 上下移动；左下角的小指针以底端为轴旋转，
     * 让「手机现在抬到多少度」一眼可见，不用读数字。
     */
    private fun drawPitchGauge(canvas: Canvas, w: Float, h: Float) {
        val pitch = pitchDeg ?: return
        val x = w - dp(22f)
        val yBottom = h * 0.70f
        val yTop = h * 0.30f
        val span = yBottom - yTop
        if (span <= dp(20f)) return

        fun yFor(deg: Float): Float {
            val d = deg.coerceIn(0f, 90f)
            return yBottom - d / 90f * span
        }

        canvas.drawLine(x, yTop, x, yBottom, pitchScalePaint)

        val marks = floatArrayOf(0f, 45f, 90f)
        val labels = arrayOf("腰以下", "45°", "90°")
        for (i in marks.indices) {
            val y = yFor(marks[i])
            canvas.drawLine(x - dp(5f), y, x + dp(5f), y, pitchScalePaint)
            canvas.drawText(labels[i], x - dp(10f), y + dp(3.5f), pitchLabelPaint)
        }

        // 当前角度：橙色标记横线
        val y = yFor(pitch)
        canvas.drawLine(x - dp(9f), y, x + dp(9f), y, pitchTickPaint)

        // 跟手指针：以刻度底部左侧为轴，0° 水平、90° 竖直向上
        val pivotX = x - dp(52f)
        val pivotY = yBottom
        canvas.save()
        canvas.rotate(pitch, pivotX, pivotY)
        canvas.drawLine(pivotX, pivotY, pivotX - dp(26f), pivotY, pitchScalePaint)
        canvas.restore()
        canvas.drawCircle(pivotX, pivotY, dp(2f), pitchTickPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        const val TASK_NONE = 0
        const val TASK_COMPOSITION = 1
        const val TASK_LEVEL = 2

        /**
         * headroom 任务（「头顶多留一点空间」）。
         *
         * 规格 T2 只列了前三个常量 —— headroom 需要一个独立取值，因为它是"有任务但
         * 不画环"的状态：判定量是 `subjectTop`，画面上没有一个落点可以画圈。
         * 若把它并进 TASK_NONE，任务态强制显示主体标记的逻辑就失效了
         *（沉浸档下这条任务会变成"只出文字，画面上什么都没有"）。
         */
        const val TASK_HEADROOM = 3

        /** 水平参考线转绿的阈值。必须与 `MissionThresholds.LEVEL_ENTER_DEG` 一致 */
        const val LEVEL_OK_DEG = 2.5f

        /** 到位动画时长 */
        const val RING_ANIM_MS = 160L

        /** 未到位时环的颜色（与现有 `targetPaint` 同色的白 59%） */
        private val IDLE_RING_COLOR = Color.argb(150, 255, 255, 255)
    }
}
