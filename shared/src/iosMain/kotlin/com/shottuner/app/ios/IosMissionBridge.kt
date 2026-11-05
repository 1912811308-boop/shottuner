// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 任务卡的跨语言门面（iOS 侧「任务卡」的唯一数据源）。
//
// ★ 为什么要有这一层：Swift 侧拿不到 MissionSample 的原材料
//   状态机 `MissionEngine` 在 commonMain，两端同一份实现（Android 的 MainActivity
//   每 400ms 喂一次 `MissionSample`）。iOS 要复用同一套判定，就必须也每 400ms 喂一次。
//   问题是 `MissionSample` 需要 `FrameStats` / `CompositionHint` /
//   `List<Recommendation>` / `Float?` —— 这四样全都过不了跨语言边界
//   （数组会桥接成 NSArray 装箱拷贝，`Float?` 会变成 `KotlinFloat?`）。
//   ⇒ **组装必须在 Kotlin 侧完成**，Swift 只负责送三个标量：now / roll / rollValid。
//
// ★ 为什么 tick 是 400ms 一次、且**不**挂在 analyzeFrame 里
//   `MissionEngine` 用 EMA 逐 tick 平滑（`MissionThresholds.SMOOTH_ALPHA`），
//   阈值与超时全是毫秒。iOS 的分析链路是 10fps，若按 10fps 喂 tick，
//   EMA 的收敛速度会是 Android 的 4 倍，判定节奏与 Android 分叉 ——
//   而且**分叉了不会有任何报错**，表现只是「iOS 好像更容易绿」。
//   ⇒ tick 由 Swift 侧独立的一个 400ms 定时器驱动（主线程），不要塞进 analyzeFrame。
//
// ★ 这个文件的两个入口分别在不同线程，缓存必须「不可变持有者 + 单次引用发布」
//   · `rememberAnalysis` 由 `IosBridge.analyzeFrame` 在 `gate.queue`（后台串行）上调用；
//   · `tick` / `abort` / `resume` 由 Swift 在主线程调用。
//   Kotlin/Native 的新内存模型（Kotlin 1.7.20 起的默认模型）**允许**跨线程共享可变状态，
//   但**不会**自动给你可见性保证：后台线程写好一份完整对象、主线程可能读到
//   「一半新一半旧」或者干脆读到 null（各自只看到自己 CPU 缓存里的值）。
//   所以缓存做成一个 `@Volatile` 的**不可变持有者引用**（[CachedFrame]，字段全是 val）：
//   写入方构造完整个对象后**一次性发布引用**，读方要么看到旧的完整对象、要么看到新的
//   完整对象，不会看到中间态。绝不逐个字段更新（那样一定会拼出新旧混合的快照）。
//   前提是被缓存的对象图本身不可变：`FrameStats` / `CompositionHint` /
//   `Recommendation` 都是构造后不再改写的值对象（`FrameStatsCore.compute`
//   每次返回新数组，不共享复用），这一点必须保持 —— 谁哪天给它加了个 var 字段，
//   这个缓存就不再安全。
//
// ★ 状态机本身只由一个线程碰：`engine` 只在 `tick` / `abort` / `resume` 里被调用，
//   这三者全部由 Swift 主线程调（`MissionModel` 是 ObservableObject / @MainActor）。
//   `rememberAnalysis` 只写 `cached`，一次都不碰 `engine`。

package com.shottuner.app.ios

import com.shottuner.app.analysis.Recommendation
import com.shottuner.app.analysis.ScoreResult
import com.shottuner.app.growth.AbortReason
import com.shottuner.app.growth.MissionEngine
import com.shottuner.app.growth.MissionSample
import com.shottuner.app.growth.MissionState
import com.shottuner.app.growth.MissionTask
import com.shottuner.app.growth.TaskKind
import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import kotlin.concurrent.Volatile

/**
 * iOS 侧任务卡的跨语言门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `IosMissionBridge.shared.tick(nowMs:roll:rollValid:)`，不是 `IosMissionBridge()`。
 *
 * 导出函数全部**签名唯一**（无重载）—— Kotlin 靠参数类型区分重载，ObjC 只能靠参数名，
 * 同名重载会被加上不可预测的尾随下划线。
 */
object IosMissionBridge {

    /**
     * 取景器叠加层的高宽比 h / w，兜底 16:9。
     *
     * ## 为什么可以兜底
     *
     * `MissionSample.aspect` 只有一个用途：把 `subjectY` 方向的偏差换算到与
     * `subjectX` 同单位（`TaskPicker.distToTarget` 里 `dy * aspect`），
     * 即「画面短边为单位」的距离。iOS 侧现在不传，用 16:9 兜底 ——
     * Android 也是这么兜的，两端口径一致。
     *
     * 兜底的代价：iPad / 4:3 设备上「构图任务」的判定距离会偏大约 33%（纵向被低估），
     * 表现为圈比实际略大、略难绿一点。**不会派错任务类型**（可行性窗口
     * [TRIGGER_D, GIVEUP_D] 的区间足够宽，0.16..0.55 与 1.33 倍的缩放不会越过边界），
     * 也不会让任务变得做不到。等 Swift 侧愿意把 `AVCaptureVideoPreviewLayer`
     * 的实际比例传进来时，改这个常量 + 加一个参数即可，不要在 Swift 里重算。
     */
    private const val DEFAULT_ASPECT: Float = 16f / 9f

    /** 唯一的状态机实例。两端跑的是同一份 `MissionEngine`。 */
    private val engine = MissionEngine()

    /**
     * 最近一次 `analyzeFrame` 的中间产物（组装 `MissionSample` 用）。
     *
     * `@Volatile` + 不可变持有者 = 「单次引用发布」，理由见文件头。
     */
    @Volatile
    private var cached: CachedFrame? = null

    /** 到位那一刻冻结的参考分。`ACHIEVED` 期间只读不改，`SCORED` 期间沿用。 */
    private var awardedScoreValue: Int = 0

    /** 冻结那一刻四维是否齐全（决定成绩卡印数字还是「参考分 --」）。 */
    private var awardedMeasured: Boolean = false

    /** 上一 tick 的相位，用来识别「刚进 ACHIEVED」「刚回 IDLE」两个迁移点。 */
    private var lastPhase: MissionState = MissionState.IDLE

    /**
     * 记下这次分析算出来的东西，供后续 tick 组装 `MissionSample`。
     *
     * **不导出给 Swift**（`internal`，不进 ObjC 头文件）：它是 Kotlin 内部的接力棒，
     * Swift 侧不该也不能调。唯一调用点是 [IosBridge.analyzeFrame] 的末尾。
     *
     * ★ 为什么复用这次分析的三个产物，而不是 tick 里重算：
     *   重算要么得再传一次整帧字节（跨语言边界传 3 个 ByteArray，每 400ms 一次），
     *   要么就得在 Kotlin 侧缓存整帧 —— 无论哪种都是浪费：同一帧画面，
     *   分数/提示/任务卡理应看同一个 `FrameStats`。**一次分析，三处复用**。
     *
     * @param stats 本次的取景统计量
     * @param hint 本次的构图提示
     * @param scoreResult 本次的四维参考分与建议（取 `recommendations` 与 `total`）
     */
    internal fun rememberAnalysis(
        stats: FrameStats,
        hint: CompositionHint,
        scoreResult: ScoreResult
    ) {
        // 构造完整个不可变对象后一次性发布引用 —— 不要拆成三次赋值。
        cached = CachedFrame(
            stats = stats,
            hint = hint,
            recs = scoreResult.recommendations,
            total = scoreResult.total,
            allMeasured = scoreResult.dimensions.all { it.measured }
        )
    }

    /**
     * 喂一次 400ms 节拍，返回这一 tick 的任务快照。
     *
     * **必须在主线程调用**（Swift 侧 `MissionModel` 的定时器回调）。
     *
     * ## now 必须是单调时钟
     * 状态机所有时间比较都用它（超时、缓冲、冷却、成绩卡停留）。调用方传
     * 单调递增的毫秒（Swift 用 `CACurrentMediaTime() * 1000` 或 `DispatchTime.now().uptimeNanoseconds / 1_000_000`），
     * **不要传 `Date().timeIntervalSince1970`** —— 系统时间会被 NTP 校正，跳一次就
     * 可能把 20 秒的任务瞬间判定超时，或者让倒计时卡住不动。`MissionEngine`
     * 内部不做任何单调性保护（与 Android 侧同一个约定）。
     *
     * ## roll 的 null 语义（最容易做错的一条）
     * `rollValid == false` 时**必须**给 `MissionSample.roll = null`，**不是 0f**。
     * `TaskPicker.levelFeasible` 与 `TaskPicker.tiltDegrees` 都靠 `roll == null`
     * （且没有地平线）判断「**没有任何姿态源**」→ 不派水平任务；
     * 给 0f 会被读成「已经端平了」→ 派一条用户根本做不到的水平任务
     * （屏幕上明明歪着，卡却说「保持住」）。
     * 这与 [IosBridge.analyzeFrame] 里 `ScoreBoard.score(roll = null)` 是同一个坑，
     * 那边 `IosBridge.kt` 的注释有原文说明。
     *
     * ## 没有缓存时直接 idle
     * 第一次 tick 早于第一次 `analyzeFrame` 返回（App 刚进取景页，约 1~2 帧）时
     * 直接返回 [IosMissionDto.IDLE]：**不要**拿空的 `FrameStats` / `CompositionHint`
     * 去构造 `MissionSample` —— 0 值几何量会让 `TaskPicker` 判断失真
     * （`skinRatio = 0`、`subjectConfidence = 0` 虽然多数路径不可行，但
     * `distToTarget` 在 `targetX/Y` 与 0 之间的组合仍可能落在可行窗口内），
     * 结果是一张指不出主体的任务卡。
     *
     * ## remainingSeconds 的口径
     * `MissionEngine.remainingMs()` 向上取整成秒：刚派发时是 20s（不是 19s），
     * 归零那一刻恰好是超时判定那一 tick。`IDLE` 为 0。
     *
     * @param nowMs 单调时钟毫秒
     * @param roll 重力横滚角（度），正值表示画面右侧偏低
     * @param rollValid 本次姿态读数是否可信；false → 按「没有姿态源」处理
     * @return 这一 tick 的扁平快照；无缓存分析时返回 idle
     */
    fun tick(nowMs: Long, roll: Float, rollValid: Boolean): IosMissionDto {
        // 单次读取引用：读到的是「某一份完整的分析结果」，不会是中间态（见文件头）。
        val frame = cached ?: return IosMissionDto.IDLE

        engine.onSample(
            MissionSample(
                now = nowMs,
                stats = frame.stats,
                hint = frame.hint,
                // ★ rollValid == false → null，不是 0f。理由见上面「roll 的 null 语义」。
                roll = if (rollValid) roll else null,
                recs = frame.recs,
                aspect = DEFAULT_ASPECT
            )
        )

        val state = engine.state

        // 到位那一刻把参考分**冻结**下来：成绩卡上显示的是「做到那一刻」的分数。
        // 若每 tick 都读最新值，成绩卡停留的 8 秒里数字会随着画面变动一直跳
        //（Android 侧也是「显示分数卡那一刻取一次 lastScoreResult」，不是实时）。
        if (state == MissionState.ACHIEVED && lastPhase != MissionState.ACHIEVED) {
            awardedScoreValue = frame.total
            awardedMeasured = frame.allMeasured
        }
        // 回到 IDLE 才清空：任务真的结束了，下一轮是新的成绩。
        if (state == MissionState.IDLE && lastPhase != MissionState.IDLE) {
            awardedScoreValue = 0
            awardedMeasured = false
        }
        lastPhase = state

        val task = engine.current
        return IosMissionDto(
            phase = state.name,
            phaseRawValue = phaseRawValueOf(state),
            title = task?.title ?: "",
            hint = task?.hint ?: "",
            skillName = task?.skillName ?: "",
            tipId = task?.tipId ?: "",
            kind = task?.kind?.name ?: "",
            kindRawValue = kindRawValueOf(task),
            // 已统一成 0..1（构图=距离变小、水平=角度变小、留白=顶边变大），Swift 不要再翻。
            ringProgress = engine.ringProgress(),
            remainingSeconds = secondsCeil(engine.remainingMs()),
            awardedScore = awardedScoreValue,
            scoreMeasured = awardedMeasured,
            message = messageOf(state, task),
            // ACHIEVED 也要显示任务卡（卡片就地变「到位了」），见 IosMissionDto 类注释。
            showsTaskCard = state == MissionState.TASK_SHOWN ||
                state == MissionState.IN_PROGRESS ||
                state == MissionState.ACHIEVED,
            // 成绩卡只在 SCORED：ACHIEVED 阶段两张卡同屏会自相矛盾。
            showsScoreCard = state == MissionState.SCORED
        )
    }

    /**
     * 中断当前任务并回到 IDLE。幂等。
     *
     * Swift 侧在以下时机调用（与 Android 的 `abortMission` 一一对应）：
     * 拍照 / 切标签页 / App 进后台 / 相机不可用 / 关掉构图网格 / 用户点「再来一条」。
     *
     * ★ 中断后会加 3s 的重新派发节流（`MissionThresholds.RETRY_IDLE_MS`）——
     *   避免「关掉网格 → 立刻又派一条」。想允许立刻派发，接着调 [resume]。
     *
     * @param reason [AbortReason] 的枚举名（`CAPTURE` / `TAB` / `PAUSE` /
     *   `CAMERA_OFF` / `GUIDE_OFF` / `USER`，可调 [abortReasonNames] 取全集）。
     *   **非法值直接忽略**（不抛异常）：跨语言边界上抛异常最难定位，
     *   一个拼错的字符串不该让取景页崩掉。区分大小写，Swift 侧不要 lowercase()。
     */
    fun abort(reason: String) {
        val parsed = AbortReason.entries.firstOrNull { it.name == reason } ?: return
        engine.abort(parsed)
        // 任务被掐掉，成绩作废：不让上一次的分数漏到下一次成绩卡上。
        awardedScoreValue = 0
        awardedMeasured = false
        lastPhase = MissionState.IDLE
    }

    /**
     * 从 IDLE 恢复（切回照片页 / 重开网格 / 回到前台之后）：允许下一个 tick 立刻派任务。
     *
     * 只在 `IDLE` 时生效 —— 任务正在显示时「恢复」是没有意义的动作，
     * 也不该借它绕过状态机（想中断请用 [abort]）。
     */
    fun resume() {
        engine.resume()
    }

    /**
     * 中断原因的全集，逗号分隔，供 Swift 侧填诊断下拉 / 断言字符串合法。
     *
     * 为什么由 Kotlin 给而不是 Swift 写死：`AbortReason` 在 commonMain，将来加一种原因
     * 时只要改 Kotlin 一处，Swift 侧自动多一项；写死在 Swift 里就会悄悄少一个。
     * 与 [IosBridge.sceneNames] 同一个理由。
     *
     * @return 例如 `CAPTURE,TAB,PAUSE,CAMERA_OFF,GUIDE_OFF,USER`
     */
    fun abortReasonNames(): String = AbortReason.entries.joinToString(",") { it.name }

    // ── 内部换算 ───────────────────────────────────────────────────────────────

    /**
     * `MissionState` → Swift `MissionPhase` 的 rawValue。
     *
     * ★ 故意**不写 else 分支**：commonMain 新增状态时这里会编译失败，
     *   逼着改 iOS 侧；写 else 就会静默地把新状态当 idle（卡片不显示，且不报错）。
     */
    private fun phaseRawValueOf(state: MissionState): String = when (state) {
        MissionState.IDLE -> "idle"
        MissionState.TASK_SHOWN -> "shown"
        MissionState.IN_PROGRESS -> "inProgress"
        MissionState.ACHIEVED -> "achieved"
        MissionState.SCORED -> "scored"
    }

    /**
     * `TaskKind` → Swift `MissionKind` 的 rawValue。无任务时给空串（Swift 侧翻成 nil）。
     *
     * 同样不写 else：新增任务类型时编译失败。
     */
    private fun kindRawValueOf(task: MissionTask?): String = when (task?.kind) {
        null -> ""
        TaskKind.COMPOSITION -> "composition"
        TaskKind.LEVEL -> "level"
        TaskKind.HEADROOM -> "headroom"
    }

    /**
     * 成绩卡 / 任务卡的副句。
     *
     * - 任务阶段给副句（与任务卡上那一行同一句话，`MissionCardView` 自己也会画
     *   `task.hint`，这里保持一致，不做第二套措辞）；
     * - `SCORED` 给成绩卡那一句，措辞与 Android `showAchieveCard()` 逐字一致
     *   （「你刚才做对了：{技法名}」）—— 两端同一句话，改文案改 Kotlin 这一处，
     *   **不要在 Swift 里再拼一遍中文**；
     * - 技法名缺失时不留一个孤零零的冒号。
     */
    private fun messageOf(state: MissionState, task: MissionTask?): String = when (state) {
        MissionState.IDLE -> ""
        MissionState.TASK_SHOWN, MissionState.IN_PROGRESS, MissionState.ACHIEVED ->
            task?.hint.orEmpty()

        MissionState.SCORED -> {
            val name = task?.skillName.orEmpty()
            if (name.isEmpty()) "你刚才做对了" else "你刚才做对了：$name"
        }
    }

    /** 毫秒 → 秒，**向上取整**：刚派发显示 20s 而不是 19s，0 只在真正到点时出现。 */
    private fun secondsCeil(ms: Long): Int {
        if (ms <= 0L) return 0
        return ((ms + 999L) / 1000L).toInt()
    }
}

/**
 * 一次 `analyzeFrame` 的中间产物 —— **全字段 `val`，构造完即不可变**。
 *
 * 不可变是这个缓存能跨线程安全发布的前提（见文件头「单次引用发布」）。
 * 想给它加可变字段之前，先回读那一段。
 */
private class CachedFrame(
    val stats: FrameStats,
    val hint: CompositionHint,
    /** `ScoreBoard.buildRecommendations()` 的输出，已按扣分比例降序。 */
    val recs: List<Recommendation>,
    /** 本次参考分总分（0..100），到位那一刻冻结进成绩卡。 */
    val total: Int,
    /** 四维是否齐全；false 时成绩卡显示「参考分 --」而不是印一个残缺的分。 */
    val allMeasured: Boolean
)
