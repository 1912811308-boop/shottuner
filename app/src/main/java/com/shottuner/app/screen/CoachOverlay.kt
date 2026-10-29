package com.shottuner.app.screen

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shottuner.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮教练卡：贴在系统相机上方显示建议。
 *
 * ## 视觉：v2.1 回到 1.6 的彩色风格
 *
 * 中间试过一版「纯黑玻璃 + 白字分层」的 Apple 材质，clover 对比后觉得太素、没辨识度，
 * 所以退回这版：**深灰底 + 青绿描边 + 青绿参数文字 + 橙色停**。
 * 底色 84% 够实，不需要文字投影；层次靠色相区分而不是靠亮度，所以分隔线也去掉了。
 *
 * 字号回到 1.6 原值（参数 19sp、正文 11sp），比 2.0 的 18/12 略大一点但不夸张。
 * 行数上限从 1.6 的 4/3 放宽到 8/8 —— clover 明确要「内容显示齐全」，宁可卡片长一点也不截断。
 *
 * ## 拖动（v2.0 新增，保留）
 *
 * 旧版把 `setOnTouchListener` 挂在整张卡上并在 ACTION_DOWN 就 `return true`，
 * 结果吞掉整个触摸序列——**内部「停」按钮一直点不动**；而且只改 `lp.y`，
 * 横向固定居中，等于只能上下挪。
 *
 * 现在顶部有一条独立手柄：只有手柄消费触摸，卡片其余部分不拦截，按钮正常响应；
 * x 与 y 同时可拖，夹在屏幕内，并记住上次位置。
 *
 * ## FLAG_SECURE 不能去掉
 *
 * 不加的话，这张卡会被自己的录屏采进画面，下一帧分析时把「ISO 800 · 1/30」
 * 当成场景内容，越算越偏。
 *
 * ## 横屏跟随方向（v3.1.1 新增）
 *
 * 用户的原话：「横屏拍照我的悬浮窗要跟着可以转移方向，不然看不到提示了」。
 *
 * 根因不在这个文件里，在 `AndroidManifest.xml`：
 *
 * ```xml
 * <activity android:name=".MainActivity" android:screenOrientation="portrait" />
 * ```
 *
 * Android 的**方向请求是 per-display 的**：`Activity.setRequestedOrientation()`
 * 最终走到 `WindowManagerService.setRequestedOrientation(displayId, orientation)`，
 * 锁的是**这个 display** 上的窗口方向，同一 display 上属于本 App 的窗口
 * （含 `TYPE_APPLICATION_OVERLAY` 悬浮窗）会一起被锁成 portrait。
 * 而系统相机是允许横屏的 —— 两边方向不一致，用户横持手机时提示卡侧躺在屏幕里，读不了。
 *
 * **不能靠解 MainActivity 的锁来解决**：主 App 的 UI 是竖屏设计的，那是产品决策；
 * 解了锁会把主 App 弄坏。所以悬浮窗只能自己管方向：
 *
 * - 用 `Display.getRotation()` 拿**物理**旋转（不受 App 方向锁影响），
 *   用 `Display.getRealSize()` 拿**物理**尺寸（横屏时是 2340×1080）；
 * - 用 `DisplayManager.DisplayListener` 监听旋转变化，变了就重建悬浮窗
 *   （宽度、坐标夹取、内容朝向全都要重算，改属性不够）；
 * - 卡片内容按 `rotation × 90°` 预旋转，让文字顺着用户横持手机的视角正立
 *   （推导过程见 `OverlayOrientation` 的文件头注释）。
 *
 * 竖屏路径的行为**完全没变**：方向 0 时卡片还是窗口根 View、宽度算法等价、
 * gravity 仍是「顶部居中」、拖动夹取区间一字未改。
 */
class CoachOverlay(
    private val context: Context,
    private val onStop: () -> Unit
) {

    private companion object {
        const val PREFS = "shottuner_overlay"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        const val CARD_MAX_DP = 340

        /** 卡片与屏幕边缘的最小留白 */
        const val CARD_MARGIN_DP = 24
        const val HANDLE_TOUCH_DP = 24
        const val HANDLE_BAR_W_DP = 36
        const val HANDLE_BAR_H_DP = 4
        const val DEFAULT_TOP_DP = 96

        /** 拖动夹取：上不越过状态栏，下留 120dp 给快门区 */
        const val DRAG_TOP_DP = 24
        const val DRAG_BOTTOM_RESERVE_DP = 120
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val display: Display? = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    /** 挂在窗口上的根 View：竖屏是卡片本身，横屏是旋转壳 */
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 最近一次已知的物理旋转 */
    private var lastRotation: Int = OverlayOrientation.ROTATION_0

    /** 旋转监听器：show() 时注册，hide() 时反注册（不注册会泄漏 Service） */
    private var displayListener: DisplayManager.DisplayListener? = null

    /** 最近一次 render 的文案：重建窗口后要接着显示，不能闪回占位符 */
    private var lastScene = ""
    private var lastParams = ""
    private var lastVideo = ""
    private var lastAdvice = ""
    private var lastCompose = ""
    private var lastScore = ""

    private lateinit var tvScene: TextView
    private lateinit var tvParams: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var tvCompose: TextView
    private lateinit var tvScore: TextView

    fun show() {
        if (root != null) return

        val rotation = currentRotation()
        lastRotation = rotation

        val density = context.resources.displayMetrics.density
        val real = Point()
        val shortSide = physicalShortSide(real)
        val longSide = max(real.x, real.y)

        // 卡片宽度按**物理短边**算：横竖屏算出来一样，卡片不会超出屏幕的窄边
        val cardWidth = OverlayOrientation.cardWidthPx(
            shortSidePx = shortSide,
            density = density,
            cardMaxDp = CARD_MAX_DP,
            marginDp = CARD_MARGIN_DP
        )

        // 方向请求是 per-display 的，悬浮窗的窗口坐标系在横屏时仍然是竖屏那套：
        // 宽 = 物理短边，高 = 物理长边。
        val spaceWidth = shortSide
        val spaceHeight = longSide

        val anchor = OverlayOrientation.anchorFor(rotation)
        val contentDegrees = OverlayOrientation.contentRotationDegrees(rotation)

        val card = buildCard()
        val handle = card.second

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxOffsetX = OverlayOrientation.maxOffsetXPx(spaceWidth, cardWidth)

        // 内容需要预旋转时套一层壳：窗口 Surface 的大小 = 根 View 的尺寸，
        // 而卡片转 90° 之后的视觉外框是「高 × 宽」，直接转根 View 会被裁掉。
        val rotated = contentDegrees != 0f
        val lp = WindowManager.LayoutParams(
            if (rotated) WindowManager.LayoutParams.WRAP_CONTENT else cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            when (anchor) {
                OverlayOrientation.Anchor.TOP_CENTER -> {
                    // 竖屏：与旧版完全一致（顶部居中 + 记住的位置）
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = OverlayOrientation.clampXPx(prefs.getInt(KEY_X, 0), maxOffsetX)
                    y = OverlayOrientation.clampTopYPx(
                        prefs.getInt(KEY_Y, dp(DEFAULT_TOP_DP)),
                        spaceHeight,
                        dp(DRAG_TOP_DP),
                        dp(DRAG_BOTTOM_RESERVE_DP)
                    )
                }

                OverlayOrientation.Anchor.CENTER -> {
                    // 横屏：竖屏记下的偏移在旋转后已经对不上「上边」，直接回正中。
                    // 居中是旋转不变量 —— 竖屏坐标系里的正中，映射到物理画面里仍是正中。
                    gravity = Gravity.CENTER
                    x = 0
                    y = 0
                }
            }
        }

        val rootView: View = if (rotated) {
            RotationShell(context).apply {
                contentWidthPx = cardWidth
                contentRotationDegrees = contentDegrees
                addView(
                    card.first,
                    FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
        } else {
            card.first
        }

        // 拖动：只有手柄消费触摸。x 相对"水平居中"，可正可负。
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX
                        downRawY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (!dragging &&
                            (abs(dx) > slop || abs(dy) > slop)
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            // 屏幕坐标（物理画面）→ 窗口坐标（竖屏那套）：横屏时差一个 90° 的整数倍
                            val d = OverlayOrientation.windowDelta(
                                screenDx = dx.roundToInt(),
                                screenDy = dy.roundToInt(),
                                rotation = rotation
                            )
                            val view = root
                            if (view != null) {
                                val offsetX = OverlayOrientation.maxOffsetXPx(spaceWidth, view.width)
                                lp.x = OverlayOrientation.clampXPx(startX + d.x, offsetX)
                                lp.y = if (anchor == OverlayOrientation.Anchor.TOP_CENTER) {
                                    OverlayOrientation.clampTopYPx(
                                        startY + d.y,
                                        spaceHeight,
                                        dp(DRAG_TOP_DP),
                                        dp(DRAG_BOTTOM_RESERVE_DP)
                                    )
                                } else {
                                    OverlayOrientation.clampCenterYPx(
                                        startY + d.y,
                                        spaceHeight,
                                        view.height,
                                        dp(DRAG_TOP_DP)
                                    )
                                }
                                try {
                                    wm.updateViewLayout(view, lp)
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 只在竖屏时记住位置：横屏的偏移量是相对「正中」的，
                        // 存回竖屏坐标系会污染用户原来摆好的位置
                        if (dragging && anchor == OverlayOrientation.Anchor.TOP_CENTER) {
                            prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                        }
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(rootView, lp)
            root = rootView
            params = lp
            applyText()
            ensureDisplayListener()
        } catch (_: Throwable) {
            root = null
        }
    }

    /**
     * 物理方向变了：重建悬浮窗。
     *
     * 为什么不只是改 `lp.gravity` / `rotation`：
     * 宽度、坐标夹取区间、内容朝向三样都跟着方向走，
     * 而且旋转后旧的内容高度会变（外壳尺寸要跟着重算），重建最省心也最不容易漏。
     */
    private fun rebuild() {
        val v = root ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        root = null
        params = null
        show()
    }

    fun render(
        scene: String,
        paramsText: String,
        videoText: String,
        adviceText: String,
        composeTip: String,
        scoreText: String
    ) {
        lastScene = scene
        lastParams = paramsText
        lastVideo = videoText
        lastAdvice = adviceText
        lastCompose = composeTip
        lastScore = scoreText
        if (root == null) return
        applyText()
    }

    /** 把最近一次 render 的文案刷到各个 TextView 上 */
    private fun applyText() {
        if (!::tvScene.isInitialized) return
        tvScene.text = lastScene
        tvParams.text = lastParams
        tvVideo.text = lastVideo
        tvVideo.visibility = if (lastVideo.isBlank()) View.GONE else View.VISIBLE
        tvScore.text = lastScore
        tvScore.visibility = if (lastScore.isBlank()) View.GONE else View.VISIBLE
        tvAdvice.text = lastAdvice
        tvAdvice.visibility = if (lastAdvice.isBlank()) View.GONE else View.VISIBLE
        tvCompose.text = lastCompose
        tvCompose.visibility = if (lastCompose.isBlank()) View.GONE else View.VISIBLE
    }

    fun hide() {
        unregisterDisplayListener()
        val v = root ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        root = null
    }

    // ── 方向监听 ──────────────────────────────────────────────────────────────

    private fun currentRotation(): Int {
        val d = display ?: return OverlayOrientation.ROTATION_0
        return try {
            d.rotation
        } catch (_: Throwable) {
            OverlayOrientation.ROTATION_0
        }
    }

    private fun ensureDisplayListener() {
        if (displayListener != null) return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                if (currentRotation() == lastRotation) return
                rebuild()
            }
        }
        try {
            // handler 传 null = 用调用线程（主线程）的 Looper，回调就在主线程
            displayManager.registerDisplayListener(listener, null)
            displayListener = listener
        } catch (_: Throwable) {
            displayListener = null
        }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        try {
            displayManager.unregisterDisplayListener(listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * 屏幕物理短边（像素）。
     *
     * `Display.getRealSize()` 给的是**物理**尺寸：横屏时是 2340×1080，
     * 不是竖屏坐标系的 1080×2340，也不受 App 自己的方向锁影响。
     * 短边在横竖屏下是同一个值，所以卡片宽度不会因为转屏而突变。
     *
     * API 35 把 `getRealSize()` 标了废弃，推荐换 `WindowManager.getCurrentWindowMetrics()`——
     * 但那个替代品给的是**本 App 窗口**的尺寸，正好会被 MainActivity 的 portrait 方向锁
     * 改成竖屏值，恰恰是这里要躲开的，所以继续用 `getRealSize()`。
     */
    @Suppress("DEPRECATION")
    private fun physicalShortSide(out: Point): Int {
        val d = display
        if (d != null) {
            try {
                d.getRealSize(out)
                if (out.x > 0 && out.y > 0) return min(out.x, out.y)
            } catch (_: Throwable) {
            }
        }
        // 兜底：资源里的尺寸（此时方向锁会影响它，但至少不会是 0）
        val dm = context.resources.displayMetrics
        out.set(dm.widthPixels, dm.heightPixels)
        return min(out.x, out.y)
    }

    // ── 卡片构建 ──────────────────────────────────────────────────────────────

    /**
     * 构建卡片：返回「卡片本体」与「顶部拖动手柄」。
     */
    private fun buildCard(): Pair<LinearLayout, View> {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(col(R.color.st_overlay_bg))
                setStroke(dp(1), col(R.color.st_overlay_stroke))
            }
        }

        // ── 拖动手柄（v2.0 新增）：只有这块消费触摸，卡片其余部分不拦截 ──
        val handleBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(HANDLE_BAR_H_DP / 2).toFloat()
                setColor(col(R.color.st_glass_handle))
            }
        }
        val handleView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                handleBar,
                LinearLayout.LayoutParams(dp(HANDLE_BAR_W_DP), dp(HANDLE_BAR_H_DP))
            )
        }

        // ── 顶部信息行 ──
        tvScene = TextView(context).apply {
            setTextColor(col(R.color.st_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "正在看相机画面…"
            maxLines = 1
        }

        tvScore = TextView(context).apply {
            setTextColor(col(R.color.st_warn_strong))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            text = ""
            visibility = View.GONE
        }

        val close = TextView(context).apply {
            text = "停"
            setTextColor(col(R.color.st_warn))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { onStop() }
        }

        // ── 主参数：白色粗体，19sp（1.6 原值） ──
        tvParams = TextView(context).apply {
            setTextColor(col(R.color.st_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            text = "ISO -- · -- · --K"
            maxLines = 2
        }

        // ── 录像方案：青绿粗体（1.6 原值） ──
        tvVideo = TextView(context).apply {
            setTextColor(col(R.color.st_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            text = "录像 --"
            maxLines = 2
        }

        // ── 建议：正文 11sp，行数放宽到 8，不截断 ──
        tvAdvice = TextView(context).apply {
            setTextColor(col(R.color.st_text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 8
            text = ""
        }

        // ── 构图：11sp，同样不截断 ──
        tvCompose = TextView(context).apply {
            setTextColor(col(R.color.st_text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 8
            text = ""
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tvScene, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tvScore, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
            addView(close)
        }

        card.addView(handleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(HANDLE_TOUCH_DP)
        ))
        card.addView(header)
        card.addView(tvParams, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        card.addView(tvVideo, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        card.addView(tvAdvice, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        card.addView(tvCompose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        return card to handleView
    }

    private fun col(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}

/**
 * 旋转壳。
 *
 * ## 为什么需要它
 *
 * 窗口的 Surface 大小 = 根 View 的尺寸。卡片按竖屏宽度排好版后是 `宽 × 高`，
 * 预旋转 90° 之后的**视觉外框**变成 `高 × 宽`。
 * 如果直接转根 View，内容会被转出 Surface 的边界 —— 屏幕上看到的是被切掉一块的卡片。
 *
 * 所以套一层壳：
 * - 壳的尺寸 = 旋转后的视觉外框（内容正好铺满，不裁也不溢出）；
 * - 壳里的卡片按**竖屏宽度**排版（文字断行与竖屏完全一致），再绕支点旋转。
 *
 * 支点取法（推导见 `OverlayOrientation.visual`）：让旋转后的外框左上角落在 (0,0)。
 */
private class RotationShell(context: Context) : FrameLayout(context) {

    /** 内容按多宽排版（= 卡片宽度） */
    var contentWidthPx: Int = 0

    /** 内容预旋转角度（顺时针为正） */
    var contentRotationDegrees: Float = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // 内容按卡片宽度排版：竖屏怎么排，横屏就怎么排
        child.measure(
            MeasureSpec.makeMeasureSpec(contentWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val w = contentWidthPx
        val h = child.measuredHeight
        val swap = contentRotationDegrees == 90f || contentRotationDegrees == 270f

        child.rotation = contentRotationDegrees
        child.pivotX = when (contentRotationDegrees) {
            90f -> h / 2f
            270f -> w / 2f
            else -> w / 2f
        }
        child.pivotY = when (contentRotationDegrees) {
            90f -> h / 2f
            270f -> w / 2f
            else -> h / 2f
        }

        setMeasuredDimension(if (swap) h else w, if (swap) w else h)
    }
}
