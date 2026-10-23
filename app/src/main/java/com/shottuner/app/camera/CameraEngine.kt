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

/**
 * 相机引擎：负责 CameraX 绑定与手动参数的写入。
 *
 * 关键约束：CameraX 的 CaptureRequest 参数在 UseCase 构建时固定，
 * 因此「改变 ISO / 快门」必须重建 UseCase 并重新 bind。为抑制闪烁，
 * 调用方需要做节流（见 MainActivity 的 1.2s 最小间隔 + 变化阈值）。
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
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
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

    private fun bind() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

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
        } catch (e: Throwable) {
            // 第一层失败：多数是"手动曝光的 CaptureRequest 这套机型的兼容问题"，
            // 或者某个 UseCase 组合不被支持。降级成**只绑预览**——
            // 用户至少还能取景、还能看构图与曝光提示，只是不能在本机拍、
            // 也拿不到逐帧统计。这比黑屏好得多。
            Diag.w(TAG, "完整 UseCase 绑定失败，降级为仅预览", e)
            try {
                provider.unbindAll()
                imageCapture = null
                camera = provider.bindToLifecycle(activity, selector, preview)
                readCapabilities()
                onReady(caps)
            } catch (e2: Throwable) {
                // 第二层也失败：真的起不来了，交给 UI 说人话
                reportFailure("仅预览降级也失败", e2)
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

            val supportsManualSensor = aeModes?.contains(CameraMetadata.CONTROL_AE_MODE_OFF) == true
            val focalMm = if (focal != null && focal.isNotEmpty()) focal[0] else 0f

            caps = CameraCaps(
                isoMin = isoRange?.lower ?: 50,
                isoMax = isoRange?.upper ?: 6400,
                exposureMinNs = expRange?.lower ?: 20_000L,
                exposureMaxNs = expRange?.upper ?: 500_000_000L,
                supportsManualSensor = supportsManualSensor,
                supportsManualWb = false,
                focalLengthMm = focalMm
            )
        } catch (e: Throwable) {
            // 读不到能力就退回保守默认值（不影响取景），但留一条证据：
            // "这台机器不支持手动"和"读能力时抛异常"是两件事，日志里要能分辨。
            Diag.w(TAG, "读取相机能力失败，退回默认上限", e)
            caps = CameraCaps.UNKNOWN
        }
    }

    private companion object {
        private const val TAG = "Camera"
    }
}
