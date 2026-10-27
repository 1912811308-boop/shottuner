package com.shottuner.app.growth

/**
 * 拍照参考分历史（G4「进步可见」）——**纯 JVM 部分**。
 *
 * 本文件不 import 任何 `android.*`，也不依赖 `Context` / 文件系统，因此
 * [ScoreHistory.add] 的环形覆盖、[ScoreHistory.firstVsBest] 的成长对比、
 * [ScoreHistory.recentSeries] 的窗口筛选、以及 [ScoreHistory.toJson] /
 * [ScoreHistory.fromJson] 都能直接在 `src/test` 下跑单测。
 * 真正的磁盘读写放在 [ScoreHistoryStore] —— 只有那个文件碰 Android。
 *
 * ## 为什么不用 Room / SQLDelight / Gson / Moshi
 * 数据上限 500 条、每条约 100 字节，最坏 55KB。为 55KB 引入 ORM 会冲击已经调好的
 * R8 规则与 40MB 包体预算，也会把「分析代码纯 JVM 可单测」这个既有优势赔进去。
 * 序列化因此手写。
 *
 * ## 为什么没用 org.json
 * org.json 来自 android.jar，而 local unit test 跑的是 AGP 生成的 mockable jar：
 * 那里的方法全是桩，一调用就抛 `RuntimeException("Method ... not mocked")`。
 * 本任务的硬要求恰恰是「toJson → fromJson 往返」「损坏 JSON 不抛异常」必须可单测；
 * 而补一条 `testImplementation("org.json:json:...")` 既违反「不新增第三方依赖」，
 * 离线仓库里也取不到。所以这里用一段 60 行的手写读写，**产出与接受的是标准 JSON**，
 * 将来若上了 Robolectric 或允许加依赖，只需替换这两个函数体，调用方一行不用改。
 *
 * ## 未测维度一律 null
 * `measured == false` 的维度必须存 null，不能存 0 —— 0 会被用户读成「这项 0 分」，
 * 而真实含义是「这项没测到」。同理，任一维未测时 `total` 记 null，
 * 与 UI 口径「参考分 --」保持一致。**宁可没有数字，也不给假数字。**
 */

/** 一次拍照的四维参考分记录。四维为 null = 该维度未测。 */
data class ShotRecord(
    /** 拍摄时刻，`System.currentTimeMillis()`（minSdk 24，不能用 `java.time`） */
    val timestamp: Long,
    /** 总分；任一维度未测时为 null */
    val total: Int?,
    val level: Int?,
    val exposure: Int?,
    val composition: Int?,
    val clarity: Int?
) {
    companion object {
        /**
         * 由四维分值合成一条记录。
         *
         * 四个值里只要有一个是 null（未测），`total` 就是 null ——
         * `ScoreBoard` 的 `total` 会把未测维度按满分 / 0 分计入，那个数不能拿来当总分用。
         */
        fun of(
            timestamp: Long,
            level: Int?,
            exposure: Int?,
            composition: Int?,
            clarity: Int?
        ): ShotRecord {
            val sum = if (level != null && exposure != null && composition != null && clarity != null) {
                level + exposure + composition + clarity
            } else {
                null
            }
            return ShotRecord(
                timestamp = timestamp,
                total = sum,
                level = level,
                exposure = exposure,
                composition = composition,
                clarity = clarity
            )
        }
    }
}

/**
 * 首末对比摘要。
 *
 * `delta` 只在「首张有分」且「存在有分记录」时才算；否则为 null。
 * 调用方拿到 null 就该显示「--」，**不许拿 0 或任何假数字兜底**。
 */
data class GrowthSummary(
    /** 第一张的总分；首张未测时为 null */
    val firstTotal: Int?,
    /** 历史最好总分；一条有分记录都没有时为 null */
    val bestTotal: Int?,
    /** `bestTotal - firstTotal`；任一端为 null 时是 null */
    val delta: Int?,
    /** 记录条数（含未测的记录） */
    val shotCount: Int
)

/**
 * 一份历史（内存态）。
 *
 * 不是单例：调用方从 [ScoreHistoryStore] 读出来、改完再写回，
 * 这样「读 → 加 → 写」可以在一个锁里完成，也方便单测直接构造。
 */
class ScoreHistory private constructor(
    private val records: MutableList<ShotRecord>
) {

    /** 当前条数（环形覆盖后 ≤ [MAX_RECORDS]）。 */
    fun size(): Int = records.size

    /** 快照副本，按时间升序。外部改动不会影响内部状态。 */
    fun all(): List<ShotRecord> = records.toList()

    /**
     * 追加一条，并按时间升序维护、超出上限丢最旧的（环形覆盖）。
     *
     * 为什么先排序再丢：时间戳来自 `System.currentTimeMillis()`，系统校时 / 手动改表
     * 都可能让新记录的时间早于旧记录。只按「插入顺序」丢第一条，会把一条新照片当成
     * 最旧的丢掉；按时间排一遍再丢，最坏情况也只是丢一条时间最异常的记录。
     */
    fun add(record: ShotRecord) {
        records.add(record)
        records.sortBy { it.timestamp }
        val overflow = records.size - MAX_RECORDS
        if (overflow > 0) {
            records.subList(0, overflow).clear()
        }
    }

    /**
     * 首末对比：第一张 vs 历史最好。
     *
     * 少于 [MIN_RECORDS_FOR_GROWTH] 条记录返回 null —— 只有一张照片谈不上「进步」，
     * 硬算出来的 delta 是自欺欺人。
     *
     * 首张未测时 [GrowthSummary.firstTotal] 为 null、[GrowthSummary.delta] 为 null：
     * 起点是「--」，任何减法都没有意义。
     */
    fun firstVsBest(): GrowthSummary? {
        if (records.size < MIN_RECORDS_FOR_GROWTH) return null
        val first = records.first()
        val bestTotal = records.mapNotNull { it.total }.maxOrNull()
        val delta = if (first.total != null && bestTotal != null) bestTotal - first.total else null
        return GrowthSummary(
            firstTotal = first.total,
            bestTotal = bestTotal,
            delta = delta,
            shotCount = records.size
        )
    }

    /**
     * 最近 [days] 天的记录（升序），给折线图提供数据。
     *
     * 折线图 UI 不在本文件职责内，这里只保证数据口径：按墙上时钟切窗口，
     * 不补点、不插值 —— 没有拍照的那天就是没有，插值出来的点是假数据。
     */
    fun recentSeries(days: Int, now: Long): List<ShotRecord> {
        // 上限 3650 天：再大 `days * DAY_MS` 会溢出 Long，窗口反而变成负数
        val span = days.coerceIn(1, 3650)
        // 「现在」由调用方注入：commonMain 里没有 System.currentTimeMillis()
        // （kotlin.system.getTimeMillis 在本工程的 Kotlin 1.9.24 上还是实验 API，
        //   且各端实现未必同刻）。注入之后单测可以传固定值，**结果可复现**。
        val from = now - span * DAY_MILLIS
        return records.filter { it.timestamp >= from }
    }

    /** 序列化成标准 JSON。null 字段整体省略（省字节，也避免和 0 混淆）。 */
    fun toJson(): String {
        val sb = StringBuilder(128 + records.size * 96)
        sb.append('{')
        sb.append('"').append(KEY_VERSION).append("\":").append(VERSION)
        sb.append(",\"").append(KEY_RECORDS).append("\":[")
        records.forEachIndexed { index, record ->
            if (index > 0) sb.append(',')
            appendRecord(sb, record)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendRecord(sb: StringBuilder, record: ShotRecord) {
        sb.append('{')
        sb.append('"').append(KEY_TIME).append("\":").append(record.timestamp)
        appendNullableInt(sb, KEY_TOTAL, record.total)
        appendNullableInt(sb, KEY_LEVEL, record.level)
        appendNullableInt(sb, KEY_EXPOSURE, record.exposure)
        appendNullableInt(sb, KEY_COMPOSITION, record.composition)
        appendNullableInt(sb, KEY_CLARITY, record.clarity)
        sb.append('}')
    }

    private fun appendNullableInt(sb: StringBuilder, key: String, value: Int?) {
        if (value == null) return
        sb.append(",\"").append(key).append("\":").append(value)
    }

    companion object {
        /** 环形上限。500 条 × ~100B ≈ 55KB，够用一年又不会撑大备份体积。 */
        const val MAX_RECORDS = 500

        /** 出成长摘要需要的最少记录数。 */
        const val MIN_RECORDS_FOR_GROWTH = 2

        private const val VERSION = 1
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        private const val KEY_VERSION = "version"
        private const val KEY_RECORDS = "records"
        private const val KEY_TIME = "t"
        private const val KEY_TOTAL = "total"
        private const val KEY_LEVEL = "level"
        private const val KEY_EXPOSURE = "exposure"
        private const val KEY_COMPOSITION = "composition"
        private const val KEY_CLARITY = "clarity"

        fun empty(): ScoreHistory = ScoreHistory(mutableListOf())

        /** 由已有记录构造（会排序 + 环形裁剪，等价于一次读盘的结果）。 */
        fun fromRecords(records: Collection<ShotRecord>): ScoreHistory {
            val history = ScoreHistory(records.toMutableList())
            history.records.sortBy { it.timestamp }
            val overflow = history.records.size - MAX_RECORDS
            if (overflow > 0) {
                history.records.subList(0, overflow).clear()
            }
            return history
        }

        /**
         * 反序列化。**任何异常都吞掉并返回空历史**，由 [onError] 通知调用方记日志。
         *
         * 为什么坏一点就整体作废，而不是跳过坏记录保留剩下的：一份残缺的历史会算出
         * 一个错的第一张（delta 的起点），而**错的 delta 比没有 delta 更伤** ——
         * 用户会照着它以为自己在进步。500 条记录重建成本极低，整体作废更安全。
         *
         * @param onError 失败原因回调（纯 JVM：记日志的 `Diag.w` 由调用方在里面调）
         */
        fun fromJson(text: String, onError: ((String) -> Unit)? = null): ScoreHistory {
            if (text.isBlank()) return empty()
            return try {
                val root = JsonParser(text).parse()
                val obj = root as? Map<*, *>
                    ?: throw IllegalStateException("根节点不是 JSON 对象")
                val items = obj[KEY_RECORDS] as? List<*>
                    ?: throw IllegalStateException("缺少 $KEY_RECORDS 数组")
                val parsed = ArrayList<ShotRecord>(items.size)
                for (item in items) {
                    val map = item as? Map<*, *>
                        ?: throw IllegalStateException("记录不是 JSON 对象")
                    parsed.add(recordFrom(map))
                }
                fromRecords(parsed)
            } catch (t: Throwable) {
                // 连 StackOverflowError 这种 Error 也接住：读历史的代码自己崩了，
                // 会让「拍完照」这件主线任务失败，比丢掉历史严重得多
                // ★ 用 `t::class.simpleName` 而不是 `t.javaClass.simpleName`：
                //   `javaClass` 是 **JVM-only** 的 API（Kotlin/Native 的 commonMain
                //   没有它），而 Android 侧编译**完全看不出来**（Android 有 javaClass）。
                //   这个 bug 就是被 CI 的 common metadata 编译抓出来的 ——
                //   metadata 编译只允许 common API，是「iOS 纯度」的唯一权威检查。
                //   `t::class` 是 common API；Kotlin/Native 上匿名类的 simpleName
                //   可能为 null，拼接出来是 "null"，可接受（这里只是错误提示文案）。
                onError?.invoke("分数历史解析失败，按空历史处理：" + (t.message ?: t::class.simpleName))
                empty()
            }
        }

        @Throws(IllegalStateException::class)
        private fun recordFrom(map: Map<*, *>): ShotRecord {
            val time = longField(map, KEY_TIME)
            if (time <= 0L) throw IllegalStateException("非法时间戳 $time")
            return ShotRecord(
                timestamp = time,
                total = nullableIntField(map, KEY_TOTAL),
                level = nullableIntField(map, KEY_LEVEL),
                exposure = nullableIntField(map, KEY_EXPOSURE),
                composition = nullableIntField(map, KEY_COMPOSITION),
                clarity = nullableIntField(map, KEY_CLARITY)
            )
        }

        private fun longField(map: Map<*, *>, key: String): Long {
            val value = map[key] ?: return 0L
            val number = value as? Number ?: throw IllegalStateException("$key 不是数字")
            return number.toLong()
        }

        /** 缺失 → null；存在但不合法（非数字 / 越界）→ 抛异常，整份历史作废。 */
        private fun nullableIntField(map: Map<*, *>, key: String): Int? {
            val value = map[key] ?: return null
            val number = value as? Number ?: throw IllegalStateException("$key 不是数字")
            val int = number.toInt()
            if (int < 0 || int > MAX_DIMENSION_VALUE) {
                throw IllegalStateException("$key 越界：$int")
            }
            return int
        }

        /** 四维满分之和（水平 25 + 曝光 30 + 构图 25 + 清晰 20）。 */
        private const val MAX_DIMENSION_VALUE = 100
    }
}

/**
 * 最小 JSON 解析器：只支持 object / array / string / number / true / false / null，
 * 够读自己写的文件，遇任何不合法输入都抛 [IllegalStateException] 交给上层兜底。
 *
 * 刻意不做成通用库：多一个特性就多一处可能抛之外的失败路径。
 */
private class JsonParser(private val src: String) {

    private var pos = 0

    fun parse(): Any? = parseValue()

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) throw IllegalStateException("JSON 在 $pos 处意外结束")
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { expectWord("true"); true }
            'f' -> { expectWord("false"); false }
            'n' -> { expectWord("null"); null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        pos++ // '{'
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return out
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw IllegalStateException("对象键必须是字符串（pos=$pos）")
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') throw IllegalStateException("对象缺少 ':'（pos=$pos）")
            pos++
            out[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return out
                }

                else -> throw IllegalStateException("对象未正确结束（pos=$pos）")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        pos++ // '['
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return out
        }
        while (true) {
            out.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return out
                }

                else -> throw IllegalStateException("数组未正确结束（pos=$pos）")
            }
        }
    }

    private fun parseString(): String {
        pos++ // 开引号
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw IllegalStateException("字符串未闭合（pos=$pos）")
            val c = src[pos++]
            when {
                c == '"' -> return sb.toString()
                c != '\\' -> sb.append(c)
                else -> appendEscaped(sb)
            }
        }
    }

    private fun appendEscaped(sb: StringBuilder) {
        if (pos >= src.length) throw IllegalStateException("转义序列未闭合（pos=$pos）")
        when (val e = src[pos++]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                if (pos + 4 > src.length) throw IllegalStateException("\\u 转义不完整（pos=$pos）")
                val hex = src.substring(pos, pos + 4)
                val code = hex.toIntOrNull(16)
                    ?: throw IllegalStateException("\\u 转义非法：$hex")
                sb.append(code.toChar())
                pos += 4
            }

            else -> throw IllegalStateException("非法转义 \\$e（pos=$pos）")
        }
    }

    private fun parseNumber(): Any {
        val start = pos
        while (pos < src.length) {
            val c = src[pos]
            val isNumberChar = c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
            if (!isNumberChar) break
            pos++
        }
        val raw = src.substring(start, pos)
        if (raw.isEmpty()) throw IllegalStateException("不是合法的值（pos=$start）")
        val isFloat = raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0
        return if (isFloat) {
            raw.toDoubleOrNull() ?: throw IllegalStateException("非法数字：$raw")
        } else {
            raw.toLongOrNull() ?: throw IllegalStateException("非法数字：$raw")
        }
    }

    private fun expectWord(word: String) {
        if (!src.startsWith(word, startIndex = pos)) {
            throw IllegalStateException("期望 $word（pos=$pos）")
        }
        pos += word.length
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < src.length) {
            when (src[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }
}
