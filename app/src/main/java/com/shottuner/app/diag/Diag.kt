package com.shottuner.app.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地诊断日志（v3.0.0 阶段 3）。
 *
 * 为什么要有它：发布版里用户只会说"点了没反应""拍不了"，而崩溃栈和异常在
 * `catch (_: Throwable) {}` 里被吞掉了。没有本地日志，等于把唯一的现场证据销毁。
 *
 * 三条设计约束，都是为"发布版"这个前提服务的：
 * 1. **不联网、不上传**。日志只写在 `filesDir/diag/` 里，只有用户主动点「导出诊断信息」
 *    才会打包成 zip 交给系统分享面板。这与隐私政策「本应用无网络权限」完全一致。
 * 2. **环形缓冲，不无限增长**。3 个文件 × 64KB，写满就滚动覆盖最旧的，
 *    最坏情况占用 192KB，不会把用户的存储撑爆。
 * 3. **日志本身绝不能抛异常**。所有写盘都包在 runCatching 里 —— 记录故障的代码
 *    自己出故障，比不记录更糟。
 */
object Diag {

    private const val MAX_BYTES = 64 * 1024
    private const val MAX_FILES = 3
    private const val FILE_PREFIX = "diag"

    private val lock = Any()

    /**
     * 每个线程一个 `SimpleDateFormat`（P1-3）。
     *
     * `SimpleDateFormat` 内部有可变的日历状态，多线程共用一个实例调用 `format()`
     * 会抛 `ArrayIndexOutOfBoundsException` 之类的怪异常 —— 而日志线程恰好不止一个
     * （相机分析线程 + UI 线程 + 前台服务线程都会写），原来的代码又是在
     * `synchronized` **之外** 调的，等于把雷埋在了最高频路径上。
     *
     * 没有改用 `java.time.format.DateTimeFormatter`：它需要 API 26+，
     * 本应用 minSdk 是 24 且没开 core library desugaring，会在 24/25 上直接崩。
     * `ThreadLocal` 是同等效果、零 API 门槛的解法。
     */
    private val stampLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var dir: File? = null

    /** 在 Application.onCreate 里调一次。之后再调用无副作用。 */
    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "diag").apply { runCatching { mkdirs() } }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable? = null) = write("W", tag, msg, tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    fun dirFile(): File? = dir

    /**
     * 打包成 zip。返回 null 表示没有任何可导出的内容。
     *
     * 除了三个日志文件，额外写一份 `meta.txt`：版本、系统版本、机型。
     * 这些不含任何个人身份信息（机型是 Build.MODEL，不是 IMEI/序列号），
     * 但足以判断"是不是某款机器特有"。
     */
    fun exportTo(target: File, versionName: String): File? {
        val source = dir ?: return null
        val files = logFiles(source).filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return null
        return try {
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                for (f in files) {
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("meta.txt"))
                zip.write(meta(versionName).toByteArray())
                zip.closeEntry()
            }
            target
        } catch (_: Throwable) {
            null
        }
    }

    /** 最近的若干行（调试/真机排查时可直接展示，不依赖导出）。 */
    fun tail(lines: Int): String {
        val source = dir ?: return ""
        return try {
            val all = logFiles(source)
                .filter { it.exists() }
                .sortedBy { it.lastModified() }
                .joinToString("\n") { it.readText() }
            all.lines().takeLast(lines.coerceAtLeast(1)).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun meta(versionName: String): String = buildString {
        append("app=").append(versionName).append('\n')
        append("android=").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
        append("model=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("abi=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
    }

    /** diag.0 最新，diag.2 最旧。 */
    private fun logFiles(base: File): List<File> =
        (0 until MAX_FILES).map { File(base, "$FILE_PREFIX.$it") }

    private fun currentFile(base: File): File = File(base, "$FILE_PREFIX.0")

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val base = dir ?: return
        val line = try {
            buildString {
                // 用本线程的实例，不碰共享状态
                append(stampLocal.get()!!.format(Date()))
                append(' ').append(level).append('/').append(tag).append(": ").append(msg)
                if (tr != null) {
                    append(" | ").append(tr.javaClass.name).append(": ").append(tr.message)
                    // 只取前 6 帧：够定位调用点，又不至于让一行日志变成几 KB
                    tr.stackTrace.take(6).forEach { append("\n    at ").append(it) }
                }
                append('\n')
            }
        } catch (_: Throwable) {
            // 连时间戳都拿不到也不能让调用方崩：退化成不带时间的裸行
            "$level/$tag: $msg\n"
        }
        runCatching {
            synchronized(lock) {
                val file = currentFile(base)
                if (file.exists() && file.length() + line.length > MAX_BYTES) rotate(base)
                FileOutputStream(file, true).use { it.write(line.toByteArray()) }
            }
        }
    }

    /** 滚动：diag.2 丢掉，diag.1 → diag.2，diag.0 → diag.1，新的 diag.0 重新开始。 */
    private fun rotate(base: File) {
        runCatching {
            val oldest = File(base, "$FILE_PREFIX.${MAX_FILES - 1}")
            if (oldest.exists()) oldest.delete()
            for (i in MAX_FILES - 1 downTo 1) {
                val from = File(base, "$FILE_PREFIX.${i - 1}")
                if (from.exists()) from.renameTo(File(base, "$FILE_PREFIX.$i"))
            }
        }
    }
}
