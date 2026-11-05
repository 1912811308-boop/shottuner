// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 三个新门面共用的「最近一帧统计量」缓存 + 场景名解析。
//
// ★ 为什么需要这个缓存，而不是让 Swift 把统计量传进来
//
//   `FeatureAdvisor.advise` 与 `VideoRecommender.plan` 的入参都是 `FrameStats?`，
//   而 `FrameStats` 有 20 个必填构造参数（histogram / meanLuma / p05 / p50 / p95 /
//   highlightClip / shadowClip / meanR / meanG / meanB / saturation / centerLuma /
//   edgeDensity / skinRatio / width / height，另有 4 个带默认值的网格字段）。
//
//   看上去「只用到 meanLuma / p05 / p95 / highlightClip / skinRatio / edgeDensity
//   这 6 个」，于是很自然会产生一个方案：让 Swift 只传这 6 个，其余 14 个填 0。
//   **那个方案是错的。** 其余 14 个不是「没用」，而是「今天没人读」。
//   将来任何人在这两条规则里多读一个字段（比如拿 `centerLuma` 判断主体是否欠曝、
//   拿 `saturation` 判断是不是阴天、拿 `dynamicRange` 替代手算的 p95-p05），
//   读到的都是**凭空造出来的 0**，而且完全静默 —— 不报错、不崩溃，
//   只是建议内容变得莫名其妙，且没有第二处可供对账。
//   本仓已经因为「缺数据伪装成 0」被打回过两次（成绩卡印假分数、hasTotal 口径），
//   不允许造出第三个。
//
//   ⇒ 正确做法：全仓唯一的 `FrameStats` **真实生产点**是 `IosBridge.analyzeFrame`，
//     把那个算出来的真实例在函数末尾存下来，新门面直接读缓存。
//     Swift 一个统计量都不用传，也就没有「填 0 还是填 null」这个必错的选择题。
//
// ★ 谁写、谁读
//   · 写：只有 `IosBridge.analyzeFrame` 一处（全仓仅此一处写 [IosFrameCache.latest]）。
//   · 读：`IosFeatureBridge`（参数建议）与 `IosVideoBridge`（录像方案）。
//
// ★ 线程模型与 `IosMissionBridge` 里那个 `cached` 同构
//   写入方是取景分析所在的串行后台队列（10fps），读方是 Swift 主线程
//   （用户切页 / 点「刷新建议」按钮时）。Kotlin/Native 的新内存模型允许跨线程
//   共享可变状态，但**不给可见性保证**：后台线程写好一个引用、主线程可能读不到，
//   或者只看到自己 CPU 缓存里的旧值。
//   ⇒ 用 `@Volatile` 做「单次引用发布」：`FrameStats` 全字段 `val`、构造完不再改写
//     （`FrameStatsCore.compute` 每次返回新数组，不复用输入），读方看到的要么是
//     某一整帧的统计量、要么是 null，不会是「一半新一半旧」。
//     这个前提必须保持 —— 谁哪天给 `FrameStats` 加了个 `var` 字段，
//     这里就不再安全。
//
// ★ 为什么不复用、也不合并 `IosMissionBridge` 里的 `cached`
//   那个 `cached` 是 T14 落地时的产物（当时还没有这个共享缓存），装的是
//   `FrameStats` + `CompositionHint` + recs + total + allMeasured 五件套，
//   专门服务 400ms tick 的任务状态机。把它拆开 / 合并要走一遍
//   「已经过两轮审计的代码」，收益只是省一个对象引用，风险与收益完全不对称。
//   ⇒ 这里是**第二处、已知的、有意保留的**少量重复：同一帧被两个模块各持一个引用
//     （而不是各算一遍）。两个引用都是只读的，彼此没有同步关系，
//     重复的代价是 8 字节指针。
//
// ★ 为什么不放进 commonMain
//   Android 侧压根不需要缓存：`MainActivity` 手里一直握着最新那次分析的产物，
//   直接调 `FeatureAdvisor.advise(...)` / `VideoRecommender.plan(...)` 即可，
//   多一层缓存只会多一个「什么时候该失效」的问题。
//   这个缓存的唯一理由是「Swift 拿不到 Kotlin 的对象、也传不进 20 个构造参数」，
//   属于纯平台适配，所以留在 iosMain。

package com.shottuner.app.ios

import com.shottuner.app.model.FrameStats
import com.shottuner.app.model.SceneType
import kotlin.concurrent.Volatile

/**
 * 最近一帧的取景统计量 —— **全仓唯一的 `FrameStats` 跨语言出口**。
 *
 * `internal`（不进 ObjC 头文件）：这是 Kotlin 内部的接力棒，
 * Swift 侧既不该也不能调，它只需要调 `IosFeatureBridge` / `IosVideoBridge`。
 *
 * 为什么用 `object` 而不是把引用塞进某个门面：写方（`IosBridge`）与读方
 * （`IosFeatureBridge` / `IosVideoBridge`）分属不同文件，谁持有都不合适；
 * 单独一个 `object` 让「只有一个写点」这件事在代码结构上就看得见。
 */
internal object IosFrameCache {

    /**
     * 最近一次成功分析的统计量；`null` = 还没分析过任何一帧。
     *
     * ★ `null` 必须是合法状态，不能用 `FrameStats.EMPTY` 顶替：
     *   `FeatureAdvisor.advise(stats = null)` 与 `advise(stats = EMPTY)` 走的**不是**
     *   同一条分支 —— 前者用 `?: 0.45f` 的默认亮度，后者老老实实读 0。
     *   拿 EMPTY 冒充「还没有画面」，会让第一次翻到建议页时看到一堆
     *   「画面平均亮度 0%，开手持夜景」的结论，而那帧根本不存在。
     */
    @Volatile
    var latest: FrameStats? = null
}

/**
 * 场景名 → 场景枚举，无法识别时退到 [SceneType.GENERAL]。
 *
 * ★ 与 `IosBridge.analyzeFrame` 里那一行的口径**逐字一致**
 *   （`SceneType.entries.firstOrNull { it.name == sceneName } ?: SceneType.GENERAL`）：
 *   区分大小写、不做 trim、未知串一律 GENERAL、**不抛异常**
 *   （跨语言边界上抛异常最难定位，一个拼错的字符串不该让整页崩掉）。
 *   门面之间对同一个 `sceneName` 必须给出同一个场景，否则用户在同一页面上
 *   会看到「建议说夜景、录像方案说通用」，而两边都不认为自己错。
 *
 * ★ 为什么不把 `IosBridge.analyzeFrame` 里那行改成调用本函数
 *   `IosBridge.kt` 是已经被两轮审计过的文件，本次任务对它的授权只有「加一行写缓存」。
 *   为了消除一行重复去动一个审计过的文件，风险不对称。
 *   代价是这段解析逻辑存在两处（这里 + IosBridge 内联）——
 *   两处都是**完全同一行代码**，且都不含可调参数，走样的概率极低；
 *   真正的护栏是「口径只有 GENERAL 和『名字精确匹配』两条」。
 *
 * @param sceneName `SceneType` 的枚举名（`NIGHT` / `BACKLIT` / `PORTRAIT` /
 *   `LANDSCAPE` / `INDOOR` / `ACTION` / `GENERAL`），空串 / null 之外的任何未知值
 *   都会落到 `GENERAL`
 * @return 解析出的场景，永不返回 null
 */
internal fun sceneFromName(sceneName: String): SceneType =
    SceneType.entries.firstOrNull { it.name == sceneName } ?: SceneType.GENERAL
