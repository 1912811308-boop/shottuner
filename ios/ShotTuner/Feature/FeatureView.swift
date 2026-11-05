// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Feature/FeatureView.swift —— 功能页：「这会儿该开什么」。
//
// ★ 本文件只做渲染，一行建议逻辑都不写。
//   「该开手持夜景还是该关掉 HDR」「这台机器到底有没有专业模式」这些判定，
//   全在 commonMain 的 `FeatureAdvisor`（`advise(...)` 实时建议 +
//   `capabilitySheet(...)` 设备能力清单），Android 侧一直读的是同一份。
//   iOS 缺的从来不是逻辑，是「门面 + 界面」：门面是 `IosFeatureBridge`
//   （见 shared/src/iosMain/kotlin/com/shottuner/app/ios/IosFeatureBridge.kt 文件头），
//   界面就是这个文件。
//   一旦有人在 Swift 里自己算一条建议，两端就再也不可能对齐 —— 而且走样了
//   **不会有人发现**（表现只是建议内容变得莫名其妙：不崩溃、不报错、不报警）。
//   这与 `ScoreCoordinator` 文件头「评分不写在 Swift 里」是同一条纪律。
//
// ★ 与 Android 逐字对齐，不自己发明文案
//   页面的结构、文案、条数上限、空态措辞、三种语气的标记与配色，全部照抄
//   `app/src/main/java/com/shottuner/app/MainActivity.kt` 的
//   `renderFeatures()` / `featureCard()`（约 1329–1392 行）。
//   理由：两端是同一个产品的两个外壳，用户两平台都用时，同一帧画面在两台手机上
//   给出的建议措辞不同，会被当成「这版没做好」。改文案只改 commonMain 一处。
//
// ★ 本文件**已知、有意保留**的两处与 Android 的差异（都是「iOS 侧还没接那个引擎」）
//   1) 标题后缀「· AI 看到 xxx」：Android 拼的是 `ai.tags.take(3)`，来自
//      `VisionSceneDetector`。iOS 侧的 Vision 场景检测还没接线，Swift 手里
//      根本没有这份 tags。**所以 iOS 侧不拼这个后缀** —— 注意不是「拼一个空串」，
//      是不拼；缺数据的正确表现是「少一句话」，不是「多一句『AI 看到 』」。
//      等 Vision 接线后，应该把 tags 从 Kotlin 侧下发（与建议同一批数据），
//      而不是在 Swift 里重算一份场景识别 —— 重算必然与建议用的场景不同源。
//   2) `needsTripod` **由调用方传入**（`ParamAdvisor` 从 `ParamRecommender` 拿到）。
//      这条曾经是「恒为 false」的已知缺失 —— iOS 侧没接参数建议引擎，Swift 手里
//      没有这个布尔，代价是「1b. 夜景长曝：别用手指戳快门」这条建议永远不出现。
//      现在 `IosParamBridge` 补上了那个引擎，`Recommendation.needsTripod` 有了来源，
//      所以它变成**输入参数**而不是常量。
//      ⇒ 两条禁令仍然有效，只是理由变了：
//        · **不许**在拿不到真实值时猜 `true` —— 明亮场景里给一条「按快门别用手指戳」
//          是噪音，比缺失更糟。拿不到就传 `false`（`ParamAdvice.none.needsTripod` 就是 false）。
//        · **不许**在这个文件里自己重算「是否需要三脚架」—— 那是把
//          `RecommendationEngine` 的一半逻辑搬进 Swift，它会和 Kotlin 侧慢慢分叉，
//          而且分叉后不会报错。
//
// ★ 跨语言规则（详见 SharedKernelBridge.swift 文件头）
//   · Kotlin `object` → Swift `Xxx.shared`，**不是** `Xxx()`
//   · Kotlin `Int` → Swift `Int32`（`Int32(i)` 之类转换不能省），`Long` → `Int64`，
//     `Boolean` → Swift `Bool`（★ Swift 没有 `Boolean` 这个类型，那是
//     CoreFoundation 的 `UInt8` 别名，写错就是编译错误）
//   · 边界只传标量 / 索引，不传 `List`
//   · `IosAdviceDto.kindText` 是**大写关键串** `DO` / `AVOID` / `KNOW`，
//     刻意**不**在 Swift 里再定义一套枚举去映射：Kotlin 侧新增语气时，
//     那份映射表会静默漏掉新值。见 `look(for:)`。
//
// ★ 只引 SwiftUI + Shared，不引 Combine
//   本文件**不需要**自己建 `ObservableObject`：观察的对象由调用方传进来
//   （`@ObservedObject var coordinator`），而 `ObservedObject` 是 SwiftUI 提供的，
//   `import SwiftUI` 就够。本仓刚因为漏 `import Combine` 让整个类型构造失败过一次，
//   这里的规避办法不是「补上 import」，而是「压根不碰 `ObservableObject` / `@Published`」。
//
// ★ 零网络：本页不用任何联网 API（全仓承诺，见 tools/ci/check-no-network.sh）。

import SwiftUI
import Shared

// MARK: - 本地行模型

/// 一条建议在列表里的展示数据。
///
/// ★ 为什么要有这个 Swift 值类型，而不是直接拿 `IosAdviceDto` 去 `ForEach`：
///   ① `body` 会重算很多次，每一次 `dto.title` 都是一次**跨语言属性读取**。
///      拿 DTO 直接绑等于把「用户滑动一下」变成几百次语言边界穿越。
///   ② Kotlin 对象不是 Swift 值类型，`ForEach` 的 diff 会依赖引用相等性，
///      而门面每次 `refresh` 都会**整批换掉**列表里的对象 —— 视图树会被整体重建。
///   ⇒ 刷新时一次性搬成本地数组，之后 `body` 只碰 Swift 内存，一次桥都不调。
///
/// ★ 为什么不搬 `priority`：列表在 Kotlin 侧已经 `sortedByDescending { it.priority }`
///   排好了（`FeatureAdvisor.advise` 末尾），Android 也只是 `take(limit)`。
///   Swift 这边再排一次，等于把「排序口径」复制成两份，将来 Kotlin 改排序
///   （比如同优先级按场景再排）就会出现「Android 顺序对、iOS 顺序错」而无人察觉。
///   纯粹的展示字段搬过来，排序权力留给唯一的产地。`kindText` 是例外 ——
///   它是配色 / 标记的输入，属于渲染必需，必须搬。
private struct AdviceRow: Identifiable {

    /// 稳定标识。用下标而不是 title：title 可能重复（同一条规则在不同场景下措辞相同）。
    let id: Int

    /// `DO` / `AVOID` / `KNOW`，以及（防御）任何将来新增的、本文件不认识的值。
    let kindText: String

    /// 卡片主句，动作口吻，如「开手持夜景」。
    let title: String

    /// 为什么，带可复算的数据。
    let reason: String

    /// 去哪儿拨，如「相机 → 更多 → 夜景」。
    let path: String
}

// MARK: - 功能页

/// 功能页：「这台机器能玩什么」/「这会儿该开什么」。
///
/// 调用方（`ContentView`）负责：把这个页面放进 `.sheet` 或取景层上的按钮里、
/// 在关闭时把 `onClose` 接回自己的 `@State`。本视图自己**不持有**任何
/// 「是否显示」的状态 —— 显示与否是调用方的决定，这里只画内容。
///
/// ★ 为什么整个 struct 标 `@MainActor`（这不是多余的装饰，去掉就是编译错）：
///   观察对象 `ScoreCoordinator` 本身是 `@MainActor` 的，而本文件里
///   `rebuild()` 要读它的 `lastAnalysisAt` / `sceneName`。
///   `rebuild()` 是**普通实例方法**（不是 `body`，也拿不到 `body` 的隐式隔离），
///   在非隔离上下文里读主 actor 隔离的属性，Swift 5.9 会报
///   "Main actor-isolated property 'sceneName' can not be referenced from a
///   nonisolated context"。
///   把隔离提到 struct 上，`rebuild()`、`card(for:)`、静态常量全都跟着落在主 actor，
///   一次解决；顺带保证 `onAppear` / `onChange` 这些回调里的写状态也在主线程。
///   这与 `ContentView.swift`（同一句注释）和 `VideoPlanView.swift` 是同一个套路
///   —— 本仓三个页面共用「协调器是 @MainActor」这一个事实，处理方式必须一致。
@MainActor
struct FeatureView: View {

    /// 评分协调器。**只读两个东西**：`lastAnalysisAt`（有没有过读数）
    /// 与 `sceneName`（当前场景名，原样透传给门面）。
    ///
    /// ★ 用 `@ObservedObject` 而不是 `@StateObject`：这个协调器的生命周期
    ///   由 `ContentView` 持有（整棵取景页共用一个）。在这里用 `@StateObject`
    ///   会**新建第二个实例**，于是「有没有读数」「当前场景」全变成另一条空链路，
    ///   页面永远停在「这台机器能玩什么」—— 而且不报错，最难查的那种。
    @ObservedObject var coordinator: ScoreCoordinator

    /// 设备档案（Kotlin `DeviceProfile`，调用方从
    /// `CameraSession.currentDeviceProfile()` 现取现传）。
    ///
    /// ★ 为什么不在本文件里自己 `currentDeviceProfile()`：
    ///   档案的探测要开 `AVCaptureDevice` / `DiscoverySession`，是重活；
    ///   而本页 `onAppear` / 每次读数变化都会重建列表。让调用方传进来，
    ///   探测频率就由调用方一次性决定（Android 侧也是 Activity 持有一份 profile）。
    ///   本视图把它当**只读、不可变**的输入用，不做任何字段判断。
    let profile: DeviceProfile

    /// 当前是否需要三脚架。
    ///
    /// ★ 来源是 `ParamAdvisor` 发布的那份建议（`ParamAdvice.needsTripod`），
    ///   由 `Recommendation.needsTripod` 逐字搬过来 —— 与 Android 完全同源。
    ///   拿不到读数时调用方传 `false`：少一条建议，好过多一条用不上的建议。
    ///
    /// ★ 为什么不在这里自己算：那是把 `RecommendationEngine` 的一半逻辑搬进 Swift，
    ///   会和 Kotlin 侧慢慢分叉，而且分叉后不会报错。
    let needsTripod: Bool

    /// 关闭回调。调用方在这里把自己的 sheet 状态置回 `false`。
    var onClose: () -> Void

    /// 当前列表（本地值类型数组）。
    ///
    /// ★ 为什么是 `@State` 而不是每次在 `body` 里现算：
    ///   `body` 会因为滚动、旋转、主题变化、父视图重绘等原因反复求值，
    ///   而列表的每次重建都是 1 次刷新 + 1 次取长度 + 最多 4 次逐条读取
    ///   （全是跨语言调用）。放在 `body` 里就是把「滚动」变成「反复跑建议引擎」。
    ///   ⇒ 只在 `onAppear` 与 `lastAnalysisAt` 变化（真的有新读数）时重算。
    @State private var rows: [AdviceRow] = []

    /// 当前这一屏是哪一支：`true` = 有读数（实时建议），`false` = 没读数（能力清单）。
    ///
    /// ★ 为什么不直接在 `body` 里读 `coordinator.lastAnalysisAt != nil`：
    ///   那会导致「标题」和「列表」来自两个不同时刻 —— 读数刚好在两者求值之间
    ///   落地时，标题写「这会儿该开什么」、列表还是能力清单（或者反过来）。
    ///   标题必须与它下面那批卡片**同源**，所以和 `rows` 一起在 `rebuild()` 里落定。
    @State private var hasReading: Bool = false

    /// 实时建议最多 4 条。
    ///
    /// ★ 照抄 Android 的理由（原文）：再多就成了说明书，用户不会读。
    ///   一屏能扫完的上限是 4 张卡，第 5 张开始用户会直接划过去，
    ///   于是「优先级最高的那条」和「第十条」在他眼里没有区别。
    private static let liveLimit: Int = 4

    /// 首次能力清单固定 6 条。
    ///
    /// ★ 照抄 Android 的理由（原文）：保证两条专业模式 KNOW 卡
    ///   （长按锁 AE/AF、RAW+）可见。`capabilitySheet` 恰好在这个顺序上把
    ///   它们排在最后两位（优先级 55 / 52），取前 6 条正好都留得住；
    ///   任何小于 6 的上限都会把它们挤掉，而这两条恰恰是最容易「不知道就白拍」的。
    private static let sheetLimit: Int = 6

    var body: some View {
        // ★ 用 `NavigationView` 而不是 `NavigationStack`：
        //   与 `TechniqueDexView.swift` 保持同一套容器（那里的注释写明理由 ——
        //   `NavigationView` 是 iOS 13+，`NavigationStack` 要 iOS 16+，
        //   用前者不会在低部署目标上编译不过）。本工程当前 deploymentTarget 是
        //   16.0，两者都能编，但同一个 App 里两种导航容器各写一半，
        //   将来调整导航栏行为就得记住「哪些页是哪种」。
        NavigationView {
            ScrollView {
                // ★ LazyVStack 而不是 VStack：能力清单最多 6 张卡，实时建议最多 4 张 ——
                //   今天其实都放得下。用 Lazy 是为了将来放开上限时不用改结构，
                //   而且它不会改变这里任何布局行为（`spacing: 6` 对应 Android 的
                //   `bottomMargin = dp(6)`）。
                LazyVStack(alignment: .leading, spacing: 6) {
                    if rows.isEmpty {
                        // ★ 空列表也要给一张卡（照抄 Android：`list.isEmpty()` 时
                        //   走 `featureCard(null)`）。理由：整页全黑会让用户以为
                        //   「这页坏了」；一句「等一秒再看」明确告诉他是在等数据。
                        //
                        // ★ 注意：这句话在「没有读数」那一支里读起来略怪
                        //   （那时并没有画面可等）。Android 是同一个分支、同一句话，
                        //   iOS 侧**刻意不改** —— 单方面「改进」文案的结果就是两端不一致，
                        //   而这条差异只有在真机上第一次冷启动才看得见。
                        //   要改就两端一起改，改 commonMain 的措辞或两处同时改。
                        emptyCard
                    } else {
                        ForEach(rows) { row in
                            card(for: row)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                // ★ 底色用 `Color.black`，不加新的 `Palette` 成员：
                //   `Palette` 只装**前景色**（accent / warn / overlayText / hairline…），
                //   背景色由页面自己给。在 `Palette` 里加一个只用一次的背景色，
                //   等于把「配色只有一个真出处」这条破掉（`PhotoReviewSheet` 同做法）。
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black)
            }
            .background(Color.black)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // 关闭按钮放在右侧：这一页是纯阅读页，没有「确认」语义，
                // 放 `.navigationBarTrailing` 是最不容易被误读的位置。
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") {
                        onClose()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        // ★ 强制深色外观：`Palette` 里的文字色是给深底设计的浅色
        //   （accent 是浅绿、overlayText 是纯白），跟随系统切到浅色外观时
        //   会变成浅字浅底，整页糊成一片。`PhotoReviewSheet` / `MissionCardView`
        //   也是这么钉的。
        .preferredColorScheme(.dark)
        // ★ 进页先算一次：`@State` 初值是空的，不算就是一张空白页。
        .onAppear {
            rebuild()
        }
        // ★ 有新读数就重算。`lastAnalysisAt` 由 `ScoreCoordinator.land(...)` 在
        //   每一帧成功分析后更新（已被 `ScoreGate` 节流到 ~10fps 以下），
        //   所以这里跟着它刷新，等于「实时建议跟着画面走」，与 Android 一致。
        //
        // ★ 用旧的单参 `onChange(of:perform:)` 形态（iOS 14+），
        //   与 `ContentView.swift` 里那两处一致；iOS 17 的新双参形态在
        //   deploymentTarget 16.0 上不可用。
        //
        // ★ 为什么监听「时间戳」而不是监听 `score` / `histogram`：
        //   建议引擎读的是 Kotlin 侧 `IosFrameCache.latest`（那一帧的真实统计量），
        //   它和 `lastAnalysisAt` 在同一个 `land(...)` 里落地。用时间戳当触发器，
        //   语义就是「有新的一帧被分析过了」，比盯某个派生值更贴近因果。
        //   代价：每次落帧都会重算一遍建议（1 + 1 + 最多 4 次跨语言调用）。
        //   这是这个页面存在的意义本身（否则就退化成「进页那一刻的快照」），
        //   而且全是纯计算、无 IO，在 10fps 量级上不构成压力。
        .onChange(of: coordinator.lastAnalysisAt) { _ in
            rebuild()
        }
    }

    // MARK: - 标题

    /// 页面标题。
    ///
    /// ★ 两支的文案与 Android 一字不差。标题后缀「· AI 看到 xxx」在 iOS 侧
    ///   **故意没有**，见文件头第 1 条已知差异。
    private var title: String {
        hasReading ? "这会儿该开什么" : "这台机器能玩什么"
    }

    // MARK: - 重算（唯一的跨语言调用入口）

    /// 重算列表。**本文件所有跨语言调用都在这里**，`body` 一次都不调。
    ///
    /// ★ 判两支的判据：`coordinator.lastAnalysisAt != nil`。
    ///   等价于 Android 的 `lastStats != null`（本地快照是否存在）。
    ///   · 没有读数 → 能力清单（`capabilityRefresh`）：纯设备事实，不吃画面统计量。
    ///     这是「第一次打开该看到什么」的答案 —— 一台陌生机器能玩什么。
    ///   · 有读数 → 实时建议（`adviseRefresh`）：读 Kotlin 侧缓存的那一帧真实统计量。
    ///
    /// ★ 为什么两支可以放心地「只调其中一个 refresh」：
    ///   门面里 `advice` 与 `capabilities` 是**两个独立字段**（见
    ///   IosFeatureBridge 文件头「两个列表各自独立持有」），所以交替刷新它们
    ///   不会互相覆盖。若两者共用一个字段，这里就必须每次两支都刷 ——
    ///   否则会看到「刚刷出来的清单突然变成另一批内容」，且不报任何错。
    private func rebuild() {
        let bridge = IosFeatureBridge.shared
        let reading = coordinator.lastAnalysisAt != nil
        hasReading = reading

        if reading {
            // ★ `sceneName` 原样透传：门面内部按 `SceneType` 枚举名精确匹配，
            //   未知 / 空串一律退到 `GENERAL`（与 `IosBridge.analyzeFrame` 同一口径）。
            //   Swift 侧**不要**在这里做 trim / 大小写规整 —— 两处口径一旦不同，
            //   同一帧画面的「建议说夜景、评分说通用」就会同时出现，而两边都不认为自己错。
            //
            // ★ `needsTripod` 直接透传调用方给的值 —— 它来自
            //   `Recommendation.needsTripod`（经 `ParamAdvisor` 搬过来），
            //   与 Android 完全同源。见文件头第 2 条。
            bridge.adviseRefresh(
                profile: profile,
                sceneName: coordinator.sceneName,
                needsTripod: needsTripod
            )
        } else {
            bridge.capabilityRefresh(profile: profile)
        }

        // ★ Kotlin 的 `Int` 在 Swift 里是 `Int32`，所以取长度后先转成 `Int`
        //   再参与 Swift 侧的算术（`min` 对 `Int` / `Int32` 不通用，
        //   而两者**不隐式转换** —— 少一次转换就是编译错误）。
        let total = Int(reading ? bridge.adviseCount() : bridge.capabilityCount())
        let limit = reading ? Self.liveLimit : Self.sheetLimit
        let take = min(total, limit)

        var built: [AdviceRow] = []
        built.reserveCapacity(take)
        for i in 0..<take {
            // 下标往门面传的时候必须显式转 `Int32`（门面签名是 `adviseAt(index: Int32)`），
            // 而 Swift 的 `Int` 与 `Int32` **不隐式转换**。
            let index = Int32(i)
            let dto = reading ? bridge.adviseAt(index: index) : bridge.capabilityAt(index: index)

            // ★ 门面明写「越界返回 nil」（跨语言边界上抛异常最难定位，
            //   一个越界的列表下标不该让整页崩掉）。这里就按「可能为 nil」写。
            //
            // ★ 这个 nil 不是理论上的：`adviseCount()` / `capabilityCount()` 与
            //   后面的 `adviseAt(index:)` / `capabilityAt(index:)` 是**两次独立的
            //   跨语言调用**，两次之间若另一处刷新了同一个列表（比如切场景时
            //   在别的入口又调了一次 `adviseRefresh`），长度和内容就可能不同源。
            //   遇到这种竞态，正确表现是「少一条」而不是「崩掉」：
            //   用户下一次落帧（~100ms 后）就会看到完整列表。
            guard let dto = dto else {
                continue
            }

            built.append(
                AdviceRow(
                    id: i,
                    kindText: dto.kindText,
                    title: dto.title,
                    reason: dto.reason,
                    path: dto.path
                )
            )
        }
        rows = built
    }

    // MARK: - 卡片

    /// 一张建议卡：三行。
    ///
    /// 逐行对齐 Android `featureCard()`：
    /// | 行 | 内容 | Android 字号 / 颜色 | 本文件 |
    /// |---|---|---|---|
    /// | ① | `标记 · 标题`，加粗 | 13sp / `st_accent` / `st_warn` / `st_text_warm` | 13pt / `Palette.accent` / `warn` / `warnStrong` |
    /// | ② | `reason` | 11sp / `st_text_body` | 11pt / `Palette.overlayTextSecondary` |
    /// | ③ | `▸ path` | 11sp / `st_text_dim` | 11pt / `Palette.levelIdle` |
    ///
    /// ★ 三行的字号是 13 / 11 / 11 —— 与 Android 完全一致，**不要**自己拉开层级。
    ///   ② 与 ③ 同字号、不同亮度（`overlayTextSecondary` 是 70% 白，
    ///   `levelIdle` 是 43% 白）就已经把「正文 / 去哪拨」分开两层了；
    ///   再叠一层字号差，第三行在小屏上会掉到读不清。
    private func card(for row: AdviceRow) -> some View {
        let look = Self.look(for: row.kindText)
        return VStack(alignment: .leading, spacing: 4) {
            Text("\(look.mark) · \(row.title)")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(look.color)
                // 建议标题可能长到两行（如「弱光别切 120mm 长焦」+ 机型前缀）。
                // 允许换行但**不截断**：截断掉的往往正是「哪颗镜头 / 哪个模式」，
                // 那半句没了这条建议就等于没说。数据来自 Kotlin，本来就不该被
                // Swift 侧悄悄裁掉。
                .fixedSize(horizontal: false, vertical: true)

            Text(row.reason)
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
                .fixedSize(horizontal: false, vertical: true)

            Text("▸ \(row.path)")
                .font(.system(size: 11))
                .foregroundStyle(Palette.levelIdle)
                .fixedSize(horizontal: false, vertical: true)
        }
        .adviceCardChrome()
    }

    /// 空态卡。
    ///
    /// ★ 文案原样照抄 Android：「画面信息还不足，等一秒再看」。
    ///   这里**不加**「下拉刷新」「点一下重试」之类——本页没有手动刷新入口，
    ///   重算完全由落帧触发（见 `body` 里的 `onChange`）。写一个不存在的交互，
    ///   比什么都不写更糟。
    private var emptyCard: some View {
        Text("画面信息还不足，等一秒再看")
            .font(.system(size: 12))
            .foregroundStyle(Palette.levelIdle)
            .adviceCardChrome()
    }

    // MARK: - 语气 → 标记 / 颜色

    /// `kindText` → 第一行的标记与颜色。
    ///
    /// | `kindText` | 标记 | 颜色 | 对应 Android |
    /// |---|---|---|---|
    /// | `DO` | 该用 | `Palette.accent` | `st_accent` |
    /// | `AVOID` | 别用 | `Palette.warn` | `st_warn` |
    /// | `KNOW` | 知道 | `Palette.warnStrong` | `st_text_warm` |
    ///
    /// ★ 为什么用 `switch` 比较三个**字面量**，而不是定义一个 Swift 枚举去映射：
    ///   枚举映射表一旦和 Kotlin 不同步，就是**静默的「建议语气画错」**——
    ///   比如 Kotlin 新增 `TIP`，映射表没跟上，那条建议就落到某个默认分支上，
    ///   颜色看着「也还行」，没人会发现。字面量比较至少让「没匹配上」这件事
    ///   有一个明确的落点（下面的 `default`）。
    ///
    /// ★ `default` 分支是**防御，不是遗忘**：`kindText` 不是这三个值之一时
    ///   （Kotlin 侧将来新增语气 / 门面口径改了 / 拼写变了），本文件
    ///   **不崩溃、不显示空白**：落到 `KNOW` 的样式（「知道」+ `warnStrong`），
    ///   而卡片正文（`title` / `reason` / `path`）**原样照显**。
    ///   理由：一条语气画错但内容完整的建议，用户仍能照做；
    ///   一条空卡片则等于这条建议彻底丢了。宁可「标签偏保守」，不可「内容消失」。
    ///   注意这里**不**把 `kindText` 原文当标记显示 —— 那是 `DO` / `AVOID` 这类
    ///   英文枚举名，直接印到中文界面上是另一种 bug。
    private static func look(for kindText: String) -> (mark: String, color: Color) {
        switch kindText {
        case "DO":
            return (mark: "该用", color: Palette.accent)
        case "AVOID":
            return (mark: "别用", color: Palette.warn)
        default:
            // 含 "KNOW" 与一切未知值 —— 见上方注释。
            return (mark: "知道", color: Palette.warnStrong)
        }
    }
}

// MARK: - 卡片外壳

/// 卡片外壳：圆角 14 的极淡白底 + 发丝线描边。
///
/// ★ 为什么是 `private extension View` 而不是 `FeatureView` 的一个
///   `func cardChrome<Content: View>(@ViewBuilder ...) -> some View`：
///   两者效果相同，但泛型函数上挂不透明返回类型（`some View`）是本文件里
///   唯一需要「读编译器脸色」的写法 —— 而 Windows 上编不了 Swift，
///   写了也没人能在合入前验一遍。非泛型方法上的 `some View` 是零疑问的，
///   所以选它。附带好处：`emptyCard` 与 `card(for:)` 用同一条链，
///   「空态卡」和「建议卡」不可能长出两套圆角 / 描边。
///
/// ★ 为什么用「5% 白底 + `hairline` 描边」而不是 `Palette.lineShadow` 当底色：
///   `lineShadow` 是 `0x8C000000`（55% 黑），压在纯黑页面上**等于不可见**，
///   卡片会失去边界、三张卡糊成一段文字。`overlayText.opacity(...)` 用的是同一个
///   允许成员（纯白）加透明度，不需要往 `Palette` 里新增成员 ——
///   `Palette` 只装两端对齐的前景色，为一张卡加一个只用一次的背景色，
///   会把「配色只有一个真出处」这条破掉。
///
/// ★ 14 的圆角与 Android `bg_card` 一致；`style: .continuous` 让圆角在小尺寸
///   卡片上不出现「方肩」。内边距 12 / 10 也照抄 Android 的 `dp(12), dp(10)`。
private extension View {

    func adviceCardChrome() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Palette.overlayText.opacity(0.05))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Palette.hairline, lineWidth: 1)
            )
    }
}
