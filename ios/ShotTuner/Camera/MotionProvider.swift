// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/MotionProvider.swift —— 姿态数据源（`CMMotionManager`）。
//
// 它是 `ScoreCoordinator.updateMotion(rollDegrees:pitchDegrees:rollValid:)` 的
// **唯一调用方**。没有它，链路会静默断在这里：
//
//     updateMotion 零调用
//        → AnalysisInputs.rollValid 恒 false
//        → analyzeFrame 传 roll = null
//        → ScoreBoard.level() 在 horizonDetected == false 时返回 measured = false
//        → anyUnmeasured = true
//        → **总分永远显示「参考分 --」**
//
// ★ 注意上面这条链**不崩、不报错**：只是用户永远拿不到分数，然后觉得这个 App 坏了。
//   这正是这份文件存在的理由，也是它必须被接上线的原因。
//
// ★ Android 侧对应物是 `app/.../sensors/LevelSensor.kt`：
//   `TYPE_GRAVITY`（拿不到才退回 `TYPE_ACCELEROMETER`）→ `LevelMath.solve(...)` → 约 30Hz 回调。
//   这里做同样三件事：拿重力 → 算角度 → 节流到 30Hz。
//
// ★ 为什么角度是**在这里**算的（而不是调 Kotlin 的 `LevelMath.solve`）：
//   ① `LevelMath.solve` 要四个入参，其中 `rotationDegrees`（屏幕方向）本类没有来源
//      —— iOS 锁竖屏，Android 那边是读 `WindowManager.defaultDisplay.rotation`；
//   ② 30Hz 下每秒跨一次语言边界、拿回一个 Kotlin 对象，不如两行三角函数。
//   ⚠️ **这是一处刻意的重复**：`ingest(...)` 的第 ④ 步是 `LevelMath.solve` 竖屏分支的
//   逐行镜像。`LevelMath` 的公式一旦改动，这里必须同步改 —— 两边分叉不会有任何报错，
//   只会让 iOS 的水平仪与 Android 的判断慢慢不一致。
//   符号不靠猜：靠「Android 的 `TYPE_GRAVITY` 与 CoreMotion 的 `gravity` 差一个负号」
//   这条可查证的事实锚定，推导写在 `ingest(...)` 的第 ③ 步。
//
// ★ 零网络、零第三方：只用 `CoreMotion`（系统框架）。

import CoreMotion
import Foundation

/// 姿态采样器：把 `CMDeviceMotion` 的重力向量变成 App 约定的横滚角 / 俯仰角。
///
/// 用法（接线方在 `ContentView` 里）：
/// ```swift
/// @State private var motion = MotionProvider()
/// ...
/// .onAppear {
///     motion.start { roll, pitch, valid in
///         coordinator.updateMotion(
///             rollDegrees: roll,
///             pitchDegrees: pitch,
///             rollValid: valid
///         )
///     }
/// }
/// .onDisappear { motion.stop() }
/// ```
///
/// ★ `@MainActor` 的理由：回调闭包要直接写 `ScoreCoordinator`（它也是 `@MainActor`），
///   把回调声明成 `@MainActor` 就省掉了调用方每次都写 `Task { @MainActor in ... }` 的样板，
///   也把「回调在主线程」这件事**写进类型**，而不是只写在注释里。
///   本类的公共面（`start` / `stop` / `isAvailable`）只有在主线程才有意义，钉住正好。
@MainActor
final class MotionProvider {

    // MARK: - 调参常量

    /// 全部可调参数集中一处。改之前先读注释，尤其是 `rollSign` / `pitchSign`。
    private enum Tuning {

        /// 采样间隔（秒）⇒ 30Hz。
        ///
        /// ★ 为什么不是 60Hz：`ScoreCoordinator` 那边的帧分析节流在 10fps，
        ///   覆盖层根本用不到 60Hz 的刷新；而 CoreMotion 的融合滤波在 30Hz 下已经足够稳。
        ///   60Hz 只是白烧电。
        static let updateInterval: TimeInterval = 1.0 / 30.0

        /// 预热时长（秒）：`start()` 之后这段时间内的采样一律不回调。
        ///
        /// ★ CoreMotion 的 device-motion 融合滤波在刚启动的头几帧还没收敛
        ///   （`gravity` 可能还是 0 或抖得厉害）。宁可让覆盖层多等 200ms，
        ///   也不要喂一个假读数进去 —— `valid = false` 时绿线不亮，那是安全的方向。
        ///   200ms @30Hz ≈ 6 帧。
        static let warmUpInterval: TimeInterval = 0.2

        /// 重力向量模长的下限（单位：G）。
        ///
        /// ★ 静止时 `CMDeviceMotion.gravity` 的模长恒为 1（它是归一化的重力方向），
        ///   启动瞬间或自由落体时会趋近 0。低于 0.9 说明这个向量没有意义，
        ///   拿它算 `atan2` 只会得到噪声角度。
        static let minimumGravityG: Float = 0.9

        /// 弧度 → 度。
        static let radiansToDegrees: Float = 180.0 / Float.pi

        /// `rollDegrees` 的最终符号因子。**正常情况恒为 `+1.0`。**
        ///
        /// ★ 它不是符号推导的一部分 —— 下面的 `atan2` 公式**本身已经带对了符号**，
        ///   乘 1.0 等于不变。留这个常量是为了「真机上一测发现整体反了」时，
        ///   只改这一个字符，不用碰任何一行算式。
        ///
        /// ★ 真要翻转时改这里，**不要改调用点、不要改 `atan2` 的参数顺序**：
        ///   参数顺序（`atan2(gx, gy)`）是 `LevelMath.solve` 的口径，改了会与 Android 分叉。
        static let rollSign: Float = 1.0

        /// `pitchDegrees` 的最终符号因子。**正常情况恒为 `+1.0`**，理由同 `rollSign`。
        static let pitchSign: Float = 1.0
    }

    // MARK: - 状态

    /// CoreMotion 入口。**`init` 里只创建对象，不探测能力、不启动** —— 见 `start(onSample:)`。
    private let manager = CMMotionManager()

    /// 采样回调。`stop()` 之后置 `nil`，所以「停完之后还在回调」在结构上不可能发生。
    private var onSample: (@MainActor (_ rollDegrees: Float, _ pitchDegrees: Float, _ valid: Bool) -> Void)?

    /// 是否正在采样。
    ///
    /// 它也编码了「设备有没有 deviceMotion」：拿不到就不置 `true`，
    /// 所以不需要再存一份 `isDeviceMotionAvailable` 的快照 —— 那种快照会过期。
    private var running = false

    /// 预热起点（`CMDeviceMotion.timestamp` 口径）。
    ///
    /// ★ 用 `Optional` 而不是「0 表示还没开始」：`timestamp` 是「开机以来的秒数」，
    ///   理论上非 0，但拿 0 当哨兵值属于「碰巧能工作」，换成可选值就没有这个隐含假设。
    private var warmUpStartedAt: TimeInterval?

    /// 是否拿到了可用的 deviceMotion（设备有陀螺仪 + 已 `start()`）。
    ///
    /// ★ `false` 时调用方应当**静默**不画水平参考线，不置灰、不报错、不显示 0°。
    ///   与 Android `LevelSensor.available == false` 的处理方式保持一致。
    var isAvailable: Bool { running }

    init() {}

    // MARK: - 生命周期

    /// 开始采样。**回调在主线程（主 actor）**。
    ///
    /// ★ 重复调用是安全的：会先 `stop()` 再重启，新闭包替换旧闭包，
    ///   预热窗口也重新计时（换了闭包就当换了会话，别拿旧窗口的余温当收敛）。
    /// - Parameter onSample: 每帧回调。
    ///   - `rollDegrees`：横滚角（度），**正值表示画面右侧偏低**（与 `GuideSnapshot` 同约定）。
    ///   - `pitchDegrees`：俯仰角（度），**正值表示镜头朝上**，0 = 手机竖直，-90 = 正对地面。
    ///   - `valid`：本次采样是否可信。`false` 时调用方必须**忽略角度**。
    func start(
        onSample: @escaping @MainActor (_ rollDegrees: Float, _ pitchDegrees: Float, _ valid: Bool) -> Void
    ) {
        // 先停：重复 start 不能留两份 handler 在跑（否则回调会翻倍）。
        stop()

        // ★ 能力探测放在这里而不是 `init`：
        //   `init` 里探测的结果可能被存成一份会过期的快照，而且对象刚创建时探测没有意义。
        guard manager.isDeviceMotionAvailable else {
            return
        }

        self.onSample = onSample
        manager.deviceMotionUpdateInterval = Tuning.updateInterval
        warmUpStartedAt = nil
        running = true

        // ★ `.xArbitraryZVertical`：z 轴对齐重力方向，x 轴任意（不做绝对方位）。
        //   为什么不用 `.xMagneticNorthZVertical`：那需要磁力计，室内会被钢筋 / 电器干扰，
        //   而且我们**根本不需要绝对方位** —— 只要重力方向就能算横滚和俯仰。
        //   少一个数据源 = 少一类现场问题。
        //
        // ★ `to: .main`：直接在主队列回调，省掉一次 `DispatchQueue.main.async`。
        //   每帧只做四次浮点运算 + 一次闭包调用，主线程完全扛得住。
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let motion else { return }

            // ★ 只从这里取**标量**：`CMDeviceMotion` 不是 `Sendable`，别让它跨进 `Task`。
            //   （下面用 `Task { @MainActor }` 投递，闭包是 `@Sendable` 的。）
            let gx = Float(motion.gravity.x)
            let gy = Float(motion.gravity.y)
            let gz = Float(motion.gravity.z)
            let timestamp = motion.timestamp

            // ★ 为什么还要跳一次主 actor：handler 虽然跑在主队列上，但它的闭包类型
            //   在静态上是**非隔离**的，直接调 `@MainActor` 的 `ingest` 编不过
            //   （Swift 5.9 不会替你把「主队列」认成「主 actor」）。
            //   `Task { @MainActor }` 是代价最小又无歧义的写法：
            //   30Hz 下每秒 30 个小任务可以忽略；主 actor 执行器是 FIFO 的，
            //   同一线程按序创建的任务按序执行，采样不会乱序。
            Task { @MainActor [weak self] in
                self?.ingest(gravityX: gx, gravityY: gy, gravityZ: gz, timestamp: timestamp)
            }
        }
    }

    /// 停止采样。**幂等**，且停完之后一定不会再有回调。
    ///
    /// ★ 切到后台必须调用（省电，也是 App Store 的常识要求）。
    /// ★ 这里不写 `deinit`：`@MainActor` 类的 `deinit` 在 Swift 5.9 里不是主 actor 隔离的，
    ///   在里面调 `stopDeviceMotionUpdates` 会被并发检查拦下。所以**生命周期由调用方负责**。
    func stop() {
        guard running || onSample != nil else { return }
        running = false
        onSample = nil
        warmUpStartedAt = nil
        manager.stopDeviceMotionUpdates()
    }

    // MARK: - 单帧处理（主线程）

    /// 处理一帧重力向量。**在主 actor 上调用**。
    ///
    /// 顺序刻意是「先判可信、再算角度」：不可信的帧连三角函数都不算，
    /// 也就不可能算出一个「看起来很合理」的假角度。
    /// - Parameters:
    ///   - gravityX: `CMDeviceMotion.gravity.x`（单位 G，向右为正）。
    ///   - gravityY: `CMDeviceMotion.gravity.y`（向上为正）。
    ///   - gravityZ: `CMDeviceMotion.gravity.z`（垂直屏幕向外为正）。
    ///   - timestamp: `CMDeviceMotion.timestamp`（秒）。
    private func ingest(
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        timestamp: TimeInterval
    ) {
        // `stop()` 之后可能还有已经排进主队列的帧 —— 这里再兜一次。
        guard running, let onSample else { return }

        // ── ① 预热窗口 ────────────────────────────────────────────────
        // 第一帧只用来记起点，不回调：此刻的 `gravity` 还没收敛，给出去就是假数据。
        guard let warmUpStart = warmUpStartedAt else {
            warmUpStartedAt = timestamp
            return
        }
        guard timestamp - warmUpStart >= Tuning.warmUpInterval else { return }

        // ── ② 数值健全性 ──────────────────────────────────────────────
        guard gravityX.isFinite, gravityY.isFinite, gravityZ.isFinite else {
            onSample(0, 0, false)
            return
        }
        let magnitude = (gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ).squareRoot()
        guard magnitude.isFinite, magnitude >= Tuning.minimumGravityG else {
            onSample(0, 0, false)
            return
        }

        // ── ③ 取反：CoreMotion 的 gravity → 传感器「反作用力」──
        //
        // ★ 这一行是全文件最要紧的地方，锚点是**两个平台上可查证的事实**：
        //   · Android `TYPE_GRAVITY`：手机竖立时 values ≈ (0, +9.81, 0)（y 为正）；
        //     而 `LevelSensor` 把 `values` **原样**喂给 `LevelMath.solve(gx, gy, gz, ...)`。
        //   · CoreMotion `CMDeviceMotion.gravity`：手机竖立时 ≈ (0, -1, 0)（y 为负）。
        //   ⇒ 两边相差一个负号。所以 `LevelMath` 要的那组数 = `-gravity`。
        //   这就是为什么下面那三个 `-` 不是「随手加的」：
        //   去掉它们，横滚角会整体反号（往左偏会提示往右转）。
        let reactionX = -gravityX
        let reactionY = -gravityY
        let reactionZ = -gravityZ

        // ── ④ 角度：与 `LevelMath.solve` 的 `rotationDegrees == 0` 分支逐行同式 ──
        //
        // LevelMath（commonMain）：
        //     val (sx, sy) = Pair(gx, gy)                       // quarter == 0 分支
        //     val roll  = atan2(sx, sy) * 180 / PI
        //     val pitch = -atan2(gz, gy) * 180 / PI
        //
        // ★ 只抄竖屏分支（等价于 `LevelMath.solve(..., rotationDegrees = 0)`）：
        //   本 App 的 iPhone 侧在 `Info.plist` 里锁了 `UIInterfaceOrientationPortrait`，
        //   屏幕相对设备没有转。
        //
        // ⚠️ **已知限制**：iPad 的 `UISupportedInterfaceOrientations~ipad` 额外允许
        //   `PortraitUpsideDown`。真到倒竖屏（180°）时，这一行会把横滚角**方向算反**
        //   （画面右侧偏低会被报成左侧偏低，文案就会让用户往反方向转 —— 越转越歪）。
        //   将来若支持倒竖屏 / 横屏，必须把 `LevelMath.solve` 的 `when (quarter)`
        //   四个分支一起抄过来，**不是**只翻一个符号（90°/270° 时两个轴会互换）。
        //   Android 侧对应的是 `WindowManager.defaultDisplay.rotation`。
        //
        // ★ `Tuning.rollSign` / `pitchSign` 乘在这里、值为 `+1.0`：公式本身已经带对符号，
        //   这两个常量只是「真机万一整体反了」的一键开关，不是推导的一部分。
        let rollRadians = atan2(reactionX, reactionY)
        let pitchRadians = -atan2(reactionZ, reactionY)

        let roll = Tuning.rollSign * rollRadians * Tuning.radiansToDegrees
        let pitch = Tuning.pitchSign * pitchRadians * Tuning.radiansToDegrees

        guard roll.isFinite, pitch.isFinite else {
            onSample(0, 0, false)
            return
        }
        onSample(roll, pitch, true)
    }
}
