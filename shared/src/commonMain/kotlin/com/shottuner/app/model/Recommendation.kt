package com.shottuner.app.model

import com.shottuner.app.text.NumFmt
import kotlin.math.floor

enum class SceneType(val label: String) {
    NIGHT("夜景 / 弱光"),
    BACKLIT("逆光"),
    PORTRAIT("人像"),
    LANDSCAPE("风景"),
    INDOOR("室内"),
    ACTION("运动 / 抓拍"),
    GENERAL("通用")
}

/** 相机硬件能力，用于把推荐值钳制在设备可执行范围内 */
class CameraCaps(
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val supportsManualSensor: Boolean,
    val supportsManualWb: Boolean,
    /** 等效焦距（毫米），无信息时为 0 */
    val focalLengthMm: Float
) {
    companion object {
        val UNKNOWN = CameraCaps(
            isoMin = 50, isoMax = 6400,
            exposureMinNs = 20_000L, exposureMaxNs = 500_000_000L,
            supportsManualSensor = false, supportsManualWb = false,
            focalLengthMm = 0f
        )
    }
}

/**
 * 一次完整的调参建议。数值单位为真实相机语义，可直接喂给 Camera2。
 */
class Recommendation(
    val scene: SceneType,
    /** 该场景的理想平均亮度 */
    val targetLuma: Float,
    /** 相对当前画面的曝光补偿建议，单位 EV */
    val evDelta: Float,
    /** 建议感光度 */
    val iso: Int,
    /** 建议曝光时间，纳秒 */
    val shutterNs: Long,
    /** 建议色温，单位 K */
    val kelvin: Int,
    /** 是否需要三脚架 */
    val needsTripod: Boolean,
    /** 主要提示语 */
    val tip: String,
    /** 次级提示语（色偏 / 细节） */
    val detail: String,
    /** 场景判定置信度 0..1 */
    val confidence: Float
) {
    /** 1/xxx 形式的快门显示文本 */
    val shutterLabel: String
        get() {
            if (shutterNs <= 0) return "--"
            val sec = shutterNs / 1_000_000_000.0
            return             if (sec >= 0.5) NumFmt.one(sec) + "s"
            // 这里必须显式写成 floor(x + 0.5)，不能换成 kotlin.math.round：
            // java.lang.Math.round 是「向 +∞ 取整」（2.5 → 3），而 kotlin.math.round
            // 是「平局向偶数」（2.5 → 2.0）。用户直接看到的是快门标签，换错会输出
            // "1/2" 而不是 "1/60"。sec 恒为正，四舍五入即 floor(x + 0.5)。
            else "1/" + floor(1.0 / sec + 0.5).toInt()
        }

    val isoLabel: String get() = "ISO $iso"
}
