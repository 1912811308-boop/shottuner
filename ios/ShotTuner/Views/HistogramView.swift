// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Views/HistogramView.swift —— 亮度直方图（SwiftUI Canvas 版）。
//
// 这是 Android `app/src/main/java/com/shottuner/app/ui/HistogramView.kt` 的 SwiftUI 重画，
// 视觉口径逐项对齐（具体数值集中在 `Metrics` 里，改一个地方就够）：
//
//   · 竖向参考线：1/4、1/2、3/4 亮度各一条，1pt，色值 `st_hairline`；
//   · 峰值只画到画布高度的 95%（`peakRatio`），头顶留一点空气，尖峰不至于顶到边框；
//   · 高度做**开方压缩**（`sqrt`）而**不是线性** —— 线性会把暗部压成一条贴地的线，
//     而真实画面里暗部信息量最大，看不见就等于没画；
//   · 填充是自上而下的三段渐变，停靠点 0 / 0.65 / 1.0；
//   · 再叠一条 2pt 描边把曲线勾清楚（渐变填充在暗色背景上边缘是虚的）。
//
// ★ 与 Android 的**唯一**结构性差异：列数来源。
//   Android 侧每帧收到 256 桶原始计数，自己按屏幕像素列合并，每列取区间最大值 ——
//   那是为了"极窄的尖峰不被相邻桶稀释"（HistogramView.kt:15 的类注释）。
//   iOS 侧拿到的 `HistogramSnapshot` 已经在 `ScoreTypes.normalized(from:)` 里
//   用同样的 max 规则合并成 64 桶并除以峰值归一化了，所以这里**直接按 64 个桶铺点**，
//   不再做第二次合并 —— 再做一次只会把 64 个点拍成更少的点，白丢分辨率。
//
// ★ 只负责画：不读数据、不算评分、不做动画。
//   数据从 `ScoreCoordinator` 经 `HistogramSnapshot` 进来（`ScoreTypes.swift` 是唯一契约）。
//   `bins` 长度不为 `binCount` 时 `isBlank` 为真，本视图只画参考线。

import Foundation
import SwiftUI

/// 亮度直方图。
///
/// 用法：
/// ```swift
/// HistogramView(snapshot: coordinator.histogram)
///     .frame(height: 64)
/// ```
struct HistogramView: View {

    /// 64 桶、已按峰值归一化到 0...1 的直方图数据。
    ///
    /// 默认 `.empty`（全 0），此时只画三条参考线 —— 不给调用方留"必填参数"，
    /// 覆盖层可以先上线、数据后到。
    var snapshot: HistogramSnapshot = .empty

    /// 是否画 1/4、1/2、3/4 三条竖向参考线。
    ///
    /// 保留成开关是为了将来做「沉浸档」：参考线是给"看直方图判断曝光"的人用的，
    /// 默认档位下它只是三条没人读的灰线。默认开，与 Android 一致。
    var showsGuides: Bool = true

    /// Android 侧逐值抄过来的绘制参数。
    ///
    /// 数值来源：`HistogramView.kt` 的 `guidePaint` / `linePaint` / `onSizeChanged`。
    private enum Metrics {

        /// 竖向参考线宽度（Android `guidePaint.strokeWidth = 1f`）。
        static let guideLineWidth: CGFloat = 1

        /// 曲线描边宽度（Android `linePaint.strokeWidth = 2f`）。
        static let strokeLineWidth: CGFloat = 2

        /// 参考线的位置：1/4、1/2、3/4。Android 用 `for (i in 1..3) x = w * i / 4f`。
        static let guideDivisions: CGFloat = 4

        /// 峰值占画布高度的比例（Android `scaled = sqrt(normalized) * (h * 0.95f)`）。
        static let peakRatio: CGFloat = 0.95

        /// 渐变中段停靠点（Android `floatArrayOf(0f, 0.65f, 1f)`）。
        static let gradientMidStop: CGFloat = 0.65
    }

    var body: some View {
        Canvas(opaque: false, rendersAsynchronously: false) { context, size in
            guard size.width > 0, size.height > 0 else { return }

            // ── ① 三条竖向参考线 ───────────────────────────────────────────────
            // 先画线再判空：没有数据时这几条线还在，画面不会突然"少一块"。
            if showsGuides {
                drawGuides(&context, size: size)
            }

            // ── ② 空图直接结束 ────────────────────────────────────────────────
            // `isBlank` = 桶数不对，或全 0。不画一条贴底的假曲线。
            guard !snapshot.isBlank else { return }

            let bins = snapshot.bins
            let peak = bins.max() ?? 0
            guard peak > 0 else { return }

            let points = columnPoints(bins, peak: peak, size: size)
            guard points.count >= 2 else { return }

            // ── ③ 填充：从底边绕一圈闭合，三段渐变 ─────────────────────────────
            var fillPath = Path()
            fillPath.move(to: CGPoint(x: 0, y: size.height))
            for point in points {
                fillPath.addLine(to: point)
            }
            fillPath.addLine(to: CGPoint(x: size.width, y: size.height))
            fillPath.closeSubpath()

            context.fill(
                fillPath,
                with: .linearGradient(
                    Gradient(stops: [
                        Gradient.Stop(color: Palette.histTop, location: 0),
                        Gradient.Stop(color: Palette.histMid, location: Metrics.gradientMidStop),
                        Gradient.Stop(color: Palette.histBottom, location: 1)
                    ]),
                    startPoint: CGPoint(x: 0, y: 0),
                    endPoint: CGPoint(x: 0, y: size.height)
                )
            )

            // ── ④ 描边：同一条曲线，但**不**闭合到底边 ─────────────────────────
            // 闭合描边会把左右两侧各多画一条竖线，看起来像给直方图加了个框。
            var strokePath = Path()
            strokePath.move(to: points[0])
            for point in points.dropFirst() {
                strokePath.addLine(to: point)
            }
            context.stroke(
                strokePath,
                with: .color(Palette.histStroke),
                lineWidth: Metrics.strokeLineWidth
            )
        }
        // 覆盖层不该吃掉手势：它叠在预览上，点击要穿透到预览 / 快门。
        .allowsHitTesting(false)
    }

    // MARK: - 绘制细节

    /// 1/4、1/2、3/4 三条竖向参考线。
    private func drawGuides(_ context: inout GraphicsContext, size: CGSize) {
        let divisions = max(Metrics.guideDivisions, 1)
        var index: CGFloat = 1
        while index < divisions {
            let x = size.width * index / divisions
            var path = Path()
            path.move(to: CGPoint(x: x, y: 0))
            path.addLine(to: CGPoint(x: x, y: size.height))
            context.stroke(
                path,
                with: .color(Palette.hairline),
                lineWidth: Metrics.guideLineWidth
            )
            index += 1
        }
    }

    /// 把 64 个桶铺成一条从左到右的折线（每个桶一个点）。
    ///
    /// - Parameters:
    ///   - bins: 桶值。理论上已经归一化到 0...1，这里仍然除一次峰值：
    ///     万一上游哪天递了原始计数进来，画面也不会整条贴在顶上。
    ///   - peak: 桶值上限，必须 > 0（调用方已保证）。
    ///   - size: 画布尺寸。
    /// - Returns: 从左到右的点序列；桶数 < 2 时返回空数组。
    private func columnPoints(_ bins: [Float], peak: Float, size: CGSize) -> [CGPoint] {
        let count = bins.count
        guard count >= 2, peak > 0 else { return [] }

        let usableHeight = size.height * Metrics.peakRatio
        let step = size.width / CGFloat(count - 1)

        var points: [CGPoint] = []
        points.reserveCapacity(count)

        for index in 0..<count {
            let x = step * CGFloat(index)
            // 除峰值 + 夹到 0...1：越界数据不得把曲线顶出画布。
            let normalized = min(max(bins[index] / peak, 0), 1)
            // 开方压缩：0.04 的桶画出来有 0.2 的高度，暗部细节才看得见。
            let scaled = min(CGFloat(normalized.squareRoot()) * usableHeight, usableHeight)
            points.append(CGPoint(x: x, y: size.height - scaled))
        }
        return points
    }
}

// MARK: - 预览

/// 造一组"像真的"亮度分布，给预览用：暗部一个小峰 + 中间调主峰 + 高光一个尖峰。
///
/// 刻意用 `HistogramSnapshot.normalized(from:)` 而不是手写 64 个数：
/// 这样预览走的是**和线上同一条**归一化路径，参数写错了预览会立刻看出来。
private func makeHistogramPreviewSnapshot() -> HistogramSnapshot {
    let rawCount = HistogramSnapshot.binCount
    var raw = [Float](repeating: 0, count: rawCount)

    for index in 0..<rawCount {
        let x = Double(index) / Double(rawCount - 1)

        // 暗部：窗边弱光
        let shadow = 0.42 * exp(-pow((x - 0.26) / 0.07, 2))
        // 中间调：主体
        let midtone = exp(-pow((x - 0.54) / 0.15, 2))
        // 高光：灯 / 天空，很窄，用来体现开方压缩的必要性
        let highlight = 0.35 * exp(-pow((x - 0.93) / 0.022, 2))

        raw[index] = Float(max(shadow, max(midtone, highlight)))
    }

    return HistogramSnapshot.normalized(from: raw)
}

#Preview("直方图 · 无数据") {
    HistogramView(snapshot: .empty)
        .frame(width: 320, height: 72)
        .background(Color.black)
        .padding()
}

#Preview("直方图 · 有数据") {
    HistogramView(snapshot: makeHistogramPreviewSnapshot())
        .frame(width: 320, height: 72)
        .background(Color.black)
        .padding()
}
