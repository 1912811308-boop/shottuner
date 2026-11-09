package com.shottuner.app.analysis

import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import kotlin.math.abs

/**
 * 规则型场景判定。
 *
 * 为什么先用规则而不是 CNN：
 * - 场景分类只是参数映射的分支条件，规则可解释、可调试、零延迟；
 * - 端侧 CNN 需要标注数据集与模型文件，属于第二阶段优化项；
 * - 规则模型在 Python 侧有等价实现（python/shottuner/scene.py），可离线回归验证。
 *
 * 调参经验：场景之间会互相"抢分"，改任一分支的权重都必须用 samples/ 下的
 * 四张样张 + pytest 重新验证，否则很容易把逆光判成风景、把暖墙判成人像。
 */
object SceneClassifier {

    class Result(
        val scene: SceneType,
        val confidence: Float,
        /** 判定来源：规则 / AI·人脸 / AI·标签。给界面标注用，让人知道凭什么这么判 */
        val source: String = "规则"
    )

    /**
     * 规则 + 端侧 AI 的融合判定。
     *
     * 融合原则不是「谁更先进听谁的」，而是按证据强度排：
     * 1. **检测到人脸就是人像**，无条件覆盖。这是唯一有硬证据的信号，
     *    肤色规则会被暖墙骗，人脸检测不会。
     * 2. 图像打标置信度高（≥0.85）或规则自己拿不准（<0.60）时，才让 AI 改判。
     *    打标对「天空 + 山脉 + 沙滩」这类场景很准，但偶尔把室内暖光判成食物。
     * 3. 逆光例外：规则用「P95 极高 + P05 极低 + 动态范围大」判逆光，
     *    这个组合特征比打标可靠得多，AI 说风景也不改。
     */
    fun classify(s: FrameStats, ai: AiVisionResult?): Result {
        val rule = classify(s)
        if (ai == null || !ai.ok) return rule

        if (ai.faceCount >= 1 && ai.faceArea >= 0.012f) {
            return Result(SceneType.PORTRAIT, ai.confidence.coerceIn(0.6f, 0.98f), "AI·人脸")
        }

        val mapped = when (ai.scene) {
            AiScene.PORTRAIT, AiScene.GROUP -> SceneType.PORTRAIT
            AiScene.LANDSCAPE, AiScene.ARCHITECTURE -> SceneType.LANDSCAPE
            AiScene.FOOD, AiScene.PET, AiScene.FLOWER -> SceneType.GENERAL
            else -> null
        } ?: return rule

        if (rule.scene == SceneType.BACKLIT && rule.confidence > 0.70f) return rule

        // ★ 人像优先：只要 AI 说画面里有「人」（标签级即可，不要求脸够大），就按人像算。
        //
        // 为什么给它开这个口子、而不是继续用下面的「置信度 ≥ 0.85 才改判」：
        //   实测最常见的误判是**户外人像**——人物在画面里、背景有天空/山，
        //   AI 打成「人 + 天空 + 自然」时，规则侧会因为天空占比高算成风景（conf 常在 0.7 上下），
        //   而标签级人像只有 0.74~0.78，**永远到不了 0.85 那条线** ⇒ 规则赢 ⇒ 判成风景。
        //   结果就是用户举着人像拍，App 却在教他拍风景（构图建议、参数预设全跟着错）。
        //
        // 为什么这个方向的错更该避免：本产品的主场景就是拍人。把风景判成人像的代价
        //   是少两句风光建议；把人像判成风景的代价是**整套建议都在讲错的对象**。
        //
        // 为什么阈值是 0.70 而不是照单全收：ML Kit 的 people 标签在纯风景里偶尔也会冒出来
        //   （画面里有个远处路人），0.70 能把最弱的那一档挡掉。
        if (mapped == SceneType.PORTRAIT && ai.confidence >= 0.70f) {
            return Result(SceneType.PORTRAIT, ai.confidence.coerceIn(0.55f, 0.95f), "AI·人物")
        }

        if (ai.confidence >= 0.85f || rule.confidence < 0.60f) {
            return Result(mapped, ai.confidence.coerceIn(0.45f, 0.95f), "AI·标签")
        }
        return rule
    }

    fun classify(s: FrameStats): Result {
        if (s.width == 0) return Result(SceneType.GENERAL, 0f)

        val scores = HashMap<SceneType, Float>()

        // 夜景：整体极暗，或大面积死黑
        var night = 0f
        if (s.meanLuma < 0.14f) night += 0.55f
        if (s.meanLuma < 0.22f) night += 0.20f
        if (s.shadowClip > 0.45f) night += 0.25f
        if (s.p95 < 0.55f) night += 0.10f
        scores[SceneType.NIGHT] = night

        // 逆光：本质是大光比 —— 既有接近饱和的亮部，也有压得极深的暗部
        var backlit = 0f
        if (s.highlightClip > 0.03f) backlit += 0.25f
        if (s.p95 > 0.90f && s.p05 < 0.20f && s.dynamicRange > 0.60f) backlit += 0.50f
        if (s.centerBias < -0.08f) backlit += 0.10f
        if (s.meanLuma > 0.30f) backlit += 0.05f
        scores[SceneType.BACKLIT] = backlit

        // 人像：肤色占比是强特征
        var portrait = 0f
        if (s.skinRatio > 0.05f) portrait += 0.40f
        if (s.skinRatio > 0.10f) portrait += 0.35f
        if (abs(s.centerBias) < 0.12f) portrait += 0.10f
        if (s.saturation < 0.45f) portrait += 0.05f
        // 肤色占比过半时更可能是暖色墙面或木质桌面，而不是真的拍人
        if (s.skinRatio > 0.75f) portrait -= 0.30f
        scores[SceneType.PORTRAIT] = portrait

        // 风景：饱和度是必要条件，否则逆光片（大光比低饱和）会被误判成风景
        var landscape = 0f
        if (s.saturation > 0.32f) {
            landscape += 0.35f
            if (s.dynamicRange > 0.55f) landscape += 0.30f
        }
        if (s.edgeDensity in 0.05f..0.55f) landscape += 0.15f
        if (s.meanLuma in 0.30f..0.75f) landscape += 0.10f
        scores[SceneType.LANDSCAPE] = landscape

        // 室内：暖色偏是主特征；若 P95 > 0.90（有窗光/灯具直射）直接排除
        var indoor = 0f
        if (s.p95 <= 0.90f) {
            if (s.colorCastIndex > 0.15f) indoor += 0.40f
            if (s.colorCastIndex > 0.35f) indoor += 0.20f
            if (s.meanLuma in 0.16f..0.50f) indoor += 0.20f
            if (s.highlightClip < 0.03f) indoor += 0.10f
        }
        scores[SceneType.INDOOR] = indoor

        // 运动抓拍：高纹理 + 足够亮度（画面元素密集、细节多）
        var action = 0f
        if (s.edgeDensity > 0.40f) action += 0.35f
        if (s.meanLuma > 0.32f) action += 0.20f
        if (s.p50 in 0.30f..0.70f) action += 0.15f
        scores[SceneType.ACTION] = action

        scores[SceneType.GENERAL] = 0.30f

        var best = SceneType.GENERAL
        var bestScore = -1f
        var second = 0f
        for ((scene, score) in scores) {
            if (score > bestScore) {
                second = bestScore
                bestScore = score
                best = scene
            } else if (score > second) {
                second = score
            }
        }

        val total = bestScore + second
        val conf = if (total <= 0f) 0.35f else (bestScore / total).coerceIn(0.35f, 0.98f)
        return Result(best, conf)
    }
}
