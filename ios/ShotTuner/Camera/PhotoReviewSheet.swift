// SPDX-License-Identifier: MIT
//
// PhotoReviewSheet.swift —— 拍完之后的复盘卡 + 系统分享面板。
//
// ★ 为什么复盘图和分享源的**都是内存里的 `UIImage`**，不是文件：
//   T17 的验收①是「照片写入相册，App 不保留副本」。
//   一旦为了分享而留一个临时文件，就出现「相册一份、沙盒一份」，
//   用户删了相册那张、App 里还躺着 —— 那就不叫不保留副本了。
//   而想「从相册读回来再分享」就必须 `.readWrite` 权限，
//   与 CaptureFlow 的 `.addOnly` 口径直接冲突（多要一档权限 = 多一句隐私文案 + 多一个审核问题）。
//   所以：**内存持有、分享完就放**，文件一律删干净。
//
// ★ 为什么复盘卡不做「分数面板」的复用：
//   取景页的 `scorePanel` 是**实时**的（每帧都可能变），复盘卡是**冻结**的
//   （拍下那一刻的参考分）。两者语义不同，混用一个视图迟早会出现
//   「复盘卡上的分数跟着后面的画面跳」。所以复盘卡的文案由调用点在按快门那一刻
//   就算好、当参数传进来，这里只负责显示。

import SwiftUI
import UIKit

/// 拍完之后的复盘卡。
///
/// 产品口径：
/// - 分数是**拍下那一刻**的参考分，不是当前的。文案由调用点算好传入。
/// - 未测全时传进来的是「参考分 --」，本视图**不认识**「未测」这个概念 ——
///   它只显示传进来的字符串。这样「未测不印数字」这条规则只有一个判定点。
/// - 照片已经在相册里了，这里不做「保存」按钮，只做「分享」与「完成」。
struct PhotoReviewSheet: View {

    /// 拍下的那张（已烧水印，如果开了水印）。
    let image: UIImage

    /// 冻结的参考分文案，例如「参考分 82 / 100」或「参考分 --」。
    let scoreText: String

    /// 关闭回调。调用方在这里调 `captureFlow.clearLastCaptured()` 放掉大图。
    var onDismiss: () -> Void = {}

    /// 分享面板是否展开。
    @State private var showsShareSheet = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                photo
                footer
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            // ★ 用 `Color.black` 而不是自造一个 `Palette.xxx`：
            //   `Palette` 里只有**前景色**（accent / warn / overlayText / hairline…），
            //   没有背景色 —— 背景一直由 `ContentView` 最底层的 `Color.black` 提供。
            //   在 `Palette` 里加一个只用一次的背景色，等于把「配色只有一个真出处」这条破掉。
            .background(Color.black)
            .navigationTitle("这张拍好了")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        onDismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        // ★ 强制深色外观，理由与 `MissionCardView` 那句一样：
        //   `Palette` 里的文字色是给深底设计的浅色，换成浅色外观就变成白字白底。
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showsShareSheet) {
            ActivityView(items: [image])
        }
    }

    // MARK: - 照片

    private var photo: some View {
        Image(uiImage: image)
            .resizable()
            // ★ `.scaledToFit` 而不是 `.fill`：复盘卡的第一职责是「让你看清自己拍了什么」，
            //   裁掉边缘会让人以为照片少了一块。宁可上下留黑。
            .scaledToFit()
            .frame(maxWidth: .infinity)
            .background(Color.black)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Palette.hairline, lineWidth: 1)
            )
    }

    // MARK: - 底部

    private var footer: some View {
        VStack(spacing: 12) {
            // 分数 + 一句「已经在相册里了」，让用户知道不用再找保存按钮。
            VStack(spacing: 4) {
                Text(scoreText)
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundStyle(Palette.accent)
                    // 等宽数字：参考分从 9 跳到 82 时卡片不左右抖。
                    .monospacedDigit()

                Text("已存入相册")
                    .font(.footnote)
                    .foregroundStyle(Palette.overlayTextSecondary)
            }

            HStack(spacing: 12) {
                Button {
                    showsShareSheet = true
                } label: {
                    Label("分享", systemImage: "square.and.arrow.up")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Palette.accent, in: Capsule())
                        // ★ 浅绿底（#7FE3C0）上白字的对比度只有约 1.4:1，基本读不清。
                        //   压成黑色（约 12:1）才行。`MissionCardView` 的「继续」按钮
                        //   踩的是同一个坑，那里也写的 `Color.black`。
                        .foregroundStyle(Color.black)
                }
            }
        }
    }
}

// MARK: - 系统分享面板

/// `UIActivityViewController` 的 SwiftUI 包装。
///
/// ★ 为什么用系统面板而不是自绘分享：
///   ① 系统面板里天然有「存储到文件 / 拷贝 / 隔空投送 / 微信」等目标，
///      自绘等于我们自己维护一份目标清单，永远跟不上；
///   ② 分享完成后系统自己收尾，我们不用管；
///   ③ 它是唯一能拿到「用户装了哪些 App」的合法渠道。
///
/// ★ `items: [Any]` 而不是 `[UIImage]`：`UIActivityViewController` 的签名的确是
///   `activityItems: [Any]`，传 `[UIImage]` 也能编译（数组协变），但用 `[Any]`
///   可以让将来加一段文字说明 / 一个 URL 时不用改这个包装。
private struct ActivityView: UIViewControllerRepresentable {

    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        // 无需更新：面板内容由 `items` 决定，而 `items` 在本次展示期间不会变。
    }
}

// MARK: - 取景页底部那条（快门 + 缩略图）

/// 快门条：中间大圆按钮，左边是刚拍那张的缩略图。
///
/// ★ 为什么单独抽出来：`ContentView` 已经 400 行，再塞 80 行按钮布局会淹掉它的生命周期逻辑。
///   本视图只做布局与"按下去要干嘛"的回调，不持有任何状态机。
struct ShutterBar: View {

    /// 是否正在拍照。为 true 时按钮置灰 —— `CaptureFlow.capture` 在重入时
    /// **直接返回且不回调**，如果按钮还可点，用户会觉得点了没反应。
    let isCapturing: Bool

    /// 刚拍那张的缩略图。nil 时显示占位圆角框（不显示「暂无照片」这类文字）。
    ///
    /// ★ 它同时决定了「能不能点开复盘」—— 有图就能点。不另设一个 `canReview` 开关：
    ///   两个布尔量表达同一件事，迟早会出现「有图但不可点」这种说不清的状态。
    let thumbnail: UIImage?

    var onShutter: () -> Void
    var onReview: () -> Void

    /// 快门直径。72pt 是「单手拇指能稳稳按到、又不至于挡住取景中心」的值。
    private static let shutterSize: CGFloat = 72
    /// 缩略图边长。
    private static let thumbSize: CGFloat = 52

    var body: some View {
        // ★ 用 ZStack 而不是 HStack 三等分：缩略图有、右侧没有对应元素，
        //   用 HStack 三等分会把快门推偏。ZStack 让快门永远在几何中心。
        ZStack {
            HStack {
                thumbnailView
                Spacer(minLength: 0)
            }

            shutterButton
        }
        .frame(height: Self.shutterSize)
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }

    private var shutterButton: some View {
        Button(action: onShutter) {
            ZStack {
                Circle()
                    .strokeBorder(Palette.overlayText.opacity(0.9), lineWidth: 3)
                    .frame(width: Self.shutterSize, height: Self.shutterSize)

                Circle()
                    .fill(isCapturing ? Palette.overlayTextSecondary : Palette.overlayText)
                    // 内圈留出 6pt 白边，是相机 App 的通用形态，用户不用学。
                    .frame(width: Self.shutterSize - 14, height: Self.shutterSize - 14)
            }
        }
        .buttonStyle(.plain)
        // ★ 不能用 `.disabled(isCapturing)`：那会让内圈变灰但按钮仍占位，
        //   而且禁用态没有任何视觉反馈（SwiftUI 默认只把不透明度降一点）。
        //   这里改成「允许点，但点了直接返回」——`capture` 自己会挡住重入。
        .opacity(isCapturing ? 0.55 : 1.0)
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if let thumbnail {
            Button(action: onReview) {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
                    .frame(width: Self.thumbSize, height: Self.thumbSize)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(Palette.hairline, lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
            // ★ 入场用 spring 而不是 `matchedGeometryEffect`：
            //   `matchedGeometryEffect` 要求源与目标**同时在视图树里**，
            //   而缩略图是「从无到有」出现的（拍照前那个位置是空的），
            //   没有可配对的源。硬做就要先放一个透明占位，收益只有"从快门飞过去"这一下，
            //   却引入一个必须在两个视图间共享的 Namespace —— 出问题时是静默失效。
            //   验收②要的是「缩略图出现 ≤400ms」，图在内存里，spring 是一帧内开始的。
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        } else {
            // 占位：同尺寸的空框，保证快门位置不因是否有照片而变化。
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.white.opacity(0.08))
                .frame(width: Self.thumbSize, height: Self.thumbSize)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Palette.hairline, lineWidth: 1)
                )
        }
    }
}
