package com.shottuner.app.analysis

/**
 * 单维度的**扁平 DTO**，供 Swift 侧直接渲染。
 *
 * ## 为什么需要扁平 DTO
 *
 * commonMain 里的 [DimensionScore] 是带方法的对象（`toString()`），
 * `ScoreResult` 里还嵌着 `List<DimensionScore>` / `List<Recommendation>`。
 * Kotlin 的 `List<T>` 跨到 ObjC 会桥接成 `NSArray`，元素装箱 + 拷贝，
 * Swift 侧还要处理 `AnyObject` 向下转型 —— 对「四个维度四条进度条」这种
 * 纯展示场景是纯负担。
 *
 * 所以跨语言边界只给**标量字段**：Swift 侧拿到的是四个独立对象，
 * 每个对象的四个属性都是 `String` / `Int` / `Bool`，不需要任何解包。
 *
 * @param label 维度显示名，与 Android 侧文案同源（「水平」「曝光」「构图」「清晰（估）」）
 * @param score 该维度得分
 * @param max 该维度满分
 * @param measured false 表示本帧该维度**没有数据**，UI 应显示「未测」而不是 0 ——
 *   缺数据不扣用户分，这是全仓统一的评分口径
 */
class IosDimensionDto(
    val label: String,
    val score: Int,
    val max: Int,
    val measured: Boolean
)

/**
 * 一帧画面的**完整分析结果**：四维参考分 + 一条提示 + 显示用的统计量。
 *
 * Swift 侧的用法是「一次调用拿全所有要显示的字段」，不需要再回调 Kotlin。
 *
 * ## ⚠️ 关于 [histogram]：全仓唯一被允许跨语言边界的数组
 *
 * 本仓的跨语言纪律是「只传标量，不传数组」（见 `IosBridge.kt` 的 KDoc），
 * 因为 Kotlin 的 `List<Float>` 会桥接成 `NSArray`（元素装箱 + 逐元素拷贝）。
 * 但 `FloatArray` 不一样：Kotlin/Native 把它导出成 `KotlinFloatArray`，
 * Swift 侧用 `get(index:)` 读的是**连续 C 数组**，不装箱、不拷贝。
 * 256 次读取的开销可以忽略，而直方图是曝光面板必须画的东西
 * （没有它，UI 只能画一根均值柱）。
 *
 * ⇒ **豁免只给这一个字段**。以后新增字段一律保持标量：要么是 `Int` / `Float` /
 *   `Boolean` / `String`，要么拆成多个标量（例如不要把 `tips` 加成
 *   `List<String>`，而应该像 [tipText] 一样「只给第一条」）。
 *
 * ## 关于 [anyUnmeasured]
 *
 * 四维里只要有一个 `measured == false`，UI 就应该在总分旁标「含未测维度」，
 * 否则用户看到 68 分会以为是「四维都测了、就是这么多」。
 * 这个标志由 Kotlin 算好，Swift 侧不要自己再遍历一遍 —— 遍历口径写两遍必然走样。
 *
 * @param ok false 表示这一帧压根没算出来（尺寸过小 / 三平面无效），
 *   此时所有数值为 0、四维 `measured = false`、文案为空串。
 *   **Swift 侧必须先判 [ok]**，不要把 false 当成「0 分」渲染
 * @param total 总分（四维之和，0..100）
 * @param grade 等级文案（优秀 / 良好 / 及格 / 待改进）
 * @param anyUnmeasured 四维中是否存在「未测」维度
 * @param level 水平维度
 * @param exposure 曝光维度
 * @param composition 构图维度
 * @param clarity 清晰（估）维度
 * @param tipId 提示关联的技法 id，可追溯回技法库；空串表示「没有具体条目」
 * @param tipText 提示正文。**只给一条**：产品口径是「提示卡只说一句话」，
 *   多条会挤爆浮层，也会让用户不知道该先做哪件
 * @param tipDimension 提示所属维度的小写名（`level` / `exposure` / `composition` /
 *   `clarity` / 空串），供 Swift 侧上色或配图标
 * @param meanLuma 平均亮度 0..1
 * @param p05 5% 分位亮度 0..1（暗部代表值）
 * @param p50 50% 分位亮度 0..1（中位亮度）
 * @param p95 95% 分位亮度 0..1（高光代表值）
 * @param highlightClip 高光溢出占比 0..1
 * @param shadowClip 暗部死黑占比 0..1
 * @param hintScore 构图分（已剔除倾斜扣分，对应 `CompositionHint.scoreNoTilt`）。
 *   倾斜已经由「水平度」扣过一次，构图不能再扣第二次，否则同一处歪斜被罚两遍，
 *   总分不可解释
 * @param tiltDeg 地平线倾角（度），正值表示画面右侧偏低
 * @param horizonDetected 是否检测到可信的地平线 / 水平结构线
 * @param horizonY 地平线纵向位置 0..1
 * @param subjectX 主体重心横坐标 0..1
 * @param subjectY 主体重心纵坐标 0..1
 * @param subjectConfidence 主体显著性置信度 0..1，低于 0.30 时 UI 不该画主体标记 ——
 *   画了就是瞎指
 * @param subjectSize 主体占画面比例 0..1
 * @param subjectLeft 主体外框左边界 0..1
 * @param subjectTop 主体外框上边界 0..1
 * @param subjectRight 主体外框右边界 0..1
 * @param subjectBottom 主体外框下边界 0..1
 * @param targetX 三分交点建议落点横坐标 0..1
 * @param targetY 三分交点建议落点纵坐标 0..1
 * @param suggestMove 是否有「该把主体挪到交点」的建议
 * @param histogram 256 桶归一化亮度直方图（每桶 0..1，总和为 1）
 *
 * ## 为什么构图字段在 Kotlin 侧算，而不是让 Swift 传进来
 *
 * `CompositionAnalyzer.analyze(s: FrameStats)` 只吃 `FrameStats` ——
 * 和 `ScoreBoard` 一样是纯函数，两端共用同一份实现。
 * 所以 Swift 侧**不需要**先把 hint 算出来再传回去：那等于要求 Swift 重写一遍
 * 构图分析，或者在 Swift 里维护一份「传什么参数」的映射，两端必然走样。
 *
 * Android 的链路是 `FrameStatsCore.compute → CompositionAnalyzer.analyze →
 * ScoreBoard.score`，iOS 走**同一条**链，只是入口在 [IosBridge.analyzeFrame]。
 */
class IosFrameAnalysisDto(
    val ok: Boolean,
    val total: Int,
    val grade: String,
    val anyUnmeasured: Boolean,
    val level: IosDimensionDto,
    val exposure: IosDimensionDto,
    val composition: IosDimensionDto,
    val clarity: IosDimensionDto,
    val tipId: String,
    val tipText: String,
    val tipDimension: String,
    val meanLuma: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val hintScore: Int,
    val tiltDeg: Float,
    val horizonDetected: Boolean,
    val horizonY: Float,
    val subjectX: Float,
    val subjectY: Float,
    val subjectConfidence: Float,
    val subjectSize: Float,
    val subjectLeft: Float,
    val subjectTop: Float,
    val subjectRight: Float,
    val subjectBottom: Float,
    val targetX: Float,
    val targetY: Float,
    val suggestMove: Boolean,
    val histogram: FloatArray
) {
    companion object {

        /** 直方图桶数。Kotlin 与 Swift 都按这个常量对齐，不写死 256。 */
        const val HISTOGRAM_BINS: Int = 256

        /** 「未测」维度：分值 0、满分 0、`measured = false`。 */
        private val EMPTY_DIMENSION: IosDimensionDto =
            IosDimensionDto(label = "", score = 0, max = 0, measured = false)

        /**
         * 算不出来时的哨兵值。
         *
         * ★ 四个 `max` 也是 0，是**刻意的**：让「未测」不可能被误渲染成
         *   「0 / 25 分」。Swift 侧判 `ok == false` 之后应显示占位文案
         *   （例如「这一帧没取到画面」），而不是画四条空进度条。
         *
         * ★ `histogram` 给 256 个 0 而不是空数组：Swift 侧画柱状图时可以
         *   直接按下标遍历，不必先判空 —— 少一个分支就少一处越界机会。
         *
         * ★★ 声明顺序有讲究：Kotlin 的 `companion object` 按**书写顺序**初始化，
         *   [FAILED] 引用了 [EMPTY_DIMENSION] 与 [HISTOGRAM_BINS]，所以这两个必须
         *   写在它**前面**。反过来写会得到 "Variable 'EMPTY_DIMENSION' must be
         *   initialized" 的编译错（踩过一次）。
         */
        val FAILED: IosFrameAnalysisDto = IosFrameAnalysisDto(
            ok = false,
            total = 0,
            grade = "",
            anyUnmeasured = true,
            level = EMPTY_DIMENSION,
            exposure = EMPTY_DIMENSION,
            composition = EMPTY_DIMENSION,
            clarity = EMPTY_DIMENSION,
            tipId = "",
            tipText = "",
            tipDimension = "",
            meanLuma = 0f,
            p05 = 0f,
            p50 = 0f,
            p95 = 0f,
            highlightClip = 0f,
            shadowClip = 0f,
            // 构图字段全部给 CompositionHint.EMPTY 的同款默认值：
            // 外框默认铺满整幅（0,0,1,1）、三分交点默认右下交点、置信度 0（不画主体）。
            hintScore = 0,
            tiltDeg = 0f,
            horizonDetected = false,
            horizonY = 0.5f,
            subjectX = 0.5f,
            subjectY = 0.5f,
            subjectConfidence = 0f,
            subjectSize = 0f,
            subjectLeft = 0f,
            subjectTop = 0f,
            subjectRight = 1f,
            subjectBottom = 1f,
            targetX = 0.6667f,
            targetY = 0.3333f,
            suggestMove = false,
            histogram = FloatArray(HISTOGRAM_BINS)
        )
    }
}
