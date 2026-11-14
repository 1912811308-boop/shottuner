// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Onboarding/PrivacyGateView.swift —— 首次启动的隐私同意页 + 同意状态存储。
//
// ★ 这个文件存在的理由（上架硬门槛）：
//   Apple 审核要求 App 在**收集任何数据之前**给出隐私政策入口，并且要求「拒绝」是可走通的。
//   本仓 Android 版早已有这道门（`llPrivacyGate`），iOS 版一直没有 ——
//   也就是说 iOS 版一启动就直奔相机权限，用户还没看过任何条款。
//
// ★ 三条硬约束，全部落在本文件里：
//   ① 未同意 → **不请求任何权限**。状态由 `PrivacyConsentStore` 掌握，
//      `ContentView.startIfAllowed()` 的第一句就是查它（见那边的注释）。
//   ② 同意状态存 `UserDefaults`，存的是**版本号**（Int），不是 Bool ——
//      条款升版要能重新征得同意。理由见 `PrivacyConsentStore.acceptedVersion`。
//   ③ 「暂不同意」不是死路：进受限模式，技法 / 录像 / 功能页照常可用（见 `ContentView`）。
//
// ★ 为什么状态机放一个 ObservableObject，而不是在 View 里放两个 @State：
//   同意状态有三个读者 —— 同意页（决定显示什么）、ContentView（决定请求不请求权限）、
//   关于页（显示当前是「已同意」还是「受限模式」）。三个读者各自持一份 @State
//   必然出现「这边同意了、那边还认为没同意」，而这正好是「未同意却要了权限」的来源。
//   ⇒ 单一来源，`@StateObject` 在 ContentView 里创建一次，向下传引用。

// ★ `import Combine` 不能省：`ObservableObject` 与 `@Published` 定义在 Combine 里。
//   本仓同类文件（`CameraPermissionModel` / `ScoreHistoryStore` / `MissionModel` /
//   `ParamAdvisor`）都显式 import 它，`ScoreHistoryStore` 的文件头还记着「不写就报
//   cannot find type 'ObservableObject' in scope」。
//   `import Foundation` 是 `UserDefaults` 的来源，同样显式写出来。
import Combine
import Foundation
import SwiftUI

// MARK: - 同意状态

/// 隐私同意状态（单一来源）。
///
/// ★ 刻意**不**标 `@MainActor`：本仓既有的 `CameraPermissionModel` /
///   `ScoreHistoryStore` 都是普通 `ObservableObject`，全部访问都发生在主线程的 UI 回调里，
///   标上隔离级别只会在「Button 的 action 闭包是非隔离的」这件事上引入额外的报错面
///   （`ContentView` 的文件头记着同一类坑）。`UserDefaults` 本身线程安全。
final class PrivacyConsentStore: ObservableObject {

    // MARK: 键

    /// 已同意政策的版本号（`Int`）。
    ///
    /// ★ 键名与 Android 侧的**同一个键族**对齐：Android 是
    ///   `KEY_PRIVACY_ACCEPTED = "privacy_accepted_v1"`（`MainActivity.kt:3050` 附近），
    ///   它把版本号编进**键名**、值是布尔。
    ///   iOS 这边把版本号放进**值**（键固定为 `privacy_accepted_version`），
    ///   好处是升版时不需要改键名、也不会留下 `privacy_accepted_v1` / `..._v2` 两个键
    ///   各自表示「同意过」的混乱状态（那正是「用户到底同意了哪一版」说不清的原因）。
    ///   前缀 `privacy_accepted` 与 Android 完全一致，两端的意图一眼能对上。
    ///
    /// ★ 为什么不与 Android 逐字节共用键名：两端的偏好存储本来就是两个不同的域
    ///   （`SharedPreferences` vs `UserDefaults`），从未互相读取；
    ///   而 Android 那个键的值类型是 Bool，共用键名会让「值到底是 1 还是 true」变得可疑。
    static let acceptedVersionKey: String = "privacy_accepted_version"

    // MARK: 状态

    /// 已经同意过的政策版本号。`0` 表示从未同意过（`UserDefaults` 里没有这个键）。
    @Published private(set) var acceptedVersion: Int

    /// 本次会话里用户是否已经做过选择（同意**或**暂不同意）。
    ///
    /// ★ 只存在内存里，**不落盘** —— 与 Android `declinePrivacy()` 的取舍一致：
    ///   「暂不同意」不写 `false`，因为「没同意过」与「明确拒绝过」在语义上是一回事，
    ///   而写盘会多出一种「用户真的拒绝过」的状态，却没有任何功能用到它。
    ///   进程被杀掉之后这个值回到 `false`，下次启动重新征求意见 —— 这正是政策
    ///   第八节第 2 条要求的行为。
    @Published private(set) var decisionMadeThisSession: Bool = false

    /// 偏好存储。注入是为了测试不受真实 `UserDefaults` 影响。
    private let defaults: UserDefaults

    // MARK: 初始化

    /// 从 `UserDefaults` 读出已同意的版本号。
    ///
    /// - Parameter defaults: 偏好存储，默认 `.standard`。
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // `integer(forKey:)` 在键不存在时返回 0 —— 正好是「从未同意过」。
        self.acceptedVersion = defaults.integer(forKey: Self.acceptedVersionKey)
    }

    // MARK: 派生状态

    /// 是否已同意**当前版本**的政策。
    ///
    /// ★ 用 `>=` 而不是 `==`：用户同意过更高的版本（未来从 v2 回退安装到 v1）时
    ///   不该被再问一遍。
    var hasAccepted: Bool {
        acceptedVersion >= PrivacyText.version
    }

    /// 是否应该显示同意页（「同意页还盖在最上层」）。
    var showsGate: Bool {
        !hasAccepted && !decisionMadeThisSession
    }

    /// 是否处于受限模式。
    ///
    /// ★ 与 `showsGate` 是两个不同的问题：
    ///   - `showsGate`：同意页此刻要不要盖住界面；
    ///   - `isRestricted`：**有没有权限碰相机**。
    ///   用户点了「暂不同意」之后，同意页收起（`showsGate == false`），
    ///   但受限状态**继续成立**（`isRestricted == true`）—— 相机与快门全程关闭。
    var isRestricted: Bool {
        !hasAccepted
    }

    // MARK: 动作

    /// 同意：写盘（版本号）→ 收起同意页。
    ///
    /// ★ 写的是 `PrivacyText.version` 这个整数，不是 `true`。
    func accept() {
        acceptedVersion = PrivacyText.version
        defaults.set(PrivacyText.version, forKey: Self.acceptedVersionKey)
        decisionMadeThisSession = true
    }

    /// 暂不同意：进受限模式。**不写盘**（理由见 `decisionMadeThisSession`）。
    func decline() {
        decisionMadeThisSession = true
    }

    /// 把同意页重新叫回来（受限模式那一屏的「重新选择」按钮用）。
    ///
    /// ★ 只改会话状态、不动已落盘的版本号：用户已经同意过 v1 又点回来时，
    ///   `hasAccepted` 仍然是 `true`，这里只是让他再看一遍条款。
    func requestConsentAgain() {
        decisionMadeThisSession = false
    }
}

// MARK: - 同意页

/// 首次启动的隐私同意页。
///
/// ★ 为什么是「嵌在 ContentView 的 ZStack 一个分支里」而不是 `.fullScreenCover`：
///   根节点上已经挂了一个 `.sheet(item: $activePage)`（四个页面共用的那个，见
///   `ContentView.body`）。在同一棵树上再挂一个模态呈现，就回到了「两个呈现同时为真时
///   只有一个生效、顺序不保证」那个坑。用一个普通分支，显示关系是**结构性的**：
///   同意页在时，取景层根本不在视图树里，相机链路也就无从启动。
///
/// ★ 它自己不碰任何权限 API —— 这个文件里没有 `AVCaptureDevice`，
///   也没有 `PHPhotoLibrary`。要权限的地方全在 `ContentView`，且都在同意之后。
struct PrivacyGateView: View {

    /// 同意状态（由 `ContentView` 持有，这里只读写它的动作）。
    @ObservedObject var consent: PrivacyConsentStore

    /// 是否正在看政策全文。
    @State private var showingFullText = false

    // MARK: 布局常量

    private enum Layout {
        /// 同意按钮高度。苹果的人机界面指南要求可点区域 ≥ 44pt，这里给足。
        static let primaryButtonHeight: CGFloat = 52
        /// 次级按钮高度。
        static let secondaryButtonHeight: CGFloat = 48
        /// 卡片圆角，与复盘卡 / 任务卡 / 录像方案页一致。
        static let cornerRadius: CGFloat = 12
    }

    var body: some View {
        ZStack {
            // ★ 底色用 `Color.black` 而不是自造色板成员：`Palette` 里只有前景色，
            //   背景一直由根视图的黑色提供（与 `VideoPlanView` / `PhotoReviewSheet` 同口径）。
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    titleBlock
                    summaryBlock
                    fullTextButton
                    agreeButton
                    denyButton
                    footnoteBlock
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 32)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        // 强制深色：`Palette` 的文字色是给深底设计的浅色，浅色外观下会变成白字白底。
        .preferredColorScheme(.dark)
        // ★ 这里挂 `.sheet` 是安全的：本视图与根 `ZStack` 是**不同节点**，
        //   各自走自己的呈现通道（`ContentView.shutterBar` 上的 sheet / alert 同理）。
        .sheet(isPresented: $showingFullText) {
            NavigationView {
                PrivacyPolicyTextView()
                    .navigationTitle("隐私政策")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("关闭") {
                                showingFullText = false
                            }
                            .foregroundStyle(Palette.accent)
                        }
                    }
            }
            .navigationViewStyle(StackNavigationViewStyle())
            .preferredColorScheme(.dark)
        }
    }

    // MARK: 子视图

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(PrivacyText.gateTitle)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Palette.overlayText)

            Text("\(PrivacyText.operatorName) · 隐私政策 \(PrivacyText.versionLabel) · \(PrivacyText.effectiveDate) 生效")
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
        }
    }

    private var summaryBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(PrivacyText.gateSummaryPoints, id: \.self) { point in
                Text(point)
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineSpacing(4)
                    // 长句必须能换行，不能被横向截断 —— 摘要里任何一条被截掉，
                    // 用户就等于没看到那一条（第四条正是「你可以拒绝」）。
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 16)
    }

    private var fullTextButton: some View {
        Button {
            showingFullText = true
        } label: {
            Text("查看完整隐私政策")
                .font(.system(size: 14))
                .foregroundStyle(Palette.accent)
                .frame(maxWidth: .infinity, minHeight: Layout.secondaryButtonHeight)
                .background(Palette.overlayText.opacity(0.10))
                .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.top, 24)
    }

    private var agreeButton: some View {
        Button {
            // 写盘（版本号）+ 收起同意页。相机权限的申请由 `ContentView` 的
            // `onChange(of: consent.acceptedVersion)` 接手 —— 那样「同意之后发生什么」
            // 只有一处实现，不会出现「同意页自己起相机、ContentView 又起一次」。
            consent.accept()
        } label: {
            Text("同意并继续")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Color.black)
                .frame(maxWidth: .infinity, minHeight: Layout.primaryButtonHeight)
                .background(Palette.accent)
                .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.top, 12)
    }

    private var denyButton: some View {
        Button {
            // ★ 「暂不同意」必须是**真按钮**，不是把用户堵在这里。
            //   它进受限模式：技法 / 录像 / 功能三页照常可用，相机与快门关闭。
            consent.decline()
        } label: {
            Text("暂不同意")
                .font(.system(size: 13))
                .foregroundStyle(Palette.overlayTextSecondary)
                .frame(maxWidth: .infinity, minHeight: Layout.secondaryButtonHeight)
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    private var footnoteBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("点「暂不同意」不会退出 App，你会进入受限模式：拍照与录像关闭，技法、方案与功能说明照常可以看。你随时可以在那一屏点「同意隐私政策并开启相机」重新选择。")
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)

            Text("照片只在这台设备上分析，不会上传。")
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 20)
    }
}

// MARK: - 政策全文（纯内容，不带导航壳）

/// 政策正文：iOS 平台说明卡片 + 全文。
///
/// ★ 只做渲染，不含任何导航壳（有没有导航栏由调用方决定）：
///   - 同意页把它包在 `NavigationView` 里、当模态呈现（自带「关闭」）；
///   - 关于页用 `NavigationLink` 把它推进去（靠系统返回按钮）。
///   两种进入方式共用同一份正文，避免「关于页里的政策」与「同意页里的政策」变成两份。
///
/// ★ 正文来自 `PrivacyText.policyFullText` —— 与 Android `res/raw/privacy_v1.txt`
///   **字节一致**（len 4832 已核对）。这里一个字都不改写、不排版、不删节。
struct PrivacyPolicyTextView: View {

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                iosPlatformNote
                Text(PrivacyText.policyFullText)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // 允许长按选中复制：政策里写着联系邮箱与投诉途径，
                    // 用户要能复制走，而不是照着屏幕手抄。
                    .textSelection(.enabled)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.black)
        .preferredColorScheme(.dark)
    }

    /// iOS 平台差异说明。
    ///
    /// ★ 为什么放在**全文之前**、且明确标注：政策第二节列的是 Android 的权限名，
    ///   iOS 用户读到会困惑（我这台手机没有「悬浮窗」这个开关）。先说清楚适用范围，
    ///   再给全文，比让用户自己去猜要诚实。
    private var iosPlatformNote: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("iOS 版说明")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Palette.accent)

            VStack(alignment: .leading, spacing: 6) {
                ForEach(PrivacyText.iosPlatformNotePoints, id: \.self) { point in
                    Text("· " + point)
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.overlayTextSecondary)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.overlayText.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
