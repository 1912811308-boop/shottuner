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
 * @param histogram 256 桶归一化亮度直方图（每桶 0..1，总和为 1）
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
            histogram = FloatArray(HISTOGRAM_BINS)
        )
    }
}
