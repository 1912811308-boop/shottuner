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

    /// 任务卡数据源。每 400ms 敲一次 commonMain 的 `MissionEngine`。
    ///
    /// ★ 它**自己不开定时器以外的逻辑**：判定、计时、文案全在 Kotlin。
    ///   Swift 侧只负责「到点了，把当前 roll 送进去，把快照翻成值类型」。
    @StateObject private var mission = MissionModel()

    /// 拍照 → 落相册 →（可选）水印 的流水线。
    @StateObject private var captureFlow = CaptureFlow()

    /// 复盘卡是否展开。
    @State private var showingReview = false

    /// 按快门那一刻**冻结**的参考分文案。
    ///
    /// ★ 不能等复盘卡打开时再读 `coordinator.score`：取景还在跑，
    ///   分数每帧都在变，复盘卡上的数字会跟着后面的画面跳。
    ///   复盘卡显示的是「你按下快门时这张照片值多少分」，必须在那一刻定住。
    @State private var reviewScoreText = ""

    /// 按快门那一刻**冻结**的分数快照。
    ///
    /// ★ 与 `reviewScoreText` 同一个理由、同一个时刻取，但用途不同：
    ///   文案是给水印和复盘卡看的（已经过 `ScoreWording` 口径处理），
    ///   快照要原样交给 `ScoreHistoryStore.record` 去落盘。
    ///   两者**都不能**读实时值 —— 否则「这张照片几分」会被后面几十帧改写。
    @State private var frozenScore: ScoreSnapshot = .empty

    /// 参考分历史。落盘在 Application Support（不参与 iCloud 备份），环形 500 条。
    ///
    /// ★ `@StateObject` 而不是 `@State`：它是 `ObservableObject`，
    ///   发 `revision` 让成长页在拍完一张后重算。
    @StateObject private var historyStore = ScoreHistoryStore()

    /// 当前打开的功能页。
    ///
    /// ★ 全 App **只有一个** `.sheet`，由它驱动。理由见 `body` 里那段注释：
    ///   同一个视图节点挂两个 `.sheet` 是 SwiftUI 有名的坑。
    @State private var activePage: AppPage?

    /// 「更多」菜单是否展开。
    @State private var showingPageMenu = false

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
                // ★ 这里必须走**完整**入口，不能只重起传感器：
                //   用户第一次拒绝权限后，唯一的补救路径是「设置 → 隐私 → 相机」，
                //   授权完切回来时 `onAppear` 不会重跑，只有 `scenePhase` 会变。
                //   只 refresh 不 start 的话，权限已经是 granted、界面却还停在
                //   「去设置里开启相机」那屏 —— 用户会以为授权没生效。
                //   `startIfAllowed()` 已由 `pipelineStarted` 做成幂等，重入安全。
                startIfAllowed()
            case .inactive, .background:
                motion.stop()
                // ★ 进后台要中断任务：`MissionEngine` 的超时是**按时间戳**算的，
                //   定时器一停，回来时第一 tick 会把这段静默一次性算成超时，
                //   用户看到的是一张突然消失的任务卡 + 一条莫名的超时记录。
                //   Android 侧对应 `onPause` 里的 `abortMission(PAUSE)`。
                //   `.inactive` 也一起处理（来电、下拉控制中心都会触发它）。
                mission.abort("PAUSE")
                // ★ 中断之后还要把定时器停掉 —— `abort` 只改状态机，不会停表。
                //   留着一个 400ms 的定时器在后台空转，既是耗电，
                //   也会让 `MissionEngine` 在用户看不见的时候继续派任务 / 记超时。
                mission.stop()
            default:
                // ★ 这里刻意用 `default` 而不是 `@unknown default`：
                //   `@unknown default` 只对**枚举**合法，对结构体是编译错误。
                //   本仓从未编译过 iOS，写 `default` 是两种声明形式下都成立的那个。
                break
            }
        }
        .onAppear(perform: startIfAllowed)
        // ★ 这条是「权限刚被授予」的兜底，不依赖 `scenePhase`。
        //
        //   用户的真实路径是：首次启动 → `onAppear` 时还是 `.notDetermined` 直接返回
        //   → 点「申请相机权限」→ 系统弹窗 → 授权 → `status` 变 `.authorized`
        //   → `body` 重算、界面切到取景层，但 **`onAppear` 不会重跑**
        //   （ZStack 的身份没变，只是内部 `if/else` 换了分支）。
        //   默认情况下系统权限弹窗会把 App 打成 `.inactive` 再回 `.active`，
        //   所以上面那条 `onChange(of: scenePhase)` 能兜住 —— 但那是**隐含契约**：
        //   iPad 分屏 / Stage Manager / 某些模拟器版本下弹窗不一定改变 scenePhase，
        //   一旦不改变，用户授权后就是永久黑屏且没有任何提示。
        //   直接盯 `status` 才是因果正确的那条线，`scenePhase` 那条退化成保险。
        .onChange(of: cameraPermission.status) { status in
            if status.allowsCapture { startIfAllowed() }
        }
        .onDisappear(perform: stopEverything)
        // ★ 「更多」用 `confirmationDialog` 而不是再开一个 sheet：
        //   根节点上已经有 `.sheet(item: $activePage)`，同节点再挂一个 `.sheet`
        //   是两个 `isPresented` 同时为真时只有一个生效、且顺序不保证的那个坑。
        //   `confirmationDialog` 与 `.sheet` 是**不同**的修饰符，各走各的呈现通道，
        //   不存在互相顶掉的问题；它是 action sheet 的语义，本来就适合「选一个去哪」。
        .confirmationDialog("要看什么", isPresented: $showingPageMenu, titleVisibility: .visible) {
            Button("这会儿该开什么") { activePage = .features }
            Button("录像方案") { activePage = .video }
            Button("参考分变化") { activePage = .growth }
            Button("技法图鉴") { activePage = .techniqueDex }
            // ★ 水印开关放在这里，是因为它只需要一个开关、不值得单开一页。
            //   标题带当前状态（`水印：开` / `水印：关`）—— 一个不显示状态的
            //   「切换水印」按钮，用户点完不知道现在是什么。
            //   不带勾选标记是 `confirmationDialog` 的限制（它不支持 Toggle 样式），
            //   所以状态只能写进标题里。
            Button(captureFlow.watermarkEnabled ? "水印：开" : "水印：关") {
                captureFlow.watermarkEnabled.toggle()
            }
            Button("取消", role: .cancel) {}
        }
        // ★ 全 App **唯一**的 `.sheet`。四个目的地共用一个，靠 `AppPage` 分派。
        //
        //   为什么收成一个：原来技法图鉴单独挂了 `.sheet(isPresented:)`，
        //   再加三个页面就变成「同一节点四个 sheet」—— SwiftUI 同时为真时
        //   只有一个生效，而且哪个生效不保证。收成 `.sheet(item:)` 之后
        //   「同时只能有一个页面」由类型本身保证（`activePage` 是单值）。
        .sheet(item: $activePage) { page in
            switch page {
            case .techniqueDex:
                TechniqueDexView()
            case .features:
                // ★ `profile` 在这里现取一次（而不是在 `body` 里预存到 `@State`）：
                //   模态内容是**惰性**求值的，只有真的要打开这一页时才付这次构造成本；
                //   而 `currentDeviceProfile()` 要读设备能力，不是轻活。
                FeatureView(
                    coordinator: coordinator,
                    profile: session.currentDeviceProfile(),
                    onClose: { activePage = nil }
                )
            case .video:
                VideoPlanView(
                    coordinator: coordinator,
                    profile: session.currentDeviceProfile(),
                    onClose: { activePage = nil }
                )
            case .growth:
                GrowthView(
                    store: historyStore,
                    onClose: { activePage = nil }
                )
            }
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
    /// 取景器上的全部 UI。
    ///
    /// ★ `horizontal` 内边距**按层给**，不再是整条 VStack 统一给：
    ///   任务卡（`MissionCardView` / `MissionScoreCardView`）自带 16pt 外边距
    ///   （它们的 `outerMargin`），如果在外面再套一层 16pt，卡片会被挤成 32pt 窄条，
    ///   在窄屏上「主句 + 角标」会换行成三条。
    private var chromeLayer: some View {
        VStack(spacing: 0) {
            topBar
                .padding(.horizontal, 16)
            Spacer(minLength: 0)
            missionLayer
            scorePanel
                .padding(.horizontal, 16)
            // ★ 快门条放在最下面（贴底部安全区），不放中间：
            //   ① 单手拇指的自然落点就在那里；② 放中间会正好挡住构图引导的中心十字，
            //   而中心十字是「主体该放哪」的参照物，挡住等于把引导废了。
            shutterBar
        }
        .padding(.top, 12)
        // ★ 纵向 padding 只留上边：底部要让快门贴到安全区边缘，
        //   由 `ShutterBar` 自己控制内边距，否则会被这里再压进去 12pt。
    }

    /// 快门条 + 它的模态与告警。
    ///
    /// ★ 为什么 `.sheet` / `.alert` 挂在**这个子视图**上，而不挂在根 `ZStack` 上：
    ///   根上已经挂了一个 `.sheet(item: $activePage)`（四个页面共用那一个）。
    ///   SwiftUI 在同一个视图节点上挂第二个 `.sheet` 是有名的坑 ——
    ///   两个呈现同时为真时只有一个生效，而且顺序不保证。
    ///   挂在不同节点上就没有这个问题，各自独立。
    private var shutterBar: some View {
        ShutterBar(
            isCapturing: captureFlow.isCapturing,
            thumbnail: captureFlow.lastCapturedImage,
            onShutter: capture,
            onReview: { showingReview = true }
        )
        .sheet(isPresented: $showingReview) {
            // ★ 用 `if let` 而不是 `?? UIImage()`：
            //   兜底造一张空图会让复盘卡显示一块黑矩形 —— 那是「坏掉」的样子，
            //   比什么都不显示更糟。理论上走不到这里（能点缩略图就说明图在），
            //   但兜底不该伪造内容。
            if let image = captureFlow.lastCapturedImage {
                PhotoReviewSheet(
                    image: image,
                    scoreText: reviewScoreText,
                    onDismiss: { showingReview = false }
                )
                // ★ 释放 12MP 位图必须放在 `onDisappear`，**不能**放在 `onDismiss` 里。
                //   `lastCapturedImage` 是 `@Published`，在关闭动画**进行中**置 nil 会触发
                //   一次 `objectWillChange` → 上面那个 `if let` 变 false → sheet 内容
                //   重算成空视图 → 用户看到「图先消失、卡片再收起」。
                //   `onDisappear` 在内容真正离场之后才跑，此时置空正好。
                //   不释放的话 12MP（约 48MB）会一直挂着，低端机上会被系统回收相机预览。
                .onDisappear { captureFlow.clearLastCaptured() }
            }
        }
        .alert(
            "这张没存进相册",
            isPresented: Binding(
                get: { captureFlow.lastError != nil },
                // ★ `set` 分支必须写：`.alert` 关闭时会把它置回 false，
                //   不在这里 `clearError()` 的话，`lastError` 一直非空，
                //   下次拍照即使成功，这条告警也会立刻又弹出来。
                set: { presented in if !presented { captureFlow.clearError() } }
            )
        ) {
            // ★ 权限被拒时给一条直达路径。只靠文案里那句「可在『设置 → 隐私与安全性
            //   → 照片』里改回来」，用户得自己翻四层菜单；相机权限那边有 `scenePhase`
            //   兜底可以自动恢复，相册这条没有等价机制，所以必须给一个跳转。
            //   按钮只在 `lastErrorNeedsSettings` 时出现 —— 渲染失败之类的错误
            //   跳设置页是误导。
            if captureFlow.lastErrorNeedsSettings {
                Button("去设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                    captureFlow.clearError()
                }
            }
            Button("好", role: .cancel) { captureFlow.clearError() }
        } message: {
            Text(captureFlow.lastError ?? "")
        }
    }

    /// 按快门。
    ///
    /// ★ 三件事的顺序有意义：
    ///   ① 先算文案 —— 必须在拍照前取，拍的过程里画面还在变；
    ///   ② 再中断任务卡 —— 与 Android 的 `abortMission(CAPTURE)` 对齐，
    ///      否则「拍照」这个动作本身会被任务引擎当成一次普通的画面变化继续判定；
    ///   ③ 最后发起拍照 —— `capture` 内部是异步的，回调回来才决定要不要开复盘卡。
    private func capture() {
        // 已经有一张在拍就直接丢弃这次点击：`CaptureFlow.capture` 会挡住重入
        // 且**不回调**，这里再挡一次只是为了让「连点两下」连文案都不重算。
        guard !captureFlow.isCapturing else { return }

        reviewScoreText = ScoreWording.full(coordinator.score)
        // ★ 与上面同一刻冻结，但这一份是给历史落盘用的**原始快照**。
        //   不能等到回调里再读 `coordinator.score`：拍照是异步的，
        //   回调回来时画面已经又走了几帧，记进历史的就成了「下一帧的分」。
        frozenScore = coordinator.score
        mission.abort("CAPTURE")

        captureFlow.capture(
            session: session,
            // ★ 传的是**已经算好的文案**。要不要印数字、什么时候印「--」，
            //   是产品的口径，判定点只能有一处（`ScoreWording`）；
            //   `CaptureFlow` 只负责把它烧到图上。
            scoreText: reviewScoreText
        ) { result in
            // 失败时 `captureFlow.lastError` 已经有中文说明了，
            // 告警由上面的 `.alert` 弹；这里不再重复提示。
            if case .success = result {
                // ★ 只有**真的存进相册**才记历史。存失败的照片不该进成长曲线 ——
                //   那条曲线回答的是「我练得有没有进步」，一张没留下来的照片
                //   不该算作一次练习。
                historyStore.record(frozenScore)

                // ★ 先确认拿到图再开复盘卡。
                //   `lastCapturedImage` 可能为 nil（水印关闭时解码失败那条路径，
                //   照片本身是成功进相册的）。原来直接置 `true`，于是 sheet 里
                //   `if let image = ...` 为 false → 内容变成空视图 →
                //   用户看到一张**没有「完成」按钮、只能下滑关闭**的白卡，
                //   以为 App 卡了。宁可不开，也不要开一张关不掉的空卡。
                if captureFlow.lastCapturedImage != nil {
                    showingReview = true
                }
            }
        }
    }

    /// 任务卡 / 成绩卡。两张卡的显示条件由 `MissionSnapshot` 算，这里不自己判。
    ///
    /// ★ 没有任务时**整卡不画**（`MissionCardView` 内部 `if let task`），
    ///   不显示「暂无任务」这类占位 —— 那会让用户以为功能坏了。
    @ViewBuilder
    private var missionLayer: some View {
        if mission.snapshot.showsTaskCard {
            MissionCardView(snapshot: mission.snapshot)
                .padding(.bottom, 10)
                // 卡片的入场/退场跟着状态机走，不用显式动画：
                // 状态每 400ms 才可能变一次，插值反而会让「到位」的绿闪得滞后。
        } else if mission.snapshot.showsScoreCard {
            MissionScoreCardView(snapshot: mission.snapshot) {
                // 「继续」＝ 主动收起成绩卡。走 `abort(USER)`：
                // 与 Android 的「点继续就结束这一条」同语义，且自带 3s 重新派发节流。
                mission.abort("USER")
            }
            .padding(.bottom, 10)
        }
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
                // 一个入口，四条去路（功能页 / 录像方案 / 成长 / 图鉴）。
                // 顶栏只有 40pt 见方的地方，塞四个图标会互相挤到认不出。
                showingPageMenu = true
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
                Text(ScoreWording.short(coordinator.score))
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
    /// 采集管线是否已经起过。
    ///
    /// ★ 为什么需要这个标志：`startIfAllowed()` 现在有两个调用方 ——
    ///   `onAppear` 和 `scenePhase == .active`，而 App 首次启动时**两者都会触发**。
    ///   不加标志的话 `CameraSession.start()` 会被调两次，而
    ///   `configureAndStart()` 里的 `installNotifications()` **没有去重**，
    ///   会装两套通知观察者（`AVCaptureSessionWasInterrupted` 之类的回调翻倍）。
    ///   `configure()` 有 `isConfigured` 挡着，`startRunning()` 也是无操作，
    ///   唯独通知这一处会真的叠加 —— 这种「只在一个点上不幂等」最容易被忽略。
    ///
    /// ★ 用它而**不是**读 `session.isSessionRunning`：那个值在 `startRunning()`
    ///   之后才置位，在权限对话框还没关掉的那段时间里是 `false`，
    ///   正好是「用户连点两次」最容易撞上的窗口。
    @State private var pipelineStarted = false

    private func startIfAllowed() {
        cameraPermission.refresh()
        guard cameraPermission.status.allowsCapture else { return }

        // 已经起过就只补传感器与任务定时器，绝不重入相机链路。
        //
        // ★ 这里要判各自的「已在跑」标志，不能无条件重起：
        //   `MotionProvider.start()` 第一句就是 `stop()`，而 `stop()` 会清掉
        //   `warmUpStartedAt` —— 每次走到这条分支都把 200ms 预热窗口从头算，
        //   接下来约 6 帧 `rollValid` 为 false（`roll = null`），水平维度白等一次。
        //   同理 `MissionModel.start()` 会先把 `snapshot` 打回 `.idle`，卡片会闪一下。
        //   两个现成的闸门（`motion.isAvailable` / `mission.isRunning`）就是为这个准备的。
        guard !pipelineStarted else {
            if !motion.isAvailable { startMotion() }
            if !mission.isRunning { startMission() }
            return
        }
        pipelineStarted = true
        session.start()
        coordinator.attach(to: session)
        startMotion()
        startMission()
    }

    /// 起任务卡的 400ms 驱动。
    ///
    /// ★ 为什么在**这里**起而不是在 `body` 里靠 `onChange` 起：
    ///   `MissionEngine` 的第 1 条任务是 `resume()` 之后下一个 tick 才派的，
    ///   越早起越早能派上；而且它依赖 `coordinator.attach` 之后才会有构图数据
    ///   （`IosMissionBridge` 的缓存由 `analyzeFrame` 填），排在 attach 之后正好。
    ///
    /// ★ 姿态读数从 `coordinator.guide` 取，**不另存一份**：
    ///   `updateMotion` 是姿态的唯一写入点，它在同一个方法里同时写
    ///   `rollDegrees` 与 `pitchAvailable`，所以这两个值一定同帧。
    ///   `pitchAvailable` 在这里当 `rollValid` 用 —— `ScoreCoordinator` 的注释
    ///   写明了「一个采样只有一个有效性标志，`pitchAvailable` 也用 `rollValid`」。
    private func startMission() {
        mission.start { [coordinator] in
            (
                roll: coordinator.guide.rollDegrees,
                valid: coordinator.guide.pitchAvailable
            )
        }
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
        // ★ 顺序：先停传感器 / 定时器，再断帧，最后停相机会话。
        //   反过来先 `detach` 的话，`gate` 已经作废，可传感器还在发 —— 那几十毫秒里
        //   每一帧都会走一次 `updateMotion`，白改一次 `@Published`，
        //   页面都关了还会让 SwiftUI 重算一次 body。
        motion.stop()
        mission.stop()
        coordinator.detach()
        session.stop()
        pipelineStarted = false
    }
}

// MARK: - 功能页路由

/// 全 App 唯一那个 `.sheet` 的目的地。
///
/// ★ 为什么收成一个枚举而不是四个 `Bool`：
///   四个 `@State var showingXxx` 允许多个同时为 `true`，而同一个节点上的
///   多个 `.sheet` 在那种情况下只有一个生效、且哪个生效不保证 ——
///   也就是说「代码看起来支持同时打开两页，实际行为随机」。
///   单值 `Optional` 让「同时只能有一个页面」由类型保证，编译器帮我们挡。
///
/// ★ 为什么不加 `.techniqueDex` 之外的更多项：
///   这个 App 只有四个能离开取景器的页面，多一项就说明该重新设计导航了。
private enum AppPage: String, Identifiable {
    case techniqueDex
    case features
    case video
    case growth

    /// `Identifiable` 要求。用 `rawValue` 而不是自增 `UUID`：
    /// sheet 的身份必须**稳定**，否则同一个页面重开会被当成另一个身份、重新走入场动画。
    var id: String { rawValue }
}

// MARK: - 参考分文案

/// 「参考分」这一句话的**唯一**出处。
///
/// ★ 为什么要单独抽出来：这个 App 的核心产出就是这一个数字，而它有一套很硬的措辞规则 ——
///   叫「参考分」不叫「得分 / 评分 / 评测」，任一维度未测时**必须显示 `--` 而不是数字**。
///   原来这句文案在 `scorePanel` 里手写、在成绩卡里又手写一遍、水印还要再写一遍，
///   三处只要有一处写错口径（比如「未测时印 0」），就是「拿半个数据骗人」。
///
/// ★ 为什么不放进 `Palette` 之类的地方：那不是配色，是产品措辞。
///   放在 `ContentView.swift` 里是因为目前只有界面在用；将来若要给水印/分享复用，
///   把它提到独立文件即可，改动只是换个文件位置。
private enum ScoreWording {

    /// 未测全时的占位。全仓只此一处，别再手写 `"参考分 --"`。
    static let unmeasured = "参考分 --"

    /// 面板用：`参考分 82` / `参考分 --`。满分数值（`/ 100`）由调用方另外渲染，
    /// 因为它有自己的字号与颜色。
    static func short(_ snapshot: ScoreSnapshot) -> String {
        snapshot.hasTotal ? "参考分 \(snapshot.total)" : unmeasured
    }

    /// 烧进照片 / 分享文本用：`参考分 82 / 100` / `参考分 --`。
    ///
    /// ★ 已测全时走 Kotlin 的 `IosBridge.referenceScoreText`，不自己拼字符串 ——
    ///   那是跨端唯一的口径来源，Android 的水印文案也是同一句。
    ///   未测全时**不调它**：它的签名是 `(score: Double)`，只能表达「有一个分数」，
    ///   传 0 就会印出「参考分 0 / 100」，正好违反上面那条规则。
    static func full(_ snapshot: ScoreSnapshot) -> String {
        snapshot.hasTotal
            ? SharedKernelBridge.referenceScoreText(Double(snapshot.total))
            : unmeasured
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
