package com.shottuner.app.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「本机写入」的收敛闸门：这一次推荐值该不该真的重建 UseCase 写进相机。
 *
 * ## 为什么单独拎出来
 * 写参数 = `unbindAll` + 重新 bind，一次就是一段黑屏与对焦抖动。逐帧写（30fps）
 * 会让画面没法看，所以必须节流。但节流太紧又会这样：**用户开了本机写入，参数却
 * 一直没变化** —— 因为判据用的是"和上次写进去的值比"，一旦"上次写进去的值"记错了，
 * 循环就永久空转，界面写着「停止本机写入」、相机却在自动曝光。
 *
 * 这段判据是纯算术，不 import 任何 Android 类型，所以能在 JVM 上钉死
 * （见 `ManualWriteGateTest`）。它约束的那条不变量对本仓的两个 bug 都是承重的：
 *
 * - **`appliedIso == 0` / `appliedShutterNs == 0L` 一律视为「还没写过」，必须写。**
 *   冷启动、翻转镜头、相机出错重试（会重建 `CameraEngine`）之后，
 *   `MainActivity` 会主动把这两个字段清零，靠的就是这一条：
 *   清零 ⟹ 下一帧必定写 ⟹ 界面上的意图能在一帧内落回相机。
 *   若将来有人把这里改成"0 也走阈值比较"，那条自愈路径会静默失效。
 * - **相对阈值 + 硬下限**：ISO 15%（下限 10）、快门 20%（下限 2ms）。
 *   下限是给"已经很亮、ISO 本来就 40"的白天场景用的 —— 纯按百分比算会一直在写。
 */
object ManualWriteGate {

    /** 两次写入之间的最小间隔（ms）。相机侧的黑屏一帧 + AE 稳定大约就是这个量级。 */
    const val MIN_INTERVAL_MS = 1200L

    /** 快门阈值的硬下限：2ms。低于它就不值得为那点差别重建一次 UseCase。 */
    const val MIN_SHUTTER_THRESHOLD_NS = 2_000_000L

    /** ISO 阈值的硬下限。夜间 ISO 3200 的 15% 是 480，白天 ISO 100 的 15% 只有 15。 */
    const val MIN_ISO_THRESHOLD = 10

    /** ISO 的相对阈值：15%，并夹到 [MIN_ISO_THRESHOLD] 以上。 */
    fun isoThreshold(appliedIso: Int): Int =
        max(MIN_ISO_THRESHOLD, (appliedIso * 0.15f).roundToInt())

    /** 快门的相对阈值：20%，并夹到 [MIN_SHUTTER_THRESHOLD_NS] 以上。 */
    fun shutterThreshold(appliedShutterNs: Long): Long =
        max(MIN_SHUTTER_THRESHOLD_NS, (appliedShutterNs * 0.2).toLong())

    /**
     * 要不要写。
     *
     * @param nowMs 当前时刻（`SystemClock.elapsedRealtime()`，单调）
     * @param lastApplyAtMs 上一次真正写入的时刻；0 = 本次会话还没写过
     * @param appliedIso 上一次写进相机的 ISO；**0 = 未知，视为必须写**
     * @param appliedShutterNs 上一次写进相机的快门；**0 = 未知，视为必须写**
     * @param targetIso 这次想写的 ISO
     * @param targetShutterNs 这次想写的快门
     * @return true 表示差得够多、值得重建一次 UseCase
     */
    fun shouldWrite(
        nowMs: Long,
        lastApplyAtMs: Long,
        appliedIso: Int,
        appliedShutterNs: Long,
        targetIso: Int,
        targetShutterNs: Long
    ): Boolean {
        if (nowMs - lastApplyAtMs < MIN_INTERVAL_MS) return false
        // `appliedIso == 0` 短路在前：没写过就没有"差多少"可比，必须写一次
        val isoFar = appliedIso == 0 || abs(targetIso - appliedIso) > isoThreshold(appliedIso)
        val shutterFar = appliedShutterNs == 0L ||
            abs(targetShutterNs - appliedShutterNs) > shutterThreshold(appliedShutterNs)
        return isoFar || shutterFar
    }
}
