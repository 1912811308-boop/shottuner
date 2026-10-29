// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/ContentView.swift —— 第一批（T10）的最小界面。
//
// ★ 只搭骨架，不实现业务逻辑。
//   界面上刻意只放三件事，每一件都对应一条必须被验证的链路：
//     ① 标题          → SwiftUI 工程能起来
//     ② 相机权限按钮  → 权限链路通
//     ③ 共享内核自检  → Kotlin → ObjC → Swift 链路通
//   真正的取景、评分、照拨文案在后续批次（AVFoundation 取景 / Vision 适配）里接上。

import SwiftUI

/// iOS 端根界面（骨架版）。
struct ContentView: View {

    /// 相机权限状态。
    @StateObject private var cameraPermission = CameraPermissionModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header
                cameraSection
                kernelSection
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
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
