package com.shottuner.app.growth

import android.content.Context
import com.shottuner.app.diag.Diag
import java.io.File

/**
 * 分数历史的磁盘读写（G4）——**Android 部分**。
 *
 * 与 [ScoreHistory] 分成两个文件，就是为了守住那条硬约束：纯逻辑不 import `android.*`，
 * 能在 JVM 上单测；所有碰 `Context` / 文件系统的代码集中在这里。
 *
 * ## 落盘位置
 * `filesDir/score_history.json`。**只存本地，不上传** —— 本 App 在 manifest 里把
 * `INTERNET` / `ACCESS_NETWORK_STATE` 用 `tools:node="remove"` 显式摘掉了，
 * 「不联网」是隐私合规的立身之本，历史数据绝不能走网络。
 *
 * ## 线程
 * 全是磁盘 IO，**调用方必须在后台线程**。目前唯一调用点 `MainActivity.capturePhoto()`
 * 的 `onSuccess` 已经在一个 `Thread{}` 里。内部另加一把锁：万一将来有第二个写入点
 * （比如导入备份），也不会两份写交叉把文件写花。
 *
 * ## 失败即降级
 * 读失败（文件不存在 / 损坏 / 半截）一律返回空历史并记 `Diag.w`；写失败只记日志。
 * **落盘永远不能让「拍照」这件主线任务失败。**
 */
object ScoreHistoryStore {

    private const val TAG = "ScoreHistory"
    private const val FILE_NAME = "score_history.json"
    private const val TMP_FILE_NAME = "score_history.json.tmp"

    private val lock = Any()

    /** 历史文件。只暴露给「导出诊断信息」之类的调试用途。 */
    fun historyFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读一份历史；任何异常都降级为空历史。 */
    fun load(context: Context): ScoreHistory = synchronized(lock) { readLocked(context) }

    /** 整份覆盖写入。返回是否成功。 */
    fun save(context: Context, history: ScoreHistory): Boolean =
        synchronized(lock) { writeLocked(context, history) }

    /**
     * 落一条记录：读 → 加 → 写，三步在一个锁里。
     *
     * 返回的 [ScoreHistory] 是写入后的内存态，调用方可以直接拿来算成长摘要，
     * 省掉一次重复的读盘。
     */
    fun append(context: Context, record: ShotRecord): ScoreHistory = synchronized(lock) {
        val history = readLocked(context)
        history.add(record)
        writeLocked(context, history)
        history
    }

    /** 首末对比摘要；不足 2 条记录时为 null。 */
    fun growth(context: Context): GrowthSummary? = synchronized(lock) { readLocked(context).firstVsBest() }

    /** 最近 [days] 天的记录，给折线图用。 */
    fun series(context: Context, days: Int): List<ShotRecord> =
        synchronized(lock) { readLocked(context).recentSeries(days, System.currentTimeMillis()) }

    /** 清空（设置里的「清除我的拍摄历史」用）。 */
    fun clear(context: Context) {
        synchronized(lock) {
            runCatching {
                historyFile(context).delete()
                File(context.filesDir, TMP_FILE_NAME).delete()
            }.onFailure { t -> Diag.w(TAG, "清除历史失败", t) }
        }
    }

    private fun readLocked(context: Context): ScoreHistory {
        val file = historyFile(context)
        val text = try {
            if (!file.exists()) return ScoreHistory.empty()
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            // 读不了盘（权限被改 / 存储满了 / 文件被别的进程锁住）不能连累拍照
            Diag.w(TAG, "读取分数历史失败，按空历史处理", t)
            return ScoreHistory.empty()
        }
        return ScoreHistory.fromJson(text) { reason -> Diag.w(TAG, reason) }
    }

    private fun writeLocked(context: Context, history: ScoreHistory): Boolean {
        val file = historyFile(context)
        val tmp = File(context.filesDir, TMP_FILE_NAME)
        val payload = history.toJson()
        return try {
            // 先写临时文件再 rename：写到一半断电/被杀，留下的是完整的旧文件，
            // 而不是一份半截 JSON（半截 JSON 会让整份历史作废）
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // rename 在个别厂商的 filesDir 上会失败，退化成直接覆盖写
                Diag.w(TAG, "临时文件改名失败，改为直接写入")
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Diag.w(TAG, "写入分数历史失败", t)
            false
        }
    }
}
