package com.shottuner.app.video

import com.shottuner.app.device.BrandGuide
import com.shottuner.app.device.CameraMode
import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import com.shottuner.app.text.NumFmt
import kotlin.math.roundToInt

/** 拍什么，决定了录像的一切参数。这比「用什么镜头」重要得多。 */
enum class VideoIntent(val label: String, val oneLine: String) {
    WALK("走动跟拍", "边走边拍，手持容易晃"),
    TALK("口播讲解", "手机固定，人对着镜头"),
    SPORT("运动抓拍", "跑动、宠物、小孩"),
    CONCERT("暗光现场", "演唱会、夜市、室内活动"),
    CINEMA("风光运镜", "三脚架或稳定器，缓慢平移"),
    SLOWMO("慢动作", "水花、跳跃、爆裂瞬间"),
    TIMELAPSE("延时摄影", "云、车流、日落"),
    SCAN("翻拍文档", "文件、屏幕、白板")
}

/**
 * 一条完整的录像方案。
 *
 * 录像和拍照是两套完全不同的逻辑，最容易踩的坑是：
 * 拍照可以为了亮度把快门放到 1/10；录像不行，快门必须跟着帧率走。
 */
class VideoPlan(
    val intent: VideoIntent,
    /** 8K / 4K / 1080P / 720P */
    val tier: String,
    val fps: Int,
    /** 建议快门，如 "1/60"。不支持手动视频时为空 */
    val shutterText: String,
    val shutterNs: Long,
    val isoHint: String,
    val bitrateMbps: Int,
    val stabilization: String,
    val useLog: Boolean,
    val logNote: String,
    val focus: String,
    val tips: List<String>,
    val audioTip: String,
    val pathHint: String,
    /** 该方案是否因为设备能力被降级过 */
    val downgraded: Boolean
) {
    val resLabel: String get() = "$tier ${fps}fps"

    /** 复制到剪贴板的一行文本 */
    fun clipboardText(): String =
        "$resLabel · ${bitrateMbps}Mbps · 快门 $shutterText · $stabilization${if (useLog) " · LOG" else ""}"
}

/**
 * 录像推荐引擎。
 *
 * 核心约束只有一条，但几乎所有人都在违反它——**180 度快门**：
 * 快门时间应当是帧间隔的一半，24fps 用 1/48、30fps 用 1/60、60fps 用 1/120。
 * 违反的后果不是"不好看"，而是运动物体出现频闪或抖动感（rolling shutter judder）。
 * 想在不改快门的前提下压暗画面，应该上 ND 减光镜，而不是拨快门。
 */
object VideoRecommender {

    fun plan(
        intent: VideoIntent,
        profile: DeviceProfile,
        stats: FrameStats?,
        scene: SceneType
    ): VideoPlan {
        val v = profile.video
        val dark = (stats?.meanLuma ?: 0.45f) < 0.28f || scene == SceneType.NIGHT

        val base = when (intent) {
            VideoIntent.WALK -> walk(profile, dark)
            VideoIntent.TALK -> talk(profile)
            VideoIntent.SPORT -> sport(profile, dark)
            VideoIntent.CONCERT -> concert(profile)
            VideoIntent.CINEMA -> cinema(profile)
            VideoIntent.SLOWMO -> slowmo(profile)
            VideoIntent.TIMELAPSE -> timelapse(profile)
            VideoIntent.SCAN -> scan(profile)
        }

        // 无专业视频的机型（如 Pixel）：快门由相机自决，只给曝光补偿与锁定建议
        if (!profile.supportsManualVideo) {
            return base.copyWithoutShutter(
                "自动",
                "这台机器的录像没有手动快门。用「点按对焦后长按锁定 AE/AF」来固定曝光，靠上下滑动曝光补偿调亮暗"
            )
        }
        return base
    }

    // ---------- 各意图的具体方案 ----------

    private fun walk(p: DeviceProfile, dark: Boolean): VideoPlan {
        // 走动跟拍：帧率给到 60 是为了两件事——防抖算法有更多帧可用，后期还能慢放到 30。
        // 但暗光下 60fps 每帧只分到一半光，噪点翻倍，所以暗光降回 30。
        val wantFps = if (dark) 30 else 60
        val tier = pickTier(listOf("1080P", "4K"), p, wantFps)
        val fps = clampFps(p, tier, wantFps)
        return build(
            intent = VideoIntent.WALK,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, null),
            stabilization = "超级防抖 / 最强档",
            isoHint = if (dark) "自动，宁可 ISO 3200 也别降帧率" else "尽量 ≤ 800",
            log = false,
            logNote = "走动时不开 LOG：手持拍的大多数素材剪不动，直出反而更省事",
            focus = "连续自动对焦（AF-C）",
            tips = listOfNotNull(
                "走动跟拍用 1080P 而不是 4K：防抖裁切后 1080P 仍有足够细节，4K 会让裁切更明显、文件大三倍",
                "两腿微屈、脚跟先落地，把手机贴近身体，比开任何防抖都有效",
                "开头先稳稳停 2 秒再起步，结尾也停住，剪辑时才有干净的头尾",
                if (dark) "暗光下降到了 30fps：慢门会让走动时拖影，站定再拍更好" else "光线充足，60fps 可以放心用"
            ),
            audio = "别用手指挡麦克风，边走边说话时手机离嘴 30cm 内",
            downsample = tier != "1080P"
        )
    }

    private fun talk(p: DeviceProfile): VideoPlan {
        // 口播没有运动，帧率越低越有电影感，但 24fps 对普通人拍口播不友好（口型与声音的容错小），
        // 平台二次压缩后 24 也容易卡顿感。折中给 30。
        val tier = pickTier(listOf("4K", "1080P"), p, 30)
        val fps = clampFps(p, tier, 30)
        return build(
            intent = VideoIntent.TALK,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, null),
            stabilization = "关闭",
            isoHint = "手动锁死，先定 ISO 200 再调灯",
            log = p.video.supports10Bit,
            logNote = if (p.video.supports10Bit)
                "支持 10bit，拍 LOG 后期有调色空间；如果只是直接发，别开，画面会发灰"
            else "不开 LOG：8bit 素材套 LOG 会出现色带，且后期一提对比度噪点就出来",
            focus = "单次对焦后锁定（AF-S + AE/AF 锁定）",
            tips = listOf(
                "手机固定住就用三脚架或堆书，任何防抖在固定机位都会引入缓慢漂移",
                "镜头与眼睛同高，别俯拍也别仰拍，这个高度差最影响可信度",
                "背景离人至少 1.5 米，才有自然的虚化层次",
                "身后补一盏灯打到脸上，比开美颜有用"
            ),
            audio = "手机别用支架夹在正下方，会收到桌面震动；领夹麦是最划算的升级",
            downsample = false
        )
    }

    private fun sport(p: DeviceProfile, dark: Boolean): VideoPlan {
        // 运动要冻结主体，快门必须快到 1/250 以上，意味着要么高帧率要么高 ISO。
        val wantFps = if (dark) 60 else 120
        val tier = pickTier(listOf("1080P", "4K"), p, wantFps.coerceAtMost(60))
        val fps = clampFps(p, tier, wantFps.coerceAtMost(60))
        return build(
            intent = VideoIntent.SPORT,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, null),
            stabilization = if (dark) "标准防抖" else "超级防抖",
            isoHint = if (dark) "自动，允许拉高" else "≤ 1600",
            log = false,
            logNote = "运动素材量最大，不开 LOG，省得后期处理不动",
            focus = "连续对焦 + 点击主体锁定",
            tips = listOf(
                "运动场景快门必须 ≥ 1/250，否则冻结不了动作——这是比帧率更硬的约束",
                "提前半秒按录制，别等动作发生才按",
                if (dark) "暗光下别追 120fps：每帧只有 1/250 秒的光，画质会崩" else "光线足，120fps 后期可慢放 4 倍",
                "横向追拍时身体转向，别只扭手腕"
            ),
            audio = "运动时的风噪很难消，后期加音乐，别指望原声",
            downsample = false
        )
    }

    private fun concert(p: DeviceProfile): VideoPlan {
        // 暗光现场是最难的：既要 30fps 保证亮度，又要压住舞台高光不溢出。
        val tier = pickTier(listOf("1080P", "4K"), p, 30)
        val fps = clampFps(p, tier, 30)
        return build(
            intent = VideoIntent.CONCERT,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, 1.3),
            stabilization = "标准防抖",
            isoHint = "自动，别锁低 ISO",
            log = false,
            logNote = "暗光下绝不开 LOG：它把对比度压平，噪点全都浮出来，后期一提更明显",
            focus = "对焦锁定在舞台，别让它来回找",
            tips = listOf(
                "先点一下主体把曝光和焦点锁住，否则舞台灯一变画面就整体跳一下",
                "手指在取景框上往下滑一点压曝光，保住灯光的颜色，人脸暗点能接受",
                "别用最强的超级防抖：它裁切更多、算法更激进，暗光下噪点明显",
                "找固定的支点——栏杆、座椅靠背，比手持稳十倍",
                "关掉手机的「影院模式 / 杜比视界」，那类模式会自动改亮度，现场光一变就翻车"
            ),
            audio = "演唱会原声是核心，别开降噪；录完别裁剪过头，低频会被切掉",
            downsample = false
        )
    }

    private fun cinema(p: DeviceProfile): VideoPlan {
        val tier = pickTier(listOf("4K", "1080P"), p, 30)
        val fps = clampFps(p, tier, 30)
        return build(
            intent = VideoIntent.CINEMA,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, 1.5),
            stabilization = "关闭（用三脚架）",
            isoHint = "锁低 ISO（100–400），靠 ND 或慢门控制亮度",
            log = p.video.supports10Bit,
            logNote = "有 10bit 就开 LOG，风光的高光和暗部层次全靠它；直出的话不开",
            focus = "对焦到中景（超焦距附近），锁定",
            tips = listOf(
                "运镜只做一种动作：横移、推进、升起，选一个做到底，混合运动看起来业余",
                "速度控制在每秒不超过画面宽度的 1/5，太快观众看不清",
                "地平线必须水平，用取景器的水平仪，别凭感觉",
                "日出日落前后 20 分钟是唯一值得等的时段，中间时段光硬、反差大"
            ),
            audio = "风光片基本都配乐，原声只是氛围，录个环境声就够",
            downsample = false
        )
    }

    private fun slowmo(p: DeviceProfile): VideoPlan {
        // 慢动作的帧率是硬条件：没有 120fps 就做不出丝滑的 1/4 慢放。
        val has120 = p.video.maxHighSpeedFps >= 120 || p.video.fps1080 >= 120
        val has240 = p.video.maxHighSpeedFps >= 240
        val wantFps = if (has240) 240 else if (has120) 120 else 60
        val tier = "1080P"
        val fps = if (p.video.fps1080 >= wantFps) wantFps else 60
        return build(
            intent = VideoIntent.SLOWMO,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, 1.5),
            stabilization = "标准防抖",
            isoHint = "自动，光线必须充足",
            log = false,
            logNote = "慢动作不开 LOG：帧率已经让每帧很暗，再压对比度就没法看了",
            focus = "预对焦到落点，然后锁定",
            tips = listOfNotNull(
                "补光或白天拍，慢动作最吃光：1/500 秒的快门意味着需要极强的照度",
                "先预对焦到动作会经过的位置，对焦跟不上是慢动作废片的第一原因",
                if (has240) "支持 240fps，可慢放 8 倍，水花和爆裂瞬间用这个"
                else if (has120) "最高 120fps，可慢放 4 倍；再慢就要靠后期插帧，会有果冻感"
                else "这台机器慢动作能力有限，改用 60fps 后期慢放 2 倍，画质损失更小",
                "录制前先按半秒，动作结束后再多录一秒，慢动作才有完整的起势和收势"
            ),
            audio = "慢动作通常不带声音，直接配乐",
            downsample = false
        )
    }

    private fun timelapse(p: DeviceProfile): VideoPlan {
        val tier = pickTier(listOf("4K", "1080P"), p, 30)
        val fps = clampFps(p, tier, 30)
        return build(
            intent = VideoIntent.TIMELAPSE,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, null),
            stabilization = "关闭",
            isoHint = "手动锁定 ISO 与白平衡",
            log = false,
            logNote = "延时不开 LOG：素材是逐帧拼的，统一色调更重要",
            focus = "手动对焦到无穷远，锁定",
            tips = listOf(
                "必须固定机位，一点风都会让画面抖",
                "手动锁定 ISO 和色温，否则每帧亮度色温都不一样，成片会闪",
                "间隔时间：云 1–3 秒、车流 2–5 秒、日落 5–10 秒、星轨 15–30 秒",
                "算一下总时长：间隔 × 帧数 ÷ 输出帧率 = 成片秒数，别拍两小时只出 3 秒"
            ),
            audio = "延时没有原声，配乐即可",
            downsample = false
        )
    }

    private fun scan(p: DeviceProfile): VideoPlan {
        val tier = pickTier(listOf("4K", "1080P"), p, 30)
        val fps = clampFps(p, tier, 30)
        return build(
            intent = VideoIntent.SCAN,
            profile = p,
            tier = tier,
            fps = fps,
            bitrate = bitrateFor(tier, fps, 1.4),
            stabilization = "关闭",
            isoHint = "锁低 ISO，靠补光",
            log = false,
            logNote = "翻拍不需要 LOG，需要的是均匀的光",
            focus = "单次对焦 + 锁定",
            tips = listOf(
                "镜头平面必须与纸面平行，歪一点就有一端虚",
                "两侧各一盏白光，形成 45 度夹角，避免反光和阴影",
                "屏幕翻拍会有摩尔纹，把手机稍微倾斜 5 度能压掉大部分",
                "4K 拍文档才够裁切，1080P 看不清小字"
            ),
            audio = "不需要音频，或后期配音",
            downsample = false
        )
    }

    // ---------- 通用工具 ----------

    /**
     * 180 度快门：快门 = 1/(2 × 帧率)。
     * 再吸附到相机拨盘档位，保证用户能真的拨出来。
     */
    fun shutterForFps(fps: Int): Long {
        if (fps <= 0) return 1_000_000_000L / 60
        return 1_000_000_000L / (fps * 2)
    }

    fun shutterLabel(fps: Int): String {
        val ns = shutterForFps(fps)
        val sec = ns / 1e9
        return if (sec >= 1.0) NumFmt.one(sec) + "s" else "1/${(1.0 / sec).roundToInt()}"
    }

    private fun pickTier(prefer: List<String>, p: DeviceProfile, needFps: Int): String {
        for (t in prefer) {
            if (p.video.canRecord(t) && p.video.fpsAtTier(t) >= needFps) return t
        }
        for (t in prefer) {
            if (p.video.canRecord(t)) return t
        }
        return "1080P"
    }

    private fun clampFps(p: DeviceProfile, tier: String, want: Int): Int {
        val max = p.video.fpsAtTier(tier)
        if (max >= want) return want
        // 退到最常见的一档，而不是取一个奇怪的中间值——拨盘和菜单里只有标准档
        val fallback = when {
            max >= 120 -> 120
            max >= 60 -> 60
            max >= 30 -> 30
            else -> max.coerceAtLeast(24)
        }
        return fallback
    }

    /**
     * 码率建议（Mbps）。
     *
     * 依据：消费级 H.264 大约每像素每帧 0.08–0.12 bit。
     * 手机实际码率多数由厂商定死，只有部分专业视频模式可调，
     * 这个值主要用于「导出/压缩时别低于它」，以及判断空间够不够。
     */
    private fun bitrateFor(tier: String, fps: Int, mul: Double?): Int {
        val base = when (tier) {
            "8K" -> 200
            "4K" -> 60
            "1080P" -> 18
            "720P" -> 9
            else -> 18
        }
        val scaled = base * (fps / 30.0)
        val extra = mul ?: 1.0
        return (scaled * extra).roundToInt().coerceAtLeast(8)
    }

    private fun build(
        intent: VideoIntent,
        profile: DeviceProfile,
        tier: String,
        fps: Int,
        bitrate: Int,
        stabilization: String,
        isoHint: String,
        log: Boolean,
        logNote: String,
        focus: String,
        tips: List<String>,
        audio: String,
        downsample: Boolean
    ): VideoPlan {
        val shutterNs = shutterForFps(fps)
        val path = BrandGuide.hint(profile.brand, CameraMode.VIDEO_PRO)
        val extra = if (profile.video.supports10Bit) "" else ""
        return VideoPlan(
            intent = intent,
            tier = tier,
            fps = fps,
            shutterText = shutterLabel(fps),
            shutterNs = shutterNs,
            isoHint = isoHint,
            bitrateMbps = bitrate,
            stabilization = stabilization,
            useLog = log,
            logNote = logNote + extra,
            focus = focus,
            tips = tips,
            audioTip = audio,
            pathHint = path,
            downgraded = downsample
        )
    }

    private fun VideoPlan.copyWithoutShutter(label: String, note: String): VideoPlan = VideoPlan(
        intent = intent,
        tier = tier,
        fps = fps,
        shutterText = label,
        shutterNs = 0L,
        isoHint = isoHint,
        bitrateMbps = bitrateMbps,
        stabilization = stabilization,
        useLog = useLog,
        logNote = logNote,
        focus = focus,
        tips = listOf(note) + tips,
        audioTip = audioTip,
        pathHint = pathHint,
        downgraded = downgraded
    )
}
