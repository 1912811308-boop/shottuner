// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Mission/MissionTypes.swift —— 任务卡的数据类型契约。
//
// ★ 与 ScoreTypes.swift 同样的定位：只放数据，不放逻辑。
//   Kotlin 侧的状态机负责「什么时候该出卡、出了之后怎么判达标」，
//   Swift 侧只负责「拿到什么就画什么」。
//
// ★ 为什么状态机必须留在 Kotlin：
//   MissionEngine 有 5 个状态、三重防抖（EMA 平滑 / 连续 N 次达标 / 单向锁定）、
//   冷却表、超时换卡、连续两次超时进静默期。这套逻辑两端必须完全一样 ——
//   任何一处"iOS 版手感不一样"都会被当成 bug 报上来。放到 Swift 重写一遍，
//   等于把 262 行最容易写错的状态迁移抄一遍，还必然走样。
//   所以它是**唯一状态源**，Swift 只做渲染。
//
// ★ 产品口径（改文案前先读 docs/ 里的任务卡规格）：
//   · 主句 ≤10 字，只说动作
//   · 副句 ≤16 字，告诉用户「怎么算到位」
//   · 原理不进卡片 —— 想学原理的用户自己点进技法图鉴
//   · 没有可派发的任务时卡片**整个消失**，不显示"暂无任务"这种占位

import Foundation

// MARK: - 任务种类

/// 三类可派发的任务。与 Kotlin `TaskKind` 一一对应。
///
/// 白名单只有这三条（Kotlin `TaskPicker.ELIGIBLE`）：
/// `landscape_horizon_level` → `.level`
/// `portrait_eyes_thirds`     → `.composition`
/// `portrait_headroom`        → `.headroom`
///
/// ★ 为什么不把所有建议都变成任务：
/// 评分会给出「曝光补偿降 0.3」这类建议，但它需要用户进专业模式改参数，
/// 15 秒内做不到。任务卡只派**靠移动手机就能完成**的事，否则用户会一直
/// 看着一张完成不了的任务卡，最后学会忽略它。
enum MissionKind: String, CaseIterable {
    case level
    case composition
    case headroom
}

// MARK: - 任务本体

/// 一张任务卡要显示的全部内容。
struct MissionTaskSnapshot: Equatable {

    /// 对应的技法 id，用来做「看这条技法」的跳转。
    var tipId: String

    var kind: MissionKind

    /// 主句，≤10 字。`{where}` 占位已由 Kotlin 侧替换成「左上」「右下」这类方位词。
    var title: String

    /// 副句，≤16 字。告诉用户怎么算做到。
    var hint: String

    /// 技法短名（如「三分法」「地平线端平」），卡片角标用。
    var skillName: String
}

// MARK: - 状态机相位

/// 五个相位。与 Kotlin `MissionState` 一一对应。
///
/// ```
/// IDLE ──pick──▶ TASK_SHOWN ──800ms──▶ IN_PROGRESS ──连续N次达标──▶ ACHIEVED
///                      ▲                    │                          │
///                      │                    │ 20s 超时                  │ 1.2s
///                      └──换一条────────────┘                          ▼
///                                                                  SCORED
///                                                                     │ 8s
///                                                                     ▼
///                                                                   IDLE
/// ```
enum MissionPhase: String, CaseIterable {
    /// 无任务，卡片不显示。
    case idle
    /// 刚派发，800ms 缓冲期（这段时间不计达标次数，防「环还没画出来就绿了」）。
    case shown
    /// 判定中，环跟着 `ringProgress` 长绿。
    case inProgress
    /// 已达标并锁定，1.2s 后转成绩卡。
    case achieved
    /// 成绩卡展示中，8s 后自动收起。
    case scored

    /// 是否应该显示任务卡（`scored` 显示的是成绩卡，另一种样式）。
    var showsTaskCard: Bool {
        self == .shown || self == .inProgress || self == .achieved
    }

    /// 是否应该显示成绩卡。
    var showsScoreCard: Bool { self == .scored }
}

// MARK: - 一次 tick 的结果

/// 状态机每 400ms 吐一次的完整快照。
///
/// ★ 为什么每帧都给全量而不是只给「变化了的部分」：
///   SwiftUI 靠 `Equatable` 判断要不要重绘，全量快照天然幂等；
///   增量事件一旦丢一条（比如页面切换时正好跨过一次 SHOW），UI 就会永久停在错的状态。
struct MissionSnapshot: Equatable {

    var phase: MissionPhase

    /// 当前任务。`phase == .idle` 时为 nil。
    var task: MissionTaskSnapshot?

    /// 环的绿意进度 0..1。`idle` 时恒为 0。
    ///
    /// 三个任务对"前进方向"的定义不同：构图是距离变小、水平是角度变小、
    /// 留白是主体顶边变大 —— 这个换算在 Kotlin `TaskPicker.progressOf` 里，
    /// Swift 侧拿到的是**已经统一成 0..1 的进度**，不要自己再算。
    var ringProgress: Float

    /// 剩余秒数（倒计时）。`idle` / `scored` 时无意义，读它之前先看 `phase`。
    var remainingSeconds: Int

    /// 成绩卡得分（0..100）。仅 `phase == .scored` 时有意义。
    var awardedScore: Int

    /// 成绩卡文案。仅 `phase == .scored` 时有意义。
    var message: String

    /// 是否应该显示任务卡。
    var showsTaskCard: Bool { phase.showsTaskCard && task != nil }

    /// 是否应该显示成绩卡。
    var showsScoreCard: Bool { phase.showsScoreCard }

    static let idle = MissionSnapshot(
        phase: .idle,
        task: nil,
        ringProgress: 0,
        remainingSeconds: 0,
        awardedScore: 0,
        message: ""
    )
}
