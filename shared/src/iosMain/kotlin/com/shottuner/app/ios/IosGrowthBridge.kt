// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 「参考分历史 / 进步可见」的跨语言门面。
//
// ★ 为什么需要它
//   `ScoreHistory`（commonMain）已经写完、有单测、Android 侧在用，但 iOS 完全够不着 ——
//   于是 iOS 版「拍完就忘了」，没有第五张照片时的那种「你比第一张好了 14 分」。
//   这个文件只做搬运，不重写任何口径。
//
// ★ 三条硬口径（都是本仓被打回过的坑，别再踩）
//
// 1. **总分只由 `ShotRecord.of(...)` 算。**
//    四维里只要有一个未测，`of` 给出的 `total` 就是 null。门面**不自己加**四个数 ——
//    自己加会造出第二个总分口径，而成绩卡、成长摘要、折线图三处一旦各有一套加法，
//    它们之间的差异永远不会有人发现（三个数都"看起来合理"）。
//
// 2. **未测维度传 null，不传 0。**
//    `measured == false` → `null`。0 会被用户读成「这项 0 分」，而真实含义是
//    「这项没测到」。`recentSeries` 之类的统计对 null 与 0 的处理本就不同，
//    用 0 顶替等于把这些记录永久污染在磁盘上（`toJson` 会把 0 当真实值写出去，
//    下次读回来已经无法分辨）。
//
// 3. **`Int?` 不过跨语言边界。**
//    Kotlin 的 `Int?` 导出成 Swift 是 `KotlinInt?`，每次取值要 `.intValue` 拆包，
//    而且极易把 nil 当成 0 用 —— 那正是本仓最忌讳的「缺数据伪装成 0」。
//    ⇒ 每一维都拆成 `xxxAt(index): Int` + `xxxMeasuredAt(index): Boolean` 两个函数，
//      调用方必须先问「测了没」再取数。
//
// ★ 为什么用 `count()` + `xxxAt(index)` 而不是返回 `List<ShotRecord>`
//   跨语言铁律：`List<T>` 会桥接成 NSArray（元素装箱 + 拷贝），Swift 侧难用。
//   这里是纯「按下标取」的读模式，索引访问器最省事，也与
//   `TechniqueDexBridge.count() / titleAt(index)` 的既有做法一致。
//
// ★ 时间从哪来
//   `add` 的 `timestamp`、`recentCount` 的 `now` 全由 **Swift 传**。
//   iosMain 里不许出现 `System.currentTimeMillis()` / `Clock` 之类的本地时钟 ——
//   一旦两端各取各的时钟，「最近 7 天」在两端就会切出不同的窗口，
//   而这种差异只会在跨月/跨时区时偶发，永远查不出来。
//
// ★ 单线程约定
//   本门面所有函数都假定**在 Swift 主线程调用**（`ScoreHistoryModel` 是
//   ObservableObject / @MainActor）。`ScoreHistory` 实例本身是可变的（内部
//   MutableList），不做并发保护；`records` / `recent` 两个只读快照用 `@Volatile`
//   保证「整体替换引用」的可见性，但它们只是给读方用的，不是给多线程写用的。
//   真要后台写历史，请回到 Kotlin 侧加锁，不要在 Swift 里跨线程调。

package com.shottuner.app.ios

import com.shottuner.app.growth.ScoreHistory
import com.shottuner.app.growth.ShotRecord
import kotlin.concurrent.Volatile

/**
 * 成长摘要的跨语言形状 —— `ScoreHistory.firstVsBest()` 的拍平版。
 *
 * | 字段 | 口径 |
 * |---|---|
 * | [hasGrowth] | **有一份可直接显示的成长对比**时为 true。false → 四个数值全部不可显示 |
 * | [firstTotal] | 第一张（时间最旧）的总分 |
 * | [bestTotal] | 历史最好总分 |
 * | [delta] | `bestTotal - firstTotal` |
 * | [shotCount] | 记录条数（含未测记录），**这一项永远是真值** |
 *
 * ## ★ [hasGrowth] == false 时，四个数值**不是 0，是「没有」**
 * Swift 侧必须写成：
 *
 * ```
 * let g = IosGrowthBridge.shared.growth()
 * if g.hasGrowth { Text("首次 \(g.firstTotal) → 最佳 \(g.bestTotal)（+\(g.delta)）") }
 * else { Text("参考分 --") }        // 不许把 0 印出来
 * ```
 *
 * 「首次 0 分」和「首次没测到」在用户眼里是两件事：前者说明他拍废了，
 * 后者说明我们没测出来。把后者显示成 0，就是在冤枉用户 ——
 * 本仓已经因为「成绩卡印假分数」被打回过一次（见 `IosMissionDto.scoreMeasured`），
 * 这里是同一个坑的第二个入口。
 *
 * ## ★ 为什么 `hasGrowth` 的含义比「记录数 ≥ 2」更严
 *
 * `firstVsBest()` 在记录数 ≥ 2 时返回非 null，但它的 `firstTotal` / `bestTotal` /
 * `delta` **仍可能是 null**（首张四维不全 / 一条有分记录都没有）。
 * 而本 DTO 的四个数值字段是非空 `Int`（理由见文件头第 3 条：`Int?` 不许过边界）。
 * 于是「记录数够了但首张未测」这个组合下，只有两种写法：
 *   · 返回 `hasGrowth = true` + `firstTotal = 0` → **印出假分数**，禁止；
 *   · 返回 `hasGrowth = false` → 这次不显示成长卡，但一个假数字都不会出现。
 * ⇒ 选后者。`hasGrowth` 的语义因此是「**有一份可以直接显示的对比**」，
 *   而不是「记录数够了」。记录数本身随时可以用 [IosGrowthBridge.count] 取。
 *
 * 代价：首张恰好未测的用户，会看不到「最佳 82」这条信息（Android 侧可以显示
 * 「首次 -- / 最佳 82」）。若要补齐，正确做法是给本 DTO 增加
 * `firstTotalValid` / `bestTotalValid` / `deltaValid` 三个标志位 ——
 * 那是契约变更，需要与 Swift 侧同步改，不在本次授权范围内。
 */
class IosGrowthDto(
    /** 是否有一份可直接显示的成长对比。false → 下面四个数值不可显示。 */
    val hasGrowth: Boolean,
    /** 第一张的总分。[hasGrowth] == false 时为 0，**不是真实值，不可显示**。 */
    val firstTotal: Int,
    /** 历史最好总分。[hasGrowth] == false 时为 0，不可显示。 */
    val bestTotal: Int,
    /** `bestTotal - firstTotal`。[hasGrowth] == false 时为 0，不可显示。 */
    val delta: Int,
    /** 记录条数（含未测记录）。**永远是真值**，可以显示「已记 N 张」。 */
    val shotCount: Int
)

/**
 * 参考分历史的 Swift 门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `IosGrowthBridge.shared.count()`，不是 `IosGrowthBridge()`。
 *
 * 导出函数全部**签名唯一**（无重载）—— Kotlin 靠参数类型区分重载，
 * ObjC 只能靠参数名，同名重载会被加上不可预测的尾随下划线。
 */
object IosGrowthBridge {

    /**
     * 内存态历史。`load` 时整体替换（不是就地 merge）——
     * 「读盘 → 加记录 → 写回」三个动作里，读盘那一步是权威的。
     */
    @Volatile
    private var history: ScoreHistory = ScoreHistory.empty()

    /**
     * [history] 的只读快照（时间升序）。
     *
     * 为什么不每次 `history.all()`：`all()` 每次都 `toList()` 复制一遍（500 条），
     * 而 Swift 的列表要逐行取 10 个字段（timestamp + 4 维 × 2），
     * 一份 500 条的列表就是 5000 次复制。快照只在 `load` / `add` 之后重建一次。
     */
    @Volatile
    private var records: List<ShotRecord> = emptyList()

    /**
     * 「最近 N 天」窗口的只读快照，由最近一次 [recentCount] 填充。
     *
     * ★ 窗口由**最近一次 [recentCount] 的 `days` / `now` 决定**，
     *   所以 [recentTotalAt] 等访问器必须在同一轮渲染里紧跟 [recentCount] 之后调用。
     *   追加记录（[add]）之后要重新调 [recentCount]，否则读到的是上一轮的窗口 ——
     *   这是刻意的：宁可让 Swift 侧看到一份「上轮的一致快照」，
     *   也不要让 count 与各访问器在一次渲染中途错位（那会画出越界的折线点）。
     */
    @Volatile
    private var recent: List<ShotRecord> = emptyList()

    /**
     * 最近一次 [load] 的解析失败原因；空串 = 没失败过。
     *
     * 由 [lastLoadError] 对外暴露，字段本身不导出（`private`）。
     *
     * ★ 这是本文件唯一的「加法」导出（不在设计契约清单里），理由：
     *   `ScoreHistory.fromJson` 的既定行为是**任何异常都吞掉并返回空历史**
     *   （见那个函数的 KDoc：残缺历史比没有历史更危险）。Android 侧由调用方
     *   在 `onError` 回调里写日志，iOS 侧如果完全没有出口，
     *   用户的 300 条历史被一份坏 JSON 清空之后**一点痕迹都不留**，
     *   现场反馈「我的记录没了」将无从查起。
     *   本函数只给 Swift 写日志用，**不要拿它做 UI 判断或重试逻辑**。
     */
    @Volatile
    private var lastError: String = ""

    /**
     * 最近一次 [load] 的解析失败原因。
     *
     * @return 失败原因（可直接进日志）；从未失败过时为空串
     */
    fun lastLoadError(): String = lastError

    /**
     * 从 Swift 侧的 Application Support 读出的 JSON 装载历史。
     *
     * @param json `saveJson()` 写出去的原文；**null（或空串）表示「还没存过」**，
     *   等价于一份空历史。不要为此传 `"{}"` 之类的占位串。
     *
     * 解析失败时 common 侧已经把异常吞掉并返回空历史（口径见
     * `ScoreHistory.fromJson` 的 KDoc：坏一点就整体作废，因为**错的 delta 比没有
     * delta 更伤** —— 用户会照着它以为自己在进步）。失败原因记进
     * [lastLoadError]，**不抛异常**（跨语言边界上抛异常最难定位）。
     */
    fun load(json: String?) {
        lastError = ""
        val loaded = if (json.isNullOrBlank()) {
            ScoreHistory.empty()
        } else {
            ScoreHistory.fromJson(json, onError = { reason -> lastError = reason })
        }
        // 整体替换：不要往旧 history 里 merge，读盘结果就是权威。
        history = loaded
        records = loaded.all()
        // 旧窗口属于上一份历史，留着会指到不存在的记录上。
        recent = emptyList()
    }

    /**
     * 追加一条拍照记录。
     *
     * ★ `total` 由 `ShotRecord.of(...)` 在 common 侧算，本函数**不自己加四个数**：
     *   任一维未测 → `of` 给出 null，与 UI 口径「参考分 --」一致（见文件头第 1 条）。
     *
     * ★ [levelMeasured] 等为 false 时，对应分值被**忽略**（转成 null），
     *   所以即使 Swift 传了 0 进来也不会写进历史 —— 门面在这里兜住「假 0」。
     *
     * ★ 环形覆盖（超过 `ScoreHistory.MAX_RECORDS` 丢最旧一条）由 common 侧完成，
     *   这里不做任何裁剪。
     *
     * @param timestamp 拍摄时刻，**墙上时钟毫秒**（`Date().timeIntervalSince1970 * 1000`）。
     *   不要传单调时钟 —— 它没有纪元，和已存记录的时间戳不可比，排序与「最近 N 天」
     *   窗口会全部失效（而且失效得很安静：记录次序看起来还算合理）
     * @param level 水平（水平度）维度分值 0..100
     * @param levelMeasured false → 该维度按「未测」入库
     * @param exposure 曝光维度分值 0..100
     * @param exposureMeasured false → 该维度按「未测」入库
     * @param composition 构图维度分值 0..100
     * @param compositionMeasured false → 该维度按「未测」入库
     * @param clarity 清晰维度分值 0..100
     * @param clarityMeasured false → 该维度按「未测」入库
     */
    fun add(
        timestamp: Long,
        level: Int,
        levelMeasured: Boolean,
        exposure: Int,
        exposureMeasured: Boolean,
        composition: Int,
        compositionMeasured: Boolean,
        clarity: Int,
        clarityMeasured: Boolean
    ) {
        // ★ 未测 → null，不是 0。0 会被 JSON 当成真实分数永久写进磁盘。
        val record = ShotRecord.of(
            timestamp = timestamp,
            level = if (levelMeasured) level else null,
            exposure = if (exposureMeasured) exposure else null,
            composition = if (compositionMeasured) composition else null,
            clarity = if (clarityMeasured) clarity else null
        )
        history.add(record)
        records = history.all()
    }

    /**
     * 序列化成 JSON，供 Swift 落盘。
     *
     * ★ Swift 侧写到 **Application Support**（不参与 iCloud 备份）：
     *   分数历史是纯本地派生数据，重建成本是「重新拍一遍」，没有跨设备同步的价值，
     *   放进备份只会撑大用户的 iCloud 配额。
     *
     * 输出始终是合法 JSON（null 字段整体省略），空历史是 `{"version":1,"records":[]}`。
     */
    fun saveJson(): String = history.toJson()

    /** 当前记录条数（环形覆盖后 ≤ [maxRecords]）。 */
    fun count(): Int = records.size

    /**
     * 时间升序第 [index] 条记录的拍摄时刻（墙上时钟毫秒）。
     *
     * @param index 0..[count] - 1
     * @return 拍摄时刻；**下标越界时返回 0**（不是有效时间，可作为越界判断依据）
     */
    fun timestampAt(index: Int): Long = records.getOrNull(index)?.timestamp ?: 0L

    /**
     * 第 [index] 条的总分。
     *
     * @return 总分；**下标越界或该记录四维不全时返回 0** ——
     *   必须先看 [totalMeasuredAt] 再决定要不要显示这个 0
     */
    fun totalAt(index: Int): Int = records.getOrNull(index)?.total ?: 0

    /** 第 [index] 条的总分是否可用（四维齐全）。越界返回 false。 */
    fun totalMeasuredAt(index: Int): Boolean = records.getOrNull(index)?.total != null

    /** 第 [index] 条的水平度分值；未测或越界时为 0（先看 [levelMeasuredAt]）。 */
    fun levelAt(index: Int): Int = records.getOrNull(index)?.level ?: 0

    /** 第 [index] 条的水平度是否测到。越界返回 false。 */
    fun levelMeasuredAt(index: Int): Boolean = records.getOrNull(index)?.level != null

    /** 第 [index] 条的曝光分值；未测或越界时为 0（先看 [exposureMeasuredAt]）。 */
    fun exposureAt(index: Int): Int = records.getOrNull(index)?.exposure ?: 0

    /** 第 [index] 条的曝光是否测到。越界返回 false。 */
    fun exposureMeasuredAt(index: Int): Boolean = records.getOrNull(index)?.exposure != null

    /** 第 [index] 条的构图分值；未测或越界时为 0（先看 [compositionMeasuredAt]）。 */
    fun compositionAt(index: Int): Int = records.getOrNull(index)?.composition ?: 0

    /** 第 [index] 条的构图是否测到。越界返回 false。 */
    fun compositionMeasuredAt(index: Int): Boolean = records.getOrNull(index)?.composition != null

    /** 第 [index] 条的清晰度分值；未测或越界时为 0（先看 [clarityMeasuredAt]）。 */
    fun clarityAt(index: Int): Int = records.getOrNull(index)?.clarity ?: 0

    /** 第 [index] 条的清晰度是否测到。越界返回 false。 */
    fun clarityMeasuredAt(index: Int): Boolean = records.getOrNull(index)?.clarity != null

    /**
     * 成长摘要：第一张 vs 历史最好。
     *
     * [IosGrowthDto.hasGrowth] 为 false 时**四个数值全部不可显示**（详见 DTO 的 KDoc，
     * 那里写了为什么这个标志比「记录数 ≥ 2」更严）。
     */
    fun growth(): IosGrowthDto {
        val summary = history.firstVsBest()
        val delta = summary?.delta
        // delta == null 蕴含 firstTotal 或 bestTotal 为 null：此时没有任何一组数字
        // 既能填满四个字段又不造假，所以整张卡不显示（见 DTO KDoc 的两种写法对比）。
        if (summary == null || delta == null) {
            return IosGrowthDto(
                hasGrowth = false,
                firstTotal = 0,
                bestTotal = 0,
                delta = 0,
                // 条数永远是真值，与 count() 一致 —— Swift 仍可显示「已记 N 张」。
                shotCount = history.size()
            )
        }
        return IosGrowthDto(
            hasGrowth = true,
            // 走到这里 delta != null 已经蕴含两者非空，`?: 0` 只是为了从 Int? 拆包。
            firstTotal = summary.firstTotal ?: 0,
            bestTotal = summary.bestTotal ?: 0,
            delta = delta,
            shotCount = summary.shotCount
        )
    }

    /**
     * 重算并记住「最近 [days] 天」的记录窗口，返回条数（时间升序）。
     *
     * ★ 窗口筛选**只用 common 的 `ScoreHistory.recentSeries(days, now)`**，
     *   不在 iOS 侧另写一套「最近 N 天」的过滤 —— 两套实现必然在
     *   「窗口边界算不算」（`>=` 还是 `>`）、「天数上限」、「时区」上分叉，
     *   而分叉不会报错，只会让两端的折线图点数不一样。
     *   顺带得到 common 侧已有的两条护栏：`days` 被钳到 1..3650
     *   （上限是为了避免 `days * 一天毫秒` 溢出成负窗口），
     *   以及**不补点、不插值**（没拍照的那天就是没有，插值出来的点是假数据）。
     *
     * ★ `now` 必须是**墙上时钟毫秒**（`Date().timeIntervalSince1970 * 1000`），
     *   因为记录里的 `timestamp` 就是墙上时钟；两者必须同一把尺子。
     *   （注意与 `IosMissionBridge.tick(nowMs:)` **相反** —— 那边要单调时钟。
     *     这两处都用「毫秒」这个词，传错不会报错，只会让窗口静默失效。）
     *
     * ★ 结果同时缓存给 [recentTotalAt] / [recentTotalMeasuredAt] / [recentTimestampAt]，
     *   所以那三个访问器必须紧跟本函数之后调用（见 [recent] 字段的说明）。
     *
     * @param days 窗口天数（> 0；会被钳到 1..3650）
     * @param now 当前时刻，墙上时钟毫秒
     * @return 窗口内的记录条数
     */
    fun recentCount(days: Int, now: Long): Int {
        recent = history.recentSeries(days, now)
        return recent.size
    }

    /**
     * 「最近 N 天」窗口里第 [index] 条的总分。
     *
     * @return 总分；越界或该记录四维不全时返回 0（先看 [recentTotalMeasuredAt]）
     */
    fun recentTotalAt(index: Int): Int = recent.getOrNull(index)?.total ?: 0

    /**
     * 「最近 N 天」窗口里第 [index] 条的总分是否可用。
     *
     * ★ 折线图的正确画法是：`measured == false` 的点**断开**（不连线、不画点），
     *   而不是画成 0 —— 一条掉到 0 再弹回来的折线会被用户读成「那天我崩了」。
     *   与 `ScoreHistory.recentSeries` 的「不补点、不插值」是同一个口径。
     */
    fun recentTotalMeasuredAt(index: Int): Boolean = recent.getOrNull(index)?.total != null

    /** 「最近 N 天」窗口里第 [index] 条的拍摄时刻；越界返回 0（= 无效时间）。 */
    fun recentTimestampAt(index: Int): Long = recent.getOrNull(index)?.timestamp ?: 0L

    /**
     * 环形上限（common 的 `ScoreHistory.MAX_RECORDS` = 500）。
     *
     * 给 Swift 侧显示「已记 128 / 500」。**由 Kotlin 给而不是 Swift 写死**：
     * common 侧哪天把上限从 500 调成 300，Swift 写死的那个数字会悄悄错，
     * 表现为「记到 500 条之后界面还说没满」。与 `IosBridge.sceneNames()` 同一个理由。
     */
    fun maxRecords(): Int = ScoreHistory.MAX_RECORDS
}
