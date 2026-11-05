// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 「照拨」参数建议的跨语言门面。
//
// ★ 这个文件补的是本产品**名字的由来**。
//   App 叫「光影调参」，核心承诺是「拍之前就知道该怎么调」——
//   也就是告诉你 ISO 拨到多少、快门拨到多少、色温往哪边挪、曝光补偿加几档。
//   Android 侧一直有这个面板（`ParamRecommender.recommend` 的输出 → `renderParams`），
//   iOS 侧却**一次都没调过** —— 引擎在 commonMain 里，但没有门面，Swift 够不着。
//   ⇒ 结果就是：iOS 版能打分、能出模式建议，唯独不给「该拨几档」。
//
// ★ 为什么不自成一个 `Recommendation` DTO 而是拍平：
//   与 IosMissionDto / IosAdviceDto 同一套理由 —— 跨语言边界只传标量。
//   `Recommendation` 里虽然没有数组，但它带 `SceneType` 枚举（导出成 Swift 的
//   `SceneType`，还要再 `.name`），而且 Swift 侧不需要「能构造它」，只需要「能读」。
//   拍平之后 Swift 侧连 `SceneType` 都不用 import。
//
// ★ 文案为什么在这里生成、不留给 Swift：
//   `shutterLabel` / `evText` / `summary` 三个串都有**口径**：
//     · 快门必须显示 `1/60` 而不是 `1/63`（吸附到真实档位，`Recommendation` 已做）
//     · EV 必须 `±0` 而不是 `+0.0`，且 `+` / `−` 符号本身承载语义（色盲也能读）
//     · 摘要行 Android 已经在用一句话，两端逐字一致才不会「同一个数两种说法」
//   这些规则放在 Swift 里重写一遍，就是第二份实现 —— 它一定会漂移。

package com.shottuner.app.ios

import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.model.Recommendation
import com.shottuner.app.recommend.ParamRecommender
import com.shottuner.app.text.NumFmt
import kotlin.math.abs

/**
 * 一次「照拨」建议的拍平版。字段语义全部来自 [Recommendation]。
 *
 * ## ★ [valid] 为 false 时，其余字段**不是 0，是「没有」**
 *
 * 出现 `valid == false` 只有一种情况：这次分析还没算出 `FrameStats`
 * （冷启动第一帧之前、或相机刚恢复）。此时 Swift 侧必须**整块不显示**，
 * 不能把 `iso = 0` / `shutterLabel = "--"` 印出来 ——
 * 「ISO 0」在用户眼里是「这台机器坏了」，而我们只是还没读到画面。
 *
 * 本仓在这一点上已经栽过三次（成绩卡印假分数、成长卡印「首次 0」、
 * 功能页传 `needsTripod = false`），所以这里把「有没有」做成**独立字段**，
 * 而不是让调用方拿某个数值去猜。
 */
class IosParamDto(
    /** 这次有没有算出建议。false → 整块不显示。 */
    val valid: Boolean,
    /** 判定出的场景枚举名（`PORTRAIT` / `NIGHT` …），诊断用。 */
    val sceneName: String,
    /** 场景的中文名（「人像」/「夜景 / 弱光」），给界面标注用。 */
    val sceneLabel: String,
    /** 建议感光度。 */
    val iso: Int,
    /** 建议曝光时间（纳秒）。相机若能写手动曝光就照这个设。 */
    val shutterNs: Long,
    /** 快门显示文本：`1/60` / `2.5s` / `--`。已吸附到真实档位。 */
    val shutterLabel: String,
    /** 建议色温（K）。 */
    val kelvin: Int,
    /** 建议曝光补偿（EV）。正=提亮，负=压暗。 */
    val evDelta: Float,
    /** EV 的显示文本：`+1.3` / `−0.7` / `±0`。负号是 U+2212，不是 ASCII `-`。 */
    val evText: String,
    /** 是否建议上三脚架。功能页那条「别用手指戳快门」就靠它。 */
    val needsTripod: Boolean,
    /** 主句：一句话说清该做什么。 */
    val tip: String,
    /** 副句：色偏 / 细节这类补充，可能为空。 */
    val detail: String,
    /** 场景判定置信度 0..1。 */
    val confidence: Float,
    /** 一行摘要，**与 Android `tvParamsSummary` 逐字一致**：`ISO 400 · 1/60 · 5200K · EV +1.3`。 */
    val summary: String
)

/**
 * 「照拨」参数建议在 Swift 侧的门面。
 *
 * Kotlin 的 `object` 在 Swift 里是类属性单例 → 调用形态
 * `IosParamBridge.shared.recommend(profile:sceneName:)`，不是 `IosParamBridge()`。
 */
object IosParamBridge {

    /**
     * 按当前这一帧给一份调参建议。
     *
     * @param profile 设备档案。**只用它的 `caps`**（ISO / 快门 / 手动的可用范围），
     *   用来把建议值钳在设备真的能执行的范围里 —— 给一台 ISO 上限 3200 的机器
     *   建议 6400，用户拨到顶也做不到，那条建议就是错的。
     * @param sceneName `SceneType` 的枚举名。未知 / 空串一律落 `GENERAL`，
     *   与 `IosBridge.analyzeFrame` 同一个解析函数（`sceneFromName`），
     *   不允许出现第二套解析口径 —— 否则同一帧会出现「建议按夜景算、评分按通用算」。
     */
    fun recommend(profile: DeviceProfile, sceneName: String): IosParamDto {
        // ★ 统计量的唯一来源。Swift 一个数都不传，也就没有「填 0 顶替」的机会。
        //   为 null 说明还没算过任何一帧 —— 这是合法的冷启动状态，不是错误。
        val stats = IosFrameCache.latest
            ?: return invalid()

        val scene = sceneFromName(sceneName)

        // `current = null`：iOS 侧没有「已应用的手动参数」这个概念
        //（相机在自动档，我们只是建议），所以阻尼系数取 1.0（全量给出建议值）。
        // 若将来接了手动曝光并回读相机当前值，这里要传真实的 ManualParams，
        // 否则会把「已经调到位了」当成「差得很远」，来回给相反的建议。
        val rec = ParamRecommender.recommend(
            stats = stats,
            scene = scene,
            caps = profile.caps,
            current = null
        )

        return IosParamDto(
            valid = true,
            sceneName = rec.scene.name,
            sceneLabel = rec.scene.label,
            iso = rec.iso,
            shutterNs = rec.shutterNs,
            shutterLabel = rec.shutterLabel,
            kelvin = rec.kelvin,
            evDelta = rec.evDelta,
            evText = evText(rec.evDelta),
            needsTripod = rec.needsTripod,
            tip = rec.tip,
            detail = rec.detail,
            confidence = rec.confidence,
            summary = "ISO ${rec.iso} · ${rec.shutterLabel} · ${rec.kelvin}K · EV ${evText(rec.evDelta)}"
        )
    }

    /**
     * EV 的显示文本。
     *
     * ★ 与 Android `MainActivity.formatEv` **逐字同义**：
     *   `abs(ev) < 0.05` → `±0`（避免 `+0.0` 这种「看起来像有偏差」的写法），
     *   其余走 `NumFmt.signed(ev, 1)`（`+` / `−` 由 NumFmt 统一给，含负号 U+2212）。
     *
     * ★ 为什么阈值是 0.05 而不是 0：`evDelta` 是浮点算出来的，
     *   画面完全达标时它是 `1e-7` 这种量级，直接判 `== 0f` 永远不成立，
     *   用户会看到一个抖动的小数。0.05 也正好是 0.1 精度下的半个刻度 ——
     *   显示成 `±0` 时，实际偏差一定小于最小刻度的一半，这是诚实的。
     */
    private fun evText(ev: Float): String =
        if (abs(ev) < 0.05f) "±0" else NumFmt.signed(ev, 1)

    /** 「还没有读数」的唯一表示。所有数值字段给 0 只是占位，`valid` 才是判据。 */
    private fun invalid(): IosParamDto = IosParamDto(
        valid = false,
        sceneName = "",
        sceneLabel = "",
        iso = 0,
        shutterNs = 0L,
        shutterLabel = "",
        kelvin = 0,
        evDelta = 0f,
        evText = "",
        needsTripod = false,
        tip = "",
        detail = "",
        confidence = 0f,
        summary = ""
    )
}
