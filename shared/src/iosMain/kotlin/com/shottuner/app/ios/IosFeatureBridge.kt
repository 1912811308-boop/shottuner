// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 「参数 / 功能建议」的跨语言门面。
//
// ★ 为什么需要它
//   `FeatureAdvisor`（commonMain）Android 侧一直在用，iOS 侧完全够不着 ——
//   没有门面 = Swift 看不到。而「这会儿该开手持夜景还是该关掉 HDR」
//   正是这个产品叫「光影调参」的那一半理由，不能只有 Android 有。
//
// ★ 为什么不直接导出 `List<FeatureSuggestion>`
//   三条理由，任一条都足够：
//   1. `FeatureSuggestion.kind` 是 `enum class AdviceKind { DO, AVOID, KNOW }`。
//      跨语言之后是 `AdviceKind.do` —— **`do` 是 Swift 关键字**，调用处必须写
//      `` `do` `` 反引号，`switch` 里同样要反引号，任何人漏一个就是编译错误，
//      而错误信息会指着 Kotlin 生成的代码，排查成本极高。
//      ⇒ 枚举一律在 Kotlin 侧拍平成 String（[IosAdviceDto.kindText]）。
//   2. `List<FeatureSuggestion>` 会桥接成 NSArray，元素装箱 + 拷贝；
//      Swift 侧还得再写一层 map 才能拿去 ForEach。
//      ⇒ 用索引访问器（[IosFeatureBridge.adviseCount] + [IosFeatureBridge.adviseAt]），
//        与 `TechniqueDexBridge` / `PhotoBook` 那条既有做法一致。
//   3. 拍平之后 Swift 侧**完全不需要 import** `AdviceKind` 这个类型，
//      也就不会有人在 Swift 里重新对一遍 `"DO" -> 某个 Swift 枚举` 的映射表
//      （那个映射表一旦和 Kotlin 不同步，就是静默的「建议语气画错」）。
//
// ★ 为什么不把 `stats` 做成参数
//   见 `IosFrameCache` 文件头：`FrameStats` 20 个构造参数，让 Swift 只填 6 个、
//   其余填 0，就等于给将来埋 14 个静默假值。这里改读 [IosFrameCache.latest]。
//
// ★ 两个列表各自独立持有
//   Swift 侧很可能在同一个页面上先刷新「这台机器能玩什么」（首次打开），
//   再刷新「现在该开什么」（有画面之后），或者在切场景时先后刷新两者。
//   如果共用同一个「当前列表」字段，后刷新的那次会把前一次的结果覆盖掉，
//   而 UI 上表现为「刚刷出来的清单突然变成了另一批内容」，且不报任何错。
//   ⇒ [advice] 与 [capabilities] 两个字段互不相干。
//
// ★ 缓存字段的并发形态
//   `@Volatile` + 不可变 `List` 引用整体替换（与 `IosMissionBridge.cached` 同一个理由）：
//   写入方是 Swift 主线程，读方也是 Swift 主线程，但 Kotlin/Native 不做任何保证；
//   「整体替换引用」保证读到的要么是旧的完整列表、要么是新的完整列表，
//   不会是半个。绝不做 `list.add(...)` 这种就地修改。
//   注意 `listOf` / `map` 返回的都是只读视图，本文件从不往这两个字段里追加。
//
// ★ 文案口径：本文件**不产出任何自己拼的中文**
//   `title` / `reason` / `path` 全部是 `FeatureAdvisor` 原文搬运。
//   两端同一句话，改文案只改 commonMain 一处，不要在 Swift 里再拼一遍。

package com.shottuner.app.ios

import com.shottuner.app.device.DeviceProfile
import com.shottuner.app.feature.FeatureAdvisor
import com.shottuner.app.feature.FeatureSuggestion
import kotlin.concurrent.Volatile

/**
 * 一条功能建议的跨语言形状。
 *
 * | 字段 | Swift 落点 | 口径 |
 * |---|---|---|
 * | [kindText] | 卡片配色 / 图标 | `AdviceKind` 的枚举名：`DO` / `AVOID` / `KNOW`。**大写关键串** |
 * | [title] | 卡片主句 | 动作口吻，如「开手持夜景」 |
 * | [reason] | 卡片正文 | 为什么，带数据（如「平均亮度 21%，处于多帧合成最占优的区间…」） |
 * | [path] | 卡片次行 | 具体去哪儿拨，如「相机 → 更多 → 夜景」 |
 * | [priority] | 排序 / 弱化 | 0..100，越大越先说；列表已按它降序 |
 *
 * ★ [kindText] 为什么是 String 而不是枚举：见文件头第 1 条（`AdviceKind.do`
 *   在 Swift 里要反引号）。Swift 侧请用 `switch dto.kindText` 配字面量
 *   `"DO"` / `"AVOID"` / `"KNOW"`，**不要**自己再定义一套枚举去映射 ——
 *   Kotlin 侧新增语气时那个映射表会静默漏掉新值。
 *
 * ★ 为什么不加一个「是否高亮」之类的派生布尔：语气只有三种，
 *   高亮规则属于 UI 决策，放 Swift 侧；DTO 只搬 Kotlin 侧真的算出来的东西。
 */
class IosAdviceDto(
    /** `AdviceKind` 的枚举名：`DO` / `AVOID` / `KNOW`。 */
    val kindText: String,
    /** 卡片主句（动作口吻，≤12 字）。 */
    val title: String,
    /** 为什么（一句到两句，带可复算的数据）。 */
    val reason: String,
    /** 去哪儿拨（品牌路径或操作步骤）。 */
    val path: String,
    /** 0..100，越大越先说。 */
    val priority: Int
)

/**
 * 「现在该开什么功能」与「这台机器有什么能力」的 Swift 门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `IosFeatureBridge.shared.adviseCount()`，不是 `IosFeatureBridge()`。
 *
 * 导出函数全部**签名唯一**（无重载）—— Kotlin 靠参数类型区分重载，
 * ObjC 只能靠参数名，同名重载会被加上不可预测的尾随下划线。
 */
object IosFeatureBridge {

    /**
     * 最近一次 [adviseRefresh] 的结果（只读快照）。
     *
     * 初值是 `emptyList()` 而不是 null：Swift 侧 `adviseCount()` 在第一次刷新之前
     * 应当是 0 条（列表空 → 不显示卡片区），而不是「必须先判空再判 count」。
     * 这与 `IosMissionDto.IDLE` 的思路一致：**没有数据就是没有数据，不是未知**。
     */
    @Volatile
    private var advice: List<IosAdviceDto> = emptyList()

    /**
     * 最近一次 [capabilityRefresh] 的结果（只读快照）。与 [advice] 互不相干，见文件头。
     */
    @Volatile
    private var capabilities: List<IosAdviceDto> = emptyList()

    /**
     * 重算「当前该开什么功能」建议。
     *
     * 数据来源与 Android 完全一致：`FeatureAdvisor.advise(profile, stats, scene, needsTripod)`，
     * 其中 `stats` 取 [IosFrameCache.latest]（`IosBridge.analyzeFrame` 写的那份真值）。
     *
     * ## 还没有画面时会发生什么（**这是合法状态，调用方不用拦**）
     * 缓存为 null 时 `advise` 内部的 `stats?.meanLuma ?: 0.45f` 会取 0.45 这个
     * 「不知道就当中等亮度」的默认值，于是弱光 / 高溢出 / 高光比 / 人像这几条
     * 依赖画面特征的规则都不会触发，剩下的是「设备事实」类建议
     * （4K60、慢动作上限、有没有专业模式）。**这正是首次打开该显示的内容。**
     * 不要为了「先给一屏东西」去传一份 `FrameStats.EMPTY` ——
     * 那会把「没有画面」读成「画面全黑」，直接蹦出「开手持夜景」。
     *
     * ## needsTripod
     * 由 Swift 侧从参数建议里搬（`Recommendation.needsTripod`）。为 true 时
     * `FeatureAdvisor` 会补一条「按快门别用手指戳」。**不知道就传 false**：
     * 传 true 会在不需要三脚架的场合多出一条用不上的建议。
     *
     * ## 主线程
     * 本函数只做纯计算（无 IO、无锁），在 Swift 主线程调用即可，不需要切队列。
     *
     * @param profile 设备档案（Swift 侧 `CameraSession.currentDeviceProfile()`）
     * @param sceneName `SceneType` 的枚举名（`NIGHT` / `BACKLIT` / `PORTRAIT` /
     *   `LANDSCAPE` / `INDOOR` / `ACTION` / `GENERAL`）；未知 / 空串按 `GENERAL`，
     *   与 `IosBridge.analyzeFrame` 口径逐字一致（见 `sceneFromName`）。**区分大小写**
     * @param needsTripod 当前参数建议是否需要三脚架
     */
    fun adviseRefresh(profile: DeviceProfile, sceneName: String, needsTripod: Boolean) {
        val list = FeatureAdvisor.advise(
            profile = profile,
            // null 是合法的：表示还没有任何一帧被分析过（见函数的「还没有画面」一节）。
            stats = IosFrameCache.latest,
            scene = sceneFromName(sceneName),
            needsTripod = needsTripod
        )
        // 先整体构造完，再一次性替换引用 —— 不要就地往 advice 里 add。
        advice = list.map { it.toDto() }
    }

    /**
     * 当前建议条数。返回 0 表示「这次没给出任何建议」（例如画面正常得没得挑），
     * **不是**「还没刷新」—— 尚未刷新与刷新后为空在 UI 上应当表现一致：不显示卡片区。
     */
    fun adviseCount(): Int = advice.size

    /**
     * 时间戳升序第 [index] 条建议。
     *
     * @param index 0..[adviseCount] - 1
     * @return 对应的 DTO；**越界返回 null**（不抛异常：跨语言边界上抛异常最难定位，
     *   一个越界的列表下标不该让整页崩掉）
     */
    fun adviseAt(index: Int): IosAdviceDto? = advice.getOrNull(index)

    /**
     * 重算「这台机器能玩什么」清单（`FeatureAdvisor.capabilitySheet`）。
     *
     * 与 [adviseRefresh] 的区别：**不吃 `FrameStats`**，纯设备事实。
     * 用于首次打开 / 换设备 / 相机权限刚授好时给一屏有内容的东西。
     *
     * 结果写进与 [advise] 无关的 [capabilities]，两次刷新可以交替进行。见文件头
     * 「两个列表各自独立持有」。
     *
     * @param profile 设备档案
     */
    fun capabilityRefresh(profile: DeviceProfile) {
        capabilities = FeatureAdvisor.capabilitySheet(profile).map { it.toDto() }
    }

    /** 能力清单条数；0 表示还没刷新过。 */
    fun capabilityCount(): Int = capabilities.size

    /**
     * 第 [index] 条能力说明。
     *
     * @param index 0..[capabilityCount] - 1
     * @return 对应的 DTO；越界返回 null（理由同 [adviseAt]）
     */
    fun capabilityAt(index: Int): IosAdviceDto? = capabilities.getOrNull(index)

    /**
     * `FeatureSuggestion`（commonMain）→ 扁平 DTO。
     *
     * ★ `kind.name` 是 `DO` / `AVOID` / `KNOW` 三个**大写枚举名**，
     *   直接当 `kindText` 用。刻意**不写 else / 不做 when 映射**：
     *   换个映射表就多一处可能和 commonMain 不同步的地方，
     *   而枚举名本身就是 Kotlin 侧唯一权威的标识（Swift 侧只把它当不透明字符串比较）。
     */
    private fun FeatureSuggestion.toDto(): IosAdviceDto = IosAdviceDto(
        kindText = kind.name,
        title = title,
        reason = reason,
        path = path,
        priority = priority
    )
}
