package com.shottuner.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.shottuner.app.MainActivity
import com.shottuner.app.analysis.AiVision
import com.shottuner.app.analysis.AiVisionResult
import com.shottuner.app.analysis.CompositionAnalyzer
import com.shottuner.app.analysis.SceneClassifier
import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.diag.Diag
import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.device.unknown
import com.shottuner.app.device.HardwareProbe
import com.shottuner.app.feature.AdviceKind
import com.shottuner.app.feature.FeatureAdvisor
import com.shottuner.app.model.CameraCaps
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import com.shottuner.app.recommend.ParamRecommender
import com.shottuner.app.video.VideoIntent
import com.shottuner.app.video.VideoRecommender
import kotlin.math.roundToInt

/**
 * 悬浮教练：录屏 → 分析 → 浮窗给建议。
 *
 * 这是「在系统相机上给建议」唯一可行的技术路径。必须说清三条硬约束：
 * 1. **只读不写**。录屏拿到的是像素，不是相机会话，任何参数都写不进去。
 *    所以浮窗给的是「你去专业模式拨这几档」，不是「我替你调好了」。
 * 2. **有延迟**。录屏帧率受系统限制，通常 5–15fps，加上分析耗时，
 *    画面到建议大约差 100–300ms。快速摇镜时会明显滞后。
 * 3. **要授权**。Android 14 起每次启动都需要用户重新确认录屏，
 *    并在通知栏常驻一条提示。这是系统强制的，应用绕不过。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"

        const val ACTION_START = "com.shottuner.app.screen.START"
        const val ACTION_STOP = "com.shottuner.app.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "coach_overlay"
        private const val NOTIF_ID = 7411
        private const val MIN_INTERVAL_MS = 320L

        /** PendingIntent 的 requestCode。两个入口必须不同，否则会互相覆盖 */
        private const val REQ_CONTENT = 1001
        private const val REQ_STOP = 1002
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var overlay: CoachOverlay? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastAt = 0L
    private var profile: DeviceProfile = DeviceProfile.unknown()
    private var caps: CameraCaps = CameraCaps.UNKNOWN

    private val aiVision = AiVision()

    @Volatile
    private var aiResult: AiVisionResult? = null

    /** 录屏帧率本来就低，AI 用每 3 帧一次，约 1 秒 */
    private var frameTick = 0

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 前台服务启动协议（targetSdk 34+ 的必答项）。
     *
     * 两条硬约束，改之前是必崩的：
     * 1. **必须返回 START_NOT_STICKY**。原来返回 START_STICKY，系统会在进程被杀后
     *    用 `intent == null` 重建服务；而我们没有任何可恢复的状态 —— 录屏授权是
     *    Intent 里带的一次性 token，重建后拿不到。原实现在 intent 为 null 时
     *    既不 startForeground 也不 stopSelf，targetSdk 34 上 5 秒内就会抛
     *    `ForegroundServiceDidNotStartInTimeException`，表现为"用户切个后台就闪退"。
     * 2. **intent == null 必须 stopSelf()**。不重建就是最好的重建：真要继续录屏，
     *    用户会重新点一次悬浮卡，那时会带新的授权 token 走正常流程。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProjection(code, data)
            }
            else -> {
                // 未知 action：不要留一个"已启动但没进入前台"的服务
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        profile = HardwareProbe.probe(this)
        caps = profile.caps

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Throwable) {
            // B 类：这里失败 = 悬浮教练完全起不来，用户只会看到"点了没反应"
            Diag.e(TAG, "获取 MediaProjection 失败（授权 token 可能已失效）", e)
            null
        }
        if (p == null) {
            stopSelf()
            return
        }
        projection = p

        // Android 14 要求注册回调，否则部分机型会直接拒绝投屏
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, main)

        worker = HandlerThread("coach-capture").also { it.start() }
        val h = Handler(worker!!.looper)
        handler = h

        val dm = resources.displayMetrics
        val w = (dm.widthPixels / 3).coerceIn(240, 720)
        val hgt = (dm.heightPixels / 3).coerceIn(320, 1600)

        reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        virtualDisplay = p.createVirtualDisplay(
            "shottuner-coach",
            w, hgt, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, h
        )

        reader!!.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < MIN_INTERVAL_MS) return@setOnImageAvailableListener
                lastAt = now
                val plane = image.planes[0]
                frameTick++
                val wantAi = frameTick % 3 == 0
                val stats = ScreenStats.compute(
                    plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
                    onBitmap = if (wantAi) { bmp ->
                        aiVision.analyze(bmp) { r -> aiResult = r }
                    } else null
                )
                if (stats != null) handle(stats)
            } catch (e: Throwable) {
                // 坏帧丢弃：投屏管线一旦抛异常会导致整条流停摆。
                // 但"坏帧"和"代码 bug"必须能在日志里分开 —— 记 W 不记 E，
                // 因为这类异常可能每秒来一次，会迅速把环形日志刷满。
                Diag.w(TAG, "屏幕帧处理失败（已丢弃该帧）", e)
            } finally {
                image.close()
            }
        }, h)

        main.post {
            overlay = CoachOverlay(this) { stopSelf() }
            overlay?.show()
        }
    }

    private fun handle(stats: FrameStats) {
        val cls = SceneClassifier.classify(stats, aiResult)
        val rec = ParamRecommender.recommend(
            stats = stats,
            scene = cls.scene,
            caps = caps,
            current = null,
            confidence = cls.confidence
        )
        val comp = CompositionAnalyzer.analyze(stats)

        // 屏幕上看不出用户按的是拍照还是录像，只能推断一个最可能的意图。
        // 判错了也不会误导：卡片上明确写着「录像」两字，用户知道自己有没有在录。
        val intent = when {
            cls.scene == SceneType.ACTION -> VideoIntent.SPORT
            stats.meanLuma < 0.26f || cls.scene == SceneType.NIGHT -> VideoIntent.CONCERT
            else -> VideoIntent.WALK
        }
        val plan = VideoRecommender.plan(intent, profile, stats, cls.scene)

        // 只取一条最有价值的建议：浮窗空间有限，且人不会在拍照时读长文
        val advice = FeatureAdvisor.advise(profile, stats, cls.scene)
            .firstOrNull { it.kind != AdviceKind.KNOW }
            ?.let { "${if (it.kind == AdviceKind.DO) "该做" else "别做"}：${it.title}" }
            .orEmpty()

        val paramsText = "ISO ${rec.iso} · ${rec.shutterLabel} · ${rec.kelvin}K"
        val videoText = "录像 ${plan.tier} ${plan.fps}fps · ${plan.shutterText} · ${plan.stabilization}"
        val sceneText = "${cls.scene.label} · ${(cls.confidence * 100).roundToInt()}%"
        val score = ScoreBoard.score(stats, comp, null, cls.scene)
        val scoreText =
            if (score.dimensions.all { it.measured }) "参考分 ${score.total}" else ""
        main.post {
            overlay?.render(
                sceneText,
                paramsText,
                videoText,
                advice,
                comp.tips.joinToString("；"),
                scoreText
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮教练", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在其他相机应用上方显示参数建议"
            }
        )
    }

    /**
     * 常驻通知。
     *
     * 两个入口都是**隐私政策里承诺过的**，不是锦上添花：
     * · 政策 §2.3 写"屏幕捕获运行期间，系统通知栏会常驻一条本应用的通知，点击即可立即停止"
     *   → 所以要 contentIntent（回 App，能看到当前状态）+ 一个"停止" action（立刻停）；
     * · 政策 §2.4 写"通知提供停止入口" → 同一个 action 兑现。
     *
     * 少了这两样，政策文本就是假的，审核点一下通知就发现。文案保持原口径
     * （"只给建议，不改任何参数"），不夸大能力。
     */
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete),
            "停止",
            stopIntent
        ).build()

        return builder
            .setContentTitle("悬浮教练运行中")
            .setContentText("正在读取屏幕上的相机画面，只给建议，不改任何参数")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .build()
    }

    private fun cleanup() {
        try {
            aiVision.close()
        } catch (_: Throwable) {
        }
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        main.post { overlay?.hide(); overlay = null }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
