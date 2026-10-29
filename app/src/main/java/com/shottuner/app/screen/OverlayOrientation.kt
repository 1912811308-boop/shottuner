package com.shottuner.app.screen

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

    /** 该姿态下的落位方式 */
    fun anchorFor(rotation: Int): Anchor =
        if (isLandscape(rotation)) Anchor.CENTER else Anchor.TOP_CENTER

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
