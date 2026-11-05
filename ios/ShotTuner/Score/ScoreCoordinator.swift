// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Score/ScoreCoordinator.swift —— iOS 侧评分链路的**唯一接线点**。
//
// 它做四件事，别的一概不管：
//   ① 从 CameraSession 收帧（`CVPixelBuffer`）；
//   ② 节流后把帧转成紧凑 I420，连同「传感器横滚角 + 构图提示」一起交给 Kotlin 门面
//      （`IosBridge.shared.analyzeFrame(...)`）；
//   ③ 把 Kotlin 吐回来的扁平 DTO 翻成 SwiftUI 能直接绑的 `ScoreSnapshot` /
//      `HistogramSnapshot`；
//   ④ 传感器与构图各自「只改自己那几个字段」地更新 `GuideSnapshot`。
//
// ★ 为什么评分不写在这里：
//   四维参考分、等级、提示卡文案全部在 commonMain（`ScoreBoard` / `FrameStatsCore` /
//   `FramingCoach`）。两端共用一份实现，iOS 侧只负责「喂数据 + 翻译结果」。
//   一旦 Swift 侧自己算分，两端就再也不可能对齐 —— 那是这份代码最想避免的事。
//
// ★ 为什么 `guide` 不从 DTO 回来：
//   横滚角与构图提示是**原生侧本来就有的数据**（CoreMotion / Vision），
//   送进 Kotlin 只是为了让「水平维度」参与评分。再让 Kotlin 把它们吐回来，
//   等于给同一份数据造两个来源，只会多一条会漂移的链路。
//
// ★ 线程模型（看图更快）：
//
//     videoQueue（后台，CameraSession 的采样回调）
//        └─ admit(now:)：节流判定 + 取一份输入快照 ──── 丢弃则 return，一行重活不做
//        └─ gate.queue（后台串行）
//              ├─ yuv420(from:)              —— 最贵的一步
//              ├─ IosBridge.analyzeFrame(...) —— 同步 Kotlin 调用
//              └─ Task { @MainActor }         —— 只把**纯 Swift 值类型**投回主线程
//                    └─ land(...) 写 @Published
//
//   ★ 为什么「投回主线程」只带 `ScoreSnapshot` / `HistogramSnapshot`：
//     它们是 Swift 值类型（字段全是 Int/String/Float/Bool，隐式 Sendable），
//     跨隔离边界零争议；而 `IosFrameAnalysisDto` 是 Kotlin 对象，不是 Sendable。
//     让 DTO 留在后台队列上被翻译掉，主线程只收成品。
//
// ★ 产品口径（改这些行为之前先想清楚）：
//   · 叫「参考分」，不叫"得分"/"评分"/"准确率"；
//   · 任一维度未测 ⇒ 总分位必须显示「参考分 --」，不拿 0 装成真实数字；
//   · 教用户「照拨」「建议值」，不替用户调。

import Combine
import CoreVideo
import Foundation
import QuartzCore
import Shared

// MARK: - 文件级常量

/// 本文件用到的固定字符串与节奏参数，集中在一处便于对照。
private enum ScoreDefaults {

    /// 落回场景。Kotlin 的 `SceneType.GENERAL` 在 Swift 侧的字符串口径。
    static let scene = "GENERAL"

    /// 两次分析之间的最短间隔（秒）。
    ///
    /// ★ 100ms ⇒ 上限 10fps。为什么不是 30fps：
    ///   一次 `analyzeFrame` 要做 160×120 网格采样 + 色度统计 + 拉普拉斯方差，
    ///   真机上是几毫秒到十几毫秒。30fps 全量分析会把 CPU 吃满、还让取景掉帧；
    ///   而**评分是慢变量**（手抖 0.3° 不该让分数跳），10fps 已经比人眼快得多。
    static let minimumInterval: CFTimeInterval = 0.1

    // ★ 「端平了没」的阈值**不在这里**：它由 `GuideSnapshot.levelOkDegrees`（2.5°）
    //   单点持有，理由见那个常量的注释（必须与 `MissionThresholds.LEVEL_ENTER_DEG` 同值）。
    //   本文件不再自带一份，避免出现「覆盖层绿了、任务卡说不达标」的分叉。
}

// MARK: - 跨线程的分析输入

/// 送进 Kotlin 的全部标量入参（每帧一份快照）。
///
/// ★ 为什么单独成一个值类型：这些字段由两条不同线程写
///   （`updateMotion` / `updateComposition` 在主线程，帧回调在后台队列），
///   每帧开始分析时**先取一份不可变快照**，就避免了「分析过程中被改了一半」的撕裂。
///
/// 默认值与 `GuideSnapshot.empty` 同口径，这样「传感器还没喂过」时
/// 送进 Kotlin 的是一组中性值，而不是 0 堆出来的假数据。
private struct AnalysisInputs {
    var roll: Float = 0
    var rollValid: Bool = false
    var sceneName: String = ScoreDefaults.scene
    var hintScore: Int32 = 0
    var tiltDeg: Float = 0
    var horizonDetected: Bool = false
    var horizonY: Float = 0.5
    var subjectX: Float = 0.5
    var subjectY: Float = 0.5
    var subjectConfidence: Float = 0
    var subjectSize: Float = 0
    var subjectLeft: Float = 0.4
    var subjectTop: Float = 0.4
    var subjectRight: Float = 0.6
    var subjectBottom: Float = 0.6
    var targetX: Float = 0.6667
    var targetY: Float = 0.3333
    var suggestMove: Bool = false
}

/// 帧门：节流时间戳、`generation` 计数器、帧序号、最近一次输入快照、清晰度缓存。
///
/// ★ 为什么单独一个类、而不是放进 `ScoreCoordinator`：
///   `ScoreCoordinator` 是 `@MainActor`，它的存储属性在非隔离上下文里**不能直接读写**，
///   而帧回调天生在后台队列上。把这几样跨线程状态收进一个自带锁的对象，
///   主线程与后台队列都只通过它的方法访问，隔离问题与竞态问题一起消失。
///
/// ★ `@unchecked Sendable` 是**有依据的**：唯一的不变量是「跨线程状态都在 `lock` 内读写」，
///   由下面每个方法自己保证；编译器看不到锁，所以只能由人来担保。
private final class ScoreGate: @unchecked Sendable {

    /// 节流判定的结果。
    enum Admission {
        /// 放行；带上这一代次、这一帧的序号与输入快照。
        case accepted(generation: UInt64, sequence: UInt64, inputs: AnalysisInputs)
        /// 距上一帧太近，直接丢弃（连转码都不要做）。
        case throttled
    }

    private let lock = NSLock()

    /// 上一次**放行**的时刻（`CACurrentMediaTime()` 口径）。
    private var lastAccepted: CFTimeInterval = 0

    /// 会话代次。`detach()` / `setScene()` 会让它 +1，用来作废排队中的旧结果。
    private var currentGeneration: UInt64 = 0

    /// 帧序号。同一代次内每放行一帧 +1，用来保证「旧的不会覆盖新的」。
    private var sequence: UInt64 = 0

    /// 已经落地到主线程的最大序号。
    private var lastLandedSequence: UInt64 = 0

    /// 本轮输入（传感器 + 构图 + 场景）。
    private var inputs = AnalysisInputs()

    /// 上一次实测到的清晰度维度。
    ///
    /// ★ 为什么放在这门里而不是 `ScoreCoordinator` 上：
    ///   DTO → `ScoreSnapshot` 的翻译在**后台队列**上做（DTO 不是 Sendable，
    ///   不能投回主线程），那里需要一个能安全读到的「上一次清晰度」。
    private var cachedClarity: DimensionSnapshot?

    /// 评分专用的**串行**后台队列。
    ///
    /// ★ 为什么放在这里而不是 `ScoreCoordinator` 上：
    ///   `ScoreCoordinator` 是 `@MainActor`，它的实例属性跨不过隔离边界；
    ///   而帧回调天生在后台队列上。放在这个自带锁的对象里，任何线程都能安全拿到。
    /// ★ 为什么不直接用 `CameraSession.videoQueue`：
    ///   那是采样回调队列，堵住它就会掉帧、取景卡顿。转码 + Kotlin 调用必须另开一条。
    /// ★ 为什么串行：`analyzeFrame` 是同步 Kotlin 调用，并发进来只会抢 CPU，
    ///   而且串行天然保证「后一帧的结果不会先到」。
    let queue = DispatchQueue(
        label: "com.shottuner.score",
        qos: .userInitiated
    )

    // MARK: 读写

    /// 做一次「节流 + 取快照」。**放行时才更新**时间戳与序号。
    /// - Parameter now: 当前单调时刻（`CACurrentMediaTime()`）。
    /// - Returns: 放行结果；`.throttled` 表示这一帧应当被整帧丢弃。
    func admit(now: CFTimeInterval) -> Admission {
        lock.lock()
        defer { lock.unlock() }
        if lastAccepted > 0, now - lastAccepted < ScoreDefaults.minimumInterval {
            return .throttled
        }
        lastAccepted = now
        sequence &+= 1
        return .accepted(generation: currentGeneration, sequence: sequence, inputs: inputs)
    }

    /// 这个结果是否还属于「当前这一代」。
    /// - Parameter generation: 分析发起时记下的代次。
    /// - Returns: `true` 表示仍然有效。
    func isCurrent(generation: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return generation == currentGeneration
    }

    /// 落地许可：代次一致**且**序号比已落地的更新，才允许动 `@Published`。
    ///
    /// ★ 为什么除了代次还要序号：`Task { @MainActor }` 的唤醒顺序不保证严格 FIFO，
    ///   偶尔会出现「后一帧先落地、前一帧后落地」。加上序号后，晚到的旧结果被丢弃，
    ///   界面上的分数就永远是「最新那一次分析」的结果，而不是随机抖一下。
    /// - Parameters:
    ///   - generation: 分析发起时记下的代次。
    ///   - sequence: 分析发起时记下的帧序号。
    /// - Returns: `true` 表示可以落地。
    func acceptLanding(generation: UInt64, sequence: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard generation == currentGeneration else { return false }
        guard sequence > lastLandedSequence else { return false }
        lastLandedSequence = sequence
        return true
    }

    /// 换代次（`detach` / `setScene` 用），并清掉节流时间戳，让下一帧立刻可分析。
    ///
    /// ★ 序号一并归零：序号只在同一代次内有意义，不清零会让新代次的第一帧
    ///   因为「序号不够大」被 `acceptLanding` 拒掉。
    func invalidate() {
        lock.lock()
        defer { lock.unlock() }
        currentGeneration &+= 1
        lastAccepted = 0
        sequence = 0
        lastLandedSequence = 0
    }

    /// 更新「本轮输入」中的某几项。
    /// - Parameter mutate: 在锁内就地修改（**不要在闭包里做重活**，它是持锁执行的）。
    func updateInputs(_ mutate: (inout AnalysisInputs) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        mutate(&inputs)
    }

    /// 只读地取一份输入快照（主线程判定 `isLevel` 时要读 `rollValid`）。
    var currentInputs: AnalysisInputs {
        lock.lock()
        defer { lock.unlock() }
        return inputs
    }

    /// 上一次实测的清晰度（可能为 `nil`，表示整条会话还没测过）。
    var clarityCache: DimensionSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        return cachedClarity
    }

    /// 记住本次实测的清晰度。
    /// - Parameter clarity: 本帧实测到的清晰度维度。
    func storeClarity(_ clarity: DimensionSnapshot) {
        lock.lock()
        defer { lock.unlock() }
        cachedClarity = clarity
    }

    /// 丢掉清晰度缓存（换场景 / 重挂载时用）。
    func clearClarity() {
        lock.lock()
        defer { lock.unlock() }
        cachedClarity = nil
    }
}

// MARK: - 协调器

/// 评分协调器：SwiftUI 绑它，它绑相机与传感器。
///
/// 用法（接线方在 ContentView 里）：
/// ```swift
/// @StateObject private var coordinator = ScoreCoordinator()
/// ...
/// .onAppear { coordinator.attach(to: cameraSession) }
/// .onDisappear { coordinator.detach() }
/// ```
///
/// ★ `@MainActor` 的理由：`@Published` 是给 SwiftUI 用的，只能在主线程改；
///   把整个类钉在主 actor 上，比每个属性各自小心更不容易出错。
///   重活（转码 + Kotlin 调用）在 `gate.queue` 里，不在主线程。
@MainActor
final class ScoreCoordinator: ObservableObject {

    // MARK: 发布状态（SwiftUI 直接绑）

    /// 四维参考分与提示卡。
    @Published private(set) var score: ScoreSnapshot = .empty

    /// 直方图（64 桶，已归一化）。
    @Published private(set) var histogram: HistogramSnapshot = .empty

    /// 覆盖层几何（横滚角 / 地平线 / 主体框 / 三分交点）。
    @Published private(set) var guide: GuideSnapshot = .empty

    /// 当前场景（Kotlin `SceneType` 的字符串口径）。
    @Published private(set) var sceneName: String = ScoreDefaults.scene

    /// 上一次成功分析的时刻。`nil` 表示还没跑出过结果。
    ///
    /// ★ UI 用它区分「还没开始」与「跑了但全未测」—— 两者都显示 `--`，
    ///   但前者不该出现「设备不支持」之类的误导文案。
    @Published private(set) var lastAnalysisAt: Date?

    // MARK: 私有状态

    /// 跨线程帧门（节流 + 代次 + 序号 + 输入快照 + 清晰度缓存）。
    ///
    /// ★ 只读一次（`let`），之后在 `attach` 里**按值传给帧回调**，
    ///   后台路径完全不经过 `self` —— 否则就是「从非隔离上下文读主 actor 属性」的编译错。
    private let gate = ScoreGate()

    /// 当前挂着的相机会话。`weak` 是因为会话的生命周期由界面持有。
    private weak var session: CameraSession?

    /// `sceneNames()` 给出的合法场景集合。为空表示「拿不到清单，不做校验」。
    private var validSceneNames: Set<String> = []

    init() {}

    // MARK: - 挂载 / 卸载

    /// 挂到相机会话上。
    ///
    /// ★ 这里**只做接线 + 合法性校验**，不在主线程构建任何 UI 列表 ——
    ///   场景清单是给 `setScene(_:)` 兜底用的，UI 要不要列出来是界面的事。
    ///
    /// ★ ★ **已知限制（刻意写死在这里，别当它不存在）：本方法会「独占」
    ///   `session.onFrame`。** `CameraSession.onFrame` 是**单槽回调**，
    ///   赋值即覆盖。若将来要在同一个 session 上并行挂第二个消费者
    ///   （例如实时 Vision 检测），**必须先把 `onFrame` 改成回调数组 / 一对多广播**，
    ///   不能在这里直接赋值 —— 直接赋值会**静默杀死**前一个消费者：
    ///   不崩、不报错，只是帧不来了，而且极难查到是谁覆盖的（半年后有人加了个
    ///   Vision 消费者发现帧不来，查两天）。改成数组时记得连 `CameraSession.detach`
    ///   一侧的清理一起改。
    /// - Parameter session: 相机会话。
    func attach(to session: CameraSession) {
        if let old = self.session, old !== session {
            old.onFrame = nil
            // 换了会话就是换了画面：上一份几何（主体框 / 地平线 / 横滚角）全部作废。
            // 不清的话，新会话第一份读数到达之前，覆盖层会照着旧画面的坐标画。
            guide = .empty
        }
        self.session = session
        refreshValidSceneNames()

        gate.updateInputs { inputs in
            inputs.sceneName = self.sceneName
        }

        // ★ 把 gate 取成局部常量再捕获，让帧回调不必回头摸 `self` 的隔离属性。
        //   帧回调在 `CameraSession.videoQueue` 上（后台），这里只做「节流 + 投递」，
        //   一行重活都不做 —— 见 `handleFrame(_:gate:coordinator:)`。
        let gate = self.gate
        session.onFrame = { [weak self] pixelBuffer in
            ScoreCoordinator.handleFrame(
                pixelBuffer,
                gate: gate,
                coordinator: self
            )
        }
    }

    /// 停止分析（页面消失时调）。
    ///
    /// ★ 除了摘掉回调，还要**换代次**：已经排在 `gate.queue` 里的帧算完回来时
    ///   代次对不上就会被丢弃，否则页面都关了还会往 `@Published` 写一次旧数据。
    ///
    /// ★ `guide` **整份落回 `.empty`**（不只是翻 `hasReading`）：
    ///   覆盖层上残留的任何几何量都是上一轮画面的 —— 主体框、地平线位置、三分交点、
    ///   横滚角，只要留下来，下次进页面、新一轮读数到齐之前就会照着画一遍。
    ///   整份重置比逐个字段清零更难漏。
    ///
    /// ★ `score` / `histogram` **故意不重置**：它们是「上一次分析的结果」，
    ///   页面还在退场动画里时抽掉数字会闪一下空白；而 `guide` 是**实时**几何，
    ///   留一帧就是错一帧。两者的时效性不一样，处理也就不一样。
    func detach() {
        gate.invalidate()
        gate.clearClarity()
        session?.onFrame = nil
        session = nil
        guide = .empty
    }

    // MARK: - 传感器与构图入口

    /// 传感器喂入（横滚角 / 俯仰角，单位：度）。
    ///
    /// ★ `rollValid == false` 表示这一次没有可信读数（刚拿起手机、CoreMotion 还没收敛）。
    ///   此时 `isLevel` 直接给 `false`（参考线保持白色），**不是**沿用上一次的绿色 ——
    ///   沿用旧值等于在「没有数据」的时候告诉用户「已经端平了」，那是假信号。
    ///   与 Android `GuideOverlayView` 的 `rollDeg ?: Float.NaN → levelOk = false` 同口径。
    ///
    /// ★ 一个采样只有一个有效性标志：`roll` 与 `pitch` 同帧同源，
    ///   所以 `pitchAvailable` 也用 `rollValid`（对应 Android 的 `pitchDeg != null`）。
    ///
    /// ★ 为什么这里也置 `hasReading = true`：`hasReading` 的契约文字是
    ///   「是否已经收到可信的**构图 / 姿态**读数」—— 一次 `rollValid == true` 的
    ///   CoreMotion 采样就是一份实测姿态，拿它画一条水平参考线不算撒谎。
    ///   若只认构图读数（严格对齐 Android 的 `hint != null`），那么 Vision 还没吐出
    ///   第一帧之前，手机明明端平了、线也是白的 —— 用户会以为功能坏了。
    ///   两条入口谁先到，覆盖层就从谁开始画；**任何一条都没到时一条线都不画**。
    /// - Parameters:
    ///   - rollDegrees: 横滚角（度），正值表示画面右侧偏低。
    ///   - pitchDegrees: 俯仰角（度）。
    ///   - rollValid: 本次读数是否可信。
    func updateMotion(rollDegrees: Float, pitchDegrees: Float, rollValid: Bool) {
        gate.updateInputs { inputs in
            inputs.roll = rollDegrees
            inputs.rollValid = rollValid
        }

        guide.rollDegrees = rollDegrees
        guide.pitchDegrees = pitchDegrees
        guide.pitchAvailable = rollValid
        // 姿态读数也是「真实测到的量」：收到它就允许覆盖层开始画（见 hasReading 的注释）。
        if rollValid {
            guide.hasReading = true
        }
        guide.isLevel = resolveIsLevel(rollValid: rollValid)
    }

    /// 构图提示喂入。
    ///
    /// ★ Vision 的检测是异步的、与相机帧不同步，所以它是**独立入口**：
    ///   一次构图结果会跟着之后若干帧一起送去评分，直到下一次更新。
    /// - Parameters:
    ///   - hintScore: common `CompositionAnalyzer` 给出的构图分（0..25）。
    ///   - tiltDeg: 画面内结构线倾角（度），正值表示右侧偏低。
    ///   - horizonDetected: 是否检测到可信地平线/水平结构线。
    ///   - horizonY: 地平线纵向位置（0..1）。
    ///   - subjectX: 主体重心 X（0..1）。
    ///   - subjectY: 主体重心 Y（0..1）。
    ///   - subjectConfidence: 主体显著度置信度（0..1）。
    ///   - subjectSize: 主体面积占比（0..1）。★ 只作评分入参，覆盖层不用它。
    ///   - subjectLeft: 主体外框左（0..1）。
    ///   - subjectTop: 主体外框上（0..1）。
    ///   - subjectRight: 主体外框右（0..1）。
    ///   - subjectBottom: 主体外框下（0..1）。
    ///   - targetX: 三分交点 X（0..1）。
    ///   - targetY: 三分交点 Y（0..1）。
    ///   - suggestMove: 是否有「把主体挪到交点」的建议。
    func updateComposition(
        hintScore: Int,
        tiltDeg: Float,
        horizonDetected: Bool,
        horizonY: Float,
        subjectX: Float,
        subjectY: Float,
        subjectConfidence: Float,
        subjectSize: Float,
        subjectLeft: Float,
        subjectTop: Float,
        subjectRight: Float,
        subjectBottom: Float,
        targetX: Float,
        targetY: Float,
        suggestMove: Bool
    ) {
        gate.updateInputs { inputs in
            inputs.hintScore = Int32(hintScore)
            inputs.tiltDeg = tiltDeg
            inputs.horizonDetected = horizonDetected
            inputs.horizonY = horizonY
            inputs.subjectX = subjectX
            inputs.subjectY = subjectY
            inputs.subjectConfidence = subjectConfidence
            inputs.subjectSize = subjectSize
            inputs.subjectLeft = subjectLeft
            inputs.subjectTop = subjectTop
            inputs.subjectRight = subjectRight
            inputs.subjectBottom = subjectBottom
            inputs.targetX = targetX
            inputs.targetY = targetY
            inputs.suggestMove = suggestMove
        }

        // ★ 只改构图那几项，**不碰** `rollDegrees` / `pitchDegrees` ——
        //   两个入口各自负责自己的字段，互相覆盖会让覆盖层抖动。
        guide.tiltDeg = tiltDeg
        guide.horizonDetected = horizonDetected
        guide.horizonY = horizonY
        guide.subjectX = subjectX
        guide.subjectY = subjectY
        guide.subjectConfidence = subjectConfidence
        guide.subjectLeft = subjectLeft
        guide.subjectTop = subjectTop
        guide.subjectRight = subjectRight
        guide.subjectBottom = subjectBottom
        guide.targetX = targetX
        guide.targetY = targetY
        guide.suggestMove = suggestMove
        // ★ 走到这里说明构图分析确实给了结果，覆盖层可以开始画了。
        //   注意：**主体置信度为 0 也算「有读数」** —— 那是「测了，画面里没有显著主体」，
        //   不是「还没测」。契约里区分的是「有没有测」，不是「测出来好不好」。
        //   这是「不撒谎」的另一半：`hasReading == false` 时视图一条参考线都不画，
        //   而不是拿 `GuideSnapshot.empty` 里的默认值画一条绿色的「你已端平」。
        guide.hasReading = true
        guide.isLevel = resolveIsLevel(rollValid: gate.currentInputs.rollValid)
    }

    /// 切换场景。
    ///
    /// ★ 换场景必须清掉清晰度缓存与节流时间戳：
    ///   上一场景的清晰度是**对另一类画面**测的，拿它兜底等于给用户一个错数字；
    ///   而立刻可分析则让用户切完场景能马上看到新结果。
    /// - Parameter name: Kotlin `SceneType` 的字符串口径，如 `PORTRAIT`。
    func setScene(_ name: String) {
        let resolved = resolveScene(name)
        guard resolved != sceneName else { return }
        sceneName = resolved
        gate.updateInputs { inputs in
            inputs.sceneName = resolved
        }
        gate.clearClarity()
        gate.invalidate()
    }

    // MARK: - 帧处理（后台）

    /// 收到一帧。**在 `CameraSession.videoQueue` 上调用**，不是主线程。
    ///
    /// ★ 为什么是 `static` 而不是实例方法：
    ///   `ScoreCoordinator` 被 `@MainActor` 隔离，它的实例方法在后台队列上
    ///   要么得标 `nonisolated`（那样又读不到 `gate` 这个隔离属性），要么就编不过。
    ///   做成 `static` 并把 `gate` 与 `coordinator` 显式传进来，
    ///   线程归属一眼可见：这个函数碰的全是 Sendable 的东西。
    ///
    /// ★ 顺序刻意是「先节流、再转码」：
    ///   `yuv420(from:)` 是整个链路里最贵的一步（一次逐行拷贝 + 一次全图色度下采样），
    ///   被节流丢掉的帧连这一步都不该做。
    /// - Parameters:
    ///   - pixelBuffer: 相机帧（FullRange 420）。
    ///   - gate: 跨线程帧门。
    ///   - coordinator: 结果落地目标（弱引用，已 detach 时为 `nil`）。
    private nonisolated static func handleFrame(
        _ pixelBuffer: CVPixelBuffer,
        gate: ScoreGate,
        coordinator: ScoreCoordinator?
    ) {
        let admission = gate.admit(now: CACurrentMediaTime())
        guard case let .accepted(generation, sequence, inputs) = admission else {
            // 低于 10fps 的节奏要求：这一帧连转码都不做，直接丢。
            return
        }

        gate.queue.async {
            // 转码失败（像素格式不对 / 内存锁不住）不崩、不猜，保留上一帧的结果。
            guard let planes = try? FrameStatsProducer.yuv420(from: pixelBuffer) else {
                return
            }

            // ★ 转码期间可能已经 detach 或换了场景 —— 这时候算出来的东西已经过时，
            //   连 Kotlin 都不用调。
            guard gate.isCurrent(generation: generation) else { return }

            let dto = IosBridge.shared.analyzeFrame(
                yPlane: FrameStatsProducer.kotlinByteArray(planes.y),
                uPlane: FrameStatsProducer.kotlinByteArray(planes.u),
                vPlane: FrameStatsProducer.kotlinByteArray(planes.v),
                yRowStride: Int32(planes.yRowStride),
                uRowStride: Int32(planes.uRowStride),
                width: Int32(planes.width),
                height: Int32(planes.height),
                // ★ 传 0 而不是上一次的复杂度：这个参数要的是「拉普拉斯方差」，
                //   不是分数。分数与方差不同量纲，传错等于污染清晰度。
                //   清晰度「沿用上一次」的兜底在 Swift 侧做（见 `clarityCache`）。
                focusFallback: 0,
                roll: inputs.roll,
                rollValid: inputs.rollValid,
                sceneName: inputs.sceneName,
                hintScore: inputs.hintScore,
                tiltDeg: inputs.tiltDeg,
                horizonDetected: inputs.horizonDetected,
                horizonY: inputs.horizonY,
                subjectX: inputs.subjectX,
                subjectY: inputs.subjectY,
                subjectConfidence: inputs.subjectConfidence,
                subjectSize: inputs.subjectSize,
                subjectLeft: inputs.subjectLeft,
                subjectTop: inputs.subjectTop,
                subjectRight: inputs.subjectRight,
                subjectBottom: inputs.subjectBottom,
                targetX: inputs.targetX,
                targetY: inputs.targetY,
                suggestMove: inputs.suggestMove
            )

            // 本轮没算成（尺寸过小、统计退化等）：保留上一帧，不要把界面清空。
            guard dto.ok else { return }

            // ★ DTO 在这里就被翻成 Swift 值类型，别让它跨隔离边界 ——
            //   它是 Kotlin 对象，不是 Sendable。
            let snapshot = makeScore(from: dto, lastClarity: gate.clarityCache)
            // 本次真的测到了才更新缓存；沿用旧值时不要把它写回去（避免「变新」）。
            if snapshot.clarity.measured, dto.clarity.measured {
                gate.storeClarity(snapshot.clarity)
            }
            let bars = makeHistogram(from: dto)

            // ★ 只有动 `@Published` 这一刻才回主线程，而且带的是纯值类型。
            Task { @MainActor [coordinator] in
                coordinator?.land(
                    snapshot: snapshot,
                    histogram: bars,
                    generation: generation,
                    sequence: sequence
                )
            }
        }
    }

    // MARK: - 结果落地（主线程）

    /// 把结果写到 `@Published` 上。**只在主线程调用**。
    ///
    /// ★ 落地前再核一次「代次 + 序号」：`Task { @MainActor }` 的唤醒顺序不保证 FIFO，
    ///   晚到的旧结果必须丢掉，否则界面会按「谁先被调度」随机闪回上一帧的分数。
    /// - Parameters:
    ///   - snapshot: 本帧的评分成品。
    ///   - histogram: 本帧的直方图成品。
    ///   - generation: 发起分析时记下的代次。
    ///   - sequence: 发起分析时记下的帧序号。
    private func land(
        snapshot: ScoreSnapshot,
        histogram bars: HistogramSnapshot,
        generation: UInt64,
        sequence: UInt64
    ) {
        guard gate.acceptLanding(generation: generation, sequence: sequence) else { return }
        score = snapshot
        histogram = bars
        lastAnalysisAt = Date()
    }

    // MARK: - DTO → Swift 类型

    /// DTO → `ScoreSnapshot`。**在后台队列上调用**，所以是 `nonisolated static`。
    ///
    /// ★ 为什么要重算 `total` / `anyUnmeasured`：
    ///   当 `clarity.measured == false` 且我们沿用了上一次的实测值时，
    ///   Kotlin 那一侧的四维是「有一个没测」，而我们这一侧四维都有值。
    ///   若照抄 DTO 的 `total` / `anyUnmeasured`，就会出现「四维都是数字、
    ///   总分却显示 --」的自相矛盾。所以只在**四维全部有值**时给总分，
    ///   否则总分归 0 并置 `anyUnmeasured = true`（UI 据此显示「参考分 --」）。
    ///
    /// ★ `grade` 不重算：等级阈值在 common 的 `ScoreBoard` 里，Swift 侧再写一遍
    ///   就是第二份实现，必然漂移。四维不全时干脆给空串（UI 不显示等级）。
    /// - Parameters:
    ///   - dto: Kotlin 结果（`ok == true` 的前提由调用方保证）。
    ///   - lastClarity: 上一次测到的清晰度，仅在本帧未测时兜底。
    /// - Returns: 可直接绑 UI 的快照。
    private nonisolated static func makeScore(
        from dto: IosFrameAnalysisDto,
        lastClarity: DimensionSnapshot?
    ) -> ScoreSnapshot {
        let level = makeDimension(key: .level, dto: dto.level)
        let exposure = makeDimension(key: .exposure, dto: dto.exposure)
        let composition = makeDimension(key: .composition, dto: dto.composition)
        var clarity = makeDimension(key: .clarity, dto: dto.clarity)
        if !clarity.measured, let fallback = lastClarity {
            clarity = fallback
        }

        let allMeasured = level.measured
            && exposure.measured
            && composition.measured
            && clarity.measured
        let total = allMeasured
            ? level.score + exposure.score + composition.score + clarity.score
            : 0

        return ScoreSnapshot(
            total: total,
            grade: allMeasured ? dto.grade : "",
            level: level,
            exposure: exposure,
            composition: composition,
            clarity: clarity,
            tipId: dto.tipId,
            tipText: dto.tipText,
            tipDimension: makeTipDimension(dto.tipDimension),
            anyUnmeasured: !allMeasured
        )
    }

    /// 单个维度 DTO → `DimensionSnapshot`。
    /// - Parameters:
    ///   - key: 该维度在 Swift 侧的稳定标识。
    ///   - dto: Kotlin 维度结果。
    /// - Returns: 维度快照。
    private nonisolated static func makeDimension(
        key: DimensionSnapshot.Key,
        dto: IosDimensionDto
    ) -> DimensionSnapshot {
        DimensionSnapshot(
            key: key,
            // Kotlin 的 label 带修饰（例如清晰维度是「清晰（估）」），一律优先用它；
            // 空串才退回本地标题，避免两端中文分叉。
            label: dto.label.isEmpty ? key.displayName : dto.label,
            score: Int(dto.score),
            max: Int(dto.max),
            measured: dto.measured
        )
    }

    /// `tipDimension` 字符串 → 维度键。
    ///
    /// ★ 空串是**正常值**（「这张没什么可挑的」），要映射成 `nil` 而不是报错。
    /// - Parameter text: Kotlin 下发的小写维度名。
    /// - Returns: 维度键；空串或未知值返回 `nil`。
    private nonisolated static func makeTipDimension(_ text: String) -> DimensionSnapshot.Key? {
        guard !text.isEmpty else { return nil }
        return DimensionSnapshot.Key(rawValue: text)
    }

    /// DTO 的 256 桶直方图 → 64 桶归一化快照。
    ///
    /// ★ 跨语言数组的读法：`KotlinFloatArray` 用 `get(index: Int32)`，长度是 `size`
    ///   （`Int32`）。这里先铺成 `[Float]` 再交给 `HistogramSnapshot.normalized(from:)`，
    ///   合并与归一化的规则只有那一份实现（按 max 合并，不是平均）。
    /// - Parameter dto: Kotlin 结果。
    /// - Returns: 直方图快照。
    private nonisolated static func makeHistogram(
        from dto: IosFrameAnalysisDto
    ) -> HistogramSnapshot {
        let source = dto.histogram
        let count = Int(source.size)
        guard count > 0 else { return .empty }

        var raw = [Float](repeating: 0, count: count)
        for index in 0..<count {
            raw[index] = source.get(index: Int32(index))
        }
        return HistogramSnapshot.normalized(from: raw)
    }

    // MARK: - 场景与水平的判定

    /// 读 `sceneNames()` 填充合法场景集合，并校正当前场景。
    ///
    /// ★ 拿不到清单（空串）时**不做校验**：宁可放行，也不要因为门面没实现
    ///   就把用户选的场景静默改掉。
    private func refreshValidSceneNames() {
        let raw = IosBridge.shared.sceneNames()
        let names = raw
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        validSceneNames = Set(names)

        let resolved = resolveScene(sceneName)
        if resolved != sceneName {
            sceneName = resolved
            gate.updateInputs { inputs in
                inputs.sceneName = resolved
            }
        }
    }

    /// 把任意输入规整成合法场景名。
    /// - Parameter name: 候选场景名。
    /// - Returns: 合法场景名；非法或为空时落回 `GENERAL`。
    private func resolveScene(_ name: String) -> String {
        guard !name.isEmpty else { return ScoreDefaults.scene }
        guard !validSceneNames.isEmpty else { return name }
        return validSceneNames.contains(name) ? name : ScoreDefaults.scene
    }

    /// 判定「画面是否已端平」——**覆盖层参考线该不该变绿**。
    ///
    /// ★ 逐行照抄 Android `GuideOverlayView` 的 `levelOk` 判据，不要各写一套：
    /// ```kotlin
    /// val theta = if (hint.horizonDetected) hint.tiltDeg else (rollDeg ?: Float.NaN)
    /// val levelOk = theta.isFinite() && abs(theta) <= LEVEL_OK_DEG   // 2.5
    /// ```
    /// 对应到 Swift 就是下面四行：有地平线看**画面结构线倾角**（最准），
    /// 没有地平线退到**手机横滚角**，两个源都没有 ⇒ `false`（线保持白色）。
    ///
    /// ★ 阈值用 `GuideSnapshot.levelOkDegrees`（2.5°），**不是** `LevelMath` 的 1°：
    ///   1° 是**评分**口径（`ScoreBoard.level` 走 `LevelMath.isLevel`），
    ///   2.5° 是**覆盖层 + 任务环**口径（`MissionThresholds.LEVEL_ENTER_DEG`）。
    ///   拿 1° 画线会让 iOS 的线比 Android 晚 1.5° 变绿，也会出现「线绿了但任务环不绿」。
    ///   两个口径各有各的消费者，混用哪一边都是错。
    ///
    /// ★ 开头那道 `hasReading` 闸门是**唯一的「没有读数就不许绿」保证点**：
    ///   `updateMotion` / `updateComposition` 里都可能是「刚挂上、还没收到采样」的状态，
    ///   那时 `guide` 里的角度是 `.empty` 的缺省值（0°），算出来必然是 `true`。
    ///   与其在两个入口各判一次（漏一处就漏一条假绿线），不如把判据收在这里一处 ——
    ///   **没有任何读数 ⇒ 不可能给出 `true`**。
    /// - Parameter rollValid: 本次横滚角是否可信。
    /// - Returns: 是否已端平；无读数时恒为 `false`。
    private func resolveIsLevel(rollValid: Bool) -> Bool {
        guard guide.hasReading else { return false }

        let theta: Float
        if guide.horizonDetected {
            theta = guide.tiltDeg
        } else if rollValid {
            theta = guide.rollDegrees
        } else {
            // 两个源都没有：不绿。不要沿用上一次的值 —— 那是「假装有数据」。
            return false
        }
        return abs(theta) <= GuideSnapshot.levelOkDegrees
    }
}
