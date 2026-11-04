// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/FrameStatsProducer.swift —— T12 验收① 的 iOS 侧离线帧统计入口。
//
// ★ 这个类的职责**只有四步**（多一步都不做）：
//   ① 读 PNG / JPEG 字节（`CGImageSource`）；
//   ② 转成紧凑的 YUV420（I420）三个平面；
//   ③ 把平面的 `ByteArray` 交给 Kotlin 的 `IosBridge.shared.dumpFrameStats(...)`；
//   ④ 把 Kotlin 返回的**字符串原样落盘**。
//
// ★★ 为什么文本必须由 Kotlin 生成（本任务最容易踩的坑）：
//   `Float` 的字符串化在 Kotlin（`$float`）与 Swift（`String(describing:)`）里
//   格式可能不同（尾零、科学计数法、-0.0、精度边界）。
//   两边各拼各的，diff 永远对不上，而且对不上还看不出是「算法不同」还是「拼法不同」。
//   ⇒ **Swift 侧一个统计量都不格式化、一个字段都不拼**，只负责「传字节 → 拿字符串 → 写文件」。
//
// ★ 跨语言写法与 `VisionSceneDetector.swift` / `SharedKernelBridge.swift` 一致：
//   `import Shared`；Kotlin `object` 在 Swift 里是**类属性单例**
//   → `IosBridge.shared` / `FrameStatsCore.shared`，**不是** `IosBridge()` / `FrameStatsCore()`。
//   详见 `shared/src/iosMain/.../IosBridge.kt` 的 KDoc（重载改名 / 数组过边界 / object 单例）。
//
// ★ 与 Android 侧的同名入口对齐：`computeFromImage(bytes) -> FrameStats`。
//   它**直接调 commonMain 的 `FrameStatsCore`**（不是 Swift 重写一份算法），
//   所以两端「同一份字节 → 同一套算法」这件事是代码结构保证的，不靠人工核对。
//
// ★ 口径铁律：这里产出的只是**参考分**的统计依据与**建议值**，
//   教用户怎么「照拨」，不替用户调，也不做任何「正确 / 错误」「专业评测」的断言。
//   零网络、零第三方依赖（ImageIO / CoreGraphics / CoreVideo 都是系统框架）。

import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import Shared

/// 离线帧统计 / dump 入口（无状态，全部静态方法）。
enum FrameStatsProducer {

    // MARK: - 错误

    /// 每一步失败都给出可定位的原因，绝不「静默返回空串」。
    enum ProducerError: Error {
        /// `CGImageSourceCreateWithData` 失败。
        case cannotCreateImageSource
        /// `CGImageSourceCreateImageAtIndex` 失败（不是可解码的 PNG/JPEG）。
        case cannotDecodeImage
        /// 建不出位图上下文。
        case cannotCreateBitmapContext
        /// 图像尺寸小于 `FrameStatsCore` 的最小尺寸（32×32）。
        case imageTooSmall(width: Int, height: Int)
        /// 不支持的 `CVPixelBuffer` 像素格式。
        case unsupportedPixelFormat(OSType)
        /// `CVPixelBuffer` 平面数不符合预期。
        case unsupportedPlaneCount(Int)
        /// `CVPixelBufferLockBaseAddress` 失败。
        case cannotLockPixelBuffer
        /// `FrameStatsCore.compute` 返回 null（采样网格退化）。
        case emptyStatistics
        /// 读文件失败。
        case cannotRead(URL, underlying: Error)
        /// 建输出目录失败。
        case cannotCreateOutputDirectory(URL, underlying: Error)
        /// 写 dump 失败。
        case cannotWrite(URL, underlying: Error)
        /// 图集目录里没有 PNG/JPEG。
        case emptyFixtureDirectory(URL)
    }

    // MARK: - 紧凑 I420 平面

    /// 紧凑的 YUV420（I420）平面。
    ///
    /// ★ 为什么必须是「紧凑」的：`dumpFrameStats(...)` 的签名只有
    ///   `yRowStride` / `uRowStride`，没有 pixelStride，也没有 vRowStride。
    ///   Kotlin 侧必然按 `yPixelStride = uPixelStride = vPixelStride = 1`、
    ///   `vRowStride = uRowStride` 调 `FrameStatsCore.compute(...)`。
    ///   与 Android 侧 `RgbToYuv.toI420()` 的 `YuvImage` 完全同一布局：
    ///   - Y：`rowStride = width`，长度 `width * height`
    ///   - U / V：`rowStride = chromaWidth = (width + 1) / 2`，
    ///     长度 `chromaWidth * chromaHeight`，`chromaHeight = (height + 1) / 2`
    struct Yuv420Planes {
        /// 图像宽（像素）。
        let width: Int
        /// 图像高（像素）。
        let height: Int
        /// Y 平面每行字节跨度（= width）。
        let yRowStride: Int
        /// U / V 平面每行字节跨度（= chromaWidth）。
        let uRowStride: Int
        /// Y 平面字节。
        let y: [UInt8]
        /// U（Cb）平面字节。
        let u: [UInt8]
        /// V（Cr）平面字节。
        let v: [UInt8]

        /// 色度平面宽度。
        var chromaWidth: Int { (width + 1) / 2 }
        /// 色度平面高度。
        var chromaHeight: Int { (height + 1) / 2 }
    }

    /// 一次落盘的结果。
    struct DumpResult {
        /// 图集里的原始文件名（也是传给 Kotlin 的 `label`）。
        let label: String
        /// dump 文件 URL。
        let outputURL: URL
    }

    // MARK: - ★ 行序开关（唯一需要 macOS CI 复核的一处）

    /// `CGContext` 位图内存的行 0 是否等于 PNG 的**顶行**。
    ///
    /// ★ 本机没有 Xcode / macOS，无法实机确认这一条。第一次在 macOS CI 跑出 dump 后，
    ///   用 `06-no-face.png`（脚本里是**从上到下的渐变**，`tools/fixtures/gen_frame_stats_fixtures.py`
    ///   的 `vgradient`）与 Android dump 的 `grid` 逐格比：
    ///   · 逐格一致 → 保持 `false`；
    ///   · 上下镜像 → 改成 `true`，**只改这一行**，其余代码一律不动。
    ///   `05-with-face.png` 的椭圆中心也在 cy=110（不是 120），同样能检出镜像。
    /// ★ 注意：`meanLuma` / `clipHigh` / `clipLow` 这类全图统计量对行序不敏感，
    ///   所以**必须比 `grid`**，不能只看均值就宣布「对齐了」。
    private static let reversesDecodedRows = false

    // MARK: - 对外入口 ①：与 Android 同签名的 FrameStats

    /// 从 PNG/JPEG 字节算出 common 的 `FrameStats`。
    ///
    /// ★ 这一步**直接调 commonMain 的 `FrameStatsCore.shared.compute(...)`**，
    ///   Swift 侧没有第二份算法。传给 Kotlin 的参数与 Kotlin 侧
    ///   `dumpFrameStats(...)` 内部约定保持一致（见 `computeStats(_:)` 注释）：
    ///   `rotationDegrees = 0` / `computeFocus = true` / `focusFallback = 0` /
    ///   `emitRgb = false`，三个 pixelStride 均为 1，`vRowStride = uRowStride`。
    ///
    /// - Parameter bytes: PNG / JPEG 文件字节。
    /// - Throws: `ProducerError`。
    /// - Returns: common 的 `FrameStats`（不是 Swift 自定义结构）。
    static func computeFromImage(_ bytes: Data) throws -> FrameStats {
        try computeStats(yuv420(fromImageBytes: bytes))
    }

    /// 直接从相机帧（`CVPixelBuffer`）算 `FrameStats`，供实时链路复用。
    /// - Parameter pixelBuffer: FullRange 420 的相机帧。
    /// - Throws: `ProducerError`。
    /// - Returns: common 的 `FrameStats`。
    static func computeFromPixelBuffer(_ pixelBuffer: CVPixelBuffer) throws -> FrameStats {
        try computeStats(yuv420(from: pixelBuffer))
    }

    // MARK: - 对外入口 ②：dump 文本（Kotlin 生成）

    /// 单张图 → Kotlin 生成的 dump 文本（**不落盘**，给测试/调试用）。
    ///
    /// ★★ 跨语言调用点：`IosBridge.shared.dumpFrameStats(...)`。
    ///   Kotlin `object` → Swift 类属性单例 `shared`；
    ///   Kotlin `ByteArray` → `KotlinByteArray`；
    ///   Kotlin `Int` → Swift `Int32`；
    ///   返回的 Kotlin `String` → Swift `String`（原样，不做任何加工）。
    ///
    /// ★ `label` 必须与 Android 侧**逐字相同**（用图集原文件名，含 `.png` 后缀）：
    ///   Kotlin 生成的文本很可能把它写进标题行；两边 label 不同，diff 就会假报不一致。
    ///
    /// - Parameters:
    ///   - imageBytes: PNG / JPEG 文件字节。
    ///   - label: 图集原文件名，例如 `01-bright.png`。
    /// - Throws: `ProducerError`。
    /// - Returns: Kotlin 侧生成的固定格式文本（**原样返回**）。
    static func dumpText(imageBytes: Data, label: String) throws -> String {
        let planes = try yuv420(fromImageBytes: imageBytes)
        return IosBridge.shared.dumpFrameStats(
            yPlane: kotlinByteArray(planes.y),
            uPlane: kotlinByteArray(planes.u),
            vPlane: kotlinByteArray(planes.v),
            yRowStride: Int32(planes.yRowStride),
            uRowStride: Int32(planes.uRowStride),
            width: Int32(planes.width),
            height: Int32(planes.height),
            label: label
        )
    }

    /// 单张图 → dump 文本 → **原样落盘**。
    ///
    /// ★ 落盘用 `String.write(to:atomically:encoding:)`，不加头、不加尾、不换换行符、
    ///   不重新编码数值：写进去的就是 Kotlin 返回的那串字节。
    /// - Parameters:
    ///   - imageBytes: 图片字节。
    ///   - label: 图集原文件名（同时决定输出文件名）。
    ///   - outputDirectory: 输出目录（不存在会自动建）。
    /// - Throws: `ProducerError`。
    /// - Returns: 写出的文件 URL。
    @discardableResult
    static func dump(
        imageBytes: Data,
        label: String,
        outputDirectory: URL
    ) throws -> URL {
        let text = try dumpText(imageBytes: imageBytes, label: label)
        try makeDirectory(at: outputDirectory)

        // 文件名带图集原文件名，便于与 Android 侧同名前缀的 dump 做 diff。
        let safeName = (label as NSString).lastPathComponent
        let outputURL = outputDirectory.appendingPathComponent("\(safeName).ios.dump.txt")
        do {
            try text.write(to: outputURL, atomically: true, encoding: .utf8)
        } catch {
            throw ProducerError.cannotWrite(outputURL, underlying: error)
        }
        return outputURL
    }

    /// 批量：把 `docs/fixtures/frame-stats/` 里的每张图**逐个**产出 dump 文件。
    ///
    /// ★ 文件名按字典序排序，两端顺序一致（Android 侧 `FrameStatsBaselineDumpTest`
    ///   也是 `sortedBy { it.name }`）。
    ///
    /// - Parameters:
    ///   - directory: 图集目录。
    ///   - outputDirectory: dump 输出目录。
    /// - Throws: `ProducerError`。
    /// - Returns: 每张图的落盘结果。
    @discardableResult
    static func dumpFixtures(
        in directory: URL,
        outputDirectory: URL
    ) throws -> [DumpResult] {
        let fileManager = FileManager.default
        let candidates: [URL]
        do {
            candidates = try fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            )
        } catch {
            throw ProducerError.cannotRead(directory, underlying: error)
        }
        let fixtures = candidates
            .filter { ["png", "jpg", "jpeg"].contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !fixtures.isEmpty else {
            throw ProducerError.emptyFixtureDirectory(directory)
        }
        try makeDirectory(at: outputDirectory)

        var results: [DumpResult] = []
        for fixture in fixtures {
            // ★ 不用零网络门禁禁止的那个 Data 便利构造器：走 FileHandle 读本地文件。
            let handle: FileHandle
            do {
                handle = try FileHandle(forReadingFrom: fixture)
            } catch {
                throw ProducerError.cannotRead(fixture, underlying: error)
            }
            defer { try? handle.close() }
            let bytes: Data
            do {
                guard let read = try handle.readToEnd() else {
                    throw ProducerError.cannotRead(
                        fixture,
                        underlying: ProducerError.cannotDecodeImage
                    )
                }
                bytes = read
            } catch let error as ProducerError {
                throw error
            } catch {
                throw ProducerError.cannotRead(fixture, underlying: error)
            }

            let label = fixture.lastPathComponent
            let outputURL = try dump(
                imageBytes: bytes,
                label: label,
                outputDirectory: outputDirectory
            )
            results.append(DumpResult(label: label, outputURL: outputURL))
        }
        return results
    }

    // MARK: - 对外入口 ③：字节 / 像素缓冲 → I420 平面

    /// PNG / JPEG 字节 → 紧凑 I420 平面。
    ///
    /// 解码链路：`CGImageSourceCreateWithData` → `CGImageSourceCreateImageAtIndex`
    /// → 8 位 RGBA 位图上下文（`premultipliedLast` + `byteOrder32Big`，内存顺序 R,G,B,A）
    /// → 反预乘 → BT.601 全范围 RGB→YUV → 2×2 色度平均。
    ///
    /// ★ 系数与量化规则**逐字照抄** Android `analysis/YuvImage.kt` 的 `RgbToYuv`：
    ///   `Y = 0.299R + 0.587G + 0.114B`、
    ///   `U = -0.168736R - 0.331264G + 0.5B + 128`、
    ///   `V = 0.5R - 0.418688G - 0.081312B + 128`；
    ///   每个像素先四舍五入（`floor(v + 0.5)`），U/V 再按 2×2 块取平均四舍五入，
    ///   全部钳到 0..255。两边用同一套正向变换，`FrameStatsCore` 里那套逆变换
    ///   才能互逆 —— 否则颜色统计与肤色判据就没法比。
    ///
    /// - Parameter bytes: PNG / JPEG 文件字节。
    /// - Throws: `ProducerError`。
    /// - Returns: 紧凑 I420 平面。
    static func yuv420(fromImageBytes bytes: Data) throws -> Yuv420Planes {
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil) else {
            throw ProducerError.cannotCreateImageSource
        }
        guard let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw ProducerError.cannotDecodeImage
        }
        let rgba = try decodeRGBA(cgImage)
        return toI420(rgba)
    }

    /// `CVPixelBuffer` → 紧凑 I420 平面。
    ///
    /// ★ 只接受 **FullRange**：
    ///   - `kCVPixelFormatType_420YpCbCr8BiPlanarFullRange`（NV12：`Y` 平面 + 交错的 `CbCr` 平面）
    ///   - `kCVPixelFormatType_420YpCbCr8PlanarFullRange`（I420：Y / Cb / Cr 三平面）
    ///   `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange`（Y 为 16–235）**明确拒绝**，
    ///   因为它会让直方图与 250/8 两个溢出阈值的口径整体偏掉
    ///   （docs/IOS-TECH-FEASIBILITY.md §2.1）。
    ///
    /// API：`CVPixelBufferLockBaseAddress(.readOnly)` →
    /// `CVPixelBufferGetBaseAddressOfPlane` / `GetBytesPerRowOfPlane` /
    /// `GetWidth(Height)OfPlane` → 逐行拷贝去 stride → `UnlockBaseAddress`。
    /// 去 stride 是必须的：`dumpFrameStats` 只吃 rowStride/pixelStride=1 的紧凑布局。
    ///
    /// - Parameter pixelBuffer: 相机帧。
    /// - Throws: `ProducerError`。
    /// - Returns: 紧凑 I420 平面。
    static func yuv420(from pixelBuffer: CVPixelBuffer) throws -> Yuv420Planes {
        let pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard width >= 32, height >= 32 else {
            throw ProducerError.imageTooSmall(width: width, height: height)
        }
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else {
            throw ProducerError.cannotLockPixelBuffer
        }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let planeCount = CVPixelBufferGetPlaneCount(pixelBuffer)
        switch pixelFormat {
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            guard planeCount == 2 else {
                throw ProducerError.unsupportedPlaneCount(planeCount)
            }
            return compactFromBiPlanar(
                pixelBuffer: pixelBuffer,
                width: width,
                height: height
            )
        case kCVPixelFormatType_420YpCbCr8PlanarFullRange:
            guard planeCount == 3 else {
                throw ProducerError.unsupportedPlaneCount(planeCount)
            }
            return compactFromPlanar(
                pixelBuffer: pixelBuffer,
                width: width,
                height: height
            )
        default:
            throw ProducerError.unsupportedPixelFormat(pixelFormat)
        }
    }

    // MARK: - 内部：解码

    /// 解码后的 8 位 RGBA（内存顺序 R,G,B,A，**已反预乘**）。
    private struct DecodedRGBA {
        let width: Int
        let height: Int
        let rgba: [UInt8]
    }

    private static func decodeRGBA(_ cgImage: CGImage) throws -> DecodedRGBA {
        let width = cgImage.width
        let height = cgImage.height
        guard width >= 32, height >= 32 else {
            throw ProducerError.imageTooSmall(width: width, height: height)
        }

        let bytesPerRow = width * 4
        let totalBytes = bytesPerRow * height
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: totalBytes)
        buffer.initialize(repeating: 0, count: totalBytes)
        defer { buffer.deallocate() }

        // ★ 用图像自己的 colorSpace，避免 ImageIO 给 PNG 贴 sRGB 标签后
        //   再向 DeviceRGB 做一次色彩转换（那会让两端像素不再逐字节相同）。
        let colorSpace = cgImage.colorSpace ?? CGColorSpaceCreateDeviceRGB()
        // `byteOrder32Big | premultipliedLast` ⇒ 内存字节顺序恒为 R,G,B,A，
        // 不依赖 CPU 字节序。
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue |
            CGImageAlphaInfo.premultipliedLast.rawValue
        guard let context = CGContext(
            data: UnsafeMutableRawPointer(buffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            throw ProducerError.cannotCreateBitmapContext
        }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        var rgba = Array(UnsafeBufferPointer(start: buffer, count: totalBytes))
        // 反预乘：透明像素按「合成到黑底」处理，与 Android `PngDecoder.readPixel()`
        // 的 `r = (r*a + 127)/255` 同一口径。图集本身无 alpha（a=255），此步为恒等。
        for index in 0..<(width * height) {
            let base = index * 4
            let alpha = Int(rgba[base + 3])
            guard alpha < 255 else { continue }
            rgba[base] = UInt8(unpremultiply(Int(rgba[base]), alpha: alpha))
            rgba[base + 1] = UInt8(unpremultiply(Int(rgba[base + 1]), alpha: alpha))
            rgba[base + 2] = UInt8(unpremultiply(Int(rgba[base + 2]), alpha: alpha))
        }

        // ★ 行序归一化。见 `reversesDecodedRows` 的说明：本机无法实机验证，
        //   macOS CI 第一次跑 dump 时用 `06-no-face.png` 的 grid 对照决定这一个开关。
        if reversesDecodedRows {
            var flipped = [UInt8](repeating: 0, count: totalBytes)
            for row in 0..<height {
                let source = row * bytesPerRow
                let target = (height - 1 - row) * bytesPerRow
                for column in 0..<bytesPerRow {
                    flipped[target + column] = rgba[source + column]
                }
            }
            rgba = flipped
        }
        return DecodedRGBA(width: width, height: height, rgba: rgba)
    }

    /// 反预乘（a=255 时恒等；a=0 时为黑）。
    private static func unpremultiply(_ value: Int, alpha: Int) -> Int {
        guard alpha > 0 else { return 0 }
        guard alpha < 255 else { return value }
        return min(255, (value * 255 + alpha / 2) / alpha)
    }

    // MARK: - 内部：RGB → I420

    /// BT.601 全范围正向变换（系数与 `app/.../YuvImage.kt` 的 `RgbToYuv` 逐字一致）。
    private static let yR = 0.299
    private static let yG = 0.587
    private static let yB = 0.114
    private static let uR = -0.168736
    private static let uG = -0.331264
    private static let uB = 0.5
    private static let vR = 0.5
    private static let vG = -0.418688
    private static let vB = -0.081312

    private static func toI420(_ image: DecodedRGBA) -> Yuv420Planes {
        let width = image.width
        let height = image.height
        let chromaWidth = (width + 1) / 2
        let chromaHeight = (height + 1) / 2
        let pixelCount = width * height

        var yPlane = [UInt8](repeating: 0, count: pixelCount)
        var uFull = [Int](repeating: 0, count: pixelCount)
        var vFull = [Int](repeating: 0, count: pixelCount)

        for index in 0..<pixelCount {
            let base = index * 4
            let r = Double(image.rgba[base])
            let g = Double(image.rgba[base + 1])
            let b = Double(image.rgba[base + 2])
            yPlane[index] = UInt8(clamp255(roundHalfUp(yR * r + yG * g + yB * b)))
            uFull[index] = clamp255(roundHalfUp(uR * r + uG * g + uB * b + 128.0))
            vFull[index] = clamp255(roundHalfUp(vR * r + vG * g + vB * b + 128.0))
        }

        var uPlane = [UInt8](repeating: 0, count: chromaWidth * chromaHeight)
        var vPlane = [UInt8](repeating: 0, count: chromaWidth * chromaHeight)
        for cy in 0..<chromaHeight {
            for cx in 0..<chromaWidth {
                var uSum = 0
                var vSum = 0
                var samples = 0
                for dy in 0..<2 {
                    let py = cy * 2 + dy
                    guard py < height else { continue }
                    for dx in 0..<2 {
                        let px = cx * 2 + dx
                        guard px < width else { continue }
                        let index = py * width + px
                        uSum += uFull[index]
                        vSum += vFull[index]
                        samples += 1
                    }
                }
                guard samples > 0 else { continue }
                let cell = cy * chromaWidth + cx
                uPlane[cell] = UInt8(clamp255(roundHalfUp(Double(uSum) / Double(samples))))
                vPlane[cell] = UInt8(clamp255(roundHalfUp(Double(vSum) / Double(samples))))
            }
        }

        return Yuv420Planes(
            width: width,
            height: height,
            yRowStride: width,
            uRowStride: chromaWidth,
            y: yPlane,
            u: uPlane,
            v: vPlane
        )
    }

    /// 四舍五入（被取整值恒为非负 ⇒ `floor(v + 0.5)` 等价于 Math.floor(v+0.5)）。
    private static func roundHalfUp(_ value: Double) -> Int {
        Int(floor(value + 0.5))
    }

    /// 钳到 0..255。
    private static func clamp255(_ value: Int) -> Int {
        min(255, max(0, value))
    }

    // MARK: - 内部：CVPixelBuffer → 紧凑 I420

    private static func compactFromBiPlanar(
        pixelBuffer: CVPixelBuffer,
        width: Int,
        height: Int
    ) -> Yuv420Planes {
        let chromaWidth = (width + 1) / 2
        let chromaHeight = (height + 1) / 2
        let sourceChromaWidth = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1)
        let sourceChromaHeight = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
        let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)?
            .assumingMemoryBound(to: UInt8.self)
        let uvBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1)?
            .assumingMemoryBound(to: UInt8.self)

        var yPlane = [UInt8](repeating: 0, count: width * height)
        if let yBase {
            for row in 0..<height {
                let source = yBase.advanced(by: row * yStride)
                for column in 0..<width {
                    yPlane[row * width + column] = source[column]
                }
            }
        }

        var uPlane = [UInt8](repeating: 128, count: chromaWidth * chromaHeight)
        var vPlane = [UInt8](repeating: 128, count: chromaWidth * chromaHeight)
        if let uvBase {
            for row in 0..<min(chromaHeight, sourceChromaHeight) {
                let source = uvBase.advanced(by: row * uvStride)
                for column in 0..<min(chromaWidth, sourceChromaWidth) {
                    // NV12 的色度平面是交错的 CbCr（UVUV...）：
                    // 偶数字节 = Cb(U)，奇数字节 = Cr(V)。
                    let cell = row * chromaWidth + column
                    uPlane[cell] = source[column * 2]
                    vPlane[cell] = source[column * 2 + 1]
                }
            }
        }
        return Yuv420Planes(
            width: width,
            height: height,
            yRowStride: width,
            uRowStride: chromaWidth,
            y: yPlane,
            u: uPlane,
            v: vPlane
        )
    }

    private static func compactFromPlanar(
        pixelBuffer: CVPixelBuffer,
        width: Int,
        height: Int
    ) -> Yuv420Planes {
        let chromaWidth = (width + 1) / 2
        let chromaHeight = (height + 1) / 2
        let sourceChromaWidth = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1)
        let sourceChromaHeight = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let uStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
        let vStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 2)
        let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)?
            .assumingMemoryBound(to: UInt8.self)
        let uBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1)?
            .assumingMemoryBound(to: UInt8.self)
        let vBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 2)?
            .assumingMemoryBound(to: UInt8.self)

        var yPlane = [UInt8](repeating: 0, count: width * height)
        if let yBase {
            for row in 0..<height {
                let source = yBase.advanced(by: row * yStride)
                for column in 0..<width {
                    yPlane[row * width + column] = source[column]
                }
            }
        }
        var uPlane = [UInt8](repeating: 128, count: chromaWidth * chromaHeight)
        var vPlane = [UInt8](repeating: 128, count: chromaWidth * chromaHeight)
        for row in 0..<min(chromaHeight, sourceChromaHeight) {
            let uSource = uBase?.advanced(by: row * uStride)
            let vSource = vBase?.advanced(by: row * vStride)
            for column in 0..<min(chromaWidth, sourceChromaWidth) {
                let cell = row * chromaWidth + column
                if let uSource { uPlane[cell] = uSource[column] }
                if let vSource { vPlane[cell] = vSource[column] }
            }
        }
        return Yuv420Planes(
            width: width,
            height: height,
            yRowStride: width,
            uRowStride: chromaWidth,
            y: yPlane,
            u: uPlane,
            v: vPlane
        )
    }

    // MARK: - 内部：跨语言边界

    /// `[UInt8]` → `KotlinByteArray`。
    ///
    /// ★★ 全文件**只有这一处**写 `KotlinByteArray` 的构造 / 写入方法名
    ///   （与 `VisionSceneMap.Scenes` 收窄 `AiScene.xxx` 同一个做法）：
    ///   Kotlin/Native 把 `ByteArray` 导出成 `KotlinByteArray`，其 Swift 方法名
    ///   （`init(size:)` / `set(index:value:)`）由 ObjC 导出规则决定。
    ///   若 macOS CI 报「找不到 `set(index:value:)`」，**只改这一个函数**。
    ///
    /// ★ Kotlin 的 `Byte` 是**有符号**的，Kotlin 侧用 `toInt() and 0xFF` 还原。
    ///   所以这里必须存**位模式**：`255` 要变成 `-1`，用 `Int8(bitPattern:)`，
    ///   绝不能写 `Int8(255)`（会 trap）。
    private static func kotlinByteArray(_ bytes: [UInt8]) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (index, byte) in bytes.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }

    /// 直接调 commonMain 的 `FrameStatsCore`（**不是 Swift 重写**）。
    ///
    /// ★ 参数与 Kotlin 侧 `dumpFrameStats(...)` 内部的约定逐项对齐：
    ///   `yPixelStride = uPixelStride = vPixelStride = 1`、
    ///   `vRowStride = uRowStride`（签名里没有 vRowStride，只能这么假定）、
    ///   `rotationDegrees = 0`（离线图本身是正立的）、
    ///   `computeFocus = true` / `focusFallback = 0`（单张图没有「下一帧」可等）、
    ///   `emitRgb = false`（dump 不需要 RGB 网格）。
    ///   这与 Android 侧 `FrameStatsBaselineDumpTest` 里那组参数完全同口径。
    ///
    /// ★ 返回值是 Kotlin 的 `Result?`，Swift 侧不拼写它的类型名
    ///   （嵌套类导出名由 Kotlin/Native 决定），用类型推导 + 可选链取 `stats`。
    private static func computeStats(_ planes: Yuv420Planes) throws -> FrameStats {
        let result = FrameStatsCore.shared.compute(
            yPlane: kotlinByteArray(planes.y),
            uPlane: kotlinByteArray(planes.u),
            vPlane: kotlinByteArray(planes.v),
            yRowStride: Int32(planes.yRowStride),
            yPixelStride: 1,
            uRowStride: Int32(planes.uRowStride),
            uPixelStride: 1,
            vRowStride: Int32(planes.uRowStride),
            vPixelStride: 1,
            width: Int32(planes.width),
            height: Int32(planes.height),
            rotationDegrees: 0,
            computeFocus: true,
            focusFallback: 0,
            emitRgb: false
        )
        guard let stats = result?.stats else {
            throw ProducerError.emptyStatistics
        }
        return stats
    }

    // MARK: - 内部：文件

    private static func makeDirectory(at url: URL) throws {
        do {
            try FileManager.default.createDirectory(
                at: url,
                withIntermediateDirectories: true,
                attributes: nil
            )
        } catch {
            throw ProducerError.cannotCreateOutputDirectory(url, underlying: error)
        }
    }
}
