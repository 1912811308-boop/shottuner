// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Score/ParamAdvisor.swift —— 「照拨」参数建议的数据源。
//
// ★ 这个文件补的是本产品**名字的由来**。
//   App 叫「光影调参」，承诺是「拍之前就知道该怎么调」。Android 侧一直有这块面板，
//   iOS 侧却一次都没调过 `ParamRecommender` —— 引擎在 commonMain 里，
//   没有门面，Swift 够不着。所以 iOS 版能打分、能给模式建议，唯独不给「该拨几档」。
//
// ★ 为什么单独一个类，而不是塞进 `ScoreCoordinator`：
//   `ScoreCoordinator` 那条链每帧跑一次（约 10fps），它的职责是「这一帧值多少分」。
//   参数建议是**另一个频率**：它只随场景与亮度缓慢变化，500ms 一次足够，
//   而且它要 `DeviceProfile`（读设备能力，不是轻活）。
//   塞进去会让「评分」这条链多一个它不需要的依赖，也让
//   「为什么改了评分逻辑，参数面板不动了」这种问题变得难查。
//
// ★ 为什么不让 Swift 自己拼 `summary` 字符串：
//   快门 `1/60`（已吸附真实档位）、EV `±0`（而不是 `+0.0`）、负号 U+2212 ——
//   这三条都是口径，`IosParamBridge` 已经在 Kotlin 侧按 Android 的规则生成好了。
//   Swift 再拼一遍就是第二份实现，必然漂移。

import Combine
import Foundation
import Shared

/// 一次建议的本地值类型。
///
/// ★ 为什么不直接持有 `IosParamDto`（Kotlin 类）：
///   `@Published` 里放 Kotlin 对象会让每次界面重算都跨语言取属性，
///   而且 `Equatable` 需要手写。拍成 Swift 值类型之后，`body` 只碰本地内存。
struct ParamAdvice: Equatable {

    /// `false` → 整块不显示。**不是**「显示一堆 0」。
    var valid: Bool

    /// 场景中文名（「人像」/「夜景 / 弱光」）。
    var sceneLabel: String

    /// 建议感光度。
    var iso: Int

    /// 建议曝光时间，纳秒。
    ///
    /// ★ 为什么界面已经有一行 `summary` 了还要这个字段：
    ///   「照拨」开关要把建议值**真写进相机**（`CameraSession.setManualExposure`），
    ///   而那需要一个数，不是 `1/60` 这种给人看的字符串。
    ///   `shutterLabel` 是**吸附后**的显示文本，反向解析回纳秒就是第二份实现
    ///   （还要处理 `2.5s` / `1/3` / 被 `coerceIn(minNs, maxNs)` 钳过的情况），
    ///   所以这里把 `IosParamDto.shutterNs` 原样留下来 ——
    ///   与 Android「照拨」写进 `SENSOR_EXPOSURE_TIME` 的是同一个数。
    var shutterNs: Int64

    /// 快门显示文本，已吸附真实档位（`1/60` / `2.5s`）。
    var shutterLabel: String

    /// 建议色温（K）。
    var kelvin: Int

    /// EV 显示文本（`+1.3` / `−0.7` / `±0`）。
    var evText: String

    /// 是否建议上三脚架。功能页那条「别用手指戳快门」就靠它。
    var needsTripod: Bool

    /// 主句。
    var tip: String

    /// 副句（可能为空）。
    var detail: String

    /// 一行摘要：`ISO 400 · 1/60 · 5200K · EV +1.3`。与 Android 逐字一致。
    var summary: String

    /// 还没有读数。界面必须整块隐藏 —— 印出 `ISO 0` 会被读成「机器坏了」。
    static let none = ParamAdvice(
        valid: false,
        sceneLabel: "",
        iso: 0,
        shutterNs: 0,
        shutterLabel: "",
        kelvin: 0,
        evText: "",
        needsTripod: false,
        tip: "",
        detail: "",
        summary: ""
    )
}

/// 每 500ms 敲一次 `IosParamBridge`，把建议发布给界面。
@MainActor
final class ParamAdvisor: ObservableObject {

    /// 节拍。
    ///
    /// ★ 500ms 而不是跟着 `ScoreCoordinator` 的 10fps：
    ///   建议值只随场景与整体亮度缓慢变化，10fps 重算只是白烧 CPU；
    ///   而 500ms 也够跟上「用户把镜头从窗外转回室内」这类变化。
    /// ★ 与 `MissionModel` 的 400ms 不同源、也不必同源：
    ///   那个频率是任务引擎 EMA 的时间常数决定的（跟 10fps 会让收敛快 4 倍），
    ///   这里没有任何状态机，只有一次纯函数调用，两者没有必须对齐的理由。
    private static let tickInterval: TimeInterval = 0.5

    /// 当前建议。初值是 `.none`，界面据此隐藏整块。
    @Published private(set) var advice: ParamAdvice = .none

    /// 设备档案。启动时给一次，整个生命周期不变（换镜头才会变，而本 App 只用主摄）。
    private var profile: DeviceProfile?

    /// 场景名的取法。由接线方注入，本类不自己去读 `ScoreCoordinator`。
    ///
    /// ★ 与 `MissionModel.rollProvider` 同一个模式：姿态/场景的**唯一真相**
    ///   在 `ScoreCoordinator` 的 `@Published` 里，本类再存一份就成了第二个来源。
    private var sceneNameProvider: (@MainActor () -> String)?

    /// 「相机此刻真被锁在」的曝光值。由接线方注入（接 `ExposureAssist`）。
    ///
    /// ★ 为什么要这个：`IosParamBridge.recommend` 现在接受 `currentIso` /
    ///   `currentShutterNs`，有真实值才能走 `ParamRecommender` 的**闭环**路径
    ///   （锚点 = 当前参数、阻尼 0.7，每拍只走七成偏差）。没有它就只能传 0/0，
    ///   引擎退回冷启动（锚点 = 场景预设、阻尼 1.0，每拍给绝对值）——
    ///   而「照拨」开着时绝对值是**正反馈**：提亮 → 下一拍说过亮该压 → 压暗 →
    ///   下下拍又说该提，每 500ms 反向修正一次，肉眼看到的就是画面来回抖。
    ///
    /// ★ 为什么是 `var` 而不是并进 `start(...)` 的形参：
    ///   它是**可变**依赖（关掉照拨之后就必须开始返回 0/0），而 `start` 只在起链路时
    ///   调一次（`ContentView.startParamAdvisor` 是个幂等闸门）。做成 `start` 的形参，
    ///   就会出现「第一次启动时照拨是关的 → provider 记下的答案永远是 0/0」。
    ///   闭包每次 tick 现问一次，答案就永远跟着 `ExposureAssist` 的当前状态走。
    ///
    /// ★ 返回 `(0, 0)` 而不是 `nil`：`nil` 表达的是「没人接线」，而 0/0 表达的是
    ///   「现在没有已应用的曝光」—— 后者是**正常状态**（绝大多数用户根本不开照拨）。
    ///   两者对 Kotlin 门面是同一个结果（任一 ≤ 0 ⇒ `current = null` ⇒ 老路径），
    ///   但在这里分开写，是为了让 `tick()` 里不必再判一次可选链。
    var appliedProvider: (@MainActor () -> (iso: Int32, shutterNs: Int64))?

    private var timer: Timer?

    /// 是否正在跑（幂等闸门，见 `start`）。
    var isRunning: Bool { timer != nil }

    /// 起表。重复调用是安全的（已经跑着就直接返回）。
    ///
    /// ★ 为什么要幂等：`ContentView` 的 `startIfAllowed()` 会被 `onAppear` 与
    ///   `scenePhase == .active` 两条路径调用，而后者在「授权后回前台」时必然触发。
    ///   不幂等就会有两个定时器同时跑，读数翻倍、CPU 也翻倍。
    func start(profile: DeviceProfile, sceneNameProvider: @escaping @MainActor () -> String) {
        self.profile = profile
        self.sceneNameProvider = sceneNameProvider
        guard timer == nil else {
            // 已经跑着：只更新注入的依赖，不重开表。
            tick()
            return
        }

        let timer = Timer(timeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            // Timer 的回调不在主 actor 上下文里，必须显式跳回来 ——
            // 本类是 `@MainActor`，`tick()` 读 `@Published` 只能在主线程。
            Task { @MainActor [weak self] in
                self?.tick()
            }
        }
        // ★ 用 `.common` 而不是 `scheduledTimer`：后者的默认 mode 是 `.default`，
        //   界面一滚动（`ScrollView` 拖拽）定时器就停摆，读数会「卡住一下」。
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer

        // 立刻先算一次，别让用户盯着空块等半秒。
        tick()
    }

    /// 停表。
    func stop() {
        timer?.invalidate()
        timer = nil
        // ★ 不清 `advice`：清掉会让界面在切后台那一刻「闪一下空块」，
        //   而回前台第一 tick 会写新值。保留旧值最多显示半秒过期读数，
        //   比闪一下更不刺眼。
    }

    /// 一次取样。
    private func tick() {
        guard let profile, let sceneNameProvider else { return }
        // ★ 每拍现问一次「相机现在被锁在哪」，不要缓存：
        //   开关可能刚被关掉（那时必须立刻退回 0/0 走冷启动），也可能刚下发了一组新值
        //   （那时必须用新值当锚点）。缓存一份就成了第二个真相源，与 `ExposureAssist`
        //   的 `applied` 迟早对不上 —— 而那个不一致的表现是「建议值卡住不动」。
        // 没接 provider / provider 说没有已应用值 ⇒ (0, 0) ⇒ Kotlin 侧 `current = null`
        // ⇒ 与引入这两个参数之前**逐字一致**的冷启动行为。
        let applied: (iso: Int32, shutterNs: Int64) =
            appliedProvider?() ?? (iso: 0, shutterNs: 0)
        let dto = IosParamBridge.shared.recommend(
            profile: profile,
            sceneName: sceneNameProvider(),
            // Kotlin `Int` → Swift `Int32`，`Long` → `Int64`：闭包已经按这两个宽度
            // 交付，这里直接传，不做二次转换（转两次最容易把 `Int`/`Int64` 混掉）。
            currentIso: applied.iso,
            currentShutterNs: applied.shutterNs
        )
        let mapped = ParamAdvice(
            valid: dto.valid,
            sceneLabel: dto.sceneLabel,
            // Kotlin `Int` → Swift `Int32`，显式转。
            iso: Int(dto.iso),
            // Kotlin `Long` → Swift `Int64`，同类型，直接传。
            // ★ 这是「照拨」真拨时写进相机快门的那**一个数**（纳秒），
            //   不要再从 `shutterLabel` 反解 —— 理由见字段注释。
            shutterNs: dto.shutterNs,
            shutterLabel: dto.shutterLabel,
            kelvin: Int(dto.kelvin),
            evText: dto.evText,
            needsTripod: dto.needsTripod,
            tip: dto.tip,
            detail: dto.detail,
            summary: dto.summary
        )
        // ★ 值没变就不发：`@Published` 每次赋值都会让 SwiftUI 重算 body，
        //   而这块面板半秒一次、内容往往几十秒不变。无脑赋值等于每秒两次无意义重绘。
        if mapped != advice {
            advice = mapped
        }
    }
}
