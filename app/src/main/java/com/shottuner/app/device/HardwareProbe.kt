package com.shottuner.app.device

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.shottuner.app.model.CameraCaps
import kotlin.math.hypot
import kotlin.math.roundToInt

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
            val a = if (aperture > 0f) " f/%.1f".format(aperture) else ""
            return "$f$a · %.1fMP".format(megapixels)
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
        fun unknown(): DeviceProfile {
            val brand = Brand.identify(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
            return DeviceProfile(
                brand = brand,
                modelName = (Build.MODEL ?: "未知机型").trim(),
                rawModel = Build.MODEL ?: "",
                osLabel = brand.osHint,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                androidSdk = Build.VERSION.SDK_INT,
                lenses = emptyList(),
                main = null,
                caps = CameraCaps.UNKNOWN,
                video = VideoCaps.UNKNOWN,
                hasFlash = false,
                isMultiCamera = false,
                supportsManualPhoto = false,
                supportsManualVideo = BrandGuide.hasManualVideo(brand)
            )
        }
    }
}

/**
 * 硬件探测器。
 *
 * 刻意不依赖 CameraX：CameraX 只暴露当前绑定的那颗镜头，
 * 而用户需要知道「我有几颗镜头、哪颗是长焦、哪个焦段该用哪颗」。
 * 直接走 CameraManager 才能枚举全部。
 */
object HardwareProbe {

    /** 35mm 画幅对角线，用于把物理焦距换算成等效焦距 */
    private const val FULL_FRAME_DIAGONAL_MM = 43.267

    fun probe(context: Context): DeviceProfile {
        val brand = Brand.identify(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        val fallback = DeviceProfile.unknown()

        val cm = try {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        } catch (_: Throwable) {
            return fallback
        }

        val lenses = ArrayList<LensInfo>(4)
        var mainCh: CameraCharacteristics? = null
        var mainLens: LensInfo? = null
        var hasFlash = false
        var multi = false
        var manualPhoto = false

        val ids = try {
            cm.cameraIdList
        } catch (_: Throwable) {
            emptyArray()
        }

        for (id in ids) {
            val ch = try {
                cm.getCameraCharacteristics(id)
            } catch (_: Throwable) {
                continue
            }
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val isBack = facing == CameraCharacteristics.LENS_FACING_BACK

            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalArr = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val apArr = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            val pix = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val capsArr = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            val focal = if (focalArr != null && focalArr.isNotEmpty()) focalArr[0] else 0f
            val w = phys?.width ?: 0f
            val h = phys?.height ?: 0f
            val equiv = if (focal > 0f && w > 0f && h > 0f) {
                (focal * FULL_FRAME_DIAGONAL_MM / hypot(w.toDouble(), h.toDouble())).roundToInt()
            } else 0

            val lens = LensInfo(
                id = id,
                equivFocalMm = equiv,
                focalMm = focal,
                aperture = if (apArr != null && apArr.isNotEmpty()) apArr[0] else 0f,
                sensorWidthMm = w,
                sensorHeightMm = h,
                pixelWidth = pix?.width ?: 0,
                pixelHeight = pix?.height ?: 0,
                hasOis = oisModes?.contains(
                    CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
                ) == true,
                isBack = isBack
            )
            lenses.add(lens)

            if (isBack) {
                if (flash) hasFlash = true
                if (capsArr?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    ) == true
                ) multi = true
                if (aeModes?.contains(CameraCharacteristics.CONTROL_AE_MODE_OFF) == true) {
                    manualPhoto = true
                }
                // 主摄判定：传感器面积最大。比「像素最多」靠谱——
                // 高像素模式常出现在副摄上，而画质主要由进光量决定。
                val prev = mainLens
                if (prev == null || lens.sensorArea > prev.sensorArea) {
                    mainLens = lens
                    mainCh = ch
                }
            }
        }

        if (mainCh == null || mainLens == null) {
            return DeviceProfile(
                brand = brand,
                modelName = fallback.modelName,
                rawModel = fallback.rawModel,
                osLabel = brand.osHint,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                androidSdk = Build.VERSION.SDK_INT,
                lenses = lenses,
                main = null,
                caps = CameraCaps.UNKNOWN,
                video = VideoCaps.UNKNOWN,
                hasFlash = hasFlash,
                isMultiCamera = multi,
                supportsManualPhoto = manualPhoto,
                supportsManualVideo = BrandGuide.hasManualVideo(brand)
            )
        }

        val ch = mainCh
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val capsArr = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val caps = CameraCaps(
            isoMin = isoRange?.lower ?: 50,
            isoMax = isoRange?.upper ?: 6400,
            exposureMinNs = expRange?.lower ?: 20_000L,
            exposureMaxNs = expRange?.upper ?: 500_000_000L,
            supportsManualSensor = manualPhoto,
            supportsManualWb = false,
            focalLengthMm = mainLens.focalMm
        )

        fun fpsAt(w: Int, h: Int): Int {
            val m = map ?: return 0
            val size = Size(w, h)
            val supported = try {
                m.getOutputSizes(SurfaceTexture::class.java)
            } catch (_: Throwable) {
                null
            } ?: return 0
            if (!supported.contains(size)) return 0
            val dur = try {
                m.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
            } catch (_: Throwable) {
                0L
            }
            if (dur <= 0L) return 0
            return (1e9 / dur.toDouble()).roundToInt()
        }

        // 慢动作能力只能从高速摄影接口读，普通 outputSizes 拿不到 240/480fps
        var maxHsFps = 0
        try {
            val hsSizes = map?.highSpeedVideoSizes ?: emptyArray()
            for (s in hsSizes) {
                val ranges = map?.getHighSpeedVideoFpsRangesFor(s) ?: continue
                for (r in ranges) {
                    if (r.upper > maxHsFps) maxHsFps = r.upper
                }
            }
        } catch (_: Throwable) {
        }

        val eisModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val outSizes = try {
            map?.getOutputSizes(SurfaceTexture::class.java)
        } catch (_: Throwable) {
            null
        }
        val maxSize = outSizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(0, 0)

        val video = VideoCaps(
            maxWidth = maxSize.width,
            maxHeight = maxSize.height,
            fps720 = fpsAt(1280, 720),
            fps1080 = fpsAt(1920, 1080),
            fps4k = fpsAt(3840, 2160),
            fps8k = fpsAt(7680, 4320),
            maxHighSpeedFps = maxHsFps,
            hasOis = mainLens.hasOis,
            hasEis = (eisModes?.any { it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF }
                ?: false),
            supports10Bit = capsArr?.contains(8) == true, // DYNAMIC_RANGE_TEN_BIT（API 31）
            supportsRaw = capsArr?.contains(3) == true    // RAW_SENSOR
        )

        return DeviceProfile(
            brand = brand,
            modelName = buildModelName(brand),
            rawModel = Build.MODEL ?: "",
            osLabel = osLabel(brand),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            androidSdk = Build.VERSION.SDK_INT,
            lenses = lenses.filter { it.isBack }.sortedBy { it.equivFocalMm },
            main = mainLens,
            caps = caps,
            video = video,
            hasFlash = hasFlash,
            isMultiCamera = multi,
            supportsManualPhoto = manualPhoto,
            supportsManualVideo = BrandGuide.hasManualVideo(brand)
        )
    }

    /**
     * 机型展示名。
     *
     * Build.MODEL 在多数国行机上是英文营销名（Xiaomi 14 Pro），
     * 部分厂商给的是内部代号（如 M2102K1C），那种情况只能照原样显示——
     * 编一个看起来像型号的假名字比显示代号更糟。
     */
    private fun buildModelName(brand: Brand): String {
        val model = (Build.MODEL ?: "").trim()
        val name = if (model.isEmpty()) "未知机型" else model
        // 品牌已在前缀里就不重复加
        val lower = name.lowercase()
        val brandWord = brand.label.lowercase()
        return if (lower.startsWith(brandWord) || lower.contains(brand.label)) name
        else "${brand.label} $name"
    }

    /**
     * 系统版本展示名。判断顺序即厂商的版本演进顺序。
     */
    private fun osLabel(brand: Brand): String {
        val sdk = Build.VERSION.SDK_INT
        return when (brand) {
            Brand.XIAOMI, Brand.REDMI ->
                if (sdk >= 34) "HyperOS" else "MIUI 14"
            Brand.HUAWEI ->
                if (sdk >= 30) "HarmonyOS" else "EMUI"
            Brand.HONOR -> "MagicOS"
            Brand.OPPO, Brand.ONEPLUS -> "ColorOS"
            Brand.REALME -> "realme UI"
            Brand.VIVO, Brand.IQOO -> "OriginOS"
            Brand.SAMSUNG -> "One UI"
            Brand.MEIZU -> "Flyme"
            else -> "Android ${Build.VERSION.RELEASE}"
        }
    }
}
