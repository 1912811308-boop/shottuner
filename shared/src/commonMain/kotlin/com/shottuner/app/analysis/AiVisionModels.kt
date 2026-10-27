package com.shottuner.app.analysis

/**
 * 端侧视觉识别的**纯数据模型**（D1 之后下沉到 commonMain）。
 *
 * 为什么在这里：
 *   这三个类型原本与 ML Kit 的调用代码同处 [AiVision] 一个文件，而那个文件 import 了
 *    与 ML Kit —— 于是**纯数据类被不纯的文件拖累**，同包的
 *   [SceneClassifier] 也就跟着不可移植。
 *   把它们拆出来单独成文件后：commonMain 里零 ML Kit / 零 Android 依赖，
 *   而**标签词表与置信度阈值仍留在平台侧**（Android 用 ML Kit 英文标签，iOS 用 Vision 词表）。
 *
 * ★ 枚举**直接进 commonMain，不降级成 String** —— 与平台绑定的是「标签词表」和
 *   「置信度阈值」，不是枚举本身。降级成 String 只会多一层将来要同步维护的映射。
 */

/** AI 看到的大类。只有这几类会影响参数推荐，再细分没有意义。 */
enum class AiScene(val label: String) {
    PORTRAIT("人像"),
    GROUP("合影"),
    LANDSCAPE("风景"),
    ARCHITECTURE("建筑"),
    FOOD("食物"),
    PET("宠物"),
    FLOWER("花草"),
    UNKNOWN("未识别")
}

/**
 * 归一化人脸框，坐标范围 0..1，原点在画面左上角。
 * 单独定义一个纯 Kotlin 数据类，方便机位教练直接消费，不依赖 Android RectF。
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    companion object {
        val ZERO = FaceBox(0f, 0f, 0f, 0f)
    }
}

class AiVisionResult(
    val scene: AiScene,
    val confidence: Float,
    val faceCount: Int,
    /** 最大人脸占画面的面积比 */
    val faceArea: Float,
    /** 中文标签，给用户看 */
    val tags: List<String>,
    val ok: Boolean,
    /** 最大人脸框中心 X，归一化 0..1；无脸时为 0.5 */
    val faceCenterX: Float = 0.5f,
    /** 最大人脸框中心 Y，归一化 0..1；无脸时为 0.5 */
    val faceCenterY: Float = 0.5f,
    /** 最大人脸框，归一化 0..1；无脸时全零 */
    val faceBox: FaceBox = FaceBox.ZERO,
    /** 头部绕 Y 轴旋转角（度），正值表示脸朝画面右侧；无脸时为 0 */
    val headEulerY: Float = 0f
) {
    companion object {
        val EMPTY = AiVisionResult(
            scene = AiScene.UNKNOWN,
            confidence = 0f,
            faceCount = 0,
            faceArea = 0f,
            tags = emptyList(),
            ok = false,
            faceCenterX = 0.5f,
            faceCenterY = 0.5f,
            faceBox = FaceBox.ZERO,
            headEulerY = 0f
        )
    }
}
