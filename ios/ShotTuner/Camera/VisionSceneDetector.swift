// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/VisionSceneDetector.swift
// —— Apple Vision 场景识别适配层（D1「用 Vision 替代 ML Kit」的落地）。
//
// 这个类做三件事：
//   ① `VNDetectFaceRectanglesRequest` → 人脸数 / 最大脸面积比 / 脸中心 / 头部朝向；
//   ② `VNClassifyImageRequest` → 原始 `(identifier, confidence)` 列表；
//   ③ ①+② 经 `VisionSceneMap` 聚合成 common 的 `AiScene`，产出 common 的 `AiVisionResult`。
//
// ★ 三条硬约束（架构师 T11 验收 ⑥）：
//   1. `perform(_:)` **绝不在主线程调用** —— 它是阻塞调用（老机型 50–300ms），
//      主线程调用会直接卡住 UI。本类用**独立的后台串行队列**，并在真正 perform 的地方
//      用 `dispatchPrecondition` 把「跑在主线程」变成**运行期立刻崩溃**，而不是偶发卡顿。
//   2. **≤ 10fps 节流** —— 见 `beginAnalysis(now:)`。
//   3. **与 1Hz 的评分管线解耦** —— 本类**不持有任何与评分管线共享的状态**，
//      也不被评分管线驱动；它是「谁来调用、谁拿结果」的独立单元。
//      评分管线（T13）按自己的节奏调 `analyze(...)` 即可，两边互不阻塞。
//
// ★ 真机约束（docs/IOS-PRD.md §7.2，PM 已查证）：
//   `VNClassifyImageRequest` **在模拟器上不可用** ⇒ 本文件用
//   `#if targetEnvironment(simulator)` **在编译期就跳过分类请求**，
//   模拟器上只跑「不崩溃 + 人脸路径」。
//   ⇒ **模拟器上「分类无结果」是预期行为，不是 bug**；分类映射必须真机跑。
//
// ★ 与 Android 侧 `AiVision` 的逐字段对照表见：
//   docs/IOS-T11-VISION-FIELD-MAP.md
//
// ★ 口径铁律：这里的输出只是**参考**，配合 UI 文案用「照拨」「建议值」，
//   教用户怎么调，不是替用户调。

import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import Shared
import Vision

/// 端侧场景识别：Apple Vision → common 的 `AiVisionResult`。
///
/// 用法（接线方在 T13 负责）：
/// ```swift
/// let detector = VisionSceneDetector()
/// detector.analyze(cvPixelBuffer: buffer, orientation: .right) { result in
///     // result.scene 是 common 的 AiScene（不是 String）
///     // result.ok == false 表示这一轮没跑成，别拿它当结论
/// }
/// ```
final class VisionSceneDetector {

    // MARK: - 配置

    /// 检测器的可调参数。
    ///
    /// ★ 默认值全部取自 `VisionSceneThresholds`（iOS 侧自有常量表），
    ///   这样「阈值在哪里」永远只有一个答案 —— 不会有人在校验逻辑里偷偷改一个魔数。
    struct Configuration {
        /// 人脸框最小宽度占画面宽度的比例（对齐 ML Kit 的 `setMinFaceSize(0.06f)`）。
        var minimumFaceWidthRatio: Float = VisionSceneThresholds.minimumFaceWidthRatio

        /// 最高分析帧率（≤ 10fps）。
        var maximumFramesPerSecond: Int = VisionSceneThresholds.maximumFramesPerSecond

        /// 参与场景判定的标签条数上限。
        var maximumClassificationObservations: Int = VisionSceneThresholds.maximumClassificationObservations

        /// 分类标签的最低置信度（★ iOS 侧独立标定，不照搬 Android 的 0.5f）。
        var minimumClassificationConfidence: Float = VisionSceneThresholds.minimumClassificationConfidence

        /// 构造默认配置。
        init() {}
    }

    // MARK: - 状态

    /// 后台串行队列：Vision 的 `perform(_:)` 全部在这里跑，**不在主线程**。
    ///
    /// ★ 为什么用**串行**而不是并发：
    ///   Vision 的请求对象不是线程安全的，而且「上一帧还没算完就丢掉这一帧」
    ///   的策略（见 `beginAnalysis`）在串行队列上是最自然的实现。
    private let queue = DispatchQueue(
        label: "com.shottuner.vision.scene",
        qos: .userInitiated
    )

    /// 保护节流状态（`lastStartTime` / `busy`）。
    private let stateLock = NSLock()

    /// 上一轮分析的**开始**时刻（`CFAbsoluteTime`，单调时钟）。
    private var lastStartTime: CFAbsoluteTime = 0

    /// 上一轮是否还没算完。
    ///
    /// ★ 与 Android 侧 `AiVision.busy` 是**同一个策略**：
    ///   「上一轮还没算完就跳过这一帧」—— AI 结果本来就低频，不需要排队。
    private var busy = false

    /// 本检测器的配置。
    private let configuration: Configuration

    // MARK: - 初始化

    /// 构造检测器。
    /// - Parameter configuration: 可调参数（默认取 iOS 侧阈值表）。
    init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
    }

    // MARK: - 对外入口

    /// 分析一张 `CGImage`（拍照后的静态图 / 标定用样张）。
    ///
    /// - Parameters:
    ///   - cgImage: 待分析的图像。
    ///   - orientation: 图像朝向（拍照时相机是横握的，静态图需要带上 EXIF 方向）。
    ///   - completion: 分析完成回调，**在主线程**调用。
    ///     ★ 帧被节流丢弃时**不会回调**（与 Android 侧 `busy` 策略一致）：
    ///     调用方不应「等一个回调」，而应把结果当成「有就更新、没有就沿用上一次」。
    func analyze(
        cgImage: CGImage,
        orientation: CGImagePropertyOrientation = .up,
        completion: @escaping (AiVisionResult) -> Void
    ) {
        submit(
            makeHandler: {
                VNImageRequestHandler(
                    cgImage: cgImage,
                    orientation: orientation,
                    options: [:]
                )
            },
            completion: completion
        )
    }

    /// 分析一帧相机画面（`CVPixelBuffer`）。
    ///
    /// - Parameters:
    ///   - pixelBuffer: 相机帧。
    ///   - orientation: 帧的朝向。
    ///     ★ 竖屏拍摄时相机给的是横向缓冲，**必须传 `.right`**（或对应的朝向），
    ///       否则人脸框与朝向会整体转 90°。
    ///   - completion: 分析完成回调，**在主线程**调用（帧被丢弃时不回调）。
    func analyze(
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation = .up,
        completion: @escaping (AiVisionResult) -> Void
    ) {
        submit(
            makeHandler: {
                VNImageRequestHandler(
                    cvPixelBuffer: pixelBuffer,
                    orientation: orientation,
                    options: [:]
                )
            },
            completion: completion
        )
    }

    /// 统一的「节流 → 后台执行 → 主线程回调」管线。
    ///
    /// - Parameters:
    ///   - makeHandler: 构造请求处理器（在后台队列上调用）。
    ///   - completion: 结果回调（在主线程调用）。
    private func submit(
        makeHandler: @escaping () -> VNImageRequestHandler,
        completion: @escaping (AiVisionResult) -> Void
    ) {
        // ★ 节流与 busy 判定在**调用线程**上做（不进队列）：
        //   被丢弃的帧连队列都不用进，省一次唤醒。
        guard beginAnalysis(now: CFAbsoluteTimeGetCurrent()) else { return }

        queue.async { [weak self] in
            guard let self else { return }
            defer { self.endAnalysis() }

            // ★ 运行期护栏：perform 绝不允许跑在主线程上。
            //   真跑到了就是**立刻崩**，而不是「偶尔卡一下 UI」那种最难查的问题。
            dispatchPrecondition(condition: .notOnQueue(.main))

            let result = self.runAnalysis(makeHandler: makeHandler)
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    /// 节流与 busy 判定。
    ///
    /// ★ 两道闸门（都来自 docs/IOS-ARCH.md §10.2.4）：
    ///   ① **≤ 10fps**：`now - lastStartTime < 1 / fps` ⇒ 丢弃。
    ///   ② **上一轮未完成** ⇒ 丢弃（同 Android 侧 `AiVision.busy`）。
    /// - Parameter now: 当前时刻。
    /// - Returns: `true` 表示这一帧被受理。
    private func beginAnalysis(now: CFAbsoluteTime) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }

        if busy { return false }

        let framesPerSecond = max(1, configuration.maximumFramesPerSecond)
        let minimumInterval = 1.0 / CFAbsoluteTime(framesPerSecond)
        if lastStartTime > 0 && (now - lastStartTime) < minimumInterval {
            return false
        }

        busy = true
        lastStartTime = now
        return true
    }

    /// 结束一轮分析（释放 busy 闸门）。
    private func endAnalysis() {
        stateLock.lock()
        defer { stateLock.unlock() }
        busy = false
    }

    // MARK: - 真正的分析

    /// 执行 Vision 请求并产出 common 的 `AiVisionResult`。
    ///
    /// - Parameter makeHandler: 构造请求处理器。
    /// - Returns: 分析结果；请求失败时返回**空结果**（`ok == false`）。
    private func runAnalysis(makeHandler: () -> VNImageRequestHandler) -> AiVisionResult {
        let faceRequest = VNDetectFaceRectanglesRequest()

        // ★ 分类请求：**模拟器上不可用**（docs/IOS-PRD.md §7.2）。
        //   编译期跳过，而不是运行期 try/catch ——
        //   因为模拟器上这个请求的失败方式不保证是「抛 NSError」（可能是直接崩），
        //   运行期兜底兜不住。
        //
        //   ★★ 连**类型名**都放在 `#if` 里面：`VNClassifyImageRequest` 在模拟器上不可用，
        //   光是声明一个 `VNClassifyImageRequest?` 变量就足以让**模拟器构建失败**。
        //   `#if` 是文本级的，去掉的部分连符号都不提。
        var requests: [VNRequest] = [faceRequest]
        #if !targetEnvironment(simulator)
        let classifyRequest = VNClassifyImageRequest()
        requests.append(classifyRequest)
        #endif

        let handler = makeHandler()
        do {
            try handler.perform(requests)
        } catch {
            // ★ 安全路径：识别失败 ⇒ 空结果（UNKNOWN + ok=false），**不崩、不猜**。
            return VisionSceneDetector.emptyResult()
        }

        // ── ① 人脸 ──
        let faceMetrics = analyzeFaces(faceRequest.results ?? [])

        // ── ② 分类标签 ──
        var labels: [VisionSceneMap.LabelObservation] = []
        #if !targetEnvironment(simulator)
        let observations = (classifyRequest.results ?? []).prefix(
            configuration.maximumClassificationObservations
        )
        labels = observations.map {
            VisionSceneMap.LabelObservation(
                identifier: $0.identifier,
                confidence: $0.confidence
            )
        }
        #endif

        // ── ③ 聚合 + 仲裁 ──
        return VisionSceneDetector.makeResult(
            faceMetrics: faceMetrics,
            labels: labels,
            configuration: configuration
        )
    }

    /// 人脸观测 → 人脸度量。
    ///
    /// - Parameter observations: Vision 的人脸观测。
    /// - Returns: 人脸度量。
    private func analyzeFaces(_ observations: [VNFaceObservation]) -> VisionSceneDetector.FaceMetrics {
        var metrics = VisionSceneDetector.FaceMetrics()

        for observation in observations {
            let box = observation.boundingBox

            // ★ 与 ML Kit 的 `setMinFaceSize(0.06f)` 对齐：
            //   ML Kit 内置了「小脸不算」的门槛，Vision 没有对应参数，
            //   不补这道闸门，两端的脸数会天然对不上（影响验收 ② 的 < 5% 对照）。
            //   ⚠️ ML Kit 文档把该比例描述为「相对图像宽度」，而实现里用的是
            //     「相对图像短边」—— 竖幅图上两者不同，真机对照（CAL-2）时要留意。
            guard box.width >= CGFloat(configuration.minimumFaceWidthRatio) else { continue }

            metrics.faceCount += 1

            // 面积比：Vision 的框已归一化，直接是「占画面面积的比例」，
            // 与 Android 侧 `(box.width * box.height) / (imageW * imageH)` 同一口径。
            let area = Float(box.width * box.height)
            guard area > metrics.faceArea else { continue }

            metrics.faceArea = area
            metrics.faceBox = VisionSceneDetector.NormalizedFaceBox(
                left: Float(box.minX),
                // ★★ 坐标系翻转（最容易错的一处，务必看清楚）：
                //   Vision 的归一化框**原点在左下角**（Core Graphics 习惯）；
                //   common 的 `FaceBox` 与 Android 的 ML Kit 框**原点在左上角**。
                //   ⇒ 必须翻转 Y，否则脸中心上下颠倒（看起来像人在画面下面）。
                top: Float(1 - box.maxY),
                right: Float(box.maxX),
                bottom: Float(1 - box.minY)
            )
            metrics.faceCenterX = metrics.faceBox.centerX
            metrics.faceCenterY = metrics.faceBox.centerY
            // ★ Vision 的 `yaw` 是**弧度**，common 的 `headEulerY` 是**度**。
            //   正负方向由 `VisionSceneThresholds.visionYawSign` 单独控制，
            //   真机对照（CAL-2）后若发现与 ML Kit 相反，只改那一个常量。
            metrics.headEulerY = Float(
                (observation.yaw?.doubleValue ?? 0) * 180.0 / Double.pi
            ) * VisionSceneThresholds.visionYawSign
        }

        return metrics
    }

    // MARK: - 结果组装

    /// 人脸度量（Swift 内部结构，最终会填进 common 的 `AiVisionResult`）。
    struct FaceMetrics {
        /// 有效人脸数。
        var faceCount: Int = 0
        /// 最大人脸占画面的面积比（0...1）。
        var faceArea: Float = 0
        /// 最大人脸框（归一化 0...1，**原点在左上角**，与 common 的 `FaceBox` 一致）。
        var faceBox = VisionSceneDetector.NormalizedFaceBox(left: 0, top: 0, right: 0, bottom: 0)
        /// 最大人脸框中心 X（0...1）。
        var faceCenterX: Float = 0.5
        /// 最大人脸框中心 Y（0...1）。
        var faceCenterY: Float = 0.5
        /// 头部绕 Y 轴朝向（度）。
        var headEulerY: Float = 0
    }

    /// 归一化人脸框（**原点在左上角**，与 common 的 `FaceBox` 坐标系一致）。
    ///
    /// ★ 命名刻意加了 `Normalized` 前缀 —— 因为 Kotlin 侧已经有一个 `FaceBox`，
    ///   同名会在 Swift 里遮蔽掉它（`FaceBox(left:top:right:bottom:)` 那个构造器就调不到了）。
    struct NormalizedFaceBox {
        /// 左边界（0...1）。
        let left: Float
        /// 上边界（0...1）。
        let top: Float
        /// 右边界（0...1）。
        let right: Float
        /// 下边界（0...1）。
        let bottom: Float

        /// 中心 X（0...1）。
        var centerX: Float { (left + right) * 0.5 }
        /// 中心 Y（0...1）。
        var centerY: Float { (top + bottom) * 0.5 }
    }

    /// 空结果（对应 Android 侧的 `AiVisionResult.EMPTY`）。
    ///
    /// ★ 逐字段对照 Android 的 `AiVisionResult.EMPTY`：
    ///   scene=UNKNOWN / confidence=0 / faceCount=0 / faceArea=0 / tags=空 /
    ///   ok=false / faceCenterX=0.5 / faceCenterY=0.5 / faceBox=全零 / headEulerY=0。
    ///
    /// ★ 注意这里构造的是 **Kotlin 的 `FaceBox`**（common 的数据类），
    ///   不是本文件里的 `NormalizedFaceBox` —— 后者只是过渡用的 Swift 结构。
    /// - Returns: 空结果。
    static func emptyResult() -> AiVisionResult {
        AiVisionResult(
            scene: VisionSceneMap.Scenes.unknown,
            confidence: 0,
            faceCount: 0,
            faceArea: 0,
            tags: [],
            ok: false,
            faceCenterX: 0.5,
            faceCenterY: 0.5,
            faceBox: FaceBox(
                left: 0,
                top: 0,
                right: 0,
                bottom: 0
            ),
            headEulerY: 0
        )
    }

    /// 把人脸度量 + 分类标签组装成 common 的 `AiVisionResult`。
    ///
    /// ★ 决策顺序与 Android 侧 `AiVision.decide()` **同构**（人脸证据优先于标签）：
    ///   1. 脸数 ≥ 3 ⇒ 合影；
    ///   2. 有脸且脸面积 ≥ 1.2% ⇒ 人像；
    ///   3. 否则看标签聚合结果；
    ///   4. 都没有 ⇒ 未识别（0.30）。
    ///
    /// ★ 报出的 `confidence` 是**判定置信度**（与 Android 侧同口径），
    ///   **不是** Vision 的原始标签置信度 ——
    ///   因为 common 的 `SceneClassifier` 用 `ai.confidence >= 0.85f` 判断
    ///   「要不要让 AI 覆盖规则结果」，报原始值会让两端行为不可比
    ///   （Vision 的强结果常见 0.77，Android 侧报的是 0.90 这种固定值）。
    ///   原始置信度只用于**族间择优**。
    ///
    /// - Parameters:
    ///   - faceMetrics: 人脸度量。
    ///   - labels: Vision 的分类标签。
    ///   - configuration: 配置（阈值来源）。
    /// - Returns: 组装好的结果。
    static func makeResult(
        faceMetrics: FaceMetrics,
        labels: [VisionSceneMap.LabelObservation],
        configuration: Configuration
    ) -> AiVisionResult {
        var scene = VisionSceneMap.Scenes.unknown
        var confidence = VisionSceneThresholds.unknownConfidence
        var identifiers: [String] = []

        if faceMetrics.faceCount >= VisionSceneThresholds.groupMinimumFaceCount {
            // ── 合影 ──
            scene = VisionSceneMap.Scenes.group
            confidence = VisionSceneThresholds.groupConfidence
        } else if faceMetrics.faceCount >= 1
            && faceMetrics.faceArea >= VisionSceneThresholds.portraitMinimumFaceArea {
            // ── 人像 ──
            scene = VisionSceneMap.Scenes.portrait
            // 与 Android 侧同一套公式（0.86 + 面积加成，上限 0.98）。
            confidence = min(
                VisionSceneThresholds.portraitBaseConfidence
                    + min(faceMetrics.faceArea, 0.2) * 0.5,
                VisionSceneThresholds.portraitMaxConfidence
            )
        } else if let aggregate = VisionSceneMap.aggregate(
            labels,
            minimumConfidence: configuration.minimumClassificationConfidence
        ) {
            // ── 标签聚合（多标签显式聚合，不取 results[0]）──
            // ★ 表为空（尚未真机标定）时 `aggregate` 返回 nil ⇒ 落到「未识别」，
            //   **不会静默给出错误分类**。
            scene = aggregate.scene
            confidence = VisionSceneMap.sceneDecisionConfidence(for: aggregate.scene)
            identifiers = aggregate.identifiers
        }

        let tags = VisionSceneMap.tagTexts(scene: scene, identifiers: identifiers)

        return AiVisionResult(
            scene: scene,
            confidence: confidence,
            faceCount: Int32(faceMetrics.faceCount),
            faceArea: faceMetrics.faceArea,
            tags: tags,
            ok: true,
            faceCenterX: faceMetrics.faceCenterX,
            faceCenterY: faceMetrics.faceCenterY,
            faceBox: FaceBox(
                left: faceMetrics.faceBox.left,
                top: faceMetrics.faceBox.top,
                right: faceMetrics.faceBox.right,
                bottom: faceMetrics.faceBox.bottom
            ),
            headEulerY: faceMetrics.headEulerY
        )
    }
}
