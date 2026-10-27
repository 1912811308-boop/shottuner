package com.shottuner.app.device

import android.os.Build
import com.shottuner.app.model.CameraCaps

/**
 * [DeviceProfile.unknown] 的 Android 实现。
 *
 * 为什么做成**扩展函数**而不是 companion 方法：
 *   `unknown()` 要读 `android.os.Build`（MANUFACTURER / BRAND / MODEL / VERSION），
 *   而 commonMain 里没有这个类 —— 所以它必须留在 Android 侧。
 *   做成 `DeviceProfile.Companion` 的扩展函数后，**调用方写
 *   `DeviceProfile.unknown()` 的写法完全不用改**（MainActivity 与
 *   ScreenCaptureService 各一处）。
 */
fun DeviceProfile.Companion.unknown(): DeviceProfile {
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
