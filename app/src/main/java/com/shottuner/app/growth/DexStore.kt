package com.shottuner.app.growth

import android.content.Context
import android.content.SharedPreferences
import com.shottuner.app.analysis.ScoreDimension
import com.shottuner.app.diag.Diag

/**
 * 图鉴解锁状态的读写（G3）——**Android 部分**。
 *
 * 与 [TechniqueDex] 分成两个文件，就是为了守住那条硬约束：纯逻辑不 import `android.*`，
 * 能在 JVM 上单测；所有碰 `Context` 的代码集中在这里。
 *
 * ## 存储方案：独立的 SharedPreferences + StringSet
 *
 * 三个选择，逐个说理由：
 *
 * **1. 独立文件 `technique_dex`，不并进 MainActivity 的 `shottuner_prefs`。**
 * 那一份是 UI 设置在用，主线程读写频繁（音量键快门、水印开关都是点了就写）。
 * 图鉴的写入会走后台线程（与 `ScoreHistoryStore` 一致），两个来源共用一份文件
 * 等于共用一个 `SharedPreferencesImpl` 的锁，UI 线程可能被后台写阻塞。
 * 分开之后各锁各的，互不拖累；`clear()` 也只会清掉图鉴，不会误伤用户设置。
 *
 * **2. StringSet，不用位图字符串。**
 * 位图要靠 id 在 `TechniqueDex.all()` 里的**下标**定位。知识库一旦在中间插入
 * 一条（为了凑分类分组，这很常见），后面所有下标整体位移，位图就会
 * **静默解锁到别的技法上** —— 而 SharedPreferences 没有版本号，这种错位
 * 没有任何机制能发现，用户只会觉得"我没做过这条怎么亮了"。
 * StringSet 存的是 id 本身，对插入、删除、重排全部免疫。
 * 代价是体积：83 条 × 约 28 字节 ≈ 2.4KB，最坏也不到 3KB，而位图是 11 字节。
 * 为 2.4KB 的差别去换一个会静默错位的方案，不划算。
 *
 * **3. 不用 JSON / 文件。**
 * 解锁状态是一组布尔，天然就是 StringSet。手写 JSON 要自己写序列化，
 * 还要处理 tmp+rename 那套原子写（`ScoreHistoryStore` 那么做是因为它是
 * 一份有内部结构的文档，坏一点整份作废）；SharedPreferences 自己就保证
 * 了写的原子性，能少写的代码就不写。
 * 也顺带避开了 `org.json` —— 它在 local unit test 里是 AGP 的 mockable 桩，
 * 一调用就抛 `not mocked`。
 *
 * ## ⚠ getStringSet 的坑
 * `SharedPreferences.getStringSet()` 返回的是**内部持有的那个 Set 实例**，
 * 直接 `add()` 再 `putStringSet()` 塞回去，SharedPreferences 会发现"新值和旧值
 * 是同一个对象"从而跳过写入，或者在别处并发修改时读到半改状态。
 * 因此本文件所有读出来的 Set 都先 `HashSet(原值)` 复制一份，写进去的也永远是
 * 一个新对象。**这条有专门的注释，别在重构时删掉。**
 *
 * ## 线程
 * 内部一把锁保证"读 → 改 → 写"不被并发打断。首次 `getSharedPreferences` 会把
 * XML 从磁盘读进来，所以调用方最好在后台线程；解锁一天最多一次，写入用
 * `commit()` 同步落盘，代价可以忽略，换来的是写完立刻可读。
 *
 * ## 失败即降级
 * 读失败 → 未解锁；写失败 → 记 `Diag.w` 并返回 false。**图鉴进度永远不能
 * 让"拍完照"这件主线任务失败。**
 */
object DexStore {

    private const val TAG = "DexStore"
    private const val PREFS_NAME = "technique_dex"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_LAST_DATE = "last_unlock_date"

    private val lock = Any()

    /** 图鉴总条数。首页那个「/ 83」从这拿，别在 UI 里写死数字。 */
    fun total(): Int = TechniqueDex.total()

    /**
     * 已解锁的 id 集合。
     *
     * 注意返回的是**与图鉴 ids 求过交的**结果：存储里可能残留着旧版本的 id
     * （比如某条技法改过名），直接拿原始集合会让首页出现「13 / 83」里 13 比 83 还大，
     * 或者进度条越界。宁可少算一条，也不给用户一个不成立的分数。
     */
    fun unlockedIds(context: Context): Set<String> = synchronized(lock) {
        val raw = rawIds(context) ?: return emptySet()
        raw.filterTo(HashSet(raw.size)) { TechniqueDex.byId(it) != null }
    }

    /** 已解锁条数。首页那个「已解锁 12」用这个。 */
    fun unlockedCount(context: Context): Int = synchronized(lock) {
        val raw = rawIds(context) ?: return 0
        var count = 0
        for (id in raw) {
            if (TechniqueDex.byId(id) != null) count++
        }
        count
    }

    /** 某条是否解锁过。id 不在图鉴里时恒为 false。 */
    fun isUnlocked(context: Context, id: String): Boolean = synchronized(lock) {
        if (id.isBlank()) return false
        rawIds(context)?.contains(id) ?: false
    }

    /** 上次解锁的日期（`yyyyMMdd`）；从未解锁过返回 null。 */
    fun lastUnlockDate(context: Context): String? = synchronized(lock) {
        prefs(context)?.getString(KEY_LAST_DATE, null)
    }

    /**
     * 记一条解锁。
     *
     * **这是"记账"原语，不自带任何门槛检查** —— 直接调用它可以在一天内解锁 83 条。
     * 正常路径请走 [tryUnlock]，那道门才带"照做 + 达标 + 每天一条"的判定。
     *
     * @return 是否是**新解锁**：`false` 表示这条本来就解锁过（或 id 非法），
     *         调用方据此决定要不要放动效
     */
    fun unlock(context: Context, id: String): Boolean = synchronized(lock) {
        unlockLocked(context, id)
    }

    /**
     * 解锁的唯一正式入口：判定 + 记账一步做完。
     *
     * 拆成 [shouldUnlock] 的三段式由调用方自己拼是危险的 —— 先判后写中间隔着一个
     * 时间点，两处都可能漏掉"每天一条"这一条。收在这里，UI 只需一个调用。
     *
     * @param tipId 完成的技法 id（`MissionTask.tipId`）
     * @param dimScore 该技法所属维度本次得分；`< 0` 表示未测
     * @param dimMax 该维度满分
     * @return 是否**新解锁** —— true 就放动效，false 什么都不做
     */
    fun tryUnlock(context: Context, tipId: String, dimScore: Int, dimMax: Int): Boolean =
        synchronized(lock) {
            val entry = TechniqueDex.byId(tipId) ?: return false
            val allowed = shouldUnlock(
                tipId = tipId,
                dimension = entry.dimension,
                dimScore = dimScore,
                dimMax = dimMax,
                alreadyUnlocked = rawIds(context)?.contains(tipId) ?: false,
                lastUnlockDate = lastUnlockDate(context),
                today = DexClock.today()
            )
            if (!allowed) return false
            unlockLocked(context, tipId)
        }

    /**
     * 维度通道解锁：某维度得分达标时，解锁该维度下第一条尚未解锁的技法。
     *
     * ## 为什么要有这条通道（B：扩解锁入口）
     * [tryUnlock] 走的是 tipId 通道，前提是「建议命中某条具体技法」，而 `ScoreBoard`
     * 当前只产得出 7 个 tipId，其中 3 条曝光还因阶梯分永远到不了达标线 ——
     * 天花板只有 4 条，图鉴等于空壳。
     * 维度通道把解锁条件放宽成「**这个维度拍好了**」：`TechniqueDex.DIMENSION_OF`
     * 里 64 条有维度归属的技法都能靠「把对应维度拍好」逐条解锁，不再受建议词表大小
     * 的限制。两个通道共用 [shouldUnlock] 与「每天最多一条」的门，防刷逻辑不变。
     *
     * @param dimension 达标的维度（null 维度不可解锁，直接返回 null）
     * @param dimScore 该维度本次得分；`< 0` 表示未测
     * @param dimMax 该维度满分
     * @return 解锁成功的技法 id；没解锁（不达标 / 已解锁 / 当天已解锁过 / 落盘失败）返回 null
     */
    fun tryUnlockByDimension(
        context: Context,
        dimension: ScoreDimension,
        dimScore: Int,
        dimMax: Int
    ): String? = synchronized(lock) {
        val unlocked = rawIds(context) ?: return null
        val candidate = TechniqueDex.nextUnlockable(dimension, unlocked) ?: return null
        val allowed = shouldUnlock(
            tipId = candidate.id,
            dimension = dimension,
            dimScore = dimScore,
            dimMax = dimMax,
            alreadyUnlocked = false,
            lastUnlockDate = lastUnlockDate(context),
            today = DexClock.today()
        )
        if (!allowed) return null
        if (unlockLocked(context, candidate.id)) candidate.id else null
    }

    /** 清空图鉴进度（设置里的「清除我的图鉴进度」用）。只影响图鉴，不动 UI 设置。 */
    fun clear(context: Context) {
        synchronized(lock) {
            val target = prefs(context)
            if (target == null) {
                Diag.w(TAG, "清除图鉴进度失败：取不到 SharedPreferences")
                return
            }
            val ok = try {
                target.edit().clear().commit()
            } catch (t: Throwable) {
                Diag.w(TAG, "清除图鉴进度失败", t)
                false
            }
            if (!ok) Diag.w(TAG, "清除图鉴进度未落盘")
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /** 记账：复制 → 加 → 写。必须在 [lock] 内调用。 */
    private fun unlockLocked(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        if (TechniqueDex.byId(id) == null) return false

        val target = prefs(context)
        if (target == null) {
            Diag.w(TAG, "记录解锁失败：取不到 SharedPreferences（id=$id）")
            return false
        }

        // 读出来的必须立刻复制一份，绝不能直接改（见类注释的 getStringSet 坑）
        val current = rawIds(context) ?: HashSet<String>()
        // 顺手把不在图鉴里的旧 id 清掉，别让存储无限攒垃圾
        current.removeAll { TechniqueDex.byId(it) == null }
        if (!current.add(id)) return false          // 本来就解锁过，不重复计数

        val today = DexClock.today()
        if (today.isBlank()) {
            Diag.w(TAG, "记录解锁失败：取不到今天的日期（id=$id）")
            return false
        }
        return try {
            // 存的是新对象，不是刚读出来的那个实例
            val ok = target.edit()
                .putStringSet(KEY_UNLOCKED, HashSet<String>(current))
                .putString(KEY_LAST_DATE, today)
                .commit()
            if (!ok) Diag.w(TAG, "记录解锁未落盘（id=$id）")
            ok
        } catch (t: Throwable) {
            Diag.w(TAG, "记录解锁失败", t)
            false
        }
    }

    /** 原始 id 集合的一份**副本**；取不到返回 null。必须在 [lock] 内调用。 */
    private fun rawIds(context: Context): HashSet<String>? {
        val target = prefs(context) ?: return null
        return try {
            val stored = target.getStringSet(KEY_UNLOCKED, null)
            if (stored == null) HashSet<String>() else HashSet<String>(stored)
        } catch (t: Throwable) {
            // 存进去的可能是别人写的类型（例如误存成了 String），ClassCastException
            // 绝不能冒到"拍完照"这条主线上：按空进度处理
            Diag.w(TAG, "读取图鉴进度失败，按空进度处理", t)
            HashSet()
        }
    }

    private fun prefs(context: Context): SharedPreferences? = try {
        // 用 applicationContext：图鉴的生命周期比 Activity 长，不能挂住 Activity 实例
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        Diag.w(TAG, "获取 SharedPreferences 失败", t)
        null
    }
}
