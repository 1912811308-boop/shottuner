// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 技法图鉴的跨语言门面。
//
// ★ 为什么单独一个文件，而不是塞进 IosBridge.kt：
//   IosBridge.kt 是「内核自检 + 数值格式化」的门面，另有成员正在往里加
//   跨端对齐用的 dump 函数。图鉴是**浏览型内容**，与那些无关，单开一个文件
//   既能避免并发改动撞车，也让导出面按用途分组。
//
// ★ 为什么不直接把 `PhotographyBook.all`（List<Technique>）导出给 Swift：
//   这是工程里已写死的跨语言规则（见 IosBridge.kt / SharedKernelBridge.swift 的注释）：
//     **跨语言边界只传标量，不传数组**。
//   Kotlin 的 `List<T>` 会桥接成 NSArray，元素装箱 + 拷贝，性能差且 Swift 侧难用；
//   而 `Technique` 里还嵌套着 `List<String>`、`List<Pair<...>>`，跨过去更麻烦。
//   ⇒ 这里把每条技巧**在 Kotlin 侧拍平成字符串**，Swift 只负责渲染。
//
// ★ 口径铁律：文案只给动作，不说「正确/错误」「专业评测」；参数用「照拨」「建议值」。
//   技巧库本身的 `oneLiner` / `steps` 就是动作式口诀，这里只做搬运和排版，不改内容。
//
// ★ 解锁进度（G3）：本文件下半段那几个 `idAt` / `unlockedCount` /
//   `isUnlockedAt` / `pickUnlockTargetByDimension` 是给 iOS 图鉴首页补「已解锁 N / 83」
//   用的。**判定一行都不在这儿写** —— 阈值与「每天最多一条」全在 common 的
//   `shouldUnlock` / `DexThresholds`，Android 的 `DexStore` 调的也是同一个函数。
//   iOS 侧若另写一份「分数够就解锁」，两端当天就会分叉，而且分叉不报错 ——
//   只会让同一张照片在两端解锁不同的条目（这类 bug 只有用户会发现）。

package com.shottuner.app.ios

import com.shottuner.app.analysis.ScoreBoard
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.growth.TechniqueDex
import com.shottuner.app.growth.shouldUnlock
import com.shottuner.app.knowledge.PhotographyBook
import com.shottuner.app.knowledge.Technique

/**
 * 技法图鉴在 Swift 侧的门面。
 *
 * Kotlin 的 `object` 在 Swift 里是**类属性单例** → 调用形态是
 * `TechniqueDexBridge.shared.count()`，不是 `TechniqueDexBridge()`。
 *
 * 导出函数全部**签名唯一**（无重载）—— Kotlin 靠参数类型区分重载，
 * ObjC 只能靠参数名，同名重载会被加上不可预测的尾随下划线。
 */
object TechniqueDexBridge {

    /** 图鉴条目总数（Android 与 iOS 读的是同一份 commonMain 数据）。 */
    fun count(): Int = PhotographyBook.all.size

    /** 第 [index] 条的短标题。 */
    fun titleAt(index: Int): String = at(index).title

    /** 第 [index] 条的分类名（中文，如「人像」「用光」）。 */
    fun categoryLabelAt(index: Int): String = at(index).category.label

    /** 第 [index] 条的一句话口诀。 */
    fun oneLinerAt(index: Int): String = at(index).oneLiner

    /**
     * 第 [index] 条的**完整详情**，已在 Kotlin 侧拼成可直接显示的纯文本。
     *
     * ★ 为什么详情也走字符串而不是结构化字段：
     *   `steps` / `params` / `mistakes` 都是 List，逐个导出会让 Swift 侧
     *   写一堆胶水代码；而图鉴是**只读浏览**，没有交互逻辑，拼好文本最省事，
     *   也保证两端的排版口径一致（同一份 Kotlin 代码）。
     */
    fun detailAt(index: Int): String = buildString {
        val t = at(index)
        append("【${t.category.label}】${t.title}\n\n")
        append("口诀：${t.oneLiner}\n\n")
        append("什么时候用\n${t.whenTo}\n\n")
        if (t.steps.isNotEmpty()) {
            append("怎么做\n")
            t.steps.forEachIndexed { i, step -> append("${i + 1}. ${step}\n") }
            append("\n")
        }
        if (t.params.isNotEmpty()) {
            append("建议值（自己照拨）\n")
            t.params.forEach { (k, v) -> append("· ${k}：${v}\n") }
            append("\n")
        }
        append("为什么有效\n${t.why}\n\n")
        if (t.mistakes.isNotEmpty()) {
            append("容易踩的坑\n")
            t.mistakes.forEach { append("· ${it}\n") }
            append("\n")
        }
        append("拍完怎么判断\n${t.check}")
    }

    /** 全部条目的分类分组数（给 Swift 侧做分区用）。 */
    fun categoryCount(): Int =
        PhotographyBook.all.map { it.category.label }.distinct().size

    // ── 解锁进度（G3）────────────────────────────────────────────────────────
    //
    // ★ 这一段的定位：`shouldUnlock` 的**薄壳**。
    //   下面每个函数都只做「拆参数 → 调 common → 把结果拍成标量」，任何一条阈值、
    //   任何一个「每天最多一条」的判断都不在这里重写。
    //
    // ★ 为什么「已解锁集合」用逗号拼接的字符串过边界：
    //   `Set<String>` 会桥接成 NSSet、`List<String>` 成 NSArray —— 与文件头那条
    //   「跨语言边界只传标量」直接冲突（元素装箱 + 拷贝，Swift 侧还得拆）。
    //   图鉴 id 全部是小写字母 + 下划线（取自 `PhotographyBook` / `TechniqueBookV15`），
    //   **不含逗号**，所以逗号分隔是无损的。
    //   ★ 万一将来真出现带逗号的 id：这里会把它切成两条对不上的 id ——
    //   表现为「不计数」，而不是「解锁到别的条目上」。宁可少算，不可错算。
    //
    // ★ 为什么不收 dimMax：
    //   各维满分由 `ScoreBoard.MAX_LEVEL / MAX_EXPOSURE / MAX_COMPOSITION / MAX_CLARITY`
    //   给，那是唯一真值来源（Android 的 `MainActivity.unlockDexByDimension` 传的
    //   也是这四个常量）。Swift 手上那份 `DimensionSnapshot.max` 本身就是从 Kotlin
    //   抄过去的，再传回来等于给「抄错」开一条没有告警的通道。
    //
    // ★ 为什么日期由 Swift 传：
    //   iosMain 里不许出现本地时钟/getCurrentTimeMillis，两端各取各的时钟会让
    //   「每天最多一条」在跨时区时切出不同的日期（与 `IosGrowthBridge` 文件头
    //   那条「时间从 Swift 传」同一个理由）。格式沿用 Android 的 `yyyyMMdd`
    //   （见 `DexClock.PATTERN`），因为它是**存储键的口径**，两端必须一致。

    /** 第 [index] 条的 id。Swift 侧拿它当行的稳定身份，解锁状态也按它记。 */
    fun idAt(index: Int): String = at(index).id

    /**
     * 这份「已解锁 id 串」里，**真正存在于图鉴**的条数。
     *
     * 与 Android 的 `DexStore.unlockedCount` 同口径：存储里可能残留旧版本的 id
     * （某条技法改过名），不跟图鉴求交会让首页出现「85 / 83」这种不成立的分数。
     * 重复的 id 只算一次（`HashSet` 去重）。
     *
     * @param joinedUnlockedIds 逗号分隔的 id 串；空串 = 一条都没解锁
     * @return 0..[count]
     */
    fun unlockedCount(joinedUnlockedIds: String): Int {
        val ids = splitIds(joinedUnlockedIds)
        if (ids.isEmpty()) return 0
        var unlocked = 0
        for (id in ids) {
            if (TechniqueDex.byId(id) != null) unlocked++
        }
        return unlocked
    }

    /**
     * 第 [index] 条是否已解锁。
     *
     * @param index 0..[count] - 1；**越界会抛**（与 `titleAt` / `detailAt` 同口径，
     *   它们都走 `at()` 的 `require`）。Swift 侧按下标遍历图鉴时天然在范围内。
     * @param joinedUnlockedIds 逗号分隔的已解锁 id 串
     */
    fun isUnlockedAt(index: Int, joinedUnlockedIds: String): Boolean {
        val id = at(index).id
        return splitIds(joinedUnlockedIds).contains(id)
    }

    /**
     * 维度通道解锁：按「本次这张照片的四维参考分」，挑出该解锁哪一条技法。
     *
     * ## 与 Android 的一致点（逐条对齐，别各改各的）
     * - 通道相同：Android 走的是 `DexStore.tryUnlockByDimension`（G3-B 之后
     *   `MainActivity:2227` 调的就是它），**不是** tipId 通道。
     *   `DexStore.tryUnlock`（tipId 那条）目前在 Android 侧没有任何调用点 ——
     *   所以 iOS 也不接它，免得两端解锁口径不一样。
     * - 维度顺序相同：水平 → 曝光 → 构图 → 清晰，见 `MainActivity.unlockDexByDimension`。
     *   多维度同时达标时只解锁**第一个**命中的，一天最多一条由 common 把关。
     * - 选条目的规则相同：`TechniqueDex.nextUnlockable`（该维度下第一条尚未解锁的，
     *   顺序 = 知识库原顺序），所以「先解锁哪条、再解锁哪条」是可预测的。
     *
     * ## 未测的维度传什么
     * 传 `-1`。这是 common 的既有约定（`shouldUnlock` 的 `dimScore` 参数：
     * `dimScore < 0` = 未测），`ScoreBoard` 也用它。**绝不要传 0** ——
     * 0 会被当成「测出来是 0 分」，那是本仓被打回过的「缺数据伪装成 0」。
     *
     * ## 文件头那条「7 条上限」说的不是这条通道
     * `TechniqueDex` 文件头写「当前只有 7 条可解锁」，指的是 **tipId 通道**
     * （`ScoreBoard` 只产得出 7 个 tipId）。维度通道的池子是 `DIMENSION_OF` 里有归属的
     * 64 条（83 减去自拍 5 / 运镜 7 / 参数 3 / 避坑 4 这 19 条 null 维度）。
     * 也就是说首页那个分母仍然是 83，但今天**走得通**的上限是 64 —— UI 侧
     * 别写「已解锁 n / 64」之类，分母按 `count()` 给。
     *
     * @param levelScore 水平维度本次参考分；`< 0` = 未测
     * @param exposureScore 曝光维度本次参考分；`< 0` = 未测
     * @param compositionScore 构图维度本次参考分；`< 0` = 未测
     * @param clarityScore 清晰维度本次参考分；`< 0` = 未测
     * @param joinedUnlockedIds 逗号分隔的已解锁 id 串（存储里可能含非法 id，这里会滤掉）
     * @param lastUnlockDate 上次解锁的日期（`yyyyMMdd`）；从未解锁过传空串
     * @param today 今天的日期（`yyyyMMdd`）；空串 = 拿不到日期，本次不解锁
     * @return 该解锁的技法 id；不该解锁（都不达标 / 已全部解锁 / 今天已解锁过 /
     *   拿不到日期）时返回**空串**（不用 null：`String?` 过桥会多一层拆包，
     *   与本仓「缺数据不用可空量」的口径一致）
     */
    fun pickUnlockTargetByDimension(
        levelScore: Int,
        exposureScore: Int,
        compositionScore: Int,
        clarityScore: Int,
        joinedUnlockedIds: String,
        lastUnlockDate: String,
        today: String
    ): String {
        val unlocked = validIds(joinedUnlockedIds)
        // 顺序与 Android 的 unlockDexByDimension 完全一致：先水平，再曝光、构图、清晰。
        // 改顺序会让「同一天多维度达标时解锁哪一条」在两端不同 —— 这不会有报错。
        pickFor(ScoreDimension.LEVEL, levelScore, ScoreBoard.MAX_LEVEL, unlocked, lastUnlockDate, today)
            ?.let { return it }
        pickFor(ScoreDimension.EXPOSURE, exposureScore, ScoreBoard.MAX_EXPOSURE, unlocked, lastUnlockDate, today)
            ?.let { return it }
        pickFor(ScoreDimension.COMPOSITION, compositionScore, ScoreBoard.MAX_COMPOSITION, unlocked, lastUnlockDate, today)
            ?.let { return it }
        pickFor(ScoreDimension.CLARITY, clarityScore, ScoreBoard.MAX_CLARITY, unlocked, lastUnlockDate, today)
            ?.let { return it }
        return ""
    }

    /**
     * 某一维的解锁候选。
     *
     * @return 该维度下第一条「本次够格解锁」的 id；没有候选 / 不达标返回 null
     */
    private fun pickFor(
        dimension: ScoreDimension,
        dimScore: Int,
        dimMax: Int,
        unlocked: Set<String>,
        lastUnlockDate: String,
        today: String
    ): String? {
        // `nextUnlockable` 已经保证候选「尚未解锁」，所以 alreadyUnlocked 恒为 false
        // （Android 的 DexStore.tryUnlockByDimension 也是这么传的）
        val candidate = TechniqueDex.nextUnlockable(dimension, unlocked) ?: return null
        val allowed = shouldUnlock(
            tipId = candidate.id,
            dimension = dimension,
            dimScore = dimScore,
            dimMax = dimMax,
            alreadyUnlocked = false,
            // 空串 = 从未解锁过；`shouldUnlock` 内部的 isNullOrBlank 会把空串当「没有」
            lastUnlockDate = lastUnlockDate,
            today = today
        )
        return if (allowed) candidate.id else null
    }

    /** 把逗号分隔的 id 串拆成集合（去重、去空白项）。 */
    private fun splitIds(joined: String): Set<String> {
        if (joined.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (part in joined.split(',')) {
            val id = part.trim()
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /** 拆开并滤掉图鉴里不存在的 id（旧版本残留）。 */
    private fun validIds(joined: String): Set<String> {
        val ids = splitIds(joined)
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>(ids.size)
        for (id in ids) {
            if (TechniqueDex.byId(id) != null) out.add(id)
        }
        return out
    }

    private fun at(index: Int): Technique {
        val all = PhotographyBook.all
        require(index in 0 until all.size) { "技法序号越界：$index / ${all.size}" }
        return all[index]
    }
}
