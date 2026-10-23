package com.shottuner.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * 重力感应封装：唯一允许 import Android 的传感器文件。
 *
 * 数学部分全在纯函数 [LevelMath] 里，这里只做三件事：
 * 1. 拿传感器（优先 `TYPE_GRAVITY`，没有就退回 `TYPE_ACCELEROMETER`）；
 * 2. 读屏幕旋转角（`Surface.ROTATION_*` → 0/90/180/270）；
 * 3. 节流到约 30Hz 再回调，避免把 UI 线程打爆。
 *
 * 降级：拿不到重力/加速度传感器时 [available] = false，调用方必须**静默隐藏水平仪**，
 * 不置灰、不报错、不显示 0°。
 */
class LevelSensor(
    context: Context,
    private val onReading: (LevelMath.LevelReading?) -> Unit
) : SensorEventListener {

    private val appContext: Context = context.applicationContext

    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /**
     * 优先进口重力传感器：它已经滤掉线性加速度，拍视频时手抖不会污染读数。
     * 没有重力传感器才退回加速度计（静止时两者等效）。
     */
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val windowManager: WindowManager? =
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var running = false
    private var lastEmitAt = 0L

    /** 是否有可用的姿态传感器。false → UI 不要渲染水平仪。 */
    val available: Boolean get() = sensor != null

    /** 开始监听。重复调用安全。 */
    fun start() {
        if (running || sensor == null) return
        val manager = sensorManager ?: return
        running = true
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** 停止监听。切到后台必须调用，省电。 */
    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEmitAt < MIN_INTERVAL_MS) return
        lastEmitAt = now
        val values = event.values
        if (values.size < 3) return
        val reading = LevelMath.solve(
            gx = values[0],
            gy = values[1],
            gz = values[2],
            rotationDegrees = screenRotationDegrees()
        )
        onReading(reading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化不需要处理：角度里已经包含噪声，UI 侧只展示整数度
    }

    /** 当前屏幕旋转角（0/90/180/270）。 */
    @Suppress("DEPRECATION")
    private fun screenRotationDegrees(): Int {
        val rotation = windowManager?.defaultDisplay?.rotation ?: return 0
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private companion object {
        /** 约 30Hz。水平仪不需要 60Hz，节流后 UI 负载减半。 */
        const val MIN_INTERVAL_MS = 33L
    }
}
