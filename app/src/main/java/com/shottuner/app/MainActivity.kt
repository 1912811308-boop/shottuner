package com.shottuner.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.shottuner.app.analysis.AiVision
import com.shottuner.app.analysis.AiVisionResult
import com.shottuner.app.analysis.CompositionAnalyzer
import com.shottuner.app.analysis.SceneClassifier
import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.analysis.ScoreResult
import com.shottuner.app.camera.CameraEngine
import com.shottuner.app.coach.FaceInfo
import com.shottuner.app.coach.FramingCoach
import com.shottuner.app.coach.SubjectBox
import com.shottuner.app.databinding.ActivityMainBinding
import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.diag.Diag
import com.shottuner.app.device.HardwareProbe
import com.shottuner.app.device.OrientationTracker
import com.shottuner.app.feature.AdviceKind
import com.shottuner.app.feature.FeatureAdvisor
import com.shottuner.app.feature.FeatureSuggestion
import com.shottuner.app.growth.AbortReason
import com.shottuner.app.growth.DexStore
import com.shottuner.app.growth.MissionEngine
import com.shottuner.app.growth.MissionEvent
import com.shottuner.app.growth.MissionSample
import com.shottuner.app.growth.MissionState
import com.shottuner.app.growth.MissionTask
import com.shottuner.app.growth.MissionThresholds
import com.shottuner.app.growth.ScoreHistoryStore
import com.shottuner.app.growth.ShotRecord
import com.shottuner.app.growth.TaskKind
import com.shottuner.app.growth.TechniqueDex
import com.shottuner.app.knowledge.PromptDecision
import com.shottuner.app.knowledge.PromptDensity
import com.shottuner.app.knowledge.PromptEngine
import com.shottuner.app.knowledge.PromptFeedback
import com.shottuner.app.knowledge.PromptPriority
import com.shottuner.app.knowledge.PromptScheduler
import com.shottuner.app.knowledge.PromptSignals
import com.shottuner.app.knowledge.TechCategory
import com.shottuner.app.knowledge.TechSignals
import com.shottuner.app.knowledge.Technique
import com.shottuner.app.knowledge.TechniqueBookV15
import com.shottuner.app.knowledge.TechniqueMatcher
import com.shottuner.app.model.CameraCaps
import com.shottuner.app.model.CompositionHint
import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.ManualParams
import com.shottuner.app.model.Recommendation
import com.shottuner.app.model.SceneType
import com.shottuner.app.recommend.ParamRecommender
import com.shottuner.app.screen.ScreenCaptureService
import com.shottuner.app.share.ShareKit
import com.shottuner.app.share.Watermark
import com.shottuner.app.sensors.LevelSensor
import com.shottuner.app.ui.GuideOverlayView
import com.shottuner.app.video.VideoIntent
import com.shottuner.app.video.VideoPlan
import com.shottuner.app.video.VideoRecommender
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var engine: CameraEngine? = null

    /** 设备档案：品牌、机型、镜头、能力。全部运行时探测，与机型无关 */
    private var profile: DeviceProfile = DeviceProfile.unknown()
    private var caps: CameraCaps = CameraCaps.UNKNOWN

    private var lastRecommendation: Recommendation? = null
    private var lockedRec: Recommendation? = null
    private var lastStats: FrameStats? = null
    private var currentScene: SceneType = SceneType.GENERAL

    /** 端侧 AI 的最近一次判定。1.5 秒一次，比逐帧的规则判定慢，但结论更硬 */
    private val aiVision = AiVision()
    @Volatile
    private var aiResult: AiVisionResult? = null

    private var autoMode = false
    private var locked = false

    private var appliedIso = 0
    private var appliedShutterNs = 0L

    /** 快门防连点：一次拍照从触发到落盘完成前，不再接收新的点击 */
    private var captureInProgress = false
    /** 最近一张：相册写入失败时文件留在缓存，供长按分享 */
    private var lastShotFile: File? = null
    private var lastShotUri: Uri? = null

    private var lastUiAt = 0L
    private var lastApplyAt = 0L
    private var lastComposeAt = 0L
    private var lastFeatureAt = 0L
    private var lastSkillAt = 0L
    private var lastFramingAt = 0L

    /**
     * 合并后建议区的三个输入（v1.8 P1-1）。
     *
     * 以前 tvTip / tvDetail / tvFraming 各写各的，其中机位那行还由另一个 400ms 节流
     * 单独刷新。合成一个 tvAdvice 之后必须有一个明确的"谁负责拼"的地方 ——
     * 就是下面这三个字段 + [refreshAdvice]。任何一处更新都只改字段再调 refresh，
     * 保证不会出现"两个 setText 互相覆盖"。
     */
    private var adviceMain: String = "把镜头对准要拍的画面"
    private var adviceDetail: String = ""
    private var adviceFraming: String = ""

    /** 上一次渲染的四个读数，用于"变的那一格闪一下"（v1.8 P1-3） */
    private var lastIso: String? = null
    private var lastShutter: String? = null
    private var lastKelvin: String? = null
    private var lastEv: String? = null

    /** 最近一次构图分析结果（机位建议与复盘卡都要用） */
    private var lastComp: CompositionHint? = null
    private var lastPitch: Float? = null
    private var hasPitch = false

    /** 姿态传感器：随 Activity 前后台启停，10Hz 喂给刻度与「机位」行 */
    private lateinit var orientationTracker: OrientationTracker

    /**
     * 重力传感器：给水平仪和提示引擎提供 roll / pitch。
     *
     * 为什么不复用 [orientationTracker]：它算的是「手机怎么歪」，
     * 而提示引擎要的是「画面怎么歪」（`roll > 0` = 画面右侧偏低，见 `LevelMath`）。
     * 两者符号相反，混用会让「顺时针转一点」变成越转越歪。宁可多挂一个监听。
     */
    private lateinit var levelSensor: LevelSensor

    /** 最近一次重力读数。null = 传感器没给数据，提示引擎会把它当「不判断」 */
    private var levelRoll: Float? = null
    private var levelPitch: Float? = null

    /** 手持稳定度代理：欧拉角相邻样本差的一阶低通。0 = 很稳，1 = 一直在晃 */
    private var motionEma = 0f
    private var motionPitchBase: Float? = null
    private var motionRollBase: Float? = null

    /**
     * 提示调度器。
     *
     * `availableTipIds` 传的是**可推送白名单**（全库减去自拍 5 条与仅浏览 3 条），
     * 不是全库：库里找不到的条目会被静默跳过，这样即便规则表将来点名了禁推条目，
     * 也漏不到用户眼前 —— 这是「禁推」的第二道闸（QA P2-2）。
     */
    private val promptScheduler: PromptScheduler by lazy {
        PromptScheduler(availableTipIds = TechniqueBookV15.pushEligibleIds)
    }

    /** 500ms 独立循环跑提示状态机。**不挂在 render() 的 220ms 节流里** */
    private val coachHandler = Handler(Looper.getMainLooper())
    private var coachLoopRunning = false
    private var coachTicks = 0

    private var guidesOn = true

    /**
     * 用户把网格关掉的那一刻（`elapsedRealtime`）。`-1` 表示现在开着、或者本次会话没关过。
     *
     * R11「网格关着」靠它算静默期：刚关不打扰（QA P1-3）。用 `elapsedRealtime` 而不是
     * 墙上时钟，理由和教练循环一样 —— 系统校时会把差值算成负数或几百天。
     */
    private var gridOffAt = -1L

    private var currentTab = TAB_PHOTO
    private var videoIntent = VideoIntent.WALK
    private var currentPlan: VideoPlan? = null
    private var currentAdvice: List<FeatureSuggestion> = emptyList()
    private var currentSkillCategory: TechCategory? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 用户设置（提示密度、音量键快门）。全 App 只有这一个 prefs 文件 */
    private val uiPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    /** 复盘卡自动收起。抽成字段是为了「点一下保持常驻」时能 removeCallbacks */
    private val hideReviewRunnable = Runnable {
        if (!isFinishing && !isDestroyed) binding.llReviewCard.visibility = LinearLayout.GONE
    }

    /**
     * 界面档位（v1.9 P1-2）。
     *
     * 只影响**可见性**，不参与任何计算：切档不改变取景、不重置冷却、不动参数。
     * 这是"一屏塞太多"的最低成本解法 —— 不是删功能，而是让默认档只留必要元素。
     */
    private enum class UiPreset(val storageKey: String, val label: String) {
        IMMERSIVE("immersive", "沉浸"),
        PRO("pro", "专业")
    }

    private var uiPreset = UiPreset.IMMERSIVE

    // ---------------- G1 任务卡（v3.1.0） ----------------

    /**
     * 任务状态机。
     *
     * 判定入口唯一：[tickMission] 挂在 `renderComposition()` 的 400ms 节拍上，
     * 不新开 Handler —— 否则 hits 计数与渲染错位，会出现"环还没画出来就已经 ACHIEVED"。
     */
    private val missionEngine = MissionEngine()

    /**
     * 最近一次 `ScoreBoard` 的结果。
     *
     * [renderScoreDetail] 写，任务卡的实时参考分与成绩卡的 +N 都读它。
     * 原来是"算完就丢"，任务卡要复用同一个结果，所以必须留一个字段。
     */
    private var lastScoreResult: ScoreResult? = null

    /** 任务派发那一刻的参考分。成绩卡的「+N」是相对它算的 */
    private var taskStartTotal: Int? = null

    /** 到位震动的防连震闸门（`elapsedRealtime`） */
    private var lastBuzzAt = 0L

    /**
     * 参数四格是否被用户手动展开。
     *
     * 不落盘，与 `llMoreActions` 同策略：每次进页面都回到"由档位决定的默认值"。
     */
    private var paramsExpanded = false

    /** 成绩卡是否被点过（点过则不再自动收起，与 `llReviewCard` 一致） */
    private var achieveCardPinned = false

    /**
     * 成绩卡 8 秒自动收起。抽成字段是为了「点一下保持常驻」时能 removeCallbacks。
     *
     * **刻意不在这里调 `abort()`**：SCORED → IDLE 由引擎自己的 8s 计时负责，
     * 那条路径会顺带把这条 tipId 推进 3s 冷却（`abort()` 不写 tipId 冷却，
     * 手动中断会让刚做完的任务立刻有资格重派）。这里只撤卡片。
     */
    private val hideAchieveRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        binding.llAchieveCard.visibility = LinearLayout.GONE
        achieveCardPinned = false
    }

    // ---------------- 隐私同意与相机可用性（v3.0.0 阶段 2） ----------------

    /** 是否已同意 v1 隐私政策。false → 受限模式：不申请相机权限、不启动 CameraEngine */
    private var privacyAccepted = false

    /** 本次会话是否已经弹过系统相机权限请求。避免每次 onResume 都重新弹一次 */
    private var cameraPermissionAsked = false

    /** 用户点了「悬浮教练」，正在等通知权限的回调 */
    private var pendingCoachStart = false

    /** 上一次按返回键的时刻（双击退出用） */
    private var lastBackAt = 0L

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 结果不在这里分支：refreshCameraState() 会把「已授予/已拒绝」两种情况都处理掉，
            // 避免"权限回调里再写一遍启动逻辑"和 onCreate/onResume 里的那份走样。
            cameraPermissionAsked = true
            refreshCameraState()
        }

    /**
     * 通知权限（Android 13+）。
     *
     * 只在用户**第一次启动悬浮教练**时请求 —— 一进 App 就弹通知权限，用户不知道你要它干嘛，
     * 拒绝率极高；而悬浮教练是唯一会用到通知的功能（前台服务常驻通知）。
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!pendingCoachStart) return@registerForActivityResult
            pendingCoachStart = false
            if (granted) {
                launchScreenCapture()
            } else {
                showNotificationDeniedDialog()
            }
        }

    /**
     * Android 9 及以下写相册需要存储权限。
     * 无论用户是否授权都继续拍照：拒绝时退化为缓存文件，不阻塞快门。
     */
    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            capturePhoto()
        }

    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                startCoachService(res.resultCode, data)
            } else {
                Toast.makeText(this, "没有录屏权限，悬浮卡起不来", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()
        applyPanelInsets()
        applyControlBarInsets()
        // installFramingLine() 已删除（v1.7）：tvFraming 现在由 XML 静态声明。
        // 原实现靠 `binding.tvCompose.parent as? LinearLayout` 猜位置插 view，
        // 布局一改就静默失效且不报错 —— 本次 P0-1/P0-2 正好改布局，那种写法必挂。
        setupVolumeShutterSwitch()
        setupWatermarkSwitch()
        setupReviewCard()
        setupMissionUi()
        orientationTracker = OrientationTracker(this) { pitch, roll ->
            onPitchRoll(pitch, roll)
        }
        levelSensor = LevelSensor(this) { reading ->
            levelRoll = reading?.roll
            levelPitch = reading?.pitch
        }
        setupCoachSlot()
        // 密度档位从设置里读，默认 STANDARD（ARCH S8 / Q4）。
        //
        // 三个档位的语义固定不变：QUIET 只放行 URGENT，STANDARD 放 URGENT+CONTEXT，
        // CHATTER 全放（含 GUIDE 的教学轮播）。规则归属也固定：R09 运镜与 R11 开网格是
        // CONTEXT（当前场景强相关），只有 R10 兜底轮播是 GUIDE。
        //
        // v1.5.0 之前这里硬编码 STANDARD 且全项目只有这一处调用 `setDensity` ——
        // 于是 R10 及其 FALLBACK_TEXT 七条文案在生产环境一次都不会出现（QA P1-2）。
        // 现在档位由功能页的三档选择器驱动，`setupDensitySelector()` 负责读写。
        setupDensitySelector()

        // 先探测设备，这一步不依赖相机绑定，界面能立刻知道「手上是什么机器」
        profile = HardwareProbe.probe(this)
        caps = profile.caps

        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnLock.setOnClickListener { toggleLock() }
        binding.btnAuto.setOnClickListener { restoreAuto() }
        binding.btnGuide.setOnClickListener { toggleGuide() }
        binding.btnCopy.setOnClickListener { copyParams() }
        binding.btnHelp.setOnClickListener { showHelp() }
        // 档位切换入口（v1.9 P1-2）：tvMode 从"状态显示"改成"点一下换档"。
        // 状态本来就有更合适的载体 —— btnMode / btnLock 的文案。
        loadUiPreset()
        binding.tvMode.setOnClickListener { cycleUiPreset() }
        // 低频 4 键折叠（v1.8 P1-4）。默认收起、状态不持久化：
        // "复制参数"和"怎么拨"是学一次就够的动作，不该和每次调参都点的"本机写入"平级。
        binding.btnMore.setOnClickListener {
            val expanding = binding.llMoreActions.visibility != LinearLayout.VISIBLE
            binding.llMoreActions.visibility =
                if (expanding) LinearLayout.VISIBLE else LinearLayout.GONE
            binding.btnMore.text = if (expanding) "更多 ⌃" else "更多 ⌄"
        }
        // 说明页的两个出口。只有点「开始」才记 shown —— 用户点了取消，
        // 下次再点悬浮教练应该还能看到这页说明，而不是被永久跳过。
        binding.btnCoachIntroStart.setOnClickListener {
            uiPrefs.edit().putBoolean(KEY_COACH_INTRO_SHOWN, true).apply()
            binding.llCoachIntro.visibility = LinearLayout.GONE
            beginCoachSetup()
        }
        binding.btnCoachIntroCancel.setOnClickListener {
            binding.llCoachIntro.visibility = LinearLayout.GONE
        }
        binding.btnExportDiag.setOnClickListener { exportDiagnostics() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnAboutClose.setOnClickListener { binding.llAbout.visibility = LinearLayout.GONE }
        // 复用同一个 llPrivacyFull —— 与首次覆盖层读的是同一份 res/raw/privacy_v1.txt。
        // 关掉全文页时会自然露出底下的关于页（z 序：关于页声明在隐私页之前）。
        binding.btnAboutPolicy.setOnClickListener {
            binding.llPrivacyFull.visibility = LinearLayout.VISIBLE
        }

        binding.btnShutter.setOnClickListener { onShutterClick() }
        binding.btnShutter.setOnLongClickListener {
            shareLastShot()
            true
        }
        binding.btnCopyVideo.setOnClickListener { copyVideoPlan() }
        binding.btnFloat.setOnClickListener { startFloatingCoach() }
        binding.btnFloat.setOnLongClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            Toast.makeText(this, "悬浮教练已停止", Toast.LENGTH_SHORT).show()
            true
        }

        binding.tabPhoto.setOnClickListener { selectTab(TAB_PHOTO) }
        binding.tabVideo.setOnClickListener { selectTab(TAB_VIDEO) }
        binding.tabFeature.setOnClickListener { selectTab(TAB_FEATURE) }
        binding.tabSkill.setOnClickListener { selectTab(TAB_SKILL) }

        // 用户实际用原生相机拍，这个界面要一直亮着方便边看边拨
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildIntentChips()
        buildSkillChips()
        selectTab(TAB_PHOTO)
        renderDeviceInfo()
        renderFeatures()

        // 隐私门必须在任何相机动作之前：覆盖层先就位，再让 refreshCameraState()
        // 决定"要不要申请权限 / 要不要启动 CameraEngine"。
        setupPrivacyGate()
        refreshCameraState()
    }

    // ---------------- G1 任务卡：UI 装配 ----------------

    /**
     * G1 新增的三个交互入口。
     *
     * 刻意不放进 [applyUiPreset] 之外的第二个地方：每个卡片/按钮的显隐只有一处拥有者，
     * 否则又会回到 P1-a 那个"谁最后执行谁说了算"的坑。
     */
    private fun setupMissionUi() {
        // 参数折叠头：点整行切换展开 / 收起
        binding.llParamsHeader.setOnClickListener {
            paramsExpanded = binding.llParams.visibility != LinearLayout.VISIBLE
            applyUiPreset()
        }

        // 到位震动开关（规格 §4.2：震动是可感知的打扰，必须给关闭出口）
        binding.swTaskBuzz.isChecked = uiPrefs.getBoolean(KEY_TASK_BUZZ, true)
        binding.swTaskBuzz.setOnCheckedChangeListener { _, checked ->
            uiPrefs.edit().putBoolean(KEY_TASK_BUZZ, checked).apply()
        }

        // 点成绩卡任意位置 → 常驻（与 llReviewCard 的既有行为完全一致）
        binding.llAchieveCard.setOnClickListener {
            uiHandler.removeCallbacks(hideAchieveRunnable)
            achieveCardPinned = true
        }
        binding.btnAchieveAgain.setOnClickListener {
            hideAchieveCard()
            abortMission(AbortReason.USER)
        }
        binding.btnAchieveDex.setOnClickListener {
            hideAchieveCard()
            // G1 阶段跳现有技法页（技法图鉴是 G3，不留一个点了没反应的按钮）
            selectTab(TAB_SKILL)
        }
    }

    // ---------------- G1 任务卡：状态机接线 ----------------

    /**
     * **所有中断任务的唯一入口**（规格 §6.3）。
     *
     * 除了状态机回 IDLE，还顺手清掉 overlay 上的任务痕���（高亮交点 + 环）：
     * 只 `abort()` 不清 Mission，等网格重新打开时任务态早已结束，
     * 画面上却还挂着一个没有任务在管的灰环 —— 那是"指引残留"，比没有更误导人。
     */
    private fun abortMission(reason: AbortReason) {
        missionEngine.abort(reason)
        binding.guideOverlay.clearMission()
        renderTaskSurface()
    }

    /**
     * 唯一的判定节拍。
     *
     * 挂在 `renderComposition()` 的 400ms 节流上（规格 §3.5），不另起 Handler，
     * 也不放进 10Hz 的 `onPitchRoll()` —— 后两者都会让 hits 计数与渲染错位。
     */
    private fun tickMission(stats: FrameStats, comp: CompositionHint) {
        if (isFinishing || isDestroyed) return
        val event = missionEngine.onSample(
            MissionSample(
                now = SystemClock.elapsedRealtime(),
                stats = stats,
                hint = comp,
                roll = levelRoll,
                recs = lastScoreResult?.recommendations.orEmpty(),
                aspect = overlayAspect()
            )
        )
        onMissionEvent(event)
    }

    /**
     * 状态机事件 → UI。
     *
     * **事件只是"要不要重绘"的提示，UI 的真相永远读 `missionEngine.state`** ——
     * 同一个 `HIDE` 可能对应"任务卡 → 成绩卡"，也可能对应"成绩卡 → 全收起"。
     */
    private fun onMissionEvent(e: MissionEvent) {
        val task = missionEngine.current
        when (e) {
            MissionEvent.NONE, MissionEvent.PROGRESS -> Unit   // 只更新下面的环与实时分
            MissionEvent.SHOW -> {
                if (task != null) {
                    setTextOnce(binding.tvTaskTitle, task.title)
                    setTextOnce(binding.tvTaskHint, task.hint)
                    lastComp?.let {
                        binding.guideOverlay.setMission(kindOf(task.kind), it.targetX, it.targetY)
                    }
                    taskStartTotal = scoreTotalIfMeasured(lastScoreResult)
                    fadeInCard(binding.llTaskCard)
                }
            }
            MissionEvent.ACHIEVED -> {
                binding.guideOverlay.markAchieved()
                buzzAchieved()
                setTextOnce(binding.tvTaskTitle, "✓ 到位")
                setTextOnce(binding.tvTaskHint, "${task?.skillName ?: ""} · 保持住")
                // 把这条技法推进 8s 冷却：否则任务一结束，教练槽立刻弹出刚做完的那条
                if (task != null) {
                    promptScheduler.markExecuted(task.tipId, SystemClock.elapsedRealtime())
                }
            }
            MissionEvent.TIMEOUT -> swapTaskCard(task)
            MissionEvent.HIDE -> {
                hideTaskCard()
                if (missionEngine.state == MissionState.SCORED) {
                    showAchieveCard()
                } else {
                    hideAchieveCard()
                }
            }
        }
        // 环的绿意每 tick 都推：PROGRESS 与 NONE 都要，它是"再挪一点点"的唯一线索
        lastComp?.let {
            binding.guideOverlay.setMissionProgress(
                it.subjectX,
                it.subjectY,
                missionEngine.ringProgress()
            )
        }
        setTextOnce(binding.tvTaskScore, scoreHeadOnly(lastScoreResult))
        renderTaskSurface()
    }

    /**
     * **任务卡与教练槽的表面仲裁**（规格 §6.3(2)）。
     *
     * 所有可能改变 `llTaskCard` / `llAchieveCard` / `llCoachSlot` 可见性的路径
     * 都必须过这里 —— 这是"两条建议同屏"这条 bug 的唯一防线。优先级：
     *   ① 相机不可用 / 隐私未同意 / 非照片页 / 拍照中 / 网格关闭 → 卡片全 GONE
     *   ② 任务引擎占用表面 → 任务卡（或成绩卡）VISIBLE + 教练槽 GONE
     *   ③ 其余 → 卡片 GONE，教练槽交还 PromptScheduler
     */
    private fun renderTaskSurface() {
        val usable = privacyAccepted &&
            currentTab == TAB_PHOTO &&
            engine != null &&
            binding.llCameraError.visibility != View.VISIBLE &&
            !captureInProgress &&
            guidesOn

        // 三重保险之一：任务占用表面期间冻结教练槽的调度器。
        // `pause()` 只是一个布尔赋值，幂等，所以每 tick 调用都是安全的。
        promptScheduler.pause(missionEngine.occupiesSurface())

        if (!usable) {
            setVis(binding.llTaskCard, View.GONE)
            setVis(binding.llAchieveCard, View.GONE)
            // 只在任务仍占着表面时才硬压教练槽；IDLE 时它的主人是 PromptScheduler，
            // 在这里抢会让 renderCoachSlot 与本函数互相打架（每 tick 翻一次可见性）。
            if (missionEngine.occupiesSurface()) setVis(binding.llCoachSlot, View.GONE)
            return
        }
        when (missionEngine.state) {
            MissionState.IDLE -> {
                setVis(binding.llTaskCard, View.GONE)
                setVis(binding.llAchieveCard, View.GONE)
                // 教练槽交还给 PromptScheduler：不去 setVisibility，让它自己决定
            }
            MissionState.TASK_SHOWN, MissionState.IN_PROGRESS, MissionState.ACHIEVED -> {
                setVis(binding.llTaskCard, View.VISIBLE)
                setVis(binding.llAchieveCard, View.GONE)
                setVis(binding.llCoachSlot, View.GONE)          // 三重保险之二：硬压
            }
            MissionState.SCORED -> {
                setVis(binding.llTaskCard, View.GONE)
                setVis(binding.llAchieveCard, View.VISIBLE)
                setVis(binding.llCoachSlot, View.GONE)
            }
        }
    }

    /** 20s 未达标换一条（规格 §2.4）：同一张卡换句话，不做位移，不弹任何失败提示。 */
    private fun swapTaskCard(task: MissionTask?) {
        if (task == null) {
            hideTaskCard()
            return
        }
        setTextOnce(binding.tvTaskTitle, task.title)
        setTextOnce(binding.tvTaskHint, task.hint)
        lastComp?.let {
            binding.guideOverlay.setMission(kindOf(task.kind), it.targetX, it.targetY)
        }
        taskStartTotal = scoreTotalIfMeasured(lastScoreResult)
        val card = binding.llTaskCard
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .setDuration(TASK_SWAP_OUT_MS)
            .withEndAction { card.animate().alpha(1f).setDuration(TASK_FADE_IN_MS).start() }
            .start()
    }

    private fun hideTaskCard() {
        setVis(binding.llTaskCard, View.GONE)
        binding.llTaskCard.animate().cancel()
        binding.llTaskCard.alpha = 1f
        // 连带撤掉画面上的任务环与高亮交点：没有任务时不该留一坨绿圈在取景器上
        binding.guideOverlay.clearMission()
    }

    /**
     * 成绩卡（规格 §4.3）。
     *
     * **评分口径守门**：任一维度未测时必须显示「参考分 -- · 有一项未测」且**不显示 +N**
     * —— 分母不完整时不给数字，更不给差值。
     */
    private fun showAchieveCard() {
        val task = missionEngine.current ?: return
        val result = lastScoreResult
        val allMeasured = scoreTotalIfMeasured(result) != null

        setTextOnce(
            binding.tvAchieveScore,
            if (allMeasured && result != null) {
                val head = "参考分 ${result.total} · ${result.grade}"
                val start = taskStartTotal
                val delta = if (start != null) result.total - start else 0
                if (delta != 0) "$head · ${if (delta > 0) "+" else ""}$delta" else head
            } else {
                "参考分 -- · 有一项未测"
            }
        )
        setTextOnce(binding.tvAchieveReason, "你刚才做对了：${task.skillName}")

        achieveCardPinned = false
        fadeInCard(binding.llAchieveCard)
        uiHandler.removeCallbacks(hideAchieveRunnable)
        uiHandler.postDelayed(hideAchieveRunnable, SCORE_CARD_MS)
    }

    private fun hideAchieveCard() {
        achieveCardPinned = false
        uiHandler.removeCallbacks(hideAchieveRunnable)
        setVis(binding.llAchieveCard, View.GONE)
        binding.llAchieveCard.animate().cancel()
        binding.llAchieveCard.alpha = 1f
    }

    /**
     * 到位震动 15ms（规格 §4.2）。
     *
     * 三道降级，缺一不可：开关关掉不震、无马达的设备跳过、ROM 抛异常吞掉。
     * 它是"到位的第二个通道"（眼睛在画面上），但**从不单独承载信息** ——
     * 环同时还会变绿 + 线宽加倍 + 多一个中心实心点 + 文案变「✓ 到位」。
     */
    private fun buzzAchieved() {
        if (!uiPrefs.getBoolean(KEY_TASK_BUZZ, true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuzzAt < 800L) return                       // 防连震
        val vib = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        if (!vib.hasVibrator()) return                            // 平板 / 模拟器直接跳过
        lastBuzzAt = now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(
                    VibrationEffect.createOneShot(ACHIEVE_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ACHIEVE_BUZZ_MS)                      // API 24 / 25 路径
            }
        } catch (t: Throwable) {
            Diag.w(TAG, "到位震动失败", t)                          // ROM 差异，吞掉即可
        }
    }

    private fun kindOf(kind: TaskKind): Int = when (kind) {
        TaskKind.COMPOSITION -> GuideOverlayView.TASK_COMPOSITION
        TaskKind.LEVEL -> GuideOverlayView.TASK_LEVEL
        TaskKind.HEADROOM -> GuideOverlayView.TASK_HEADROOM
    }

    /**
     * 取景叠加层的高宽比 h/w。
     *
     * 判定用的 `d` 是以画面短边为单位的，overlay 是竖屏（h > w），所以这里直接用 h/w，
     * 与 `TaskPicker.distToTarget()` 的口径一致 —— 判定线和画出来的环是同一套映射。
     */
    private fun overlayAspect(): Float {
        val w = binding.guideOverlay.width
        val h = binding.guideOverlay.height
        if (w <= 0 || h <= 0) return 16f / 9f
        return h.toFloat() / w.toFloat()
    }

    /** 参考分；**任一维度未测时返回 null**，调用方据此显示「参考分 --」。 */
    private fun scoreTotalIfMeasured(result: ScoreResult?): Int? {
        if (result == null) return null
        if (!result.dimensions.all { it.measured }) return null
        return result.total
    }

    /** 任务卡右上角的实时参考分。与 [formatScore] 的第���行同口径，未测时显示「参考分 --」。 */
    private fun scoreHeadOnly(result: ScoreResult?): String {
        val total = scoreTotalIfMeasured(result)
        return if (total != null) "参考分 $total" else "参考分 --"
    }

    /** 只在值真的变了才写控件：400ms 一次的刷新不该每次都触发一遍 measure/layout。 */
    private fun setTextOnce(view: TextView, text: String) {
        if (view.text?.toString() == text) return
        view.text = text
    }

    /** 同上：可见性没变就不 setVisibility（避免每 tick 触发一次 requestLayout）。 */
    private fun setVis(view: View, visibility: Int) {
        if (view.visibility != visibility) view.visibility = visibility
    }

    /** 120ms 淡入。与既有复盘卡/白闪同一档的量级，远低于 A4 的 400ms 上限。 */
    private fun fadeInCard(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(TASK_FADE_IN_MS).start()
    }

    // ---------------- 隐私同意覆盖层（v3.0.0 阶段 2） ----------------

    /**
     * 装配覆盖层与它的三个出口，并按存档决定首次是否显示。
     *
     * 只做「显示什么」，不做「相机怎么办」—— 后者全部交给 [refreshCameraState]，
     * 这样"同意 / 拒绝 / 从设置页回来"三条路径都收敛到同一个判断点。
     */
    private fun setupPrivacyGate() {
        privacyAccepted = uiPrefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)

        binding.tvPrivacyFull.text = readPrivacyText()
        binding.btnPrivacyFull.setOnClickListener { binding.llPrivacyFull.visibility = View.VISIBLE }
        binding.btnPrivacyClose.setOnClickListener { binding.llPrivacyFull.visibility = View.GONE }
        binding.btnPrivacyAgree.setOnClickListener { acceptPrivacy() }
        binding.btnPrivacyDeny.setOnClickListener { declinePrivacy() }
        // 占位上的按钮：隐私态重新弹同意，权限态去设置（由 onCameraOffAction 分流）
        binding.btnCameraOffAction.setOnClickListener { onCameraOffAction() }
        binding.btnCameraRetry.setOnClickListener { retryCamera() }

        // 返回键四层处理（v3.0.0 阶段 3，U7）。用 OnBackPressedDispatcher，
        // 不覆写已废弃的 onBackPressed()——Manifest 里 enableOnBackInvokedCallback=true
        // 之后，Android 13+ 只认 dispatcher 这条路。
        //
        // 优先级自上而下：
        //   ① 隐私全文页 → 收回全文页（不退出）
        //   ② 关于页    → 收回关于页
        //   ③ 隐私门    → **不拦截**：用户还没做选择，返回就该离开，下次启动重问
        //   ④ 教练说明页 → 收回说明页
        //   ⑤ 非照片页签 → 切回照片页（这是最常见的误触：在技法页翻完想回拍照）
        //   ⑥ 照片页    → 双击退出（2 秒内第二次才真退出，避免误触丢状态）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.llPrivacyFull.visibility == View.VISIBLE ->
                        binding.llPrivacyFull.visibility = View.GONE

                    // 关于页在隐私全文页之下、教练说明页之上，单独一层
                    binding.llAbout.visibility == View.VISIBLE ->
                        binding.llAbout.visibility = View.GONE

                    binding.llPrivacyGate.visibility == View.VISIBLE -> passThrough()

                    binding.llCoachIntro.visibility == View.VISIBLE ->
                        binding.llCoachIntro.visibility = View.GONE

                    currentTab != TAB_PHOTO -> selectTab(TAB_PHOTO)

                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackAt < BACK_EXIT_MS) {
                            passThrough()
                        } else {
                            lastBackAt = now
                            Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            /**
             * 交回系统默认行为。必须先把自己关掉，否则 onBackPressed() 会重新派发到
             * 这个回调上，形成死循环把栈打爆。
             */
            private fun passThrough() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        if (!privacyAccepted) showPrivacyGate()
    }

    private fun showPrivacyGate() {
        binding.llPrivacyFull.visibility = View.GONE
        binding.llPrivacyGate.visibility = View.VISIBLE
    }

    /** 同意：落盘 → 收覆盖层 → 立刻按新状态起相机（用户不该再点第二次） */
    private fun acceptPrivacy() {
        privacyAccepted = true
        uiPrefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
        binding.llPrivacyFull.visibility = View.GONE
        binding.llPrivacyGate.visibility = View.GONE
        refreshCameraState()
    }

    /**
     * 暂不同意：**不落盘 false**（本来就是 false），只收覆盖层并进入受限模式。
     *
     * 刻意不写 `putBoolean(false)`：键不存在与值为 false 语义相同，
     * 少一次写盘、也避免"用户从没同意过"被判成"用户明确拒绝过"。
     */
    private fun declinePrivacy() {
        privacyAccepted = false
        binding.llPrivacyFull.visibility = View.GONE
        binding.llPrivacyGate.visibility = View.GONE
        refreshCameraState()
        Toast.makeText(this, "已进入受限模式，点取景区可随时重新开启相机", Toast.LENGTH_LONG).show()
    }

    /** 读 raw 里的政策全文。读不到就退化成一句可读提示，不让覆盖层白屏。 */
    private fun readPrivacyText(): String = try {
        resources.openRawResource(R.raw.privacy_v1).bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        Diag.w(TAG, "隐私政策全文读取失败", e)
        "隐私政策读取失败。请通过 1912811308@qq.com 联系我们索取全文。"
    }

    /**
     * **相机可用性的唯一判定处**（架构师 R3/R7 提到的三处散落逻辑合并）。
     *
     * 收敛顺序：
     * 1. 没同意隐私 → 占位（"隐私未同意"态），**不申请权限、不建引擎**；
     * 2. 同意了但没权限 → 占位（"权限被拒"态）+ 只在本次会话第一次时弹系统弹窗；
     * 3. 同意了且有权限 → 占位收起、引擎没建就建（幂等，可从 onResume 反复调）。
     */
    private fun refreshCameraState() {
        if (isFinishing || isDestroyed) return

        if (!privacyAccepted) {
            showCameraOff(
                CameraOff.PRIVACY,
                "相机已关闭",
                "你选择了暂不同意隐私政策，所以相机、拍照与画面分析都不会启动。\n" +
                    "技法库、录像方案与功能建议仍然可以正常查看。",
                    "重新开启相机"
                )
                // G1：相机不可用时必须收掉任务卡，否则会出现"相机关着还弹任务卡"
                abortMission(AbortReason.CAMERA_OFF)
                return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showCameraOff(
                CameraOff.PERMISSION,
                "需要相机权限",
                "没有相机权限就拿不到画面，曝光与构图都无从判断。\n" +
                    "其余页面（技法、录像方案、功能建议）不受影响。",
                "去设置授权"
            )
            abortMission(AbortReason.CAMERA_OFF)
            if (!cameraPermissionAsked) {
                cameraPermissionAsked = true
                requestCamera.launch(Manifest.permission.CAMERA)
            }
            return
        }

        binding.llCameraOff.visibility = LinearLayout.GONE
        binding.guideOverlay.visibility = View.VISIBLE
        applyShutterAvailability()
        if (engine == null) startCamera()
    }

    /** 取景占位的两种原因。行动建议不同，所以按钮文案与点击行为都要跟着换 */
    private enum class CameraOff { PRIVACY, PERMISSION }

    private var cameraOffReason = CameraOff.PRIVACY

    private fun showCameraOff(reason: CameraOff, title: String, body: String, action: String) {
        cameraOffReason = reason
        binding.tvCameraOffTitle.text = title
        binding.tvCameraOffBody.text = body
        binding.btnCameraOffAction.text = action
        binding.llCameraOff.visibility = LinearLayout.VISIBLE
        binding.guideOverlay.visibility = View.GONE
        binding.btnShutter.isEnabled = false
        binding.btnShutter.alpha = 0.4f
    }

    /** 占位上的唯一按钮：隐私态重新弹同意，权限态去系统设置 */
    private fun onCameraOffAction() {
        when (cameraOffReason) {
            CameraOff.PRIVACY -> showPrivacyGate()
            CameraOff.PERMISSION -> {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
    }

    // ---------------- 面板 inset 响应（v1.8 P2-2） ----------------

    /**
     * 面板底部让开系统导航栏。
     *
     * 沉浸模式下系统栏平时是收起的，但用户从边缘上滑手势时它会**瞬时滑出**，
     * 那一瞬间会压住面板最后一行（原来是固定 12dp margin，完全不响应 inset）。
     * 这里把 inset 加到 paddingBottom 上，并用 `updatePadding` 保留原有的 14dp 内边距。
     */
    private fun applyPanelInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.panel) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = dp(PANEL_PADDING_DP) + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.panel)
    }

    // ---------------- 音量键快门（v1.7 P0-3） ----------------

    /**
     * 音量下键当快门。
     *
     * 三个刻意的边界，写清楚免得后人"顺手修正"：
     * 1. **只拦 VOLUME_DOWN，VOLUME_UP 原样放行**。全拦会让用户在 App 内无法调音量，
     *    投诉成本远高于收益 —— 而且用户想调音量时不会先切走 App。
     * 2. **只认 ACTION_DOWN + repeatCount == 0**。长按音量键会以 repeatCount>0 连发，
     *    那样按住不放会变成"一直按快门"，比不响应更糟。
     * 3. **只在照片页 + 相机就绪时吞掉按键**。其余情况走 super，保留系统音量面板。
     *
     * 已知限制：悬浮卡阶段我们在后台、没有输入焦点，收不到按键 —— 这是系统限制，不绕。
     * 无障碍（TalkBack）下音量键另有语义，所以功能页给了开关，默认开。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val firstPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (isVolumeDown && firstPress &&
            currentTab == TAB_PHOTO &&
            engine != null &&
            !captureInProgress &&
            uiPrefs.getBoolean(KEY_VOLUME_SHUTTER, true)
        ) {
            onShutterClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 功能页的「音量键拍照」开关。默认开，改档立即落盘（下次冷启动仍然生效）。 */
    private fun setupVolumeShutterSwitch() {
        binding.swVolumeShutter.isChecked = uiPrefs.getBoolean(KEY_VOLUME_SHUTTER, true)
        binding.swVolumeShutter.setOnCheckedChangeListener { _, checked ->
            uiPrefs.edit().putBoolean(KEY_VOLUME_SHUTTER, checked).apply()
        }
    }

    /**
     * 功能页的「成绩水印」开关。默认关（先小范围用），改档立即落盘。
     *
     * 只影响**之后**拍的照片：已经写进相册的那张改不动（我们不再持有它的可写句柄），
     * 这一点写在副标题里，避免用户以为开关能追溯。
     */
    private fun setupWatermarkSwitch() {
        binding.swWatermark.isChecked = uiPrefs.getBoolean(KEY_WATERMARK, false)
        binding.swWatermark.setOnCheckedChangeListener { _, checked ->
            uiPrefs.edit().putBoolean(KEY_WATERMARK, checked).apply()
        }
    }

    /** 水印是否开启。每次拍照时读一次，不缓存 —— 设置改了下一张就生效。默认关。 */
    private fun watermarkEnabled(): Boolean = uiPrefs.getBoolean(KEY_WATERMARK, false)

    /** 复盘卡：点一下保持常驻，点「看技法」跳技法页（跳转本身也算"我在看"，先取消收起） */
    private fun setupReviewCard() {
        binding.llReviewCard.setOnClickListener { uiHandler.removeCallbacks(hideReviewRunnable) }
        binding.btnReviewSkill.setOnClickListener {
            uiHandler.removeCallbacks(hideReviewRunnable)
            binding.llReviewCard.visibility = LinearLayout.GONE
            selectTab(TAB_SKILL)
        }
    }

    /** 相机应用该有的样子：不留状态栏和导航栏，把可视面积全给取景器 */
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // targetSdk 35 起系统强制 edge-to-edge，导航栏图标色必须显式声明（v3.0.0 阶段 3）：
        // 深色底上要浅色图标。不写的话，部分 ROM 会保留"浅色底用深图标"的记忆值，
        // 手势条滑出时会出现一条几乎看不见的白色图标。
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    /**
     * 底部控制条让开系统手势条（v3.0.0 阶段 3）。
     *
     * 之前只有 `panel` 做了 inset，`llControlBar` 没有 —— 手势导航条瞬时滑出时
     * 会压住 72dp 的快门下沿。这里同样把 inset 加到 paddingBottom 上（保留原有 6dp）。
     */
    private fun applyControlBarInsets() {
        val base = dp(CONTROL_BAR_PADDING_DP)
        ViewCompat.setOnApplyWindowInsetsListener(binding.llControlBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = base + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.llControlBar)
    }

    private fun startCamera() {
        val eng = CameraEngine(
            activity = this,
            previewView = binding.previewView,
            onStats = { stats -> onFrame(stats) },
            onReady = { c -> runOnUiThread { onCameraReady(c) } },
            onBitmap = { bmp ->
                // analyze 的回调已经在主线程，这里直接更新状态即可
                aiVision.analyze(bmp) { r ->
                    aiResult = r
                    if (currentTab == TAB_FEATURE) renderFeatures()
                }
            },
            // 相机起不来时给一条可见的错误条。回调可能在主线程也可能不在，统一 post 一次。
            onError = { failure -> runOnUiThread { showCameraError(failure) } }
        )
        engine = eng
        eng.start()
    }

    private fun onCameraReady(c: CameraCaps) {
        caps = c
        hideCameraError()
        renderDeviceInfo()
        // 默认教练模式：只给数，不抢相机。用户拿原生相机拍，这里负责测光和教构图。
        autoMode = false
        updateModeUi()
        renderFeatures()
    }

    // ---------------- 相机失败的可见反馈（v3.0.0 阶段 3） ----------------

    /**
     * 相机被占用是**常态**（微信视频、别的相机 App 没退干净），黑屏无提示是最差的处理。
     *
     * 两种原因给两句不同的话，因为用户能做的事不同：占了就"关掉别的应用再试"，
     * 未知就只能"重试一次看看"。
     */
    private fun showCameraError(failure: CameraEngine.Failure) {
        if (isFinishing || isDestroyed) return
        binding.tvCameraErrorTitle.text = "相机打不开"
        binding.tvCameraErrorBody.text = when (failure) {
            CameraEngine.Failure.IN_USE -> "摄像头正被其他应用占用（微信视频、系统相机没退出等）。关掉之后再试。"
            CameraEngine.Failure.UNKNOWN -> "初始化失败。可以先点重试；反复失败请在功能页导出诊断信息。"
        }
        binding.llCameraError.visibility = LinearLayout.VISIBLE
        // 定格在错误态：快门按下去只会再报一次同样的错
        binding.btnShutter.isEnabled = false
        binding.btnShutter.alpha = 0.4f
    }

    private fun hideCameraError() {
        binding.llCameraError.visibility = LinearLayout.GONE
        applyShutterAvailability()
    }

    /**
     * 重试：先把旧引擎彻底扔掉再重建。
     *
     * 不 `engine.stop()` 直接 new 的话，同一个 ProcessCameraProvider 上会留下
     * 上一次绑定失败的 UseCase，第二次大概率继续失败。
     */
    private fun retryCamera() {
        binding.llCameraError.visibility = LinearLayout.GONE
        val old = engine
        engine = null
        try {
            old?.stop()
        } catch (e: Throwable) {
            Diag.w(TAG, "重试前停止旧引擎失败", e)
        }
        refreshCameraState()
    }

    // ---------------- 设备档案 ----------------

    private fun renderDeviceInfo() {
        val v = profile.video
        val sb = StringBuilder()
        sb.append(profile.modelName).append(" · ").append(profile.osLabel)
        sb.append("\n主摄 ").append(profile.main?.label ?: "信息缺失")
        sb.append(" · 等效 ").append(profile.equivFocalMm).append("mm")
        sb.append(" · ").append(if (profile.supportsManualPhoto) "可手动曝光" else "仅自动曝光")
        sb.append("\nISO ").append(profile.caps.isoMin).append("–").append(profile.caps.isoMax)
        sb.append(" · 录像最高 ").append(v.maxLabel)
        if (v.fps4k > 0) sb.append(" · 4K").append(v.fps4k)
        if (v.maxHighSpeedFps > 0) sb.append(" · 慢动作 ").append(v.maxHighSpeedFps)
        if (profile.lenses.size > 1) {
            sb.append("\n镜头 ").append(profile.lenses.joinToString(" ") { "${it.equivFocalMm}mm" })
        }
        binding.tvCaps.text = sb.toString()
    }

    // ---------------- 页签 ----------------

    private fun selectTab(idx: Int) {
        currentTab = idx

        // G1：页签切换的任务语义（规格 §6.3(4)）。
        // 切走 → 立刻中断（任务环画在 guideOverlay 上，技法页根本没有画面让它依附）；
        // 切回 → 只放开 pick 的资格，真正的派发等下一次 renderComposition，
        //       TASK_SHOWN 的 800ms 缓冲保证不会"切回来就闪一张卡"。
        if (idx == TAB_PHOTO) {
            missionEngine.resume()
        } else {
            abortMission(AbortReason.TAB)
        }
        renderTaskSurface()

        binding.pagePhoto.visibility = if (idx == TAB_PHOTO) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.pageVideo.visibility = if (idx == TAB_VIDEO) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.pageFeature.visibility = if (idx == TAB_FEATURE) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.pageSkill.visibility = if (idx == TAB_SKILL) LinearLayout.VISIBLE else LinearLayout.GONE

        // 屏幕快门与音量键快门必须同口径（v1.7.1 P1-c）：两者都只在照片页可用。
        // 以前屏幕快门没有 tab 守卫，录像/功能/技法页点它也能拍，而音量键却被
        // dispatchKeyEvent 挡掉了 —— 同一个动作两个入口两种行为。
        //
        // 刻意用 isEnabled + alpha 而不是隐藏 llControlBar：panel 的底边约束指向
        // llControlBar，隐藏它会让 panel 失去锚点，又回到 P1-a 那个"整块塌掉"的坑。
        applyShutterAvailability()

        val tabs = arrayOf(binding.tabPhoto, binding.tabVideo, binding.tabFeature, binding.tabSkill)
        for (i in tabs.indices) {
            val tv = tabs[i]
            if (i == idx) {
                tv.setBackgroundResource(R.drawable.bg_chip)
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_accent))
            } else {
                tv.background = null
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_text_dim))
            }
        }
        if (idx == TAB_VIDEO) renderVideo()
        if (idx == TAB_FEATURE) renderFeatures()
        if (idx == TAB_SKILL) renderSkills()
        // 切页后重新套用档位：上面的 page* 显隐与本函数管的那些 view 互不重叠，
        // 但 renderFeatures/renderSkills 会重建列表，重新套一次能保证档位状态收敛。
        applyUiPreset()
    }

    /**
     * 快门可用性 = 「当前在照片页」。屏幕快门与音量键快门共用这一条口径。
     *
     * 抽成函数是因为它有三个调用点：切页、拍照成功回调、拍照失败回调。
     * 后两处如果直接写 `isEnabled = true`，用户在拍照过程中切到录像页，
     * 回调回来又会把快门点亮 —— 表面能按、点了没反应（`dispatchKeyEvent` 那边
     * 依然是按 tab 判的），比一直灰着更难理解。
     */
    private fun applyShutterAvailability() {
        // 四个条件缺一不可：在照片页 + 已同意隐私 + 相机没报错 + 相机可用。
        // 最后一条会被 showCameraError 置上，否则切一次页签就会把快门重新点亮，
        // 用户按下去只会再收到一次"拍照失败"。
        val cameraBroken = binding.llCameraError.visibility == View.VISIBLE
        val usable = currentTab == TAB_PHOTO && privacyAccepted && !cameraBroken
        binding.btnShutter.isEnabled = usable
        binding.btnShutter.alpha = if (usable) 1f else 0.4f
    }

    // ---------------- 录像页 ----------------

    /** 意图胶囊。八个意图覆盖了绝大多数日常拍摄，选一个就够了，不做多层菜单。 */
    private fun buildIntentChips() {
        binding.llIntents.removeAllViews()
        for (intent in VideoIntent.values()) {
            val tv = TextView(this).apply {
                text = intent.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                gravity = Gravity.CENTER
                setOnClickListener {
                    videoIntent = intent
                    updateChipStyle()
                    renderVideo()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(6)
            binding.llIntents.addView(tv, lp)
        }
        updateChipStyle()
    }

    private fun updateChipStyle() {
        for (i in 0 until binding.llIntents.childCount) {
            val tv = binding.llIntents.getChildAt(i) as TextView
            val selected = VideoIntent.values()[i] == videoIntent
            if (selected) {
                tv.setBackgroundResource(R.drawable.bg_chip)
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_accent))
            } else {
                tv.background = null
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_text_dim))
            }
        }
    }

    private fun renderVideo() {
        val plan = VideoRecommender.plan(videoIntent, profile, lastStats, currentScene)
        currentPlan = plan

        binding.tvIntentDesc.text = "${videoIntent.oneLine} · ${plan.pathHint}"

        binding.tvVRes.text = plan.tier
        binding.tvVFps.text = "${plan.fps}"
        binding.tvVShutter.text = plan.shutterText
        binding.tvVBitrate.text = "${plan.bitrateMbps}M"

        binding.tvVStab.text = buildString {
            append("防抖 ").append(plan.stabilization)
            append(" · LOG ").append(if (plan.useLog) "开" else "关")
            append(" · ").append(plan.isoHint)
        }
        binding.tvVTips.text = plan.tips.joinToString("\n") { "· $it" }
        binding.tvVAudio.text = "声音：${plan.audioTip}"
    }

    private fun copyVideoPlan() {
        val plan = currentPlan ?: run {
            Toast.makeText(this, "还没生成方案", Toast.LENGTH_SHORT).show()
            return
        }
        val text = plan.clipboardText() + "\n" + plan.tips.joinToString("\n") { "· $it" }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("光影调参 · 录像", text))
        Toast.makeText(this, "已复制录像方案", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 功能页 ----------------

    private fun renderFeatures() {
        // 取局部快照：lastStats 是可变的，直接判空后使用会触发智能转换失败
        val snap = lastStats
        val list = if (snap == null) {
            FeatureAdvisor.capabilitySheet(profile)
        } else {
            FeatureAdvisor.advise(
                profile,
                snap,
                currentScene,
                needsTripod = lastRecommendation?.needsTripod == true
            )
        }
        currentAdvice = list
        binding.tvFeatureTitle.text = buildString {
            append(if (snap == null) "这台机器能玩什么" else "这会儿该开什么")
            val ai = aiResult
            if (ai != null && ai.ok && ai.tags.isNotEmpty()) {
                append(" · AI 看到 ")
                append(ai.tags.take(3).joinToString(" "))
            }
        }

        binding.llFeatures.removeAllViews()
        if (list.isEmpty()) {
            binding.llFeatures.addView(featureCard(null))
            return
        }
        // 实时建议最多 4 条：再多就成了说明书，用户不会读。
        // 首次能力清单固定 6 条，保证两条专业模式 KNOW 卡（长按锁 AE/AF、RAW+）可见。
        val limit = if (snap == null) 6 else 4
        for (s in list.take(limit)) {
            binding.llFeatures.addView(featureCard(s))
        }
    }

    private fun featureCard(s: FeatureSuggestion?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        card.layoutParams = lp

        if (s == null) {
            card.addView(labelText("画面信息还不足，等一秒再看", 12f, R.color.st_text_dim))
            return card
        }

        val mark = when (s.kind) {
            AdviceKind.DO -> "该用"
            AdviceKind.AVOID -> "别用"
            AdviceKind.KNOW -> "知道"
        }
        val color = when (s.kind) {
            AdviceKind.DO -> R.color.st_accent
            AdviceKind.AVOID -> R.color.st_warn
            AdviceKind.KNOW -> R.color.st_text_warm
        }
        card.addView(labelText("$mark · ${s.title}", 13f, color, bold = true))
        card.addView(labelText(s.reason, 11f, R.color.st_text_body))
        card.addView(labelText("▸ ${s.path}", 11f, R.color.st_text_dim))
        return card
    }

    /**
     * 小号文本的统一构造器。
     *
     * 第三参数是**颜色资源 id**（`@ColorRes`），不是字符串 —— v1.7.1 P2-a 之前这里
     * 收字符串再交给 Color 解析十六进制，于是"改调色板要全文搜索"这件事永远收不干净。
     */
    private fun labelText(t: String, size: Float, @ColorRes color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(1), 0, dp(1))
        }

    // ---------------- 技法页 ----------------

    /** 「全部」+ 15 个分类（`TechCategory` 当前枚举值个数）。写法和录像意图胶囊保持一致，选中态复用 bg_chip。 */
    private fun buildSkillChips() {
        binding.llSkillCats.removeAllViews()
        val labels = listOf("全部") + TechCategory.values().map { it.label }
        for ((index, label) in labels.withIndex()) {
            val tv = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentSkillCategory =
                        if (index == 0) null else TechCategory.values()[index - 1]
                    updateSkillChipStyle()
                    renderSkills()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(6)
            binding.llSkillCats.addView(tv, lp)
        }
        updateSkillChipStyle()
    }

    private fun updateSkillChipStyle() {
        for (i in 0 until binding.llSkillCats.childCount) {
            val tv = binding.llSkillCats.getChildAt(i) as TextView
            val selected = if (i == 0) {
                currentSkillCategory == null
            } else {
                currentSkillCategory == TechCategory.values()[i - 1]
            }
            if (selected) {
                tv.setBackgroundResource(R.drawable.bg_chip)
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_accent))
            } else {
                tv.background = null
                tv.setTextColor(ContextCompat.getColor(this, R.color.st_text_dim))
            }
        }
    }

    /** 技法页顶部的图鉴进度：已解锁 N / 83 + 进度条。每次渲染技法页时刷新。 */
    private fun renderDexProgress() {
        val unlocked = DexStore.unlockedCount(this)
        val total = DexStore.total()
        binding.tvDexProgress.text = "已解锁 $unlocked / $total"
        binding.pbDex.max = total
        binding.pbDex.progress = unlocked
    }

    private fun renderSkills() {
        renderDexProgress()
        val signals = buildTechSignals()
        val category = currentSkillCategory
        val list = if (category == null) {
            TechniqueMatcher.suggest(currentScene, signals)
        } else {
            TechniqueMatcher.byCategory(category)
        }

        binding.tvSkillTitle.text = when {
            category != null -> "「${category.label}」全部技法"
            !signals.hasStats -> "新手起步包"
            else -> "此刻最该注意的 ${list.size} 条"
        }

        binding.llSkills.removeAllViews()
        if (list.isEmpty()) {
            binding.llSkills.addView(labelText("画面信息不足，等一秒再看", 12f, R.color.st_text_dim))
            return
        }
        for (tech in list) {
            binding.llSkills.addView(skillCard(tech, signals))
        }
    }

    private fun skillCard(tech: Technique, signals: TechSignals): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showSkillDialog(tech) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        card.layoutParams = lp

        // 标题行：分类 · 标题，已解锁的在后面跟一个绿色「已解锁」标记（图鉴 G3）
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(labelText("${tech.category.label} · ${tech.title}", 13f, R.color.st_accent, bold = true))
        if (DexStore.isUnlocked(this, tech.id)) {
            head.addView(labelText("  已解锁", 11f, R.color.st_priority_context, bold = true))
        }
        card.addView(head)
        card.addView(labelText(tech.oneLiner, 12f, R.color.st_text))
        val paramLine = tech.params.joinToString(" · ") { "${it.first} ${it.second}" }
        card.addView(labelText(paramLine, 11f, R.color.st_text_warm))
        val reason = if (currentSkillCategory == null) {
            TechniqueMatcher.reasonFor(tech, signals)
        } else {
            "「${tech.category.label}」分类收录"
        }
        card.addView(labelText(reason, 11f, R.color.st_text_dim))
        return card
    }

    private fun showSkillDialog(tech: Technique) {
        val body = buildString {
            append("什么时候用\n").append(tech.whenTo).append("\n\n")
            append("怎么做\n")
            tech.steps.forEachIndexed { index, step ->
                append(index + 1).append(". ").append(step).append('\n')
            }
            append("\n为什么\n").append(tech.why).append("\n\n")
            append("常见错误\n")
            tech.mistakes.forEach { append("· ").append(it).append('\n') }
            append("\n拍完怎么验\n").append(tech.check)
        }
        AlertDialog.Builder(this)
            .setTitle(tech.title)
            .setMessage(body)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 把 FrameStats 和设备能力折算成纯 Kotlin 信号，知识层不碰 Android 类型。 */
    private fun buildTechSignals(): TechSignals {
        val snap = lastStats
        val hasTele = profile.lenses.size > 1
        val longExposure = profile.caps.exposureMaxNs >= 1_000_000_000L
        if (snap == null) {
            return TechSignals(
                hasTele = hasTele,
                supportsManual = profile.supportsManualPhoto,
                supportsLongExposure = longExposure,
                hasStats = false
            )
        }
        return TechSignals(
            meanLuma = snap.meanLuma,
            highlightClip = snap.highlightClip,
            shadowClip = snap.shadowClip,
            dynamicRange = snap.dynamicRange,
            saturation = snap.saturation,
            skinRatio = snap.skinRatio,
            edgeDensity = snap.edgeDensity,
            centerBias = snap.centerBias,
            colorCast = snap.colorCastIndex,
            hasTele = hasTele,
            supportsManual = profile.supportsManualPhoto,
            supportsLongExposure = longExposure,
            hasStats = true
        )
    }

    // ---------------- 照片页 ----------------

    /** 分析线程回调，做节流后切主线程渲染 */
    private fun onFrame(stats: FrameStats) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiAt < 220) return
        lastUiAt = now
        uiHandler.post { render(stats) }
    }

    private fun render(stats: FrameStats) {
        if (isFinishing) return

        lastStats = stats
        binding.histogram.setHistogram(stats.histogram)
        binding.tvLuma.text =
            "亮度 ${(stats.meanLuma * 100).roundToInt()}% · 高光截断 ${(stats.highlightClip * 100).roundToInt()}%"

        val cls = SceneClassifier.classify(stats, aiResult)
        currentScene = cls.scene

        val current = if (engine?.isManual() == true && appliedIso > 0) {
            ManualParams(appliedIso, appliedShutterNs)
        } else {
            engine?.liveParams()
        }

        val rec = ParamRecommender.recommend(
            stats = stats,
            scene = cls.scene,
            caps = caps,
            current = current,
            confidence = cls.confidence
        )

        if (locked && lockedRec == null) {
            lockedRec = rec
        }
        val display = if (locked && lockedRec != null) lockedRec!! else rec
        lastRecommendation = rec

        binding.tvScene.text =
            "${cls.scene.label} ${(cls.confidence * 100).roundToInt()}% · ${cls.source}"
        renderParams(display)
        setAdviceMain(display.tip, display.detail)

        renderComposition(stats)

        if (currentTab == TAB_VIDEO) renderVideo()

        // 功能建议变化慢，1.5 秒一次足够，否则文字一直在跳
        val now = SystemClock.elapsedRealtime()
        if (currentTab == TAB_FEATURE && now - lastFeatureAt > 1500) {
            lastFeatureAt = now
            renderFeatures()
        }

        if (currentTab == TAB_SKILL && now - lastSkillAt > 1500) {
            lastSkillAt = now
            renderSkills()
        }

        if (autoMode && !locked) {
            maybeApply(display)
        }
    }

    /**
     * 四个读数 + 变化高亮（v1.8 P1-3）。
     *
     * 字号不是平权的：快门 26sp（唯一不可逆的量 —— ISO 高了能减噪、色温错了能后期，
     * 糊了救不回来），ISO/色温 20sp，偏差 11sp。
     *
     * 变化高亮**只对 alpha 做动画**。用 ValueAnimator 每帧 setText 会持续触发
     * measure/layout，2Hz 一次的读数刷新叠加 220ms 的取景渲染，面板会开始掉帧 ——
     * 那样"高亮"反而变成了负担。
     */
    private fun renderParams(display: Recommendation) {
        val isoText = display.iso.toString()
        val shutterText = display.shutterLabel
        val kelvinText = "${display.kelvin}K"
        val evText = formatEv(display.evDelta)

        applyParam(binding.tvIso, lastIso, isoText)
        applyParam(binding.tvShutter, lastShutter, shutterText)
        applyParam(binding.tvKelvin, lastKelvin, kelvinText)
        applyParam(binding.tvEv, lastEv, evText, evColorRes(evText))

        lastIso = isoText
        lastShutter = shutterText
        lastKelvin = kelvinText
        lastEv = evText
        renderParamsSummary()
    }

    /**
     * 偏差的符号配色。
     *
     * 注意：`+` / `−` / `±` **符号本身就是非颜色信息**，颜色只是加速识别 ——
     * 色盲用户看不到色差也不会丢失语义，满足"颜色不单独承载信息"。
     */
    @ColorRes
    private fun evColorRes(evText: String): Int = when {
        evText.startsWith("+") -> R.color.st_text_warm
        evText.startsWith("−") || evText.startsWith("-") -> R.color.st_text_option
        else -> R.color.st_text_dim
    }

    /** 写一个读数：只有值真的变了才 setText + 闪一下 alpha。 */
    private fun applyParam(
        view: TextView,
        previous: String?,
        value: String,
        @ColorRes colorRes: Int? = null
    ) {
        if (view.text?.toString() == value) return
        view.text = value
        if (colorRes != null) view.setTextColor(ContextCompat.getColor(this, colorRes))
        if (previous != null && previous != value) {
            view.animate().cancel()
            view.alpha = 0.6f
            view.animate().alpha(1f).setDuration(PARAM_FLASH_MS).start()
        }
    }

    /**
     * G1：参数收起时用一行摘要证明"参数还在"（规格 §5.1 L3）。
     *
     * 四格+直方图默认收起后，如果什么都不留，用户会以为功能被删了。
     * 这行字同时是「参数 ▾」展开前的预览。
     */
    private fun renderParamsSummary() {
        val rec = lastRecommendation ?: return
        setTextOnce(
            binding.tvParamsSummary,
            "ISO ${rec.iso} · ${rec.shutterLabel} · ${rec.kelvin}K · EV ${formatEv(rec.evDelta)}"
        )
    }

    // ---------------- 合并后的建议区（v1.8 P1-1） ----------------

    /** 主句/副句来自参数建议，两处都可能先到，所以拆成两个 setter + 一个 refresh。 */
    private fun setAdviceMain(main: String, detail: String) {
        if (main == adviceMain && detail == adviceDetail) return
        adviceMain = main
        adviceDetail = detail
        refreshAdvice()
    }

    /** 机位信息由姿态回调按 400ms 节流写入，最终并到副句末尾。 */
    private fun setAdviceFraming(framing: String) {
        if (framing == adviceFraming) return
        adviceFraming = framing
        refreshAdvice()
    }

    /**
     * 把「主句 + 副句」拼成一个 TextView。
     *
     * 为什么用 Spannable 而不是两个 View：少一层布局、少一次 measure，
     * 也避免两行字行距对不齐（两个 TextView 各自带 margin，很难调准）。
     * 主句 13sp 粗体，副句按 [ADVICE_SUB_SCALE] 缩到约 11sp 并转灰。
     */
    private fun refreshAdvice() {
        val sub = listOf(adviceDetail, adviceFraming)
            .filter { it.isNotBlank() }
            .joinToString("　")
        val text = if (sub.isEmpty()) adviceMain else adviceMain + "\n" + sub
        val span = SpannableString(text)
        span.setSpan(StyleSpan(Typeface.BOLD), 0, adviceMain.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (sub.isNotEmpty()) {
            val start = adviceMain.length + 1
            span.setSpan(
                RelativeSizeSpan(ADVICE_SUB_SCALE),
                start,
                text.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.st_text_dim)),
                start,
                text.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvAdvice.text = span
    }

    /**
     * 构图诊断的刷新频率比测光低一档：构图靠「人移动到位置」改变，
     * 更新太快反而让人抓不住该往哪边挪。
     */
    private fun renderComposition(stats: FrameStats) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastComposeAt < 400) return
        lastComposeAt = now

        val comp = CompositionAnalyzer.analyze(stats)
        lastComp = comp
        binding.guideOverlay.submit(comp)
        binding.tvScore.text = "构图 ${comp.score}"
        binding.tvCompose.text = comp.tips.joinToString("；")
        renderScoreDetail(stats, comp)
        // 构图本来就按 400ms 节流，这里强制刷新一次，让机位建议紧跟构图结果
        updateFramingLine(force = true)
        // 水平参考线的"端平了没"必须跟判定用**同一个 roll**（规格 §3.2）：
        // 两处读不同的源，就会出现"线绿了但环没绿"。NaN 表示传感器这次没给数，
        // overlay 会据此把线退回白色 —— 不这么做，线会带着上一次的读数一直绿着。
        binding.guideOverlay.submitRoll(levelRoll ?: Float.NaN)
        // G1：任务状态机的唯一判定入口，直接搭这条 400ms 节拍（规格 §3.5）
        tickMission(stats, comp)
    }

    // ---------------- 四维参考分（v1.5） ----------------

    /**
     * 四维参考分。
     *
     * 措辞是「参考分」不是「评分」：这个数是从画面统计反推的，不是相机的测光结论。
     * 未测的维度显示「未测」而不是 0 —— 0 会被读成「拍得很差」，而事实是「没测到」。
     * 同理，只要有一项未测，总分就不给数字（分母不完整，给了也是错的）。
     */
    private fun renderScoreDetail(stats: FrameStats, comp: CompositionHint) {
        val result = ScoreBoard.score(
            stats = stats,
            hint = comp,
            roll = levelRoll,
            scene = currentScene,
            mirrored = false
        )
        // G1：留一份给任务卡（实时参考分）与成绩卡（+N）复用，不要再各算一遍
        lastScoreResult = result
        binding.tvScoreDetail.text = formatScore(result)
    }

    private fun formatScore(result: ScoreResult): String {
        val allMeasured = result.dimensions.all { it.measured }
        val head = if (allMeasured) {
            "参考分 ${result.total} · ${result.grade}"
        } else {
            "参考分 -- · 有一项未测"
        }
        val dims = result.dimensions.joinToString("  ") { d ->
            if (d.measured) "${d.label} ${d.score}" else "${d.label} 未测"
        }
        return "$head\n$dims"
    }

    // ---------------- 教练提示槽（v1.5） ----------------

    /** 交互：点击=下一条，长按=这次会话别再提。 */
    private fun setupCoachSlot() {
        binding.llCoachSlot.setOnClickListener { onCoachNext() }
        binding.llCoachSlot.setOnLongClickListener {
            onCoachBlock()
            true
        }
    }

    /**
     * 三档提示密度的选择器（放在功能页顶部）。
     *
     * 为什么必须有这个入口：R10 兜底轮播是 GUIDE 档，STANDARD 下会被整条过滤，
     * 而在 v1.5.0 之前全项目只有 `onCreate` 一处 `setDensity` 调用 ——
     * 「用户可以自己调到 CHATTER」在 UI 上根本做不到（QA P1-2）。
     *
     * 读档走 `SharedPreferences`：密度是用户设置，跨进程重启必须保留。
     */
    private fun setupDensitySelector() {
        val prefs = uiPrefs
        val density = parseDensity(prefs.getString(KEY_PROMPT_DENSITY, null))
        // 先落状态再勾选，避免勾选触发的回调把同一件事做两遍
        promptScheduler.setDensity(density)
        binding.rgPromptDensity.check(radioIdOf(density))
        binding.tvDensityHint.text = densityHint(density)

        binding.rgPromptDensity.setOnCheckedChangeListener { _, checkedId ->
            val picked = densityOf(checkedId)
            if (picked == null) return@setOnCheckedChangeListener
            promptScheduler.setDensity(picked)
            prefs.edit().putString(KEY_PROMPT_DENSITY, picked.name).apply()
            binding.tvDensityHint.text = densityHint(picked)
            // 改档位可能把当前这张卡判成不合规（`setDensity` 会把 current 置空），
            // 立刻 tick 一次让状态机重算并刷新槽位 —— 不要等 500ms 的下一个节拍，
            // 否则用户会觉得「点了没反应」（QA P2-4）。
            renderCoachSlot(promptScheduler.tick(buildPromptSignals(), SystemClock.elapsedRealtime()))
        }
    }

    /** 存进去的字符串可能来自旧版本或被手改过，解析不出来就退回默认档，不崩。 */
    private fun parseDensity(raw: String?): PromptDensity {
        if (raw == null) return PromptDensity.STANDARD
        return runCatching { PromptDensity.valueOf(raw) }.getOrDefault(PromptDensity.STANDARD)
    }

    private fun radioIdOf(density: PromptDensity): Int = when (density) {
        PromptDensity.QUIET -> R.id.rbDensityQuiet
        PromptDensity.STANDARD -> R.id.rbDensityStandard
        PromptDensity.CHATTER -> R.id.rbDensityChatter
    }

    /** 未知 checkedId（比如 `clearCheck`）返回 null，调用方直接忽略。 */
    private fun densityOf(checkedId: Int): PromptDensity? = when (checkedId) {
        R.id.rbDensityQuiet -> PromptDensity.QUIET
        R.id.rbDensityStandard -> PromptDensity.STANDARD
        R.id.rbDensityChatter -> PromptDensity.CHATTER
        else -> null
    }

    private fun densityHint(density: PromptDensity): String = when (density) {
        PromptDensity.QUIET -> "只提醒画面硬伤，取景时最安静"
        PromptDensity.STANDARD -> "硬伤 + 当前场景建议（含运镜与网格）"
        PromptDensity.CHATTER -> "再加上通用教学轮播，新手期更有用"
    }

    /**
     * 500ms 独立循环。
     *
     * **刻意不挂在 `render()` 的 220ms 节流里**：提示状态机自己按"秒"记账
     * （8s 冷却、3s 驻留、holdMs），如果 tick 频率跟着帧率或 UI 节流走，
     * 冷却会漂移、卡片会闪。这里用 `elapsedRealtime()` 而不是 `currentTimeMillis()`：
     * 后者会被系统校时和用户改表拨动，一旦往回跳，冷却就会变成永不过期。
     */
    private fun startCoachLoop() {
        if (coachLoopRunning) return
        coachLoopRunning = true
        promptScheduler.pause(false)
        coachHandler.postDelayed(coachRunnable, COACH_TICK_MS)
    }

    private fun stopCoachLoop() {
        coachLoopRunning = false
        coachHandler.removeCallbacks(coachRunnable)
        promptScheduler.pause(true)
    }

    private val coachRunnable = object : Runnable {
        override fun run() {
            if (!coachLoopRunning) return
            tickCoach()
            coachHandler.postDelayed(this, COACH_TICK_MS)
        }
    }

    private fun tickCoach() {
        if (isFinishing || isDestroyed) return
        coachTicks++
        // G1 互斥（规格 §6.3(2)）：任务占用表面期间**不推进 scheduler**。
        // 否则它的 8s 冷却 / 3s 驻留会在任务期间偷偷跑完，任务一结束立刻弹两条
        //（一条教练槽 + 一条新任务），正是这条 bug 要防的东西。
        if (missionEngine.occupiesSurface()) {
            binding.llCoachSlot.visibility = LinearLayout.GONE
            return
        }
        val decision = promptScheduler.tick(buildPromptSignals(), SystemClock.elapsedRealtime())
        if (decision.changed) renderCoachSlot(decision)
    }

    /**
     * 把现有信号折算成提示引擎能吃的纯 Kotlin 结构。
     *
     * 降级口径（拿不到就传 null / false，绝不编造）：
     * - 重力传感器没有 → `roll`/`pitch` 传 null，水平度记满分但标未测；
     * - 没有前置通路 → `mirrored` 恒 false（`CameraEngine` 只开 `DEFAULT_BACK_CAMERA`）；
     * - `coveredTopics` 本版恒空：还没有"用户已学过"的持久化，R08 因此永远有资格出牌。
     */
    private fun buildPromptSignals(): PromptSignals = PromptSignals(
        tech = buildTechSignals(),
        roll = levelRoll,
        pitch = levelPitch,
        tab = if (currentTab == TAB_VIDEO) PromptEngine.TAB_VIDEO else PromptEngine.TAB_PHOTO,
        videoIntentOrdinal = videoIntent.ordinal,
        scene = currentScene,
        hasTele = profile.lenses.size > 1,
        equivFocalMm = profile.equivFocalMm,
        gridOn = guidesOn,
        gridOffAgoMs = if (guidesOn || gridOffAt < 0L) {
            Long.MAX_VALUE
        } else {
            (SystemClock.elapsedRealtime() - gridOffAt).coerceAtLeast(0L)
        },
        coveredTopics = emptySet(),
        sessionCount = coachTicks,
        mirrored = false,
        stability = (motionEma / MOTION_SCALE_DEG).coerceIn(0f, 1f)
    )

    /**
     * 只在 `decision.changed == true` 时才碰控件。
     *
     * 500ms 一次的 tick 如果每次都 setText，会持续触发 measure/layout，
     * 叠加 220ms 的画面渲染，整块面板会开始掉帧。
     */
    private fun renderCoachSlot(decision: PromptDecision) {
        val prompt = decision.prompt
        if (prompt == null) {
            binding.llCoachSlot.visibility = LinearLayout.GONE
            // autoResolve 命中：问题自己解决了，给一句正向反馈再收卡
            val feedback = decision.feedback
            if (feedback != null) {
                val text = when (feedback) {
                    PromptFeedback.LEVEL_OK -> "端平了，保持住"
                    PromptFeedback.EXPOSURE_OK -> "曝光回来了"
                }
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val candidate = prompt.candidate
        binding.llCoachSlot.visibility = LinearLayout.VISIBLE
        // 8dp 色点：优先级只用一个颜色表达（URGENT 橙 / CONTEXT 主色 / GUIDE 冷蓝）。
        // bg_dot 是纯白 oval，这里靠 backgroundTint 上色，不用换 drawable。
        binding.tvCoachTag.backgroundTintList =
            ColorStateList.valueOf(priorityColor(candidate.priority))
        binding.tvCoachAction.text = candidate.action
        binding.tvCoachReason.text = candidate.reason
    }

    /**
     * 提示优先级 → 颜色。
     *
     * 迁移到 `R.color.st_priority_*` 时**必须逐条核对**：
     * URGENT 橙 / CONTEXT 主色 / GUIDE 冷蓝。这三个值承载"硬伤用橙色"的语义，
     * 一旦错位，8 个 JUnit 一个都不会报（它们全是纯逻辑，不碰 UI）。
     */
    private fun priorityColor(priority: PromptPriority): Int = when (priority) {
        PromptPriority.URGENT -> ContextCompat.getColor(this, R.color.st_priority_urgent)
        PromptPriority.CONTEXT -> ContextCompat.getColor(this, R.color.st_priority_context)
        PromptPriority.GUIDE -> ContextCompat.getColor(this, R.color.st_priority_guide)
    }

    /** 点击：当前条进 8s 冷却，换下一条（不拉黑）。 */
    private fun onCoachNext() {
        renderCoachSlot(promptScheduler.onNext(SystemClock.elapsedRealtime()))
    }

    /** 长按：本次会话不再提这条。 */
    private fun onCoachBlock() {
        renderCoachSlot(promptScheduler.onBlock(SystemClock.elapsedRealtime()))
        Toast.makeText(this, "这次会话不再提这条", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 机位提示（P0-6） ----------------

    /**
     * 机位提示原来由 `installFramingLine()` 动态插一行 TextView：
     * 取 `binding.tvCompose.parent as? LinearLayout`、再取它的 `.parent`，插到兄弟位置。
     * 这种"靠父容器类型猜位置"的写法有一个致命性质：**布局一改就静默失效** ——
     * `?: return` 吞掉一切，功能消失但编译、测试、日志全都正常（架构 S3）。
     *
     * v1.7 直接删掉它；v1.8 起机位信息并入 tvAdvice 的副句（[setAdviceFraming]），
     * 照片页不再有独立的"机位"行。少一层耦合，也少一类查不出来的 bug。
     */

    /** 10Hz 姿态回调：刻度全速跟手，「机位」文字与构图一起按 400ms 节流 */
    private fun onPitchRoll(pitch: Float, roll: Float) {
        if (isFinishing || isDestroyed) return
        lastPitch = pitch
        hasPitch = true
        updateMotion(pitch, roll)
        binding.guideOverlay.submitPitch(pitch)
        updateFramingLine()
    }

    /**
     * 手持稳定度代理。
     *
     * 欧拉角的相邻样本差可以直接当「晃不晃」用：静止时每 100ms 差不到 0.1°，
     * 边走边拍会到 1–3°。乘 2 归一化到 0..1，R10 兜底要求的阈值是 0.35
     * （约 0.7°/样本），实测下来站着不动能过、走路过不了，正好。
     *
     * 已知局限：这是加速度计融合出来的角度差，不是角速度积分，
     * 匀速平移（不转不晃）会被误判为稳定。等有陀螺仪通路再换真值。
     */
    private fun updateMotion(pitch: Float, roll: Float) {
        val basePitch = motionPitchBase
        val baseRoll = motionRollBase
        if (basePitch != null && baseRoll != null) {
            val delta = abs(pitch - basePitch) + abs(roll - baseRoll)
            motionEma += MOTION_ALPHA * (delta - motionEma)
        }
        motionPitchBase = pitch
        motionRollBase = roll
    }

    private fun updateFramingLine(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFramingAt < 400) return
        lastFramingAt = now

        val ai = aiResult
        val comp = lastComp
        val pitch = lastPitch

        val advice = if (ai != null && ai.ok && ai.faceCount > 0) {
            val face = FaceInfo(
                count = ai.faceCount,
                centerX = ai.faceCenterX,
                centerY = ai.faceCenterY,
                area = ai.faceArea,
                boxBottom = ai.faceBox.bottom,
                eulerYDeg = ai.headEulerY
            )
            // 主体置信度太低时宁可不给几何建议，避免把噪声当成人的脚/关节
            val subject = comp
                ?.takeIf { it.subjectConfidence > 0.30f }
                ?.let {
                    SubjectBox(
                        left = it.subjectLeft,
                        top = it.subjectTop,
                        right = it.subjectRight,
                        bottom = it.subjectBottom
                    )
                }
            FramingCoach.advise(
                face = face,
                subject = subject,
                comp = comp ?: CompositionHint.EMPTY,
                equivFocalMm = profile.equivFocalMm
            )
        } else {
            emptyList()
        }

        val text = when {
            advice.isNotEmpty() -> "机位：${advice.first().title}"
            hasPitch && pitch != null -> "机位：当前俯仰 ${pitch.roundToInt()}°"
            else -> "机位：等画面再判断"
        }
        setAdviceFraming(text)
    }

    private fun toggleGuide() {
        guidesOn = !guidesOn
        // 记下「刚被关掉」的时刻，R11 要靠它算静默期：用户主动关网格是为了看清画面，
        // 500ms 后就弹「把网格打开」等于跟他的选择对着干（QA P1-3）。
        gridOffAt = if (guidesOn) -1L else SystemClock.elapsedRealtime()
        binding.guideOverlay.showGrid = guidesOn
        binding.guideOverlay.showGuides = guidesOn
        binding.btnGuide.text = if (guidesOn) "网格" else "已关"
        binding.btnGuide.setTextColor(
            ContextCompat.getColor(
                this,
                if (guidesOn) android.R.color.holo_green_light else android.R.color.darker_gray
            )
        )
        if (!guidesOn) {
            binding.tvScore.text = "构图 --"
            binding.tvCompose.text = "构图引导已关闭，点右侧「已关」重新打开"
        }

        // G1：任务环与三分交点都画在 guideOverlay 上，网格关了就没有载体；
        // 而且用户关网格是为了看清画面，这时候弹任务卡是跟他的选择对着干。
        if (guidesOn) {
            missionEngine.resume()
        } else {
            abortMission(AbortReason.GUIDE_OFF)
        }
        renderTaskSurface()
    }

    /** 复制成一行纯文本，方便切到系统相机时心里有底 */
    private fun copyParams() {
        val rec = lastRecommendation ?: run {
            Toast.makeText(this, "还没测到画面，等一秒再试", Toast.LENGTH_SHORT).show()
            return
        }
        val text = "ISO ${rec.iso} · ${rec.shutterLabel} · WB ${rec.kelvin}K（${rec.scene.label}）"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("光影调参", text))
        Toast.makeText(this, "已复制：$text", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 内建拍照（v1.6） ----------------

    /** 点快门：旧系统先补存储权限，其他情况直接拍。权限被拒也继续，只是改存缓存。 */
    private fun onShutterClick() {
        if (captureInProgress) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        capturePhoto()
    }

    private fun capturePhoto() {
        if (captureInProgress) return

        // G1（规格 §6.3(3)）：一按快门就撤掉任务卡/成绩卡，不让它挡着白闪与缩略图飞入。
        // abort 幂等，与 showReviewCard() 里的那次是双保险 —— 覆盖"ACHIEVED 之后
        // 1.2s 内用户就按了快门"这条路径（那时成绩卡可能刚显示）。
        abortMission(AbortReason.CAPTURE)

        val eng = engine ?: run {
            Toast.makeText(this, "相机还没就绪，稍等一秒", Toast.LENGTH_SHORT).show()
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(cacheDir, "shot_$stamp.jpg")

        captureInProgress = true
        binding.btnShutter.isEnabled = false
        lastShotFile = file
        lastShotUri = null

        // 按下瞬间的反馈：白闪 90ms + 快门缩放 120ms。
        // 这两下是"我收到了"的唯一即时确认，比任何文字都快。
        playShutterFeedback()

        // 水印数据要在按下快门这一刻就取：写相册是后台线程，等那时 `lastStats`
        // 已经被下一帧覆盖了，烧上去的分数就不是这一张的。
        val shotMark = if (watermarkEnabled()) buildShotMark() else null

        // 成长历史（G4）也必须在按下这一刻取：写相册在后台线程跑，等到那时 `lastStats`
        // 已经被下一帧覆盖了，落盘的就不是这一张的分。取不到（还没测到画面）就不记，
        // 而不是记一条全是 null 的记录 —— 那会污染「第 1 张」这个起点。
        val shotRecord = buildShotRecord(System.currentTimeMillis())

        // 自动曝光下没有「正在写入」的手动参数，把当前建议复制到剪贴板，
        // 用户之后去系统相机仍能照着拨，且全程不阻塞快门。
        // v1.7：不再弹 toast（连拍时会连续弹、遮挡取景），改成复盘卡里的一行灰字。
        val autoExposureNote = if (eng.isManual()) {
            null
        } else {
            copyCurrentAdviceQuietly()
            if (lastRecommendation != null) {
                "自动曝光 · 参数建议已复制到剪贴板"
            } else {
                "自动曝光 · 参数建议等测光完成后再复制"
            }
        }

        eng.takePhoto(
            outputFile = file,
            onSuccess = { saved ->
                // 相册写入是磁盘 IO，放到后台线程，避免卡住取景画面
                Thread {
                    val uri = saveShotToGallery(saved, shotMark)
                    // 只有真的进了相册才记一笔：缓存里那张用户随时可能清掉，
                    // 记进去会让「拍了多少张」涨得比相册里的照片快，成长叙事就假了。
                    // 这里是磁盘 IO，本来就在后台线程。
                    val unlockedDexId = if (uri != null && shotRecord != null) {
                        runCatching { ScoreHistoryStore.append(this@MainActivity, shotRecord) }
                            .onFailure { t -> Diag.w(TAG, "写入分数历史失败（不影响照片）", t) }
                        // 图鉴维度通道解锁（G3-B）：四维里哪个达标就从哪个维度解锁一条。
                        // 「每天最多一条」由 DexStore 内部管，多维度达标也只解锁第一个命中。
                        runCatching { unlockDexByDimension(shotRecord) }
                            .onFailure { t -> Diag.w(TAG, "图鉴解锁失败（不影响照片）", t) }
                            .getOrNull()
                    } else {
                        null
                    }
                    // 缩略图必须在删缓存文件之前解出来
                    val thumb = loadThumbBitmap(uri ?: Uri.fromFile(saved))
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        captureInProgress = false
                        // 不能写死 isEnabled = true：拍照途中用户可能切走页签
                        applyShutterAvailability()
                        if (uri != null) {
                            lastShotUri = uri
                            lastShotFile = null
                            // 相册已经有一份，缓存的临时文件可以删掉
                            try {
                                saved.delete()
                            } catch (_: Throwable) {
                            }
                            flyThumbTo(thumb)
                            showReviewCard(
                                listOfNotNull("已存到相册 Pictures/ShotTuner", autoExposureNote)
                                    .joinToString(" · "),
                                unlockedDexId
                            )
                        } else {
                            // 保存失败才 toast —— 成功路径一律不弹
                            Toast.makeText(
                                this,
                                "没能写入相册，已存到缓存，长按快门可分享",
                                Toast.LENGTH_SHORT
                            ).show()
                            flyThumbTo(thumb)
                            showReviewCard(
                                listOfNotNull("仅存到缓存（相册写入失败），长按快门可分享", autoExposureNote)
                                    .joinToString(" · "),
                                unlockedDexId
                            )
                        }
                    }
                }.start()
            },
            onError = { e ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    captureInProgress = false
                    applyShutterAvailability()
                    try {
                        file.delete()
                    } catch (_: Throwable) {
                    }
                    if (lastShotFile == file) lastShotFile = null
                    // U4：给用户看人话，技术细节进诊断日志（用户读不懂 CameraX 的异常名）
                    Diag.e(TAG, "拍照失败", e)
                    Toast.makeText(this, "拍照失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** 复制建议但不弹「已复制」提示，避免和自动曝光的说明 toast 打架 */
    private fun copyCurrentAdviceQuietly() {
        val rec = lastRecommendation ?: return
        val text = "ISO ${rec.iso} · ${rec.shutterLabel} · WB ${rec.kelvin}K（${rec.scene.label}）"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("光影调参", text))
    }

    /**
     * 写系统相册。
     *
     * Android 10+ 走 MediaStore 的 RELATIVE_PATH，不需要存储权限；
     * 9 及以下必须先有 WRITE_EXTERNAL_STORAGE，否则返回 null 由调用方退化为缓存。
     */
    /**
     * 写系统相册。
     *
     * Android 10+ 走 MediaStore 的 RELATIVE_PATH，不需要存储权限；
     * 9 及以下必须先有 WRITE_EXTERNAL_STORAGE，否则返回 null 由调用方退化为缓存。
     *
     * [mark] 非 null 时先烧成绩水印：解码原图 → Canvas 画水印 → 压缩到一个临时文件，
     * 再把**临时文件**的字节写进相册。
     *
     * 为什么必须先落临时文件再拷：`contentResolver.openOutputStream(uri)` 只能写一次，
     * 而水印需要"先解码整张图"才知道画在哪；直接在流上画做不到。
     * 为什么不顺手改 `source`：`source` 是 CameraX 的临时产物，成功路径上本来就要
     * `delete()`，语义上不该被写成"带水印的原图"。
     * 水印失败（OOM / 解码失败）一律降级为**原图直存** —— 少一层水印可以接受，丢照片不行。
     */
    private fun saveShotToGallery(source: File, mark: Watermark.Mark?): Uri? {
        var stamped: File? = null
        val payload = try {
            if (mark == null) {
                source
            } else {
                val tmp = File(cacheDir, "${source.nameWithoutExtension}_wm.jpg")
                if (Watermark.stamp(source, tmp, mark)) tmp.also { stamped = it } else source
            }
        } catch (t: Throwable) {
            // 水印是锦上添花，任何一个异常都不能变成"照片没存上"
            Diag.w(TAG, "水印烧制失败，按原图保存", t)
            source
        }
        return try {
            val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            if (!isQ &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (isQ) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/ShotTuner"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (isQ) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = contentResolver.insert(collection, values) ?: return null
            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法打开相册输出流")
                output.use { out ->
                    payload.inputStream().use { input -> input.copyTo(out) }
                }
                if (isQ) {
                    val done = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, done, null, null)
                }
                uri
            } catch (e: Throwable) {
                // 半截文件不能留在相册里
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Throwable) {
                }
                throw e
            }
        } catch (e: Throwable) {
            // B 类：写相册失败会让用户以为照片丢了（其实在缓存里）。必须留证据。
            Diag.w(TAG, "写入系统相册失败", e)
            null
        } finally {
            // 带水印的临时文件已经拷进相册，删掉；失败路径下也别留在缓存里
            stamped?.let { tmp ->
                try {
                    tmp.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * 水印用的结构化复盘数据（G2-A）。
     *
     * 放在 [buildShotReview] 旁边而不去解析它的返回值：那个函数拼的是三行给人读的字符串，
     * 解析字符串等于把两份格式悄悄耦在一起，改文案就会静默写错水印。
     *
     * `total` 为 null = 有维度未测，水印落「参考分 --」—— 不烧假数字。
     */
    private fun buildShotMark(): Watermark.Mark {
        val snap = lastStats
        val hint = lastComp
        if (snap == null || hint == null) {
            return Watermark.Mark(total = null, grade = null, technique = resolveTechniqueName(null))
        }
        val result = ScoreBoard.score(
            stats = snap,
            hint = hint,
            roll = levelRoll,
            scene = currentScene,
            mirrored = false
        )
        val allMeasured = result.dimensions.all { it.measured }
        return Watermark.Mark(
            total = if (allMeasured) result.total else null,
            grade = if (allMeasured) result.grade else null,
            technique = resolveTechniqueName(result.recommendations.firstOrNull()?.tipId)
        )
    }

    /**
     * 这条建议关联的**技法简称**（如「三分法」），取不到返回 null（水印就只烧两行）。
     *
     * 两级取法：
     * 1. 建议自带的 `tipId` 直接查技法表 —— 这是真正的"这条建议关联的技法"；
     * 2. 取不到（例如全满分那句 `tipId` 是空串）就退回「此刻最该注意的第一条」。
     *
     * 技法表里 `title` 是整句（「眼睛放上三分线，别放正中」），
     * 由 [Watermark.shortenTechnique] 砍到逗号并限长。
     */
    private fun resolveTechniqueName(tipId: String?): String? {
        val byAdvice = tipId?.takeIf { it.isNotBlank() }
            ?.let { TechniqueBookV15.byId[it] }
            ?.title
        if (byAdvice != null) return Watermark.shortenTechnique(byAdvice)
        val fallback = TechniqueMatcher.suggest(currentScene, buildTechSignals(), limit = 1)
            .firstOrNull()?.title
        return Watermark.shortenTechnique(fallback)
    }

    /** 拍完立刻复盘：用拍摄这一帧的四维参考分，不另起一套评分 */
    private fun buildShotReview(): String {
        val snap = lastStats ?: return "还没测到画面，这一张已保存。"
        val hint = lastComp ?: return "还没测到构图，这一张已保存。"
        val result = ScoreBoard.score(
            stats = snap,
            hint = hint,
            roll = levelRoll,
            scene = currentScene,
            mirrored = false
        )
        val allMeasured = result.dimensions.all { it.measured }
        val head = if (allMeasured) {
            "这张参考分 ${result.total} · ${result.grade}"
        } else {
            "这张参考分 -- · 有一项未测"
        }
        val dims = result.dimensions.joinToString(" · ") { d ->
            if (d.measured) "${d.label} ${d.score}" else "${d.label} 未测"
        }
        val advice = lastRecommendation?.tip?.takeIf { it.isNotBlank() }
            ?: hint.tips.firstOrNull().orEmpty()
        return buildString {
            append(head)
            append('\n')
            append(dims)
            if (advice.isNotBlank()) {
                append("\n建议：")
                append(advice)
            }
        }
    }

    /**
     * 落盘用的结构化复盘数据（G4「进步可见」）。
     *
     * 为什么另起一个函数而不是解析 [buildShotReview] 的返回值：那个函数拼的是三行
     * 给人读的字符串（还带「参考分 --」「未测」这类文案），反解等于把存储格式和
     * UI 文案悄悄绑死 —— 改一句文案，历史数据就静默错位。
     *
     * 评分口径与 [buildShotMark] / [buildShotReview] 完全一致：同一帧、同一套
     * [ScoreBoard.score] 参数，未测维度记 null（不记 0），任一维未测则总分为 null。
     *
     * @return null = 这一帧没有任何可记的数据（相机刚起来还没测到画面），调用方跳过落盘
     */
    private fun buildShotRecord(timestamp: Long): ShotRecord? {
        val snap = lastStats ?: return null
        val hint = lastComp ?: return null
        val result = ScoreBoard.score(
            stats = snap,
            hint = hint,
            roll = levelRoll,
            scene = currentScene,
            mirrored = false
        )
        return ShotRecord.of(
            timestamp = timestamp,
            level = measuredScoreOf(result, ScoreDimension.LEVEL),
            exposure = measuredScoreOf(result, ScoreDimension.EXPOSURE),
            composition = measuredScoreOf(result, ScoreDimension.COMPOSITION),
            clarity = measuredScoreOf(result, ScoreDimension.CLARITY)
        )
    }

    /**
     * 取某一维的分值；未测（`measured == false`）返回 null。
     *
     * 这里必须返回 null 而不是 0：`ScoreBoard` 对未测维度填的是「满分」或「0」
     * 两种兜底值，落盘时照抄会把「没测到」写成「这项 0 分」，用户会当成自己拍砸了。
     */
    private fun measuredScoreOf(result: ScoreResult, dimension: ScoreDimension): Int? {
        val scored = result.dimensions.firstOrNull { it.dimension == dimension } ?: return null
        return if (scored.measured) scored.score else null
    }

    /**
     * 图鉴维度通道解锁（G3-B「扩解锁入口」）。
     *
     * 按 LEVEL → EXPOSURE → COMPOSITION → CLARITY 顺序尝试，第一个「该维度有分且达标」
     * 的维度解锁一条。顺序只保证同一天多维度达标时先解锁哪条可预测，没有优先级含义；
     * 「每天最多一条」由 [DexStore.tryUnlockByDimension] 内部的门把关，未测维度（null）
     * 直接跳过。
     *
     * @return 解锁成功的技法 id；本次没有解锁（未达标 / 已解锁 / 当天已解锁过）返回 null
     */
    private fun unlockDexByDimension(record: ShotRecord): String? {
        record.level?.let {
            DexStore.tryUnlockByDimension(this, ScoreDimension.LEVEL, it, ScoreBoard.MAX_LEVEL)?.let { id -> return id }
        }
        record.exposure?.let {
            DexStore.tryUnlockByDimension(this, ScoreDimension.EXPOSURE, it, ScoreBoard.MAX_EXPOSURE)?.let { id -> return id }
        }
        record.composition?.let {
            DexStore.tryUnlockByDimension(this, ScoreDimension.COMPOSITION, it, ScoreBoard.MAX_COMPOSITION)?.let { id -> return id }
        }
        record.clarity?.let {
            DexStore.tryUnlockByDimension(this, ScoreDimension.CLARITY, it, ScoreBoard.MAX_CLARITY)?.let { id -> return id }
        }
        return null
    }

    /**
     * 拍照复盘卡（v1.7 P0-5，替代原来的 AlertDialog）。
     *
     * 为什么换成卡而不是对话框：AlertDialog 是模态阻断，用户拍完要按「好」才能继续取景，
     * 而在取景场景里"打断"的代价是错过画面。更糟的是对话框弹出期间 `onFrame` 还在跑、
     * `render()` 还在 setText —— 用户对着一个"活着的"背景读分数。
     *
     * 现在：写进面板顶部的卡，5 秒后自动收起（点一下保持常驻）。
     * 文案沿用 [buildShotReview] 的三行，一行不多。
     */
    private fun showReviewCard(statusLine: String, unlockedDexId: String? = null) {
        // G1：复盘卡与成绩卡不能同屏 —— 两者都在讲"刚才那张怎么样"，叠在一起是噪声。
        abortMission(AbortReason.CAPTURE)

        val lines = buildShotReview().split('\n')
        binding.tvReviewHead.text = lines.getOrElse(0) { "这一张已保存" }
        binding.tvReviewDims.text = lines.getOrElse(1) { "" }
        val advice = lines.drop(2).joinToString(" ").removePrefix("建议：").trim()
        binding.tvReviewAdvice.text = advice
        binding.tvReviewAdvice.visibility = if (advice.isEmpty()) View.GONE else View.VISIBLE
        // 图鉴解锁反馈：这次解锁了某条技法，就在状态行补一句「图鉴 +1：xxx」。
        // 取不到标题就退回只显示原状态行，绝不为了一句反馈崩掉复盘卡。
        val dexNote = unlockedDexId?.let { id -> TechniqueDex.byId(id)?.let { "图鉴 +1：${it.title}" } }
        binding.tvReviewStatus.text = listOfNotNull(statusLine, dexNote).joinToString(" · ")
        binding.llReviewCard.visibility = LinearLayout.VISIBLE
        // 分享的一定是「已经写进相册的那张带水印的图」；相册写入失败时
        // ShareKit 会退回 FileProvider 的 cache-path。两者都走 content://，
        // 没有 file:// —— Android 7+ 上接收方会直接拒收 file://。
        binding.btnReviewShare.setOnClickListener {
            // 用户在读这张卡，别收
            uiHandler.removeCallbacks(hideReviewRunnable)
            shareLastShot()
        }
        // 卡在 ScrollView 里（页签/教练槽之后）：用户若正把面板滚在下面，卡片会看不见。
        // 直接回到顶部 —— 复盘是刚拍完那一刻最该看的东西（v1.7.1 P2-c）。
        binding.panel.scrollTo(0, 0)
        uiHandler.removeCallbacks(hideReviewRunnable)
        uiHandler.postDelayed(hideReviewRunnable, REVIEW_CARD_MS)
    }

    /**
     * 按下瞬间的反馈：白闪 + 快门缩放。
     *
     * 白闪 total 90ms（alpha 0 → 0.55 → 0），快门 120ms（1.0 → 0.92 → 1.0），
     * 都远低于 A4 要求的 400ms 上限 —— 转场是为了"确认按到了"，不是为了好看。
     */
    private fun playShutterFeedback() {
        val flash = binding.tvShutterFlash
        flash.animate().cancel()
        flash.alpha = 0f
        flash.animate()
            .alpha(0.55f)
            .setDuration(FLASH_IN_MS)
            .withEndAction {
                flash.animate().alpha(0f).setDuration(FLASH_OUT_MS).start()
            }
            .start()

        val shutter = binding.btnShutter
        shutter.animate().cancel()
        shutter.scaleX = 1f
        shutter.scaleY = 1f
        shutter.animate()
            .scaleX(0.92f).scaleY(0.92f)
            .setDuration(SHUTTER_PULSE_MS / 2)
            .withEndAction {
                shutter.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(SHUTTER_PULSE_MS / 2)
                    .start()
            }
            .start()
    }

    /**
     * 缩略图"飞入"左下角 —— 这就是「已保存」的唯一确认（A5）。
     *
     * 实现取舍：**不新建临时 View**，直接让 `ivThumb` 从快门坐标起跑。
     * 两者同属 `llControlBar`，坐标系一致，所以偏移量可以直接从各自的 `x/y/width/height`
     * 算出来，不需要 `getLocationOnScreen` 或全局布局监听。
     * 代价：飞入的首帧会让缩略图短暂出现在快门位置附近（48dp 大小、35% 缩放），
     * 视觉上正好就是"从快门飞出来"，比叠一层临时 View 更省也更稳。
     */
    private fun flyThumbTo(bitmap: Bitmap?) {
        if (bitmap == null) return
        val thumb = binding.ivThumb
        val shutter = binding.btnShutter
        thumb.setImageBitmap(bitmap)
        val dx = (shutter.x + shutter.width / 2f) - (thumb.x + thumb.width / 2f)
        val dy = (shutter.y + shutter.height / 2f) - (thumb.y + thumb.height / 2f)
        thumb.animate().cancel()
        thumb.visibility = View.VISIBLE
        thumb.translationX = dx
        thumb.translationY = dy
        thumb.scaleX = 0.35f
        thumb.scaleY = 0.35f
        thumb.alpha = 0.25f
        thumb.animate()
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(THUMB_FLY_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * 解一张 48dp 缩略图。
     *
     * 两段式解码：先只读尺寸算 `inSampleSize`，再按采样率真解 ——
     * 一亿像素的传感器直接 `decodeStream` 会在低端机上 OOM，而这里只需要 48dp。
     * 任何异常都返回 null（缩略图是锦上添花，不能因为它让拍照失败）。
     */
    private fun loadThumbBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val target = THUMB_TARGET_PX
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 分享最近一张：优先相册 Uri（带水印的成片），回退到缓存文件（相册写入失败的降级路径）。
     *
     * 长按快门和复盘卡上的「分享」共用这一条路，保证两处分享出去的是同一张图。
     */
    private fun shareLastShot() {
        val uri = lastShotUri ?: ShareKit.uriForCacheFile(this, lastShotFile)
        if (uri == null) {
            Toast.makeText(this, "还没有可分享的照片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ShareKit.share(this, uri)) {
            Toast.makeText(this, "没找到能接收照片的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("怎么拨 · 为什么是这个数")
            .setMessage(HELP_TEXT)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 启动悬浮教练的三道门槛，缺一不可：
     * 1. 悬浮窗权限（显示在其他应用上层）——没有它浮窗根本加不进去；
     * 2. 录屏授权——Android 14 起每次都要用户点一次确认，无法长期记住；
     * 3. 前台服务——Android 12 起后台启动服务必须走 startForegroundService。
     */
    private fun startFloatingCoach() {
        // 悬浮教练要读屏，必须先有隐私同意 —— 这是本应用最敏感的一项授权，
        // 不能出现"用户还没同意条款就直接弹录屏授权"的路径。
        if (!privacyAccepted) {
            showPrivacyGate()
            return
        }
        // 第一次点：先给一页说明（这是给审核看的"为什么需要录屏+悬浮窗"的证据），
        // 用户点过「开始」之后就不再打扰。
        if (!uiPrefs.getBoolean(KEY_COACH_INTRO_SHOWN, false)) {
            binding.llCoachIntro.visibility = LinearLayout.VISIBLE
            return
        }
        beginCoachSetup()
    }

    /** 说明页之后的真正流程：悬浮窗权限 → 通知权限 → 录屏授权 */
    private fun beginCoachSetup() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先允许「显示在其他应用上层」，再点一次", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        // 通知权限：没有它，前台服务的常驻通知不显示 —— 用户看不到"正在录屏"的任何提示，
        // 这对屏幕捕获这种敏感权限是信任灾难。所以在发起录屏之前先把通知权限要下来。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingCoachStart = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchScreenCapture()
    }

    /** 真正发起录屏授权。拆出来是因为"通知权限已授予"与"刚授予"两条路都要走到这里。 */
    private fun launchScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestScreenCapture.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * 关于页（P0-2）。
     *
     * 之前全 App 只有一个地方能看隐私政策全文：首次覆盖层。用户点过「同意」之后
     * 就再也找不到了，而政策里还写着「历史版本可在『关于』页面查阅」—— 那句话当时是假的。
     *
     * 版本号从 PackageManager 现读，不写死：versionName 改了这里自动跟着变。
     */
    private fun showAbout() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "3.0.0"
        binding.tvAboutMeta.text = "光影调参 · 版本 $versionName\n联系邮箱：1912811308@qq.com"
        binding.llAbout.visibility = LinearLayout.VISIBLE
    }

    /**
     * 导出诊断信息（v3.0.0 阶段 3）。
     *
     * **只有用户主动点击才会产生这个 zip**，而且不经过任何上传通道 ——
     * 打包完交给系统分享面板，发给谁由用户自己决定。
     * 这与隐私政策「本应用无网络权限」完全一致，不构成"暗中收集数据"。
     */
    private fun exportDiagnostics() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val target = File(cacheDir, "shottuner-diag-${System.currentTimeMillis()}.zip")
        val zip = Diag.exportTo(target, versionName)
        if (zip == null) {
            Toast.makeText(this, "还没有可导出的诊断信息", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出诊断信息"))
        } catch (e: Throwable) {
            // 这里必须让用户知道失败，但不要说"ActivityNotFoundException"这种话
            Diag.e(TAG, "导出诊断信息失败", e)
            Toast.makeText(this, "导出失败，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 通知权限被拒：必须说清后果，并给一条去设置的路。
     *
     * 不说后果的话，用户只会看到"点了没反应"—— 而实际发生的是前台服务起来了、
     * 通知却不显示，他既不知道正在录屏，也没法从通知栏停止。这是最坏的一种状态。
     */
    private fun showNotificationDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("没有通知权限")
            .setMessage(
                "没有通知权限，录屏期间你收不到提醒，也无法从通知栏停止。\n\n" +
                    "你可以到系统设置里打开「通知」后再试一次。"
            )
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            Uri.parse("package:$packageName")
                        ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCoachService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Throwable) {
            // U4：用户看到的是"没起来"，不是"ForegroundServiceStartNotAllowedException"
            Diag.e(TAG, "启动悬浮教练前台服务失败", e)
            Toast.makeText(this, "启动失败，请重试", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "悬浮卡已就位，去打开系统相机试试（长按按钮可关闭）", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    /**
     * 自动写入节流：参数变化幅度达到阈值才重建 UseCase，
     * 避免频繁重绑导致的画面闪烁与对焦抖动。
     */
    private fun maybeApply(rec: Recommendation) {
        val eng = engine ?: return
        if (!caps.supportsManualSensor) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastApplyAt < 1200) return

        val isoThreshold = max(10, (appliedIso * 0.15f).roundToInt())
        val shutterThreshold = max(2_000_000L, (appliedShutterNs * 0.2).toLong())

        val isoFar = appliedIso == 0 || abs(rec.iso - appliedIso) > isoThreshold
        val shutterFar = appliedShutterNs == 0L || abs(rec.shutterNs - appliedShutterNs) > shutterThreshold

        if (isoFar || shutterFar) {
            appliedIso = rec.iso
            appliedShutterNs = rec.shutterNs
            lastApplyAt = now
            eng.applyManual(ManualParams(rec.iso, rec.shutterNs))
        }
    }

    private fun toggleMode() {
        if (!caps.supportsManualSensor) {
            Toast.makeText(this, "该设备不支持手动曝光写入，只能查看推荐值", Toast.LENGTH_SHORT).show()
            return
        }
        autoMode = !autoMode
        if (!autoMode) {
            engine?.applyManual(null)
            appliedIso = 0
            appliedShutterNs = 0L
        } else {
            val rec = lastRecommendation
            if (rec != null) {
                appliedIso = rec.iso
                appliedShutterNs = rec.shutterNs
                lastApplyAt = SystemClock.elapsedRealtime()
                engine?.applyManual(ManualParams(rec.iso, rec.shutterNs))
            }
        }
        updateModeUi()
    }

    private fun toggleLock() {
        locked = !locked
        if (locked) {
            lockedRec = lastRecommendation
            val rec = lastRecommendation
            if (rec != null && autoMode && caps.supportsManualSensor) {
                appliedIso = rec.iso
                appliedShutterNs = rec.shutterNs
                lastApplyAt = SystemClock.elapsedRealtime()
                engine?.applyManual(ManualParams(rec.iso, rec.shutterNs))
            }
            Toast.makeText(this, "已锁定当前参数组合", Toast.LENGTH_SHORT).show()
        } else {
            lockedRec = null
            Toast.makeText(this, "已解除锁定", Toast.LENGTH_SHORT).show()
        }
        updateModeUi()
    }

    private fun restoreAuto() {
        locked = false
        lockedRec = null
        autoMode = false
        appliedIso = 0
        appliedShutterNs = 0L
        engine?.applyManual(null)
        updateModeUi()
    }

    /**
     * 只刷新"是否在写入 / 是否锁定"这两个状态 —— 都落在按钮文案上。
     *
     * v1.9 起 `tvMode` 不再承担这个职责：它改成了档位切换入口，只显示
     * 「沉浸 · 点切换 / 专业 · 点切换」。信息没有丢，因为：
     *   · 「在写入」→ `btnMode` 的「本机写入（可选）/ 停止本机写入」
     *   · 「已锁定」→ `btnLock` 的「锁定参数 / 解除锁定」
     * 原来 tvMode 用三种文案重复这两件事，是典型的"同一件事说两遍"。
     */
    private fun updateModeUi() {
        binding.btnMode.text = if (autoMode) "停止本机写入" else "本机写入（可选）"
        binding.btnLock.text = if (locked) "解除锁定" else "锁定参数"
    }

    // ---------------- 界面档位（v1.9 P1-2） ----------------

    /** 读档位。存的字符串可能来自旧版本或被手改过，解析不出来就退回沉浸档。 */
    private fun loadUiPreset() {
        val raw = uiPrefs.getString(KEY_UI_PRESET, null)
        uiPreset = UiPreset.values().firstOrNull { it.storageKey == raw } ?: UiPreset.IMMERSIVE
    }

    /** 点 `tvMode` 循环切档，切完立即落盘 —— 下次冷启动保持用户的选择。 */
    private fun cycleUiPreset() {
        uiPreset = if (uiPreset == UiPreset.IMMERSIVE) UiPreset.PRO else UiPreset.IMMERSIVE
        uiPrefs.edit().putString(KEY_UI_PRESET, uiPreset.storageKey).apply()
        applyUiPreset()
    }

    /**
     * **所有档位相关的显隐都集中在这里**，别的地方一律不碰这几个 view 的 visibility。
     *
     * 沉浸档隐藏的四类东西，判据都是"专业用户才读得懂 / 只在调机位时有用"：
     * 1. `histogram`：直方图 —— 弱光曝光的辅助工具，默认档看数值就够（已归 llParams 管）；
     * 2. `tvCompose`：构图建议的**细节行**；
     * 3. `llMoreActions`：锁定/恢复自动/复制参数/怎么拨 —— 学一次就够的低频动作；
     * 4. 取景器上的俯仰刻度与主体圈（交给 GuideOverlayView 的两个开关）。
     *
     * **`tvAdvice` 必须留着**：它是"接下来手怎么动"的唯一出口（v1.8 刚把三行并成它），
     * 藏掉它这一档就没法用了。同理 `tvScore`（构图分）和 `tvScene` 也留着。
     *
     * **G1 改动**：`tvScoreDetail`（参考分）**不再跟着档位隐藏**。它承载 Aha 的分数，
     * 两个档位都必须可见 —— 以前它被归进"沉��档看不懂的数字"，但那串四格参数
     * 已经降级到 L3 了，参考分才是照片页该留下的那个数。
     *
     * **不要在这里碰 `llTaskCard` / `llAchieveCard`**：这两个的显隐只由 [renderTaskSurface]
     * 一处控制（本函数会在 selectTab 末尾被调用，两处都管就又回到 P1-a 那个
     * "谁最后执行谁说了算"的坑）。
     */
    private fun applyUiPreset() {
        val pro = uiPreset == UiPreset.PRO
        val detailVisibility = if (pro) View.VISIBLE else View.GONE

        binding.tvMode.text = "${uiPreset.label} · 点切换"
        binding.tvMode.setTextColor(ContextCompat.getColor(this, R.color.st_accent))

        // G1：tvScoreDetail 是 L2（参考分），**两个档位都必须可见**
        binding.tvScoreDetail.visibility = View.VISIBLE

        // 构图细节行维持原策略
        binding.tvCompose.visibility = detailVisibility

        // G1：直方图归 llParams 管，这里不再单独控制 —— 两处都管会出现
        // "专业档下 llParams 收起但直方图还露着"
        val paramsVisible = pro || paramsExpanded
        binding.llParams.visibility = if (paramsVisible) View.VISIBLE else View.GONE
        binding.tvParamsSummary.visibility = if (paramsVisible) View.GONE else View.VISIBLE
        setTextOnce(binding.tvParamsToggle, if (paramsVisible) "参数 ▴" else "参数 ▾")

        // 低频按钮行：沉浸档强制收起。
        // 专业档**不强制展开** —— P1-4 定的是"默认收起、由 btnMore 控制"，
        // 档位不该覆盖用户刚点开的动作。所以这里只做"沉浸档收起"这一个方向。
        if (!pro) binding.llMoreActions.visibility = View.GONE

        // 取景器上的几何辅助
        binding.guideOverlay.showPitchGauge = pro
        binding.guideOverlay.showSubjectMarks = pro
    }

    private fun formatEv(ev: Float): String {
        if (abs(ev) < 0.05f) return "±0"
        return String.format("%+.1f", ev)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Main"

        private const val TAB_PHOTO = 0
        private const val TAB_VIDEO = 1
        private const val TAB_FEATURE = 2
        private const val TAB_SKILL = 3

        /** 提示状态机的节拍。500ms 足够跟上 3s 驻留/8s 冷却的分辨率，又不至于把 UI 打满 */
        private const val COACH_TICK_MS = 500L

        /** 稳定度 EMA 系数：10Hz 输入下时间常数约 0.45s，够压掉单帧抖动 */
        private const val MOTION_ALPHA = 0.2f

        /** 欧拉角样本差到这个值算"一直在晃"（度/样本） */
        private const val MOTION_SCALE_DEG = 2f

        /** 设置存储。名字带包名后缀没意义，App 自己的私有目录 */
        private const val PREFS_NAME = "shottuner_prefs"

        /** 提示密度档位（`PromptDensity.name`）。默认值语义见 `parseDensity` */
        private const val KEY_PROMPT_DENSITY = "prompt_density"

        /** 音量下键当快门（默认开）。无障碍模式下可关 */
        private const val KEY_VOLUME_SHUTTER = "volume_shutter"

    /** 界面档位：`immersive` / `pro`。默认沉浸 */
    private const val KEY_UI_PRESET = "ui_preset"

    /**
     * 成片水印开关（G2-A）。默认开 —— 水印是唯一能跟着用户照片跑出应用的传播载体，
     * 默认关等于这个功能不存在。存 SharedPreferences，关掉后保存的照片不带水印。
     */
    private const val KEY_WATERMARK = "watermark_enabled"

        /** 隐私政策 v1 的同意标记。键名带版本号，将来条款升版可以重新征求同意 */
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted_v1"

        /**
         * G1：任务「到位」时的震动开关（默认开）。
         *
         * 见规格 §4.2 —— 震动是可感知的打扰，必须有关闭出口。权限本身是 normal 级
         *（安装即授予、零数据零网络），这里给的是"不想被打扰"的选择权。
         */
        private const val KEY_TASK_BUZZ = "task_buzz"

        /** 到位震动时长 15ms。真机若感觉不到可改 20ms（A4 上限是 400ms） */
        private const val ACHIEVE_BUZZ_MS = 15L

        /** 成绩卡停留时长。比复盘卡的 5s 长 —— 它是 Aha 的产物，要读完两行再决定点哪 */
        private const val SCORE_CARD_MS = MissionThresholds.SCORE_CARD_MS

        /** 任务卡淡入 120ms；超时换条是先淡出 90ms 再淡入 */
        private const val TASK_FADE_IN_MS = 120L
        private const val TASK_SWAP_OUT_MS = 90L

        /** 复盘卡自动收起的时长。5 秒够读完三行，又不至于一直占着面板顶部 */
        private const val REVIEW_CARD_MS = 5_000L

        /** 快门白闪：进 45ms + 出 45ms = 90ms（A4 上限 400ms） */
        private const val FLASH_IN_MS = 45L
        private const val FLASH_OUT_MS = 45L

        /** 快门缩放全程 120ms */
        private const val SHUTTER_PULSE_MS = 120L

        /** 缩略图飞入 180ms */
        private const val THUMB_FLY_MS = 180L

        /** 缩略图目标边长（px）。48dp × 4x 屏 ≈ 192，足够 centerCrop */
        private const val THUMB_TARGET_PX = 192

        /** 读数变化时那一格的 alpha 闪动时长（v1.8 P1-3）。只动 alpha，不动文字 */
        private const val PARAM_FLASH_MS = 200L

        /**
         * 建议区副句相对主句的字号比。
         *
         * 主句在 XML 里定的是 13sp，副句要 11sp → 11/13。用比例而不是绝对值，
         * 是因为 RelativeSizeSpan 作用在"当前 textSize"上，写绝对值反而会被叠加。
         */
        private const val ADVICE_SUB_SCALE = 11f / 13f

        /** panel 的基准内边距（与 activity_main.xml 的 padding 保持一致） */
        private const val PANEL_PADDING_DP = 14

        /** llControlBar 的基准下内边距（与 activity_main.xml 的 paddingVertical 一致） */
        private const val CONTROL_BAR_PADDING_DP = 6

        /** 双击退出的判定窗口 */
        private const val BACK_EXIT_MS = 2_000L

        /** 悬浮教练说明页是否已展示过。只在用户点过「开始」后置真 */
        private const val KEY_COACH_INTRO_SHOWN = "coach_intro_shown"

        /** 说明文案。这里放教学内容，不放口号——用户要的是「下一步手怎么动」 */
        private val HELP_TEXT = """
【照片怎么拨】
1. 相机顶部模式栏向左滑到底，进「专业」
2. 下方会出现 ISO / S / WB / AF / EV，点开任意一个就是拨盘
3. 建议值已经吸附到拨盘档位上，列表里一定找得到同名档位
4. 顺序永远是：先定快门（受手抖和主体运动限制），再用 ISO 补亮度
5. 色温拿不准就选 A 自动，混合光下才需要锁死 K 值

【三个数怎么理解】
快门管动与静 —— 慢于 1/25 手持容易糊；拍走动的人至少 1/125；想把水拍成丝就慢到 1/2 并找支撑。
ISO 管干净与明亮 —— 越低越干净，弱光下每加一档噪点肉眼可见地涨。
色温管冷与暖 —— 数值越低画面越蓝，越高越黄。
三者是乘法关系 —— 快门慢一倍等于 ISO 降一半，曝光量不变。

【录像怎么设】
录像和拍照最大的区别是：快门不能随便动，它必须跟着帧率走。
行规叫 180 度快门 —— 快门时间 = 帧间隔的一半：
· 24fps → 1/48（拨盘上选 1/50）
· 30fps → 1/60
· 60fps → 1/120
· 120fps → 1/240（拨盘上选 1/250）
违反这条，运动物体会出现频闪和抖动感。
想在不改快门的前提下让画面变暗，正确做法是加 ND 减光镜，不是拨快门。

【帧率怎么选】
30fps 是通用档，暗光下最稳。
60fps 给运动和走动，后期还能慢放一半。
120fps 以上是慢动作专用，每帧曝光只有 1/240 秒，室内基本拍不了。
24fps 最像电影，但口播和对焦容错小，新手别一上来就用。

【LOG 要不要开】
只在打算后期调色时开。直出会发灰、发平。
8bit 机器开 LOG 会出现色带；弱光下开 LOG，噪点会被提亮得更明显。

【快门在哪】
屏幕底部那一圈圆按钮就是快门，它不会随面板滚动跑掉。
音量下键也能当快门（滑不动屏幕时更好按），音量上键还是调音量；
无障碍模式下如果觉得冲突，到功能页把「音量键拍照」关掉就行。
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        // 姿态传感器只在界面可见时工作，退到后台立刻停，避免无谓耗电
        if (::orientationTracker.isInitialized) orientationTracker.start()
        if (::levelSensor.isInitialized) levelSensor.start()
        startCoachLoop()
        // 从系统设置页授权回来时，相机不会自己重启（架构师 R3/R7）：
        // 这里统一收口，权限变更、进程被回收后返回、受限模式切回，都靠它收敛。
        refreshCameraState()
    }

    override fun onPause() {
        // G1：退后台必须清空判定状态（含 hits 与 smoothed）。
        // 后台期间画面不更新，回来时主体早就变了，续着判必然误判。
        abortMission(AbortReason.PAUSE)
        if (::orientationTracker.isInitialized) orientationTracker.stop()
        if (::levelSensor.isInitialized) levelSensor.stop()
        stopCoachLoop()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::orientationTracker.isInitialized) orientationTracker.stop()
        if (::levelSensor.isInitialized) levelSensor.stop()
        coachHandler.removeCallbacksAndMessages(null)
        // 复盘卡的 5 秒收起定时器必须撤掉，否则持有 Activity 的 Runnable 会泄漏
        uiHandler.removeCallbacks(hideReviewRunnable)
        // 成绩卡的 8 秒收起定时器同理
        uiHandler.removeCallbacks(hideAchieveRunnable)
        coachLoopRunning = false
        engine?.stop()
        engine = null
        aiVision.close()
    }
}
