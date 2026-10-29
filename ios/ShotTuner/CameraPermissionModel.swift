// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/CameraPermissionModel.swift —— 相机权限状态机（骨架版）。
//
// ★ 口径：这个类**只负责要权限**，不替用户调任何参数。
//   App 的职责是教用户怎么调（给参考分 + 照拨建议），不是替用户调。
// ★ 隐私口径：相机画面只在本机分析，不上传；这里不碰任何图像数据。

import AVFoundation
import Combine
import Foundation

/// 相机授权状态。
enum CameraPermissionStatus: Equatable {
    /// 还没查过（刚启动）。
    case unknown
    /// 用户还没做过选择（系统会在第一次 requestAccess 时弹框）。
    case notDetermined
    /// 已授权。
    case authorized
    /// 用户拒绝了。
    case denied
    /// 被系统策略限制（如家长控制 /  MDM）。
    case restricted

    /// 面向用户的一句话说明。
    var text: String {
        switch self {
        case .unknown:
            return "相机权限：待查询"
        case .notDetermined:
            return "相机权限：还没问过你"
        case .authorized:
            return "相机权限：已授权"
        case .denied:
            // ★ 拒绝之后 iOS 不会再弹框，只能去系统设置里改 —— 所以必须告诉用户去哪改。
            return "相机权限：已拒绝。可在「设置 → 隐私与安全性 → 相机」里改回来"
        case .restricted:
            return "相机权限：被系统限制，暂时用不了"
        }
    }

    /// 是否可以开始取景。
    var allowsCapture: Bool { self == .authorized }
}

/// 相机权限的可观察状态。
final class CameraPermissionModel: ObservableObject {

    /// 当前授权状态。
    @Published private(set) var status: CameraPermissionStatus = .unknown

    init() {
        refresh()
    }

    /// 重新读取当前授权状态（从系统设置返回 App 时也该调一次）。
    func refresh() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            status = .authorized
        case .denied:
            status = .denied
        case .restricted:
            status = .restricted
        case .notDetermined:
            status = .notDetermined
        @unknown default:
            // 未来的新状态按「不可用」处理，不猜。
            status = .unknown
        }
    }

    /// 请求相机授权。
    ///
    /// ★ 用户拒绝过一次之后，iOS 不会再弹框（必须去系统设置）——
    ///   所以这里只是把状态如实反映出来，不做任何诱导式文案。
    func request() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async {
                self?.status = granted ? .authorized : .denied
            }
        }
    }
}
