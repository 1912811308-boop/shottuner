package com.shottuner.app.sensors

import com.shottuner.app.text.NumFmt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.PI

/**
 * 姿态数学：重力向量 → 横滚/俯仰，以及水平仪的方向文案。
 *
 * 这个文件是**纯函数**，不 import 任何 Android 类型，所以能直接跑 JVM 单测。
 * Android 侧的传感器封装在 [LevelSensor]。
 *
 * ## 符号约定
 * 沿用 `CompositionHint.tiltDeg` 的约定：**roll > 0 表示画面右侧偏低**。
 * 也就是说 roll 描述的是"照片歪成什么样"，而不是"手机往哪边倒"。
 *
 * 推导（0° 屏幕方向）：
 * 把重力（使用加速度计的反作用力方向，静止时模长为 g）投影到设备坐标系，
 * `roll = atan2(gx, gy)`。设备逆时针倾斜 φ 时 gx = g·sinφ，于是 roll = φ > 0，
 * 而手机逆时针倾斜恰好让画面里的地平线右端下沉 —— 与约定一致。
 *
 * 修正方向：画面右端偏低时，用户需要把手机顺时针转回去，
 * 因此 `roll > 0 → "顺时针"`。前置镜像预览里方向是反的，必须翻转。
 */
object LevelMath {

    /** 重力向量模长低于该值视为"没有有效数据"（自由落体 / 传感器未就绪）。 */
    private const val MIN_GRAVITY = 1.5f

    /** 判定"已经平了"的容差（度）。 */
    const val LEVEL_TOLERANCE_DEG = 1f

    /** 姿态读数。 */
    class LevelReading(
        /** 横滚角（度），正值 = 画面右侧偏低 */
        val roll: Float,
        /** 俯仰角（度），0 = 手机竖直，负值 = 俯拍 */
        val pitch: Float
    ) {
        override fun toString(): String =
            "LevelReading(roll=${NumFmt.fixed(roll, 2)}, pitch=${NumFmt.fixed(pitch, 2)})"
    }

    /** 水平仪文案。 */
    class RollHint(
        /** 偏差绝对角度（整数度） */
        val degrees: Int,
        /** 修正方向："顺时针" / "逆时针" */
        val direction: String
    ) {
        /** 一句话建议，供复盘建议与提示卡复用 */
        val text: String get() = "地平线还差 ${degrees}°，${direction}转一点"
    }

    /**
     * 由重力向量 + 屏幕旋转角算出姿态。
     *
     * @param gx 设备坐标系 x 分量（向右为正）
     * @param gy 设备坐标系 y 分量（向上为正）
     * @param gz 设备坐标系 z 分量（垂直屏幕向外为正）
     * @param rotationDegrees `screen.orientation.angle`，取值 0/90/180/270
     * @return null 表示重力向量退化（模长过小），调用方必须把"不可用"当一等公民处理，不要拿 NaN 往下算
     */
    fun solve(gx: Float, gy: Float, gz: Float, rotationDegrees: Int): LevelReading? {
        if (!gx.isFinite() || !gy.isFinite() || !gz.isFinite()) return null
        val magnitude = hypot(hypot(gx, gy), gz)
        if (magnitude < MIN_GRAVITY) return null

        val deg = 180.0 / PI
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val quarter = ((normalized + 45) / 90) * 90 % 360

        // 把重力投影到"屏幕坐标系"，再算角度
        val (sx, sy) = when (quarter) {
            90 -> Pair(-gy, gx)
            180 -> Pair(-gx, -gy)
            270 -> Pair(gy, -gx)
            else -> Pair(gx, gy)
        }
        val roll = (atan2(sx.toDouble(), sy.toDouble()) * deg).toFloat()

        // 俯仰：屏幕横轴上的重力分量与 z 分量的夹角，向下俯拍为负
        val pitchAxis = when (quarter) {
            90 -> gy
            180 -> -gy
            270 -> -gy
            else -> gy
        }
        val pitch = (-atan2(gz.toDouble(), pitchAxis.toDouble()) * deg).toFloat()

        return LevelReading(roll = roll, pitch = pitch)
    }

    /** 是否已水平（无数据时视为"不判断"，由调用方决定文案）。 */
    fun isLevel(roll: Float?): Boolean =
        roll != null && abs(roll) <= LEVEL_TOLERANCE_DEG

    /**
     * 水平仪方向文案。
     *
     * @param roll 横滚角（度），正值 = 画面右侧偏低
     * @param mirrored 预览是否镜像。镜像会把左右翻转，文案必须跟着翻，否则用户照提示转越转越歪
     */
    fun rollHint(roll: Float, mirrored: Boolean): RollHint {
        val absDeg = abs(roll)
        val degrees = absDeg.roundToInt()
        // 画面右端偏低 → 把手机顺时针转回去
        val clockwise = roll > 0f
        val flip = mirrored
        val finalClockwise = if (flip) !clockwise else clockwise
        return RollHint(
            degrees = degrees,
            direction = if (finalClockwise) "顺时针" else "逆时针"
        )
    }

    /** 把角度夹到 [-180, 180]，供外部展示用。 */
    fun normalizeDegrees(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
