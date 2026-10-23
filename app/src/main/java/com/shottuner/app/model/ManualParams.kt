package com.shottuner.app.model

/**
 * 相机当前（或即将写入）的曝光参数。
 *
 * 这个类放在 model 包里而不是塞进 CameraEngine，是因为它是算法层与相机层之间的
 * 唯一契约：算法产出它，相机执行它。两边各自持有一份同名类会导致类型不兼容，
 * 这是 Kotlin 里非常容易踩的坑。
 */
class ManualParams(val iso: Int, val shutterNs: Long) {
    /** 相对曝光量（ISO × 秒），只用于比较大小，没有绝对物理意义 */
    val exposure: Double get() = iso.toDouble() * (shutterNs / 1e9)
}
