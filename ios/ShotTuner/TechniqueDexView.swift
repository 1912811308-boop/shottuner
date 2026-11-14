// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/TechniqueDexView.swift —— 技法图鉴（iOS 侧）。
//
// ★ 为什么这个页面能「今晚」就上线：
//   83 条拍照技巧**早已在 commonMain**（PhotographyBook.all），Android 的技法图鉴
//   一直读的是它。iOS 侧缺的从来不是内容，只是一个能显示它的界面 +
//   一条把 List<Technique> 拍平成标量的跨语言门面（TechniqueDexBridge）。
//   ⇒ 本文件只做渲染，不写任何业务逻辑，也不复制任何技巧文案。
//
// ★ 跨语言规则（见 SharedKernelBridge.swift 文件头）：
//   Kotlin `object` → Swift `Xxx.shared`；Int → Int32；边界只传标量。
//
// ★ 口径铁律：只说「参考分」，不说「正确/错误」「专业评测」；参数用「照拨」「建议值」。
//   页面上的文案全部来自技巧库本身，不改内容、不加评价。
//
// ★ 解锁进度（G3）：顶部「已解锁 N / 83 + 进度条」+ 列表里已解锁条目的绿色「已解锁」标记，
//   与 Android 的 `MainActivity.renderDexProgress` / `skillCard` 对齐。
//   数字全部来自 `DexUnlockStore`（它再问 `TechniqueDexBridge`），
//   **判定在 common 的 `shouldUnlock`**，本文件一行判断都不写 ——
//   连「N / 83」里的 83 也是 `TechniqueDexBridge.count()` 给的，不写死。
//
// ★ 关于空状态（一条都没解锁时）：
//   仍然显示「已解锁 0 / 83」+ 一条空的进度条，下面多一行灰字说明解锁条件。
//   ★ 为什么不把整块进度藏掉（像成长卡的 `hasGrowth == false` 那样）：
//     成长卡藏起来是因为「没有可比的两张」时它给不出任何信息；
//     而「0 / 83」本身就是信息 —— 它告诉用户这是一本**还没开始的册子**，
//     并且顺带解释了怎么开始。藏起来会让用户以为这个页面没有进度这回事，
//     等他拍到第一条解锁时，界面会突然多出一整块东西。

import SwiftUI
import Shared

/// 一条技法在列表里的展示数据（本地值类型，避免 body 重算时反复跨语言）。
private struct TechniqueRow: Identifiable {

    /// 在 Kotlin 图鉴里的下标（详情页 `TechniqueDetailView(index:)` 要用）。
    let index: Int

    /// 技法 id。解锁状态按它记（见 `DexUnlockStore`），也是列表的稳定身份。
    ///
    /// ★ 用 id 而不是下标做身份：知识库将来在中间插一条，下标会整体位移，
    ///   `ForEach` 就会把「第 12 行」当成同一行重绘（绿标闪错位置）。
    ///   id 对插入、删除、重排全部免疫（Android 的 `DexStore` 用的是同一个理由）。
    let dexId: String

    let title: String
    let category: String
    let oneLiner: String

    /// `Identifiable` 要的稳定身份。
    var id: String { dexId }
}

/// 技法图鉴：与 Android 侧同源的 83 条拍照技巧。
///
/// ★ 为什么整个 struct 标 `@MainActor`（理由与 `GrowthView` / `ContentView` 顶部一致）：
///   `DexUnlockStore` 是 `@MainActor` 类，而本文件的 `progressHeader` / `progressBar` /
///   `unlockedBadge` 都是**普通成员**，不是 `body`。不标就是非隔离上下文，
///   读 `dexStore.unlockedCount` 会编译失败
///   （"Main actor-isolated property can not be referenced from a nonisolated context"）。
@MainActor
struct TechniqueDexView: View {

    /// 一次性读完的行数据。body 只碰本地数组，不再逐行跨语言取值。
    private let rows: [TechniqueRow]

    /// 解锁状态（落盘 + 判定出口）。
    ///
    /// ★ `@StateObject` 而不是 `@State`：它是 `ObservableObject`，
    ///   解锁之后会发 `unlockedCount` / `unlockedIds` 让本页重算。
    /// ★ 本页**只读进度，不写进度**：解锁的唯一入口是 `DexUnlockStore.recordCapture(_:)`，
    ///   调用点在 `ContentView.capture()` 的成功回调里（与 `historyStore.record` 同一处）。
    ///   **不要在本页加「打开页面时补结算一次」之类的副作用** —— 理由见
    ///   `DexUnlockStore.init()` 的注释：那会在「点开册子」时花掉当天的额度，
    ///   把「我这一张拍得好」与解锁之间的因果切断。
    /// ★ 实例目前由本页自己创建（`ContentView` 的调用点是 `TechniqueDexView()`）；
    ///   将来若改成从外层传入，只需换掉这一行的初始化方式，本页其余代码不用动。
    @StateObject private var dexStore = DexUnlockStore()

    init() {
        // ★ 一次性读完，之后 body 只碰本地数组。
        //   Kotlin 的 Int 映射到 Swift 是 Int32，所以要显式转换。
        let total = Int(TechniqueDexBridge.shared.count())
        var built: [TechniqueRow] = []
        built.reserveCapacity(total)
        for i in 0..<total {
            let index = Int32(i)
            built.append(
                TechniqueRow(
                    index: i,
                    dexId: TechniqueDexBridge.shared.idAt(index: index),
                    title: TechniqueDexBridge.shared.titleAt(index: index),
                    category: TechniqueDexBridge.shared.categoryLabelAt(index: index),
                    oneLiner: TechniqueDexBridge.shared.oneLinerAt(index: index)
                )
            )
        }
        rows = built
    }

    var body: some View {
        // 用 NavigationView（iOS 13+）而不是 NavigationStack（iOS 16+），
        // 避免在低部署目标上编译不过。
        NavigationView {
            List {
                Section {
                    progressHeader
                        // 顶部进度块不画行背景、不加分隔线：它是页头，不是一条内容。
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                Section {
                    ForEach(rows) { row in
                        NavigationLink(destination: TechniqueDetailView(index: Int32(row.index))) {
                            rowLabel(row)
                        }
                    }
                }
            }
            .navigationTitle("技法图鉴")
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .bottom) {
                Text("共 \(rows.count) 条 · 与 Android 版同源，照片不上传")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 6)
            }
        }
    }

    // MARK: - 顶部进度

    /// 「已解锁 N / 83」+ 进度条（+ 空状态说明）。
    ///
    /// 与 Android 的 `renderDexProgress` 同一件事：一行数字 + 一条进度条。
    private var progressHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("已解锁")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                // ★ 分母用 `dexStore.total`（= `TechniqueDexBridge.count()`），
                //   不写死 83：知识库哪天加到 90 条，这里会自动跟上。
                Text("\(dexStore.unlockedCount) / \(dexStore.total)")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Palette.accent)
                Spacer(minLength: 0)
            }
            progressBar
            if dexStore.unlockedCount == 0 {
                // 空状态：解释「怎么才会有第一条」。
                // ★ 只说动作与参考分，不做评价、不承诺奖励。
                Text("把某一维拍到参考分达标，就会解锁对应的一条 · 每天最多一条")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("技法图鉴解锁进度")
        .accessibilityValue("已解锁 \(dexStore.unlockedCount) / \(dexStore.total)")
    }

    /// 进度条。
    ///
    /// ★ 自己画而不是用 `ProgressView(value:)`：系统那版在深色列表里是系统蓝，
    ///   与「已解锁」绿标（`Palette.accent`）不是同一个色，用户会以为是两个指标。
    ///   Android 侧进度条用的是主色，这边跟着主色走。
    private var progressBar: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.secondary.opacity(0.2))
                Capsule()
                    .fill(Palette.accent)
                    // 比例由 `dexStore.progress` 给（已夹到 0...1，绝不做除零）
                    .frame(width: proxy.size.width * CGFloat(dexStore.progress))
            }
        }
        .frame(height: 6)
    }

    // MARK: - 行

    /// 一行：标题（已解锁的跟一个绿标）+ 分类胶囊 + 一句话口诀。
    private func rowLabel(_ row: TechniqueRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(row.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2)
                // 与 Android `skillCard` 里那句绿色「已解锁」对齐（同一个绿：主色）
                if dexStore.isUnlocked(id: row.dexId) {
                    unlockedBadge
                }
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(row.category)
                    .font(.caption2)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15))
                    .clipShape(Capsule())
                Text(row.oneLiner)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }

    /// 「已解锁」绿标。
    ///
    /// ★ 文案与 Android 一字不差（`labelText("  已解锁", ..., R.color.st_priority_context)`），
    ///   两端截图对比时不会出现「一个说已解锁、一个说已完成」。
    private var unlockedBadge: some View {
        Text("已解锁")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(Palette.accent)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Palette.accent.opacity(0.15))
            .clipShape(Capsule())
    }
}

/// 单条技法的完整详情。
///
/// 详情文本由 **Kotlin 侧**拼好（TechniqueDexBridge.detailAt），
/// Swift 只负责显示 —— 这样两端的排版口径天然一致，也不用把
/// `steps` / `params` / `mistakes` 这些 List 逐个搬过语言边界。
private struct TechniqueDetailView: View {
    let index: Int32

    var body: some View {
        ScrollView {
            Text(TechniqueDexBridge.shared.detailAt(index: index))
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
        }
        .navigationTitle("技法详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}
