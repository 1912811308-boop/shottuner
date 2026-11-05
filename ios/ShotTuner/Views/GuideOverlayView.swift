// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Views/GuideOverlayView.swift —— 取景器上的构图引导层（SwiftUI Canvas 版）。
//
// 这是 Android `app/src/main/java/com/shottuner/app/ui/GuideOverlayView.kt` 的重画，
// 只画六层基础引导，**不含** G1 任务态（任务环 / 交点高亮 / 到位动画）—— 那部分归任务模块。
//
// 三个视觉元素对应三条构图原则（Android 类注释原话）：
//   · 三分线：把画面切成九宫格，交点是最容易放稳主体的位置；
//   · 水平参考线 + 实际地平线：一眼看出歪了几度，不用读文字；
//   · 主体标记 + 目标交点：把「该往哪挪」变成两个点对齐的动作。
//
// ── 六层，按 Android 的绘制顺序 ──────────────────────────────────────────────
//   ① 三分线      4 条：先黑底 2.5pt，再白面 1pt（双描边）
//   ② 中心十字    臂长 12pt，1.5pt，白 47%
//   ③ 水平参考线  两条对称短段，`isLevel` 决定主色 / 待平色
//   ④ 地平线倾角  仅 `horizonDetected && !isLevel` 时画，橙色虚线 2pt
//   ⑤ 主体标记    实心点 r3.2 + 圈 r13；`suggestMove` 时加目标圈 r17 + 连线
//   ⑥ 俯仰刻度    右侧竖轴，中心 h/2，±30°，5° 一格，当前角度用 `warnStrong` 加粗
//
// ── 为什么三分线要画两遍 ────────────────────────────────────────────────────
//   只画一条白 18% 的 1px 线时，叠在亮度 0.9 的天空上反差只有 0.018 —— 用户以为开了网格，
//   实际看不见线，这是功能性失效。双描边（黑底 + 白面）对**任何**背景都保证约 ±40% 的
//   局部反差：亮背景靠黑底显形，暗背景靠白面显形。代价是每帧多 4 条 stroke，可忽略。
//
// ★ 坐标系口径：所有归一化量（0...1）来自 `GuideSnapshot`，乘画布尺寸得到点坐标。
//   Android 侧 `PreviewView` 用 FILL_CENTER 缩放、画面会被裁切，所以归一化坐标与画面
//   真实位置只是近似 —— 构图提示允许这个误差，它的用途是「引导用户移动手机」，
//   不是精确测量。SwiftUI 侧同样如此（`AVCaptureVideoPreviewLayer` 的
//   `videoGravity = .resizeAspectFill`），本视图**不做**任何 aspect-fill 反算。
//
// ★ 只负责画：不读传感器、不算评分、不做动画。数据从 `GuideSnapshot` 进来。
//   没有快照时传 `.empty`（`subjectConfidence == 0` → 不画主体标记；`isLevel == true`
//   → 参考线是平的绿线），与"还没数据"的视觉是一致的。

import Foundation
import SwiftUI

/// 取景器覆盖层。
///
/// 用法：
/// ```swift
/// ZStack {
///     CameraPreviewView(session: session)
///     GuideOverlayView(snapshot: coordinator.guide)
/// }
/// ```
struct GuideOverlayView: View {

    /// 覆盖层要画的全部几何量。默认 `.empty`：先上线、数据后到也不会崩。
    var snapshot: GuideSnapshot = .empty

    /// 三分线。沉浸档可以关掉（网格是"对齐构图"时才需要的东西）。
    var showsGrid: Bool = true

    /// 引导总开关（中心十字 / 水平参考线 / 地平线 / 主体标记 / 俯仰刻度）。
    ///
    /// ★ 关掉它会连俯仰刻度一起关 —— 与 Android `onDraw` 的 `if (!showGuides) return`
    ///   一致（那条 return 在 `drawPitchGauge` 之前）。
    var showsGuides: Bool = true

    /// 俯仰刻度条。沉浸档关掉：它只是右侧一段没人读的刻度线。
    var showsPitchGauge: Bool = true

    /// 主体圈 + 目标交点。沉浸档关掉：这两个圈会在人脸 / 主体上压出两个圆，抢画面。
    var showsSubjectMarks: Bool = true

    /// Android 侧逐值抄过来的绘制参数。
    ///
    /// 数值来源见每条注释里的 Android 变量名；`dp(v)` 在 iOS 上就是 `v` 点
    /// （1dp ≈ 1pt，两端都按逻辑像素算），所以数值原样搬，不做 density 换算。
    private enum Metrics {

        // ── ① 三分线 ────────────────────────────────────────────────────────
        /// `gridShadowPaint.strokeWidth = dp(2.5f)`
        static let gridShadowLineWidth: CGFloat = 2.5
        /// `gridPaint.strokeWidth = dp(1f)`
        static let gridLineWidth: CGFloat = 1
        /// `gridPaint.color = Color.argb(89, 255, 255, 255)` → 89/255 ≈ 35% 白。
        static let gridWhiteAlpha: CGFloat = 0.35

        // ── ② 中心十字 ──────────────────────────────────────────────────────
        /// 单臂长度（从画面中心向外）。任务书给的 12pt。
        static let crossHalfArm: CGFloat = 12
        /// `crossPaint.strokeWidth = dp(1.5f)`
        static let crossLineWidth: CGFloat = 1.5
        /// `crossPaint.color = Color.argb(120, 255, 255, 255)` → 120/255 ≈ 47% 白。
        static let crossWhiteAlpha: CGFloat = 0.47

        // ── ③ 水平参考线 ────────────────────────────────────────────────────
        /// `levelPaint.strokeWidth = dp(1.5f)`
        static let levelLineWidth: CGFloat = 1.5
        /// 短段近端到中心的距离。任务书给的 20pt（Android 是 `w * 0.09f`，见报告）。
        static let levelArmLength: CGFloat = 20
        /// 短段远端 = `arm * 2.2`（Android `arm * 2.2f`）。
        static let levelArmGapRatio: CGFloat = 2.2

        // ── ④ 地平线倾角 ────────────────────────────────────────────────────
        /// `horizonPaint.strokeWidth = dp(2f)`
        static let horizonLineWidth: CGFloat = 2
        /// 半长 = `w * 0.46f`（Android `drawLine(px - w*0.46f, ..., px + w*0.46f, ...)`）。
        static let horizonHalfSpanRatio: CGFloat = 0.46
        /// `DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)`
        static let horizonDash: [CGFloat] = [9, 7]

        // ── ⑤ 主体标记 ──────────────────────────────────────────────────────
        /// 实心点半径 `dp(3.2f)`。
        static let subjectDotRadius: CGFloat = 3.2
        /// `subjectDotPaint.color = Color.argb(150, 127, 227, 192)` → 150/255 ≈ 59% 主色。
        static let subjectDotAlpha: CGFloat = 0.59
        /// 主体圈半径 `dp(13f)`。
        static let subjectRingRadius: CGFloat = 13
        /// `subjectPaint.strokeWidth = dp(2f)`
        static let subjectRingLineWidth: CGFloat = 2
        /// 目标圈半径 `dp(17f)`。
        static let targetRingRadius: CGFloat = 17
        /// `targetPaint.strokeWidth = dp(1.5f)`
        static let targetLineWidth: CGFloat = 1.5
        /// `DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)`
        static let targetDash: [CGFloat] = [6, 6]
        /// `targetPaint.color = Color.argb(150, 255, 255, 255)` → 59% 白。
        static let targetWhiteAlpha: CGFloat = 0.59

        // ── ⑥ 俯仰刻度 ──────────────────────────────────────────────────────
        /// 竖轴离右边缘的距离（Android `val x = w - dp(22f)`）。
        static let pitchRightInset: CGFloat = 22
        /// 半长 = h * 0.20（Android 刻度总长 h*0.40，中心在 h/2，两端 0.30h / 0.70h）。
        static let pitchHalfSpanRatio: CGFloat = 0.20
        /// 量程 ±30°。
        static let pitchRangeDegrees: Float = 30
        /// 一格 5°。
        static let pitchStepDegrees: Float = 5
        /// `pitchScalePaint.color = Color.argb(180, 127, 227, 192)` → 70% 主色。
        static let pitchAxisAlpha: CGFloat = 0.70
        /// `pitchScalePaint.strokeWidth = dp(1.2f)`
        static let pitchAxisLineWidth: CGFloat = 1.2
        /// `pitchScalePaint` 刻度半长 `dp(5f)`，这里短刻度 4、长刻度 7（每 15° 一条长刻度）。
        static let pitchMinorHalfLength: CGFloat = 4
        static let pitchMajorHalfLength: CGFloat = 7
        /// 当前角度刻度半长 `dp(9f)`。
        static let pitchCurrentHalfLength: CGFloat = 9
        /// `pitchTickPaint.strokeWidth = dp(2.2f)`
        static let pitchCurrentLineWidth: CGFloat = 2.2
        /// `pitchLabelPaint.textSize = 10sp`，iOS 侧不做字体缩放换算（见文件末尾的说明）。
        static let pitchLabelSize: CGFloat = 10
        /// `pitchLabelPaint.color = Color.argb(210, 127, 227, 192)` → 82% 主色。
        static let pitchLabelAlpha: CGFloat = 0.82
        /// 文字到竖轴的水平间距。
        static let pitchLabelGap: CGFloat = 12
        /// 文字相对刻度线的上移量，避免和橙色刻度糊在一起。
        static let pitchLabelRise: CGFloat = 9
        /// 每 N 格画一条长刻度（5° × 3 = 15°）。
        static let pitchMajorEvery: Int = 3
    }

    var body: some View {
        Canvas(opaque: false, rendersAsynchronously: false) { context, size in
            // Android: `if (w < 10f || h < 10f) return` —— 尺寸没铺开时不画，
            // 否则所有半径 / 臂长都相对一个荒谬的小尺寸，会糊成一团。
            guard size.width >= 10, size.height >= 10 else { return }

            // ① 三分线
            if showsGrid {
                drawThirds(&context, size: size)
            }

            guard showsGuides else { return }

            // ② 中心十字
            drawCenterCross(&context, size: size)

            // ③ 水平参考线
            drawLevelReference(&context, size: size)

            // ④ 地平线倾角线
            drawHorizonTilt(&context, size: size)

            // ⑤ 主体标记 + 目标交点
            if showsSubjectMarks, snapshot.hasDrawableSubject {
                drawSubjectMarks(&context, size: size)
            }

            // ⑥ 俯仰刻度
            if showsPitchGauge {
                drawPitchGauge(&context, size: size)
            }
        }
        // 覆盖层不该吃掉手势：它叠在预览上，对焦 / 曝光点按要穿透下去。
        .allowsHitTesting(false)
    }

    // MARK: - ① 三分线

    /// 4 条三分线：白面 1pt 压在黑底 2.5pt 上。
    ///
    /// 先画完 4 条黑底再画 4 条白面（Android 是两个 `for` 循环），
    /// 这样交叉点的黑底不会盖住另一条线的白面。
    private func drawThirds(_ context: inout GraphicsContext, size: CGSize) {
        let width = size.width
        let height = size.height

        var shadowPath = Path()
        var facePath = Path()

        for index in 1...2 {
            let x = width * CGFloat(index) / 3
            let y = height * CGFloat(index) / 3

            // 竖线
            shadowPath.move(to: CGPoint(x: x, y: 0))
            shadowPath.addLine(to: CGPoint(x: x, y: height))
            facePath.move(to: CGPoint(x: x, y: 0))
            facePath.addLine(to: CGPoint(x: x, y: height))

            // 横线
            shadowPath.move(to: CGPoint(x: 0, y: y))
            shadowPath.addLine(to: CGPoint(x: width, y: y))
            facePath.move(to: CGPoint(x: 0, y: y))
            facePath.addLine(to: CGPoint(x: width, y: y))
        }

        context.stroke(
            shadowPath,
            with: .color(Palette.lineShadow),
            lineWidth: Metrics.gridShadowLineWidth
        )
        context.stroke(
            facePath,
            with: .color(Color.white.opacity(Metrics.gridWhiteAlpha)),
            lineWidth: Metrics.gridLineWidth
        )
    }

    // MARK: - ② 中心十字

    /// 画面正中的十字，用来确认"手机是正的"和"主体该放哪儿"。
    ///
    /// 注意：Android 版**没有**这个十字（它在三分交点上画小圆圈）。本版按 T13-B 任务书
    /// 第二层新增，色值沿用 Android `crossPaint`（白 47%、1.5pt），视觉重量与交点圈一致。
    private func drawCenterCross(_ context: inout GraphicsContext, size: CGSize) {
        let centerX = size.width * 0.5
        let centerY = size.height * 0.5
        let arm = Metrics.crossHalfArm

        var path = Path()
        path.move(to: CGPoint(x: centerX - arm, y: centerY))
        path.addLine(to: CGPoint(x: centerX + arm, y: centerY))
        path.move(to: CGPoint(x: centerX, y: centerY - arm))
        path.addLine(to: CGPoint(x: centerX, y: centerY + arm))

        context.stroke(
            path,
            with: .color(Color.white.opacity(Metrics.crossWhiteAlpha)),
            lineWidth: Metrics.crossLineWidth
        )
    }

    // MARK: - ③ 水平参考线

    /// 「端平了没」的参考线：两条对称短段，中间留空给画面。
    ///
    /// 位置：有可信地平线时贴地平线（`horizonY`），否则落在画面中线。
    ///
    /// 颜色：`isLevel` 为真时主色（绿），否则待平色（白 43%）。
    ///
    /// ★ 判定源是 Kotlin 侧算好的 `isLevel`，本视图**不自己判** —— 否则会出现
    ///   「线绿了但任务环没绿」。Android 侧曾在这里写死 `horizonDetected && |tilt| <= 1.8`，
    ///   结果对着没有地平线的白墙时参考线永远是白的（用户端平了也得不到确认）。
    private func drawLevelReference(_ context: inout GraphicsContext, size: CGSize) {
        let width = size.width
        let height = size.height

        let refY = snapshot.horizonDetected
            ? clampedUnit(snapshot.horizonY) * height
            : height * 0.5

        let arm = Metrics.levelArmLength
        let centerX = width * 0.5
        let outer = arm * Metrics.levelArmGapRatio

        var path = Path()
        // 左段：由外向内（与 Android 的 drawLine 端点顺序一致，方向不影响渲染）
        path.move(to: CGPoint(x: centerX - outer, y: refY))
        path.addLine(to: CGPoint(x: centerX - arm, y: refY))
        // 右段
        path.move(to: CGPoint(x: centerX + arm, y: refY))
        path.addLine(to: CGPoint(x: centerX + outer, y: refY))

        context.stroke(
            path,
            with: .color(snapshot.isLevel ? Palette.accent : Palette.levelIdle),
            lineWidth: Metrics.levelLineWidth
        )
    }

    // MARK: - ④ 地平线倾角线

    /// 实际地平线的倾角：以地平线中心为轴旋转的橙色虚线。
    ///
    /// 只在 `horizonDetected && !isLevel` 时画 —— 画面已经端平时这条线会和绿色参考线
    /// 完全重合，画出来只是让绿色变成"绿里透橙"。
    private func drawHorizonTilt(_ context: inout GraphicsContext, size: CGSize) {
        guard snapshot.horizonDetected, !snapshot.isLevel else { return }

        let pivotX = size.width * 0.5
        let pivotY = clampedUnit(snapshot.horizonY) * size.height

        // 角度与方向：`tiltDeg` 正值表示画面右侧偏低，屏幕 y 轴向下，
        // 所以方向向量取 (cos, sin) 即可让右端更低 —— 与 Android 的 canvas.rotate 等价。
        // 这里用三角函数直接算端点，而不是套一层 context 变换：
        // GraphicsContext 没有 save/restore，手动变换容易漏还原。
        let radians = Double(snapshot.tiltDeg) * Double.pi / 180
        let halfSpan = size.width * Metrics.horizonHalfSpanRatio
        let dx = CGFloat(cos(radians)) * halfSpan
        let dy = CGFloat(sin(radians)) * halfSpan

        var path = Path()
        path.move(to: CGPoint(x: pivotX - dx, y: pivotY - dy))
        path.addLine(to: CGPoint(x: pivotX + dx, y: pivotY + dy))

        context.stroke(
            path,
            with: .color(Palette.warn),
            style: StrokeStyle(lineWidth: Metrics.horizonLineWidth, dash: Metrics.horizonDash)
        )
    }

    // MARK: - ⑤ 主体标记

    /// 主体重心（实心点 + 圈），以及"该挪到哪个三分交点"（目标圈 + 虚线连线）。
    ///
    /// 显著性低于 0.30 时调用方已经拦掉了（`hasDrawableSubject`）：画了就是瞎指。
    private func drawSubjectMarks(_ context: inout GraphicsContext, size: CGSize) {
        let width = size.width
        let height = size.height

        let subjectX = clampedUnit(snapshot.subjectX) * width
        let subjectY = clampedUnit(snapshot.subjectY) * height

        // 实心点：半径 3.2，59% 主色 —— 圈心必须有个实点，
        // 否则在浅色背景上只剩一个空心圆，看不出"中心在哪"。
        let dotRadius = Metrics.subjectDotRadius
        var dotPath = Path()
        dotPath.addEllipse(in: CGRect(
            x: subjectX - dotRadius,
            y: subjectY - dotRadius,
            width: dotRadius * 2,
            height: dotRadius * 2
        ))
        context.fill(dotPath, with: .color(Palette.accent.opacity(Metrics.subjectDotAlpha)))

        // 主体圈：半径 13，2pt 主色
        let ringRadius = Metrics.subjectRingRadius
        var ringPath = Path()
        ringPath.addEllipse(in: CGRect(
            x: subjectX - ringRadius,
            y: subjectY - ringRadius,
            width: ringRadius * 2,
            height: ringRadius * 2
        ))
        context.stroke(
            ringPath,
            with: .color(Palette.accent),
            lineWidth: Metrics.subjectRingLineWidth
        )

        guard snapshot.suggestMove else { return }

        let targetX = clampedUnit(snapshot.targetX) * width
        let targetY = clampedUnit(snapshot.targetY) * height

        // 目标圈：半径 17，59% 白虚线（Android `targetPaint`）
        let targetRadius = Metrics.targetRingRadius
        var targetPath = Path()
        targetPath.addEllipse(in: CGRect(
            x: targetX - targetRadius,
            y: targetY - targetRadius,
            width: targetRadius * 2,
            height: targetRadius * 2
        ))
        context.stroke(
            targetPath,
            with: .color(Color.white.opacity(Metrics.targetWhiteAlpha)),
            style: StrokeStyle(lineWidth: Metrics.targetLineWidth, dash: Metrics.targetDash)
        )

        // 连线：把「往哪挪」变成一条可见的位移。
        // Android 用的是同一支白色虚线笔；T13-B 任务书要求用 `warn`，
        // 理由是「该挪」是待办提示、不是已完成的主体提示，橙色比白色先被看见。
        var connectorPath = Path()
        connectorPath.move(to: CGPoint(x: subjectX, y: subjectY))
        connectorPath.addLine(to: CGPoint(x: targetX, y: targetY))
        context.stroke(
            connectorPath,
            with: .color(Palette.warn),
            style: StrokeStyle(lineWidth: Metrics.targetLineWidth, dash: Metrics.targetDash)
        )
    }

    // MARK: - ⑥ 俯仰刻度

    /// 右侧俯仰刻度：中心在画面中线，量程 ±30°，5° 一格。
    ///
    /// 正值向上（手机往上抬），负值向下。当前角度用 `warnStrong` 加粗刻度 + 等宽数字标出。
    ///
    /// ★ 与 Android 的差异（任务书要求，非疏漏）：Android 是 0°→90° 的**绝对**刻度
    ///   （底部「腰以下」、中点「45°」、顶部「90°」，另有一个绕底端旋转的指针）。
    ///   iOS 这套改成以中线为 0 的**相对**刻度 —— 拍照时真正要读的是"还差几度到水平"，
    ///   绝对值还得用户自己做减法。
    private func drawPitchGauge(_ context: inout GraphicsContext, size: CGSize) {
        let height = size.height
        let axisX = size.width - Metrics.pitchRightInset
        let centerY = height * 0.5
        let halfSpan = height * Metrics.pitchHalfSpanRatio

        // Android: `if (span <= dp(20f)) return` —— 刻度总长不到 20 点就干脆不画，
        // 否则刻度线会挤成一坨，比没有更糟。
        guard halfSpan * 2 >= 20 else { return }

        let axisColor = Palette.accent.opacity(Metrics.pitchAxisAlpha)

        // 竖轴
        var axisPath = Path()
        axisPath.move(to: CGPoint(x: axisX, y: centerY - halfSpan))
        axisPath.addLine(to: CGPoint(x: axisX, y: centerY + halfSpan))
        context.stroke(
            axisPath,
            with: .color(axisColor),
            lineWidth: Metrics.pitchAxisLineWidth
        )

        // 刻度：-30° … +30°，每 5° 一条；每 15°（index 是 3 的倍数）加长一点
        let tickCount = Int(Metrics.pitchRangeDegrees / Metrics.pitchStepDegrees)
        var tickPath = Path()
        for index in -tickCount...tickCount {
            let degrees = Float(index) * Metrics.pitchStepDegrees
            let y = pitchAxisY(degrees: degrees, centerY: centerY, halfSpan: halfSpan)
            let halfLength = index % Metrics.pitchMajorEvery == 0
                ? Metrics.pitchMajorHalfLength
                : Metrics.pitchMinorHalfLength
            tickPath.move(to: CGPoint(x: axisX - halfLength, y: y))
            tickPath.addLine(to: CGPoint(x: axisX + halfLength, y: y))
        }
        context.stroke(
            tickPath,
            with: .color(axisColor),
            lineWidth: Metrics.pitchAxisLineWidth
        )

        // 当前角度：橙色粗刻度 + 等宽数字
        let pitch = snapshot.pitchDegrees.isFinite ? snapshot.pitchDegrees : 0
        let currentY = pitchAxisY(degrees: pitch, centerY: centerY, halfSpan: halfSpan)

        var currentPath = Path()
        currentPath.move(to: CGPoint(x: axisX - Metrics.pitchCurrentHalfLength, y: currentY))
        currentPath.addLine(to: CGPoint(x: axisX + Metrics.pitchCurrentHalfLength, y: currentY))
        context.stroke(
            currentPath,
            with: .color(Palette.warnStrong),
            lineWidth: Metrics.pitchCurrentLineWidth
        )

        // 数字用等宽字体：俯仰角是每帧都在变的量，比例字体下宽度会跳，
        // 刻度旁边一抖一抖的数字比不显示更烦人。
        //
        // `.foregroundColor` 在 iOS 17 起被标记 deprecated（提示改 `foregroundStyle`），
        // 但只有前者能返回 `Text`（`context.draw` 需要 `Text`），且部署目标是 iOS 16，
        // 所以这里保留，只吃一条编译告警。
        let label = Text(pitchLabelText(pitch))
            .font(.system(size: Metrics.pitchLabelSize, weight: .medium, design: .monospaced))
            .foregroundColor(Palette.accent.opacity(Metrics.pitchLabelAlpha))

        context.draw(
            label,
            at: CGPoint(
                x: axisX - Metrics.pitchLabelGap,
                y: currentY - Metrics.pitchLabelRise
            ),
            anchor: .trailing
        )
    }

    /// 把角度换算成刻度线上的 y。超出量程的夹到两端（刻度线不出画面）。
    private func pitchAxisY(degrees: Float, centerY: CGFloat, halfSpan: CGFloat) -> CGFloat {
        let range = Metrics.pitchRangeDegrees
        let clamped = min(max(degrees, -range), range)
        return centerY - CGFloat(clamped / range) * halfSpan
    }

    /// 「+12° / -8° / 0°」。带符号是因为"抬头"和"低头"的修正方向相反。
    private func pitchLabelText(_ pitch: Float) -> String {
        let rounded = pitch.rounded()
        if abs(rounded) < 0.5 {
            return "0°"
        }
        return String(format: "%+.0f°", rounded)
    }

    // MARK: - 归一化坐标

    /// 把 `GuideSnapshot` 的归一化量夹到 0...1 再乘尺寸。
    ///
    /// 三重防御：
    /// - `NaN` / `±inf` 一律落回 0.5（画面中线）—— 画到边框上会误导用户"主体在角落"；
    /// - 负数 / 大于 1 夹边界 —— 上游换算法时不会把标记画到画布外；
    /// - 返回 `CGFloat`，避免 `Float * CGFloat` 的隐式转换告警。
    private func clampedUnit(_ value: Float) -> CGFloat {
        guard value.isFinite else { return 0.5 }
        return CGFloat(min(max(value, 0), 1))
    }
}

// MARK: - 预览

/// 一个"什么都开"的快照：地平线偏低、歪 3.5°、主体在左侧、建议挪到右上交点。
private func makeGuidePreviewSnapshot() -> GuideSnapshot {
    GuideSnapshot(
        rollDegrees: 0.8,
        isLevel: false,
        horizonDetected: true,
        horizonY: 0.62,
        tiltDeg: 3.5,
        subjectX: 0.28,
        subjectY: 0.55,
        subjectLeft: 0.18,
        subjectTop: 0.40,
        subjectRight: 0.38,
        subjectBottom: 0.70,
        subjectConfidence: 0.72,
        targetX: 0.6667,
        targetY: 0.3333,
        suggestMove: true,
        pitchDegrees: 12
    )
}

#Preview("覆盖层 · 无数据") {
    GuideOverlayView(snapshot: .empty)
        .frame(width: 260, height: 460)
        .background(Color(white: 0.35))
}

#Preview("覆盖层 · 有数据") {
    GuideOverlayView(snapshot: makeGuidePreviewSnapshot())
        .frame(width: 260, height: 460)
        .background(Color(white: 0.35))
}
