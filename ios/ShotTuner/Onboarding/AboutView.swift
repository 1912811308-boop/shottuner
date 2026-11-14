// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Onboarding/AboutView.swift —— 关于页（隐私政策入口 + 双清单入口 + 版本信息）。
//
// ★ 为什么这一页是**阻塞级**上架项：
//   1. 政策第八节第 3 条写着「本政策的历史版本可在应用内『关于』页面查阅」——
//      没有这一页，那句话就是假的；
//   2. 用户的路径是「同意 → 之后再也找不到政策」。Android 侧也踩过同一个坑
//      （`MainActivity.showAbout()` 的注释写着：在此之前全 App 只有首次覆盖层能看全文）；
//   3. 「双清单」的两个入口必须挂在某个页面上，商店审核找的就是这个位置。
//   见 `docs/RELEASE-PRD.md` 风险 6 与第 7 节第 5 项。
//
// ★ 三个入口（与 Android 关于页一一对应，文案不改）：
//   ① 隐私政策全文      → `PrivacyPolicyTextView`（与同意页共用同一份正文，不复制第二份）
//   ② 个人信息收集清单  → `DoubleListView(kind: .personalInfoCollected)`
//   ③ 第三方共享清单    → `DoubleListView(kind: .thirdPartyShared)`
//
// ★ 为什么三个入口都是 `NavigationLink` 推入、而不是各自开一个 sheet：
//   本页自己就活在一个 sheet 里（由 `ContentView` 的 `activePage` 驱动）。
//   在 sheet 里再开 sheet，iOS 16 上虽然支持，但「关掉第二层之后第一层状态错乱」
//   这类问题只在真机上偶发，而在没有 Xcode 的这台机器上无法验证。
//   推入是**结构上**没有这个风险的那种做法：返回按钮由系统给，关了就是关了。
//
// ★ 为什么版本号要从 `Bundle` 现读、不写死：
//   `MARKETING_VERSION` 在 `ios/project.yml` 里，写死的副本一定会在某次升版后忘记改
//   （Android 侧的 `showAbout()` 也是现读 `versionName`，同一个理由）。

// ★ `import Foundation` 不能省：版本号走 `Bundle` / `CharacterSet`（`.whitespaces`），
//   这些是 Foundation 的类型，不靠 SwiftUI 的再导出（本仓 `GuideOverlayView` 同样显式写）。
import Foundation
import SwiftUI

/// 关于页。
struct AboutView: View {

    /// 隐私同意状态。本页**只读**它（显示当前是「已同意」还是「受限模式」），
    /// 不改它 —— 撤回同意走的是系统设置里的权限开关，与 Android 侧口径一致。
    @ObservedObject var consent: PrivacyConsentStore

    /// 关闭回调（由接线方决定是 dismiss 还是切页）。
    var onClose: () -> Void

    // MARK: 布局常量

    private enum Layout {
        /// 入口行的最小高度：苹果的人机界面指南要求可点区域 ≥ 44pt。
        static let rowMinHeight: CGFloat = 48
        /// 圆角，与复盘卡 / 任务卡 / 录像方案页一致。
        static let cornerRadius: CGFloat = 12
    }

    var body: some View {
        // 用 NavigationView（iOS 13+）而不是 NavigationStack（iOS 16+）：
        // 照本仓既有先例（`VideoPlanView` / `TechniqueDexView`），同一批页面统一样式。
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    metaBlock
                    entryBlock
                    networkNote
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // ★ `Color.black` 而不是自造色板成员：`Palette` 里只有前景色，
            //   背景一直由根视图的黑色提供（与 `VideoPlanView` / `DoubleListView` 同口径）。
            .background(Color.black)
            .navigationTitle("关于")
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
        // iPad 上裸 `NavigationView` 会变成「左空右内容」的分栏，一个工具页不该长成那样。
        .navigationViewStyle(StackNavigationViewStyle())
        // 强制深色：`Palette` 的文字色是给深底设计的浅色，浅色外观下会变成白字白底。
        .preferredColorScheme(.dark)
    }

    // MARK: 子视图

    /// 应用名 / 版本 / 联系方式 / 政策版本 / 当前同意状态。
    ///
    /// ★ 把「当前状态」摆出来是有用的：用户点了「暂不同意」之后，唯一能确认
    ///   「我确实没同意」的地方就是这里；否则他只能靠「相机没画面」去猜。
    private var metaBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(PrivacyText.operatorName) · 版本 \(appVersion)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Palette.overlayText)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text("联系邮箱：\(PrivacyText.contactEmail)")
                .font(.system(size: 12))
                .foregroundStyle(Palette.overlayTextSecondary)

            Text("隐私政策：\(PrivacyText.versionLabel)（\(PrivacyText.effectiveDate) 生效）")
                .font(.system(size: 12))
                .foregroundStyle(Palette.overlayTextSecondary)

            Text(consentStatusText)
                .font(.system(size: 12))
                // 受限状态用警示色，已同意用次级文字色 —— 一眼能分辨，不必读字。
                .foregroundStyle(consent.hasAccepted ? Palette.overlayTextSecondary : Palette.warn)
        }
        // 允许长按选中复制：联系邮箱是用户真要拿去用的东西。
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 三个入口。
    private var entryBlock: some View {
        VStack(spacing: 10) {
            NavigationLink(
                destination: PrivacyPolicyTextView()
                    .navigationTitle("隐私政策")
                    .navigationBarTitleDisplayMode(.inline)
            ) {
                entryLabel(
                    "隐私政策全文",
                    "\(PrivacyText.versionLabel) · \(PrivacyText.effectiveDate) 生效 · 与 Android 版逐字相同"
                )
            }
            .buttonStyle(.plain)

            NavigationLink(destination: DoubleListView(kind: .personalInfoCollected)) {
                entryLabel("个人信息收集清单", "本应用不收集任何个人信息")
            }
            .buttonStyle(.plain)

            NavigationLink(destination: DoubleListView(kind: .thirdPartyShared)) {
                entryLabel("第三方共享清单", "不含广告 / 统计 / 推送 / 崩溃上报 SDK")
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 18)
    }

    /// 一条入口行：主标题 + 副标题 + 右向箭头。
    private func entryLabel(_ title: String, _ subtitle: String) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.accent)
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Palette.overlayTextSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: Layout.rowMinHeight, alignment: .leading)
        .background(Palette.overlayText.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: Layout.cornerRadius, style: .continuous))
    }

    /// 零网络的结论行。
    ///
    /// ★ 与 Android 关于页末尾那句同口径，只把「未申请网络访问权限」换成 iOS 的事实：
    ///   iOS 没有「网络权限」这个开关，能如实说的是「不含网络请求代码、没有放宽 ATS」。
    private var networkNote: some View {
        Text("本应用不含任何网络请求代码，也没有声明任何联网能力，在操作系统层面不具备联网能力。")
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 20)
    }

    // MARK: 数据

    /// 版本号：`CFBundleShortVersionString`（对应 `project.yml` 的 `MARKETING_VERSION`）
    /// + `CFBundleVersion`（`CURRENT_PROJECT_VERSION`）。
    ///
    /// ★ 两个都读不到时返回「未知」而不是编一个数字：编出来的版本号会让人拿它去报案、
    ///   去对日志，对不上之后排查方向就彻底错了。
    private var appVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String

        let shortText = (short ?? "").trimmingCharacters(in: .whitespaces)
        let buildText = (build ?? "").trimmingCharacters(in: .whitespaces)

        switch (shortText.isEmpty, buildText.isEmpty) {
        case (false, false):
            return "\(shortText)（构建 \(buildText)）"
        case (false, true):
            return shortText
        case (true, false):
            return "构建 \(buildText)"
        case (true, true):
            return "未知"
        }
    }

    /// 当前同意状态的一句话。
    private var consentStatusText: String {
        consent.hasAccepted
            ? "隐私同意状态：已同意 \(PrivacyText.versionLabel)"
            : "隐私同意状态：受限模式（尚未同意隐私政策）"
    }
}
