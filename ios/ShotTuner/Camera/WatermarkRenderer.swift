// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/WatermarkRenderer.swift —— T17 · 成绩水印（Core Graphics 烧字）。
//
// 只做一件事：把**已经算好的**文案烧到成片的左下角，返回一张新图。
// 它不判断「该写什么」—— 文案口径（「参考分 82 / 100」还是「参考分 --」）由调用方
// 用 `ScoreSnapshot.hasTotal` 决定（T17 验收④：未测全时烧「参考分 --」，不烧假数字）。
// 本文件只负责「把这行字画上去」。
//
// ★ 零第三方依赖：只用 Core Graphics / UIKit / Foundation，全是系统框架。
//   本仓硬规则 —— 工程里不出现 CocoaPods，也不出现 SPM 的第三方依赖
//   （ios/project.yml 的 packages 段被 tools/ci/check-ios-project-spec.sh 盯着）。
//
// ★ 为什么不用 `UIFont(name: "HelveticaNeue-Medium", size:)`：
//   指定字体名要精确匹配 PostScript 名，**不同 iOS 版本 / 不同机型上可能返回 nil**。
//   返回 nil 之后要么崩溃（强解包），要么走一条「看起来能跑」的降级分支 ——
//   在真机上表现为「水印没出现」，而且**不报错、不崩溃、日志里什么都没有**。
//   系统字体用 `UIFont.systemFont(ofSize:weight:)` 永远不会返回 nil，且自动跟随
//   Dynamic Type 的字重表，没有理由去指定字体名。
//
// ★ 为什么字要垫一块半透明黑底：
//   白字直接压在浅色画面上（雪景、逆光的白墙、婚纱）等于没写。
//   底衬 alpha 取 0.45、白字 alpha 取 0.95 —— 压得住亮部，又不至于像贴了个黑膏药。
//
// ★ 坐标与旋转（这里是最容易出「歪水印」的地方，见 `orientedPixelSize(of:)`）：
//   `UIImage.imageOrientation` 不是 `.up` 时，`cgImage` 里的**像素本身没有转**，
//   旋转信息只存在 `imageOrientation` 这一个枚举里。所以：
//     · 直接 `cgContext.draw(image.cgImage!, in:)` → 画出来是**躺倒的**原图；
//       而我们随后在上下文的坐标系里画的正经文字，相对这张躺倒的图就是「转了 90°」；
//     · 本文件的画法是 `image.draw(in:)` —— UIKit 会把 `imageOrientation` 一起应用，
//       于是画布里的照片是**正立的**，同一坐标系里画的水印方向自然也是对的。
//   `AVCapturePhoto` 经 `fileDataRepresentation()` 写出的 JPEG，竖拍时**通常**已经带
//   正确的 EXIF 方向（UIKit 读出来 `imageOrientation == .right`/`.left` 而不是把像素转正），
//   ⇒ 这条处理不是「以防万一」，而是**日常路径**。不处理它就等于每张竖拍水印都歪。

import CoreGraphics
import Foundation
import UIKit

/// 成片水印绘制。
///
/// 纯函数，无状态、无副作用、不读任何全局配置 —— 开关由调用方（`CaptureFlow`）判断。
enum WatermarkRenderer {

    // MARK: - 版式常量

    /// 版式参数。集中放在一处，改版式只改这里。
    ///
    /// ★ 全部按「图宽的比例」定义，而不是绝对值：
    ///   同一份代码要服务 1080p 与 48MP 两种成片，绝对像素值必然在其中一端不可看
    ///   （12px 的字在 8064 宽的图上就是一条灰线）。
    private enum Metrics {
        /// 距图边的留白：图宽的 4%。
        static let marginRatio: CGFloat = 0.04
        /// 留白兜底（小图上 4% 会小到贴边）。
        static let marginMin: CGFloat = 12
        /// 字号：图宽的 3.5%。
        static let fontRatio: CGFloat = 0.035
        /// 字号兜底。★ 单位是**像素**（画布 scale 固定为 1，见 `render`），不是 point。
        static let fontMin: CGFloat = 14
        /// 底衬黑的不透明度。
        static let plateAlpha: CGFloat = 0.45
        /// 白字的不透明度。
        static let textAlpha: CGFloat = 0.95
        /// 底衬内边距（横向），相对字号。
        static let platePaddingXRatio: CGFloat = 0.5
        /// 底衬内边距（纵向），相对字号。
        static let platePaddingYRatio: CGFloat = 0.32
        /// 底衬圆角，相对字号。★ 圆角越大越像 UI 控件，太小又毛刺，取 0.35 挡中间。
        static let cornerRadiusRatio: CGFloat = 0.35
        /// 文字区最多占图宽的比例（留出两侧 margin 之后还能再收一点，避免长文案顶到边）。
        static let maxTextWidthRatio: CGFloat = 0.92
    }

    // MARK: - 入口

    /// 在图片左下角烧一行文字，返回**新图**，不改原图（`UIImage` 本身不可变）。
    ///
    /// - Parameters:
    ///   - image: 源图。相册写入前的成片（`UIImage(contentsOfFile:)` 读出来的 JPEG）。
    ///   - text: **已经算好的**文案，例如「参考分 82 / 100」或「参考分 --」。
    ///     空白串（全空格 / 全换行）视为「不烧水印」，直接返回原图。
    /// - Returns: 烧好水印的图；入参无像素数据时原样返回（宁可不加，也不返回空图）。
    static func render(_ image: UIImage, text: String) -> UIImage {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // ★ 空文案不烧：底衬会变成一块无字的黑膏药，比没水印更难看。
        guard !trimmed.isEmpty else { return image }

        let canvasSize = orientedPixelSize(of: image)
        // ★ 这是「至少断言」的那一处（见文件头「坐标与旋转」）：
        //   画布尺寸为 0 说明拿到的 UIImage 没有可用像素（CIImage 支撑的图、或已在
        //   别处被释放）。Debug 下炸在这里，比在真机上生成一张 0×0 的图再写进相册好查。
        assert(
            canvasSize.width > 0 && canvasSize.height > 0,
            "WatermarkRenderer: 画布尺寸为 0 —— 源图没有像素数据，水印无从画起"
        )
        guard canvasSize.width > 0, canvasSize.height > 0 else { return image }

        let format = UIGraphicsImageRendererFormat.default()
        // ★ scale = 1：把画布的「点」直接当**像素**用。
        //   这样输出位图的像素尺寸 == canvasSize，既不会因为屏幕 scale 被二次放大，
        //   也不会在非整数倍缩放下丢一圈像素。
        format.scale = 1
        // ★ opaque = false：相机成片本来不透明，但 render() 是通用工具函数，
        //   万一将来有人把带 alpha 的图（截图 / PNG）传进来，opaque = true 会把
        //   透明区填成黑色 —— 那是很难往回追的显现错误。代价是多一条 alpha 通道
        //   （12MP 约 12MB 的瞬时内存），可以接受。
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(size: canvasSize, format: format)
        return renderer.image { _ in
            // ① 先画照片本体。
            //    ★ 必须是 `image.draw(in:)`（UIKit 会应用 `imageOrientation`），
            //    不能是 `cgContext.draw(image.cgImage!, in:)`（只搬原始像素、忽略方向）。
            //    两者的差别在竖拍成片上就是「正的」与「躺倒的」，见文件头。
            image.draw(in: CGRect(origin: .zero, size: canvasSize))
            // ② 再压水印。同一坐标系 ⇒ 文字方向与照片方向天然一致。
            drawWatermark(text: trimmed, canvasSize: canvasSize)
        }
    }

    // MARK: - 绘制细节

    /// 在画布左下角画「半透明黑底 + 白字」。
    ///
    /// 坐标系：`UIGraphicsImageRenderer` 提供的是 **UIKit 风格**的上下文
    /// （原点在左上、y 向下增长），所以「左下角」= y 取 `height - margin - 底衬高`。
    ///
    /// - Parameters:
    ///   - text: 已 trim 过的非空文案。
    ///   - canvasSize: 画布尺寸（= 输出像素尺寸）。
    private static func drawWatermark(text: String, canvasSize: CGSize) {
        let width = canvasSize.width
        let height = canvasSize.height

        let margin = max(Metrics.marginMin, width * Metrics.marginRatio)
        let fontSize = max(Metrics.fontMin, width * Metrics.fontRatio)
        let font = UIFont.systemFont(ofSize: fontSize, weight: .semibold)
        let textColor = UIColor(white: 1.0, alpha: Metrics.textAlpha)

        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: textColor
        ]
        let attributed = NSAttributedString(string: text, attributes: attributes)

        let padX = fontSize * Metrics.platePaddingXRatio
        let padY = fontSize * Metrics.platePaddingYRatio

        // ★ 先量化出可用文字宽：图宽 × 92%，再扣两侧 margin 与底衬内边距。
        //   给一个下界 fontSize，避免极窄的图算出 0 或负数宽度后
        //   `boundingRect` 退化成「一个字符一行」。
        let textMaxWidth = max(
            fontSize,
            width * Metrics.maxTextWidthRatio - margin * 2 - padX * 2
        )
        // ★ `.usesLineFragmentOrigin` 必须给：不给的话按「单行、不换行」量，
        //   长文案会量出一个超出画面的宽度，底衬跟着出界。
        let measured = attributed.boundingRect(
            with: CGSize(width: textMaxWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        // ceil 让底衬尺寸落在整像素上，避免半透明黑底在边缘出现一条半像素灰线。
        let textSize = CGSize(width: ceil(measured.width), height: ceil(measured.height))
        let plateSize = CGSize(
            width: textSize.width + padX * 2,
            height: textSize.height + padY * 2
        )
        let plateOrigin = CGPoint(x: margin, y: height - margin - plateSize.height)
        let plateRect = CGRect(origin: plateOrigin, size: plateSize)
        // 圆角不能超过短边的一半，否则 `UIBezierPath` 的圆角会被系统钳成椭圆角。
        let cornerRadius = min(
            fontSize * Metrics.cornerRadiusRatio,
            min(plateSize.width, plateSize.height) / 2
        )

        UIColor(white: 0.0, alpha: Metrics.plateAlpha).setFill()
        UIBezierPath(roundedRect: plateRect, cornerRadius: cornerRadius).fill()

        attributed.draw(
            at: CGPoint(x: plateOrigin.x + padX, y: plateOrigin.y + padY)
        )
    }

    // MARK: - 尺寸与方向

    /// 图在**正立**之后应有的像素尺寸。
    ///
    /// ★ 为什么不用 `image.size`：
    ///   `image.size` 是「点」且与 `scale` 相关，不同来源的图（相册读回 / `AVCapturePhoto`
    ///   直接转 / 测试里手搓的）`scale` 不一致，用它算字号会让同一张图的字号时大时小。
    ///   这里一律按 `cgImage` 的**像素**尺寸算，只有拿不到 `cgImage` 时才退回
    ///   `size × scale`（CIImage 支撑的图会走到这条兜底）。
    ///
    /// ★ 为什么要交换宽高：
    ///   `.left` / `.right` / `.leftMirrored` / `.rightMirrored` 这四种方向表示
    ///   「显示时相对原始像素转了 90°」，正立后的宽高与原始像素**互换**。
    ///   不交换的话，竖拍成片（`cgImage` 是 4032×3024，orientation = .right）会被当成
    ///   横图排版：水印按「宽 4032」算字号与留白，实际画在 3024 宽的正立画布上 ——
    ///   字偏大、留白偏宽，且 `image.draw(in:)` 的矩形长宽比不对，照片会被拉变形。
    ///
    /// - Parameter image: 源图。
    /// - Returns: 正立后的像素尺寸（可能为 0，调用方需判空）。
    private static func orientedPixelSize(of image: UIImage) -> CGSize {
        let raw: CGSize
        if let cgImage = image.cgImage, cgImage.width > 0, cgImage.height > 0 {
            // ★ 显式 CGFloat(...)：`cgImage.width` 是 Int，Swift 不做隐式转换。
            raw = CGSize(width: CGFloat(cgImage.width), height: CGFloat(cgImage.height))
        } else {
            raw = CGSize(
                width: image.size.width * image.scale,
                height: image.size.height * image.scale
            )
        }
        return swapsDimensions(image.imageOrientation)
            ? CGSize(width: raw.height, height: raw.width)
            : raw
    }

    /// 该方向是否表示「显示时相对原始像素转了 90°」。
    ///
    /// - Parameter orientation: `UIImage.imageOrientation`。
    /// - Returns: `true` 表示正立后宽高需要互换。
    private static func swapsDimensions(_ orientation: UIImage.Orientation) -> Bool {
        switch orientation {
        case .left, .right, .leftMirrored, .rightMirrored:
            return true
        case .up, .down, .upMirrored, .downMirrored:
            return false
        @unknown default:
            // ★ 未来新增的方向值按「不交换」处理：不交换最坏是版式略偏（字号/留白不准），
            //   交换错了则是把横图当竖图排，画面会被拉变形 —— 前者可接受，后者不可接受。
            return false
        }
    }
}
