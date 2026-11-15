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
// ★ 口径铁律：这里产出的只是**参考分**与**建议值**，教用户「照拨」。
//   `setManualExposure` 存在，但**只有用户显式打开「照拨」开关时**上层才会调它
//   （见 `ExposureAssist`）—— 默认永远是相机自动曝光，App 不替用户做决定。
//   零网络、零第三方依赖、不签名、不加麦克风、不加 App Extension。

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
        /// 找不到前置摄像头（模拟器 / 少数 iPad 前面的那颗被系统占用）。
        ///
        /// ★ 与 `noBackCamera` 分开，是因为 UI 的两句话不一样：
        ///   「这台设备没有前置摄像头」和「找不到后置摄像头」是两种不同的坏消息，
        ///   合成一条会让用户在「切前置失败」时看到「后置摄像头不可用」，纯误导。
        case noFrontCamera
        /// 建 `AVCaptureDeviceInput` 失败。
        case cannotCreateInput(underlying: Error)
        /// session 收不下相机输入。
        case cannotAddInput
        /// session 收不下视频输出。
        case cannotAddVideoDataOutput
        /// session 收不下拍照输出。
        case cannotAddPhotoOutput
        /// `lockForConfiguration()` 失败。
        case cannotLockDevice(underlying: Error)
        /// 目标帧率不在该 format 的 `videoSupportedFrameRateRanges` 里。
        case unsupportedFrameRate(Int)
        /// 该设备 / 该 format 不支持 `.custom` 曝光模式。
        case customExposureUnsupported
        /// 曝光已被「照拨」锁在 `.custom`，此时不允许切 format / 帧率。
        ///
        /// ★ 为什么是**拒绝**而不是「悄悄帮你恢复自动再切」：
        ///   后者会让「开关还亮着、相机已经是自动档」这个状态成立，而它没有任何
        ///   界面线索 —— 用户以为自己在照拨，其实相机自己在动。这在本仓是明令禁止的
        ///   那类**静默失效**。要切格式就先把开关关掉，这是调用方一行代码的事。
        case exposureLocked
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

    /// 每帧的**旁路**消费者（可为空）。给「搭车看帧」的模块用，目前只有 Vision 场景识别。
    ///
    /// ★ 为什么要有第二个槽，而不是让大家都挂 `onFrame`：
    ///   `onFrame` 是**单槽**，赋值即覆盖。`ScoreCoordinator` 已经占着它（它在文件头
    ///   专门写了一段警告：谁再赋值就会**静默杀死**评分链 —— 不崩不报错，只是帧不来了）。
    ///   与其去改那个被警告过的东西，不如在**投递点**多开一个显式命名的旁路槽：
    ///   两个消费者各占一个属性，谁也覆盖不了谁。
    ///
    /// ★ 为什么不做成数组广播：本 App 固定只有这两个消费者。数组会引入
    ///   「谁先谁后」「谁持有强引用」「移除时按索引还是按身份」这些不必要的问题。
    ///   真出现第三个消费者时再改，那时也有三处调用可以一起核。
    ///
    /// ★ 实现方**必须立刻返回**：这个回调和 `onFrame` 一样跑在 `videoQueue` 上，
    ///   阻塞它就是阻塞整条取景链路。耗时工作（Vision、编码、落盘）请自己扔到别的队列。
    var onFrameTap: ((CVPixelBuffer) -> Void)? {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _onFrameTap
        }
        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _onFrameTap = newValue
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

    /// `makePreviewLayer()` 造出来的那一层（弱引用，视图自己持有它）。
    ///
    /// ★ 为什么要留一个引用：镜像策略必须在**当前**那条 connection 上设置，
    ///   而每次换 input（前置 ↔ 后摄）`commitConfiguration()` 都会重建 connection，
    ///   旧 connection 上设的值全部作废。没有这个引用就只能等下一帧再从
    ///   `videoDataOutput` 上抠 —— 而预览层的 connection 在 `videoDataOutput` 上取不到。
    private weak var previewLayer: AVCaptureVideoPreviewLayer?

    /// 当前是否前置摄像头。
    ///
    /// ★ 它是**事实**，不是意图：由 `configure()` / `toggleCamera()` 在真正换完 input
    ///   之后按 `device.position` 写。所以即使后摄被别的 App 占着、系统退给了前置，
    ///   这里也会诚实地是 `true`。
    private var isFrontFlag = false

    /// 曝光是否已被 `setManualExposure` 锁在 `.custom`。
    ///
    /// ★ 记它只为了在 `apply(format:...)` / `applyFrameRate(_:...)` 里**拒绝**
    ///   带锁切格式（见 `Failure.exposureLocked`）。
    /// ★ 它不是「相机状态的真值」，只是「本类最后一次写成功的是什么」——
    ///   系统在来电 / 中断后可能自己把曝光模式改回自动，那时这里会偏保守
    ///   （依然拒绝切格式）。偏保守的代价是调用方多关一次开关，偏激进则会
    ///   在未知状态下改 format，两种代价不对等。
    private var isExposureLocked = false

    /// 「镜像策略需要重设」的标志。
    ///
    /// ★ 为什么需要它：`commitConfiguration()` 会**重建** connection 对象，
    ///   而镜像设置挂在 connection 上。换镜头那一刻预览层的 connection 可能还没建好
    ///   （`applyMirroringPolicy()` 那次设了个空），于是前置预览就回到系统默认的
    ///   **镜像**状态 —— 而「预览不镜像」是本任务明写的验收条件。
    ///   ⇒ 置位之后由 `captureOutput` 在下一次出帧时消化（那时 connection 一定在）。
    private var mirroringDirty = true

    private var isConfigured = false
    private var isRunning = false
    private var startTime: CFAbsoluteTime = 0
    private var frameIndex: UInt64 = 0

    /// ★ 只存「上一次已经通知出去的值」，用于去重；**不是能力来源**。
    private var lastPublishedRawCaps: RawCaps?

    private var _onFrame: ((CVPixelBuffer) -> Void)?

    /// `onFrameTap` 的存储。与 `_onFrame` 同一把 `stateLock` 保护。
    private var _onFrameTap: ((CVPixelBuffer) -> Void)?
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
            self.stateLock.lock()
            self.isRunning = false
            self.stateLock.unlock()
        }
    }

    /// 会话是否正在跑。
    var isSessionRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isRunning
    }

    /// 当前是不是前置摄像头（只读；换镜头由 `toggleCamera` 负责）。
    ///
    /// ★ 没有 setter：`isFrontFlag` 只能由「真的换完 input」的那两处写，
    ///   否则界面会显示「前置」而相机还是后摄 —— 那种状态没有任何办法自愈。
    var isFront: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isFrontFlag
    }

    /// 曝光是否已被 `setManualExposure` 锁住（只读）。
    var isExposureLockedByAdvice: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isExposureLocked
    }

    /// 给 SwiftUI 的 `UIViewRepresentable` 用的预览层。
    ///
    /// ★ 只返回 layer，不碰视图层级（接线由主理人统一做）。
    ///
    /// ★★ **预览不镜像**（本任务明确要求「预览与成片都不镜像」）。
    ///   理由不是审美，是产品定位：这是个**构图教练**，屏幕上要给的是
    ///   「往左移一点」「地平线压 2°」这种**成片坐标**里的指令。
    ///   前置默认是镜像的（像照镜子），用户看着镜像把主体往左挪，
    ///   成片里主体却在右边 —— 教练的每一句话都会被镜像反着执行。
    ///   ⇒ 宁可让自拍预览「不像镜子」，也要让指导与成片同向。
    ///
    /// ★ 这里**必须显式设** `automaticallyAdjustsVideoMirroring = false`，
    ///   不能只写 `isVideoMirrored = false`：后者在自动镜像为真时会被系统覆盖
    ///   （前置默认自动镜像），写了个寂寞。两行的顺序也不可换 ——
    ///   自动镜像为真时设 `isVideoMirrored` 会抛异常。
    ///
    /// - Returns: 绑定了本 `session` 的预览层。
    func makePreviewLayer() -> AVCaptureVideoPreviewLayer {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        // ★ 预览层的 connection 在 session 还没起流时可能是 nil（系统在
        //   `commitConfiguration` 之后才建它）。所以这里设一次只是「顺手」，
        //   真正兜底的是 `applyMirroringPolicy()` 的其余调用点
        //   （换镜头后、首帧时、拍照前）。
        applyNoMirror(layer.connection)
        previewLayer = layer
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
    /// | `lenses` / `main` | 当前传感器（后摄枚举多颗 / 前置只给在用那颗） |
    /// | `caps` | `currentCaps()`（★ 现场重读） |
    /// | `video` | `device.formats` 尺寸 + 各 format 的 `videoSupportedFrameRateRanges` |
    /// | `hasFlash` | `AVCaptureDevice.hasFlash`（前置恒 false，由系统给） |
    /// | `isMultiCamera` | 镜头枚举结果 > 1（前置恒 false，见 `readLenses`） |
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
            // ★★ 与「照拨」的冲突点①：**带锁切 format 一律拒绝**。
            //
            //   为什么不在内部先 `resumeAutoExposure` 再切（那也能跑通）：
            //   那样调用方拿到的 `.success` 意味着「格式换了、曝光也变自动了」，
            //   而它无从得知后半句 —— 界面上「照拨」开关还亮着，相机已经自己动。
            //   **静默改变用户相机的状态**比直接报错难查得多（本仓的既有教训）。
            //   要切格式：先 `resumeAutoExposure` 或把开关关掉，一行代码的事。
            guard !self.isExposureLockedByAdvice else {
                completion(.failure(.exposureLocked))
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
            // ★★ 与「照拨」的冲突点②：帧率同理。帧率直接决定本 format 的
            //   `maxExposureDuration`，而手动快门的时长是按旧上限算出来的 ——
            //   先把 30fps 改成 60fps 再沿用 1/30s 的快门，就是一张黑帧。
            guard !self.isExposureLockedByAdvice else {
                completion(.failure(.exposureLocked))
                return
            }
            let format = currentDevice.activeFormat
            // ★ `where:` 标签不能省：`Sequence.contains` 的两个重载分别是
            //   `contains(_:)`（要求 Element: Equatable）与 `contains(where:)`。
            //   写 `contains({ ... })` 两个都匹配不上 —— 前者会对闭包做
            //   「`Element == (Range) -> Bool`？」的类型推断，推不出来就报
            //   「Missing argument label 'where:' in call」。
            //   本文件另两处（`:384`、`:642`）用的是尾随闭包 `contains { ... }`，
            //   那种形式才是省略标签的合法写法，这里在 `guard` 条件里不好用尾随闭包。
            guard format.videoSupportedFrameRateRanges.contains(where: {
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
                DispatchQueue.main.async { completion(Failure.customExposureUnsupported) }
                return
            }
            do {
                try currentDevice.lockForConfiguration()
            } catch {
                DispatchQueue.main.async { completion(error) }
                return
            }

            let duration = CMTime(value: shutterNs, timescale: 1_000_000_000)
            // ★ 先立「已锁」标志再下发：`apply(format:)` 也在 `sessionQueue` 上，
            //   虽然串行不会真并发，但把标志立在下发**之前**能让「锁已生效」
            //   与「参数已写」这对事实的边界清晰 —— 回调里再立的话，
            //   中间那段时间里相机是手动态而标志是 false。
            self.setExposureLocked(true)
            currentDevice.setExposureModeCustom(duration: duration, iso: iso) { [weak self] _ in
                currentDevice.unlockForConfiguration()
                // ★★ 重读点③：曝光模式切换后也重读一次（宁可多读，不可拿旧值）。
                self?.refreshCapabilities(force: true)
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    /// 交还给相机自动曝光。
    ///
    /// ★ 把曝光**交还**给相机是本类里唯一「恢复出厂状态」的动作，
    ///   所以它是 `isExposureLocked` 的唯一清除点（另一处是换镜头时，
    ///   见 `toggleCamera`）。忘掉清标志 ⇒ 切格式永远报 `exposureLocked`，
    ///   用户在功能页里会看到「换不了分辨率」而没有任何线索。
    ///
    /// - Parameter completion: 主线程回调。
    func resumeAutoExposure(completion: @escaping (Error?) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                try self.resumeAutoExposureNow()
            } catch {
                DispatchQueue.main.async { completion(error) }
                return
            }
            self.refreshCapabilities(force: true)
            DispatchQueue.main.async { completion(nil) }
        }
    }

    /// `resumeAutoExposure` 的**同步**版本（调用方必须已经在 `sessionQueue` 上）。
    ///
    /// ★ 为什么单独抽出来：`toggleCamera` 要在「换 input」之前把曝光交还自动，
    ///   而它自己就在 `sessionQueue` 上 —— 再调一次公开版会二次 `async` 排队，
    ///   于是「先恢复自动」和「再换 input」的顺序就只剩「同一个串行队列」这一层保证，
    ///   而换 input 是同步跑的，排队的那次反而可能后执行 ⇒ 新设备带着旧锁起流。
    ///
    /// - Throws: `Failure.noBackCamera` / 加锁失败时的原始错误。
    private func resumeAutoExposureNow() throws {
        guard let currentDevice = device else {
            throw Failure.noBackCamera
        }
        // ★ 加锁失败就抛出去（不吞）：吞掉的话调用方会以为「已经回自动了」，
        //   而相机其实还锁在手动态 —— 随后换 input 就是「带着旧锁起新传感器」。
        try currentDevice.lockForConfiguration()
        currentDevice.exposureMode = .continuousAutoExposure
        currentDevice.unlockForConfiguration()
        setExposureLocked(false)
    }

    /// 改「曝光已锁」标志（唯一写入口，`stateLock` 保护）。
    private func setExposureLocked(_ locked: Bool) {
        stateLock.lock()
        isExposureLocked = locked
        stateLock.unlock()
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
                self?.takeFinishedCapture(matching: url)
                DispatchQueue.main.async { completion(result) }
            }
            // ★ 成片不镜像：拍照前在**自己的队列上同步**把 photoOutput 的
            //   connection 再确认一次。放在这里而不是只放在切换/首帧那两处，
            //   是因为这是唯一「成片一定会经过」的点 —— 上面两处是「尽量早」，
            //   这里是「一定对」。前置默认镜像，成片一旦镜像，
            //   教练说的「往左」就全反了（见 `makePreviewLayer` 的注释）。
            self.applyNoMirror(output.connection(with: .video))
            self.stateLock.lock()
            self.photoCaptures.append(capture)
            self.stateLock.unlock()
            output.capturePhoto(with: AVCapturePhotoSettings(), delegate: capture)
        }
    }

    /// 从持有表里摘掉已完成的 delegate。
    /// - Parameter url: 该次拍照的输出 URL。
    /// - Returns: 被摘掉的 delegate（没有则 nil）。
    @discardableResult
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

        // ★ 起流之后再设一次镜像：预览层的 connection 有些机型要等
        //   `startRunning()` 之后才存在（`makePreviewLayer()` 里那次多半是 nil）。
        //   真正兜底的是首帧那次（`captureOutput` 里），这里只是让它早点对。
        applyMirroringPolicy()
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        // `.inputPriority`：把分辨率决定权交给下面显式设置的 `activeFormat`，
        // 否则 sessionPreset 会在 commit 时把 format 覆盖掉。
        session.sessionPreset = .inputPriority

        guard let backCamera = preferredDevice(for: .back) else {
            throw Failure.noBackCamera
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: backCamera)
        } catch {
            throw Failure.cannotCreateInput(underlying: error)
        }
        guard session.canAddInput(input) else {
            throw Failure.cannotAddInput
        }
        session.addInput(input)
        device = backCamera
        // ★ 按**真正拿到的**那颗写标志，不按「我请求的是后摄」写：
        //   后摄被别的 App 占住时 `AVCaptureDevice.default(for: .video)` 可能
        //   退给前置，那时界面上的「翻转」按钮方向才是对的。
        setFrontFlag(backCamera.position == .front)
        // 首次配置同样会建 connection ⇒ 标脏，等首帧补齐镜像设置。
        markMirroringDirty()

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

    // MARK: - 前置 / 后摄切换

    /// 按 `position` 挑一颗「默认」相机。
    ///
    /// 后摄的优先级与原实现**逐字相同**（广角 → 任意视频设备），换成一个函数
    /// 只是为了让 `configure()` 与 `toggleCamera()` 用同一套选择口径 ——
    /// 两处各写一遍的话，切回后摄拿到的可能是超广角，而首次进页面拿的是广角，
    /// 于是「切回去之后画面视角变了」。这种不一致极难被用户描述清楚。
    ///
    /// 前置：广角优先，再退 TrueDepth（Face ID 机型上两者都是能出彩色图的设备）。
    /// - Parameter position: 目标位置。
    /// - Returns: 找到的设备；没有则 nil。
    private func preferredDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        switch position {
        case .front:
            return AVCaptureDevice.default(
                .builtInWideAngleCamera,
                for: .video,
                position: .front
            ) ?? AVCaptureDevice.default(
                .builtInTrueDepthCamera,
                for: .video,
                position: .front
            ) ?? AVCaptureDevice.default(for: .video)
        default:
            return AVCaptureDevice.default(
                .builtInWideAngleCamera,
                for: .video,
                position: .back
            ) ?? AVCaptureDevice.default(for: .video)
        }
    }

    /// 写「当前是否前置」标志（唯一写入口，`stateLock` 保护）。
    /// - Parameter front: 是否前置。
    private func setFrontFlag(_ front: Bool) {
        stateLock.lock()
        isFrontFlag = front
        stateLock.unlock()
    }

    /// 标记「镜像策略待重设」，下一次出帧时消化（见 `mirroringDirty`）。
    private func markMirroringDirty() {
        stateLock.lock()
        mirroringDirty = true
        stateLock.unlock()
    }

    /// 翻转前后摄像头。
    ///
    /// ★★ 为什么必须**重建 session 配置**，而不是「换一个 input」了事：
    ///   iOS 不支持在同一个 `AVCaptureSession` 上把后摄 input 原地换成前置 input
    ///   （两颗传感器的输出流参数不同），必须
    ///   `beginConfiguration` → 移除旧 input → 加新 input → `commitConfiguration`。
    ///   这三步里的任何一步顺序错了，`canAddInput` 的返回值都会骗人 —— 见 `switchInput`。
    ///
    /// ★ 全过程在 `sessionQueue` 上串行执行：`startRunning()` / `lockForConfiguration()`
    ///   都在这个队列上，从别处并发改 session 配置是这个类最容易踩的坑。
    ///
    /// ★ 切换期间画面会黑几百毫秒（系统重建管线），这是 AVFoundation 的固有时延，
    ///   接线方要在这段时间里禁用按钮 + 显示 loading（见 `ContentView.shutterBar`）。
    ///
    /// - Parameter completion: **主线程**回调。成功表示「新 input 已生效、
    ///   format 已按新设备重挑、能力已重报」。失败时相机保持在原来那颗上。
    func toggleCamera(completion: @escaping (Result<Void, Error>) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.device != nil else {
                DispatchQueue.main.async { completion(.failure(Failure.noBackCamera)) }
                return
            }

            // ① 先把曝光交还自动 —— **必须在换 input 之前**。
            //   曝光模式是**设备级**状态，带着 `.custom` 换到另一颗传感器上，
            //   它会以上一颗的 ISO / 快门起流（常见现象：全黑或全白，
            //   而且不会自愈，因为新设备的自动曝光已经被我们关掉了）。
            //   恢复失败不阻断切换：换镜头这件事比「保持手动曝光」重要得多。
            try? self.resumeAutoExposureNow()

            // ② 重建 input（内部自带回滚）。
            let target: AVCaptureDevice.Position = self.isFront ? .back : .front
            do {
                try self.switchInput(to: target)
            } catch let failure as Failure {
                DispatchQueue.main.async { completion(.failure(failure)) }
                return
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(Failure.cannotCreateInput(underlying: error)))
                }
                return
            }

            // ③ 新设备的 `activeFormat` 与旧设备**毫无关系**，必须重新挑一次。
            //   `activeFormat` 是设备属性：换 input 之后拿到的是新设备的
            //   「它自己的默认 format」，前置可能是 1920×1080@30，后摄可能是
            //   4032×3024 的拍照 format —— 分析链要的是 ≤1280 的轻档，不重挑
            //   就等于「换了颗镜头、顺便换了个完全不同量级的帧」，
            //   直方图与评分会跟着跳，而用户只按了一下翻转。
            if let currentDevice = self.device {
                self.applyPreferredFormat(currentDevice)
            }

            // ④ 镜像策略重设：`commitConfiguration()` 会重建 connection，
            //   旧 connection 上设的「不镜像」已经随旧 input 一起作废了。
            self.applyMirroringPolicy()

            // ⑤ 能力重报：ISO / 快门范围是 format 级的，换了传感器必然变。
            //   不重报的话「照拨」会给出一组前置根本执行不了的数。
            self.refreshCapabilities(force: true)

            DispatchQueue.main.async { completion(.success(())) }
        }
    }

    /// 把 session 的视频 input 换成 `position` 那颗，失败时回滚到原来那颗。
    ///
    /// ★★ 顺序铁律：**先移除旧 input，再问 `canAddInput`**。
    ///   `AVCaptureSession` 允许同时挂多路视频 input（多摄同时出流就靠这个），
    ///   所以「不问就加」会成功，然后 session 里有两路视频输入：
    ///   `device` 指向新的、而 `videoDataOutput` 连的可能是旧的那路，
    ///   表现出来就是「翻转后画面没变 / 时不时黑一下」。这类 bug 不看代码根本猜不到。
    ///
    /// ★ 回滚也要做：`canAddInput` 为 false 时把旧 input 装回去再 commit，
    ///   否则用户会停在「一个 input 都没有」的黑屏上，而且**没有任何错误提示** ——
    ///   因为切换这个动作本身报错了，界面却以为自己在正常取景。
    ///
    /// - Parameter position: 目标位置。
    /// - Throws: `Failure.noFrontCamera` / `.noBackCamera` / `.cannotCreateInput` /
    ///   `.cannotAddInput`。
    private func switchInput(to position: AVCaptureDevice.Position) throws {
        guard let newDevice = preferredDevice(for: position) else {
            // 分开写两个 throw（而不是三元表达式）：这里要的是「哪种坏消息」，
            // 写成 `throw cond ? .a : .b` 会把重点埋进一行里，也会让类型推断
            // 去猜 `Error` 与 `Failure` 的转换。
            if position == .front {
                throw Failure.noFrontCamera
            }
            throw Failure.noBackCamera
        }

        let newInput: AVCaptureDeviceInput
        do {
            newInput = try AVCaptureDeviceInput(device: newDevice)
        } catch {
            throw Failure.cannotCreateInput(underlying: error)
        }

        session.beginConfiguration()
        let oldInputs = session.inputs.compactMap { $0 as? AVCaptureDeviceInput }
        for input in oldInputs {
            session.removeInput(input)
        }

        if session.canAddInput(newInput) {
            session.addInput(newInput)
            session.commitConfiguration()
            device = newDevice
            setFrontFlag(newDevice.position == .front)
            // ★ connection 已被重建 ⇒ 已设的「不镜像」全部作废，标脏等下一帧补。
            markMirroringDirty()
            return
        }

        // 回滚：把原来那一路装回去，别把用户留在黑屏上。
        for input in oldInputs where session.canAddInput(input) {
            session.addInput(input)
            break
        }
        session.commitConfiguration()
        // 回滚也重建了一次 connection，同样要标脏。
        markMirroringDirty()
        throw Failure.cannotAddInput
    }

    // MARK: - 镜像策略（预览与成片都不镜像）

    /// 把「不镜像」应用到当前所有相关的 connection 上。
    ///
    /// ★ 三处 connection，一处都不能漏（漏了的结果是「预览正着、成片反着」
    ///   或者反过来，而用户只会觉得「这 App 有时候怪怪的」）：
    ///   ① `videoDataOutput` 的视频连接 —— 喂给评分与 Vision 的那条；
    ///   ② `photoOutput` 的视频连接 —— 成片；
    ///   ③ `AVCaptureVideoPreviewLayer` 的连接 —— 用户眼前的画面。
    ///
    /// ★ 之所以要「重复设」而不是设一次：connection 是随配置重建的对象，
    ///   `commitConfiguration()` / 换 input 之后拿到的是**新对象**，
    ///   旧对象上的值不会跟过来。调用点见 `configureAndStart` / `toggleCamera` /
    ///   `captureOutput`（首帧）/ `capturePhoto`。
    private func applyMirroringPolicy() {
        let apply: () -> Void = { [weak self] in
            guard let self else { return }
            self.applyNoMirror(self.videoDataOutput?.connection(with: .video))
            self.applyNoMirror(self.photoOutput?.connection(with: .video))
            // ★ 预览层是 UI 对象，只在主线程碰它。
            self.applyNoMirror(self.previewLayer?.connection)
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    /// 在一条 connection 上强制「不镜像」。
    ///
    /// ★ 三行的顺序与判空都不能省：
    ///   ① `isVideoMirroringSupported == false` 时强设 `isVideoMirrored` 会**抛异常**
    ///      （不是返回错误，是 ObjC exception，Swift 里直接终止进程）；
    ///   ② 必须先 `automaticallyAdjustsVideoMirroring = false`，
    ///      否则系统会在下一次配置时把 `isVideoMirrored` 覆盖回「前置=镜像」；
    ///   ③ 而且「自动镜像为真时设 `isVideoMirrored`」本身也是异常。
    /// - Parameter connection: 目标连接（可为 nil —— 还没建好）。
    private func applyNoMirror(_ connection: AVCaptureConnection?) {
        guard let connection, connection.isVideoMirroringSupported else { return }
        connection.automaticallyAdjustsVideoMirroring = false
        connection.isVideoMirrored = false
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
        raw.isoMin = Int32(max(Float(1), format.minISO.rounded()))
        raw.isoMax = max(raw.isoMin, Int32(format.maxISO.rounded()))
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

    /// `DiscoverySession` 枚举镜头 → `[LensInfo]`。**跟着当前那颗传感器走**。
    ///
    /// iOS 不暴露传感器物理尺寸与物理焦距，所以 `equivFocalMm` / `focalMm` /
    /// `sensorWidthMm` / `sensorHeightMm` 按 common 的「0 = 未知」填 0；
    /// `aperture` 用真实的 `AVCaptureDevice.lensAperture`；
    /// `pixelWidth/Height` 用该镜头 `activeFormat` 的真实尺寸。
    ///
    /// ★★ 前置只给**一条**：就是当前在用那颗。
    ///   这不是偷懒，是诚实。后摄枚举多颗是因为用户真的能在几颗之间切（超广/长焦），
    ///   而本 App 的前置**不提供镜头选择**。若照抄 `.front` 的 DiscoverySession，
    ///   Face ID 机型会同时返回广角与 TrueDepth 两颗 ⇒ `lenses.count > 1`
    ///   ⇒ `DeviceProfile.isMultiCamera` 变成 true ⇒ 功能页对前置自称「多摄」。
    ///   前置根本没有「多摄」这回事，那句就是假的。
    ///   ⇒ 前置返回单条，`isBack` 也照实为 false（`camera.position == .back` 天然成立）。
    ///
    /// - Returns: 当前传感器下的镜头列表（前置恒为 0 或 1 条）。
    private func readLenses() -> [LensInfo] {
        if isFront {
            guard let currentDevice = device else { return [] }
            return [makeLensInfo(currentDevice)]
        }

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
        return discovery.devices.map(makeLensInfo)
    }

    /// 一颗 `AVCaptureDevice` → common 的 `LensInfo`（字段映射见 `readLenses`）。
    /// - Parameter camera: 目标设备。
    /// - Returns: 拍平后的镜头信息。
    private func makeLensInfo(_ camera: AVCaptureDevice) -> LensInfo {
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
        // 首帧计时只结算一次：frameIndex 自增后，后续帧不会再进这个分支。
        if frameIndex == 0 && startTime > 0 {
            _firstFrameLatencySeconds = CFAbsoluteTimeGetCurrent() - startTime
        }
        let index = frameIndex
        frameIndex += 1
        // ★ 取出并清掉「镜像待重设」：换镜头/重建配置后会置位，
        //   这里消化掉。清在这里（而不是在主线程那次 apply 之后）是安全的 ——
        //   能走到这里就说明管线在出帧，三条 connection 必然都已存在。
        let needsMirroring = mirroringDirty
        mirroringDirty = false
        stateLock.unlock()

        // ★ 首帧 = 「管线真的通了」= 所有 connection 都已存在。
        //   镜像策略在两个时机补：
        //   ① 每次换镜头后的第一帧（`mirroringDirty`，见 `switchInput`）——
        //      `commitConfiguration()` 重建了 connection，旧值作废；
        //   ② 整个会话的第一帧 —— `makePreviewLayer()` / `configureAndStart()`
        //      那两次调用时预览层的 connection 可能还是 nil，设了等于没设。
        //   这里跑在 `videoQueue`，能收到帧就说明 session 在跑、connection 都在。
        if needsMirroring || index == 0 {
            applyMirroringPolicy()
        }

        // ★★ 重读点⑥（兜底）：系统在我们不知情时改了 activeFormat 也能发现。
        //    节流到约 1Hz，读几个属性而已，不会拖慢取景。
        let interval = UInt64(max(1, configuration.capsRefreshFrameInterval))
        if index > 0 && index % interval == 0 {
            refreshCapabilities()
        }

        onFrame?(pixelBuffer)
        // ★ 旁路消费者（目前是 Vision 场景识别）。放在 `onFrame` **之后**：
        //   评分链是主产出，先把它喂饱；旁路晚一微秒无所谓，急了可以丢帧（它自己有节流）。
        onFrameTap?(pixelBuffer)
    }
}

// MARK: - 给用户看的一句话

extension CameraSession.Failure {

    /// 这条失败该怎么向用户交代。
    ///
    /// ★ 为什么放在这里（而不是在 `ContentView` 里 `switch`）：
    ///   ① 只有本文件知道每个 case 的**真实成因**，文案跟着成因走才不会漂；
    ///   ② 现在有**两个**调用方要用它（换镜头的告警、照拨失灵的告警），
    ///      各写一份必然出现「同一件事两种说法」。
    ///
    /// ★ 文案口径：说**用户能做什么**，不说异常名。
    ///   「cannotAddInput」对用户毫无意义，而「这台相机接不进当前的取景会话，
    ///   退出去重进一次」是他能执行的动作。
    var userMessage: String {
        switch self {
        case .permissionDenied:
            return "相机权限被拒绝了。可以在「设置 → 隐私与安全性 → 相机」里打开。"
        case .noBackCamera:
            return "找不到可用的后置摄像头。"
        case .noFrontCamera:
            return "这台设备没有可用的前置摄像头。"
        case .cannotCreateInput:
            return "相机被占用了，稍等一秒再试。"
        case .cannotAddInput:
            return "这台相机接不进当前的取景会话，退出取景页重进一次。"
        case .cannotAddVideoDataOutput, .cannotAddPhotoOutput:
            return "相机功能没能初始化，退出取景页重进一次。"
        case .cannotLockDevice:
            return "相机正忙，这次没设上。"
        case .unsupportedFrameRate(let fps):
            return "当前格式不支持 \(fps)fps。"
        case .customExposureUnsupported:
            return "当前的格式不支持手动曝光，已保持自动。"
        case .exposureLocked:
            return "先关掉「照拨」再换分辨率或帧率。"
        case .photoCapture:
            return "这一张没拍成。"
        }
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
