// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/ExposureAssist.swift —— 把「照拨」从**只显示**变成**真拨**。
//
// ★ 这个文件补的是一个「函数写好了、全仓 0 调用点」的洞。
//   `CameraSession.setManualExposure` / `resumeAutoExposure` 从 T12 就写好了，
//   但 iOS 侧谁也没调过 —— 于是「照拨」那一行只是把 ISO / 快门**显示给用户看**，
//   相机一直在自动档。Android 侧 `applyManual` 有 4 个调用点，是真拨的。
//   两端界面上写着同一句话，行为却不同，这是最难被用户描述、也最难被回归发现的一类差距。
//
// ★ 为什么单独一个类，而不把这几行塞进 `ContentView`：
//   ① 它有**状态机**（开关 / 已下发值 / 在途 / 待下发 / 待复位），
//      而 `ContentView` 是本仓最大的文件，状态越堆越难核；
//   ② 它必须能回答「现在相机是不是被我锁着」这个问题，答案要跟着
//      `CameraSession` 的生命周期走（换镜头、离页、进后台都要复位），
//      放在一个可测的类里比散在 4 个闭包里清楚。
//
// ★ 线程口径：本类是 `@MainActor`（与 `CaptureFlow` / `ParamAdvisor` 同一套），
//   所有状态只在主线程读写；`CameraSession` 的回调**不在主线程**，
//   所以每个回调都显式 `Task { @MainActor in ... }` 跳回来 ——
//   与 `CaptureFlow` 里那条「闭包类型上没有 `@MainActor` ⇒ 显式跳回」的口径一致。
//
// ★ 与 Android 的差距（**已知**，改不了，属于 shared 侧的事）：
//   `IosParamBridge.recommend` 里 `current = null` 写死，即 iOS 走的是
//   参数引擎的「冷启动」路径（锚点取场景预设、阻尼 1.0，每次给**绝对值**），
//   而不是 Android 那种「把上次写入的真实参数递回去」的闭环路径。
//   后果：建议值是绝对目标值，「照拨」下发之后画面会向目标收敛，收敛稳定后
//   建议值不再变 ⇒ 不抖；但若模型与实际传感器响应差得多，理论上可能出现
//   半秒一次的来回找（每 tick 都是全量修正）。本期不动 shared（边界约束），
//   这里只做两件事抑制它：**值没变就不下发** + **在途只保留最新一条**。

import Combine
import Foundation

/// 「照拨」开关的执行器：把 `ParamAdvisor` 的建议值真写进相机，并在该复位时复位。
///
/// ## 状态机（四个状态，别再加第五个）
///
/// | 事件 | 动作 |
/// |---|---|
/// | 打开开关 | 立刻下发当前建议值（`applied` 清零 ⇒ 这次一定下发） |
/// | 建议值刷新 | 值**变了**才下发；没变一个 API 都不调 |
/// | 关闭开关 | `resumeAutoExposure` 交还自动 |
/// | 离开取景页 / 进后台 / 换镜头 | 同「关闭开关」，且开关本身也打回关 |
///
/// ★ 为什么「关闭」和「离页」必须是同一个动作：相机锁的是**设备级**状态，
///   它不跟着视图生命周期走。页面上看不见开关了，相机却还停在上一次的
///   ISO / 快门上 —— 下次进页面取景一片死黑，而界面上没有任何东西能解释。
@MainActor
final class ExposureAssist: ObservableObject {

    // MARK: - 对外状态

    /// 开关是否打开。UI 画的就是它，**不要在别处另存一份**。
    @Published private(set) var isOn: Bool = false

    /// 最近一次失败的说明（`nil` = 没有错误）。
    ///
    /// ★ 失败必须是**可见**的：相机没锁上、开关却亮着，等于「静默失效」。
    ///   所以失败时本类会把开关**打回关**，并在这里留一句话给界面弹。
    @Published private(set) var lastError: String?

    // MARK: - 内部状态

    /// 一次已经下发到相机的曝光。**去重键**。
    ///
    /// ★ 为什么要去重：`ParamAdvisor` 每 500ms 给一次建议，而 ISO / 快门往往
    ///   几十秒都不变（同一场景下亮度稳定）。每 500ms 走一遍
    ///   `lockForConfiguration` + `setExposureModeCustom` 的异步回调，
    ///   会让取景持续轻微卡顿，而且这些调用排在 `sessionQueue` 上，
    ///   **拍照也要排这条队列**。所以：值没变就一个 API 都不调。
    private struct Applied: Equatable {
        let iso: Int
        let shutterNs: Int64
    }

    /// 相机。由接线方在起链路时注入（`attach`），弱引用 —— 相机由视图持有。
    private weak var session: CameraSession?

    /// 已经下发成功（或正在下发）的那组值。
    private var applied: Applied?

    /// 是否有一条 `setManualExposure` 在飞。
    ///
    /// ★ 为什么必须知道：`setManualExposure` 与 `resumeAutoExposure` 争同一把
    ///   `AVCaptureDevice.lockForConfiguration`。在飞的时候硬插一次
    ///   `resumeAutoExposure` 会让两个会话队列块互相等锁（最坏是整条取景队列卡住）。
    ///   所以「在飞」时只记意图，等回调回来再执行。
    private var inFlight = false

    /// 在飞期间到来的**最新**一条建议。
    ///
    /// ★ 为什么只留最新一条（而不是排队）：曝光是「当前状态」，不是命令流。
    ///   排三条旧值挨个下发，用户看到的是画面抖三下再到位；只下发最后一条，
    ///   画面直接到位。
    private var pendingAdvice: ParamAdvice?

    /// 在飞期间收到的「复位」意图（关开关 / 离页 / 换镜头）。
    private var pendingReset = false

    /// 复位完成后要执行的回调（换镜头时用：先让相机回自动，再换 input）。
    private var resetCompletion: (@MainActor () -> Void)?

    // MARK: - 接线

    /// 注入相机。起链路时调一次即可，重复调用安全。
    /// - Parameter session: 采集会话。
    func attach(_ session: CameraSession) {
        self.session = session
    }

    /// 清掉错误（告警关闭时调）。
    func clearError() {
        lastError = nil
    }

    // MARK: - 开关

    /// 打开「照拨」：立刻把当前建议值写进相机。
    ///
    /// ★ 没有可用建议时**不开**（返回 false）。理由与界面那块「`valid == false`
    ///   就整块不画」相同：此刻的 ISO / 快门是「还没有」，不是「0」——
    ///   拿 0 去锁相机会得到一张全黑的取景，而用户以为自己开了个有用的功能。
    ///
    /// - Parameter advice: 当前建议值。
    /// - Returns: 是否真的打开了。
    @discardableResult
    func turnOn(with advice: ParamAdvice) -> Bool {
        guard session != nil else {
            // ★ 不静默：开关点不亮必须有一句解释，否则用户只会觉得按钮坏了。
            lastError = "相机还没准备好，稍等一下再试。"
            return false
        }
        guard Self.isUsable(advice) else {
            lastError = "还没读到画面，暂时没有可照拨的参数。"
            return false
        }
        guard !isOn else { return true }

        isOn = true
        lastError = nil
        // ★ 清掉去重键：开关刚打开，就算这组值和上次（关之前）一样也要重发一次 ——
        //   中间可能已经被 `resumeAutoExposure` 交还给自动了。
        applied = nil
        apply(advice)
        return true
    }

    /// 关闭「照拨」：交还自动曝光。
    ///
    /// ★ 这个方法是**幂等**的，而且在「本来就没在照拨」时什么都不做：
    ///   所以离页 / 进后台 / 换镜头都可以无脑调一次，不需要先问 `isOn`。
    ///
    /// - Parameter completion: 复位完成（主线程）后回调。换镜头要串在这个后面 ——
    ///   先让相机回自动，再换 input，否则新传感器会带着旧锁起流。
    func turnOff(completion: (@MainActor () -> Void)? = nil) {
        let hadApplied = isOn || applied != nil || inFlight
        isOn = false
        applied = nil
        pendingAdvice = nil

        guard hadApplied else {
            // 没在照拨 ⇒ 相机本来就在自动档，不需要碰它。
            completion?()
            return
        }

        guard !inFlight else {
            // 有一条 set 在飞：记下意图，等 `finished` 里再复位（理由见 `inFlight`）。
            pendingReset = true
            // ★ 与已排队的那个回调**串起来**，而不是覆盖。
            //   覆盖是会丢回调的：换镜头时 `flipCamera` 传了 completion（它负责关掉
            //   「切换中」的 loading 态），如果此时恰好进后台、`scenePhase` 那次
            //   `turnOff()` 把它盖成 nil，翻转按钮就会永远停在禁用态 ——
            //   没有任何东西能把它解开。
            let previous = resetCompletion
            if previous != nil || completion != nil {
                resetCompletion = {
                    previous?()
                    completion?()
                }
            }
            return
        }

        resumeAuto(completion: completion)
    }

    /// 建议值刷新时调（由接线方在 `.onChange(of: paramAdvisor.advice)` 里挂）。
    /// - Parameter advice: 最新建议值。
    func sync(with advice: ParamAdvice) {
        guard isOn else { return }
        apply(advice)
    }

    // MARK: - 下发

    /// 是否是一组「能拿去锁相机」的建议值。
    /// - Parameter advice: 建议值。
    /// - Returns: 可用为 true。
    private static func isUsable(_ advice: ParamAdvice) -> Bool {
        advice.valid && advice.iso > 0 && advice.shutterNs > 0
    }

    /// 请求下发一条建议（可能只是记下来，等上一条回来）。
    /// - Parameter advice: 建议值。
    private func apply(_ advice: ParamAdvice) {
        guard Self.isUsable(advice), session != nil else { return }

        let target = Applied(iso: advice.iso, shutterNs: advice.shutterNs)
        // ★ 值没变：一个 API 都不调。这是「别每 500ms 抖一次相机」那句话的实现。
        guard target != applied else { return }

        pendingAdvice = advice
        guard !inFlight else { return }
        dispatchPending()
    }

    /// 把 `pendingAdvice` 里那条真正发给相机。
    private func dispatchPending() {
        guard let session, let advice = pendingAdvice, Self.isUsable(advice) else { return }
        pendingAdvice = nil

        let target = Applied(iso: advice.iso, shutterNs: advice.shutterNs)
        applied = target
        inFlight = true

        session.setManualExposure(
            iso: Float(advice.iso),
            shutterNs: advice.shutterNs
        ) { [weak self] error in
            // 回调不在主线程（`CameraSession` 保证回主线程，但闭包类型上没标
            // `@MainActor`）⇒ 显式跳回来，与 `CaptureFlow` 同一套写法。
            Task { @MainActor [weak self] in
                self?.finished(error)
            }
        }
    }

    /// 一次下发结束（成功或失败）。
    /// - Parameter error: 失败原因；`nil` 表示成功。
    private func finished(_ error: Error?) {
        inFlight = false

        if let error {
            // ★ 失败 → 把「已应用」清掉 + **开关打回关** + 留一句给界面。
            //   最坏的情况是「开关亮着、相机其实在自动」：用户照着开关的预期
            //   去调构图，而画面的曝光根本不是他锁的那个值。
            applied = nil
            pendingAdvice = nil
            isOn = false
            lastError = Self.message(for: error)
        }

        if pendingReset {
            pendingReset = false
            let completion = resetCompletion
            resetCompletion = nil
            // 失败时 `resumeAutoExposure` 依然是安全的（设备在自动档时它是空操作）。
            resumeAuto(completion: completion)
            return
        }

        if pendingAdvice != nil {
            dispatchPending()
        }
    }

    /// 交还自动曝光。
    /// - Parameter completion: 完成（主线程）后回调。
    private func resumeAuto(completion: (@MainActor () -> Void)?) {
        guard let session else {
            completion?()
            return
        }
        session.resumeAutoExposure { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                // 复位动作本身会把 inFlight 的语义清干净：这一路没有在飞的下发了。
                self.inFlight = false
                if let error {
                    self.lastError = Self.message(for: error)
                }
                completion?()
            }
        }
    }

    /// 把错误翻译成一句人话。
    /// - Parameter error: 相机抛回来的错误。
    /// - Returns: 给用户看的说明。
    private static func message(for error: Error) -> String {
        // `CameraSession.Failure` 自带 `userMessage`（唯一出处，见那个 extension）。
        if let failure = error as? CameraSession.Failure {
            return failure.userMessage
        }
        return "手动曝光没设上，已经切回自动。"
    }
}
