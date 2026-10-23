package com.shottuner.app.screen

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shottuner.app.R
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
        const val HANDLE_TOUCH_DP = 24
        const val HANDLE_BAR_W_DP = 36
        const val HANDLE_BAR_H_DP = 4
        const val DEFAULT_TOP_DP = 96
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var tvScene: TextView
    private lateinit var tvParams: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var tvCompose: TextView
    private lateinit var tvScore: TextView

    fun show() {
        if (root != null) return
        val dm = context.resources.displayMetrics
        val cardWidth = min(dp(CARD_MAX_DP), dm.widthPixels - dp(24))

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
        val handle = LinearLayout(context).apply {
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

        card.addView(handle, LinearLayout.LayoutParams(
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

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxOffsetX = ((dm.widthPixels - cardWidth) / 2).coerceAtLeast(0)

        val lp = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt(KEY_X, 0).coerceIn(-maxOffsetX, maxOffsetX)
            y = prefs.getInt(KEY_Y, dp(DEFAULT_TOP_DP))
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
                            (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            lp.x = (startX + dx).roundToInt().coerceIn(-maxOffsetX, maxOffsetX)
                            // 上不越过状态栏，下留 120dp 余量给快门区
                            lp.y = (startY + dy).roundToInt()
                                .coerceIn(dp(24), dm.heightPixels - dp(120))
                            try {
                                wm.updateViewLayout(card, lp)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
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
            wm.addView(card, lp)
            root = card
            params = lp
        } catch (_: Throwable) {
            root = null
        }
    }

    fun render(
        scene: String,
        paramsText: String,
        videoText: String,
        adviceText: String,
        composeTip: String,
        scoreText: String
    ) {
        if (root == null) return
        tvScene.text = scene
        tvParams.text = paramsText
        tvVideo.text = videoText
        tvVideo.visibility = if (videoText.isBlank()) View.GONE else View.VISIBLE
        tvScore.text = scoreText
        tvScore.visibility = if (scoreText.isBlank()) View.GONE else View.VISIBLE
        tvAdvice.text = adviceText
        tvAdvice.visibility = if (adviceText.isBlank()) View.GONE else View.VISIBLE
        tvCompose.text = composeTip
        tvCompose.visibility = if (composeTip.isBlank()) View.GONE else View.VISIBLE
    }

    fun hide() {
        val v = root ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        root = null
    }

    private fun col(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
