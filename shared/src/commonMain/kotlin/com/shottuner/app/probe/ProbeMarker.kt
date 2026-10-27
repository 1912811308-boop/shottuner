package com.shottuner.app.probe

/**
 * 最小探测标记对象。
 *
 * 存在的唯一目的：让 `compileCommonMainKotlinMetadata` 真的有源文件要编译，
 * 从而验证 **commonMain 的 metadata 编译链在本机可用**。
 *
 * 如果这个文件都能编过，说明：
 *  1. Kotlin Multiplatform 插件能正常解析；
 *  2. metadata 目标（与平台无关的公共源码集）能产出 klib/metadata；
 *  3. stdlib 的 common 变体能被解析。
 *
 * 这三点是整条 iOS 移植路线的地基。地基不通就不该往里搬 7,000 行代码。
 */
object ProbeMarker {
    /** 探测标记，固定值便于构建日志确认走到了这一步。 */
    const val MARKER: String = "kmp-common-main-probe"
}
