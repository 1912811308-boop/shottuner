package com.shottuner.app.screen

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.shottuner.app.sensors.LevelMath
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
 *
 * ## 横屏跟随方向 v3.1.2：display 之外再加一路信号
 *
 * v3.1.1 只信 `Display.getRotation()`，结果在 clover 的小米 14 Pro 上翻车：
 * 用系统相机横屏拍，**界面确实转成了横屏，悬浮卡却竖着不动**。
 *
 * 原因是 MIUI 的系统相机把 Activity 锁成竖屏、再在 View 层自己把画面转 90°
 * ——「界面转了」和「display 转了」是两件事。`Display.getRotation()` 停在
 * [OverlayOrientation.ROTATION_0]，`DisplayListener` 自然不回调，
 * 而 [OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY] 本身又不做任何补偿 ⇒ 什么都不发生。
 *
 * 现在朝向由**两路信号**共同决定，判定规则全部收敛到纯函数
 * [OverlayOrientation.effectiveContentDegrees]（可单测，见 `CoachOverlayOrientationTest`）：
 *
 * 1. **display 优先**：`Display.getRotation()` 不是 0，就用它 —— 它比传感器准。
 * 2. **设备角度兜底**：display 说「没转」时，看加速度计算出的横滚角；
 *    |roll| ≥ 55° 就认为设备物理上横过来了，按 ±90° 补偿。
 * 3. **400ms 去抖**：新角度要连续稳定 400ms 才重建。横竖切换是人为动作，
 *    400ms 不影响手感，但能挡住「举着手机慢慢转」时连续重建导致的闪烁。
 * 4. **500ms 轮询兜底**：`DisplayListener` 在部分 OEM 上对 overlay 窗口不回调，
 *    轮询是最后一道保险（生命周期见 [hide]）。
 * 5. **手动档位优先于以上全部**：功能页的「悬浮窗方向」可以强制竖屏 / 强制横屏，
 *    理由写在 [OverlayOrientation.ManualOrientation] 的注释里 ——
 *    真机行为因 ROM 而异，自动判定不可能覆盖全部，用户得能一条路自救。
 */
class CoachOverlay(
    private val context: Context,
    private val onStop: () -> Unit
) {

    private companion object {
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

        /**
         * 朝向变化要**稳定这么久**才重建悬浮窗（毫秒）。
         *
         * 横竖切换是人为动作，用户不会在半秒内来回切；400ms 完全不影响手感，
         * 但能挡住「举着手机慢慢转」时角度在阈值附近连续跨过导致的一串重建
         * （每重建一次都要 removeView + addView，连续十几次会看到明显闪烁）。
         */
        const val ORIENTATION_STABLE_MS = 400L

        /**
         * 朝向轮询间隔（毫秒）。
         *
         * `DisplayManager.DisplayListener` 在部分 OEM 上对 `TYPE_APPLICATION_OVERLAY`
         * 窗口不回调，而加速度计也可能被厂商省电策略掐掉 —— 轮询是最后一道保险。
         * 500ms 的代价是两次读属性（`Display.getRotation()` + 一个 volatile Float），
         * 相对于 320ms 一次的截图分析可以忽略。
         */
        const val ORIENTATION_POLL_MS = 500L

        /** 传感器节流：66ms ≈ 15Hz，够 400ms 去抖用，又不至于把主线程打满 */
        const val SENSOR_MIN_INTERVAL_MS = 66L
    }

    /**
     * 悬浮窗自己的设置（拖动位置 + 朝向兜底档位）。
     *
     * 与功能页共用一个文件名，常量统一放在 `OverlayOrientation` 里，避免两处字面量走偏。
     */
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(OverlayOrientation.PREFS_OVERLAY, Context.MODE_PRIVATE)
    }

    /**
     * 悬浮窗**窗口坐标系模型** —— 真机第一件事要确认的就是这条。
     *
     * ## ★ 2026-11-09 真机确认：真机是 [OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY]
     *
     * 用户实测（小米 14 Pro / HyperOS，横持手机用系统相机）：
     * **横屏时悬浮卡侧躺 90°、字是横的，完全没法读** —— 这正是本文件下方
     * 那段判据写的「横屏时卡片侧躺 ⇒ 窗口其实跟随了 display」。
     *
     * 原因：manifest 里 `MainActivity` 的 `portrait` 锁只约束**这个 Activity 的窗口**，
     * 并没有把整个 display 锁住。系统相机（全屏、有焦点）请求横屏后 display 真的转了，
     * 悬浮窗不属于任何 Activity，于是也跟着 display 的逻辑坐标系走。
     * 这时若还按 LOCKED_PORTRAIT 去「预旋转 R×90°」，就是在已经横过来的画面上
     * 再转一次 —— 结果就是侧躺。
     *
     * ⇒ 改成 FOLLOW_DISPLAY 之后：内容不预旋转、拖动增量不换算、
     *   卡片宽度按逻辑宽（横屏就是长边）算，四套几何一起切过去，不需要别处配合。
     *   竖屏下两种模型等价（R=0 时预旋转 0°、窗口尺寸同为短边×长边），所以零回归。
     *
     * ⚠️ 如果将来某台设备又出现侧躺（厂商可能改行为），**只改这一行**回 LOCKED_PORTRAIT。
     */
    private val windowSpaceModel = OverlayOrientation.WindowSpaceModel.FOLLOW_DISPLAY

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val display: Display? = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    /** 挂在窗口上的根 View：竖屏是卡片本身，横屏是旋转壳 */
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 最近一次已知的 display 旋转（`Display.getRotation()` 的序号） */
    private var lastRotation: Int = OverlayOrientation.ROTATION_0

    /** 旋转监听器：show() 时注册，hide() 时反注册（不注册会泄漏 Service） */
    private var displayListener: DisplayManager.DisplayListener? = null

    // ── 朝向判定状态（v3.1.2：display + 设备物理朝向双信号）────────────────────

    /** 主线程 Handler：去抖计时、轮询、传感器回调统一落在这条线程上 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 设备物理横滚角（度，-180..180，0 = 竖持）。
     *
     * ★ 必须是**没有按屏幕方向补偿过**的原始设备角 —— 只有这样才能在
     *   「界面转了但 `Display.getRotation()` 仍是 0」时把 90° 读出来。
     *   `LevelMath.solve(..., rotationDegrees = 0)` 给的就是这个口径。
     *
     * @Volatile：传感器回调与轮询都在主线程，理论上不需要；但 `SensorManager`
     *   在某些 ROM 上会从别的线程派发，加一个不会错，且成本为零。
     */
    @Volatile
    private var deviceRollDeg: Float = 0f

    /**
     * 当前**已经生效**的内容预旋转角（度）。
     *
     * null = 还没算过（首次 show 时算一次）。重建时直接用它，
     * 不重新采样 —— 否则 rebuild 那一刻传感器正好抖到阈值另一侧，会重建出一个错的朝向。
     */
    private var appliedContentDegrees: Float? = null

    /** 正在等待稳定的候选角度；null = 没有候选 */
    private var pendingContentDegrees: Float? = null

    /** 候选角度第一次出现的时刻（`SystemClock.elapsedRealtime()`） */
    private var pendingSinceElapsed: Long = 0L

    /** 加速度计/重力传感器（拿不到就是 null，静默降级成只信 display） */
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val gravitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var sensorRegistered = false
    private var lastSensorEmitAt = 0L

    /**
     * 用户改了「悬浮窗方向」档位时立刻重建 —— 这是**自救开关**，不能要求用户
     * 关掉再打开悬浮教练才生效。
     *
     * ★ 必须用字段强引用住：`SharedPreferences` 对监听器是**弱引用**，
     *   写成 `prefs.registerOnSharedPreferenceChangeListener { ... }` 传个 lambda，
     *   下一次 GC 就静默失效（表现是「设置里改了没反应」，极难查）。
     */
    private val prefChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 只按 key 判定，不比对 prefs 实例：功能页与悬浮窗拿到的可能不是同一个
            // Context（Activity vs Service），虽然同进程内通常返回同一实例，
            // 但把正确性押在「通常」上是不可接受的。键名带 orientation 语义，别的键不会误命中。
            if (key == OverlayOrientation.KEY_ORIENTATION_MODE) {
                // 手动档是用户明确点的，不需要 400ms 去抖：立刻按新档重建一次
                handler.post {
                    if (root != null) {
                        pendingContentDegrees = null
                        pendingSinceElapsed = 0L
                        appliedContentDegrees = resolveContentDegrees()
                        rebuild()
                    }
                }
            }
        }

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

        // 窗口坐标系：LOCKED_PORTRAIT 时是竖屏那套（短边 × 长边），
        // FOLLOW_DISPLAY 时是 display 当前的逻辑尺寸（横屏时 2340×1080）。
        val (spaceWidth, spaceHeight) = OverlayOrientation.windowSpaceSize(
            model = windowSpaceModel,
            realWidthPx = real.x,
            realHeightPx = real.y,
            shortSidePx = shortSide,
            longSidePx = longSide
        )

        // 卡片宽度受**窗口坐标系的宽**约束：锁在竖屏时是物理短边，
        // 跟随 display 时是逻辑宽（横屏时是长边）。竖屏两者同为 1080，零回归。
        val cardWidth = OverlayOrientation.cardWidthPx(
            spaceWidthPx = spaceWidth,
            density = density,
            cardMaxDp = CARD_MAX_DP,
            marginDp = CARD_MARGIN_DP
        )

        // 落位方式必须跟着窗口模型走：FOLLOW_DISPLAY 下横屏的「上边」是真的上边，
        // 用 CENTER 会把卡片放到画面正中、正好压住主体。见 `anchorFor` 的注释。
        val anchor = OverlayOrientation.anchorFor(rotation, windowSpaceModel)

        // ★ 内容朝向走「双信号」判定（display + 设备物理朝向），不再只信 display。
        //   applied 已经算过就用它 —— 重建期间传感器抖动不能让朝向跟着抖。
        val contentDegrees = appliedContentDegrees ?: resolveContentDegrees().also {
            appliedContentDegrees = it
        }
        val contentRotationIndex = OverlayOrientation.rotationIndexOf(contentDegrees)

        // 竖屏那条路上「下面要留 120dp 给快门区」；而内容预旋转后，卡片在窗口坐标系里的
        // 视觉高度是 cardWidth（宽高对调），所以下界还要再加上这一截，
        // 否则用户往下拖时卡片会拖出窗口坐标系的可视区（FLAG_LAYOUT_NO_LIMITS 允许越界，就真丢了）。
        // 只有顶部锚点用得上这两个值，其它锚点是相对中心夹取。
        val bottomReserve = if (OverlayOrientation.isQuarterTurn(contentDegrees)) {
            dp(DRAG_BOTTOM_RESERVE_DP) + cardWidth
        } else {
            dp(DRAG_BOTTOM_RESERVE_DP)
        }

        val card = buildCard()
        val handle = card.second

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val maxOffsetX = OverlayOrientation.maxOffsetXPx(spaceWidth, cardWidth)

        // 内容需要预旋转时套一层壳：窗口 Surface 的大小 = 根 View 的尺寸，
        // 而卡片转 90° 之后的视觉外框是「高 × 宽」，直接转根 View 会被裁掉。
        val rotated = contentRotationIndex != OverlayOrientation.ROTATION_0
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
                        bottomReserve
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
                // ★ 用**生效的**旋转序号，不是 `Display.getRotation()`：
                //   「界面转了但 display 没转」时两者不同，那正是本次要修的场景。
                contentRotation = contentRotationIndex
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
                                rotation = rotation,
                                model = windowSpaceModel
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
                                        bottomReserve
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
            startOrientationSensors()
            startOrientationPolling()
            startPrefListener()
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

    /**
     * 收摊：**所有**注册过的东西都在这里反注册。
     *
     * 顺序无所谓，但一条都不能漏 —— 漏一条就是 Service 泄漏
     * （`ScreenCaptureService.cleanup()` 是唯一的调用者，它之后会 `stopSelf()`）。
     * - `DisplayListener`（系统服务持有我们的回调）
     * - 加速度计（`SensorManager` 持有我们的回调，**还会持续耗电**）
     * - 500ms 轮询（主线程 Handler 持有 Runnable，会一直把自己排回去）
     * - SharedPreferences 监听器（虽然它不持有 Context，但会拿到失效的 prefs 回调）
     */
    fun hide() {
        unregisterDisplayListener()
        stopOrientationPolling()
        stopOrientationSensors()
        stopPrefListener()
        // 下次 show() 要重新采样：中间可能已经转过屏了
        appliedContentDegrees = null
        pendingContentDegrees = null
        pendingSinceElapsed = 0L
        val v = root ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        root = null
    }

    // ── 方向判定（display + 设备物理朝向 双信号）──────────────────────────────

    /**
     * 最终要用的内容预旋转角：**手动档位优先，其次自动判定**。
     *
     * 每次现算，不缓存 —— 缓存就得管失效，而这里读的是两个随手可得的属性、
     * 加上一个 volatile Float，成本可以忽略。
     */
    private fun resolveContentDegrees(): Float {
        val manual = OverlayOrientation.parseManualOrientation(
            prefs.getString(OverlayOrientation.KEY_ORIENTATION_MODE, null)
        )
        return OverlayOrientation.resolveContentDegrees(
            displayRotation = currentRotation(),
            deviceRollDeg = deviceRollDeg,
            model = windowSpaceModel,
            manual = manual
        )
    }

    /**
     * 朝向变了没有？变了、且稳定够久 → 重建。
     *
     * 去抖用「候选角度的首次出现时刻」而不是 `Handler` 的延迟消息：
     * 角度在阈值附近来回抖时，延迟消息会被不停取消再重排，
     * 而记录时刻的写法天然只认「连续稳定」——逻辑更短也更好读。
     */
    private fun applyOrientationIfStable() {
        if (root == null) return
        val target = resolveContentDegrees()
        val applied = appliedContentDegrees
        if (applied != null && target == applied) {
            // 回到已生效的角度：候选作废（用户转了一下又转回来）
            pendingContentDegrees = null
            pendingSinceElapsed = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pendingContentDegrees != target) {
            pendingContentDegrees = target
            pendingSinceElapsed = now
            return
        }
        if (now - pendingSinceElapsed < ORIENTATION_STABLE_MS) return
        pendingContentDegrees = null
        pendingSinceElapsed = 0L
        appliedContentDegrees = target
        rebuild()
    }

    /** 500ms 轮询：DisplayListener 不回调时的最后一道保险 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            // 窗口没了就别再排自己 —— 这是轮询不会泄漏的唯一保证
            if (root == null) return
            applyOrientationIfStable()
            handler.postDelayed(this, ORIENTATION_POLL_MS)
        }
    }

    private fun startOrientationPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, ORIENTATION_POLL_MS)
    }

    private fun stopOrientationPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    /**
     * 加速度计/重力：唯一能在「display 说没转」时告诉我们设备其实横过来了的信号。
     *
     * 拿不到传感器（`gravitySensor == null`）就静默跳过 —— 此时只剩 display 与轮询，
     * 页面照样能用，只是那种 ROM 上没有修复效果。**不要**弹窗、不要报错。
     */
    private fun startOrientationSensors() {
        if (sensorRegistered) return
        val manager = sensorManager ?: return
        val sensor = gravitySensor ?: return
        try {
            manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI, handler)
            sensorRegistered = true
        } catch (_: Throwable) {
            sensorRegistered = false
        }
    }

    private fun stopOrientationSensors() {
        if (!sensorRegistered) return
        sensorRegistered = false
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (_: Throwable) {
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSensorEmitAt < SENSOR_MIN_INTERVAL_MS) return
            lastSensorEmitAt = now
            val v = event.values
            if (v.size < 3) return
            // rotationDegrees = 0：**故意**不按屏幕方向补偿，要的就是设备自身的横滚角。
            // 传 display 的旋转进来会把「界面转了但 display 没转」这个信号抵消掉 ——
            // 那正是本次要抓的东西。
            val reading = LevelMath.solve(gx = v[0], gy = v[1], gz = v[2], rotationDegrees = 0)
                ?: return
            deviceRollDeg = reading.roll
            applyOrientationIfStable()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 精度变化不需要处理：朝向判定只看是否过了 55° 阈值，对噪声不敏感
        }
    }

    private fun startPrefListener() {
        try {
            prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Throwable) {
        }
    }

    private fun stopPrefListener() {
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Throwable) {
        }
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
                val rotation = currentRotation()
                if (rotation == lastRotation) return
                lastRotation = rotation
                // 真实转屏是系统级事件（系统已经去过抖），而且窗口尺寸、卡片宽度、
                // 夹取区间全都要跟着变 —— 不等 400ms，立刻重建。
                pendingContentDegrees = null
                pendingSinceElapsed = 0L
                appliedContentDegrees = resolveContentDegrees()
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
 * 支点与外框的算法只有一处：[OverlayOrientation.visual] —— 这里直接调它，
 * 不自己再写一遍，免得两处算法走偏（走偏的表现是内容被裁掉一截）。
 */
private class RotationShell(context: Context) : FrameLayout(context) {

    /** 内容按多宽排版（= 卡片宽度） */
    var contentWidthPx: Int = 0

    /** 物理旋转（`Display.getRotation()` 的序号：0/1/2/3） */
    var contentRotation: Int = OverlayOrientation.ROTATION_0

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
        val visual = OverlayOrientation.visual(
            cardWidthPx = contentWidthPx,
            contentHeightPx = child.measuredHeight,
            rotation = contentRotation
        )
        child.rotation = OverlayOrientation.contentRotationDegrees(contentRotation)
        child.pivotX = visual.pivotX
        child.pivotY = visual.pivotY
        setMeasuredDimension(visual.widthPx, visual.heightPx)
    }
}
