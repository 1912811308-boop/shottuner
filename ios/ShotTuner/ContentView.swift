// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/ContentView.swift —— iOS 端主界面：取景 + 覆盖层 + 参考分。
//
// ★ 这个文件是**唯一的接线点**。四个模块各自独立开发，谁也不引用谁：
//
//     CameraSession         采集帧 / 出预览层 / 报能力
//          ↓ onFrame
//     ScoreCoordinator      节流 + 后台算 + 回主线程发 @Published
//          ↓ score / histogram / guide
//     GuideOverlayView      覆盖层（三分线、地平线、主体圈、俯仰刻度）
//     HistogramView         亮度直方图
//     MissionCardView       任务卡（数据源还没接，见下）
//
// ★ 为什么接线集中在这一个文件：
//   如果每个视图自己去调 `IosBridge`，就会出现「三处各算一遍、各自缓存」，
//   而且节流策略会分散。现在唯一的真相是 `ScoreCoordinator` 的三个
//   `@Published`，视图只读不写。
//
// ★ 任务卡还没接（`MissionSnapshot.idle`）：
//   T14 的 UI 已完成，但生成 `MissionSnapshot` 的 Kotlin 侧 `missionTick`
//   还没做（`MissionEngine` 要包一层跨语言门面）。在那之前卡片恒为 idle，
//   也就是**不显示** —— 这符合产品口径（没有可派发的任务时卡片整个消失，
//   不显示"暂无任务"这种占位）。

import AVFoundation
import SwiftUI
import UIKit

/// iOS 端根界面。
///
/// ★ 为什么整个 struct 标 `@MainActor`（而不是只靠 `body` 的隐式隔离）：
///   `ScoreCoordinator` 是 `@MainActor` 类，它的 `init` 也是隔离的。
///   而 `@StateObject private var coordinator = ScoreCoordinator()` 这个默认值
///   是在 struct 的（非隔离）初始化器里求值的 —— Swift 5.9 会报
///   "Call to main actor-isolated initializer in a synchronous nonisolated context"。
///   把隔离级别提到 struct 上，这一行就不用改成 `StateObject(wrappedValue:)`
///   那种绕法，也顺便让所有 UI 回调自动落在主线程。
@MainActor
struct ContentView: View {

    /// 前后台状态。用于在切后台时停掉姿态采样（见 `body` 里的 `onChange`）。
    @Environment(\.scenePhase) private var scenePhase

    /// 相机权限状态机（只负责要权限，不碰任何图像数据）。
    @StateObject private var cameraPermission = CameraPermissionModel()

    /// 采集会话。
    ///
    /// ★ 用 `@State` 而不是 `@StateObject`：`CameraSession` 是 `NSObject` 子类，
    ///   不发布任何 `@Published`（它用回调 + 锁），所以不是 `ObservableObject`。
    ///   `@State` 对引用类型同样保证「只创建一次」，视图重建不会重建会话。
    @State private var session = CameraSession()

    /// 评分协调层。它是**唯一**持有 `ScoreCoordinator` 的地方。
    @StateObject private var coordinator = ScoreCoordinator()

    /// 姿态采样（重力 → 横滚 / 俯仰）。
    ///
    /// ★ 必须是 `@State` 而不是每次 `onAppear` 现 `new` 一个：`CMMotionManager`
    ///   是系统级单例代理，重复实例化会让回调节奏变得不可预期；`@State` 保证
    ///   视图重建（横竖屏、分屏、SwiftUI 重算 body）时拿到的是同一个对象。
    ///
    /// ★ 它不是 `ObservableObject`：本类不发布 `@Published`，读数通过 `start` 的
    ///   回调直接写进 `ScoreCoordinator`。所以这里不能用 `@StateObject`。
    @State private var motion = MotionProvider()

    /// 技法图鉴是否以模态呈现。
    @State private var showingTechniqueDex = false

    /// 是否显示详细统计（直方图 + 四维明细）。默认收起，把画面还给取景。
    @State private var showingDetails = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if cameraPermission.status.allowsCapture {
                cameraLayer
                overlayLayer
                chromeLayer
            } else {
                permissionGate
            }
        }
        .statusBarHidden(true)
        // ★ 前后台切换**必须**在这里管：SwiftUI 的 `onDisappear` 在「App 切到后台」
        //   时并**不会**被调用（视图还在层级里，只是被盖住了），
        //   只靠它的话 `CMMotionManager` 会一直以 30Hz 跑到用户手动杀进程 ——
        //   既是耗电问题，也是审核问题（`MotionProvider.stop()` 的文档里写死了这条）。
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                // 只重起传感器，**不碰相机**：`CameraSession.start()` 不是幂等的
                //（会重跑一遍 `configureAndStart`，重复加 outputs 会抛异常），
                // 而相机从后台回来由系统自己恢复，不需要我们插手。
                if cameraPermission.status.allowsCapture { startMotion() }
            case .inactive, .background:
                motion.stop()
            default:
                // ★ 这里刻意用 `default` 而不是 `@unknown default`：
                //   `@unknown default` 只对**枚举**合法，对结构体是编译错误。
                //   本仓从未编译过 iOS，写 `default` 是两种声明形式下都成立的那个。
                break
            }
        }
        .onAppear(perform: startIfAllowed)
        .onDisappear(perform: stopEverything)
        .sheet(isPresented: $showingTechniqueDex) {
            TechniqueDexView()
        }
    }

    // ── 取景 + 覆盖层 ────────────────────────────────────────────────────────
    private var cameraLayer: some View {
        CameraPreviewLayer(session: session)
            .ignoresSafeArea()
    }

    /// 取景覆盖层。
    ///
    /// ★ 顺序不能换：预览层在下、覆盖层在上。覆盖层是透明的 `Canvas`，
    ///   底色由预览层给；反过来会被黑色背景挡住。
    private var overlayLayer: some View {
        GuideOverlayView(snapshot: coordinator.guide)
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    // ── 界面控件 ────────────────────────────────────────────────────────────
    private var chromeLayer: some View {
        VStack(spacing: 0) {
            topBar
            Spacer()
            scorePanel
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var topBar: some View {
        HStack(alignment: .top, spacing: 12) {
            // 直方图：靠左，半透明底，不抢画面
            HistogramView(snapshot: coordinator.histogram)
                .frame(width: 132, height: 64)
                .padding(8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            Spacer(minLength: 0)

            Button {
                showingTechniqueDex = true
            } label: {
                Image(systemName: "book.closed")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Palette.overlayText)
                    .frame(width: 40, height: 40)
                    .background(.ultraThinMaterial, in: Circle())
            }
        }
    }

    /// 参考分面板。
    ///
    /// ★ 产品口径：任一维度未测时总分显示「参考分 --」，不显示数字。
    ///   这条判断靠 `ScoreSnapshot.hasTotal`，不要在视图里自己遍历四维。
    private var scorePanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(coordinator.score.hasTotal ? "参考分 \(coordinator.score.total)" : "参考分 --")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(Palette.overlayText)

                if coordinator.score.hasTotal {
                    Text("/ 100")
                        .font(.footnote)
                        .foregroundStyle(Palette.overlayTextSecondary)
                }

                Spacer(minLength: 8)

                if !coordinator.score.grade.isEmpty {
                    Text(coordinator.score.grade)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Palette.accent.opacity(0.18), in: Capsule())
                }
            }

            // 四维进度条
            HStack(spacing: 10) {
                ForEach(coordinator.score.dimensions) { dimension in
                    DimensionBar(dimension: dimension)
                }
            }

            // 提示卡：只说一条，只给动作
            if !coordinator.score.tipText.isEmpty {
                Text(coordinator.score.tipText)
                    .font(.footnote)
                    .foregroundStyle(Palette.overlayText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        // ★ 材质会自适应明暗，亮背景（天空/白墙）上它会变成白色，
        //   白字直接消失。把局部配色锁成深色，材质恒定渲成深色底。
        .environment(\.colorScheme, .dark)
    }

    // ── 权限门 ──────────────────────────────────────────────────────────────
    private var permissionGate: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(Palette.accent)

            Text("光影调参")
                .font(.title2.bold())
                .foregroundStyle(Palette.overlayText)

            Text(cameraPermission.status.text)
                .font(.footnote)
                .foregroundStyle(Palette.overlayTextSecondary)
                .multilineTextAlignment(.center)

            Button {
                cameraPermission.request()
            } label: {
                Text(cameraPermission.status.allowsCapture ? "重新检查权限" : "申请相机权限")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Palette.accent, in: Capsule())
            }

            Text("照片只在这台设备上分析，不会上传。App 给的是参考分与「照拨」建议，参数由你自己调。")
                .font(.caption)
                .foregroundStyle(Palette.overlayTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
        }
        .padding(32)
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────
    private func startIfAllowed() {
        cameraPermission.refresh()
        guard cameraPermission.status.allowsCapture else { return }
        session.start()
        coordinator.attach(to: session)
        startMotion()
    }

    /// 起姿态采样，把读数直接喂进协调层。
    ///
    /// ★ 为什么这一段不能省（`0f89dd3` 之前就是漏了它）：
    ///   `ScoreCoordinator` 的 `level` 维度只在 `rollValid == true` 时才算得出来。
    ///   没人调 `updateMotion` ⇒ `rollValid` 恒 `false` ⇒ Kotlin 侧收到 `roll = null`
    ///   ⇒ `level.measured = false` ⇒ `ScoreSnapshot.hasTotal == false` ⇒
    ///   **总分永远显示「参考分 --」**。全程不崩、不报错、日志干净，
    ///   从 UI 上看起来只是「还没测出来」，所以这个漏洞靠肉眼回归抓不住。
    ///
    /// ★ 不判 `motion.isAvailable`：拿不到 deviceMotion 时 `start` 内部直接返回，
    ///   一次回调都不发，`rollValid` 保持 `false` —— 正是「静默隐藏水平仪」的语义。
    ///   在这里再判一次只会让能力探测分散在两处。
    private func startMotion() {
        motion.start { rollDegrees, pitchDegrees, valid in
            // ★ 直接写 `coordinator`，不包 `Task { @MainActor }`：
            //   `MotionProvider.start` 的闭包已经声明成 `@MainActor`，
            //   本方法也跑在主 actor 上，再包一层只会多一次调度。
            coordinator.updateMotion(
                rollDegrees: rollDegrees,
                pitchDegrees: pitchDegrees,
                rollValid: valid
            )
        }
    }

    private func stopEverything() {
        // ★ 顺序：先停传感器，再断帧，最后停相机会话。
        //   反过来先 `detach` 的话，`gate` 已经作废，可传感器还在发 —— 那几十毫秒里
        //   每一帧都会走一次 `updateMotion`，白改一次 `@Published`，
        //   页面都关了还会让 SwiftUI 重算一次 body。
        motion.stop()
        coordinator.detach()
        session.stop()
    }
}

// MARK: - 四维进度条

/// 单维度：名字 + 分数 + 一条细进度条。
private struct DimensionBar: View {

    let dimension: DimensionSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Text(dimension.label)
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.overlayTextSecondary)
                    .lineLimit(1)

                Spacer(minLength: 2)

                // ★ 未测时显示占位符而不是 0 —— 0 分会被读成"你这项做得很差"，
                //   而这只是"这一帧没算出来"。
                Text(dimension.measured ? "\(dimension.score)" : "--")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(dimension.measured ? Palette.overlayText : Palette.overlayTextSecondary)
            }

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.18))
                    Capsule()
                        .fill(Palette.accent)
                        .frame(width: barWidth(in: geo.size.width))
                }
            }
            .frame(height: 3)
        }
        .frame(maxWidth: .infinity)
    }

    private func barWidth(in total: CGFloat) -> CGFloat {
        guard dimension.measured, dimension.max > 0 else { return 0 }
        let ratio = CGFloat(dimension.score) / CGFloat(dimension.max)
        return total * min(max(ratio, 0), 1)
    }
}

// MARK: - 相机预览层

/// 把 `AVCaptureVideoPreviewLayer` 包成 SwiftUI 视图。
///
/// ★ 为什么不用 `AVCaptureVideoPreviewLayer` 的 SwiftUI 原生替代：
///   SwiftUI 目前没有。iOS 16 的 `CameraPreview` 也不存在（那是第三方库）。
///   所以必须 `UIViewRepresentable` —— 这是唯一一处绕不开的 UIKit。
///
/// ★ `layoutSubviews` 那一步不能省：`UIViewRepresentable` 给的 frame 在
///   旋转 / 安全区变化时会变，但 `CALayer` 的子层不会自动跟随父 view 的
///   边界变化重排（`videoGravity` 只管内容怎么填，不管 layer 自身尺寸）。
struct CameraPreviewLayer: UIViewRepresentable {

    let session: CameraSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        view.attach(session.makePreviewLayer())
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // 预览层绑的是 session 实例，不需要在每次 SwiftUI 更新时重建。
    }

    /// 自带一个 `AVCaptureVideoPreviewLayer` 子层的容器。
    final class PreviewView: UIView {

        private var previewLayer: AVCaptureVideoPreviewLayer?

        func attach(_ layer: AVCaptureVideoPreviewLayer) {
            previewLayer?.removeFromSuperlayer()
            layer.frame = bounds
            self.layer.addSublayer(layer)
            previewLayer = layer
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            // 关掉隐式动画：转屏时 layer 尺寸跳变会有一段可笑的插值动画。
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            previewLayer?.frame = bounds
            CATransaction.commit()
        }
    }
}

// MARK: - 预览

#Preview("主界面 · 无权限") {
    ContentView()
}
