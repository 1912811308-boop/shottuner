package com.shottuner.app.feature

import com.shottuner.app.device.BrandGuide
import com.shottuner.app.device.CameraMode
import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import com.shottuner.app.text.NumFmt
import kotlin.math.roundToInt

/** 建议的三种语气：现在该用、现在别用、值得知道 */
enum class AdviceKind { DO, AVOID, KNOW }

class FeatureSuggestion(
    val kind: AdviceKind,
    val title: String,
    val reason: String,
    val path: String,
    val priority: Int
)

/**
 * 功能顾问：在「参数」之外，回答「这会儿该开哪个模式」。
 *
 * 与参数推荐的分工：参数解决「同一个模式里怎么调」，功能解决「该不该换模式」。
 * 后者其实更影响成片——拿夜景模式去拍跑动的人，再怎么调参数都是废片。
 *
 * 所有规则都挂在可测的画面特征或可读的硬件事实上，
 * 没有「AI 猜你喜欢」这种没法验证的东西。
 */
object FeatureAdvisor {

    fun advise(
        profile: DeviceProfile,
        stats: FrameStats?,
        scene: SceneType,
        /** 当前推荐是否需要三脚架；为 true 时补一条「别用手指戳快门」 */
        needsTripod: Boolean = false
    ): List<FeatureSuggestion> {
        val out = ArrayList<FeatureSuggestion>(6)
        val dark = (stats?.meanLuma ?: 0.45f) < 0.26f || scene == SceneType.NIGHT
        val highContrast = (stats?.let { it.p95 - it.p05 } ?: 0f) > 0.62f
        val clipping = (stats?.highlightClip ?: 0f) > 0.04f
        val moving = scene == SceneType.ACTION

        // 1. 弱光 + 静止主体 → 手持夜景（多帧合成是手机最强的一项能力）
        if (dark && !moving) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "开手持夜景",
                    "画面平均亮度 ${pct(stats?.meanLuma)}%，处于多帧合成最占优的区间：连拍多帧对齐叠加，噪点比单帧低一到两档，动态范围也更宽",
                    BrandGuide.hint(profile.brand, CameraMode.NIGHT),
                    100
                )
            )
        }

        // 1b. 夜景长曝：按快门那一下的震动比想象中大，倒计时/音量键是最便宜的防抖
        if (dark && needsTripod) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "按快门别用手指戳",
                    "用 2 秒倒计时，或用手表 / 耳机音量键：手指离开屏幕后再曝光，长曝才不糊",
                    "相机设置里打开 2 秒倒计时；有手表或耳机就按音量键当快门",
                    98
                )
            )
        }

        // 2. 弱光 + 运动主体 → 必须避开夜景模式。这是最容易被忽略的一条。
        if (dark && moving) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.AVOID,
                    "别用夜景模式",
                    "夜景靠多帧对齐合成，主体一动就会拖影，出来的是一团糊。改用专业模式：快门 1/125 以上，ISO 让它涨",
                    BrandGuide.hint(profile.brand, CameraMode.PHOTO_PRO),
                    99
                )
            )
        }

        // 3. 高光溢出 → HDR 或压曝光
        if (clipping) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "开 HDR",
                    "已有 ${pct(stats?.highlightClip)}% 的像素顶到纯白，这部分信息在单帧里已经没了。HDR 靠欠曝帧把高光找回来",
                    BrandGuide.hint(profile.brand, CameraMode.HDR),
                    90
                )
            )
        }

        // 4. 大光比 + 有 RAW → 值得开 RAW 做后期
        if (profile.video.supportsRaw && highContrast && !moving) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "可以开 RAW",
                    "画面明暗跨度大（P95-P05 = ${pct(stats?.let { it.p95 - it.p05 })}%），RAW 保留了 10bit 以上的原始数据，后期拉回高光暗部比 JPEG 余地大得多",
                    BrandGuide.hint(profile.brand, CameraMode.RAW),
                    70
                )
            )
        }

        // 5. 人像 + 背景杂乱 → 人像模式
        val skin = stats?.skinRatio ?: 0f
        val busy = (stats?.edgeDensity ?: 0f) > 0.28f
        if (skin > 0.05f && busy) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "用人像模式",
                    "检测到肤色占 ${pct(skin)}%，背景纹理偏密（边缘密度 ${pct(stats?.edgeDensity)}%）。人像模式的虚化能把背景压下去，主体更立",
                    BrandGuide.hint(profile.brand, CameraMode.PORTRAIT),
                    80
                )
            )
        }

        // 6. 弱光下用长焦 → 用数据劝退
        val main = profile.main
        if (main != null && dark) {
            val tele = profile.lenses
                .filter { it.equivFocalMm > main.equivFocalMm * 1.5f }
                .maxByOrNull { it.sensorArea }
            if (tele != null && tele.sensorArea > 0f && tele.sensorArea < main.sensorArea * 0.5f) {
                val ratio = (tele.sensorArea / main.sensorArea * 100).roundToInt()
                val stops = kotlin.math.ln(main.sensorArea.toDouble() / tele.sensorArea) /
                    kotlin.math.ln(2.0)
                out.add(
                    FeatureSuggestion(
                        AdviceKind.AVOID,
                        "弱光别切 ${tele.equivFocalMm}mm 长焦",
                        "长焦的传感器面积只有主摄的 $ratio%，进光量差约 ${NumFmt.one(stops)} 档，" +
                            "同样亮度下噪点明显更多。宁可拍完再用主摄裁切",
                        BrandGuide.hint(profile.brand, CameraMode.ZOOM),
                        85
                    )
                )
            }
        }

        // 7. 主摄等效焦距偏广 → 拍人的焦段建议
        if (skin > 0.03f && profile.equivFocalMm in 18..28) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "拍人切到 ${(profile.equivFocalMm * 2)}mm 左右",
                    "主摄等效 ${profile.equivFocalMm}mm 属于广角，贴太近脸会变形（鼻子变大、脸变宽）。退两步用 2 倍，比例才自然",
                    BrandGuide.hint(profile.brand, CameraMode.ZOOM),
                    60
                )
            )
        }

        // 8. 逆光：按「点测光 → 找反光面 → 转 45°」的先后顺序给三步。
        //    优先级 96/95/94 保证输出稳定；不使用闪光灯直打（手机闪光灯指数低、色温硬）。
        if (scene == SceneType.BACKLIT) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "逆光 ① 点脸测光",
                    "点脸测光，曝光补偿 -0.7～-1.0，先保高光；天空可以亮，脸不能黑",
                    "取景框上点一下人脸，出现对焦框后向下滑到 -0.7～-1.0 EV",
                    96
                )
            )
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "逆光 ② 找反光面",
                    "脸还是黑 → 让人面向最近的白墙 / 浅色地面，或穿浅色衣服，用漫反射把光补回脸上",
                    "优先找窗边白墙或浅色地面，让人侧对反光面站",
                    95
                )
            )
            out.add(
                FeatureSuggestion(
                    AdviceKind.DO,
                    "逆光 ③ 转 45° 侧逆光",
                    "转 45° 侧逆光，让太阳留在画面外；脸有侧光，明暗过渡也更自然",
                    "人不动，你往侧面走约 45°，直到太阳不在取景框里",
                    94
                )
            )
        }

        // 9. 录像能力说明：只在设备真的强时才提
        if (profile.video.fps4k >= 60) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "支持 4K 60fps",
                    "运动、走动、宠物用它，后期可无损慢放到 30fps；静态口播反而应该是 4K 30fps，帧率低一半、文件小一半、暗光更干净",
                    BrandGuide.hint(profile.brand, CameraMode.VIDEO_PRO),
                    40
                )
            )
        }

        if (profile.video.maxHighSpeedFps >= 120) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "慢动作最高 ${profile.video.maxHighSpeedFps}fps",
                    "可慢放 ${profile.video.maxHighSpeedFps / 30} 倍。慢动作最吃光：每帧曝光只有 1/${profile.video.maxHighSpeedFps * 2} 秒，室内基本拍不了",
                    BrandGuide.hint(profile.brand, CameraMode.SLOW_MO),
                    35
                )
            )
        }

        if (!profile.supportsManualPhoto) {
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "这台机器没有专业模式",
                    "系统不提供给第三方写入曝光参数，也没有厂商的专业模式入口。下面的建议值是「该往哪个方向调」，用曝光补偿滑杆去逼近",
                    "取景框上点一下 → 上下滑动曝光补偿滑杆",
                    30
                )
            )
        }

        return out.sortedByDescending { it.priority }
    }

    /** 没有画面时给一份「这台机器能玩什么」，用于首次打开 */
    fun capabilitySheet(profile: DeviceProfile): List<FeatureSuggestion> {
        val out = ArrayList<FeatureSuggestion>(5)
        val v = profile.video
        out.add(
            FeatureSuggestion(
                AdviceKind.KNOW,
                "主摄 ${profile.main?.label ?: "信息缺失"}",
                "等效 ${profile.equivFocalMm}mm，安全快门约 1/${(1_000_000_000L / profile.handheldSafeShutterNs)} 秒。手机主摄多是 23–24mm 的广角，拍人别贴太近",
                BrandGuide.hint(profile.brand, CameraMode.ZOOM),
                90
            )
        )
        if (profile.lenses.size > 1) {
            val list = profile.lenses.joinToString(" / ") { "${it.equivFocalMm}mm" }
            out.add(
                FeatureSuggestion(
                    AdviceKind.KNOW,
                    "后置 ${profile.lenses.size} 颗镜头：$list",
                    "优先用光学焦段，屏幕上点数字切换；双指放大是裁切，画质会掉",
                    BrandGuide.hint(profile.brand, CameraMode.ZOOM),
                    80
                )
            )
        }
        out.add(
            FeatureSuggestion(
                AdviceKind.KNOW,
                "录像最高 ${v.maxLabel} ${v.fpsAtTier(v.maxLabel)}fps",
                buildString {
                    append("1080P 最高 ${v.fps1080}fps")
                    if (v.fps4k > 0) append(" · 4K 最高 ${v.fps4k}fps")
                    if (v.maxHighSpeedFps > 0) append(" · 慢动作 ${v.maxHighSpeedFps}fps")
                    append(if (v.hasOis) " · 有光学防抖" else " · 无光学防抖")
                    append(if (v.supports10Bit) " · 支持 10bit" else " · 仅 8bit")
                },
                BrandGuide.hint(profile.brand, CameraMode.VIDEO_PRO),
                70
            )
        )
        out.add(
            FeatureSuggestion(
                AdviceKind.KNOW,
                "ISO ${profile.caps.isoMin}–${profile.caps.isoMax} · 快门最慢 ${formatLong(profile.caps.exposureMaxNs)}",
                if (profile.supportsManualPhoto)
                    "手动曝光可用。低于 1/${(1_000_000_000L / profile.handheldSafeShutterNs)} 秒就必须找支撑"
                else "不支持手动曝光写入，只能用曝光补偿逼近建议值",
                BrandGuide.hint(profile.brand, CameraMode.PHOTO_PRO),
                60
            )
        )

        // 专业模式的两条「知道就好」：都不是参数，但能避免关键时刻拍废。
        out.add(
            FeatureSuggestion(
                AdviceKind.KNOW,
                "长按对焦框可锁 AE/AF",
                "逆光、舞台、隔玻璃时先用它：锁定后重新构图，曝光和对焦都不会跑",
                "取景框长按 → 出现锁图标后再构图",
                55
            )
        )
        out.add(
            FeatureSuggestion(
                AdviceKind.KNOW,
                "RAW+ 不是真 RAW 单帧",
                "它是一张亮 + 一张暗的机内合成：动态范围大，但不能当 14bit 原始文件调",
                "专业模式 → RAW+（部分机型叫 RAW 增强）",
                52
            )
        )
        return out
    }

    private fun pct(v: Float?): Int = ((v ?: 0f) * 100).roundToInt()

    private fun formatLong(ns: Long): String {
        val sec = ns / 1e9
        return if (sec >= 1.0) NumFmt.fixed(sec, 0) + "s" else "1/${(1.0 / sec).roundToInt()}s"
    }
}
