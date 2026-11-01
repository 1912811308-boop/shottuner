// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/VisionCalibrationCollector.swift
// —— 真机标定采集工具：dump `VNClassifyImageRequest` 的**原始** `(identifier, confidence)` 全列表。
//
// ★ 这个工具是干什么的：
//   `VisionSceneMap` 的映射表**不能凭记忆写**（docs/IOS-ARCH.md §10.2.3）。
//   真正的填表流程是「真机采集 → 人工归族 → 回填 → 锁测试」，
//   采集这一步落在 docs/IOS-ARCH-EXEC.md **T22 · CAL-1**。
//   本文件就是那一步的**采集器**：把 Vision 吐出来的原始标识符与置信度**一字不改**地导出来，
//   供人工判断「哪些标识符属于哪个族」。
//
// ★ 为什么必须导**原始**列表而不是「分类结论」：
//   映射表要固化的是**标识符 → 族**这张表本身。
//   如果只导出「这张图被判成了食物」，那就等于把映射逻辑写进了采集环节，
//   采集出来的东西反而成了「用错误的映射表去标定映射表」—— 循环论证。
//
// ★ 零网络、零第三方依赖：只用系统框架 `Vision`。
//   输出走**文件**（App 沙盒内的 Documents 目录），不是网络 —— 零网络承诺在 iOS 侧
//   同样成立（docs/IOS-PRD.md §7.2 的 L1 源码层门禁会扫到这个文件）。
//
// ★ 用法（一句话）：
//   在真机上把样张喂给 `VisionCalibrationCollector.collect(from:)`，
//   它会把 `<样张名>\t<identifier>\t<confidence>` 逐行写进 Documents 目录下的
//   `vision-calibration/<样张名>.tsv`，用 Xcode 的 Devices and Simulators 把文件取走即可。
//
// ★ 模拟器上这个工具**只会得到空结果**（`VNClassifyImageRequest` 模拟器不可用）——
//   这是**预期行为，不是 bug**（docs/IOS-PRD.md §7.2）。

import CoreGraphics
import Foundation
import ImageIO
import Vision

/// 真机标定采集器：导出 Vision 分类的原始标识符列表。
///
/// ★ 它是**开发期工具**，不是产品功能：
///   - 生产路径不调用它；
///   - 它不做任何「归族」判断，只做「原样导出」——
///     归族是人（或者将来的标定脚本）做的，采集器不参与决策。
enum VisionCalibrationCollector {

    /// 一条原始分类观测。
    struct RawObservation {
        /// Vision 的原始标识符（层级串，例：`animal_mammal_dog`）。
        let identifier: String
        /// 该标识符的置信度（0...1）。
        let confidence: Float
    }

    /// 采集结果的落盘文件名（不含扩展名）。
    static let outputDirectoryName = "vision-calibration"

    /// 对一张图执行分类请求，导出**原始**观测列表。
    ///
    /// ★ 三个刻意的设计：
    ///   1. **不排序、不取前 N、不做任何过滤** —— 原样导出，包括低置信度的。
    ///      阈值该定在哪，得看**完整**的分布，而不是被裁剪后的头部。
    ///   2. **不导出任何「场景结论」** —— 见文件头。
    ///   3. 失败时返回**空数组 + 错误描述**，不抛异常（标定工具不该把 App 搞崩）。
    ///
    /// - Parameters:
    ///   - cgImage: 待采集的样张。
    ///   - orientation: 样张朝向（横竖屏拍摄时需要带上）。
    /// - Returns: 原始观测列表（按 Vision 返回顺序）。
    static func collect(
        from cgImage: CGImage,
        orientation: CGImagePropertyOrientation = .up
    ) -> [RawObservation] {
        // ★ 模拟器上 `VNClassifyImageRequest` 不可用 —— 编译期就跳过，
        //   免得在模拟器上调试时踩到「这个请求到底怎么失败」的问题。
        #if targetEnvironment(simulator)
        return []
        #else
        let request = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(
            cgImage: cgImage,
            orientation: orientation,
            options: [:]
        )
        do {
            try handler.perform([request])
        } catch {
            return []
        }
        return (request.results ?? []).map {
            RawObservation(identifier: $0.identifier, confidence: $0.confidence)
        }
        #endif
    }

    /// 把原始观测列表格式化成 TSV 文本（可直接粘进表格工具）。
    ///
    /// - Parameters:
    ///   - observations: 原始观测列表。
    ///   - sampleName: 样张名（写进每一行，便于多张图合并后追溯来源）。
    /// - Returns: TSV 文本。
    static func formatTSV(
        _ observations: [RawObservation],
        sampleName: String
    ) -> String {
        var lines: [String] = []
        for observation in observations {
            // 用 String(format:) 而不是字符串插值拼浮点数 ——
            // 插值会按当前 locale 给出逗号小数点，粘进表格工具就串列了。
            lines.append(
                "\(sampleName)\t\(observation.identifier)\t"
                    + String(format: "%.6f", observation.confidence)
            )
        }
        return lines.joined(separator: "\n")
    }

    /// 把采集结果写进 App 沙盒的 Documents 目录。
    ///
    /// ★ 为什么写文件而不是打日志：
    ///   一张样张的原始标识符可能有**几十上百条**，日志会被系统截断，
    ///   而且 Xcode 控制台复制粘贴容易丢行。文件是完整的、可反复取的。
    ///
    /// - Parameters:
    ///   - observations: 原始观测列表。
    ///   - sampleName: 样张名（同时作为文件名）。
    /// - Returns: 写入的文件 URL；失败返回 `nil`。
    static func write(
        _ observations: [RawObservation],
        sampleName: String
    ) -> URL? {
        let text = formatTSV(observations, sampleName: sampleName)
        guard let directory = calibrationDirectory() else { return nil }
        let fileURL = directory.appendingPathComponent("\(sampleName).tsv")
        do {
            try text.write(to: fileURL, atomically: true, encoding: .utf8)
            return fileURL
        } catch {
            return nil
        }
    }

    /// 标定输出目录（Documents/vision-calibration）。
    ///
    /// ★ 先 createDirectory 再写文件 —— Documents 目录存在，但**子目录不存在**，
    ///   直接往不存在的目录写会失败（这个坑很小，但排查起来很烦）。
    ///
    /// - Returns: 目录 URL；创建失败返回 `nil`。
    static func calibrationDirectory() -> URL? {
        let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first
        guard let documents else { return nil }
        let directory = documents.appendingPathComponent(
            outputDirectoryName,
            isDirectory: true
        )
        guard FileManager.default.fileExists(atPath: directory.path) else {
            do {
                try FileManager.default.createDirectory(
                    at: directory,
                    withIntermediateDirectories: true
                )
            } catch {
                return nil
            }
        }
        return directory
    }
}
