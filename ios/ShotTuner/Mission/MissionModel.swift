// SPDX-License-Identifier: MIT
//
// MissionModel.swift —— 任务卡的 Swift 侧驱动。
//
// ★ 它解决的是「谁来每 400ms 敲一次 Kotlin 状态机」这个问题。
//   `MissionEngine` 在 commonMain，两端同一份实现；Android 侧在
//   `renderComposition()` 末尾每 400ms 喂一次 `onSample`，iOS 侧没有
//   `renderComposition` 这样的定时渲染点，所以必须自己起一个 400ms 的定时器。
//
// ★ 为什么不能跟着 `ScoreCoordinator` 的 10fps 走：
//   `MissionEngine` 内部用 EMA（`MissionThresholds.SMOOTH_ALPHA`）**逐 tick** 平滑，
//   所有阈值与超时都是毫秒。按 10fps 喂，EMA 收敛快 4 倍、20 秒超时里的
//   「连续 N 次达标」也会提前满足 —— 两端判定节奏分叉，而且**分叉了不会有任何报错**。
//   400ms 是 Android 侧的既定口径（`§3.5`），这里是刻意对齐，不是随手取的数。
//
// ★ 这一层只做三件事：拿当前 roll → 调 Kotlin → 把 DTO 翻成 Swift 值类型。
//   任何判定、计时、文案都不在这里写。写了就是第二份实现。

// ★ `import Combine` 不能省：`ObservableObject` 与 `@Published` 都定义在 Combine 里，
//   `Foundation` 不会替它递过来，KMP 的 `Shared` 框架更不会。
//   本仓另外两个用同样符号的文件（`ScoreCoordinator.swift:54`、
//   `CameraPermissionModel.swift:10`）都显式写了这一行 —— 漏了就是
//   "cannot find type 'ObservableObject' in scope" + "unknown attribute 'Published'"，
//   而且会连带把 `ContentView` 里的 `@StateObject private var mission` 一起拖红。
import Combine
import Foundation
import Shared

/// 任务卡的数据源。
///
/// ★ `@MainActor`：`@Published` 只能主线程写，且 `IosMissionBridge.tick` 内部会动
///   状态机（非线程安全），必须与 UI 同一条线程。定时器挂在主 RunLoop 上，
///   所以「主线程」这件事是结构上成立的，不只是靠约定。
@MainActor
final class MissionModel: ObservableObject {

    // MARK: - 对外状态

    /// 最近一次 tick 的完整快照。视图直接绑它。
    ///
    /// ★ 只有真的变了才赋值（`!=` 靠 `MissionSnapshot: Equatable`）：
    ///   每 400ms 无脑赋值会让 SwiftUI 在「什么都没变」时也重算一次 body，
    ///   而 body 里有 `Canvas` —— 代价不小。
    @Published private(set) var snapshot: MissionSnapshot = .idle

    /// 是否正在驱动状态机。
    var isRunning: Bool { timer != nil }

    // MARK: - 内部

    /// 400ms。**必须与 Android 一致**，理由见文件头。
    private static let tickInterval: TimeInterval = 0.4

    private var timer: Timer?

    /// 取当前姿态读数。由接线方注入，本类不自己去读传感器。
    ///
    /// ★ 不直接持有 `MotionProvider` 的理由：姿态的**唯一真相**在
    ///   `ScoreCoordinator.updateMotion` 写的那份 `guide` 里。
    ///   本类再存一份自己的 roll 就成了第二个来源，两份一定会不同步
    ///   （时序上必然有几十毫秒的窗口）—— 而水平任务的判定就吃这个值。
    ///
    /// ★ 属性类型上的 `@MainActor` **不能省**：`start(rollProvider:)` 的形参是
    ///   `@escaping @MainActor () -> ...`，若这里写成裸 `() -> ...`，
    ///   赋值那一步就是「丢掉全局 actor」的转换（该告警在 Swift 6 会升级成错误）。
    ///   同仓 `MotionProvider.swift:112` 的 `onSample` 就是正确写法，照着来的。
    private var rollProvider: (@MainActor () -> (roll: Float, valid: Bool))?

    // MARK: - 生命周期

    /// 开始驱动。重复调用安全（会先停）。
    /// - Parameter rollProvider: 每次 tick 取一次当前 `(横滚角, 是否可信)`。
    func start(rollProvider: @escaping @MainActor () -> (roll: Float, valid: Bool)) {
        stop()
        self.rollProvider = rollProvider

        // ★ 从 IDLE 恢复：`MissionEngine` 的重新派发有 3s 节流（`RETRY_IDLE_MS`），
        //   进入页面时调一次 `resume()` 让第一条任务立刻可派，
        //   否则用户会看见「打开相机，4 秒后才有任务卡」。
        IosMissionBridge.shared.resume()

        // ★ 用 `Timer(timeInterval:repeats:block:)` + `RunLoop.main.add(_:forMode: .common)`
        //   而不是 `Timer.scheduledTimer`：后者只登记在 `.default` 模式，
        //   用户一拖滚动视图就停摆 —— 而 `MissionEngine` 的超时是**按时间戳算**的，
        //   定时器停摆期间不 tick，恢复后第一 tick 会一次性把这段静默算成「超时」。
        let timer = Timer(timeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            // ★ block 的闭包类型在静态上是**非隔离**的（`@Sendable`），
            //   直接调 `@MainActor` 的 `tick()` 编不过。用 `Task { @MainActor }`
            //   把它拉回主 actor —— 与 `MotionProvider` 里的写法一致。
            //   RunLoop 本来就在主线程，这里只是让类型系统也知道。
            Task { @MainActor [weak self] in
                self?.tick()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer

        // 立刻先给一帧，避免最多 400ms 的空白。
        tick()
    }

    /// 停止驱动。**幂等**，停完不会再有 tick。
    func stop() {
        timer?.invalidate()
        timer = nil
        rollProvider = nil
        // ★ 快照要落回 idle：卡片是「实时」的东西，
        //   页面都关了还留着一张任务卡在内存里，下次进来会先闪一下旧文案。
        snapshot = .idle
    }

    // MARK: - 外部动作

    /// 中断当前任务（拍照 / 切后台 / 关网格 / 用户点「再来一条」）。
    ///
    /// - Parameter reason: `AbortReason` 的枚举名，**区分大小写**：
    ///   `CAPTURE` / `TAB` / `PAUSE` / `CAMERA_OFF` / `GUIDE_OFF` / `USER`。
    ///   Kotlin 侧对非法值直接忽略，不抛异常。
    func abort(_ reason: String) {
        IosMissionBridge.shared.abort(reason: reason)
        // 立刻拉一帧，让卡片当场消失，不等下一个 400ms。
        // （`tick()` 内部有空缓存早返回，会安全地拿到 idle。）
        tick()
    }

    // MARK: - 单次 tick

    private func tick() {
        guard let rollProvider else { return }
        let reading = rollProvider()

        // ★ `ProcessInfo.systemUptime` 是**单调**时钟（秒，含小数），
        //   与 Android 的 `SystemClock.elapsedRealtime()` 同一个用途：
        //   `MissionEngine` 只做差值比较，绝不能喂 `Date()`（会因用户改系统时间而跳变）。
        let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000.0)

        let dto = IosMissionBridge.shared.tick(
            nowMs: nowMs,
            roll: reading.roll,
            rollValid: reading.valid
        )
        let next = Self.map(dto)
        if next != snapshot {
            snapshot = next
        }
    }

    // MARK: - DTO → Swift 值类型

    /// 把 Kotlin 的扁平 DTO 翻成 Swift 契约。
    ///
    /// ★ 用 Kotlin 给的 `phaseRawValue` / `kindRawValue`，**不要**拿 `phase` / `kind`
    ///   自己转小写：`MissionState.IN_PROGRESS.name` 是 `"IN_PROGRESS"`，
    ///   lowercased 之后是 `"in_progress"`，而 `MissionPhase.inProgress` 的
    ///   rawValue 是 `"inProgress"` —— 差一个下划线，`MissionPhase(rawValue:)` 会
    ///   静默返回 nil，卡片永远不显示且不报错。
    private static func map(_ dto: IosMissionDto) -> MissionSnapshot {
        let phase = MissionPhase(rawValue: dto.phaseRawValue) ?? .idle

        // 没有任务就不造任务对象 —— `MissionCardView` 靠 `task == nil` 整卡不画。
        let task: MissionTaskSnapshot? = {
            guard phase != .idle, !dto.title.isEmpty else { return nil }
            guard let kind = MissionKind(rawValue: dto.kindRawValue) else { return nil }
            return MissionTaskSnapshot(
                tipId: dto.tipId,
                kind: kind,
                title: dto.title,
                hint: dto.hint,
                skillName: dto.skillName
            )
        }()

        // ★ `Int(...)` 转换不能省：Kotlin `Int` 过边界是 `Int32`，
        //   Swift 侧要 `Int` 才能直接喂给 `Text("\(...)")` 与 `Int` 字段。
        return MissionSnapshot(
            phase: phase,
            task: task,
            ringProgress: dto.ringProgress,
            remainingSeconds: Int(dto.remainingSeconds),
            awardedScore: Int(dto.awardedScore),
            // ★ 这个标志必须透传，不能丢：它决定成绩卡印数字还是「参考分 --」。
            //   到位那一刻四维大概 14/15 概率不齐（清晰度每 15 帧才实测一次），
            //   丢掉它就会把一个偏低的残缺总分当成真分印出来。
            scoreMeasured: dto.scoreMeasured,
            message: dto.message
        )
    }
}
