package com.shottuner.app.analysis

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

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

/**
 * 端侧视觉识别：人脸检测 + 图像打标。
 *
 * 为什么不用手机自带的 AI 助手：
 * 小米的超级小爱、华为的小艺、OPPO 的小布都没有向第三方 App 开放
 * 「把当前画面交给你，告诉我这是人像还是风景」这种能力。系统级的视觉入口
 * 只服务于自家相机和相册，第三方调不到，也没有标准 API。
 *
 * 可行的方案是自己带模型：ML Kit 的模型是打包进 APK 的，离线运行、
 * 不依赖 Google 服务、也不依赖任何厂商，因此对市面上所有 Android 机型都成立。
 * 代价是安装包变大（约 +12MB），换来的是真识别而不是猜。
 *
 * 两类模型各管一件事：
 * - Face Detection：判断有没有脸、几张、占多大面积。判人像最可靠，比肤色规则准得多。
 * - Image Labeling：400+ 通用标签（Sky / Mountain / Food / Dog …），用来判断风景、食物、宠物。
 */
class AiVision {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.06f)
            .build()
    )

    private val main = Handler(Looper.getMainLooper())

    /** 上一轮还没算完就跳过：AI 结果本来就是每 1.5 秒一次，不需要排队 */
    @Volatile
    private var busy = false

    fun analyze(bitmap: Bitmap, onResult: (AiVisionResult) -> Unit) {
        if (busy) {
            bitmap.recycle()
            return
        }
        busy = true

        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (_: Throwable) {
            busy = false
            bitmap.recycle()
            return
        }

        val lock = Any()
        var pending = 2
        var faceCount = 0
        var faceArea = 0f
        var faceCenterX = 0.5f
        var faceCenterY = 0.5f
        var faceBox = FaceBox.ZERO
        var headEulerY = 0f
        val tagSet = LinkedHashSet<String>()

        fun finish() {
            val emit = synchronized(lock) {
                pending--
                pending == 0
            }
            if (!emit) return
            busy = false
            val (scene, conf) = decide(faceCount, faceArea, tagSet)
            val tags = tagSet.take(6).map { translate(it) }
            val result = AiVisionResult(
                scene = scene,
                confidence = conf,
                faceCount = faceCount,
                faceArea = faceArea,
                tags = tags,
                ok = true,
                faceCenterX = faceCenterX,
                faceCenterY = faceCenterY,
                faceBox = faceBox,
                headEulerY = headEulerY
            )
            main.post {
                onResult(result)
                bitmap.recycle()
            }
        }

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                faceCount = faces.size
                var best = 0f
                val imageW = image.width.coerceAtLeast(1)
                val imageH = image.height.coerceAtLeast(1)
                for (f in faces) {
                    val b = f.boundingBox
                    val area = (b.width().toFloat() * b.height()) /
                        (imageW.toFloat() * imageH).coerceAtLeast(1f)
                    if (area > best) {
                        best = area
                        // boundingBox 是 image 像素坐标，除以宽高得到 0..1 的用户所见坐标。
                        // 这里用的 bitmap 已在 FrameAnalyzer 里按 sensor 朝向旋正。
                        faceBox = FaceBox(
                            left = (b.left.toFloat() / imageW).coerceIn(0f, 1f),
                            top = (b.top.toFloat() / imageH).coerceIn(0f, 1f),
                            right = (b.right.toFloat() / imageW).coerceIn(0f, 1f),
                            bottom = (b.bottom.toFloat() / imageH).coerceIn(0f, 1f)
                        )
                        faceCenterX = faceBox.centerX
                        faceCenterY = faceBox.centerY
                        headEulerY = f.headEulerAngleY
                    }
                }
                faceArea = best
                if (best <= 0f) {
                    // 检测到脸但框面积为 0 的极端情况：仍保留安全默认值
                    faceCenterX = 0.5f
                    faceCenterY = 0.5f
                    faceBox = FaceBox.ZERO
                    headEulerY = 0f
                }
                finish()
            }
            .addOnFailureListener { finish() }

        labeler.process(image)
            .addOnSuccessListener { labels ->
                for (l in labels) {
                    tagSet.add(l.text.lowercase())
                }
                finish()
            }
            .addOnFailureListener { finish() }
    }

    fun close() {
        try {
            labeler.close()
            faceDetector.close()
        } catch (_: Throwable) {
        }
    }

    private fun decide(
        faceCount: Int,
        faceArea: Float,
        tags: Set<String>
    ): Pair<AiScene, Float> {
        // 人脸是最硬的证据：有脸且占够面积，就是人像，不再看其他标签
        if (faceCount >= 3) return AiScene.GROUP to 0.92f
        if (faceCount >= 1 && faceArea >= 0.012f) {
            return AiScene.PORTRAIT to (0.86f + faceArea.coerceAtMost(0.2f) * 0.5f).coerceAtMost(0.98f)
        }

        val hasSky = tags.any { it in SKY }
        val hasNature = tags.any { it in NATURE }
        val hasPeople = tags.any { it in PEOPLE }
        val hasBuilding = tags.any { it in BUILDING }
        val hasFood = tags.any { it in FOOD }
        val hasPet = tags.any { it in PET }
        val hasFlower = tags.any { it in FLOWER }

        return when {
            hasPeople && !hasSky -> AiScene.PORTRAIT to 0.74f
            hasFood -> AiScene.FOOD to 0.90f
            hasPet -> AiScene.PET to 0.88f
            hasSky && hasNature -> AiScene.LANDSCAPE to 0.93f
            hasNature -> AiScene.LANDSCAPE to 0.86f
            hasSky -> AiScene.LANDSCAPE to 0.82f
            hasBuilding -> AiScene.ARCHITECTURE to 0.82f
            hasFlower -> AiScene.FLOWER to 0.80f
            else -> AiScene.UNKNOWN to 0.30f
        }
    }

    private fun translate(tag: String): String = CN[tag] ?: tag

    companion object {
        private val SKY = setOf(
            "sky", "cloud", "clouds", "blue", "sunset", "sunrise", "horizon",
            "afterglow", "dusk", "dawn", "daytime", "atmosphere"
        )
        private val NATURE = setOf(
            "mountain", "hill", "mountainous landforms", "highland", "plateau", "ridge",
            "summit", "valley", "canyon", "cliff", "rock", "tree", "forest", "wood",
            "jungle", "grove", "lake", "river", "sea", "ocean", "water", "beach",
            "coast", "shore", "waterfall", "stream", "pond", "bay", "grass", "field",
            "meadow", "lawn", "landscape", "nature", "wilderness", "island", "desert",
            "snow", "glacier", "leaf", "vegetation", "park"
        )
        private val PEOPLE = setOf(
            "person", "people", "selfie", "face", "human", "smile", "portrait",
            "portrait photography", "child", "baby", "woman", "man", "girl", "boy",
            "crowd", "gesture", "fun", "photography"
        )
        private val BUILDING = setOf(
            "building", "skyscraper", "architecture", "city", "town", "street",
            "house", "bridge", "tower", "urban area", "neighbourhood", "room",
            "interior design", "furniture", "ceiling", "wall", "floor"
        )
        private val FOOD = setOf(
            "food", "dish", "cuisine", "dessert", "meal", "cake", "pizza", "bread",
            "fruit", "vegetable", "sushi", "noodle", "drink", "coffee", "tea",
            "baked goods", "fast food", "snack", "recipe"
        )
        private val PET = setOf(
            "cat", "dog", "animal", "pet", "bird", "fish", "rabbit", "hamster",
            "wildlife", "mammal", "puppy", "kitten"
        )
        private val FLOWER = setOf(
            "flower", "plant", "garden", "rose", "blossom", "petal", "bouquet",
            "floristry", "houseplant"
        )

        private val CN = mapOf(
            "sky" to "天空", "cloud" to "云", "blue" to "蓝天",
            "sunset" to "日落", "sunrise" to "日出", "horizon" to "地平线",
            "mountain" to "山脉", "hill" to "丘陵", "valley" to "山谷",
            "tree" to "树木", "forest" to "森林", "wood" to "树林",
            "lake" to "湖", "river" to "河流", "sea" to "海", "ocean" to "海洋",
            "water" to "水面", "beach" to "沙滩", "coast" to "海岸",
            "waterfall" to "瀑布", "grass" to "草地", "field" to "田野",
            "landscape" to "风景", "nature" to "自然", "snow" to "雪",
            "person" to "人物", "people" to "人群", "selfie" to "自拍",
            "face" to "人脸", "smile" to "微笑", "portrait" to "人像",
            "child" to "小孩", "baby" to "婴儿", "woman" to "女性", "man" to "男性",
            "building" to "建筑", "skyscraper" to "高楼", "architecture" to "建筑",
            "city" to "城市", "street" to "街道", "house" to "房屋",
            "bridge" to "桥", "room" to "室内",
            "food" to "食物", "dish" to "菜品", "cuisine" to "料理",
            "dessert" to "甜点", "cake" to "蛋糕", "drink" to "饮品",
            "coffee" to "咖啡", "fruit" to "水果", "vegetable" to "蔬菜",
            "cat" to "猫", "dog" to "狗", "animal" to "动物", "pet" to "宠物",
            "bird" to "鸟", "flower" to "花", "plant" to "植物", "garden" to "花园",
            "night" to "夜景", "lighting" to "灯光", "car" to "汽车",
            "vehicle" to "车辆", "skyline" to "天际线"
        )
    }
}
