package com.shottuner.app.knowledge

import com.shottuner.app.model.SceneType

/**
 * iOS 侧的**兜底提示**：`ScoreBoard` 没有建议时，从技法库按场景轮换拿一条。
 *
 * ## 为什么需要它（1.3.4 遗留的最后一公里）
 *
 * Android 的提示链路是「`PromptEngine` 11 条规则 + R10 兜底轮播」，1.3.4 已经把 R10
 * 的池子改成「精选两条 + 技法库同场景候选，按 48 秒窗口轮换」，于是 15 条新技巧
 * 在 Android 上真的会被推出来。
 *
 * iOS 走的是另一条路：`IosBridge.analyzeFrame` 只取
 * `scoreResult.recommendations.firstOrNull()` —— 那是 `ScoreBoard` **按分数维度**
 * 给的建议。它的覆盖面等于「四个维度里哪些没拿满」，画面没毛病时一条都没有。
 * 结果：15 条新技巧上了 iOS 的图鉴（98 条），但取景时一句都不会出现。
 *
 * 这个对象补的就是那一段：**`ScoreBoard` 没话说时，从同一个技法池里轮一条**。
 *
 * ## 三条铁律（改这里的代码之前先读）
 *
 * 1. **池子只有一份**：直接用 [PromptEngine.fallbackPool]，绝不另写一套。
 *    两套池子必然分叉 —— 分叉的表现是「Android 教 A、iOS 教 B」，而两边都"看起来正常"，
 *    没人会发现。窗口步进（[PromptEngine.FALLBACK_ROTATE_MS]）也同理，共用同一个常量。
 * 2. **精选话术不上 iOS**：池子的前两位是 R10 的「保底话术」（`白加黑减` / `先保高光` 那几句），
 *    它们是 Android 在"连一条库内候选都没轮到"时垫底用的。iOS 已经有 `ScoreBoard`
 *    的分数建议，不该退化成那几句泛泛的口诀，所以取之前把整张精选表剔掉。
 * 3. **相位要稳**：同一个 48 秒窗口内多次调用必须返回**同一条**。
 *    取景浮层每秒重算一次，若每 tick 换一句，用户看到的是文字在抖 —— 那比没有提示更糟。
 *    所以索引按 `nowMs / FALLBACK_ROTATE_MS`（窗口序号）取，而不是按 `nowMs` 本身取。
 *
 * ## 纯函数
 *
 * 不 import 任何平台 API、不读时钟：`nowMs` 由调用方注入，同输入必得同输出。
 * 这样 `commonTest` 能逐条钉住轮换行为，两端也跑同一份实现。
 */
object TechniqueTipFallback {

    /**
     * 取这一拍该显示的技法 id。
     *
     * 命中规则（全部是确定性的，没有随机、没有哈希序）：
     * 1. `pool = PromptEngine.fallbackPool(scene, nowMs)` —— 与 Android R10 同一个池子；
     * 2. 剔掉精选话术（[PromptEngine.isCuratedFallbackText]），只留技法库条目；
     * 3. 下标 = `(nowMs / FALLBACK_ROTATE_MS) mod pool.size` —— 窗口序号对池长取模。
     *
     * 第 3 步的两个性质正是这个函数存在的理由：
     * - **同一窗口内恒定**：`nowMs / FALLBACK_ROTATE_MS` 在窗口内是常数，100 次调用同一个 id；
     * - **跨窗口必换**：窗口序号 +1，池子本身也整体滑一格（[PromptEngine.FALLBACK_ROTATE_MS]
     *   与池子的滑动周期是同一个常量），下标与池子同时前进，相邻两窗不会撞在同一条。
     *
     * @param scene 当前场景（与 Android 同源的 [SceneType]，见 `SceneClassifier`）
     * @param nowMs 当前时刻（毫秒，单调时钟即可；负数也安全，[Long.mod] 是 floorMod）
     * @return 命中的技法 id；池子里一条技法都没有（只有精选话术）时返回 null，
     *   **调用方保持空串**，绝不编一条出来
     */
    fun pick(scene: SceneType, nowMs: Long): String? {
        val pool = PromptEngine.fallbackPool(scene, nowMs)
            .filterNot { PromptEngine.isCuratedFallbackText(it) }
        if (pool.isEmpty()) return null

        val windowIndex = nowMs / PromptEngine.FALLBACK_ROTATE_MS
        // Long.mod 是 floorMod：nowMs 为负时下标仍然落在 0 until size，不会抛越界。
        return pool[windowIndex.mod(pool.size.toLong()).toInt()]
    }

    /**
     * 技法 id → 卡片主文案（该条技法自己的 `oneLiner` 口诀）。
     *
     * 复用 [PromptEngine.fallbackText]，**不在这里再写一份 id → 文案的映射**：
     * 那份映射已经包含「精选表优先、其次技法库 `oneLiner`」的取舍，重复实现必然漂移。
     *
     * @param id [pick] 返回的技法 id
     * @return 口诀文案；解析不出来时返回空串，调用方**必须保持空卡片**
     *   （宁可不说话，也不推一张没有动作的空卡片）
     */
    fun text(id: String): String = PromptEngine.fallbackText(id)
}
