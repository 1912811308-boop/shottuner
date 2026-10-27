package com.shottuner.app.device

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.shottuner.app.model.CameraCaps
import com.shottuner.app.text.NumFmt
import kotlin.math.hypot
import kotlin.math.roundToInt

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
