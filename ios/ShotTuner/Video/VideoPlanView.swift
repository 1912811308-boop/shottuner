// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Video/VideoPlanView.swift —— 录像方案页（iOS 侧）。
//
// ★ 为什么这个页面能「今晚」就上线：
//   「拍什么意图 → 用什么分辨率 / 帧率 / 快门 / 码率」的整套规则早已在 commonMain
//   （`VideoRecommender`），Android 一直在用，iOS 完全够不着 —— iOS 版此前只能取景拍照，
//   录像页是一张白纸。而录像恰恰是最容易踩 180 度快门坑的地方
//   （快门必须跟着帧率走：30fps 配 1/60、60fps 配 1/120）。
//   ⇒ 本文件只做渲染，**一行方案逻辑都不写**：不钳帧率、不挑档位、不拼文案。
//
// ★ 口径：与 Android `MainActivity` 的 `renderVideo()` / `copyVideoPlan()` **逐字一致**。
//   Android 那两段（`app/src/main/java/com/shottuner/app/MainActivity.kt`）是这份 UI 的
//   唯一参照，文案一个字都不自己发明：
//     · 意图说明行 = `"${oneLine} · ${pathHint}"`（中文间隔点，不是 `|`，不是 `-`）
//     · 四个数值块 = `tier` / `"${fps}"` / `shutterText` / `"${bitrateMbps}M"`
//     · 摘要行     = `"防抖 ${stabilization} · LOG ${开/关} · ${isoHint}"`
//     · 要点       = 每条前缀 `"· "`，逐行（Android 是 `joinToString("\n") { "· $it" }`）
//     · 声音行     = `"声音：${audioTip}"`
//     · 复制内容   = `clipboardText + "\n" + tips.joinToString("\n") { "· $it" }`
//   两端是同一个产品的两个外壳，文案一旦各自润色，用户拿两台手机对比就会发现不一样，
//   而那种差异永远不会有人去对账。
//
// ★ `downgraded` 表示「机型能力不足，这份方案已被降档」，**Android 侧的 UI 里没有单独
//   显示它**（已核对 `renderVideo()` 全文），所以 iOS 侧**也不加任何降级文案** ——
//   保持一致。`tier` 永远是这台机器真能拨出来的那一档，用户看到的不是错方案。
//   DTO 仍然带着这个字段（`IosVideoPlanDto.downgraded`），将来两端要一起加提示时再用。
//
// ★ `shutterText` 可能是 `"自动"`（`supportsManualVideo == false` 的机型没有手动录像
//   快门），**不要**按 `"/"` split、**不要**格式化成 `1/60s`、**不要**补单位 ——
//   它不是「分子/分母」字符串，它是一个已经定稿的展示文本，原样印出来。
//
// ★ 为什么 `NavigationView` 而不是 `NavigationStack`：
//   照本仓既有先例（`ios/ShotTuner/TechniqueDexView.swift:55`）。`NavigationStack`
//   是 iOS 16 API，本工程部署目标是 16.0（能编），但同一批页面都用 `NavigationView`，
//   混用会让「关不掉的弹层 / 多一层导航栏」这类问题只在某些入口出现。
//   另外显式加 `StackNavigationViewStyle()`：iPad 上裸 `NavigationView` 会变成
//   「左空右内容」的分栏，一个工具页不该长成那样。
//
// ★ 为什么方案要存成 `@State private var plan: VideoPlanRow?`（本地值类型）：
//   `body` 在滚动、动画、父视图刷新时会被反复重算，如果在 `body` 里直接调
//   `IosVideoBridge.shared.plan(...)`，每次重算都是一次跨语言调用 + 一次
//   `VideoRecommender` 全量计算（含 4~6 条 tips 字符串构造）。更糟的是它**不可预测**：
//   用户只是滚动了一下列表，方案文本就被重新生成了一遍。
//   ⇒ 只在「进页面」与「切换意图」两个时机算一次，得到的副本存进 `@State`。
//
// ★ 为什么刷新不用 Combine 订阅：
//   `coordinator`（`ScoreCoordinator`）里那些 `@Published`（score / guide / histogram）
//   是**每帧**都在变的实时读数，而录像方案只依赖 `sceneName`（用户手选、频率极低）
//   与设备档案（`profile` 由调用点传入，整页生命周期内不变）。
//   订阅整份 coordinator 会让这一页在取景时被 5~10fps 重算，纯属白烧 CPU。
//   ⇒ 在 `onAppear` 里读一次 `coordinator.sceneName` 即可；场景是进页面之前选的，
//     页面期间不会变（本页没有场景选择器）。

import SwiftUI
import UIKit
import Shared

// MARK: - 展示行

/// 一份录像方案的**本地值类型**副本。
///
/// ★ 存在的唯一理由是「别在 body 里跨语言」：`IosVideoPlanDto` 是 Kotlin 对象，
///   每次读字段都是一次 ObjC 消息派发；拷成 Swift struct 之后 body 只碰 Swift 内存。
///
/// ★ 字段一个都不改名、不换算 —— 这一层只做搬运。想改口径请改 commonMain 的
///   `VideoRecommender`，不要在这里算。
///
/// | 字段 | 本页落点 | 与 Android 对应 |
/// |---|---|---|
/// | `intentLabel` | **不显示** | 意图名只出现在胶囊上，`renderVideo()` 也不显示它 |
/// | `tier` | 分辨率块 | `tvVRes` |
/// | `fps` | 帧率块 | `tvVFps` |
/// | `resLabel` | **不显示** | 现成的 `"4K 30fps"`，Android 该页没用它（用在分享文案里） |
/// | `shutterText` | 快门块 | `tvVShutter`（可能是「自动」，原样印） |
/// | `isoHint` | 摘要行 | `tvVStab` 的一部分 |
/// | `bitrateMbps` | 码率块 | `tvVBitrate` |
/// | `stabilization` | 摘要行 | `tvVStab` |
/// | `useLog` | 摘要行（开/关） | `tvVStab` |
/// | `logNote` | **不显示** | Android 没显示（`VideoPlan` 自己的注释里也说明了） |
/// | `focus` | **不显示** | Android 录像页没显示对焦（功能页里有） |
/// | `tips` | 要点列表 | `tvVTips` |
/// | `audioTip` | 声音行 | `tvVAudio` |
/// | `pathHint` | 意图说明行 | `tvIntentDesc` |
/// | `downgraded` | **不显示**（见文件头） | Android 侧不显示，保持一致 |
/// | `clipboardText` | 复制按钮 | `copyVideoPlan()` |
private struct VideoPlanRow {

    let intentLabel: String
    let tier: String
    let fps: Int
    let resLabel: String
    let shutterText: String
    let isoHint: String
    let bitrateMbps: Int
    let stabilization: String
    let useLog: Bool
    let logNote: String
    let focus: String
    let tips: [String]
    let audioTip: String
    let pathHint: String
    let downgraded: Bool
    let clipboardText: String

    /// 从 Kotlin DTO 抄一份。**只搬运，不换算。**
    ///
    /// ★ `fps` / `bitrateMbps` 是 Kotlin `Int`，跨到 Swift 是 `Int32`，这里显式转成
    ///   `Int`：`Int32` 与 `Int` 在 Swift 里**没有隐式转换**（不是 `Boolean`/`Bool`
    ///   那种大小写问题，是两个不同宽度、不能互相代入的类型），
    ///   不转就会在 `String(fps)` / 算术处编译失败。
    ///
    /// - Parameter dto: 门面吐出的扁平方案。
    init(from dto: IosVideoPlanDto) {
        intentLabel = dto.intentLabel
        tier = dto.tier
        fps = Int(dto.fps)
        resLabel = dto.resLabel
        shutterText = dto.shutterText
        isoHint = dto.isoHint
        bitrateMbps = Int(dto.bitrateMbps)
        stabilization = dto.stabilization
        useLog = dto.useLog
        logNote = dto.logNote
        focus = dto.focus
        // ★ `tips` 是全仓唯一允许过边界的 `List<String>`：元素是 `String`（ObjC 侧就是
        //   `NSString`），桥接成 `NSArray<NSString>` 之后 Swift 看到的直接是 `[String]`，
        //   不需要任何逐元素转换代码。对象数组仍然禁止（见 `IosVideoBridge` 文件头）。
        tips = dto.tips
        audioTip = dto.audioTip
        pathHint = dto.pathHint
        downgraded = dto.downgraded
        clipboardText = dto.clipboardText
    }
}

// MARK: - 页面

/// 录像方案页：八个拍摄意图 → 一整套可直接照拨的录像参数。
///
/// ★ 为什么整个 struct 标 `@MainActor`（理由与 `ContentView` 顶部那段完全一致）：
///   `ScoreCoordinator` 是 `@MainActor` 类，而本页的 `refresh()` 要读它的
///   `sceneName`。`refresh()` 是**普通方法**，不是 `body` —— 不标 `@MainActor`
///   就是一个非隔离上下文，读主 actor 的隔离属性会直接编译失败
///   （"Main actor-isolated property 'sceneName' can not be referenced from a
///   nonisolated context"）。只靠 `body` 的隐式隔离救不到它。
///   标在 struct 上还能顺带保证所有 UI 回调都落在主线程。
@MainActor
struct VideoPlanView: View {

    /// 评分协调器。**只用来读 `sceneName`**（本页不存在每帧刷新的东西，
    /// 所以不订阅它的 `@Published`）。
    @ObservedObject var coordinator: ScoreCoordinator

    /// 设备档案（common 的 `DeviceProfile`，由调用点用
    /// `CameraSession.currentDeviceProfile()` 现场探测后传入）。
    ///
    /// ★ 用 `let` 而不是 `@State`：它整页生命周期内不变，而 `plan(...)` 每次调用都会
    ///   用到它 —— 存成状态只会让「哪一份才是当前的」变得可疑。
    let profile: DeviceProfile

    /// 关闭回调（由接线方决定是 dismiss 还是切页）。
    var onClose: () -> Void

    // MARK: 本地状态

    /// 意图胶囊的文案（`intentLabelAt(index:)`），进页面时一次性读完。
    @State private var intentLabels: [String] = []

    /// 意图的一句话说明（`intentOneLineAt(index:)`），与上一项同源同序。
    @State private var intentOneLines: [String] = []

    /// 当前选中的意图下标（Kotlin `Int` → Swift `Int32`）。
    ///
    /// ★ 为什么存下标而不是枚举：`VideoIntent` 是**带构造参数的枚举**，跨语言会变成
    ///   一整套 `SharedVideoIntent` 实例，本仓铁律是「枚举不过边界」。
    ///   下标顺序由 Kotlin 的枚举声明顺序决定，两端永远不会各排一套。
    @State private var intentIndex: Int32 = 0

    /// 当前方案（本地值类型副本，见 `VideoPlanRow`）。
    ///
    /// `nil` 只在门面返回 null 时出现 —— 那意味着 `intentIndex` 越界
    /// （理论上不会：胶囊就是按下标铺的），此时显示占位说明，不崩。
    @State private var plan: VideoPlanRow?

    /// 「已复制录像方案」这句提示是否显示。
    ///
    /// ★ 不用定时器自动消失：切换意图时清掉就够了（见 `refresh()`）。
    ///   定时器会引入「页面已经走了、回调还在改状态」这一类只在真机上偶发的问题，
    ///   而这句提示的全部价值只是「让你知道剪贴板里有了」。
    @State private var showsCopied = false

    // MARK: 布局常量

    private enum Layout {
        /// 数值块之间的间距（Android 是 `layout_weight` 等分，没有固定间距）。
        static let metricSpacing: CGFloat = 8
        /// 卡片圆角，与复盘卡 / 任务卡一致。
        static let cornerRadius: CGFloat = 12
        /// 复制按钮的最小高度：苹果的人机界面指南要求可点区域 ≥ 44pt。
        static let buttonMinHeight: CGFloat = 44
    }

    var body: some View {
        // 用 NavigationView（iOS 13+）而不是 NavigationStack（iOS 16+），见文件头。
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    intentChips
                    intentDescription
                    metricsRow
                    summaryLine
                    tipsBlock
                    audioLine
                    copySection
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // ★ `Color.black` 而不是自造一个色板成员：`Palette` 里只有前景色，
            //   背景一直由根视图的黑色提供（与 `PhotoReviewSheet` 同口径）。
            .background(Color.black)
            .navigationTitle("录像方案")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        onClose()
                    }
                    .foregroundStyle(Palette.accent)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        // 强制深色：`Palette` 的文字色是给深底设计的浅色，浅色外观下会变成白字白底。
        .preferredColorScheme(.dark)
        .onAppear {
            loadIntentsIfNeeded()
            refresh()
        }
    }

    // MARK: - 意图胶囊

    /// 八个意图的横向胶囊条（Android 是 `HorizontalScrollView` + `llIntents`）。
    private var intentChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                // ★ 用 `indices` 而不是 `0..<intentLabels.count`：后者在数量变化时
                //   会让 ForEach 的身份判断变得微妙（本仓被打回过一次「列表少一项」），
                //   `Array(indices)` 是一个稳定的整数数组。
                ForEach(Array(intentLabels.indices), id: \.self) { index in
                    chipButton(index: index)
                }
            }
        }
    }

    /// 单颗胶囊。
    ///
    /// ★ 选中态用 `Palette.accent`（Android `st_accent`），未选中用
    ///   `Palette.overlayTextSecondary`（Android `st_text_dim`）——
    ///   值都在 `Palette` 里一一对应，不自己调透明度。
    ///
    /// - Parameter index: 意图下标（Swift `Int`，来自胶囊数组）。
    private func chipButton(index: Int) -> some View {
        let selected = Int32(index) == intentIndex
        return Button {
            selectIntent(Int32(index))
        } label: {
            Text(intentLabels[index])
                .font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .foregroundStyle(selected ? Palette.accent : Palette.overlayTextSecondary)
                .overlay(
                    Capsule()
                        .strokeBorder(
                            selected ? Palette.accent : Palette.hairline,
                            lineWidth: 1
                        )
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - 意图说明

    /// `"\(oneLine) · \(pathHint)"`，与 Android `tvIntentDesc` 同文案。
    ///
    /// 方案算不出来时落回 Android 布局 XML 里那句占位文案
    /// （`选一个拍摄意图，给出完整录像方案`）—— 不显示「出错了」，
    /// 因为用户什么都没做错。
    private var intentDescription: some View {
        Group {
            if let plan = plan {
                Text("\(oneLineForCurrentIntent()) · \(plan.pathHint)")
            } else {
                Text("选一个拍摄意图，给出完整录像方案")
            }
        }
        .font(.system(size: 11))
        .foregroundStyle(Palette.overlayTextSecondary)
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - 四个数值块

    /// 分辨率 / 帧率 / 快门 / 码率。
    ///
    /// ★ 口径逐字照 `renderVideo()`：
    ///   `tvVRes = plan.tier`、`tvVFps = "${plan.fps}"`、
    ///   `tvVShutter = plan.shutterText`、`tvVBitrate = "${plan.bitrateMbps}M"`。
    private var metricsRow: some View {
        HStack(alignment: .top, spacing: Layout.metricSpacing) {
            metricBlock(title: "分辨率", value: plan?.tier ?? "--", large: true)
            metricBlock(title: "帧率", value: plan.map { String($0.fps) } ?? "--", large: true)
            // ★ 原样印：`shutterText` 可能是「自动」，不做 `"/"` 解析（见文件头）。
            metricBlock(title: "快门", value: plan?.shutterText ?? "--", large: true)
            metricBlock(
                title: "码率",
                value: plan.map { "\($0.bitrateMbps)M" } ?? "--",
                // Android 的码率字号比另外三个小（13sp vs 21sp）、颜色是次级的：
                // 它是导出/压缩时的下限参考，不是用户要照着拨的参数。
                large: false
            )
        }
    }

    /// 单个数值块（小标题 + 大数字）。
    ///
    /// - Parameters:
    ///   - title: 小标题（分辨率 / 帧率 / 快门 / 码率）。
    ///   - value: 已格式化好的显示值。
    ///   - large: 是否用大字号（码率用小字号，见调用点）。
    private func metricBlock(title: String, value: String, large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(Palette.overlayTextSecondary)
            Text(value)
                .font(.system(size: large ? 21 : 13, weight: .bold, design: .monospaced))
                .foregroundStyle(large ? Palette.overlayText : Palette.overlayTextSecondary)
                // ★ 等宽字体 + 单行：`tier`（`1080P`）与 `shutterText`（`1/2000`）
                //   宽度差很大，没有单行约束时相邻块会被推着左右跳。
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 摘要行

    /// `"防抖 X · LOG 开/关 · ISO 提示"`，与 Android `tvVStab` 同文案。
    private var summaryLine: some View {
        Text(summaryText)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(Palette.accent)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// 摘要行文本。方案还没算出来时给空串（整行宽度归零，不占位）。
    private var summaryText: String {
        guard let plan = plan else { return "" }
        // Android：`"防抖 " + stabilization + " · LOG " + (if useLog "开" else "关")
        //          + " · " + isoHint`
        let logLabel = plan.useLog ? "开" : "关"
        return "防抖 \(plan.stabilization) · LOG \(logLabel) · \(plan.isoHint)"
    }

    // MARK: - 要点 / 声音

    /// 要点口诀，每条前缀 `"· "`。
    ///
    /// ★ 用一个 `Text` 装整段（`joined(separator: "\n")`）而不是 `ForEach` 逐行，
    ///   理由：Android 那边就是**一个** TextView（`tvVTips`）装 `joinToString("\n")`，
    ///   行间距由 `lineSpacingExtra="3dp"` 控制。逐行 `ForEach` 会让行距由布局决定，
    ///   与 Android 的观感分叉；而且 tips 里有重复文案时 `id: \.self` 还会撞身份。
    private var tipsBlock: some View {
        Text(tipsText)
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// `tips.map { "· " + $0 }.joined(separator: "\n")`，与 Android 逐字一致。
    private var tipsText: String {
        guard let plan = plan else { return "" }
        return plan.tips.map { "· " + $0 }.joined(separator: "\n")
    }

    /// `"声音：\(audioTip)"`，与 Android `tvVAudio` 同文案。
    private var audioLine: some View {
        Text(plan.map { "声音：\($0.audioTip)" } ?? "")
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - 复制

    /// 复制按钮 + 复制成功的提示。
    private var copySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                copyPlan()
            } label: {
                Text("复制录像方案")
                    .font(.system(size: 12, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: Layout.buttonMinHeight)
                    // Android 是 `textColor=st_bg`（黑）+ `backgroundTint=st_accent`。
                    .foregroundStyle(Color.black)
                    .background(Palette.accent)
                    .clipShape(
                        RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous)
                    )
            }
            .buttonStyle(.plain)

            if showsCopied {
                Text("已复制录像方案")
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.accent)
            }
        }
    }

    /// 把方案写进剪贴板。
    ///
    /// ★ 内容与 Android `copyVideoPlan()` **逐字节一致**：
    ///   `clipboardText + "\n" + tips.joinToString("\n") { "· $it" }`。
    ///   `clipboardText()` 自己可能已带换行（`stabilization` 的括号说明、
    ///   「无手动快门」那条 tip 都会被拼进去），**不要再 trim、不要再加工** ——
    ///   加一层清理就与 Android 分叉了，而复制出来的文本是用户会粘到备忘录/微信里的东西。
    ///
    /// ★ 不做「一条 tips 都没有」的特殊处理：那种情况下 Android 的结果是
    ///   `clipboardText` 后面多一个换行，两边保持一致比「更漂亮」重要。
    private func copyPlan() {
        guard let plan = plan else { return }
        let tipsLines = plan.tips.map { "· " + $0 }.joined(separator: "\n")
        UIPasteboard.general.string = plan.clipboardText + "\n" + tipsLines
        showsCopied = true
    }

    // MARK: - 数据

    /// 进页面时把意图清单一次性读完（标量下标访问，越界返回空串，不会崩）。
    ///
    /// ★ 不在 `body` 里做：8 颗胶囊 × 2 次跨语言调用，每次 body 重算就是 16 次
    ///   ObjC 派发；而清单在整页生命周期内是不变的。
    private func loadIntentsIfNeeded() {
        guard intentLabels.isEmpty else { return }
        let total = Int(IosVideoBridge.shared.intentCount())
        guard total > 0 else {
            intentLabels = []
            intentOneLines = []
            return
        }
        var labels: [String] = []
        var oneLines: [String] = []
        labels.reserveCapacity(total)
        oneLines.reserveCapacity(total)
        for offset in 0..<total {
            // Swift `Int` → Kotlin `Int` 必须显式转 `Int32`（无隐式转换）。
            let index = Int32(offset)
            labels.append(IosVideoBridge.shared.intentLabelAt(index: index))
            oneLines.append(IosVideoBridge.shared.intentOneLineAt(index: index))
        }
        intentLabels = labels
        intentOneLines = oneLines
    }

    /// 按当前下标取「一句话说明」；越界或清单还没读到时给空串。
    private func oneLineForCurrentIntent() -> String {
        let index = Int(intentIndex)
        guard index >= 0, index < intentOneLines.count else { return "" }
        return intentOneLines[index]
    }

    /// 重算方案并落到本地状态。
    ///
    /// ★ 只在两处调用：`onAppear` 与切换意图（见文件头「为什么不在 body 里调桥」）。
    ///
    /// ★ `sceneName` 用 coordinator 的当前值（`SceneType` 的字符串口径）；
    ///   未知/空串在 Kotlin 侧会落回 `GENERAL`，所以这里不做校验。
    ///   `stats` 由门面自己从 `IosFrameCache` 取 —— 刚进页面、摄像头还没出第一帧时
    ///   它取到 null，那是**合法**调用（`VideoRecommender` 会用默认亮度算一份方案）。
    private func refresh() {
        // 复制提示属于「上一份方案」的反馈，换了方案就该收起来。
        showsCopied = false
        guard let dto = IosVideoBridge.shared.plan(
            intentIndex: intentIndex,
            profile: profile,
            sceneName: coordinator.sceneName
        ) else {
            // 越界才会走到这里（`VideoRecommender.plan` 本身对任何设备都返回方案）。
            plan = nil
            return
        }
        plan = VideoPlanRow(from: dto)
    }

    /// 切换意图并重算。
    /// - Parameter index: 目标意图下标。
    private func selectIntent(_ index: Int32) {
        if index != intentIndex {
            intentIndex = index
        }
        refresh()
    }
}
