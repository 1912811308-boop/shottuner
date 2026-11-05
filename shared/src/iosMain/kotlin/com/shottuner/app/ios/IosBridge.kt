// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 只在 iOS 目标编译的源集。
//
// ★ 这个源集的存在意义（第一批）：
//   ① 让 macOS CI 的 `:shared:compileKotlinIosArm64` / `:shared:compileKotlinIosSimulatorArm64`
//      **真的覆盖到 iosMain** —— 源集里没有文件时，这道编译门禁等于没编任何 iOS 专属代码；
//   ② 作为**跨语言边界的唯一收窄口**：Kotlin/Native 会把 shared 模块的 public API
//      导出成 ObjC 头文件，Swift 侧 `import Shared` 之后就能调。
//      跨语言的坑全在「导出什么」上，所以这里刻意把导出面收窄成
//      **标量进、标量出**的扁平函数（见 [IosBridge]）。
//
// ★ 为什么不放业务逻辑：
//   commonMain 才是共享内核（评分 / 构图 / 图鉴 / 技法文案），两边同一份实现。
//   iosMain 是**平台适配层**，第一批只有一行编译期常量 + 一个联通性自检。

package com.shottuner.app.ios

import com.shottuner.app.analysis.CompositionAnalyzer
import com.shottuner.app.analysis.DimensionScore
import com.shottuner.app.analysis.FrameStatsCore
import com.shottuner.app.analysis.FrameStatsDump
import com.shottuner.app.analysis.IosDimensionDto
import com.shottuner.app.analysis.IosFrameAnalysisDto
import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.model.SceneType
import com.shottuner.app.sensors.LevelMath
import com.shottuner.app.text.NumFmt

/**
 * iOS 侧的**跨语言门面**：Swift 侧唯一被允许直接触碰的 Kotlin API。
 *
 * ## 为什么要有这一层
 *
 * Kotlin/Native 会把 shared 模块的 public 声明导出成 ObjC 头文件（`Shared.framework/Headers/Shared.h`），
 * Swift 用 `import Shared` 引入后就能调用。直接调 `commonMain` 的类本身是可行的，
 * 但有三个坑：
 *
 * 1. **`object` 在 Swift 里是「类属性单例」**，所以是 `IosBridge.shared`，不是 `IosBridge()`。
 * 2. **重载会被改名**：Kotlin 靠参数类型区分重载，ObjC 只能靠参数名，
 *    所以同名重载在 Swift 侧会被加上尾随下划线（`one(value:)` / `one(value_:)`），
 *    **哪个被加下划线不可预测**。所以导出的函数必须**签名唯一**。
 * 3. **数组过不了边界**：`FloatArray` 会桥接成 NSArray of NSNumber（装箱 + 拷贝），
 *    性能差、Swift 侧难用。跨语言边界只传标量；需要结构时由 commonMain 提供**扁平 DTO**。
 *
 * 这一层把上面三条全部消化掉：对外只暴露签名唯一的、标量进标量出的函数。
 *
 * ## 命名说明
 *
 * Kotlin/Native 导出的 ObjC 类名带 framework 前缀（`SharedIosBridge`），
 * 但 Swift 侧通过 `swift_name` 看到的仍是**纯 Kotlin 名**（`IosBridge`）。
 */
object IosBridge {

    /** 共享内核 API 版本。Swift 侧用它判断 framework 与工程是否配套。 */
    const val SHARED_API_VERSION: Int = 1

    /**
     * 联通性自检串 —— 编译期常量。
     *
     * 它的作用是**证明 iosMain 源集确实参与了编译并被导出到了 ObjC**：
     * Swift 侧只要能拿到这串文字，就说明 KMP 的「Kotlin → ObjC → Swift」整条链路是通的。
     *
     * @return 固定文案，含 API 版本号
     */
    fun describe(): String =
        "ShotTuner iOS · 共享内核已联通（api v$SHARED_API_VERSION）"

    /**
     * 定点数值格式化（commonMain 的 [NumFmt]）：小数点恒为 `.`，不跟随系统语言。
     *
     * @param value 待格式化的数值
     * @return 例如 `1.5`
     */
    fun formatOne(value: Double): String = NumFmt.one(value)

    /**
     * 带正负号的定点数值（commonMain 的 [NumFmt]）：曝光补偿这类数值必须带号。
     *
     * @param value 待格式化的数值
     * @return 例如 `+0.3` / `-0.3`
     */
    fun formatSigned(value: Double): String = NumFmt.signed(value, decimals = 1)

    /**
     * 参考分文案。
     *
     * ★ 口径铁律：只说「**参考分**」。shottuner 拿不到"按下快门那一刻"的画面，
     *   只有取景时的连续统计量，所以给的是实时参考值，不是专业评测。
     *   任何地方都不得把它写成「得分」「评分」「准确率」这类断言式措辞。
     *
     * @param score 参考分（0..100）
     * @return 例如 `参考分 82 / 100`
     */
    fun referenceScoreText(score: Double): String = "参考分 ${score.toInt()} / 100"

    /**
     * 画面是否已经水平（commonMain 的 [LevelMath]）。
     *
     * @param roll 横滚角（度），正值表示画面右侧偏低
     * @return 在容差内返回 true
     */
    fun isLevel(roll: Double): Boolean = LevelMath.isLevel(roll.toFloat())

    /**
     * 跨端对齐用的取景统计量 dump：喂进一帧 YUV420 的三平面字节，吐出**整段文本**。
     *
     * ## 为什么返回 String 而不是把统计量逐个导出
     *
     * T12 要证明「同一批固定测试图在 Android 与 iOS 上算出同一套 FrameStats」。
     * 若把 `FrameStats` 的字段逐个桥接给 Swift 再由 Swift 拼文本：
     *
     * 1. `FloatArray`（histogram / grid / skinGrid）会桥接成 `NSArray<NSNumber>`，
     *    既慢又容易在 Swift 侧被隐式转成不同精度；
     * 2. 更要命的是 **Float 的字符串化在 Kotlin 与 Swift 里不保证逐字一致**，
     *    两边各拼各的，算法明明相同 diff 也会永远对不上。
     *
     * 所以这里让 **Kotlin 一次性生成文本**，Swift 只做「传字节 → 拿字符串 → 落盘」：
     * 格式由 commonMain 的 [FrameStatsDump] 统一定义，两端调用的是同一份拼装代码，
     * 一致性是结构保证，不靠人工对齐文档。
     *
     * ## 固定参数口径
     *
     * `yPixelStride=1`、`uPixelStride=1`、`vPixelStride=1`、`vRowStride=uRowStride`、
     * `rotationDegrees=0`、`computeFocus=true`、`focusFallback=0f`、`emitRgb=false`。
     * 这些值写死在 [FrameStatsDump.computeDump] 里，**Android 侧 dump 工具调的也是它**，
     * 所以两端不会各传各的。
     *
     * ## Swift 侧调用提示
     *
     * `IosBridge` 是 Kotlin `object`，在 Swift 里是类属性单例：
     * `IosBridge.shared.dumpFrameStats(yPlane:uPlane:vPlane:yRowStride:uRowStride:width:height:label:)`。
     * `ByteArray` 会被 Kotlin/Native 导出成 `KotlinByteArray`，Swift 侧需用它
     * （或由 `NSData` 逐字节填充）承载三个平面；`String` 正常桥接成 Swift `String`。
     *
     * @param yPlane Y 平面字节
     * @param uPlane U 平面字节
     * @param vPlane V 平面字节
     * @param yRowStride Y 平面每行字节跨度
     * @param uRowStride U 平面每行字节跨度（V 平面沿用同一个值）
     * @param width 图像宽（像素）
     * @param height 图像高（像素）
     * @param label 图名，写入首行 `### ` 标题，例如 `01-bright`
     * @return 可直接落盘、与 Android 侧逐字节 diff 的文本；尺寸过小等无法统计时给出占位文本
     */
    fun dumpFrameStats(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        width: Int,
        height: Int,
        label: String
    ): String = FrameStatsDump.computeDump(
        yPlane = yPlane,
        uPlane = uPlane,
        vPlane = vPlane,
        yRowStride = yRowStride,
        uRowStride = uRowStride,
        width = width,
        height = height,
        label = label
    )

    /**
     * 「一帧 YUV 进、四维参考分 + 一条提示 + 直方图出」——iOS 取景浮层的唯一入口。
     *
     * ## 为什么是一次调用出全部
     *
     * Swift 侧每秒要刷新 1 次浮层。如果拆成「先拿 stats、再拿分数、再拿提示」三次调用，
     * 每次跨语言边界都是 ObjC 消息派发 + 可能的对象装箱；更要紧的是**三次调用之间
     * 画面可能已经换帧**，拼出来的分数与直方图就对不上了。一次调用、一次快照。
     *
     * ## 参数口径（与 `FrameStatsDump.computeDump` 完全一致，不要各自演化）
     *
     * `yPixelStride = uPixelStride = vPixelStride = 1`、`vRowStride = uRowStride`、
     * `rotationDegrees = 0`、`computeFocus = true`、`emitRgb = false`。
     * 签名里没有 vRowStride / pixelStride，是因为 iOS 的 I420 平面恒为紧凑布局
     * （8bit 每像素 1 字节、U/V 两平面尺寸相同），多传三个参数只会多三处出错机会。
     *
     * [focusFallback] 由调用方传：实时链路每 15 帧才实测一次清晰度，
     * 没实测的帧必须沿用上一次的值，否则清晰度会在「未测」和「18」之间每 0.5 秒跳一次。
     *
     * ## 三个容易做错的点（都已在实现里钉住）
     *
     * 1. **构图分用 `hint.scoreNoTilt`，不是 `hint.score`。** 倾斜已经被「水平度」
     *    维度扣过一次分，如果构图维度再用含倾斜的分数，同一处歪斜会被罚两遍，
     *    总分不可解释。`CompositionAnalyzer` 同时给出这两个值，别拿错。
     * 2. **`rollValid == false` 时给 `ScoreBoard.score` 传 `roll = null`。**
     *    `ScoreBoard.level()` 正是靠 `roll == null`（且没有地平线）来判定
     *    「两个源都没有」→ 记满分 + 标「未测」。传 0f 会变成「测了，很平」，
     *    缺传感器就变成了假数据。
     * 3. **构图提示由本函数内部算，不接受外部传入。** 见下面「构图为什么不再由
     *    Swift 传入」一节 —— 上一版有 15 个 hint 参数，那是设计错误。
     *
     * ## 提示只给一条
     *
     * [IosFrameAnalysisDto.tipText] 取 `recommendations.firstOrNull()`。
     * `ScoreBoard` 内部最多会给 3 条（每维度 1 条），但产品口径是
     * 「提示卡只说一句话」：浮层空间有限，三条并列用户也不知道先做哪件。
     * 需要完整清单时应当另开一次调用，而不是把这个字段改成数组。
     *
     * ## 数组边界
     *
     * 返回的 [IosFrameAnalysisDto] 里只有 `histogram` 一个数组，
     * 理由见 `IosFrameAnalysisDto` 的 KDoc（`KotlinFloatArray.get(index)` 直读 C 数组，
     * 不装箱；直方图是曝光面板必须画的）。其余字段全是标量。
     *
     * @param yPlane Y 平面字节（紧凑 I420）
     * @param uPlane U 平面字节
     * @param vPlane V 平面字节
     * @param yRowStride Y 平面每行字节跨度（= 图像宽）
     * @param uRowStride U 平面每行字节跨度（= 色度宽，V 平面沿用同一个值）
     * @param width 图像宽（像素）
     * @param height 图像高（像素）
     * @param focusFallback 本帧没实测清晰度时沿用的上一次方差；0 表示整条会话还没测过
     * @param roll 重力传感器横滚角（度），正值表示画面右侧偏低
     * @param rollValid 传感器是否有有效值；false 时按「没有重力源」处理
     * @param sceneName `SceneType` 的枚举名（`NIGHT` / `BACKLIT` / `PORTRAIT` /
     *   `LANDSCAPE` / `INDOOR` / `ACTION` / `GENERAL`）；无法识别时按 `GENERAL` 处理
     * @return 扁平分析结果；三平面无效 / 尺寸过小时返回 [IosFrameAnalysisDto.FAILED]
     *
     * ## 构图为什么不再由 Swift 传入
     *
     * 上一版这里有 15 个 hint 参数（`hintScore` / `tiltDeg` / `subjectX` …），
     * 要求 Swift 侧先算好构图提示再传回来。**那是设计错误**：
     *
     * `CompositionAnalyzer.analyze(s: FrameStats)` 只吃 `FrameStats`，是纯函数。
     * 让 Swift 传 hint 等于要求它在 Swift 里重写一遍构图分析，或者在 Swift 里
     * 维护一份「这些参数该从哪来」的映射 —— 两端必然走样，而且走样了没人会发现
     * （构图分只是少几分的差别，不像崩溃那样显眼）。
     *
     * 现在链路和 Android 完全一致：
     * `FrameStatsCore.compute → CompositionAnalyzer.analyze → ScoreBoard.score`，
     * 三步都在 commonMain，iOS 只是从 [analyzeFrame] 这一个入口进。
     *
     * ⇒ 参数从 26 个降到 11 个。少 15 个跨语言参数，就少 15 处「传错顺序」
     *   或「传了默认值忘了改」的机会。
     */
    fun analyzeFrame(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        width: Int,
        height: Int,
        focusFallback: Float,
        roll: Float,
        rollValid: Boolean,
        sceneName: String
    ): IosFrameAnalysisDto {
        val result = FrameStatsCore.compute(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = yRowStride,
            yPixelStride = 1,
            uRowStride = uRowStride,
            uPixelStride = 1,
            vRowStride = uRowStride,
            vPixelStride = 1,
            width = width,
            height = height,
            rotationDegrees = 0,
            computeFocus = true,
            focusFallback = focusFallback,
            emitRgb = false
        ) ?: return IosFrameAnalysisDto.FAILED

        val stats = result.stats

        // 构图分析与 Android 走同一份实现：只吃 FrameStats，纯函数。
        // 算不出来时 analyze() 自己返回 CompositionHint.EMPTY（全 0 / 置信度 0），
        // 不是 null，所以这里不需要判空 —— 构图维度会按 0 分算，
        // 主体标记因置信度 0 不画，这是「没构图数据」应有的表现。
        val hint = CompositionAnalyzer.analyze(stats)

        // 未知场景名一律退到 GENERAL，不抛异常：跨语言边界上抛异常最难定位。
        val scene = SceneType.entries.firstOrNull { it.name == sceneName } ?: SceneType.GENERAL

        // rollValid == false 必须传 null 而不是 0f —— ScoreBoard.level() 靠 null 判定
        // 「两个源都没有」，进而记满分 + 标「未测」；传 0f 会把缺数据伪装成「很平」。
        val rollOrNull: Float? = if (rollValid) roll else null

        val scoreResult = ScoreBoard.score(
            stats = stats,
            hint = hint,
            roll = rollOrNull,
            scene = scene
        )

        val dimensions = scoreResult.dimensions
        // 产品口径：提示卡只说一条。ScoreBoard 最多给 3 条，这里只取最重要的一条。
        val primaryTip = scoreResult.recommendations.firstOrNull()

        // 任务卡的数据源复用这一次分析，不重新算一遍：Swift 的 400ms tick
        // （IosMissionBridge.tick）要组装 MissionSample，而 stats / hint / recs
        // 正是这次 analyzeFrame 内部算出来的东西 —— 丢掉了就得跨语言重传整帧字节。
        IosMissionBridge.rememberAnalysis(stats, hint, scoreResult)

        return IosFrameAnalysisDto(
            ok = true,
            total = scoreResult.total,
            grade = scoreResult.grade,
            anyUnmeasured = dimensions.any { !it.measured },
            level = dimensionDto(dimensions, ScoreDimension.LEVEL),
            exposure = dimensionDto(dimensions, ScoreDimension.EXPOSURE),
            composition = dimensionDto(dimensions, ScoreDimension.COMPOSITION),
            clarity = dimensionDto(dimensions, ScoreDimension.CLARITY),
            tipId = primaryTip?.tipId ?: "",
            tipText = primaryTip?.text ?: "",
            // lowercase() 在 Kotlin 里是 locale 无关的（等价于 Locale.ROOT），
            // 不会在土耳其语环境下把 "LEVEL" 变成 "leveL" 之类的怪东西。
            tipDimension = primaryTip?.dimension?.name?.lowercase() ?: "",
            meanLuma = stats.meanLuma,
            p05 = stats.p05,
            p50 = stats.p50,
            p95 = stats.p95,
            highlightClip = stats.highlightClip,
            shadowClip = stats.shadowClip,
            // 构图字段全部来自上面那一次 CompositionAnalyzer.analyze(stats)。
            // Swift 侧拿去做覆盖层（主体圈、地平线、三分交点），不需要再算一遍。
            hintScore = hint.scoreNoTilt,
            tiltDeg = hint.tiltDeg,
            horizonDetected = hint.horizonDetected,
            horizonY = hint.horizonY,
            subjectX = hint.subjectX,
            subjectY = hint.subjectY,
            subjectConfidence = hint.subjectConfidence,
            subjectSize = hint.subjectSize,
            subjectLeft = hint.subjectLeft,
            subjectTop = hint.subjectTop,
            subjectRight = hint.subjectRight,
            subjectBottom = hint.subjectBottom,
            targetX = hint.targetX,
            targetY = hint.targetY,
            suggestMove = hint.suggestMove,
            histogram = stats.histogram
        )
    }

    /**
     * 场景枚举名清单，逗号分隔，供 Swift 侧填场景选择器。
     *
     * 为什么由 Kotlin 给而不是 Swift 写死：`SceneType` 在 commonMain，
     * 将来加一个场景（比如 `MACRO`）时，只要改 Kotlin 一处，
     * Swift 的选择器自动多一项；写死在 Swift 里就会悄悄少一个。
     *
     * @return 例如 `NIGHT,BACKLIT,PORTRAIT,LANDSCAPE,INDOOR,ACTION,GENERAL`
     */
    fun sceneNames(): String = SceneType.entries.joinToString(",") { it.name }

    /**
     * 按维度取一条扁平 DTO。
     *
     * `ScoreBoard.score` 一定会返回四个维度，理论上不会缺；但这里仍然给 `firstOrNull`
     * + 兜底，理由和 [analyzeFrame] 里一样：**跨语言边界上抛异常最难定位**，
     * 宁可返回「未测」也不要让 Swift 侧的整个取景浮层崩掉。
     *
     * @param dimensions 四维分数
     * @param target 要取的那一维
     * @return 对应的扁平 DTO；缺失时返回「未测」哨兵
     */
    private fun dimensionDto(
        dimensions: List<DimensionScore>,
        target: ScoreDimension
    ): IosDimensionDto {
        val found = dimensions.firstOrNull { it.dimension == target }
            ?: return IosDimensionDto(label = "", score = 0, max = 0, measured = false)
        return IosDimensionDto(
            label = found.label,
            score = found.score,
            max = found.max,
            measured = found.measured
        )
    }
}
