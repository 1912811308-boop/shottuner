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

package com.shottuner.app.ios

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

    private fun at(index: Int): Technique {
        val all = PhotographyBook.all
        require(index in 0 until all.size) { "技法序号越界：$index / ${all.size}" }
        return all[index]
    }
}
