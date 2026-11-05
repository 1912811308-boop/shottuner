// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Growth/GrowthView.swift —— 成长页：首末对比卡 + 参考分折线图（iOS 侧）。
//
// ★ 这个页面存在的理由（G4「进步可见」）：
//   参考分只在取景时闪一下，用户永远不知道自己**比第一张好了多少**。
//   第五张照片之后，那个「你比第一张好了 14 分」才是留下来的理由。
//   数据全部由 common 的 `ScoreHistory` 给（`firstVsBest` / `recentSeries`），
//   Android 侧用的是同一份实现 —— 本文件只渲染，一行统计都不写。
//
// ★ 三条不可违反的显示铁律（`IosGrowthDto` 的 KDoc 里逐条写了理由）：
//
//   1. **整卡总闸**：`hasGrowth == false`（= 记录数不够 `MIN_RECORDS_FOR_GROWTH`）
//      ⇒ 整张对比卡不显示。`--` 堆一行出来只会让用户以为坏了，没有任何信息量。
//      ★ `hasGrowth` 现在**只表达「凑够记录数了没」**，不再兼职「数值能不能显示」——
//      那是三个 `xxxValid` 各自的职责（`IosGrowthBridge.growth()` 的 KDoc 写明了）。
//   2. **逐字段判**：`hasGrowth == true` 时，
//      `firstTotal` 看 `firstTotalValid`、`bestTotal` 看 `bestTotalValid`、
//      `delta` 看 `deltaValid`；invalid 的印 `--`，而 `delta` invalid 时
//      **整段「（+20）」不渲染**（既不印 `（+0）` 也不印 `（--）`：
//      「提高了多少」这个说法在起点未知时本来就不成立）。
//      ★ 契约现状（别照旧注释写）：DTO 现在是 **9 个字段**，三个 `xxxValid`
//      **已经存在**（`9119b47` 之后）。本文件早前那版注释写的是「将来补上标志位
//      再加三条 `if`」—— 那段描述已经过期，删掉了。
//   3. **永不印 invalid 的 0**：`xxxValid == false` 时对应字段的值是 0，
//      那**不是分数、也不是差值**（只是 `Int?` 拆包时的占位）。
//      本仓已经因为「缺数据伪装成 0」被打回两次（成绩卡印假分数、`hasTotal` 口径），
//      这里是第三个入口。
//
// ★ 一个必须写下来的边界（**代码不许依赖它**）：
//   `firstVsBest()` 的语义决定了 `bestTotalValid == false` 时 `firstTotalValid`
//   必然也是 `false`（一条有分记录都没有，首张自然也没分）。
//   但本文件仍然**逐字段判**，绝不写成 `if summary.bestTotalValid { 两个一起印 }`
//   那样一把兜住 —— 因为将来 common 侧改口径（例如「只统计最近 90 天」）时，
//   这个蕴含关系会**悄悄**不成立，而届时报错的不会是这里，只会是用户看到的假数字。
//
// ★ 为什么 `NavigationView` 而不是 `NavigationStack`：
//   照既有先例（`ios/ShotTuner/TechniqueDexView.swift:55`）。本工程部署目标是 16.0，
//   两者都能编；但同一批页面统一用 `NavigationView`，避免「某个入口的关闭按钮行为
//   不一样」这种只在特定路径上出现的问题。显式 `StackNavigationViewStyle()`
//   是为了让 iPad 上不变成「左空右内容」的分栏。
//
// ★ 为什么零第三方图表库：
//   本仓硬规则是**零第三方依赖**（`ios/project.yml` 里连 `packages:` 段都不写，
//   `tools/ci/check-ios-project-spec.sh` 会扫 Podfile / Package.swift / Cartfile）。
//   而且这个图的全部需求就是「一条折线 + 网格 + 断点」，`Path` 三十行就够；
//   引一个图表库换来的是一整棵依赖树的审计成本。
//
// ★ 折线图为什么按「时间等距」而不是「索引等距」：
//   索引等距意味着一周内拍 2 张与拍 200 张在横轴上密度一样 ——
//   于是「密集练习的那一周」在图上看不出更密，趋势完全失真。
//   按时间等距之后，斜率才是真的「每天涨多少分」。

import SwiftUI
import Shared

// MARK: - 常量

/// 本页的固定参数，集中一处便于与 Android 对照。
private enum GrowthDefaults {

    /// 折线图窗口天数。
    ///
    /// ★ 30 天是主理人定的初值，与 Android 成长页口径**待对齐**
    ///   （Android 目前没有成长页，所以暂时没有对侧口径可比）。
    ///   改这个数只要改这里 —— 门面会把它钳到 1..3650。
    static let windowDays: Int = 30

    /// 折线图高度。
    static let chartHeight: CGFloat = 180

    /// 卡片圆角，与复盘卡 / 任务卡一致。
    static let cornerRadius: CGFloat = 12

    /// 折线图的纵向留白：让 0 分与 100 分的点不被边框切掉一半描边。
    static let chartVerticalPadding: CGFloat = 8
}

// MARK: - 页面

/// 成长页：首末对比 + 最近 30 天的参考分折线。
///
/// ★ 为什么整个 struct 标 `@MainActor`（理由与 `ContentView` 顶部那段一致）：
///   `ScoreHistoryStore` 是 `@MainActor` 类，而本页的 `countLine` / `growthCard` /
///   `chartSection` 都是**普通成员**，不是 `body`。不标就是非隔离上下文，
///   读 `store.count` / 调 `store.recentPoints` 都会编译失败
///   （"Main actor-isolated property can not be referenced from a nonisolated context"）。
///   只靠 `body` 的隐式隔离救不到这些成员。
@MainActor
struct GrowthView: View {

    /// 落盘 + 取数的门面。`@ObservedObject`：`revision` 变了本页就重算
    /// （拍完一张回到这里时，卡片与折线都会跟上）。
    @ObservedObject var store: ScoreHistoryStore

    /// 关闭回调（由接线方决定是 dismiss 还是切页）。
    var onClose: () -> Void

    /// 折线图的点。`onAppear` 时取一次。
    ///
    /// ★ 不放成 `store` 的计算属性：那样每次 body 重算都会做一轮
    ///   `recentCount` + 100 次下标访问（30 天里点很多时更贵）。
    ///   进页面取一次即可 —— 这一页本身不产生新记录。
    @State private var points: [GrowthPoint] = []

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // ★ 传的是「当前真实摘要」：`store.growth` 只读、不缓存
                    //   （common 那边是真相）。
                    growthCard(store.growth)
                    countLine
                    chartSection
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // 背景用 `Color.black`：`Palette` 里只有前景色，没有背景色。
            .background(Color.black)
            .navigationTitle("成长记录")
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
            points = store.recentPoints(days: GrowthDefaults.windowDays, now: Date())
        }
    }

    // MARK: - 首末对比卡

    /// 首末对比卡。
    ///
    /// ★ 铁律 1（整卡总闸）：`hasGrowth == false` 时**不渲染任何东西**
    ///   （`@ViewBuilder` 里只有 `if` 没有 `else`，等价于 `EmptyView`）。
    ///   记录数那一行在卡片**外面**（见 `countLine`）—— 那一行永远有意义。
    ///
    /// ★ 铁律 2（逐字段判）：三个数值各自走 `scoreValue` / `deltaBadge`，
    ///   由它们自己看 `xxxValid`。这里**不预判**「首张有分就一定有最佳」之类的
    ///   蕴含关系（理由见文件头那个边界）。
    ///
    /// ★ 三种输入下的样子：
    ///   · 首张未测 + 有最佳 → `首次 -- → 最佳 82`（无增量段）
    ///   · 两者都有         → `首次 62 → 最佳 82（+20）`
    ///   · 两者都没有       → `首次 -- → 最佳 --`（无增量段；此时唯一的信息量是
    ///                        卡片下方那行「已记 N / 500 张」，这是刻意的：
    ///                        藏掉整卡会与「`hasGrowth` 只表达凑够记录数」冲突）
    ///
    /// - Parameter summary: 门面给的成长摘要。
    @ViewBuilder
    private func growthCard(_ summary: IosGrowthDto) -> some View {
        if summary.hasGrowth {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    caption("首次")
                    scoreValue(
                        summary.firstTotal,
                        valid: summary.firstTotalValid,
                        highlight: false
                    )

                    Text("→")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.overlayTextSecondary)
                        .padding(.horizontal, 2)

                    caption("最佳")
                    scoreValue(
                        summary.bestTotal,
                        valid: summary.bestTotalValid,
                        highlight: true
                    )

                    // ★ 只传整个 DTO：`deltaValid` 的判断被关在这个函数内部，
                    //   外面**没有**任何一处能直接把 `summary.delta` 印出去。
                    deltaBadge(summary)
                }

                Text("参考分是同一套算法在两端给的估价，不是评测结论")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.overlayTextSecondary)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.white.opacity(0.04))
            .overlay(
                RoundedRectangle(cornerRadius: GrowthDefaults.cornerRadius, style: .continuous)
                    .strokeBorder(Palette.hairline, lineWidth: 1)
            )
            .clipShape(
                RoundedRectangle(cornerRadius: GrowthDefaults.cornerRadius, style: .continuous)
            )
        }
    }

    /// 小标题（「首次」/「最佳」）。
    /// - Parameter text: 标题文案。
    /// - Returns: 统一字号与颜色的标题。
    private func caption(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
    }

    /// 一个分数位：`valid` 才印数字，否则印 `--`。
    ///
    /// ★ 铁律 3 的唯一落点：**这里没有第二条把 `value` 印出来**的路径。
    ///
    /// ★ `--` 用 `Palette.overlayTextSecondary` 而不是 `accent`：
    ///   `accent` 在这张卡上是「好数字」的颜色（最佳分、增量都用它），
    ///   而 `--` 既不是好消息也不是坏消息，用强调色等于在强调「没有数据」。
    ///
    /// ★ 两个分支的**字号、字重、`.monospacedDigit()` 完全一致**：
    ///   `--` 与 `82` 切换时宽度不抖，卡片不会左右跳一下。
    ///
    /// - Parameters:
    ///   - value: 门面给的分数（无效时是占位的 0，**不会被印出来**）。
    ///   - valid: 这个分数是否真的测出来了。
    ///   - highlight: 是否用主色（最佳分用，首次分不用）。
    /// - Returns: 分数位。
    @ViewBuilder
    private func scoreValue(_ value: Int32, valid: Bool, highlight: Bool) -> some View {
        if valid {
            Text("\(value)")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(highlight ? Palette.accent : Palette.overlayText)
                .monospacedDigit()
        } else {
            Text("--")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(Palette.overlayTextSecondary)
                .monospacedDigit()
        }
    }

    /// 增量段（`（+20）` / `（-5）` / `（0）`）。
    ///
    /// ★ 参数是**整个 DTO**，不是 `delta` 数值 —— 这是刻意的防误用设计：
    ///   `deltaValid` 的检查被关在函数体内部，调用方想「只印数字」也印不出来。
    ///   如果签名是 `deltaLabel(_ delta: Int)`，下一个人从 `summary.delta` 取到
    ///   `Int32` 之后只要 `Int(...)` 一下就能绕过这道闸，而假 0 就是这么印出来的。
    ///
    /// ★ `deltaValid == false` 时**整段不渲染**（`@ViewBuilder` 只有 `if`）：
    ///   不印 `（+0）`（会被读成「一点没进步」）、也不印 `（--）`
    ///   （「提高了多少」这个说法在没有起点时本来就不成立）。
    ///
    /// - Parameter summary: 门面给的成长摘要。
    /// - Returns: 增量段；无效时什么都不画。
    @ViewBuilder
    private func deltaBadge(_ summary: IosGrowthDto) -> some View {
        if summary.deltaValid {
            Text(deltaText(Int(summary.delta)))
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.accent)
                .monospacedDigit()
        }
    }

    /// 增量文案：`（+20）` / `（-5）` / `（0）`。
    ///
    /// ★ 参数是 Swift `Int`（不是 `Int32`）：DTO 的 `delta` 是 `Int32`，
    ///   两者**没有隐式转换**，所以 `deltaText(summary.delta)` 直接写出来是编译错 ——
    ///   必须先在 `deltaBadge` 里判过 `deltaValid` 才能走到这一步。
    ///   这是「用类型挡住误用」的第二道。
    ///
    /// ★ 正数才补 `+`：写下 `（20）` 会让人以为「总和是 20」；
    ///   `（0）` 不写成 `（+0）` ——「没有变化」是一种有意义的结果，不是进步。
    ///
    /// - Parameter value: 已经确认有效的差值。
    /// - Returns: 带中文括号的增量文案。
    private func deltaText(_ value: Int) -> String {
        value > 0 ? "（+\(value)）" : "（\(value)）"
    }

    // MARK: - 记录数

    /// 「已记 N / M 张」。
    ///
    /// ★ 永远显示，与 `hasGrowth` 无关：`IosGrowthDto.shotCount`（这里用
    ///   `store.count`，同一口径）**永远是真实条数**，即使对比卡因为数据不全
    ///   而整张不显示。用户至少能看到「记录在长」。
    ///
    /// ★ 上限由门面给（`maxRecords()`），不写死 500 —— 见 `ScoreHistoryStore.maxRecords()`。
    private var countLine: some View {
        Text("已记 \(store.count) / \(store.maxRecords()) 张")
            .font(.system(size: 11))
            .foregroundStyle(Palette.overlayTextSecondary)
    }

    // MARK: - 折线图

    /// 折线图区块（标题 + 图）。
    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("最近 \(GrowthDefaults.windowDays) 天参考分")
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
            GrowthChart(points: points)
                .frame(height: GrowthDefaults.chartHeight)
        }
    }
}

// MARK: - 折线图

/// 参考分折线图（`Path` 自绘，零第三方依赖）。
///
/// 数据约定（由 `ScoreHistoryStore.recentPoints` 保证）：
/// - 时间**升序**；
/// - `total == nil` 表示这条记录四维不全 —— **图上必须断开**。
private struct GrowthChart: View {

    /// 已按时间升序排好的点。
    let points: [GrowthPoint]

    var body: some View {
        // ★ 一个能画的点都没有时不画图：**绝不画一条 0 横线**。
        //   一条贴着底边的 0 分线会被读成「连续 30 天都是 0 分」，
        //   而事实是一条完整记录都没有。
        if hasAnyMeasured {
            GeometryReader { geometry in
                let size = geometry.size
                let placed = positions(in: size)
                let dots = placed.compactMap { $0 }
                let runs = lineRuns(from: placed)

                ZStack(alignment: .topLeading) {
                    grid(size: size)

                    // 每一段连续的已测点画一条折线。
                    // ★ nil 点打断 `runs`：断点处不连线，也不会把两侧连成一条
                    //   「穿过未测记录」的斜线（那会凭空编出一段趋势）。
                    // ★ `ForEach(Array(runs.indices), id: \.self)` 而不是
                    //   `ForEach(Array(runs.enumerated()), id: \.offset)`：
                    //   **Swift 的 KeyPath 不支持指向元组成员**，
                    //   `\.offset` 会报 "key path cannot refer to tuple element"。
                    ForEach(Array(runs.indices), id: \.self) { index in
                        Path { path in
                            let run = runs[index]
                            guard let first = run.first else { return }
                            path.move(to: first)
                            for point in run.dropFirst() {
                                path.addLine(to: point)
                            }
                        }
                        .stroke(Palette.accent, lineWidth: 2)
                    }

                    // 已测点各画一个小圆点。
                    // ★ 这一步同时解决「只有一个点」的情况：`runs` 里只有 1 个点的段
                    //   不画线，但这里一定有一个点 —— 用户看到的是「图上有一个点」，
                    //   而不是一张空白图。
                    ForEach(Array(dots.indices), id: \.self) { index in
                        Circle()
                            .fill(Palette.accent)
                            .frame(width: 5, height: 5)
                            .offset(x: dots[index].x - 2.5, y: dots[index].y - 2.5)
                    }
                }
                .frame(width: size.width, height: size.height)
            }
            .padding(.vertical, GrowthDefaults.chartVerticalPadding)
        } else {
            Text("还没测到完整的参考分")
                .font(.system(size: 11))
                .foregroundStyle(Palette.overlayTextSecondary)
                .frame(
                    maxWidth: .infinity,
                    minHeight: GrowthDefaults.chartHeight,
                    alignment: .center
                )
        }
    }

    // MARK: 数据判定

    /// 是否存在至少一个「有总分」的点。
    private var hasAnyMeasured: Bool {
        points.contains { $0.total != nil }
    }

    // MARK: 坐标映射

    /// 把点映射成画布坐标；`nil` 表示该点没有总分（必须断开）。
    ///
    /// ★ X 轴用**首末记录的真实时间**当窗口两端，按时间比例落点（见文件头
    ///   「为什么按时间等距」）。不用「固定 30 天」的原因是：刚用两天的人，
    ///   所有点会挤在最右边 1/15 的宽度里，图形看起来像一条竖线。
    ///
    /// - Parameter size: 画布尺寸。
    /// - Returns: 与 `points` 等长、逐项对应的坐标数组。
    private func positions(in size: CGSize) -> [CGPoint?] {
        guard let firstDate = points.first?.timestamp,
              let lastDate = points.last?.timestamp else {
            return []
        }
        return points.map { point in
            guard let total = point.total else { return nil }
            let x = xPosition(
                for: point.timestamp,
                from: firstDate,
                to: lastDate,
                width: size.width
            )
            let y = yPosition(for: total, height: size.height)
            return CGPoint(x: x, y: y)
        }
    }

    /// 时间 → 横坐标（线性映射到 `0..width`）。
    ///
    /// - Parameters:
    ///   - time: 该点的时刻。
    ///   - start: 窗口最早时刻。
    ///   - end: 窗口最晚时刻。
    ///   - width: 画布宽度。
    /// - Returns: 横坐标；窗口跨度为 0 时落在正中（不叠在左边缘）。
    private func xPosition(
        for time: Date,
        from start: Date,
        to end: Date,
        width: CGFloat
    ) -> CGFloat {
        let span = end.timeIntervalSince(start)
        // ★ `span <= 0`：所有点落在同一时刻（例如同一分钟连拍两张）。
        //   落到正中而不是左边缘 —— 左边缘会让它看起来像「30 天前」。
        guard span > 0 else { return width / 2 }
        let ratio = time.timeIntervalSince(start) / span
        return width * CGFloat(min(max(ratio, 0), 1))
    }

    /// 分数 → 纵坐标（Y 轴固定 0..100，反向：100 分在顶部）。
    ///
    /// ★ 固定 0..100 而不是「按数据自适应」：自适应的图会让 78→80 的进步看起来
    ///   爬满整个屏幕，那是视觉欺骗。Y 轴口径写死，进步多大就是多大。
    ///
    /// - Parameters:
    ///   - total: 总分。
    ///   - height: 画布高度。
    /// - Returns: 纵坐标。
    private func yPosition(for total: Int, height: CGFloat) -> CGFloat {
        let clamped = min(max(total, 0), 100)
        return height * (1 - CGFloat(clamped) / 100)
    }

    /// 把「坐标数组」切成若干段连续的有值点。
    ///
    /// ★ 只有长度 ≥ 2 的段才返回：单点段不画线（画线需要两个端点），
    ///   那个点由 `dots` 里的圆点负责显示。
    ///
    /// - Parameter placed: `positions(in:)` 的结果。
    /// - Returns: 每段是一条折线的顶点序列。
    private func lineRuns(from placed: [CGPoint?]) -> [[CGPoint]] {
        var runs: [[CGPoint]] = []
        var current: [CGPoint] = []
        for item in placed {
            if let point = item {
                current.append(point)
            } else if !current.isEmpty {
                runs.append(current)
                current = []
            }
        }
        if !current.isEmpty {
            runs.append(current)
        }
        return runs.filter { $0.count >= 2 }
    }

    // MARK: 网格

    /// 网格：每 25 分一条横线（0 / 25 / 50 / 75 / 100）+ 一圈描边。
    ///
    /// ★ 网格用 `Palette.hairline`（20% 白），线用 `Palette.accent`：
    ///   网格必须能看见但不能抢戏 —— 它是参照物，不是数据。
    ///
    /// - Parameter size: 画布尺寸。
    /// - Returns: 网格图层。
    private func grid(size: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            // ★ 同样避开 `\.offset`（KeyPath 进不了元组）。
            ForEach(Array(gridLevels.indices), id: \.self) { index in
                let y = yPosition(for: gridLevels[index], height: size.height)
                Path { path in
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: size.width, y: y))
                }
                .stroke(Palette.hairline, lineWidth: 1)
            }

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .strokeBorder(Palette.hairline, lineWidth: 1)
                .frame(width: size.width, height: size.height)
        }
    }

    /// 网格的分数刻度。
    private var gridLevels: [Int] {
        [0, 25, 50, 75, 100]
    }
}
