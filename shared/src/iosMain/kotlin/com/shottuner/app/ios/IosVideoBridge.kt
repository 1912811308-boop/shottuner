// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 「录像方案」的跨语言门面。
//
// ★ 为什么需要它
//   `VideoRecommender`（commonMain）是「拍什么 → 用什么分辨率 / 帧率 / 快门 / 码率」
//   的整套规则，Android 侧一直在用，iOS 完全够不着。iOS 版此前只能取景拍照，
//   录像页是一张白纸。而录像恰恰是最容易踩 180 度快门坑的地方
//   （快门必须跟着帧率走，30fps 配 1/60、60fps 配 1/120）。
//
// ★ 为什么意图用 index 传，而不是把 `VideoIntent` 枚举导出
//   `VideoIntent` 是**带构造参数的枚举**（`VideoIntent(val label, val oneLine)`），
//   跨语言之后 ObjC 侧会变成一整套 `SharedVideoIntent` 类实例，
//   Swift 侧要拿它就得先 import 这个类型、再按 `SharedVideoIntent.walk` 这种方式取 ——
//   而本仓的跨语言铁律是**枚举不过边界**（`AdviceKind.DO` → `AdviceKind.do`
//   要反引号，是同一个坑的更恶劣版本）。
//   ⇒ 用 `0 until intentCount()` 的下标，Swift 侧存一个 `Int`（`@State var intent: Int`），
//     标签和一句话说明分别用 [IosVideoBridge.intentLabelAt] /
//     [IosVideoBridge.intentOneLineAt] 取。下标顺序由 Kotlin 的枚举声明顺序决定，
//     两端永远不会各自排一套。
//
// ★ 为什么文案全部在 Kotlin 侧拼好
//   `VideoPlan` 的 `tips` / `logNote` / `isoHint` / `audioTip` / `pathHint` 全是
//   common 侧写好的中文动作口诀，本文件只搬运，**一个字都不重新拼**。
//   两端同一份文案，改口径只改 commonMain 一处。
//
// ★ `clipboardText` 为什么不在 Swift 里拼
//   `VideoPlan.clipboardText()` 是 common 侧现成的方法，格式是
//   「4K 30fps · 60Mbps · 快门 1/60 · 关闭（用三脚架） · LOG」。
//   若 Swift 自己按字段拼一遍，两端复制出来的文本会慢慢不一样
//   （少一个空格、LOG 的位置不同），而这种差异没人会去对账。
//   ⇒ 原样透传，Swift 直接塞 `UIPasteboard.general.string`。
//   ★ 文本里**可能带换行**（`copyWithoutShutter` 会把「没有手动快门」那段说明
//     塞进 tips；`stabilization` 也可能带括号说明），原样透传、不要 trim、
//     不要按行拆分再重排。
//
// ★ `IosVideoPlanDto.tips` 是 `List<String>` —— 跨语言铁律的**唯一例外**
//   铁律是「`List<T>` 不过边界」（元素装箱 + 拷贝 + Swift 侧难用）。
//   但 `List<String>` 是例外，而且只有这一处允许：
//   `String` 在 Kotlin/Native 上映射成 `NSString`，而 `NSString` 本来就是类簇、
//   **不会被装箱成 `KotlinString` 之类的中介对象**；`List<String>` 因此直接桥接成
//   `NSArray<NSString>`，Swift 侧看到的就是原生 `[String]`，不需要任何转换代码。
//   tips 是「固定几条、顺序敏感、要逐条渲染成小节」的展示文本，数量 4~6 条，
//   用索引访问器反而会让 Swift 侧多写一层 `(0..<count).map { tipsAt(index: $0) }`
//   的胶水 —— 那个胶水是纯负担，没有换来任何安全性。
//   ★ 对比：**对象数组仍然禁止**。`List<VideoPlan>` / `List<ShotRecord>` 会走
//     `NSArray` + 逐个对象桥接，元素里再嵌数组就层层拷贝，且 Swift 侧拿到的是
//     `[SharedVideoPlan]` 这种带 framework 前缀的类型名。
//     判据是：**元素是 `String` / 标量才允许例外，元素是自定义类一律用索引访问器。**
//
// ★ 越界不抛异常
//   `intentIndex` 越界（含负数）时 [IosVideoBridge.plan] 返回 null。
//   Swift 侧 `try` 不了 Kotlin 异常 —— 真抛出去就是 `NSException` 打穿到
//   Swift 的 `try!` 或直接终止进程，而这里只是一个下拉框下标。
//   同理 `intentLabelAt` / `intentOneLineAt` 越界返回空串（Swift 侧显示为空，
//   不会崩，也不会显示上一个意图的标签）。

package com.shottuner.app.ios

import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.video.VideoIntent
import com.shottuner.app.video.VideoPlan
import com.shottuner.app.video.VideoRecommender

/**
 * 一条录像方案的跨语言形状 —— `VideoPlan` 的拍平版。
 *
 * | 字段 | Swift 落点 | 口径 |
 * |---|---|---|
 * | [intentLabel] | 卡片标题 | `VideoIntent.label`，如「走动跟拍」 |
 * | [tier] | 分辨率档 | `4K` / `1080P` / `720P`（永远是这几档，不会是中间值） |
 * | [fps] | 帧率 | 已按设备能力钳过（见 `VideoRecommender.clampFps`） |
 * | [resLabel] | 组合标签 | 现成的 `"$tier ${fps}fps"`，**不要再自己拼**「4K · 30」 |
 * | [shutterText] | 快门 | 180 度快门算出的 `1/60`；**无手动录像的机型是 `自动`** |
 * | [isoHint] | ISO | 提示语带方向（「自动，宁可 ISO 3200 也别降帧率」） |
 * | [bitrateMbps] | 码率 | Mbps，导出/压缩时的下限参考 |
 * | [stabilization] | 防抖 | 现成短语（「超级防抖 / 最强档」「关闭（用三脚架）」） |
 * | [useLog] | LOG 开关 | true 才显示 LOG 徽标 |
 * | [logNote] | LOG 说明 | 为什么开或不开，随方案不同 |
 * | [focus] | 对焦 | 现成短语（「连续自动对焦（AF-C）」） |
 * | [tips] | 要点列表 | 4~6 条动作口诀，`[String]`（例外允许，见文件头） |
 * | [audioTip] | 收音 | 现成一句 |
 * | [pathHint] | 去哪儿设 | 品牌路径（`BrandGuide.hint`） |
 * | [downgraded] | 降级提示 | true 表示这份方案因设备能力被降过档 |
 * | [clipboardText] | 复制按钮 | `VideoPlan.clipboardText()` 原文，直接进 `UIPasteboard` |
 *
 * ★ [shutterText] 的特殊值：`自动`
 *   `profile.supportsManualVideo == false` 的机型（如部分 Pixel）没有手动快门，
 *   `VideoRecommender` 会把 `shutterText` 换成「自动」并在 tips 头部插一条说明。
 *   此时 [shutterText] 不是「1/xx」形式，**Swift 侧不要按 `/` 去解析它**，
 *   直接当字符串显示。
 *
 * ★ [downgraded] 与 [tier] 的关系
 *   `downgraded == true` 只说明「用户本来可能期待更高一档，但设备/亮度不答应」，
 *   不代表 [tier] 是错的 —— [tier] 永远是这台机器能真的拨出来的那一档。
 */
class IosVideoPlanDto(
    /** `VideoIntent.label`，如「走动跟拍」。 */
    val intentLabel: String,
    /** 分辨率档：`4K` / `1080P` / `720P`。 */
    val tier: String,
    /** 帧率，已按设备能力钳到标准档（24 / 30 / 60 / 120 / 240）。 */
    val fps: Int,
    /** 现成的组合标签，如「4K 30fps」。**不要自己拼**。 */
    val resLabel: String,
    /** 180 度快门标签，如「1/60」；无手动录像的机型为「自动」（见类注释）。 */
    val shutterText: String,
    /** ISO 提示语（带方向，不是单个数值）。 */
    val isoHint: String,
    /** 建议码率（Mbps）。 */
    val bitrateMbps: Int,
    /** 防抖档位短语。 */
    val stabilization: String,
    /** 是否建议开 LOG。 */
    val useLog: Boolean,
    /** 为什么开 / 不开 LOG。 */
    val logNote: String,
    /** 对焦方式短语。 */
    val focus: String,
    /** 要点口诀 4~6 条（`List<String>` 是跨语言铁律的唯一例外，见文件头）。 */
    val tips: List<String>,
    /** 收音建议。 */
    val audioTip: String,
    /** 去哪儿设（品牌路径）。 */
    val pathHint: String,
    /** 该方案是否因设备能力被降级过。 */
    val downgraded: Boolean,
    /** `VideoPlan.clipboardText()` 原文，直接塞 `UIPasteboard.general.string`。 */
    val clipboardText: String
)

/**
 * 录像方案的 Swift 门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `IosVideoBridge.shared.intentCount()`，不是 `IosVideoBridge()`。
 *
 * 导出函数全部**签名唯一**（无重载）—— Kotlin 靠参数类型区分重载，
 * ObjC 只能靠参数名，同名重载会被加上不可预测的尾随下划线。
 */
object IosVideoBridge {

    /**
     * 意图总数（当前 8 种：走动跟拍 / 口播讲解 / 运动抓拍 / 暗光现场 /
     * 风光运镜 / 慢动作 / 延时摄影 / 翻拍文档）。
     *
     * 由 Kotlin 的 `VideoIntent.entries.size` 现算，**不在 Swift 里写死 8**：
     * common 侧将来加一种意图（比如「直播」），Swift 的下拉框自动多一项；
     * 写死就会悄悄少一个，而用户只会觉得「怎么没有直播」。
     */
    fun intentCount(): Int = VideoIntent.entries.size

    /**
     * 第 [index] 种意图的名字（`VideoIntent.label`），如「走动跟拍」。
     *
     * @param index 0..[intentCount] - 1
     * @return 名字；**越界返回空串**（不抛异常，见文件头）
     */
    fun intentLabelAt(index: Int): String = VideoIntent.entries.getOrNull(index)?.label ?: ""

    /**
     * 第 [index] 种意图的一句话说明（`VideoIntent.oneLine`），如「边走边拍，手持容易晃」。
     *
     * @param index 0..[intentCount] - 1
     * @return 说明；越界返回空串
     */
    fun intentOneLineAt(index: Int): String =
        VideoIntent.entries.getOrNull(index)?.oneLine ?: ""

    /**
     * 生成一份录像方案。
     *
     * 数据来源与 Android 完全一致：
     * `VideoRecommender.plan(intent, profile, stats, scene)`，
     * 其中 `stats` 取 [IosFrameCache.latest]（`IosBridge.analyzeFrame` 写的真值），
     * `scene` 由 [sceneFromName] 解析（与 `IosBridge.analyzeFrame` 逐字同口径）。
     *
     * ## 缓存为 null 时是合法调用
     * `stats == null` 时 `VideoRecommender` 用 `?: 0.45f` 当默认亮度，
     * 于是只有 `scene == NIGHT` 才会被判定为暗光（`SceneType.ACTION` 同理）。
     * 也就是说「刚进录像页、摄像头还没出第一帧」时照样能给出一份方案 ——
     * 这正是用户点开下拉框那一刻需要看到的东西。
     * ★ **不要**为了「稳一点」传一份 `FrameStats.EMPTY`：那会把「没有画面」
     *   读成「画面全黑」，直接把暗光分支全部打开（暗光会降帧率、劝退 120fps）。
     *
     * ## 主线程
     * 纯计算（无 IO、无锁），在 Swift 主线程调用即可。
     *
     * @param intentIndex 意图下标 0..[intentCount] - 1；**越界返回 null**
     * @param profile 设备档案（Swift 侧 `CameraSession.currentDeviceProfile()`）
     * @param sceneName `SceneType` 的枚举名；未知 / 空串按 `GENERAL`（区分大小写）
     * @return 扁平方案；仅当 `intentIndex` 越界时返回 null（`VideoRecommender.plan`
     *   本身对任何设备都会返回一份方案，不存在「算不出来」的分支）
     */
    fun plan(intentIndex: Int, profile: DeviceProfile, sceneName: String): IosVideoPlanDto? {
        // 越界 → null。不用 first()/require()：跨语言边界上抛异常最难定位。
        val intent = VideoIntent.entries.getOrNull(intentIndex) ?: return null
        val plan = VideoRecommender.plan(
            intent = intent,
            profile = profile,
            stats = IosFrameCache.latest,
            scene = sceneFromName(sceneName)
        )
        return plan.toDto()
    }

    /**
     * `VideoPlan`（commonMain）→ 扁平 DTO。
     *
     * ★ 所有字符串字段都是**引用搬运**，没有一处 `buildString` / 拼接 ——
     *   唯一的「派生」是 `resLabel` 与 `clipboardText`，而这两个都是
     *   common 侧现成的计算属性 / 方法，这里只是取值。
     */
    private fun VideoPlan.toDto(): IosVideoPlanDto = IosVideoPlanDto(
        intentLabel = intent.label,
        tier = tier,
        fps = fps,
        resLabel = resLabel,
        shutterText = shutterText,
        isoHint = isoHint,
        bitrateMbps = bitrateMbps,
        stabilization = stabilization,
        useLog = useLog,
        logNote = logNote,
        focus = focus,
        tips = tips,
        audioTip = audioTip,
        pathHint = pathHint,
        downgraded = downgraded,
        clipboardText = clipboardText()
    )
}
