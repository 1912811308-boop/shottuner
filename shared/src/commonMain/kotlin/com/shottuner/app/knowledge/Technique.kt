package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType

/**
 * 技法的分类。分类只用于筛选和视觉分组，不参与硬件能力判断。
 *
 * v1.5 新增 4 个值（`SELFIE` / `CAMERA_MOVE` / `PARAMS` / `PITFALL`）。
 * 加值会让所有对该枚举做穷尽 `when` 的地方编译失败 —— 这是**故意**的：
 * 编译器会点名每一处需要补分支，比运行时静默落到 `else` 安全得多。
 * 已知需要补的是 [TechniqueMatcher.reasonFor]。
 */
enum class TechCategory(val label: String) {
    PORTRAIT("人像"),
    LANDSCAPE("风光"),
    NIGHT("夜景"),
    FOOD("食物"),
    STREET("街头"),
    ACTION("宠物小孩"),
    MACRO("微距细节"),
    LIGHT("用光"),
    BASIC("基本功"),
    INDOOR("室内弱光"),
    SILHOUETTE("剪影"),

    /** 自拍。本 App 没有前置通路，这 5 条只做技法页浏览内容，不进任何推送池。 */
    SELFIE("自拍"),

    /** 运镜。只在录像页（`PromptEngine.TAB_VIDEO`）出现。 */
    CAMERA_MOVE("运镜"),

    /** 参数起步值。 */
    PARAMS("参数"),

    /** 避坑。命中的是"设置/习惯"层面的错误，不是画面层面的。 */
    PITFALL("避坑")
}

/**
 * 匹配用的画面信号。
 *
 * 由 MainActivity 从 FrameStats 和 DeviceProfile 折算而来。这里刻意不依赖
 * Android 类型，保证知识层可以直接跑 JVM 单元测试。
 */
class TechSignals(
    val meanLuma: Float = 0.45f,
    val highlightClip: Float = 0f,
    val shadowClip: Float = 0f,
    val dynamicRange: Float = 0.5f,
    val saturation: Float = 0.3f,
    val skinRatio: Float = 0f,
    val edgeDensity: Float = 0.2f,
    val centerBias: Float = 0f,
    val colorCast: Float = 0f,
    /** 有独立长焦镜头 */
    val hasTele: Boolean = false,
    /** 支持手动曝光 */
    val supportsManual: Boolean = false,
    /** 最长快门 >= 1 秒 */
    val supportsLongExposure: Boolean = false,
    /** 是否已经拿到真实画面 */
    val hasStats: Boolean = false
)

/**
 * 一条摄影技法。文案是主体，trigger 只负责把它和当前画面接上。
 */
class Technique(
    /** 稳定英文 id，用于测试和埋点 */
    val id: String,
    /** 带判断力的短标题 */
    val title: String,
    val category: TechCategory,
    /** 一句话口诀 */
    val oneLiner: String,
    /** 什么情况下用 */
    val whenTo: String,
    /** 3-5 步具体动作 */
    val steps: List<String>,
    /** 起手参数键值对 */
    val params: List<Pair<String, String>>,
    /** 为什么有效，讲物理或视觉原理 */
    val why: String,
    /** 常见错误 */
    val mistakes: List<String>,
    /** 拍完怎么判断成功 */
    val check: String,
    val trigger: TechTrigger
)

/**
 * 技法与画面的匹配条件。
 *
 * 数值条件一旦设置就必须满足，否则该条直接出局；场景只负责加分，
 * 这样通用条目不会被场景过滤掉。
 */
class TechTrigger(
    val scenes: Set<SceneType> = emptySet(),
    val maxLuma: Float? = null,
    val minLuma: Float? = null,
    val minHighlightClip: Float? = null,
    val minDynamicRange: Float? = null,
    val maxDynamicRange: Float? = null,
    val minSaturation: Float? = null,
    val minSkinRatio: Float? = null,
    val minEdgeDensity: Float? = null,
    val maxEdgeDensity: Float? = null,
    val requiresTele: Boolean = false,
    val requiresManual: Boolean = false,
    val requiresLongExposure: Boolean = false,
    /** 基础权重，越大越优先 */
    val weight: Int = 10,
    /**
     * 暗部死黑占比下限（`FrameStats.shadowClip`）。null = 不限制。
     * v1.5 新增：欠曝类条目（"白加黑减"等）要靠它才能真正被触发。
     */
    val minShadowClip: Float? = null,
    /**
     * 高光溢出占比上限（`FrameStats.highlightClip`）。null = 不限制。
     * v1.5 新增：过曝类条目（"先保高光"等）要靠它才能真正被触发。
     */
    val maxHighlightClip: Float? = null
)
