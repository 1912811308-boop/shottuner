// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/Camera/CaptureFlow.swift —— T17 · 拍照闭环（拍照 →（可选）水印 → 相册）。
//
// 职责边界（刻意收窄，UI 接线由 ContentView 负责）：
//   ① 起一次 `AVCapturePhotoOutput` 拍照，落一个**临时** JPEG；
//   ② 若开关打开，把**已经算好的**文案烧进这张图，再落第二个临时 JPEG；
//   ③ 把最终那张写进系统相册（**只申请 addOnly 权限**）；
//   ④ 无论成败，删掉 App 侧所有临时文件 —— 这是 T17 验收①：
//      「照片写入相册，**App 不保留副本**」。
//
// ★ 本类**不判断文案口径**。传进来的 `scoreText` 就是最终要烧在图上那行字：
//   测全了是「参考分 82 / 100」，没测全是「参考分 --」。
//   这个判断属于产品口径，调用点用 `ScoreSnapshot.hasTotal` 决定，本类只负责烧。
//   （T17 验收④：未测全时烧「参考分 --」，**不烧假数字** —— 一旦把判断挪进本类，
//    就会出现「本类以为自己知道该写什么」的第二份口径，两端必然漂移。）
//
// ★★ 为什么只要 `.addOnly`，不要 `.readWrite`：
//   本 App 从不读相册（零网络、照片只在本机分析），多要一档读权限 = 多一条隐私文案、
//   多一个审核问询点、多一次用户犹豫。`.addOnly` 的授权提示只说「添加到照片」，
//   与 Info.plist 里 `NSPhotoLibraryAddUsageDescription` 的文案口径完全对齐。
//
// ★★ 临时文件的生命周期（最容易写错的地方，见 `finish(_:job:)`）：
//   Swift 的 `defer` 在**函数返回**时执行 —— 异步回调里写 `defer { 删文件 }`
//   会在「刚发起异步请求、还没拿到结果」时就执行，看起来删了、其实删的是空跑，
//   而真正写完相册之后文件还在。本文件的写法是：**每条退出路径都走 `finish`**，
//   由 `finish` 统一删 `job.tempURLs` 里登记过的全部临时文件，删完才回调调用方。
//   退出路径共 5 条：
//     (a) 拍照本身失败        → finish
//     (b) 读临时 JPEG 失败    → finish（水印路径）
//     (c) JPEG 编码 / 写盘失败 → finish（水印路径）
//     (d) 相册权限被拒        → finish
//     (e) 相册写入成功 / 失败  → finish
//   其中 (a)(b)(c)(d) 是「已经知道结果」的同步点，(e) 在 `performChanges` 的
//   completion 里 —— 那是整个流程唯一真正「之后」的时刻。
//
// ★ 线程口径（每一步在哪执行，逐条对应上面的流程）：
//   | 步骤 | 执行线程 |
//   |---|---|
//   | `capture(...)` 入口、`isCapturing` / `lastError` 读写 | 主线程（本类 `@MainActor`） |
//   | `session.capturePhoto(to:)` 内部起流 / 落盘 | `CameraSession.sessionQueue`（后台串行） |
//   | 它的 completion | **主线程**（`CameraSession` 内部 `DispatchQueue.main.async`），
//   |                      但闭包类型上没有 `@MainActor` ⇒ 本文件用 `Task { @MainActor in }` 显式跳回 |
//   | 水印渲染 + JPEG 编码 | 主线程（刻意，理由见 `applyWatermarkIfNeeded`） |
//   | `PHPhotoLibrary.requestAuthorization` 回调 | **系统决定的队列**（不是主线程）⇒ 跳回主 actor |
//   | `performChanges` 的 change block | 系统决定的队列（后台） |
//   | `performChanges` 的 completion | 系统决定的队列（后台）⇒ 跳回主 actor |
//
// ★ 零网络、零第三方依赖：只用 AVFoundation（经 `CameraSession`）/ Photos / UIKit /
//   Core Graphics / Foundation，全是系统框架。本文件不出现任何联网调用。
//
// ★ 复查清单（本仓踩过的坑，改这个文件时逐条核）：
//   · `ObservableObject` / `@Published` 在 **Combine** 里，不在 Foundation 里 ——
//     漏 `import Combine` 会让整个类型构造失败，并把调用方一起拖红；
//   · Swift 没有 `Boolean` 类型（那是 CoreFoundation 的 `UInt8` 别名），布尔一律 `Bool`；
//   · `@MainActor` 类的 `init` 也是隔离的，`deinit` **不是**隔离的（本类没有 deinit）；
//   · `CGFloat` / `Double` / `Float` 之间**不隐式转换**，几何计算处必须显式转。

import Combine
import Foundation
import Photos
import UIKit

/// `UserDefaults` 键名 —— 水印开关。
///
/// ★ 必须与 Android 侧 `MainActivity.KEY_WATERMARK` **逐字一致**（`watermark_enabled`），
///   默认 `false`。两端同名同默认值，将来做「配置迁移 / 对照排查」时不用再记一份映射表。
/// ★ 放在文件作用域而不是类里：类里的 `static let` 会被 `@MainActor` 一起隔离，
///   而键名是纯数据，没有理由要求「必须主线程才能读一个字符串」。
private let watermarkDefaultsKey = "watermark_enabled"

/// 拍照闭环：拍照 →（可选）水印 → 写入相册 → 删临时文件。
///
/// 用法（接线方）：
/// ```swift
/// @StateObject private var captureFlow = CaptureFlow()
/// ...
/// // scoreText 由调用点决定口径 —— 本类不判断「有没有测全」
/// let text = coordinator.score.hasTotal
///     ? "参考分 \(coordinator.score.total) / 100"
///     : "参考分 --"
/// captureFlow.capture(session: camera, scoreText: text) { result in
///     // 已经在主线程；成功后 lastSavedAt / lastCapturedImage 都已被更新：
///     //   · lastSavedAt        → 缩略图飞入动画的触发点
///     //   · lastCapturedImage  → 缩略图 / 复盘卡大图 / 分享的源（只在内存里）
/// }
/// ...
/// // 复盘卡关闭后**必须**调，把 12MP 位图从内存放掉：
/// captureFlow.clearLastCaptured()
/// ```
@MainActor
final class CaptureFlow: ObservableObject {

    // MARK: - 对外状态

    /// 是否正在拍 / 正在写相册。
    ///
    /// ★ 用来**挡住重入**，不是用来做进度动画的：拍照期间再点快门会被直接丢弃
    ///   （不排队）—— `CameraSession.photoCaptures` 是按 URL 索引的持有表，
    ///   连拍会让表里同时挂多个 delegate，而 AVFoundation 对同一 `photoOutput`
    ///   并发拍照本身也不保证顺序。快门按钮应该 `.disabled(isCapturing)`。
    @Published private(set) var isCapturing: Bool = false

    /// 最近一次失败给用户看的中文说明；`nil` 表示没有未读错误。
    ///
    /// ★ 刻意是 `String` 而不是 `Error`：`Error.localizedDescription` 是**给开发者看的**
    ///   英文系统文案（「The operation couldn't be completed. (PHPhotosErrorDomain error -1.)」），
    ///   直接甩到界面上等于没写。本类的 `CaptureFailure` 提供中文 `errorDescription`。
    /// ★ `private(set)` ⇒ 调用方要清掉它（例如关掉提示条）请用 `clearError()`。
    @Published private(set) var lastError: String? = nil

    /// 最近一次成功保存到相册的时刻。
    ///
    /// ★ 缩略图飞入（`matchedGeometryEffect`，T17 验收② ≤400ms）靠它触发：
    ///   调用方 `onChange(of: captureFlow.lastSavedAt)` 开始动画，动画用的图是
    ///   `lastCapturedImage`（刚拍的内存图），不是从相册读回来的图 ——
    ///   要读相册就得多要 `.readWrite` 权限，而本 App 不读相册。
    @Published private(set) var lastSavedAt: Date? = nil

    /// 刚拍下（且已完成水印）的图。**只在内存里**，用于缩略图 / 复盘卡 / 分享。
    ///
    /// ★ 相册里那份才是权威副本，App 不保留任何文件 —— 这是 T17 验收①
    ///   「照片写入相册，App 不保留副本」的内存侧另一半：
    ///   文件删干净了，但复盘卡与分享面板还需要一张图，所以图在内存里活到复盘卡关闭为止。
    /// ★ 生命周期约定（调用方照这个来，否则 12MP 位图会一直挂着）：
    ///   · 每次 `capture()` 入口清成 `nil`（新的一次拍照不能先闪一下上一张）；
    ///   · 成功保存到相册那一刻**一定有值**（两条路径都做了，见 `applyWatermarkIfNeeded`）；
    ///   · **复盘卡关闭时调 `clearLastCaptured()`** 把它放掉。
    /// ★ 12MP 位图约 48MB。低端机上这 48MB 是实打实的压力，所以「什么时候放」不是可选项。
    @Published private(set) var lastCapturedImage: UIImage?

    // MARK: - 水印开关

    /// 水印开关（`watermark_enabled`，默认 `false`，与 Android 一致）。
    ///
    /// ★ 每次读写都直接落 `UserDefaults`，不在内存里留一份副本 ——
    ///   设置页可能用 `@AppStorage("watermark_enabled")` 直接改它，
    ///   两处各存一份会让「设置里打开了、拍照时还是没水印」这种鬼故事出现。
    var watermarkEnabled: Bool {
        get { defaults.bool(forKey: watermarkDefaultsKey) }
        set { defaults.set(newValue, forKey: watermarkDefaultsKey) }
    }

    /// 存储后端。默认 `.standard`；测试可以注入独立的 `UserDefaults`，
    /// 免得测试之间互相污染同一个开关。
    private let defaults: UserDefaults

    // MARK: - 初始化

    /// 构造。
    /// - Parameter defaults: 开关的存储后端（默认 `.standard`）。
    ///
    /// ★ `@MainActor` 类的 `init` 也是主 actor 隔离的：调用点（`@StateObject` 初始化）
    ///   本来就在主线程，无需额外处理。
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - 错误

    /// 拍照闭环的失败分类。
    ///
    /// ★ 用「分类」而不是直接透传 `Error`：UI 需要的是「该怎么办」，
    ///   而系统错误对象只说了「哪个调用失败」。
    enum CaptureFailure: LocalizedError {

        /// 相册（仅添加）权限被拒 / 被限制。
        case saveDenied

        /// 水印渲染或 JPEG 编码失败（拿到图但没有可用位图数据）。
        case renderFailed

        /// 写盘失败：临时文件写入，或相册事务失败。
        case writeFailed(underlying: Error)

        /// 拍照本身失败（`CameraSession` 侧，例如输出未就绪）。
        case photoCapture(underlying: Error)

        /// 给用户看的中文说明。
        var errorDescription: String? {
            switch self {
            case .saveDenied:
                return "没能把照片存进相册：系统里的「照片」写入权限没打开。可在「设置 → 隐私与安全性 → 照片」里改回来，再拍一张。"
            case .renderFailed:
                return "这张照片没能加上水印，所以没有保存。可以关掉水印后再拍一张。"
            case .writeFailed:
                return "没能写入相册，请检查系统设置里的照片权限，然后再拍一张。"
            case .photoCapture:
                return "这次没拍成，请再按一次快门。"
            }
        }
    }

    /// `performChanges` 只回一个 `Bool`，失败时 `error` 也可能是 `nil`。
    ///
    /// ★ 系统不给原因时，我们不编一个假原因 —— 这个类型的 `errorDescription`
    ///   就是「相册没给出具体原因」，只用于日志/诊断，不进用户文案。
    struct MissingUnderlyingError: LocalizedError {
        var errorDescription: String? { "相册事务失败，系统没有给出具体错误" }
    }

    /// 清掉 `lastError`（提示条关闭时调）。
    func clearError() {
        lastError = nil
    }

    /// 放掉内存里那张复盘图（**复盘卡关闭时调**）。
    ///
    /// ★ 为什么必须有这个入口：`lastCapturedImage` 是一张 12MP 位图（约 48MB），
    ///   只要它还挂在 `@Published` 上就不会被回收。复盘卡是「看一眼就关」的界面，
    ///   关闭之后这张图没有任何用途 —— 留着就是拿 48MB 换一个「下次打开更快」的错觉，
    ///   而在低端机上 48MB 足够让相机预览被系统回收。
    /// ★ 传 `nil` 会触发一次 `objectWillChange`，SwiftUI 会把复盘卡的大图按空处理 ——
    ///   所以**要在卡片已经关掉之后再调**（`onDisappear` 或关闭动画结束后），
    ///   不要在关闭动画进行中调，否则用户会看到大图先消失、卡片再收起。
    func clearLastCaptured() {
        lastCapturedImage = nil
    }

    // MARK: - 入口

    /// 拍一张 →（可选）烧水印 → 写入系统相册 → 删掉 App 侧临时文件。
    ///
    /// ★ 重入语义：`isCapturing == true` 时**直接返回，连 `completion` 都不调**。
    ///   理由是「这次点击什么也没发生」—— 回调一个失败会让调用方误报一条错误提示。
    ///   调用方应把快门的可用性绑在 `isCapturing` 上。
    ///
    /// - Parameters:
    ///   - session: 已起流的相机会话（`CaptureFlow` 只调用它的 `capturePhoto`）。
    ///   - scoreText: **已经算好的**文案，例如「参考分 82 / 100」或「参考分 --」。
    ///   - completion: 在主线程回调**恰好一次**（成功保存 / 任一步失败）。
    func capture(
        session: CameraSession,
        scoreText: String,
        completion: @escaping @MainActor (Result<Void, Error>) -> Void
    ) {
        // ★ 重入直接丢弃，不排队（见 isCapturing 的注释）。
        guard !isCapturing else { return }
        isCapturing = true
        // 每次进入清一次错误：上一张的失败不该污染这一张的提示条。
        lastError = nil
        // ★ 同时放掉上一张的复盘图：新的一次拍照如果先闪一下上一张，
        //   用户会以为「刚拍的还是那张」，而且这 48MB 会在整个流程期间白挂着。
        lastCapturedImage = nil

        // ① 临时文件：`temporaryDirectory` 下唯一文件名。
        //    ★ 用 UUID 而不是时间戳：同一毫秒内连点两次时时间戳会撞名，
        //      而 `CameraSession` 的 delegate 持有表是**按 URL 索引**的，撞名会互相顶掉。
        let rawURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".jpg")
        // 登记进 job：此后所有退出路径都由 `finish(_:job:)` 统一删除。
        let job = CaptureJob(tempURLs: [rawURL], completion: completion)

        // ② 拍照。completion 已经在主线程（CameraSession 内部 `DispatchQueue.main.async`），
        //    但闭包类型上没有 `@MainActor` 标注 ⇒ 这里再用 `Task { @MainActor in }` 显式
        //    跳回主 actor。多一次 runloop 轮转（亚毫秒），换来的是「不依赖别人文档里的线程承诺」。
        //
        // ★ 刻意**强引用** self，不用 `[weak self]`：
        //   拍照是 <1s 的一次性动作，`CameraSession` 拍完就把 delegate 摘掉了，不存在循环引用；
        //   而如果 self 先走，`weak` 会让临时文件永远留在 tmp 目录、调用方的 completion
        //   永远不来（UI 卡在「正在拍」），两种后果都比多活 1 秒糟。
        session.capturePhoto(to: rawURL) { [self] result in
            Task { @MainActor [self] in
                switch result {
                case .success(let savedURL):
                    self.applyWatermarkIfNeeded(savedURL, scoreText: scoreText, job: job)
                case .failure(let error):
                    self.finish(.failure(CaptureFailure.photoCapture(underlying: error)), job: job)
                }
            }
        }
    }

    // MARK: - ③ 水印（可选）

    /// 需要时烧水印，然后交给相册。
    ///
    /// ★ 为什么渲染放在主线程（而不是 `Task.detached`）：
    ///   12MP 成片的解码 + 重绘 + JPEG 编码大约一两百毫秒。把它挪到后台需要
    ///   `UIImage` 跨 actor 传递（`UIImage` 不是 `Sendable`），在 Swift 6 严格并发下
    ///   要额外包一层 `@unchecked Sendable` 传输壳 —— 为一个 <200ms 的一次性动作引入
    ///   并发协议风险，不划算。而且这一步发生在**快门按下之后**，
    ///   `isCapturing` 已经挡住了重入，用户看到的是快门按钮短暂不可用，
    ///   不是可以感知的卡顿。
    ///
    /// - Parameters:
    ///   - originalURL: 相机刚写出的临时 JPEG。
    ///   - scoreText: 要烧在图上的文案（已由调用点决定口径）。
    ///   - job: 本次拍照的临时文件登记表。
    private func applyWatermarkIfNeeded(
        _ originalURL: URL,
        scoreText: String,
        job: CaptureJob
    ) {
        // 开关关着：原图直接进相册，不重新编码。
        // ★ 不重新编码是刻意的：多一次 JPEG 往返就多一次画质损失，
        //   而「不开水印」时用户期待的就是相机原样输出的那张。
        guard watermarkEnabled else {
            // ★★ 但复盘图**必须在这里解码**，而且必须赶在 `finish` 删 tmp 之前。
            //   为什么不能省：① 复盘卡与分享面板要一张图，不存文件就只能留在内存里；
            //   ② 不能拿取景帧凑数 —— 预览是 1280 长边的分析帧、且是拍照前那一瞬，
            //      构图与实际成片差一点，用户会觉得「我拍的跟刚才看到的不是一张」。
            //   为什么放在这里而不是更早：这条路径**不重编码**（原图直进相册），
            //   所以解码只发生在「需要复盘图」这一件事上，且解码失败也不该
            //   把一张已经拍成的照片作废 —— `job.image` 留 nil，相册照写。
            job.image = UIImage(contentsOfFile: originalURL.path)
            saveToPhotoLibrary(originalURL, job: job)
            return
        }

        guard let image = UIImage(contentsOfFile: originalURL.path) else {
            finish(.failure(CaptureFailure.renderFailed), job: job)
            return
        }
        let marked = WatermarkRenderer.render(image, text: scoreText)
        // ★ 复盘图就是这张：已经解码完、也烧好水印了，直接挂上 ——
        //   不再多解码一次，也保证它在 `finish` 删文件之后依然有效。
        job.image = marked
        guard let data = marked.jpegData(compressionQuality: 0.95) else {
            finish(.failure(CaptureFailure.renderFailed), job: job)
            return
        }

        // 第二个临时文件（放在 tmp 而不是 caches：它就是一次性的，没有复用价值）。
        let markedURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".jpg")
        job.tempURLs.append(markedURL)
        do {
            try data.write(to: markedURL, options: .atomic)
        } catch {
            finish(.failure(CaptureFailure.writeFailed(underlying: error)), job: job)
            return
        }

        // ★ 原图临时文件到这里就没用了，立刻删 —— 不等 `finish`。
        //   路径是「烧完水印再写相册」，中间可能隔一次系统权限弹框（用户要想几秒），
        //   期间 tmp 里同时躺着原图与带水印图，等于双份占盘。
        //   注意：它仍在 `job.tempURLs` 里，`finish` 会再删一次 —— `try?` 吞掉
        //   「文件不存在」，重复删除是安全的。
        try? FileManager.default.removeItem(at: originalURL)

        saveToPhotoLibrary(markedURL, job: job)
    }

    // MARK: - ④ 写相册

    /// 申请 `addOnly` 权限并写入相册。
    ///
    /// ★ `requestAuthorization(for: .addOnly)`：只申请添加，不申请读取，见文件头。
    /// ★ 它的回调**不在主线程**（系统决定的队列）⇒ 里面立刻 `Task { @MainActor in }` 跳回来，
    ///   而且判定权限状态、发起 `performChanges` 都在主 actor 上做，
    ///   保证 `job` 的状态只有一个线程在碰。
    ///
    /// - Parameters:
    ///   - sourceURL: 最终的本地文件（原图，或烧好水印的那张）。
    ///   - job: 本次拍照的临时文件登记表。
    private func saveToPhotoLibrary(_ sourceURL: URL, job: CaptureJob) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { [self] status in
            Task { @MainActor [self] in
                // `.limited`：iOS 对「仅添加」通常给 `.authorized`，但用户若同时把读权限
                // 设成「部分照片」，这里也可能拿到 `.limited` —— 它同样意味着**可以写**，
                // 所以放行。`.notDetermined` 出现在「用户刚划掉弹框」的极端情况，
                // 按拒绝处理（此时确实没拿到授权）。
                guard status == .authorized || status == .limited else {
                    self.finish(.failure(CaptureFailure.saveDenied), job: job)
                    return
                }

                PHPhotoLibrary.shared().performChanges {
                    // ★ `PHAssetCreationRequest` 必须在 change block **内部**创建：
                    //   在 block 外创建的 request 不会被这次事务认领，
                    //   结果是「performChanges 成功但是相册里没有这张图」——
                    //   最难查的一类 bug（没有任何报错）。
                    let request = PHAssetCreationRequest.forAsset()
                    request.addResource(with: .photo, fileURL: sourceURL, options: nil)
                } completionHandler: { success, error in
                    // ★ 这个回调同样**不在主线程**，跳回来再改状态。
                    //
                    // ★★ 临时文件就是在这里删的（经 `finish`）：
                    //    `performChanges` 的 completion 是**唯一**能确定
                    //    「系统已经把文件读进相册了」的时刻。早一步删（例如
                    //    `addResource` 之后立刻删）会让刚写入的资源变成空图/黑图；
                    //    这也是「不要在异步回调里写 defer」的由来，见文件头。
                    Task { @MainActor [self] in
                        if success {
                            self.finish(.success(()), job: job)
                        } else {
                            let underlying: Error = error ?? MissingUnderlyingError()
                            self.finish(
                                .failure(CaptureFailure.writeFailed(underlying: underlying)),
                                job: job
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: - 统一收尾（★ 所有退出路径的唯一出口）

    /// 删掉本次拍照登记过的**全部**临时文件，复位状态，然后在主线程回调调用方。
    ///
    /// ★ 为什么收在一个函数里：临时文件的删除必须**恰好**覆盖 5 条退出路径
    ///   （见文件头列表）。任何一条路径漏删，就等于 App 在 tmp 里留了副本，
    ///   直接违反 T17 验收①「照片写入相册，App 不保留副本」。
    ///   收成一个出口之后，新增失败分支只需调 `finish`，不可能再漏。
    ///
    /// - Parameters:
    ///   - result: 最终结果（成功 = 已进相册）。
    ///   - job: 本次拍照的临时文件登记表与调用方回调。
    private func finish(_ result: Result<Void, Error>, job: CaptureJob) {
        // 先删文件，再回调：调用方在 completion 里可能立刻去读相册、
        // 或以为 App 里还留着一份 —— 回调那一刻必须已经是「干净」的。
        for url in job.tempURLs {
            // `try?`：文件可能已经在 `applyWatermarkIfNeeded` 里删过一次，
            // 也可能因为上一步失败根本没生成。删不掉不值得再抛一个错误给用户。
            try? FileManager.default.removeItem(at: url)
        }
        job.tempURLs.removeAll()

        isCapturing = false
        switch result {
        case .success:
            // ★ 复盘图**在这里才公开**：图在流程中途解好挂在 `job` 上，
            //   到「已确认进相册」这一刻才写成 `@Published`。
            //   顺序上确实晚于临时文件删除 —— 但图早在解码时就已进内存，
            //   删文件不影响它（这正是「解码必须发生在 `finish` 之前」的原因）。
            lastCapturedImage = job.image
            lastSavedAt = Date()
        case .failure(let error):
            lastError = Self.userMessage(for: error)
        }
        job.completion(result)
    }

    /// 把任意 `Error` 折成给用户看的一句中文。
    ///
    /// ★ 不直接透传 `localizedDescription`：那是英文的、面向开发者的，
    ///   出现在中文界面上等于没写。本文件的错误类型都实现了中文 `errorDescription`，
    ///   其余（极端情况下系统抛出来的别的错误）走一句兜底中文。
    ///
    /// - Parameter error: 任意错误。
    /// - Returns: 面向用户的中文说明。
    private static func userMessage(for error: Error) -> String {
        if let failure = error as? CaptureFailure, let text = failure.errorDescription {
            return text
        }
        if let localized = error as? LocalizedError, let text = localized.errorDescription {
            return text
        }
        return "这次没能把照片存进相册，请再试一次。"
    }

    // MARK: - 一次拍照的批次状态

    /// 一次拍照的批次状态：临时文件登记表 + 复盘图 + 调用方回调。
    ///
    /// ★ 为什么需要这个对象：临时文件清单在流程中途会变（水印路径会多出第二个文件），
    ///   而流程被拆成了几个方法 + 几次异步跳转。用局部变量就得把清单塞进每个闭包
    ///   （并且是**可变捕获**，跨异步边界时非常容易写出「删的是旧清单」的 bug）；
    ///   收成一个对象之后，谁都能看到同一份清单，且只在主 actor 上读写。
    private final class CaptureJob {

        /// 本次拍照产生过的所有临时文件（可能被提前删掉其中一部分）。
        var tempURLs: [URL]

        /// 复盘图（缩略图 / 复盘卡 / 分享共用）。
        ///
        /// ★ 存在这里的唯一理由是**时序**：`finish` 一进门就把 tmp 删光，
        ///   所以图必须在调用 `finish` 之前就解好、挂在这个对象上带进去。
        ///   在 `finish` 里再去 `UIImage(contentsOfFile:)` 只会拿到 nil（文件已删），
        ///   那就成了「照片存进去了、复盘卡空着」——最难查的一类时序 bug。
        var image: UIImage?

        /// 调用方的回调（主 actor 上调用，恰好一次）。
        let completion: @MainActor (Result<Void, Error>) -> Void

        /// 构造。
        /// - Parameters:
        ///   - tempURLs: 初始的临时文件清单（至少包含相机要写的那个 URL）。
        ///   - completion: 调用方回调。
        init(
            tempURLs: [URL],
            completion: @escaping @MainActor (Result<Void, Error>) -> Void
        ) {
            self.tempURLs = tempURLs
            self.completion = completion
        }
    }
}
