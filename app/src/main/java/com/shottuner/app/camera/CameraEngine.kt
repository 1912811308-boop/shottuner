package com.shottuner.app.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.shottuner.app.analysis.FrameAnalyzer
import com.shottuner.app.diag.Diag
import com.shottuner.app.model.CameraCaps
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.ManualParams
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 相机引擎：负责 CameraX 绑定与手动参数的写入。
 *
 * 关键约束：CameraX 的 CaptureRequest 参数在 UseCase 构建时固定，
 * 因此「改变 ISO / 快门」必须重建 UseCase 并重新 bind。为抑制闪烁，
 * 调用方需要做节流（见 MainActivity 的 1.2s 最小间隔 + 变化阈值）。
 *
 * ## 前置镜头（3.2.0）
 * `CameraSelector` 只在 bind 时生效，所以「翻转」不是改个标记，而是
 * `unbindAll` + 重新 bind（见 [toggleCamera]）。前置后置的唯一决定点是 [bind]
 * 里那个 `selector` 局部变量，两条 `bindToLifecycle` 分支都读它 —— 任何一处
 * 再写死 `DEFAULT_BACK_CAMERA` 都会造成"预览是前置、拍照是后置"这种最难查的错位。
 *
 * ## 镜像：预览与成片**都不镜像**（产品口径，不是偷懒）
 * 这是一个**构图教练**，教练说「往左移一点」，用户看到的画面和成片必须同向。
 * 一旦预览左右翻转，口令得跟着反着说；而用户回看照片时方向又是真实的 ——
 * 两边对不上，教练的第一句话就不可信了。所以这里刻意：
 * 1. 不做任何视图级翻转（不碰 `previewView.scaleX` 一类）；
 * 2. 成片显式写 `isReversedHorizontal = false`（见 [takePhoto]）。
 *
 * 本机（CameraX 1.3.4）能不能关掉前置预览的镜像：`Preview.Builder.setMirrorMode`
 * 在该版本里是 `UnsupportedOperationException("setMirrorMode is not supported.")`，
 * 而 `camera-view` 1.3.4 内部**没有任何镜像逻辑**（前置预览走 camera2 原始帧，本就不镜像），
 * 所以"不镜像"由默认行为满足，代码不需要额外动作。若将来升级 CameraX 且默认值变了，
 * 才是需要在这里补 `setMirrorMode` 的时刻。
 */
@ExperimentalCamera2Interop
class CameraEngine(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val onStats: (FrameStats) -> Unit,
    private val onReady: (CameraCaps) -> Unit,
    /** 可选：每 45 帧回调一张降采样位图，交给端侧 AI 识别场景 */
    private val onBitmap: ((android.graphics.Bitmap) -> Unit)? = null,
    /**
     * 可选：相机起不来时回调出去，让 UI 给一条可见的错误条 + 重试按钮。
     *
     * 传的是**分类后的原因**而不是 Throwable：调用方需要的是"要不要提示用户
     * 去关掉别的 App"，不是异常栈。异常本身已经进了 Diag。
     */
    private val onError: ((Failure) -> Unit)? = null
) {

    /** 相机初始化失败的原因。区分它是因为两者的行动建议完全不同 */
    enum class Failure {
        /** 被别的应用占着（微信视频、系统相机没退干净）—— 用户自己就能解决 */
        IN_USE,

        /** 其它原因：硬件不可用、权限被撤、厂商 ROM 限制等 */
        UNKNOWN
    }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var caps: CameraCaps = CameraCaps.UNKNOWN

    /**
     * 当前绑的是不是**前置**镜头。
     *
     * 外部只读：改它必须走 [toggleCamera]，因为「改标记」和「重新 bind」是同一件事。
     * 分两步做的话，中间那一瞬会出现「标记已是前置、画面上还是后摄」的错位，
     * 而 `PromptScheduler` 的推送池、`MainActivity` 的镜头档案都按这个标记取值。
     */
    var isFront: Boolean = false
        private set

    /** 上一次记过日志的镜头朝向。null = 还没记过，用来把「镜头档案」日志压到每次切换一条。 */
    private var loggedFront: Boolean? = null

    /**
     * 当前这颗镜头的等效焦距（35mm 口径）。**0 = 算不出来**。
     *
     * 由 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` + `SENSOR_INFO_PHYSICAL_SIZE` 现算，
     * 公式与 `HardwareProbe` 逐字一致（焦距 × 43.267 / 传感器对角线），
     * 这样前置与后置的数字在同一把尺子上。
     *
     * 为什么宁可给 0 也不给个"差不多"的默认值：这个数会进 R05（广角人像）与
     * `FramingCoach` 的广角判定，编一个数出来会让前置也吃到"切 2x"的建议。
     * 0 在本项目 common 里的语义就是"未知"，规则侧自然会闭嘴。
     */
    private var equivFocalMm: Int = 0

    /** 当前镜头的等效焦距，0 = 未知。切镜头后由 `readCapabilities()` 刷新。 */
    fun currentEquivFocalMm(): Int = equivFocalMm

    /** null 表示交还给相机自动曝光（AE） */
    private var manual: ManualParams? = null

    /**
     * 相机当前真实生效的曝光参数。
     *
     * 这个值来自 CaptureResult，是闭环控制的关键：
     * 不知道「当前画面是用什么参数拍出来的」，就无法判断该加多少曝光，
     * 只能盲目套用预设值，必然过冲。
     */
    @Volatile
    private var liveIso: Int = 0

    @Volatile
    private var liveShutterNs: Long = 0L

    fun liveParams(): ManualParams? {
        val iso = liveIso
        val shutter = liveShutterNs
        if (iso <= 0 || shutter <= 0L) return null
        return ManualParams(iso, shutter)
    }


    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bind()
            } catch (e: Throwable) {
                // B 类：用户会看到黑屏，必须留证据并让 UI 说话
                reportFailure("ProcessCameraProvider 获取失败", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        analysisExecutor.shutdown()
    }

    fun currentCaps(): CameraCaps = caps

    /** [bind] 的结果。翻转镜头时必须知道"这次到底绑成了什么"，不能让 UI 靠猜。 */
    private enum class BindResult {
        /** 预览 + 拍照 + 分析三件都绑上了 */
        FULL,

        /** 只绑上了预览：能取景、能看提示，但不能在本机拍（`imageCapture` 为 null） */
        PREVIEW_ONLY,

        /** 两级降级都失败，这一侧镜头起不来 */
        FAILED
    }

    /**
     * 翻转镜头的返回值。
     *
     * 不用裸 Boolean 是因为调用方（`MainActivity`）还要处理第二件事：
     * **翻转会把手动曝光复位成自动**（曝光参数是镜头级能力，见 [toggleCamera]）。
     * 只回一个 boolean 的话，UI 只能靠"翻完再看 `isManual()`"去猜；一旦将来多一个
     * 复位动作，猜法就会失效，而界面上"说自动、实际手动"是绝不能出现的状态。
     */
    class ToggleResult(
        /** 是否真的换到了另一侧。false = 切不过去，已回退到原来那一侧 */
        val switched: Boolean,
        /** 是否因为这次翻转把本机写入（手动 ISO / 快门）复位成了自动 */
        val manualReset: Boolean
    )

    /**
     * 翻转前 / 后摄。
     *
     * 实现要点（四条缺一不可）：
     * 1. **曝光先回自动**：ISO / 快门是镜头级能力，后摄的区间搬到前置上常常不成立。
     *    前置若不支持 `AE_MODE_OFF`，搬过去会让 bind 降级成「仅预览」——
     *    用户点快门才发现相机废了，那是本功能最差的失败形态。所以先清 `manual` 再翻。
     * 2. `CameraSelector` 只在 bind 时生效，所以翻完必须 `unbindAll` 再重新 bind ——
     *    这件事 [bind] 内部已经做了，本方法**只走 bind 一条路**，绝不自己再拼一遍
     *    UseCase（那样两条 `bindToLifecycle` 分支的降级逻辑就得抄两份）；
     * 3. 因此 `bind` 的两级降级（完整 → 仅预览）对前置一样生效；
     * 4. 切不过去要**回退**：把 [isFront] 和画面都退回原来那一侧，用户至少还能继续拍。
     *    注意回退**不恢复**手动曝光：参数已经清掉了，硬塞回旧值就得再 bind 一次、再黑一帧，
     *    所以统一回自动，并通过 [ToggleResult.manualReset] 让 UI 把这件事说出来。
     *
     * @return 见 [ToggleResult]
     */
    fun toggleCamera(): ToggleResult {
        if (cameraProvider == null) {
            // 还没 start() 到 provider 就点了翻转：不静默，日志留证据，由 UI 给提示
            Diag.w(TAG, "翻转失败：ProcessCameraProvider 尚未就绪")
            return ToggleResult(switched = false, manualReset = false)
        }
        // 清字段而不是 `applyManual(null)`：后者内部会 bind() 一次，
        // 等于"先在后摄上白重建一遍 UseCase、紧接着又 unbind 换前置"，
        // 多一段黑屏、多一次 setSurfaceProvider，效果完全一样。
        val manualCleared = manual != null
        if (manualCleared) {
            manual = null
            Diag.d(TAG, "翻转前把手动曝光复位为自动（参数不跨镜头搬）")
        }

        val target = !isFront
        isFront = target
        // 这一次**不报错**：还有退路，先报出来会闪一条"相机打不开"的常驻错误条。
        if (bind(reportErrors = false) != BindResult.FAILED) {
            return ToggleResult(switched = true, manualReset = manualCleared)
        }

        // 切不过去：状态与画面一起退回去，别把用户丢在一块黑屏上
        Diag.w(TAG, "翻转失败：${if (target) "前置" else "后置"}镜头绑定失败，回退")
        isFront = !target
        // 回退这次**要报错**：连原来那一侧都绑不回来的话，那是真正的"相机打不开"，
        // 必须让用户看到常驻错误条 + 重试按钮，而不是一句"翻转失败"。
        bind(reportErrors = true)
        return ToggleResult(switched = false, manualReset = manualCleared)
    }

    /** 写入手动曝光参数；传 null 恢复自动曝光 */
    fun applyManual(params: ManualParams?) {
        manual = params
        bind()
    }

    fun isManual(): Boolean = manual != null

    /**
     * 拍一张照片写入 [outputFile]。
     *
     * 回调统一在主线程执行，调用方可以直接更新 UI。
     * 相机尚未绑定时走 [onError]，不抛异常。
     */
    fun takePhoto(
        outputFile: File,
        onSuccess: (File) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("相机尚未就绪"))
            return
        }
        // 成片**不镜像**（产品口径，见类注释「镜像」一段）：前置自拍的成片要和取景
        // 同向，否则教练说"往左一点"、照片里却是往右。CameraX 对前置默认就是 false，
        // 这里显式写一遍，免得将来升级 CameraX 后默认值悄悄变了，成片方向跟着翻。
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        val options = ImageCapture.OutputFileOptions.Builder(outputFile)
            .setMetadata(metadata)
            .build()
        val executor = ContextCompat.getMainExecutor(activity)
        try {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onSuccess(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onError(exception)
                    }
                }
            )
        } catch (e: Throwable) {
            onError(e)
        }
    }

    /**
     * 按 [isFront] 绑一遍 UseCase。
     *
     * @param reportErrors 绑不上时要不要通过 [onError] 报给 UI。
     *        翻转镜头的**第一次尝试**传 false：切不过去还能回退，此时报错会让用户
     *        看到一条"相机打不开"的常驻条，但画面其实还在正常取景。其余调用点全部 true。
     * @return 绑到了哪一级（完整 / 仅预览 / 失败），见 [BindResult]
     */
    private fun bind(reportErrors: Boolean = true): BindResult {
        val provider = cameraProvider ?: return BindResult.FAILED
        // 前置 / 后置的**唯一**决定点。下面两条 bindToLifecycle 分支都读这一个 val，
        // 不允许任何一处再写死 DEFAULT_BACK_CAMERA —— 那会造成"预览前置、拍照后置"。
        val selector = if (isFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analysisExecutor, FrameAnalyzer(onStats, onBitmap))

        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()

        // 无论自动还是手动，都抓取每一帧的实时曝光参数
        val resultCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                if (iso != null && exposure != null && iso > 0 && exposure > 0) {
                    liveIso = iso
                    liveShutterNs = exposure
                }
            }
        }
        val extender = Camera2Interop.Extender(previewBuilder)
        extender.setSessionCaptureCallback(resultCallback)

        val m = manual
        if (m != null) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, m.shutterNs)
            // 白平衡保持自动：手动 R/G/B 增益在多数机型上差异极大，强行写入易偏色甚至崩溃
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO
            )

            // 拍照 UseCase 单独一套 CaptureRequest，必须写入与预览完全相同的曝光三项。
            // 少了这段，预览是手动档、成片却回落到自动档，两张画面的曝光对不上。
            val captureExtender = Camera2Interop.Extender(captureBuilder)
            captureExtender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            captureExtender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
            captureExtender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, m.shutterNs)
            captureExtender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val capture = captureBuilder.build()
        imageCapture = capture

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(activity, selector, preview, capture, analysis)
            readCapabilities()
            onReady(caps)
            return BindResult.FULL
        } catch (e: Throwable) {
            // 第一层失败：多数是"手动曝光的 CaptureRequest 这套机型的兼容问题"，
            // 或者某个 UseCase 组合不被支持。降级成**只绑预览**——
            // 用户至少还能取景、还能看构图与曝光提示，只是不能在本机拍、
            // 也拿不到逐帧统计。这比黑屏好得多。
            Diag.w(TAG, "完整 UseCase 绑定失败，降级为仅预览${if (isFront) "（前置）" else ""}", e)
            try {
                provider.unbindAll()
                imageCapture = null
                camera = provider.bindToLifecycle(activity, selector, preview)
                readCapabilities()
                onReady(caps)
                return BindResult.PREVIEW_ONLY
            } catch (e2: Throwable) {
                // 第二层也失败：真的起不来了，交给 UI 说人话
                if (reportErrors) {
                    reportFailure("仅预览降级也失败", e2)
                } else {
                    // 翻转的第一次尝试：调用方会回退到原来那一侧，
                    // 这里只留日志，不惊动用户（媒体音/错误条都等回退之后再说）
                    Diag.e(TAG, "仅预览降级也失败（翻转首次尝试，静默回退）", e2)
                }
                return BindResult.FAILED
            }
        }
    }

    /**
     * 把失败分类、记日志、回调 UI。
     *
     * 分类靠**消息文本扫描**而不是异常类型：CameraX 在不同厂商 ROM 上抛的类型并不统一
     * （CameraUnavailableException / IllegalStateException / CameraAccessException 都见过），
     * 但 "in use" 这个词在系统层的报错里高度稳定。
     */
    private fun reportFailure(what: String, e: Throwable) {
        val text = buildString {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 6) {
                append(t.javaClass.name).append(' ').append(t.message).append('\n')
                t = t.cause
                depth++
            }
        }
        val inUse = text.contains("in use", ignoreCase = true) ||
            text.contains("in_use", ignoreCase = true) ||
            text.contains("CAMERA_IN_USE", ignoreCase = true) ||
            text.contains("already in use", ignoreCase = true)
        val failure = if (inUse) Failure.IN_USE else Failure.UNKNOWN
        Diag.e(TAG, "$what -> $failure", e)
        onError?.invoke(failure)
    }

    /**
     * 读**当前这一颗镜头**的能力。
     *
     * 全部取自 `Camera2CameraInfo.from(camera.cameraInfo)`，也就是 bind 上的那一颗 ——
     * 前置时读到的是前置自己的 ISO / 快门 / 焦距范围，不含任何"主摄"假设。
     * 这点是刻意的：前置普遍没有长焦、也常常不支持手动快门，把主摄范围套到前置上
     * 会让 UI 显示一串"看起来支持、写进去没反应"的数字。
     *
     * `DeviceProfile`（主摄 / 镜头列表 / 等效焦距）是**设备级**信息，不在这里混进来，
     * 也不会因为切到前置就凭空多出几颗镜头。
     */
    private fun readCapabilities() {
        val cam = camera ?: return
        try {            val info = cam.cameraInfo
            val c2 = Camera2CameraInfo.from(info)

            val isoRange: android.util.Range<Int>? =
                c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange: android.util.Range<Long>? =
                c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val aeModes: IntArray? =
                c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            val focal: FloatArray? =
                c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val physSize: android.util.SizeF? =
                c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

            val supportsManualSensor = aeModes?.contains(CameraMetadata.CONTROL_AE_MODE_OFF) == true
            val focalMm = if (focal != null && focal.isNotEmpty()) focal[0] else 0f
            // 等效焦距：与 `HardwareProbe` 同一公式（焦距 × 全画幅对角线 / 传感器对角线），
            // 保证前置与后置的数字可以直接比。读不到传感器物理尺寸就给 0（未知），
            // 不拿后摄的值冒充 —— 这个数会进 R05 的广角判定。
            val equiv = if (focalMm > 0f && physSize != null &&
                physSize.width > 0f && physSize.height > 0f
            ) {
                (focalMm * FULL_FRAME_DIAGONAL_MM /
                    hypot(physSize.width.toDouble(), physSize.height.toDouble())).roundToInt()
            } else {
                0
            }
            equivFocalMm = equiv

            caps = CameraCaps(
                isoMin = isoRange?.lower ?: 50,
                isoMax = isoRange?.upper ?: 6400,
                exposureMinNs = expRange?.lower ?: 20_000L,
                exposureMaxNs = expRange?.upper ?: 500_000_000L,
                supportsManualSensor = supportsManualSensor,
                supportsManualWb = false,
                focalLengthMm = focalMm
            )
            // 留一条证据，诊断导出时能看清"这次读到的是前置还是后置"。
            // 只在**镜头变了**时记一次：readCapabilities 在手动曝光收敛期间每 1–2 秒
            // 跑一次 bind，每次都写会把诊断日志刷满，反而看不到真正的异常。
            if (loggedFront != isFront) {
                loggedFront = isFront
                Diag.d(
                    TAG,
                    "镜头档案 ${if (isFront) "前置" else "后置"}：ISO ${caps.isoMin}–${caps.isoMax} · " +
                        "快门 ${caps.exposureMinNs}–${caps.exposureMaxNs}ns · " +
                        "焦距 ${caps.focalLengthMm}mm / 等效 ${equivFocalMm}mm · " +
                        "手动曝光 ${if (supportsManualSensor) "支持" else "不支持"}"
                )
            }
        } catch (e: Throwable) {
            // 读不到能力就退回保守默认值（不影响取景），但留一条证据：
            // "这台机器不支持手动"和"读能力时抛异常"是两件事，日志里要能分辨。
            Diag.w(TAG, "读取相机能力失败，退回默认上限", e)
            caps = CameraCaps.UNKNOWN
            // 等效焦距也要一起退回未知：留着上一颗镜头的数字会让 R05 拿错尺子
            equivFocalMm = 0
        }
    }

    private companion object {
        private const val TAG = "Camera"

        /** 全画幅对角线（mm）。与 `HardwareProbe` 同值，保证两端算出的等效焦距可比。 */
        private const val FULL_FRAME_DIAGONAL_MM = 43.267
    }
}
