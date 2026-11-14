// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/SceneDriver.swift —— 把 Vision 场景识别接到评分链上。
//
// ★ 这个文件补的是 iOS 侧最后一个「门面补了但没人调」的洞。
//   `VisionSceneDetector`（T11）和 `IosSceneBridge` 都写完了、也全绿，
//   但两边都没人调 —— 于是 `ScoreCoordinator.sceneName` 恒为 `GENERAL`，
//   夜景 / 逆光 / 人像三套专用规则**全部静默失效**：
//   评分按通用场景算、照拨按通用预设给、功能页只出通用建议。
//   失效是静默的，界面上看不出任何异常。
//
// ★ 为什么单开一个类，而不是塞进 `ScoreCoordinator`：
//   评分链在那 10fps 的热路径上，多塞一个 Vision 消费者会让「评分为什么变慢/变了」
//   变成一个要翻两层的问题。这里它是**旁路**：搭 `CameraSession.onFrameTap` 的车，
//   结果通过一个回调告诉接线方，评分链本身一行都不用改。
//
// ★ ★ 最要紧的一条：**`setScene` 会作废在途结果，不能每帧调。**
//   `ScoreCoordinator.setScene` 内部会 `gate.invalidate()` —— 会话代次 +1，
//   所有排队中的分析结果回来时代次对不上、全部丢弃。
//   如果每次 Vision 出结果都调一次，画面在两种场景间抖一下就会让评分**永远落不了地**
//   （每次快出结果就被作废）。这个 bug 在界面上表现为「参考分一直不出来」，
//   而日志里什么都看不到。
//   ⇒ 三道闸：连续 N 次一致才算数（去抖）、两次切换至少间隔 2 秒（限频）、
//     和自己当前值相同就什么都不做（`setScene` 本身也会 early return，但这里先挡掉避免白算）。

import AVFoundation
import CoreVideo
import Foundation
import Shared

/// 旁路的场景识别：喂帧 → Vision → 融合 → 去抖 → 通知接线方切场景。
///
/// 生命周期跟着取景页走：`start` 一次、`stop` 一次，中间不用管。
final class SceneDriver {

    // MARK: - 调参

    private enum Tuning {
        /// 连续多少次**一致**的判定才真正切换。
        ///
        /// ★ 3 次：Vision 节流是 ≤10fps，3 次约 0.3~0.6 秒 —— 够滤掉「镜头扫过」那一两帧，
        ///   又不至于让用户从室外走进室内后等太久。
        ///   取 1 就是「每帧都算数」，正是上面注释里那个把评分饿死的写法。
        static let requiredStreak = 3

        /// 两次切换之间的最小间隔（秒）。
        ///
        /// ★ 2 秒：`gate.invalidate()` 之后要等下一帧被受理并算完才有新读数。
        ///   间隔太短会连续作废、参考分长时间不更新；太长则场景跟手性差。
        ///   Vision 本身还有 ≤10fps 的节流，这里是第二道。
        static let minimumSwitchInterval: CFTimeInterval = 2.0
    }

    // MARK: - 依赖

    private let detector = VisionSceneDetector()

    /// 场景切换回调。由接线方接回 `ScoreCoordinator.setScene(_:)`。
    ///
    /// ★ 类型上标 `@MainActor`（注意是标**函数类型**，不是标变量）：
    ///   `setScene` 是主 actor 隔离的，接线方在闭包里直接调它会报
    ///   "Main actor-isolated method can not be referenced from a nonisolated context"。
    ///   把隔离写进函数类型，接线方就不需要自己再包一层 `Task { @MainActor in ... }`。
    ///   这个回调由 `consider` 在**主线程**上调用（Vision 的 completion 本来就回主线程）。
    var onSceneChange: (@MainActor (String) -> Void)?

    // MARK: - 去抖状态

    /// 最近一次已经**应用**的场景（Kotlin `SceneType` 名，如 `PORTRAIT`）。
    private var appliedScene = ""

    /// 当前正在攒的候选场景。
    private var candidate = ""

    /// 候选已经连续出现了几次。
    private var streak = 0

    /// 上一次真正切换的时刻（`CACurrentMediaTime()`，单调递增，不受改系统时间影响）。
    private var lastSwitchAt: CFTimeInterval = 0

    /// 是否在跑（幂等闸门）。
    private(set) var isRunning = false

    // MARK: - 生命周期

    /// 起表。重复调用安全。
    func start() {
        isRunning = true
        resetHysteresis()
    }

    /// 停表并清状态。
    ///
    /// ★ 清 `appliedScene` 而不只是翻开关：下次进页面时画面已经换了一轮，
    ///   沿用上一轮的「已应用场景」会让第一次切换被错误地判成「没变」。
    func stop() {
        isRunning = false
        resetHysteresis()
    }

    private func resetHysteresis() {
        appliedScene = ""
        candidate = ""
        streak = 0
        lastSwitchAt = 0
    }

    // MARK: - 入口

    /// 喂一帧。**在 `CameraSession.videoQueue` 上调用，必须立刻返回。**
    ///
    /// ★ 这里只做一次「转交」：真正的节流、跳队列、算完回主线程都在
    ///   `VisionSceneDetector.submit` 内部完成（它保证 ≤10fps 且上一轮没完就不受理）。
    ///   所以本方法里**不能**有任何同步耗时工作。
    ///
    /// ★ `orientation: .right`：竖屏拍摄时相机给的是横向缓冲，不转就会把人脸框和
    ///   朝向整体歪 90°。本 App 的取景页锁竖屏，所以恒为 `.right`。
    ///   （若将来支持横屏取景，这里要按 `Display` 的旋转换朝向。）
    func submit(pixelBuffer: CVPixelBuffer) {
        guard isRunning else { return }
        detector.analyze(pixelBuffer: pixelBuffer, orientation: .right) { [weak self] ai in
            // detector 文档保证 completion 在主线程，但类型上没有 `@MainActor` 标注，
            // 所以显式跳一次 —— `assumeIsolated` 更快，但它一旦猜错就是运行期 trap，
            // 这个不在关键路径上，不值得为省一次派发去赌。
            Task { @MainActor [weak self] in
                self?.consider(ai)
            }
        }
    }

    // MARK: - 融合与去抖

    /// 拿一次 Vision 结果去问 Kotlin 侧要最终场景，然后决定要不要切换。
    ///
    /// ★ 融合（规则 + AI 谁说了算）**留在 Kotlin 的 `SceneClassifier`**：
    ///   那是两端共用的一份实现，`IosSceneBridge.classify` 只是把参数递进去。
    ///   在 Swift 里再写一遍「人像优先于风景」就是第二份实现，早晚和 Android 分叉。
    @MainActor
    private func consider(_ ai: AiVisionResult) {
        guard isRunning else { return }

        let dto = IosSceneBridge.shared.classify(
            aiOk: ai.ok,
            // Kotlin 枚举的 `name` 就是 `PORTRAIT` / `LANDSCAPE` 这种大写串，
            // 与 `IosSceneBridge.aiSceneOf` 的解析口径一致（不用再自己映射一遍，
            // 那会变成第二张表，加一个枚举值时必然漏掉一处）。
            aiSceneName: ai.scene.name,
            faceCount: ai.faceCount,
            faceArea: ai.faceArea,
            aiConfidence: ai.confidence
        )

        // 空串 = 还没有帧统计量（冷启动第一帧之前）。此时**不猜**，
        // 保持上一份场景 —— 猜一个 GENERAL 会把已经识别对的场景打回去。
        let name = dto.sceneName
        guard !name.isEmpty else { return }

        // 已经是这个场景：清掉候选，不做任何事。
        if name == appliedScene {
            candidate = ""
            streak = 0
            return
        }

        if name == candidate {
            streak += 1
        } else {
            candidate = name
            streak = 1
        }

        guard streak >= Tuning.requiredStreak else { return }

        // 限频：刚切过就再等一会儿，避免连续 `invalidate()` 把评分饿死。
        let now = CACurrentMediaTime()
        if lastSwitchAt > 0, now - lastSwitchAt < Tuning.minimumSwitchInterval {
            return
        }

        appliedScene = name
        lastSwitchAt = now
        // 切完就把候选清掉：下次同一个场景来了重新从 0 攒，
        // 否则 `streak` 会一直涨，等于失去去抖意义。
        candidate = ""
        streak = 0

        onSceneChange?(name)
    }
}
