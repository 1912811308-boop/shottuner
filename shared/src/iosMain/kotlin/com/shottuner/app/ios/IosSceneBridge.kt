// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 「融合场景判定」的跨语言门面。
//
// ★ 为什么需要它（这是四个门面里唯一「补设计缺口」而不是「补功能缺口」的一个）
//   `IosBridge.analyzeFrame` 的签名收的是 `sceneName: String` —— 这等于把「场景判定」
//   摆在了 Swift 侧。但判定规则不在 Swift 侧，在 commonMain 的 `SceneClassifier`
//   （规则 + 端侧 AI 的融合：人脸硬证据 > 打标置信度 > 规则分数）。
//   如果不补这个门面，Swift 只有两个选择：
//     a) 永远传 `"GENERAL"` —— 夜景 / 逆光 / 人像三套专用建议全部失效，
//        而失效是**静默**的（建议照样出，只是永远是通用版）；
//     b) 在 Swift 里再写一份判定规则 —— 那是第二份实现，
//        两份规则必然漂移，且漂移没人会发现（分数只是差几分，不像崩溃那样显眼）。
//   ⇒ 补一个只做「搬运」的门面，判定仍然只有 commonMain 一份实现。
//
// ★ 本文件**不含任何判定逻辑**，一个字都不许有
//   融合原则（人脸无条件覆盖、打标 ≥0.85 才改判、逆光例外）里每一个阈值
//   都是 `SceneClassifier` 的调参结论，改一份就必须重跑 samples/ 回归。
//   这里只做三件事：
//     ① 把 5 个标量拼成一个 `AiVisionResult`（`AiVisionResult` 有 10 个字段，
//        过不了跨语言边界，所以只能在这里拼）；
//     ② 把 `FrameStats` 从 `IosFrameCache` 里取出来；
//     ③ 把 `SceneClassifier.Result` 拍平成 [IosSceneDto]。
//   任何「顺手加个判断」的念头都在制造第二份实现。
//
// ★ 为什么 `AiVisionResult` 可以只填 4 个字段，而 `FrameStats` 不行
//   这是个容易看错的区别，写清楚：
//   · `FrameStats` 的 20 个字段**全是必填、没有默认值**。让 Swift 只填 6 个、
//     其余填 0，那 14 个 0 就是**伪造**（见 IosFrameCache.kt 文件头）。
//   · `AiVisionResult` 的 `faceCenterX` / `faceCenterY` / `faceBox` / `headEulerY`
//     **有声明默认值，而且默认值本身就是「无脸」语义**（0.5 / 0.5 / 全零框 / 0°）。
//     这里不显式传它们，用的正是声明默认值，不是「填了个假 0」。
//   · 更要紧的是：`SceneClassifier.classify` 只读 `ok` / `faceCount` / `faceArea` /
//     `scene` / `confidence` 这 5 个字段，**一次都不读** faceBox 那一组
//     （那是给机位教练 FramingCoach 用的）。
//   所以本文件只传这 5 个 + `tags = emptyList()`（必填），其余交给默认值。
//   ★ 判据：**「默认值 == 缺数据」才可以用默认值；「默认值 == 假数据」必须传真值。**
//
// ★ 越界 / 脏输入不抛异常（跨语言边界上抛异常最难定位）
//   · 还没算过任何一帧 → 返回全空 DTO（见 [IosSceneBridge.classify]）；
//   · `aiSceneName` 拼错 / 大小写不同 → 落到 `AiScene.UNKNOWN`，
//     `SceneClassifier` 对 UNKNOWN 的处理是「不改判、退回纯规则」（`else -> null`
//     → `?: return rule`），于是 `source` 会显示「规则」。**不会崩，也不会瞎判**；
//   · `aiConfidence` 传错数量级（例如传 85 而不是 0.85）**不会报错**，
//     但会被读成「置信度极高」，从而让 AI 打标覆盖规则结论。0..1 这个量级
//     只能由调用方守住 —— 见 [IosSceneBridge.classify] 的 @param 说明。

package com.shottuner.app.ios

import com.shottuner.app.analysis.AiScene
import com.shottuner.app.analysis.AiVisionResult
import com.shottuner.app.analysis.SceneClassifier

/**
 * 一次融合判定的跨语言结果 —— `SceneClassifier.Result` 的拍平版。
 *
 * | 字段 | Swift 落点 | 口径 |
 * |---|---|---|
 * | [sceneName] | 传给 `IosBridge.analyzeFrame(sceneName:)` | `SceneType` 枚举名，**大写**（`NIGHT` / `BACKLIT` / `PORTRAIT` / `LANDSCAPE` / `INDOOR` / `ACTION` / `GENERAL`）。空串 = 没有读数 |
 * | [confidence] | 置信度条 / 日志 | 0..1。空串状态下为 0 |
 * | [source] | 界面标注「凭什么这么判」 | 中文，`规则` / `AI·人脸` / `AI·标签`。空串 = 没有读数 |
 *
 * ## ★ 三个空串/0 的含义：**「我还不知道」，不是「通用场景」**
 * [sceneName] 为空串时，Swift 侧**必须保持当前场景不变**：
 *
 * ```
 * let v = IosSceneBridge.shared.classify(aiOk: ..., aiSceneName: "PORTRAIT", ...)
 * if !v.sceneName.isEmpty { currentScene = v.sceneName }
 * // 空串什么都别做 —— 不要写成 `currentScene = v.sceneName`（会清成空）
 * // 更不要 `v.sceneName.isEmpty ? "GENERAL" : v.sceneName`
 * ```
 *
 * 为什么不能兜底成 `GENERAL`：`GENERAL` 是一个**判定结论**（「我看过了，是通用场景」），
 * 而空串是「我还没看」。把后者显示成前者，等于替用户宣布一个我们并不知道的事实 ——
 * 本仓对这类「缺数据伪装成有数据」的写法已经打回过两次。
 * 典型触发时机：App 刚进相机页，`analyzeFrame` 还没返回过第一帧。
 *
 * ## ★ 为什么 [sceneName] 不是中文
 * `analyzeFrame` / `adviseRefresh` / `plan` 认的都是 `SceneType` 的**枚举名**，
 * 不是 `SceneType.label`（「夜景 / 弱光」这类中文显示名）。三处必须同一把钥匙，
 * 否则「判定给了夜景、建议按通用算」这种错会以「建议内容有点怪」的形式出现，
 * 极难定位。中文显示名由 UI 侧自己映射（Swift 已有的场景选择器就是按
 * `IosBridge.sceneNames()` 的枚举名建的）。
 */
class IosSceneDto(
    /** `SceneType` 枚举名（大写）。**空串 = 还没有读数，调用方不要改场景**。 */
    val sceneName: String,
    /** 判定置信度 0..1；空串状态下为 0。 */
    val confidence: Float,
    /** 判定来源（中文）：`规则` / `AI·人脸` / `AI·标签`；空串 = 还没有读数。 */
    val source: String
)

/**
 * 融合场景判定的 Swift 门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `IosSceneBridge.shared.classify(...)`，不是 `IosSceneBridge()`。
 *
 * 导出函数只有**一个**，签名唯一（无重载）—— Kotlin 靠参数类型区分重载，
 * ObjC 只能靠参数名，同名重载会被加上不可预测的尾随下划线。
 */
object IosSceneBridge {

    /**
     * 融合判定当前场景。统计量取自 [IosFrameCache.latest]（`IosBridge.analyzeFrame` 写的真值）。
     *
     * ## 一次调用、一个结论
     * 判定要用到 `meanLuma` / `p05` / `p95` / `shadowClip` / `highlightClip` / `skinRatio` /
     * `saturation` / `edgeDensity` / `centerBias` / `colorCastIndex` / `width` 共 11 个统计量，
     * 全部在 Kotlin 侧从缓存直读。Swift 一个都不用传，也就没有「传错顺序 / 漏传」的机会。
     *
     * ## 必须先有帧
     * [IosFrameCache.latest] 为 null（App 刚进相机页、`analyzeFrame` 还没返回过）时
     * 返回 `IosSceneDto("", 0f, "")` —— **不兜底成 `GENERAL`**，理由见 [IosSceneDto] 的 KDoc。
     * 调用方看到空串就别动当前场景，等下一次（10fps 的下一次只要 100ms）。
     *
     * ## aiOk == false 的语义
     * 传 `ai = null` 给 `SceneClassifier.classify`，**不是**传一个 `ok = false` 的
     * `AiVisionResult`。虽然 `classify` 内部写的是 `if (ai == null || !ai.ok) return rule`、
     * 两条路结果一样，但「这一帧没有 AI 结果」的本意就是 null；
     * 造一个 ok=false 的对象去依赖内部那个 `||` 分支，等于把实现细节当契约用。
     *
     * ## 主线程
     * 纯计算（无 IO、无锁、无状态），在 Swift 主线程调用即可。
     *
     * @param aiOk 这一帧 Vision 结果是否有效。**false 时**其余四个 AI 参数一律被忽略
     *   （可以是任何值，建议传 0 / ""）
     * @param aiSceneName Kotlin `AiScene` 的枚举名：`PORTRAIT` / `GROUP` / `LANDSCAPE` /
     *   `ARCHITECTURE` / `FOOD` / `PET` / `FLOWER` / `UNKNOWN`。
     *   解析时先 `trim()` 再 `uppercase()`，所以 `"portrait"` / `" Portrait "` 也能认；
     *   **认不出来的词**（包括中文显示名「人像」）一律落 `UNKNOWN`，
     *   此时本次判定退化成纯规则、`source` 显示「规则」（不崩、不瞎判）。
     *   注意：这不等于「AI 没结果」—— `UNKNOWN` 只是不改判，`faceCount ≥ 1`
     *   的人脸硬证据仍然生效
     * @param faceCount 画面里检测到的人脸数。`>= 1` 且 `faceArea >= 0.012` 时
     *   **无条件判定为人像**（融合原则第 1 条，唯一有硬证据的信号）
     * @param faceArea 最大人脸占画面面积比，**0..1**（不是百分比）
     * @param aiConfidence Vision 打标置信度，**0..1**（不是百分制）。
     *   ★ 传 85 不会报错，但会被读成「置信度极高」（阈值是 0.85），
     *   从而让 AI 打标覆盖规则结论 —— 数量级只能由调用方守住
     * @return 融合判定结果；还没算过任何一帧时返回全空 DTO（见上）
     */
    fun classify(
        aiOk: Boolean,
        aiSceneName: String,
        faceCount: Int,
        faceArea: Float,
        aiConfidence: Float
    ): IosSceneDto {
        // 没有帧就没有判定：不构造空 FrameStats 去骗分类器（那会被判成「全黑夜景」）。
        val stats = IosFrameCache.latest
            ?: return IosSceneDto(sceneName = "", confidence = 0f, source = "")

        val ai: AiVisionResult? = if (aiOk) {
            AiVisionResult(
                scene = aiSceneOf(aiSceneName),
                confidence = aiConfidence,
                faceCount = faceCount,
                faceArea = faceArea,
                // 必填字段，但 classify 不读它：tags 是「给用户看的中文标签」，
                // 由平台侧的 Vision 词表产出，与场景判定无关。给空列表是诚实的
                // （我们确实没有标签），不是伪造。
                tags = emptyList(),
                ok = true
                // 其余 4 个字段（faceCenterX / faceCenterY / faceBox / headEulerY）
                // 用声明默认值 = 「无脸」语义，classify 一次都不读。见文件头那段对比。
            )
        } else {
            // 本意就是「这一帧没有 AI 结果」，所以是 null，不是 ok=false 的对象。
            null
        }

        val result = SceneClassifier.classify(stats, ai)
        return IosSceneDto(
            // ★ 用 name 不是 label：label 是中文显示名，analyzeFrame 认的是枚举名。
            sceneName = result.scene.name,
            confidence = result.confidence,
            source = result.source
        )
    }

    /**
     * `AiScene` 枚举名（字符串）→ 枚举值，认不出来一律 `UNKNOWN`。
     *
     * ## ★ 为什么这里**不用手写 when**（与 `IosMissionBridge.phaseRawValueOf` 的取舍相反）
     * `IosMissionBridge` 那几个映射刻意不写 `else`，让 commonMain 新增枚举成员时
     * **编译失败**，逼着人回来看 iOS 侧。那种护栏在这里拿不到：
     * 本函数的输入是 **String**，编译器无法证明 `when` 穷尽，
     * 所以最后必须有 `else` —— 而一旦有了 `else`，`AiScene` 新增成员时
     * 这个手写 when **不会编译失败**，只会静默把新场景落到 `UNKNOWN`，
     * 表现为「AI 明明认出了 X，判定却退回纯规则」，没有任何报错。
     * ⇒ 改成从 `AiScene.entries` 派生：**新增成员自动被认出来，结构上不存在漂移**。
     *   代价是「枚举名即协议」这件事变成隐式约定，所以在这里用注释钉死：
     *   **Swift 侧传的必须是 Kotlin 枚举名（全大写）**，这也正是
     *   `IosBridge.sceneNames()` / `abortReasonNames()` 已经在用的同一套约定。
     *
     * 与 `sceneFromName`（SceneType 版，见 IosFrameCache.kt）保持同一种写法，
     * 两个门面对「名字 → 枚举」给出同一套规则：trim + uppercase + 认不出退到兜底值。
     *
     * ## 容错边界
     * - 大小写 / 首尾空格：`trim()` + `uppercase()` 兜住（Kotlin 的 `uppercase` 是
     *   locale 无关的，不会在土耳其语环境下把 `i` 变成 `İ`）；
     * - 中文显示名（`AiScene.label`，如「人像」）：**认不出来**，落到 `UNKNOWN`。
     *   这是刻意的 —— 标签与枚举名是两套词表，混用会让两块屏幕显示不同的场景；
     * - 空串：同样落到 `UNKNOWN`。
     */
    private fun aiSceneOf(aiSceneName: String): AiScene {
        val upper = aiSceneName.trim().uppercase()
        return AiScene.entries.firstOrNull { it.name == upper } ?: AiScene.UNKNOWN
    }
}
