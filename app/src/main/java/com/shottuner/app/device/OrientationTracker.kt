package com.shottuner.app.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.atan2

/**
 * 手机俯仰角 / 横滚角跟踪器（P0-1）。
 *
 * 只依赖 TYPE_ROTATION_VECTOR：它是普通传感器，不需要任何权限，也不受摄像头
 * 是否可用影响。游戏矢量已经融合了加速度计、陀螺仪与磁力计，比单读重力方向稳，
 * 又比原始陀螺仪积分不容易漂。
 *
 * 角度定义（单位：度）：
 * - pitch：屏幕平面相对水平面的倾角。手机平放（屏幕朝上）≈ 0°，竖持取景 ≈ 90°，
 *   继续向后仰越过 90° 后进入 90°–180°，倒置时为负值；
 * - roll：竖持时左右歪头的角度。竖持正对 ≈ 0°，向右歪 45° ≈ +45°。
 *
 * 推导：旋转矩阵 R 把设备坐标映射到世界坐标（东、北、天）。世界「天」方向在
 * 设备坐标中的分量就是 R 的第三行 (ux, uy, uz)，也就是设备 +Y（机顶）与 +Z
 * （屏幕法线）各自朝向天空的程度。于是：
 *   pitch = atan2(uy, uz)   // 平放 uy=0,uz=1 → 0°；竖持 uy=1,uz=0 → 90°
 *   roll  = atan2(ux, uy)   // 竖持 ux=0,uy=1 → 0°；侧倾时 ux 随之变化
 * 这两个式子对平放和竖持都连续，且不含磁北，因此不会因为室内磁干扰而跳变。
 *
 * 回调频率固定为 10Hz（100ms 一次），但传感器本身按 GAME 档（约 20ms）采样：
 * 这样低通滤波能拿到足够密的样本，UI 又不会被高频回调拖慢。
 *
 * 低通系数 α = 0.25，理由：10Hz 输出下时间常数 τ ≈ -dt / ln(1-α) ≈ 0.35s，
 * 足以压掉手持时的 1–2° 高频抖动，同时转动手机时的跟随延迟只有约 0.1–0.2s，
 * 不会让人感觉刻度「粘手」。α 越大越跟手但越抖，0.25 是两者的折中。
 */
class OrientationTracker(
    context: Context,
    private val onPitchRoll: (pitchDeg: Float, rollDeg: Float) -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager? = try {
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    } catch (_: Throwable) {
        null
    }

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)

    /** 上一次输出是否已初始化。NaN 表示还没有可信样本 */
    private var filteredPitch = Float.NaN
    private var filteredRoll = Float.NaN

    private var listening = false
    private var lastEmitAt = 0L

    val isAvailable: Boolean get() = rotationSensor != null

    fun start() {
        if (listening) return
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: return
        filteredPitch = Float.NaN
        filteredRoll = Float.NaN
        lastEmitAt = 0L
        try {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            listening = true
        } catch (_: Throwable) {
            listening = false
        }
    }

    fun stop() {
        if (!listening) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
        listening = false
        filteredPitch = Float.NaN
        filteredRoll = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (event.values.size < 3) return

        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        } catch (_: Throwable) {
            return
        }

        // 世界坐标系的「上」在设备坐标中的分量（旋转矩阵第三行）
        val ux = rotationMatrix[6]
        val uy = rotationMatrix[7]
        val uz = rotationMatrix[8]

        val rawPitch = Math.toDegrees(atan2(uy.toDouble(), uz.toDouble())).toFloat()
        val rawRoll = Math.toDegrees(atan2(ux.toDouble(), uy.toDouble())).toFloat()

        // 低通滤波：首次样本直接采用，避免从 0 慢慢爬上来
        if (filteredPitch.isNaN() || filteredRoll.isNaN()) {
            filteredPitch = rawPitch
            filteredRoll = rawRoll
        } else {
            val alpha = LOW_PASS_ALPHA
            filteredPitch += alpha * (rawPitch - filteredPitch)
            filteredRoll += alpha * (rawRoll - filteredRoll)
        }

        // 10Hz 输出节流
        val now = SystemClock.elapsedRealtime()
        if (lastEmitAt != 0L && now - lastEmitAt < EMIT_INTERVAL_MS) return
        lastEmitAt = now
        onPitchRoll(filteredPitch, filteredRoll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化不影响角度读数，忽略即可
    }

    private companion object {
        const val LOW_PASS_ALPHA = 0.25f
        const val EMIT_INTERVAL_MS = 100L
    }
}
