// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/ContentView.swift —— 第一批（T10）的最小界面。
//
// ★ 第一批搭骨架，第二批补内容：
//   ① 标题          → SwiftUI 工程能起来
//   ② 相机权限按钮  → 权限链路通
//   ③ 共享内核自检  → Kotlin → ObjC → Swift 链路通
//   ④ 技法图鉴      → 83 条拍照技巧（与 Android 同源，读 commonMain）
//   ★ 取景、评分、照拨文案**仍未接入** —— 那是 T12/T13 的事，别把图鉴
//     误当成「功能版」。界面上这句话要一直留着，直到取景真正接上。

import SwiftUI

/// iOS 端根界面（骨架版）。
struct ContentView: View {

    /// 相机权限状态。
    @StateObject private var cameraPermission = CameraPermissionModel()

    /// 技法图鉴是否以模态呈现。
    @State private var showingTechniqueDex = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header
                cameraSection
                techniqueSection
                kernelSection
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .sheet(isPresented: $showingTechniqueDex) {
            // TechniqueDexView 自带 NavigationView，可直接作为模态内容。
            TechniqueDexView()
        }
    }

    // ── 标题 ────────────────────────────────────────────────────────────────
    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("光影调参")
                .font(.largeTitle.bold())
            Text("iOS 版第一批：工程骨架已跑通")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("本版本只搭骨架：相机取景与评分接入在后续批次。")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("技法图鉴已上线：83 条拍照技巧，与 Android 版读同一份内核。")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // ── 相机权限 ────────────────────────────────────────────────────────────
    private var cameraSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("相机权限")

            Button {
                cameraPermission.request()
            } label: {
                Text(cameraPermission.status.allowsCapture ? "重新检查权限" : "申请相机权限")
            }
            .buttonStyle(.borderedProminent)

            Text(cameraPermission.status.text)
                .font(.footnote)
                .foregroundStyle(.secondary)

            Text("照片只在这台设备上分析，不会上传。App 给的是参考分与「照拨」建议，参数由你自己调。")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // ── 技法图鉴（83 条，与 Android 同源）───────────────────────────────────
    private var techniqueSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("技法图鉴")

            Button {
                showingTechniqueDex = true
            } label: {
                Text("浏览 \(SharedKernelBridge.techniqueCount) 条拍照技巧")
            }
            .buttonStyle(.bordered)

            Text("口诀、什么时候用、怎么做、建议值、容易踩的坑——全部来自共享内核，两端同一份内容。")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // ── 共享内核自检 ────────────────────────────────────────────────────────
    private var kernelSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("共享内核自检")

            LabeledRow(label: "Kotlin 内核", value: SharedKernelBridge.kernelDescription)
            LabeledRow(label: "定点格式化", value: SharedKernelBridge.formatOne(1.5))
            LabeledRow(label: "带号格式化", value: SharedKernelBridge.formatSigned(0.3))
            LabeledRow(label: "参考分", value: SharedKernelBridge.referenceScoreText(82))
            LabeledRow(
                label: "是否水平",
                value: SharedKernelBridge.isLevel(roll: 0.4) ? "已平" : "还差一点"
            )
            LabeledRow(
                label: "commonMain 直调",
                value: "\(SharedKernelBridge.commonNormalizeDegrees(370))°"
            )
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.headline)
    }
}

/// 一行「标签 : 取值」。骨架阶段用它替代真正的业务卡片。
private struct LabeledRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.footnote.monospaced())
                .multilineTextAlignment(.trailing)
        }
    }
}
