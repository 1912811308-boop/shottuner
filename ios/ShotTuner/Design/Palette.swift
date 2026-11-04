// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Design/Palette.swift —— 与 Android 版逐值对齐的色板。
//
// ★ 为什么逐个抄数值而不是「各自取个好看的」：
//   两端是同一个产品的两个外壳。用户如果两个平台都用，色差会被当成"这版没做好"。
//   Android 侧的取值在 app/src/main/res/values/colors.xml，那边每条都标了对比度，
//   这里原样搬过来，改一处就必须改另一处（CHANGELOG 里要写）。
//
// ★ SwiftUI 的 Color 没有「十六进制整数」构造器，所以下面有个 `Color(argb:)` 扩展。
//   注意 Android 的 `#AARRGGBB` 是 **ARGB**（透明度在前），
//   与 Web 的 `#RRGGBBAA` 相反 —— 抄色值时最容易在这里抄反。

import SwiftUI

/// Android `colors.xml` 的 Swift 镜像。
enum Palette {

    // ── 主色 ────────────────────────────────────────────────────────────────
    /// `st_accent` #7FE3C0 —— 模式 / 页签 / 参考分 / 快门。对比度 12.3:1。
    static let accent = Color(argb: 0xFF7FE3C0)

    /// `st_warn` #FFB74D —— 倾斜 / 别用。对比度 10.5:1。
    static let warn = Color(argb: 0xFFFFB74D)

    /// `st_warn_strong` #FFC53D —— 悬浮卡的高亮警示，比 `warn` 更黄一点。
    static let warnStrong = Color(argb: 0xFFFFC53D)

    // ── 描边 ────────────────────────────────────────────────────────────────
    /// `st_hairline` #33FFFFFF —— 发丝线（20% 白）。
    static let hairline = Color(argb: 0x33FFFFFF)

    /// 覆盖层上黑色描边的统一透明度。
    ///
    /// 亮背景（天空 / 白墙）上的白色线条必须靠这层黑底才显形，
    /// Android 侧对应 `gridShadowPaint` / `ringShadowPaint` 的 `Color.argb(140, 0, 0, 0)`。
    static let lineShadow = Color(argb: 0x8C000000)

    // ── 直方图 ──────────────────────────────────────────────────────────────
    /// `st_hist_stroke` #B37FE3C0 —— 直方图描边（70% 主色）。
    static let histStroke = Color(argb: 0xB37FE3C0)

    /// `st_hist_top` #CC7FE3C0 —— 渐变顶部（80% 主色）。
    static let histTop = Color(argb: 0xCC7FE3C0)

    /// `st_hist_mid` #227FE3C0 —— 渐变中段（13% 主色），停靠点 0.65。
    static let histMid = Color(argb: 0x227FE3C0)

    /// `st_hist_bottom` #007FE3C0 —— 渐变底部（全透明）。
    static let histBottom = Color(argb: 0x007FE3C0)

    // ── 语义色 ──────────────────────────────────────────────────────────────
    /// 水平参考线「已平」时用主色，未平时用 43% 白（对应 Android `argb(110,255,255,255)`）。
    static let levelIdle = Color(argb: 0x6EFFFFFF)

    /// 覆盖层上的文字主色。
    static let overlayText = Color.white

    /// 覆盖层上的次级文字。
    static let overlayTextSecondary = Color(argb: 0xB3FFFFFF)
}

extension Color {

    /// 用 Android 风格的 `#AARRGGBB` 整数构造颜色。
    ///
    /// - Parameter argb: 32 位整数，高位是 alpha。
    init(argb: UInt32) {
        let a = Double((argb >> 24) & 0xFF) / 255.0
        let r = Double((argb >> 16) & 0xFF) / 255.0
        let g = Double((argb >> 8) & 0xFF) / 255.0
        let b = Double(argb & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
