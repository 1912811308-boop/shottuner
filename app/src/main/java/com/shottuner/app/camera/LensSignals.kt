package com.shottuner.app.camera

/**
 * 「当前是哪颗镜头」→ 提示引擎 / 技法推荐看到的镜头档案。
 *
 * ## 为什么需要这一层
 * 3.2.0 加了前置通路，而 `DeviceProfile` 里的 `hasTele` / `equivFocalMm` 描述的是
 * **后置主摄** —— `HardwareProbe` 明确只把 `isBack` 的镜头塞进 `lenses`。
 * 前置时继续拿后摄档案去喂规则，会得到两条荒谬建议：
 * 1. R05「人像用广角」：前置 24mm 命中 `equivFocalMm <= 28` 且 `hasTele`，
 *    于是劝用户「切到 2x」—— 而前置的 2x 是纯数码变焦，画质塌方；
 * 2. R08「暗光别硬拉长焦」：前置根本没有长焦，`hasTele` 为真属于凭空捏造。
 *
 * 所以这里把「按镜头折算」抽成一个**纯函数**：不 import 任何 Android 类型，
 * 不需要真机就能在 JVM 上把口径钉死。
 *
 * ## 三条口径
 * - **前置没有长焦**：`CameraSelector.DEFAULT_FRONT_CAMERA` 只会给我们一颗前置；
 *   就算某些机型有前置多摄，本 App 也没有切换入口，所以 `hasTele` 恒 false；
 * - **前置的等效焦距只用前置自己的数据**：算得出来就用（`CameraEngine.currentEquivFocalMm()`），
 *   算不出来给 0 —— 0 在本项目 common 里的语义是「未知」，比拿主摄的 23mm 冒充诚实；
 * - **后置路径逐字节不变**：老口径（`profile.lenses.size > 1` / `profile.equivFocalMm`）
 *   原样透传，这样"出问题一定是前置"这条排查前提成立。
 */
object LensSignals {

    /** 折算结果。用 data class 是为了让测试能直接比相等，而不是逐个字段断言。 */
    data class Values(
        /** 当前镜头是否有可用的独立长焦（决定能不能给"切 2x / 走过去别硬拉"这类建议） */
        val hasTele: Boolean,
        /** 当前镜头等效焦距（35mm 口径）。0 = 算不出来（未知） */
        val equivFocalMm: Int
    )

    /**
     * 按当前镜头折算。
     *
     * @param isFront 当前绑的是不是前置
     * @param backHasTele 后置档案里的"有长焦"判断（`profile.lenses.size > 1`）
     * @param backEquivFocalMm 后置主摄等效焦距（`profile.equivFocalMm`）
     * @param frontEquivFocalMm 前置等效焦距，由 `CameraEngine` 从这颗镜头的
     *        `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` + `SENSOR_INFO_PHYSICAL_SIZE` 现算；
     *        算不出来传 0（未知）。负值按未知处理 —— 那种值只可能来自计算错误，
     *        不该被当成"超广角"喂给规则。
     */
    fun of(
        isFront: Boolean,
        backHasTele: Boolean,
        backEquivFocalMm: Int,
        frontEquivFocalMm: Int
    ): Values = if (isFront) {
        Values(hasTele = false, equivFocalMm = frontEquivFocalMm.coerceAtLeast(0))
    } else {
        Values(hasTele = backHasTele, equivFocalMm = backEquivFocalMm)
    }
}
