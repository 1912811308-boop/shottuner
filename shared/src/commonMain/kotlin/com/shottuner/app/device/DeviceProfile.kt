package com.shottuner.app.device

import com.shottuner.app.model.CameraCaps
import com.shottuner.app.text.NumFmt

/**
 * 设备档案的**纯数据模型**（从 HardwareProbe.kt 抽出来）。
 *
 * 为什么抽：
 *   [LensInfo] / [VideoCaps] / [DeviceProfile] 三个都是纯数据类，却与
 *   Android-only 的 `object HardwareProbe` 同处一个文件（该文件 import 了 6 个
 *   `android.*`）—— 于是 `feature/FeatureAdvisor` 与 `video/VideoRecommender`
 *   两个「纯文件」实际**不可移植**。
 *
 *   拆出来之后：数据模型进 commonMain，真正需要 Android 的
 *   `HardwareProbe.probe(context)` 留在 app 侧，**app → shared 单向依赖**。
 */

/** 一颗镜头。等效焦距由传感器物理尺寸现场算，不查机型表。 */
class LensInfo(
    val id: String,
    /** 等效 35mm 焦距，四舍五入到整数。0 表示厂商没给物理尺寸，算不出来 */
    val equivFocalMm: Int,
    val focalMm: Float,
    val aperture: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val hasOis: Boolean,
    val isBack: Boolean
) {
    val megapixels: Float
        get() = if (pixelWidth <= 0 || pixelHeight <= 0) 0f
        else (pixelWidth.toLong() * pixelHeight / 1_000_000.0).toFloat()

    val sensorArea: Float get() = sensorWidthMm * sensorHeightMm

    val label: String
        get() {
            val f = if (equivFocalMm > 0) "${equivFocalMm}mm" else "?mm"
            val a = if (aperture > 0f) " f/${NumFmt.one(aperture)}" else ""
            return "$f$a · ${NumFmt.one(megapixels)}MP"
        }
}

/** 录像侧的能力。全部来自运行时探测，不是猜的。 */
class VideoCaps(
    val maxWidth: Int,
    val maxHeight: Int,
    val fps720: Int,
    val fps1080: Int,
    val fps4k: Int,
    val fps8k: Int,
    /** 高速摄影（慢动作）能达到的最高帧率，0 表示不支持 */
    val maxHighSpeedFps: Int,
    val hasOis: Boolean,
    val hasEis: Boolean,
    val supports10Bit: Boolean,
    val supportsRaw: Boolean
) {
    val maxLabel: String
        get() {
            if (maxWidth <= 0 || maxHeight <= 0) return "未知"
            val mp = maxWidth.toLong() * maxHeight / 1_000_000.0
            return if (mp >= 30) "8K" else if (mp >= 8) "4K" else if (mp >= 2) "1080P" else "720P"
        }

    fun fpsAtTier(tier: String): Int = when (tier) {
        "8K" -> fps8k
        "4K" -> fps4k
        "1080P" -> fps1080
        "720P" -> fps720
        else -> 30
    }

    /** 该档位是否真的能拍：有分辨率且至少有 24fps */
    fun canRecord(tier: String): Boolean = fpsAtTier(tier) >= 24

    companion object {
        val UNKNOWN = VideoCaps(0, 0, 30, 30, 0, 0, 0, false, false, false, false)
    }
}

/**
 * 一台设备的完整档案。
 *
 * 这是「支持所有机型」的实现方式：不维护机型白名单，而是运行时向系统要真值。
 * 各厂商的 Sensor 尺寸、ISO 范围、每档分辨率的实际帧率、防抖支持都不同，
 * CameraCharacteristics 里全都有，读出来即可，永远不会过期。
 */
class DeviceProfile(
    val brand: Brand,
    /** 人话机型名，如「Xiaomi 14 Pro」 */
    val modelName: String,
    val rawModel: String,
    val osLabel: String,
    val androidRelease: String,
    val androidSdk: Int,
    val lenses: List<LensInfo>,
    /** 后置主摄，取传感器面积最大的一颗 */
    val main: LensInfo?,
    val caps: CameraCaps,
    val video: VideoCaps,
    val hasFlash: Boolean,
    val isMultiCamera: Boolean,
    /** 该机能否写手动曝光参数 */
    val supportsManualPhoto: Boolean,
    /** 品牌是否提供可调快门的专业录像 */
    val supportsManualVideo: Boolean
) {
    /** 主摄等效焦距，缺省按手机主流的 23mm 估 */
    val equivFocalMm: Int get() = main?.equivFocalMm?.takeIf { it > 0 } ?: 23

    /**
     * 手持安全快门（纳秒）。
     *
     * 物理基准是「1 / 等效焦距」，这是 135 画幅的老经验，对手机同样适用：
     * 23mm 约 1/23s，70mm 长焦就要 1/70s。
     * 有 OIS 的机型可放宽约两档（÷4），但只在拍照时成立——
     * 录像时 OIS 只稳机身不冻主体，不能这么放宽。
     */
    val handheldSafeShutterNs: Long
        get() {
            val base = 1_000_000_000.0 / equivFocalMm
            val factor = if (main?.hasOis == true) 4.0 else 1.0
            return (base * factor).toLong().coerceIn(2_000_000L, 200_000_000L)
        }

    /** 一行的设备摘要，给界面底部显示 */
    fun summary(): String {
        val lens = main?.label ?: "镜头信息缺失"
        val manual = if (supportsManualPhoto) "可手动" else "仅自动"
        val v = video.maxLabel
        return "$modelName · $osLabel\n主摄 $lens · $manual · 录像最高 $v"
    }

    companion object {
        // Android 侧的 [unknown] 实现见 app 模块的 DeviceProfileAndroid.kt ——
        // 它要读 android.os.Build，而 commonMain 里没有这个类。
        // 这里保留空 companion，是为了让那个扩展函数有挂载点，
        // 调用方写 DeviceProfile.unknown() 的写法不用改。
    }
}
