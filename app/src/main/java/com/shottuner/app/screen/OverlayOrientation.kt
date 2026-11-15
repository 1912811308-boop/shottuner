package com.shottuner.app.screen

import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮教练卡的「方向内核」。
 *
 * ## 为什么要有这个文件
 *
 * 「悬浮窗要不要跟着屏幕转」这件事，答案取决于两条 Android 层面的事实：
 *
 * 1. **方向请求是 per-display 的。**
 *    `Activity.setRequestedOrientation()` 最终走到
 *    `WindowManagerService.setRequestedOrientation(displayId, orientation)`，
 *    它锁的是**这个 display** 上的窗口方向，不是某一个 Activity。
 *    同一 display 上属于本 App 的窗口（包括 `TYPE_APPLICATION_OVERLAY` 悬浮窗）
 *    会一起被锁成同一个方向。MainActivity 在 manifest 里锁了 `portrait`，
 *    于是悬浮窗的**窗口坐标系**也跟着变成竖屏。
 * 2. **物理旋转不受方向锁影响。**
 *    `Display.getRotation()` 反映的是屏幕真实的物理朝向（0/90/180/270），
 *    `Display.getRealSize()` 给的是物理尺寸（横屏时是 2340×1080，而不是竖屏的 1080×2340）。
 *
 * 所以结论是：**悬浮窗不能跟着宿主 Activity 的方向走，只能自己管。**
 * 本文件把这套「方向 → 几何」的换算抽成纯函数，不 import 任何 Android 类型，
 * 因此可以脱离真机单测。
 *
 * ## 坐标系约定（改代码前先读完这段）
 *
 * 记 `R = Display.getRotation()`，取值 0 / 90 / 180 / 270（语义与
 * `android.view.Surface.ROTATION_*` 一致）。
 *
 * AOSP `Display.getRotation()` 的官方注释已经把正负号讲死了：
 *
 * > The angle is the rotation of the drawn graphics on the screen, which is the
 * > opposite direction of the physical rotation of the device. For example, if
 * > the device is rotated 90 degrees counter-clockwise, to compensate rendering
 * > will be rotated by 90 degrees clockwise and thus the returned value here
 * > will be Surface.ROTATION_90.
 *
 * 翻译成人话：
 *
 * - 设备**逆时针**转 90°（顶部转向用户左手边）⇒ `R == ROTATION_90`；
 * - 系统为了让画面正立，会把画出来的图形**顺时针**转 90°。
 *
 * 我们要做的补偿方向必须和系统一致 —— 也是顺时针。
 * 于是 **内容预旋转角 = R × 90°（顺时针为正，与 `View.rotation` 的约定一致）**：
 *
 * | R | 设备姿态 | 内容预旋转 |
 * |---|---|---|
 * | 0 | 竖持 | 0°（不转） |
 * | 90 | 逆时针横持（顶部在左） | 90° 顺时针 |
 * | 180 | 倒置 | 180° |
 * | 270 | 顺时针横持（顶部在右） | 270°（= 逆时针 90°） |
 *
 * 为什么 R=90 要转 +90 而不是 −90：设备逆时针转了 90°，
 * 画在玻璃上的内容也跟着逆时针歪了 90°，要把它扳回来就得**顺时针**转 90°。
 * R=270 同理镜像。
 *
 * ## 窗口坐标系
 *
 * 由于方向请求是 per-display 的，悬浮窗的窗口坐标系在横屏时**仍然是竖屏那套**
 * （宽 = 物理短边，高 = 物理长边）。所以：
 *
 * - 卡片宽度按**物理短边**算（横竖屏算出来一样，正好不越界）；
 * - 横屏时窗口用 `Gravity.CENTER`（居中在旋转下不变，换算后仍在画面正中）；
 * - 拖动增量从「屏幕坐标系」换算到「窗口坐标系」时，要按 R 旋转 90° 的整数倍。
 */
object OverlayOrientation {

    /**
     * 与 `android.view.Surface.ROTATION_*` 一致。
     * 这里重写一遍常量，是为了本文件不 import 任何 Android 类型（能直接单测）。
     */
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    // ── 偏好设置（CoachOverlay 与 MainActivity 共用，只能有一份字面量）──────────

    /**
     * 悬浮窗自己的 SharedPreferences 文件名（历史名，别改：用户在旧版的拖动位置在里面）。
     *
     * 放在这个纯函数文件里而不是各自的类里，是因为**两边都要读同一份**：
     * `CoachOverlay` 要读它决定朝向，功能页要写它让用户自救。
     * 两处各写一遍字符串，早晚有一处改漏 —— 表现就是「设置里改了，悬浮窗不理会」。
     */
    const val PREFS_OVERLAY = "shottuner_overlay"

    /**
     * 「悬浮窗方向」手动兜底档位的键。
     *
     * 存的是 [ManualOrientation] 的枚举名（见 [manualOrientationKey]），
     * 不是序号 —— 序号会被将来插入新档位偷偷改语义，枚举名不会。
     */
    const val KEY_ORIENTATION_MODE = "orientation_mode"

    /** 卡片在窗口坐标系里的落位方式 */
    enum class Anchor {
        /** 顶部居中（竖屏沿用旧行为，零回归） */
        TOP_CENTER,

        /** 正中（横屏用：横屏下「上边」是侧边，没有安全的顶部区域，居中最稳） */
        CENTER
    }

    /**
     * 悬浮窗**窗口坐标系**的两种模型。
     *
     * 之所以要区分：Android 的方向请求是 per-display 的，MainActivity 锁了 portrait，
     * 于是同一个 display 上属于本 App 的悬浮窗窗口，它的坐标系到底是
     * 「被一起锁成竖屏」还是「仍然跟随 display」，决定了整套几何怎么算。
     *
     * 这一条**只能真机确认**，所以做成可切换的两模型 —— 判错时改一个常量即可，
     * 不用在三处几何公式里同时动刀（改漏一处的后果是卡片歪 90° 或拖不动）。
     *
     * - [LOCKED_PORTRAIT]（默认）：窗口坐标系被锁在竖屏那套
     *   （宽 = 物理短边，高 = 物理长边）。横屏时框架把整个窗口旋转进 display 的
     *   逻辑坐标系，所以内容必须**预旋转 R×90°** 才正立，拖动增量也要换算。
     * - [FOLLOW_DISPLAY]：窗口坐标系跟随 display 的当前逻辑坐标系
     *   （横屏时 2340×1080）。框架不再替我们旋转，所以内容**不用预旋转**、
     *   拖动增量也**不用换算**，卡片宽度上限直接用逻辑宽。
     *
     * 真机判据：横屏时卡片**侧躺 90°**（文字横着）⇒ 窗口其实跟随了 display
     * ⇒ 切到 [FOLLOW_DISPLAY]。
     */
    enum class WindowSpaceModel {
        LOCKED_PORTRAIT,
        FOLLOW_DISPLAY
    }

    /**
     * **手动兜底**的朝向档位（功能页那个「悬浮窗方向」选择器）。
     *
     * ★ 为什么必须留这一档（注释里写清理由，将来别有人以「有自动了，删掉吧」为名删掉）：
     *   真机上的旋转行为**因 ROM 而异**。小米/华为/OPPO 各自会改一版：
     *   有的 OEM 对 overlay 不回调 `DisplayListener`，有的把系统相机做成
     *   「竖屏锁定 + 界面内部旋转」，还有的屏幕旋转由厂商自己的传感器算法决定
     *   （我们拿到的 rollout 可能差 20°）。自动判定再聪明也不可能覆盖全部机型，
     *   **给用户一个能立刻自救的开关，比让我们再猜一轮便宜** —— 用户转一下手机、
     *   点两下就正了，而我们不必再等一次真机反馈。
     */
    enum class ManualOrientation {
        /** 按 display + 设备物理朝向自动判定（默认） */
        AUTO,

        /** 强制竖屏：内容不预旋转，永远竖着 */
        FORCE_PORTRAIT,

        /**
         * 强制横屏，手机**顶部朝左**（= 设备逆时针横置）。
         *
         * 补偿方向见文件头的推导：[ROTATION_90] ⇒ 内容顺时针转 90°。
         */
        FORCE_LANDSCAPE_TOP_LEFT,

        /**
         * 强制横屏，手机**顶部朝右**（= 设备顺时针横置）。
         *
         * 补偿方向：[ROTATION_270] ⇒ 内容顺时针转 270°（视觉上等于逆时针 90°）。
         */
        FORCE_LANDSCAPE_TOP_RIGHT
    }

    /**
     * 判定「设备物理上已经横过来」的横滚角阈值（度）。
     *
     * 取值 55 的理由：竖持时手抖 ±20° 很常见，横持是 ±90°，
     * 55 正好是两者的中点 —— 两边各留 35° 余量。
     * 取 45 会在「举着手机慢慢转」时在阈值附近来回抖（每抖一次就重建一次悬浮窗）；
     * 取 65 则略斜着拿就判不出横屏，用户还是会看到竖排的字。
     */
    const val LANDSCAPE_ROLL_THRESHOLD_DEG = 55f

    /** 拖动增量（窗口坐标系） */
    data class Delta(val x: Int, val y: Int)

    /**
     * 旋转之后的视觉外框与旋转支点。
     *
     * @param widthPx 旋转后卡片在窗口里占的宽
     * @param heightPx 旋转后卡片在窗口里占的高
     * @param pivotX 旋转支点 x（相对卡片自身坐标系）
     * @param pivotY 旋转支点 y（相对卡片自身坐标系）
     */
    data class Visual(
        val widthPx: Int,
        val heightPx: Int,
        val pivotX: Float,
        val pivotY: Float
    )

    /** 非法 rotation 一律按 0 处理，避免脏数据把几何算炸 */
    fun normalizeRotation(rotation: Int): Int = when (rotation) {
        ROTATION_90, ROTATION_180, ROTATION_270 -> rotation
        else -> ROTATION_0
    }

    /** 是否横屏姿态（rotation 90 / 270） */
    fun isLandscape(rotation: Int): Boolean {
        val r = normalizeRotation(rotation)
        return r == ROTATION_90 || r == ROTATION_270
    }

    /**
     * 内容需要预旋转的角度（顺时针为正，与 `View.rotation` 约定一致）。
     *
     * 推导见文件头的注释：R=90 ⇒ 设备逆时针转了 90° ⇒ 内容顺时针转 90° 才正立。
     *
     * @param model 窗口坐标系模型：窗口跟随 display 时框架不再替我们旋转，
     *   也就不需要预旋转（详见 [WindowSpaceModel]）。
     */
    fun contentRotationDegrees(
        rotation: Int,
        model: WindowSpaceModel = WindowSpaceModel.LOCKED_PORTRAIT
    ): Float =
        if (model == WindowSpaceModel.FOLLOW_DISPLAY) {
            0f
        } else {
            normalizeRotation(rotation) * 90f
        }

    /**
     * **实际的**「内容预旋转角」—— 本题（横屏时悬浮卡不跟着转）的修复核心。
     *
     * ## 为什么 [contentRotationDegrees] 不够
     *
     * 它只信 `Display.getRotation()`。而真机上存在一类「界面转了、display 没转」的情形：
     * 系统相机把 Activity 锁成竖屏，再在自己的 View 里手动把画面转 90°（MIUI 就这么干）。
     * 用户**看到**界面横过来了，`Display.getRotation()` 却仍然返回 [ROTATION_0]，
     * 于是 [WindowSpaceModel.FOLLOW_DISPLAY] 分支算出「不用预旋转」= 0°，
     * 卡片就竖着不动 —— 这正是 clover 在小米 14 Pro 上复现的那个 bug。
     *
     * 补法是引入第二个信号：**设备物理朝向**（加速度计/重力的横滚角）。
     * display 说「没转」、但设备物理上已经横过来了，就用设备角度补上。
     *
     * ## 规则（改之前先想清楚，这四条是互相咬合的）
     *
     * | displayRotation | \|deviceRoll\| | model | 返回 |
     * |---|---|---|---|
     * | ≠ 0 | 任意 | [WindowSpaceModel.FOLLOW_DISPLAY] | `0f`（窗口自己转了，内容不补） |
     * | ≠ 0 | 任意 | [WindowSpaceModel.LOCKED_PORTRAIT] | `rotation × 90f`（老逻辑，没变） |
     * | 0 | ≥ [LANDSCAPE_ROLL_THRESHOLD_DEG] | 两者 | `+90f`（roll > 0）/ `-90f`（roll < 0） |
     * | 0 | < 阈值 | 两者 | `0f`（竖持，含手抖） |
     *
     * 第一条优先于设备角度：display 已经告诉我们它转了，**它比传感器准** ——
     * 传感器有噪声、有延迟，而且用户可能只是把手机放在桌沿斜着。
     *
     * ## 符号
     *
     * `deviceRollDeg > 0` 对应 `ROTATION_90`（设备逆时针横置、顶部朝左），
     * 而 `ROTATION_90` 的补偿是 `+90°`（见文件头推导）。所以正负号可以直接搬过来，
     * 不需要取反 —— 这一点用 `LevelMath` 的实测口径验过：
     * 设备顶部朝左时重力读数 `(gx, gy) = (+g, 0)`，`atan2(gx, gy) = +90°`。
     *
     * @param displayRotation `Display.getRotation()`（0/1/2/3）
     * @param deviceRollDeg 加速度计算出的横滚角（-180..180），**0 = 竖持**，
     *   必须是「不按屏幕方向补偿」的原始设备角（`LevelMath.solve` 传 rotationDegrees = 0）
     * @param model 窗口坐标系模型
     * @return 内容预旋转角（顺时针为正，与 `View.rotation` 约定一致）
     */
    fun effectiveContentDegrees(
        displayRotation: Int,
        deviceRollDeg: Float,
        model: WindowSpaceModel = WindowSpaceModel.LOCKED_PORTRAIT
    ): Float {
        val rotation = normalizeRotation(displayRotation)
        val roll = normalizeRoll(deviceRollDeg)

        // 设备物理上横过来了吗？横了就给 ±90°，否则 null（= 传感器说不出话来）
        val fromDevice: Float? = if (abs(roll) >= LANDSCAPE_ROLL_THRESHOLD_DEG) {
            if (roll > 0f) 90f else -90f
        } else {
            null
        }

        return when (model) {
            // 窗口跟随 display：display 说转了，框架已经替我们转好，内容不补
            WindowSpaceModel.FOLLOW_DISPLAY ->
                if (rotation != ROTATION_0) 0f else fromDevice ?: 0f

            // 窗口被锁在竖屏：老逻辑不动，只在 display 说不出话时用设备角度兜底
            WindowSpaceModel.LOCKED_PORTRAIT ->
                if (rotation != ROTATION_0) rotation * 90f else fromDevice ?: 0f
        }
    }

    /**
     * 超出手动档位之外的最终决策：**手动档优先于自动判定**。
     *
     * @param manual 用户在功能页选的档位
     * @return 内容预旋转角；[ManualOrientation.AUTO] 时等于
     *   [effectiveContentDegrees] 的结果
     */
    fun resolveContentDegrees(
        displayRotation: Int,
        deviceRollDeg: Float,
        model: WindowSpaceModel = WindowSpaceModel.LOCKED_PORTRAIT,
        manual: ManualOrientation = ManualOrientation.AUTO
    ): Float = manualContentDegrees(manual)
        ?: effectiveContentDegrees(displayRotation, deviceRollDeg, model)

    /**
     * 手动档位对应的固定预旋转角。
     *
     * @return null 表示「不强制」= [ManualOrientation.AUTO]，交给自动判定
     */
    fun manualContentDegrees(manual: ManualOrientation): Float? = when (manual) {
        ManualOrientation.AUTO -> null
        ManualOrientation.FORCE_PORTRAIT -> 0f
        // 顶部朝左 = ROTATION_90 的姿态 = 顺时针补 90°（与文件头表格一致）
        ManualOrientation.FORCE_LANDSCAPE_TOP_LEFT -> 90f
        // 顶部朝右 = ROTATION_270 的姿态 = 顺时针补 270°（视觉上逆时针 90°）
        ManualOrientation.FORCE_LANDSCAPE_TOP_RIGHT -> 270f
    }

    /**
     * 档位 → 存进 SharedPreferences 的字符串（枚举名）。
     *
     * 用枚举名而不是序号：将来在中间插一档不会把老用户的选择改成语义不同的另一档。
     */
    fun manualOrientationKey(manual: ManualOrientation): String = manual.name

    /**
     * 从 SharedPreferences 读出的字符串还原档位。
     *
     * 脏数据（null / 空串 / 手改过的值 / 老版本残留）一律回落 [ManualOrientation.AUTO] ——
     * 默认值必须是「自动」，否则一次读错就把用户锁死在某个固定朝向里。
     */
    fun parseManualOrientation(raw: String?): ManualOrientation =
        if (raw.isNullOrBlank()) {
            ManualOrientation.AUTO
        } else {
            ManualOrientation.entries.firstOrNull { it.name == raw } ?: ManualOrientation.AUTO
        }

    /**
     * 把任意横滚角规整到 [-180, 180]，非有限值按 0（竖持）处理。
     *
     * 传感器偶尔会给出 NaN/Inf（驱动 bug 或自由落体），一次脏值不能把卡片翻掉。
     */
    private fun normalizeRoll(roll: Float): Float {
        if (!roll.isFinite()) return 0f
        var d = roll % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * 预旋转角 → `Surface.ROTATION_*` 序号（0/1/2/3）。
     *
     * 旋转壳与 [visual] 都按**序号**表达（四套几何本来就是按 90° 整数倍切的），
     * 所以 [effectiveContentDegrees] 给出 `-90f` 时要能换回 [ROTATION_270]。
     * 非 90° 倍数的脏值就近取整，不抛异常。
     */
    fun rotationIndexOf(contentDegrees: Float): Int {
        if (!contentDegrees.isFinite()) return ROTATION_0
        var d = contentDegrees % 360f
        if (d < 0f) d += 360f
        // +45 后整除 90 就是「就近的 90° 整数倍」；%4 把 315..360 折回 0
        return when ((((d + 45f) / 90f).toInt()) % 4) {
            1 -> ROTATION_90
            2 -> ROTATION_180
            3 -> ROTATION_270
            else -> ROTATION_0
        }
    }

    /** 是否四分之一转（横置）：内容预旋转 90° / 270° 时旋转壳要宽高对调 */
    fun isQuarterTurn(contentDegrees: Float): Boolean {
        val idx = rotationIndexOf(contentDegrees)
        return idx == ROTATION_90 || idx == ROTATION_270
    }

    /**
     * 悬浮窗窗口坐标系的尺寸（宽、高）。
     *
     * @param realWidthPx / realHeightPx `Display.getRealSize()` 的值（已按当前旋转调换）
     * @param shortSidePx / longSidePx 屏幕物理短边 / 长边
     */
    fun windowSpaceSize(
        model: WindowSpaceModel,
        realWidthPx: Int,
        realHeightPx: Int,
        shortSidePx: Int,
        longSidePx: Int
    ): Pair<Int, Int> = when (model) {
        // 被锁在竖屏那套：窗口坐标系恒为「短边 × 长边」
        WindowSpaceModel.LOCKED_PORTRAIT -> shortSidePx to longSidePx
        // 跟随 display：就是 display 当前的逻辑尺寸（横屏时 2340×1080）
        WindowSpaceModel.FOLLOW_DISPLAY -> realWidthPx to realHeightPx
    }

    /**
     * 该姿态下的落位方式。
     *
     * ★ 必须看 `model`，不能只看 `rotation`：
     *   - [WindowSpaceModel.LOCKED_PORTRAIT]：横屏时窗口被框架整体旋转，
     *     窗口坐标系里「上边」其实是画面的侧边，没有安全的顶部区域，所以用 [Anchor.CENTER]。
     *   - [WindowSpaceModel.FOLLOW_DISPLAY]：窗口坐标系就是 display 的逻辑坐标系，
     *     横屏时「上边」是真的上边，用 [Anchor.TOP_CENTER] 才对 ——
     *     沿用 CENTER 会把卡片放到画面正中，正好压住主体，比竖屏的顶部位置更糟。
     *
     * ★ 默认值保持 [WindowSpaceModel.LOCKED_PORTRAIT]：既有调用点与测试的行为不变，
     *   只有真正知道模型的地方（`CoachOverlay`）才显式传。
     *
     * @param rotation `Display.getRotation()`
     * @param model 窗口坐标系模型
     */
    fun anchorFor(
        rotation: Int,
        model: WindowSpaceModel = WindowSpaceModel.LOCKED_PORTRAIT
    ): Anchor =
        if (model == WindowSpaceModel.FOLLOW_DISPLAY) {
            Anchor.TOP_CENTER
        } else if (isLandscape(rotation)) {
            Anchor.CENTER
        } else {
            Anchor.TOP_CENTER
        }

    /**
     * 卡片宽度上限。
     *
     * 卡片的排版宽度必须装得下**窗口坐标系的宽** —— 窗口坐标系被锁住时是竖屏的宽
     * （物理短边），跟随 display 时是逻辑宽（横屏时是长边）。
     *
     * @param spaceWidthPx 窗口坐标系的宽（像素）
     * @param density 屏幕密度
     * @param cardMaxDp 卡片最大宽度（dp）
     * @param marginDp 卡片与屏幕边缘的最小留白（dp）
     */
    fun cardWidthPx(spaceWidthPx: Int, density: Float, cardMaxDp: Int, marginDp: Int): Int {
        val maxPx = (cardMaxDp * density).toInt()
        val limitPx = spaceWidthPx - (marginDp * density).toInt()
        return min(maxPx, limitPx).coerceAtLeast(0)
    }

    /**
     * 卡片旋转之后的视觉外框与支点。
     *
     * 设卡片排版尺寸为 `w × h`（宽 × 高），绕支点顺时针转 θ：
     * - θ=0/180：视觉外框仍是 `w × h`；
     * - θ=90/270：视觉外框变成 `h × w`（宽高对调）。
     *
     * 支点的取法是让旋转后的外框左上角正好落在 (0,0)：
     * - θ=90 → 支点 (h/2, h/2)：宽高对调后左上角回到原点；
     * - θ=180 → 支点 (w/2, h/2)：绕中心转，外框不变；
     * - θ=270 → 支点 (w/2, w/2)。
     */
    fun visual(cardWidthPx: Int, contentHeightPx: Int, rotation: Int): Visual {
        val w = cardWidthPx
        val h = contentHeightPx
        return when (normalizeRotation(rotation)) {
            ROTATION_90 -> Visual(widthPx = h, heightPx = w, pivotX = h / 2f, pivotY = h / 2f)
            ROTATION_180 -> Visual(widthPx = w, heightPx = h, pivotX = w / 2f, pivotY = h / 2f)
            ROTATION_270 -> Visual(widthPx = h, heightPx = w, pivotX = w / 2f, pivotY = w / 2f)
            else -> Visual(widthPx = w, heightPx = h, pivotX = w / 2f, pivotY = h / 2f)
        }
    }

    /**
     * 把「屏幕坐标系」里的拖动增量换算成「窗口坐标系」里的增量。
     *
     * 屏幕坐标系 = 物理画面（横屏时物理画面是横的）；
     * 窗口坐标系 = 悬浮窗自己那套。
     *
     * - 窗口被锁在竖屏坐标系时（[WindowSpaceModel.LOCKED_PORTRAIT]），
     *   两者差一个 R×90° 的旋转（顺时针为正）；
     * - 窗口跟随 display 时（[WindowSpaceModel.FOLLOW_DISPLAY]），
     *   两者就是同一个坐标系，不换算。
     */
    fun windowDelta(
        screenDx: Int,
        screenDy: Int,
        rotation: Int,
        model: WindowSpaceModel = WindowSpaceModel.LOCKED_PORTRAIT
    ): Delta {
        if (model == WindowSpaceModel.FOLLOW_DISPLAY) return Delta(screenDx, screenDy)
        return when (normalizeRotation(rotation)) {
            // 顺时针 90°：(x, y) → (-y, x)
            ROTATION_90 -> Delta(x = -screenDy, y = screenDx)
            // 180°：(x, y) → (-x, -y)
            ROTATION_180 -> Delta(x = -screenDx, y = -screenDy)
            // 顺时针 270°（= 逆时针 90°）：(x, y) → (y, -x)
            ROTATION_270 -> Delta(x = screenDy, y = -screenDx)
            else -> Delta(x = screenDx, y = screenDy)
        }
    }

    /** 顶部锚点下，x 的合法偏移范围是 ±maxOffset（相对水平居中） */
    fun maxOffsetXPx(spaceWidthPx: Int, cardWidthPx: Int): Int =
        ((spaceWidthPx - cardWidthPx) / 2).coerceAtLeast(0)

    fun clampXPx(x: Int, maxOffsetXPx: Int): Int =
        x.coerceIn(-maxOffsetXPx, maxOffsetXPx)

    /** 顶部锚点下，y 是距顶部的距离：上不越过状态栏，下留出快门区 */
    fun clampTopYPx(y: Int, spaceHeightPx: Int, minTopPx: Int, bottomReservePx: Int): Int =
        y.coerceIn(minTopPx, (spaceHeightPx - bottomReservePx).coerceAtLeast(minTopPx))

    /**
     * 居中锚点下，y 是相对中心的偏移：卡片上下都别出界。
     *
     * 横屏用的是「居中」锚点，窗口坐标系的 y 轴在物理画面里是横向的，
     * 「下边留快门区」那套语义在这里对不上，所以上下留同一个边距即可。
     *
     * @param visualHeightPx 卡片在窗口坐标系里的高（横屏时是旋转后的高）
     */
    fun clampCenterYPx(
        y: Int,
        spaceHeightPx: Int,
        visualHeightPx: Int,
        marginPx: Int
    ): Int {
        val half = ((spaceHeightPx - visualHeightPx) / 2).coerceAtLeast(0)
        val limit = (half - marginPx).coerceAtLeast(0)
        return y.coerceIn(-limit, limit)
    }
}
