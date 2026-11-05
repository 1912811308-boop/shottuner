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
//   1. `hasGrowth == false` ⇒ **整张对比卡不显示**。不是显示一堆 `--`：
//      一张写着「首次 -- / 最佳 --」的卡片除了让用户以为坏了，没有任何信息量。
//   2. `hasGrowth == true` 时，每个数值**单独看自己的有效性标志**才决定要不要印。
//      ★ 现状（写这份代码时的真实签名）：`IosGrowthDto` **没有** per-field 的
//        `firstValid` / `bestValid` / `deltaValid` 三个标志，只有 `hasGrowth` 一个总闸。
//        门面在 `growth()` 里保证：`hasGrowth == true` 蕴含「首张有分、存在有分记录、
//        delta 可算」，三者同时成立 —— 所以现在一个总闸就够。
//        将来若给 DTO 补上三个 `xxxValid`，只需在 `growthCard` 里加三条 `if`，
//        渲染结构不用改。
//   3. **任何情况下都不许把 invalid 的 0 印出来。** 本仓已经因为「缺数据伪装成 0」
//      被打回两次（成绩卡印假分数、`hasTotal` 口径），这里是第三个入口。
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
    /// ★ `hasGrowth == false` 时**不渲染任何东西**（`@ViewBuilder` 里只有 `if` 没有
    ///   `else`，等价于 `EmptyView`）。这就是铁律 1：不是显示 `--`，是整张卡不存在。
    ///   记录数那一行在卡片**外面**（见 `countLine`）—— 那一行永远有意义。
    ///
    /// - Parameter summary: 门面给的成长摘要。
    @ViewBuilder
    private func growthCard(_ summary: IosGrowthDto) -> some View {
        if summary.hasGrowth {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("首次")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.overlayTextSecondary)
                    Text("\(summary.firstTotal)")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundStyle(Palette.overlayText)
                        .monospacedDigit()

                    Text("→")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.overlayTextSecondary)
                        .padding(.horizontal, 2)

                    Text("最佳")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.overlayTextSecondary)
                    Text("\(summary.bestTotal)")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundStyle(Palette.accent)
                        .monospacedDigit()

                    Text(deltaLabel(summary.delta))
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(Palette.accent)
                        .monospacedDigit()
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

    /// 增量文案：`（+20）` / `（-5）` / `（0）`。
    ///
    /// ★ 正数才补 `+`：写下 `（20）` 会让人以为「总和是 20」，
    ///   而 `（0）` 不写成 `（+0）` —— 「没有变化」是一种有意义的结果，不是进步。
    ///
    /// - Parameter delta: `bestTotal - firstTotal`。
    /// - Returns: 带中文括号的增量文案。
    private func deltaLabel(_ delta: Int32) -> String {
        let value = Int(delta)
        return value > 0 ? "（+\(value)）" : "（\(value)）"
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
