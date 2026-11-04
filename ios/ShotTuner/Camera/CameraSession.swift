// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/CameraSession.swift —— T12 · AVCaptureSession 取景 + 设备能力探测。
//
// 这个类的职责边界（刻意收窄，接线由 ContentView / T13 负责）：
//   ① 起一条 AVCaptureSession：一个视频输入 + `.videoDataOutput`（CVPixelBuffer）+ `.photoOutput`；
//   ② 把 AVFoundation 运行时探测到的能力**映射成 commonMain 的数据结构**
//      （`DeviceProfile` / `LensInfo` / `VideoCaps` / `CameraCaps`），不另建 Swift 版模型；
//   ③ 把「当前 activeFormat 的 ISO / 快门能力」按**每次现场重读**的口径交给上层。
//
// ★★ 本文件最重要的一条：`CameraCaps` 必须动态重读（architect-2 查证，见
//   docs/IOS-ARCH.md §770 / docs/IOS-TECH-FEASIBILITY.md §2.2）。
//   Android 的 `SENSOR_INFO_SENSITIVITY_RANGE` / `SENSOR_INFO_EXPOSURE_TIME_RANGE`
//   是**传感器级固定值**，开机读一次缓存起来没问题；
//   iOS 的 `minISO/maxISO/minExposureDuration/maxExposureDuration` 是
//   **`AVCaptureDevice.activeFormat` 级**的，切分辨率、切帧率、切 format 都会变。
//   ⇒ 缓存首次值 = 参数建议会给出当前 format 下根本不可执行的 ISO/快门。
//   本文件的做法见 `currentCaps()` / `refreshCapabilities(force:)` 的注释。
//
// ★ 像素格式硬约束：`kCVPixelFormatType_420YpCbCr8BiPlanarFullRange`（**FullRange**）。
//   plane 0 就是 Y（亮度），与 Android `YUV_420_888` 的 plane 0 语义一致。
//   用 VideoRange 的话 Y 是 16–235 而不是 0–255，直方图与高光/死黑阈值全部要重标
//   （docs/IOS-TECH-FEASIBILITY.md §2.1）。
//
// ★ 线程口径：`startRunning()` / `lockForConfiguration()` / 能力读取都在
//   `sessionQueue`（后台串行）上；视频帧回调在 `videoQueue`（后台串行）上。
//   只有「给用户看的错误」才回主线程，主线程不做任何重活。
//
// ★ 口径铁律：这里产出的只是**参考分**与**建议值**，教用户「照拨」，
//   不替用户调。零网络、零第三方依赖、不签名、不加麦克风、不加 App Extension。

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import Shared

/// `AVCaptureSession` 封装：取景 + 拍照 + 设备能力探测。
///
/// 用法（接线方在 T13 负责）：
/// ```swift
/// let camera = CameraSession()
/// camera.onFrame = { pixelBuffer in /* 后台队列，别在这里碰 UI */ }
/// camera.onCapabilitiesChanged = { caps in /* 后台队列 */ }
/// camera.start()
/// let layer = camera.makePreviewLayer()   // UIViewRepresentable 里挂上去
/// ```
final class CameraSession: NSObject {

    // MARK: - 配置

    /// 可调参数。默认值都是「先让取景尽快出画面」的取值，不是画质最优值。
    struct Configuration {
        /// 取景分析用的目标长边（像素）。
        ///
        /// ★ 首帧 < 1.5s 的关键：Analysis 帧根本不需要 4K。
        ///   选一个 <= 1280 的 format 能让 sensor 与 ISP 用轻档起流，
        ///   同时 `FrameStatsCore` 的采样网格（目标 160×120）在 1280 宽上已经绰绰有余。
        var preferredLongEdge: Int = 1280

        /// 目标帧率（fps）。
        var preferredFrameRate: Int = 30

        /// 视频帧的像素格式。★ 必须是 FullRange，见文件头。
        var pixelFormat: OSType = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange

        /// 兜底重读能力的帧间隔（30fps 下约 1 秒一次）。
        ///
        /// 为什么还要兜底：系统可能在我们不知情时切 `activeFormat`
        /// （加 photoOutput、来电话、多摄切换、低功耗策略等），
        /// `activeFormat` 并不保证 KVO-compliant。每 N 帧现场读一次，
        /// 就能保证「外部改了 format 也不会一直用旧 caps」。
        var capsRefreshFrameInterval: Int = 30

        /// 构造默认配置。
        init() {}
    }

    /// 失败原因（分类而不是 Throwable：UI 要的是「该怎么办」，不是异常栈）。
    enum Failure: Error {
        /// 相机权限被拒 / 被限制。
        case permissionDenied
        /// 找不到后置摄像头。
        case noBackCamera
        /// 建 `AVCaptureDeviceInput` 失败。
        case cannotCreateInput(underlying: Error)
        /// session 收不下视频输出。
        case cannotAddVideoDataOutput
        /// session 收不下拍照输出。
        case cannotAddPhotoOutput
        /// `lockForConfiguration()` 失败。
        case cannotLockDevice(underlying: Error)
        /// 目标帧率不在该 format 的 `videoSupportedFrameRateRanges` 里。
        case unsupportedFrameRate(Int)
        /// 拍照失败。
        case photoCapture(underlying: Error)
    }

    /// 从 `activeFormat` 现场读出来的能力原始值。
    ///
    /// ★ 这是一个 **Swift 侧的 Equatable 值**，`refreshCapabilities` 只拿它做
    ///   「这次和上次通知出去的值是否相同」的去重 —— **它不是能力缓存**。
    ///   能力值本身每次都从 `device.activeFormat` 重新读，绝不存下来当答案。
    struct RawCaps: Equatable {
        var isoMin: Int32 = 50
        var isoMax: Int32 = 6400
        var exposureMinNs: Int64 = 20_000
        var exposureMaxNs: Int64 = 500_000_000
        var supportsManualSensor: Bool = false
        var supportsManualWb: Bool = false
        var focalLengthMm: Float = 0
        /// `activeFormat` 的指纹（分辨率 / 帧率 / ISO / 快门 / 当前帧时长）。
        /// 只要 format 或帧率变了，这里就变 ⇒ 强制重读并通知。
        var formatKey: String = ""
    }

    // MARK: - 对外状态与回调

    /// 会话本体。接线方用 `makePreviewLayer()` 拿预览层；不要自己改 session 配置。
    let session = AVCaptureSession()

    /// 一帧画面（`CVPixelBuffer`）。
    /// ★ 在 `videoQueue`（后台串行）回调，**不要在这里更新 UI**。
    var onFrame: ((CVPixelBuffer) -> Void)? {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _onFrame
        }
        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _onFrame = newValue
        }
    }

    /// 能力变化（切 format / 切帧率 / 设备重连 / 兜底发现变化）。
    /// ★ 在后台队列回调；参数就是**刚现场重读**出来的 `CameraCaps`。
    var onCapabilitiesChanged: ((CameraCaps) -> Void)? {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _onCapabilitiesChanged
        }
        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _onCapabilitiesChanged = newValue
        }
    }

    /// 失败（主线程回调，UI 可以直接弹条）。
    var onError: ((Failure) -> Void)? {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _onError
        }
        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _onError = newValue
        }
    }

    /// 首帧延迟（秒）。`nil` 表示还没出第一帧。
    /// ★ 验收②「取景首帧 < 1.5s」的可观测点：起流时记时，首帧回调时结算。
    var firstFrameLatencySeconds: TimeInterval? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return _firstFrameLatencySeconds
    }

    // MARK: - 私有状态

    private let sessionQueue = DispatchQueue(
        label: "com.shottuner.camera.session",
        qos: .userInitiated
    )
    private let videoQueue = DispatchQueue(
        label: "com.shottuner.camera.video",
        qos: .userInitiated
    )

    /// 保护回调 / 首帧计时 / 拍照 delegate 持有表。
    private let stateLock = NSLock()
    /// 单独保护 `lastPublishedRawCaps`，避免与 `stateLock` 嵌套。
    private let capsLock = NSLock()

    private let configuration: Configuration

    private var device: AVCaptureDevice?
    private var videoDataOutput: AVCaptureVideoDataOutput?
    private var photoOutput: AVCapturePhotoOutput?

    private var isConfigured = false
    private var isRunning = false
    private var startTime: CFAbsoluteTime = 0
    private var frameIndex: UInt64 = 0

    /// ★ 只存「上一次已经通知出去的值」，用于去重；**不是能力来源**。
    private var lastPublishedRawCaps: RawCaps?

    private var _onFrame: ((CVPixelBuffer) -> Void)?
    private var _onCapabilitiesChanged: ((CameraCaps) -> Void)?
    private var _onError: ((Failure) -> Void)?
    private var _firstFrameLatencySeconds: TimeInterval?
    private var photoCaptures: [PhotoCapture] = []
    private var notificationTokens: [NSObjectProtocol] = []

    /// ★ 全工程只有这一处写 `Brand.xxx`（与 `VisionSceneMap.Scenes` 同一个收窄做法）。
    /// common 的 `Brand` 枚举没有 APPLE 条目（它是 Android 厂商菜单指路用的），
    /// iOS 侧填 `OTHER` 只是拿一个合法枚举值，iOS UI 不使用 `BrandGuide` 的文案。
    /// 万一真机编译发现导出名是 `Brand.OTHER`，只改这一行。
    private enum CommonBrand {
        static let other = Brand.other
    }

    // MARK: - 初始化

    /// 构造会话。
    /// - Parameter configuration: 可调参数（默认取「首帧优先」的配置）。
    init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
        super.init()
    }

    deinit {
        for token in notificationTokens {
            NotificationCenter.default.removeObserver(token)
        }
    }

    // MARK: - 生命周期

    /// 申请权限并起流。
    ///
    /// ★ 权限判定与 `startRunning()` 都不在主线程：
    ///   `requestAccess` 的 completion 队列由系统决定，我们立刻转回 `sessionQueue`。
    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            sessionQueue.async { [weak self] in
                self?.configureAndStart()
            }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.sessionQueue.async {
                        self.configureAndStart()
                    }
                } else {
                    self.publishError(.permissionDenied)
                }
            }
        case .denied, .restricted:
            publishError(.permissionDenied)
        @unknown default:
            publishError(.permissionDenied)
        }
    }

    /// 停流。
    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.isRunning = false
        }
    }

    /// 会话是否正在跑。
    var isSessionRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isRunning
    }

    /// 给 SwiftUI 的 `UIViewRepresentable` 用的预览层。
    ///
    /// ★ 只返回 layer，不碰视图层级（接线由主理人统一做）。
    /// - Returns: 绑定了本 `session` 的预览层。
    func makePreviewLayer() -> AVCaptureVideoPreviewLayer {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        return layer
    }

    // MARK: - 当前能力（★★ 动态重读）

    /// **当前** `activeFormat` 下的相机能力。
    ///
    /// ★★ 这是本任务验收④的核心：**每次调用都现场读 `device.activeFormat`**，
    /// 类里没有任何「当前 caps」字段。切分辨率 / 切帧率 / 切 format 之后，
    /// 调用方拿到的立刻是新值 —— 不存在「缓存首次值」这回事。
    ///
    /// - Returns: common 的 `CameraCaps`（不是 Swift 自定义结构）。
    func currentCaps() -> CameraCaps {
        makeCameraCaps(readRawCaps())
    }

    /// 当前设备档案（common 的 `DeviceProfile`，字段一一对应）。
    ///
    /// 映射表：
    /// | `DeviceProfile` 字段 | iOS 来源 |
    /// |---|---|
    /// | `brand` | `Brand.other`（common 无 APPLE 条目，见 `CommonBrand`） |
    /// | `modelName` / `rawModel` | `AVCaptureDevice.localizedName` / `.modelID` |
    /// | `osLabel` | `ProcessInfo.operatingSystemVersionString` |
    /// | `androidRelease` / `androidSdk` | Android 专属字段，填中性值 `"iOS"` / `0` |
    /// | `lenses` / `main` | `DiscoverySession` 枚举后置镜头 + `activeFormat` 尺寸 |
    /// | `caps` | `currentCaps()`（★ 现场重读） |
    /// | `video` | `device.formats` 尺寸 + 各 format 的 `videoSupportedFrameRateRanges` |
    /// | `hasFlash` | `AVCaptureDevice.hasFlash` |
    /// | `isMultiCamera` | 后置镜头枚举结果 > 1 |
    /// | `supportsManualPhoto` | `isExposureModeSupported(.custom)` |
    /// | `supportsManualVideo` | 同上（iOS 是设备级锁定，预览/成片/录像同一套） |
    ///
    /// - Returns: 现场探测的设备档案。
    func currentDeviceProfile() -> DeviceProfile {
        let lenses = readLenses()
        let mainLens = lenses.max { lhs, rhs in
            Int64(lhs.pixelWidth) * Int64(lhs.pixelHeight) <
                Int64(rhs.pixelWidth) * Int64(rhs.pixelHeight)
        }
        let currentDevice = device
        let manual = currentDevice?.isExposureModeSupported(.custom) ?? false
        let osVersion = ProcessInfo.processInfo.operatingSystemVersionString

        return DeviceProfile(
            brand: CommonBrand.other,
            modelName: currentDevice?.localizedName ?? "未知相机",
            rawModel: currentDevice?.modelID ?? "unknown",
            osLabel: "iOS \(osVersion)",
            androidRelease: "iOS",
            androidSdk: 0,
            lenses: lenses,
            main: mainLens,
            caps: currentCaps(),
            video: readVideoCaps(),
            hasFlash: currentDevice?.hasFlash ?? false,
            isMultiCamera: lenses.count > 1,
            supportsManualPhoto: manual,
            supportsManualVideo: manual
        )
    }

    /// 设备当前支持的全部 format（供 UI 做分辨率/帧率选择）。
    /// - Returns: `AVCaptureDevice.formats` 原样返回；设备没就绪时返回空数组。
    func availableFormats() -> [AVCaptureDevice.Format] {
        device?.formats ?? []
    }

    /// 切换分辨率 / 帧率 / format，**并在切换后立刻重读能力**。
    ///
    /// ★★ 这是「动态重读」的主路径：`activeFormat` 一变，
    /// `minISO/maxISO/minExposureDuration/maxExposureDuration` 就可能变，
    /// 所以 lock 一放就 `refreshCapabilities(force: true)`，不等下一帧、不沿用旧值。
    ///
    /// - Parameters:
    ///   - format: 目标 `AVCaptureDevice.Format`。
    ///   - frameRate: 目标帧率（必须落在该 format 的 `videoSupportedFrameRateRanges` 内）。
    ///   - completion: 在后台队列回调，成功时带回**新 format 下**的 `CameraCaps`。
    func apply(
        format: AVCaptureDevice.Format,
        frameRate: Int,
        completion: @escaping (Result<CameraCaps, Failure>) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let currentDevice = self.device else {
                completion(.failure(.noBackCamera))
                return
            }
            let supported = format.videoSupportedFrameRateRanges.contains {
                $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate)
            }
            guard supported else {
                completion(.failure(.unsupportedFrameRate(frameRate)))
                return
            }

            do {
                try currentDevice.lockForConfiguration()
            } catch {
                completion(.failure(.cannotLockDevice(underlying: error)))
                return
            }
            defer { currentDevice.unlockForConfiguration() }

            currentDevice.activeFormat = format
            let duration = CMTime(value: 1, timescale: CMTimeScale(frameRate))
            currentDevice.activeVideoMinFrameDuration = duration
            currentDevice.activeVideoMaxFrameDuration = duration

            // ★★ 重读点①：切 format / 帧率之后立刻读，绝不缓存首次值。
            self.refreshCapabilities(force: true)
            completion(.success(self.currentCaps()))
        }
    }

    /// 只改帧率（不改 format）。帧率同样可能改变可曝光时长上限，所以也要重读。
    /// - Parameters:
    ///   - frameRate: 目标帧率。
    ///   - completion: 后台队列回调。
    func applyFrameRate(
        _ frameRate: Int,
        completion: @escaping (Result<CameraCaps, Failure>) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let currentDevice = self.device else {
                completion(.failure(.noBackCamera))
                return
            }
            let format = currentDevice.activeFormat
            guard format.videoSupportedFrameRateRanges.contains({
                $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate)
            }) else {
                completion(.failure(.unsupportedFrameRate(frameRate)))
                return
            }

            do {
                try currentDevice.lockForConfiguration()
            } catch {
                completion(.failure(.cannotLockDevice(underlying: error)))
                return
            }
            defer { currentDevice.unlockForConfiguration() }

            let duration = CMTime(value: 1, timescale: CMTimeScale(frameRate))
            currentDevice.activeVideoMinFrameDuration = duration
            currentDevice.activeVideoMaxFrameDuration = duration

            // ★★ 重读点②：帧率变了也重读。
            self.refreshCapabilities(force: true)
            completion(.success(self.currentCaps()))
        }
    }

    /// 写手动曝光（与 Android 的 `CONTROL_AE_MODE_OFF + SENSOR_*` 等价）。
    ///
    /// ★ iOS 是**设备级锁定**：锁一次，预览 / 成片 / 录像天然一致，
    ///   不需要像 Android 那样给 Preview 和 ImageCapture 各写一遍
    ///   （docs/IOS-TECH-FEASIBILITY.md §2.2）。
    /// ★ 口径：这是把「建议值」写进相机，教用户照拨，不是替用户做评测。
    ///
    /// - Parameters:
    ///   - iso: 建议感光度（会被系统钳到当前 `activeFormat` 的范围内）。
    ///   - shutterNs: 建议曝光时间，纳秒。`CMTime(value: ns, timescale: 1e9)` 精确无损。
    ///   - completion: 主线程回调，带回可能的错误。
    func setManualExposure(
        iso: Float,
        shutterNs: Int64,
        completion: @escaping (Error?) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let currentDevice = self.device else {
                DispatchQueue.main.async { completion(Failure.noBackCamera) }
                return
            }
            guard currentDevice.isExposureModeSupported(.custom) else {
                DispatchQueue.main.async { completion(Failure.cannotLockDevice(
                    underlying: ManualExposureError.customModeUnsupported
                )) }
                return
            }
            do {
                try currentDevice.lockForConfiguration()
            } catch {
                DispatchQueue.main.async { completion(error) }
                return
            }

            let duration = CMTime(value: shutterNs, timescale: 1_000_000_000)
            currentDevice.setExposureModeCustom(duration: duration, iso: iso) { [weak self] _ in
                currentDevice.unlockForConfiguration()
                // ★★ 重读点③：曝光模式切换后也重读一次（宁可多读，不可拿旧值）。
                self?.refreshCapabilities(force: true)
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    /// 交还给相机自动曝光。
    /// - Parameter completion: 主线程回调。
    func resumeAutoExposure(completion: @escaping (Error?) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let currentDevice = self.device else {
                DispatchQueue.main.async { completion(Failure.noBackCamera) }
                return
            }
            do {
                try currentDevice.lockForConfiguration()
            } catch {
                DispatchQueue.main.async { completion(error) }
                return
            }
            currentDevice.exposureMode = .continuousAutoExposure
            currentDevice.unlockForConfiguration()
            self.refreshCapabilities(force: true)
            DispatchQueue.main.async { completion(nil) }
        }
    }

    /// 手动曝光失败的子原因。
    enum ManualExposureError: Error {
        /// 该设备 / 该 format 不支持 `.custom` 曝光模式。
        case customModeUnsupported
    }

    // MARK: - 拍照

    /// 拍一张照片并落盘到指定 URL。
    /// - Parameters:
    ///   - url: 目标文件（父目录需已存在）。
    ///   - completion: **主线程**回调。
    func capturePhoto(to url: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let output = self.photoOutput else {
                DispatchQueue.main.async { completion(.failure(Failure.cannotAddPhotoOutput)) }
                return
            }
            let capture = PhotoCapture(outputURL: url) { [weak self] result in
                // 释放 delegate（A/V 框架只在拍照期间弱引用它）。
                if let capture = self?.takeFinishedCapture(matching: url) {
                    _ = capture
                }
                DispatchQueue.main.async { completion(result) }
            }
            self.stateLock.lock()
            self.photoCaptures.append(capture)
            self.stateLock.unlock()
            output.capturePhoto(with: AVCapturePhotoSettings(), delegate: capture)
        }
    }

    /// 从持有表里摘掉已完成的 delegate。
    /// - Parameter url: 该次拍照的输出 URL。
    /// - Returns: 被摘掉的 delegate（没有则 nil）。
    private func takeFinishedCapture(matching url: URL) -> PhotoCapture? {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard let index = photoCaptures.firstIndex(where: { $0.outputURL == url }) else {
            return nil
        }
        return photoCaptures.remove(at: index)
    }

    // MARK: - 配置与起流

    private func configureAndStart() {
        if !isConfigured {
            do {
                try configure()
                isConfigured = true
            } catch let failure as Failure {
                publishError(failure)
                return
            } catch {
                publishError(.cannotCreateInput(underlying: error))
                return
            }
        }

        if let currentDevice = device {
            applyPreferredFormat(currentDevice)
        }
        installNotifications()

        // ★★ 重读点④：起流后先把当前 activeFormat 的能力报一次。
        //    这一份是「现在的值」，不是「开机时的值」。
        refreshCapabilities(force: true)

        startTime = CFAbsoluteTimeGetCurrent()
        session.startRunning()
        stateLock.lock()
        isRunning = session.isRunning
        stateLock.unlock()
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        // `.inputPriority`：把分辨率决定权交给下面显式设置的 `activeFormat`，
        // 否则 sessionPreset 会在 commit 时把 format 覆盖掉。
        session.sessionPreset = .inputPriority

        guard let backCamera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) ?? AVCaptureDevice.default(for: .video) else {
            throw Failure.noBackCamera
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: backCamera)
        } catch {
            throw Failure.cannotCreateInput(underlying: error)
        }
        guard session.canAddInput(input) else {
            throw Failure.cannotCreateInput(
                underlying: ManualExposureError.customModeUnsupported
            )
        }
        session.addInput(input)
        device = backCamera

        let videoOutput = AVCaptureVideoDataOutput()
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: NSNumber(value: configuration.pixelFormat)
        ]
        // 分析流水线处理不过来时丢旧帧，不排队（与 Android 的
        // STRATEGY_KEEP_ONLY_LATEST 同一策略）。
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(videoOutput) else {
            throw Failure.cannotAddVideoDataOutput
        }
        session.addOutput(videoOutput)
        videoDataOutput = videoOutput

        let photo = AVCapturePhotoOutput()
        guard session.canAddOutput(photo) else {
            throw Failure.cannotAddPhotoOutput
        }
        session.addOutput(photo)
        photoOutput = photo
    }

    /// 在「首帧快」的预算内挑一个最大的 format，并把帧率定住。
    private func applyPreferredFormat(_ currentDevice: AVCaptureDevice) {
        let targetFps = Double(max(1, configuration.preferredFrameRate))
        let candidates = currentDevice.formats.filter { format in
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let longEdge = max(Int(dims.width), Int(dims.height))
            let fpsOK = format.videoSupportedFrameRateRanges.contains {
                $0.maxFrameRate >= targetFps && $0.minFrameRate <= targetFps
            }
            return longEdge <= configuration.preferredLongEdge && fpsOK
        }
        let chosen = candidates.max { lhs, rhs in
            pixelArea(lhs.formatDescription) < pixelArea(rhs.formatDescription)
        }

        do {
            try currentDevice.lockForConfiguration()
        } catch {
            // 拿不到锁就用系统当前 format 起流（取景优先），不阻塞首帧。
            return
        }
        defer { currentDevice.unlockForConfiguration() }

        if let chosen {
            currentDevice.activeFormat = chosen
            let duration = CMTime(value: 1, timescale: CMTimeScale(configuration.preferredFrameRate))
            currentDevice.activeVideoMinFrameDuration = duration
            currentDevice.activeVideoMaxFrameDuration = duration
        }
        // 起流后 configureAndStart 会立刻 refreshCapabilities(force: true)，
        // 所以这里不重复通知 —— 但它同样**不缓存**任何能力值。
    }

    private func pixelArea(_ description: CMVideoFormatDescription) -> Int64 {
        let dims = CMVideoFormatDescriptionGetDimensions(description)
        return Int64(dims.width) * Int64(dims.height)
    }

    // MARK: - 通知：format 可能被系统自己改掉

    private func installNotifications() {
        guard notificationTokens.isEmpty else { return }
        // ★ 用稳定的通知名字符串，不依赖某个 SDK 版本才有的静态常量拼写。
        let names = [
            "AVCaptureSessionDidStartRunningNotification",
            "AVCaptureSessionRuntimeErrorNotification",
            "AVCaptureDeviceWasConnectedNotification",
            "AVCaptureDeviceWasDisconnectedNotification"
        ]
        for name in names {
            let token = NotificationCenter.default.addObserver(
                forName: Notification.Name(name),
                object: nil,
                queue: nil
            ) { [weak self] _ in
                guard let self else { return }
                self.sessionQueue.async {
                    // ★★ 重读点⑤：系统事件后强制重读，防止「外部改了 activeFormat」。
                    self.refreshCapabilities(force: true)
                }
            }
            notificationTokens.append(token)
        }
    }

    // MARK: - ★★ 能力重读的实现

    /// 现场读一次 `activeFormat` 的能力原始值。
    ///
    /// ★ 这个函数**没有缓存、没有 memo、没有早退**：
    ///   每次都从 `device.activeFormat` 取 `minISO` / `maxISO` /
    ///   `minExposureDuration` / `maxExposureDuration`，
    ///   并把当前帧时长也塞进 `formatKey`（帧率变化同样能被检测到）。
    private func readRawCaps() -> RawCaps {
        guard let currentDevice = device else {
            // 与 common 的 `CameraCaps.UNKNOWN` 同口径（保守默认，不猜）。
            return RawCaps()
        }
        let format = currentDevice.activeFormat
        var raw = RawCaps()
        raw.isoMin = Int32(max(1, format.minISO.rounded()))
        raw.isoMax = Int32(max(raw.isoMin, format.maxISO.rounded()))
        raw.exposureMinNs = nanoseconds(format.minExposureDuration)
        raw.exposureMaxNs = max(raw.exposureMinNs, nanoseconds(format.maxExposureDuration))
        raw.supportsManualSensor = currentDevice.isExposureModeSupported(.custom)
        // Android 侧 `supportsManualWb` 硬编码 false（手动 RGB 增益跨机型偏色），
        // iOS 侧同样不做手动白平衡 ⇒ 零差距。
        raw.supportsManualWb = false
        // iOS 的 activeFormat 不暴露物理焦距（docs/IOS-TECH-FEASIBILITY.md §2.1）。
        // common 约定 0 = 未知，就填 0，不拿 videoZoomFactor 去「近似」一个假值。
        raw.focalLengthMm = 0
        raw.formatKey = formatKey(format, device: currentDevice)
        return raw
    }

    /// 把 `CMTime` 换成纳秒（与 Android 的 `SENSOR_EXPOSURE_TIME` 单位一致）。
    private func nanoseconds(_ time: CMTime) -> Int64 {
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int64((seconds * 1_000_000_000).rounded())
    }

    /// format 指纹：分辨率 + 支持的帧率区间 + ISO + 快门 + **当前帧时长**。
    private func formatKey(_ format: AVCaptureDevice.Format, device: AVCaptureDevice) -> String {
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let fps = format.videoSupportedFrameRateRanges
            .map { "\($0.minFrameRate)-\($0.maxFrameRate)" }
            .joined(separator: ",")
        let activeMin = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
        let activeMax = CMTimeGetSeconds(device.activeVideoMaxFrameDuration)
        return "\(dims.width)x\(dims.height)|fps:\(fps)|iso:\(format.minISO)-\(format.maxISO)" +
            "|exp:\(CMTimeGetSeconds(format.minExposureDuration))-\(CMTimeGetSeconds(format.maxExposureDuration))" +
            "|active:\(activeMin)-\(activeMax)"
    }

    /// 重读并（在变化时）通知上层。
    ///
    /// ★★ 动态重读的完整语义：
    ///   1. `readRawCaps()` **现场读**，不查缓存；
    ///   2. 与 `lastPublishedRawCaps` 比较，只用于「要不要再通知一次」的去重；
    ///      —— 缓存的是**通知记录**，不是能力值；
    ///   3. 构造的是 common 的 `CameraCaps`，调用方拿到的永远是刚读出来的值。
    ///
    /// - Parameter force: 已知发生了 format/帧率切换时传 `true`，无条件通知。
    func refreshCapabilities(force: Bool = false) {
        let raw = readRawCaps()
        capsLock.lock()
        let changed = force || lastPublishedRawCaps != raw
        if changed {
            lastPublishedRawCaps = raw
        }
        capsLock.unlock()
        guard changed else { return }
        onCapabilitiesChanged?(makeCameraCaps(raw))
    }

    private func makeCameraCaps(_ raw: RawCaps) -> CameraCaps {
        CameraCaps(
            isoMin: raw.isoMin,
            isoMax: raw.isoMax,
            exposureMinNs: raw.exposureMinNs,
            exposureMaxNs: raw.exposureMaxNs,
            supportsManualSensor: raw.supportsManualSensor,
            supportsManualWb: raw.supportsManualWb,
            focalLengthMm: raw.focalLengthMm
        )
    }

    // MARK: - DeviceProfile 的字段映射

    /// `device.formats` + 每个 format 的 `videoSupportedFrameRateRanges` → `VideoCaps`。
    ///
    /// ★ 档位划分**照抄 common 的 `VideoCaps.maxLabel`**（按百万像素）：
    ///   >=30MP → 8K；>=8MP → 4K；>=2MP → 1080P；其余 → 720P。
    ///   这样两端的「哪一档」是同一个口径，不会出现 iOS 归 4K、Android 归 1080P。
    private func readVideoCaps() -> VideoCaps {
        guard let currentDevice = device else {
            return VideoCaps(
                maxWidth: 0, maxHeight: 0,
                fps720: 0, fps1080: 0, fps4k: 0, fps8k: 0,
                maxHighSpeedFps: 0,
                hasOis: false, hasEis: false,
                supports10Bit: false, supportsRaw: false
            )
        }

        var maxWidth: Int32 = 0
        var maxHeight: Int32 = 0
        var fps720: Int32 = 0
        var fps1080: Int32 = 0
        var fps4k: Int32 = 0
        var fps8k: Int32 = 0
        var maxHighSpeedFps: Int32 = 0
        var maxArea: Int64 = 0

        for format in currentDevice.formats {
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let area = Int64(dims.width) * Int64(dims.height)
            if area > maxArea {
                maxArea = area
                maxWidth = dims.width
                maxHeight = dims.height
            }
            let topFps = format.videoSupportedFrameRateRanges.reduce(0.0) {
                max($0, $1.maxFrameRate)
            }
            let fps = Int32(topFps.rounded())
            let megapixels = Double(area) / 1_000_000.0
            if megapixels >= 30 {
                fps8k = max(fps8k, fps)
            } else if megapixels >= 8 {
                fps4k = max(fps4k, fps)
            } else if megapixels >= 2 {
                fps1080 = max(fps1080, fps)
            } else {
                fps720 = max(fps720, fps)
            }
            if topFps > 60 {
                maxHighSpeedFps = max(maxHighSpeedFps, Int32(topFps.rounded()))
            }
        }

        let eis = videoDataOutput?.connection(with: .video)?
            .isVideoStabilizationSupported ?? false
        return VideoCaps(
            maxWidth: maxWidth,
            maxHeight: maxHeight,
            fps720: fps720,
            fps1080: fps1080,
            fps4k: fps4k,
            fps8k: fps8k,
            maxHighSpeedFps: maxHighSpeedFps,
            // iOS 的 `activeFormat` 没有可靠的 OIS 标志位；
            // 与 OIS / 10bit / RAW 一样按「unknown」处理，不猜。
            hasOis: false,
            hasEis: eis,
            supports10Bit: false,
            supportsRaw: false
        )
    }

    /// `DiscoverySession` 枚举后置镜头 → `[LensInfo]`。
    ///
    /// iOS 不暴露传感器物理尺寸与物理焦距，所以 `equivFocalMm` / `focalMm` /
    /// `sensorWidthMm` / `sensorHeightMm` 按 common 的「0 = 未知」填 0；
    /// `aperture` 用真实的 `AVCaptureDevice.lensAperture`；
    /// `pixelWidth/Height` 用该镜头 `activeFormat` 的真实尺寸。
    private func readLenses() -> [LensInfo] {
        let types: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera,
            .builtInDualCamera,
            .builtInTripleCamera
        ]
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: types,
            mediaType: .video,
            position: .back
        )
        return discovery.devices.map { camera in
            let dims = CMVideoFormatDescriptionGetDimensions(
                camera.activeFormat.formatDescription
            )
            return LensInfo(
                id: camera.uniqueID,
                equivFocalMm: 0,
                focalMm: 0,
                aperture: camera.lensAperture,
                sensorWidthMm: 0,
                sensorHeightMm: 0,
                pixelWidth: dims.width,
                pixelHeight: dims.height,
                hasOis: false,
                isBack: camera.position == .back
            )
        }
    }

    // MARK: - 错误出口

    private func publishError(_ failure: Failure) {
        DispatchQueue.main.async { [weak self] in
            self?.onError?(failure)
        }
    }
}

// MARK: - 视频帧回调

extension CameraSession: AVCaptureVideoDataOutputSampleBufferDelegate {

    /// 每帧回调（**在 `videoQueue` 上**，不在主线程）。
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        stateLock.lock()
        let isFirstFrame = (frameIndex == 0)
        if isFirstFrame && startTime > 0 {
            _firstFrameLatencySeconds = CFAbsoluteTimeGetCurrent() - startTime
        }
        let index = frameIndex
        frameIndex += 1
        stateLock.unlock()

        // ★★ 重读点⑥（兜底）：系统在我们不知情时改了 activeFormat 也能发现。
        //    节流到约 1Hz，读几个属性而已，不会拖慢取景。
        let interval = UInt64(max(1, configuration.capsRefreshFrameInterval))
        if index > 0 && index % interval == 0 {
            refreshCapabilities()
        }

        onFrame?(pixelBuffer)
        _ = isFirstFrame
    }
}

// MARK: - 拍照 delegate（需要被强持有到回调完成）

private final class PhotoCapture: NSObject, AVCapturePhotoCaptureDelegate {

    /// 照片数据为空。
    enum PhotoError: Error {
        /// `fileDataRepresentation()` 返回 nil。
        case emptyData
    }

    let outputURL: URL
    let completion: (Result<URL, Error>) -> Void

    init(outputURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        self.outputURL = outputURL
        self.completion = completion
        super.init()
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error {
            completion(.failure(error))
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            completion(.failure(PhotoError.emptyData))
            return
        }
        do {
            try data.write(to: outputURL, options: .atomic)
            completion(.success(outputURL))
        } catch {
            completion(.failure(error))
        }
    }
}
