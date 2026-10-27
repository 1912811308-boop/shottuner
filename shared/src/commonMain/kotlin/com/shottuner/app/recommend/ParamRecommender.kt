package com.shottuner.app.recommend

import com.shottuner.app.model.CameraCaps
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.ManualParams
import com.shottuner.app.model.Recommendation
import com.shottuner.app.model.SceneType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 参数推荐核心。
 *
 * 核心思想：曝光是「总量」，参数是「分配」。
 *   总曝光量 E = ISO × 快门时间
 *   目标是把 E 调整到让平均亮度收敛到场景理想值，再在 ISO 与快门之间做约束分配。
 *
 * 两条工作路径：
 * 1. 冷启动（current == null）：以场景预设为锚点，按画面偏差给出绝对建议值。
 * 2. 闭环（current != null）：已知上一次写入的真实参数，可用反馈控制逐步收敛，
 *    阻尼系数 0.7 用于抑制来回震荡。这也是自动模式能真正稳定工作的前提。
 */
object ParamRecommender {

    private class Preset(
        val targetLuma: Float,
        val isoStart: Int,
        val shutterStartNs: Long,
        val isoCap: Int,
        val shutterFloorNs: Long,
        val shutterCeilNs: Long
    )

    private const val MS = 1_000_000L

    private val PRESETS: Map<SceneType, Preset> = mapOf(
        SceneType.NIGHT to Preset(
            targetLuma = 0.20f, isoStart = 1600, shutterStartNs = 33 * MS,
            isoCap = 6400, shutterFloorNs = 33 * MS, shutterCeilNs = 125 * MS
        ),
        SceneType.BACKLIT to Preset(
            targetLuma = 0.40f, isoStart = 100, shutterStartNs = 2 * MS,
            isoCap = 1600, shutterFloorNs = 2 * MS, shutterCeilNs = 16 * MS
        ),
        SceneType.PORTRAIT to Preset(
            targetLuma = 0.50f, isoStart = 100, shutterStartNs = 4 * MS,
            isoCap = 1600, shutterFloorNs = 4 * MS, shutterCeilNs = 16 * MS
        ),
        SceneType.LANDSCAPE to Preset(
            targetLuma = 0.46f, isoStart = 100, shutterStartNs = 2 * MS,
            isoCap = 800, shutterFloorNs = 2 * MS, shutterCeilNs = 33 * MS
        ),
        SceneType.INDOOR to Preset(
            targetLuma = 0.42f, isoStart = 400, shutterStartNs = 16 * MS,
            isoCap = 3200, shutterFloorNs = 20 * MS, shutterCeilNs = 66 * MS
        ),
        SceneType.ACTION to Preset(
            targetLuma = 0.45f, isoStart = 200, shutterStartNs = 1 * MS,
            isoCap = 6400, shutterFloorNs = 1 * MS, shutterCeilNs = 4 * MS
        ),
        SceneType.GENERAL to Preset(
            targetLuma = 0.45f, isoStart = 100, shutterStartNs = 4 * MS,
            isoCap = 1600, shutterFloorNs = 16 * MS, shutterCeilNs = 33 * MS
        )
    )

    fun recommend(
        stats: FrameStats,
        scene: SceneType,
        caps: CameraCaps,
        current: ManualParams? = null,
        confidence: Float = 0.5f
    ): Recommendation {
        val preset = PRESETS[scene] ?: PRESETS.getValue(SceneType.GENERAL)

        // 1. 计算曝光偏差（单位 EV）
        val target = preset.targetLuma
        val measured = stats.meanLuma.coerceIn(0.02f, 0.98f)
        var evDelta = log2(target / measured)

        // 2. 高光保护：溢出像素越多，越要压曝光
        val overRatio = stats.highlightClip
        if (overRatio > 0.04f) {
            evDelta -= min(0.9f, (overRatio - 0.04f) * 8f)
        }
        // 3. 暗部抬升：大面积死黑时补亮（逆光场景不补，避免把天空彻底曝废）
        if (scene != SceneType.NIGHT && scene != SceneType.BACKLIT) {
            if (stats.shadowClip > 0.20f) {
                evDelta += min(0.5f, (stats.shadowClip - 0.20f) * 2.0f)
            }
        }
        // 4. 闭环阻尼
        val damping = if (current == null) 1.0f else 0.7f
        val appliedEv = (evDelta * damping).coerceIn(-3.5f, 3.5f)

        // 5. 从锚点推导总曝光量并做分配
        val anchorIso: Double
        val anchorShutterNs: Double
        if (current == null) {
            anchorIso = preset.isoStart.toDouble()
            anchorShutterNs = preset.shutterStartNs.toDouble()
        } else {
            anchorIso = current.iso.toDouble()
            anchorShutterNs = current.shutterNs.toDouble()
        }

        val gain = 2.0.pow(appliedEv.toDouble())
        val totalExposure = anchorIso * anchorShutterNs / 1e9 * gain

        var shutterNs = (anchorShutterNs * gain)
            .coerceIn(preset.shutterFloorNs.toDouble(), max(preset.shutterCeilNs, preset.shutterFloorNs).toDouble())

        var iso = if (shutterNs <= 0.0) anchorIso else totalExposure / (shutterNs / 1e9)

        // ISO 上限：受场景噪声容忍与硬件能力双重约束
        val isoCap = min(preset.isoCap, caps.isoMax)
        val isoFloor = max(caps.isoMin, 50)
        if (iso > isoCap) {
            iso = isoCap.toDouble()
            // 剩余曝光量交还给快门（放慢）
            shutterNs = totalExposure / iso * 1e9
            shutterNs = shutterNs.coerceIn(
                preset.shutterFloorNs.toDouble(),
                max(preset.shutterCeilNs, preset.shutterFloorNs).toDouble()
            )
        } else if (iso < isoFloor) {
            iso = isoFloor.toDouble()
            shutterNs = totalExposure / iso * 1e9
            shutterNs = shutterNs.coerceIn(
                preset.shutterFloorNs.toDouble(),
                max(preset.shutterCeilNs, preset.shutterFloorNs).toDouble()
            )
        }

        // 硬件曝光范围钳制
        shutterNs = shutterNs.coerceIn(caps.exposureMinNs.toDouble(), caps.exposureMaxNs.toDouble())

        val finalIso = quantizeIso(iso.coerceIn(isoFloor.toDouble(), isoCap.toDouble()), isoFloor, isoCap)
        val finalShutter = quantizeShutter(
            shutterNs.roundToLong().coerceAtLeast(1_000L),
            caps.exposureMinNs,
            caps.exposureMaxNs
        )

        // 6. 评估实际可达到的曝光量，判断是否已到硬件天花板
        //    注意方向：曝光量 E ∝ ISO × 快门，快门越长曝光越多
        val achievedEv = log2(
            ((finalIso.toDouble() / anchorIso) * (finalShutter.toDouble() / anchorShutterNs)).toFloat()
        )
        val shortfall = appliedEv - achievedEv

        // 7. 白平衡：灰世界假设下的色温补偿
        val kelvin = estimateKelvin(stats)

        // 8. 三脚架判定
        val needsTripod = finalShutter > 1_000_000_000L / 25 && (scene == SceneType.NIGHT || scene == SceneType.INDOOR)

        // 9. 文案
        val tip = buildTip(scene, shortfall, needsTripod)
        val detail = buildDetail(stats, kelvin, scene)

        return Recommendation(
            scene = scene,
            targetLuma = target,
            evDelta = appliedEv,
            iso = finalIso,
            shutterNs = finalShutter,
            kelvin = kelvin,
            needsTripod = needsTripod,
            tip = tip,
            detail = detail,
            confidence = confidence
        )
    }

    /**
     * 由灰世界色偏反推色温。
     * 逻辑：meanR/meanB 偏大说明画面整体偏暖，需要更低色温（更冷）的设定去中和。
     * 该建议的前提是相机自动白平衡已经工作，输出的是「校正后仍存在的残余偏色」。
     */
    fun estimateKelvin(stats: FrameStats): Int {
        if (stats.meanR <= 0f || stats.meanB <= 0f) return 5500
        val cast = log2(stats.meanR / stats.meanB)
        val k = 5500 - cast * 1600
        return k.roundToInt().coerceIn(2500, 8500)
    }

    private fun buildTip(scene: SceneType, shortfall: Float, needsTripod: Boolean): String {
        if (shortfall > 0.7f) {
            return "光线不足，已到硬件上限：建议找支撑物或补光，否则只能靠后期提亮"
        }
        if (shortfall < -0.7f) {
            return "画面偏亮，正在压曝光以保住高光细节"
        }
        if (needsTripod) {
            return "快门已低于 1/25s，手持容易糊：建议靠墙或使用三脚架"
        }
        return when (scene) {
            SceneType.NIGHT -> "弱光场景：优先放慢快门保低 ISO，噪点更少"
            SceneType.BACKLIT -> "逆光场景：压曝光保天空，主体可后期提亮或用补光"
            SceneType.PORTRAIT -> "人像场景：用较快快门冻结微动，肤色更干净"
            SceneType.LANDSCAPE -> "风景场景：低 ISO 保细节，注意高光不要溢出"
            SceneType.INDOOR -> "室内暖光：先校色温再补曝光，避免越调越黄"
            SceneType.ACTION -> "运动抓拍：快门优先，宁可提高 ISO 也要冻结动作"
            SceneType.GENERAL -> "曝光接近理想值，可直接拍摄"
        }
    }

    private fun buildDetail(stats: FrameStats, kelvin: Int, scene: SceneType): String {
        val parts = ArrayList<String>(3)
        val dk = kelvin - 5500
        if (abs(dk) >= 250) {
            parts.add(if (dk < 0) "偏暖，建议色温降至 ${kelvin}K" else "偏冷，建议色温升至 ${kelvin}K")
        } else {
            parts.add("白平衡基本准确")
        }

        if (stats.highlightClip > 0.10f) parts.add("高光溢出 ${(stats.highlightClip * 100).roundToInt()}%，注意保留层次")
        if (stats.dynamicRange < 0.30f) parts.add("反差偏低，可能雾感或镜头脏污")
        if (stats.saturation < 0.12f && scene != SceneType.NIGHT) parts.add("色彩淡，可适度提高饱和度")

        return parts.joinToString("；")
    }

    private fun log2(x: Float): Float {
        if (x <= 0f) return 0f
        return (ln(x.toDouble()) / ln(2.0)).toFloat()
    }

    /** 相机可设置的 ISO 档位（1/3 档序列） */
    private val ISO_STOPS = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        8000, 10000, 12800
    )

    /** 快门档位中「快于 1 秒」的部分，用分母表示，1/60 这类读数才符合相机拨盘。 */
    private val FAST_SHUTTER_DENOMS = intArrayOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
        800, 640, 500, 400, 320, 250, 200, 160, 125, 100,
        80, 60, 50, 40, 30, 25, 20, 15, 13, 10,
        8, 6, 5, 4, 3
    )

    /** 快门档位中「慢于等于 0.4 秒」的部分，单位秒 */
    private val SLOW_SHUTTER_STOPS = doubleArrayOf(
        0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2,
        4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0
    )

    /**
     * 把连续快门吸附到相机拨盘上真实存在的档位。
     *
     * 这条规则是给「照着抄」准备的：用户要在小米专业模式的下拉列表里找这个数，
     * 建议 1/63 秒等于让用户在列表里找不到东西。宁可差 1/3 档，也要可执行。
     */
    fun quantizeShutter(ns: Long, minNs: Long, maxNs: Long): Long {
        val target = ns.coerceIn(1_000L, 30_000_000_000L) / 1e9
        var best = target
        var bestDist = Double.MAX_VALUE
        fun consider(sec: Double) {
            if (sec <= 0.0) return
            val d = abs(ln(sec / target))
            if (d < bestDist) {
                bestDist = d
                best = sec
            }
        }
        for (d in FAST_SHUTTER_DENOMS) consider(1.0 / d)
        for (s in SLOW_SHUTTER_STOPS) consider(s)

        val snapped = (best * 1e9).toLong()
        return snapped.coerceIn(minNs, maxNs)
    }

    /**
     * 把连续 ISO 吸附到最近档位。
     * 建议「ISO 72」在真机上根本设不了，宁可差 1/3 档也不能给不可执行的值。
     */
    fun quantizeIso(value: Double, lo: Int, hi: Int): Int {
        var best = -1
        var bestDist = Double.MAX_VALUE
        val safe = if (value < 1e-6) 1e-6 else value
        for (stop in ISO_STOPS) {
            if (stop < lo || stop > hi) continue
            val d = abs(ln(stop / safe) / ln(2.0))
            if (d < bestDist) {
                bestDist = d
                best = stop
            }
        }
        return if (best > 0) best else value.roundToInt().coerceIn(lo, hi)
    }
}
