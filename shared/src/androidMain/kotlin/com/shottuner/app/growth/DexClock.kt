package com.shottuner.app.growth

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图鉴的日期口径。**纯 JVM**，minSdk 24 兼容。
 *
 * 只产 `yyyyMMdd` 字符串，不产时间戳，理由有三：
 * 1. 「每天最多解锁一条」比的是**日期**，不是精确到毫秒的时长。时间戳还要再砍一道，
 *    多一道就多一处时区/取整口径的分歧。
 * 2. `java.time.LocalDate` 要 API 26+，本 App minSdk 24 且没开 desugaring，用了会
 *    `NoClassDefFoundError`。
 * 3. 字符串可以直接进单测，`"20250601"` 比 `1700000000000L` 可读得多，也能直接
 *    存进 SharedPreferences，不需要序列化。
 *
 * `SimpleDateFormat` 内部有可变日历状态，共享一个实例跨线程 `format()` 会抛怪异常
 * （`Diag` 踩过同一个坑），所以每个线程一份。
 *
 * ★ 为什么在 androidMain 而不是 commonMain：
 *   `SimpleDateFormat`（java.text）与 `ThreadLocal` 在 **Kotlin/Native 的 commonMain
 *   里都不存在**（Kotlin/Native 没有 java.text / java.util）。
 *   而 `DexClock` 只被 Android 层的 `DexStore` 用 —— 所以整块搬到 androidMain，
 *   common 侧的 `TechniqueDex` 保持纯算法。
 *   这也是「**expect/actual 守死 0 个**」纪律的完整落地：能用「放在平台源集里」
 *   解决的，就不用 expect/actual（K/Native 编译慢是已列的中高风险）。
 */
object DexClock {

    /** 日期格式。对外暴露是为了让 `DexStore` 与单测共用同一套口径。 */
    const val PATTERN = "yyyyMMdd"

    private val stampLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat(PATTERN, Locale.US)
    }

    /**
     * 格式化；取不到就返回空串，由调用方决定怎么兜底，绝不让日期代码抛异常。
     *
     * 不用 `get()!!`：`Diag` 里那么写是因为那里必须拿到时间戳，这里图鉴少了日期
     * 只是少解锁一条，**宁可少解锁，也不让日期代码冒出一个 NPE**。
     */
    fun format(millis: Long): String {
        val formatter = stampLocal.get() ?: return ""
        return try {
            formatter.format(Date(millis))
        } catch (_: Throwable) {
            ""
        }
    }

    /** 今天。 */
    fun today(): String = format(System.currentTimeMillis())
}
