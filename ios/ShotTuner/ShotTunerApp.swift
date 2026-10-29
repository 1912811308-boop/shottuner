// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/ShotTunerApp.swift —— iOS 端入口。
//
// ★ 为什么 iOS 侧用 SwiftUI 而不是 Compose Multiplatform：
//   现有 Android UI 是 View 体系（View + viewBinding），已经上架。
//   改用 CMP 等于把已发布的 UI 整体重写一遍，而**算法内核已经在 commonMain 里共享了**，
//   UI 层的重写换不来任何复用收益 —— 得不偿失。
//   SwiftUI 直接拿到原生手感（回弹、文本选择、系统手势、灵动岛适配），
//   且本项目的真实工作量在相机 / 传感器 / AI，SwiftUI 也一样帮不上忙。
//
// ★ 第一批（T10）只搭骨架：能跑起来、能拿相机权限、能从 Kotlin 共享内核取值。

import SwiftUI

/// ShotTuner iOS 应用的根。
@main
struct ShotTunerApp: App {

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
