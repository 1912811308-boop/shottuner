// SPDX-License-Identifier: MIT
//
// shared/src/iosMain —— 只在 iOS 目标编译的源集。
//
// ★ 这个源集的存在意义（第一批）：
//   ① 让 macOS CI 的 `:shared:compileKotlinIosArm64` / `:shared:compileKotlinIosSimulatorArm64`
//      **真的覆盖到 iosMain** —— 源集里没有文件时，这道编译门禁等于没编任何 iOS 专属代码；
//   ② 作为**跨语言边界的唯一收窄口**：Kotlin/Native 会把 shared 模块的 public API
//      导出成 ObjC 头文件，Swift 侧 `import Shared` 之后就能调。
//      跨语言的坑全在「导出什么」上，所以这里刻意把导出面收窄成
//      **标量进、标量出**的扁平函数（见 [IosBridge]）。
//
// ★ 为什么不放业务逻辑：
//   commonMain 才是共享内核（评分 / 构图 / 图鉴 / 技法文案），两边同一份实现。
//   iosMain 是**平台适配层**，第一批只有一行编译期常量 + 一个联通性自检。

package com.shottuner.app.ios

import com.shottuner.app.sensors.LevelMath
import com.shottuner.app.text.NumFmt

/**
 * iOS 侧的**跨语言门面**：Swift 侧唯一被允许直接触碰的 Kotlin API。
 *
 * ## 为什么要有这一层
 *
 * Kotlin/Native 会把 shared 模块的 public 声明导出成 ObjC 头文件（`Shared.framework/Headers/Shared.h`），
 * Swift 用 `import Shared` 引入后就能调用。直接调 `commonMain` 的类本身是可行的，
 * 但有三个坑：
 *
 * 1. **`object` 在 Swift 里是「类属性单例」**，所以是 `IosBridge.shared`，不是 `IosBridge()`。
 * 2. **重载会被改名**：Kotlin 靠参数类型区分重载，ObjC 只能靠参数名，
 *    所以同名重载在 Swift 侧会被加上尾随下划线（`one(value:)` / `one(value_:)`），
 *    **哪个被加下划线不可预测**。所以导出的函数必须**签名唯一**。
 * 3. **数组过不了边界**：`FloatArray` 会桥接成 NSArray of NSNumber（装箱 + 拷贝），
 *    性能差、Swift 侧难用。跨语言边界只传标量；需要结构时由 commonMain 提供**扁平 DTO**。
 *
 * 这一层把上面三条全部消化掉：对外只暴露签名唯一的、标量进标量出的函数。
 *
 * ## 命名说明
 *
 * Kotlin/Native 导出的 ObjC 类名带 framework 前缀（`SharedIosBridge`），
 * 但 Swift 侧通过 `swift_name` 看到的仍是**纯 Kotlin 名**（`IosBridge`）。
 */
object IosBridge {

    /** 共享内核 API 版本。Swift 侧用它判断 framework 与工程是否配套。 */
    const val SHARED_API_VERSION: Int = 1

    /**
     * 联通性自检串 —— 编译期常量。
     *
     * 它的作用是**证明 iosMain 源集确实参与了编译并被导出到了 ObjC**：
     * Swift 侧只要能拿到这串文字，就说明 KMP 的「Kotlin → ObjC → Swift」整条链路是通的。
     *
     * @return 固定文案，含 API 版本号
     */
    fun describe(): String =
        "ShotTuner iOS · 共享内核已联通（api v$SHARED_API_VERSION）"

    /**
     * 定点数值格式化（commonMain 的 [NumFmt]）：小数点恒为 `.`，不跟随系统语言。
     *
     * @param value 待格式化的数值
     * @return 例如 `1.5`
     */
    fun formatOne(value: Double): String = NumFmt.one(value)

    /**
     * 带正负号的定点数值（commonMain 的 [NumFmt]）：曝光补偿这类数值必须带号。
     *
     * @param value 待格式化的数值
     * @return 例如 `+0.3` / `-0.3`
     */
    fun formatSigned(value: Double): String = NumFmt.signed(value, decimals = 1)

    /**
     * 参考分文案。
     *
     * ★ 口径铁律：只说「**参考分**」。shottuner 拿不到"按下快门那一刻"的画面，
     *   只有取景时的连续统计量，所以给的是实时参考值，不是专业评测。
     *   任何地方都不得把它写成「得分」「评分」「准确率」这类断言式措辞。
     *
     * @param score 参考分（0..100）
     * @return 例如 `参考分 82 / 100`
     */
    fun referenceScoreText(score: Double): String = "参考分 ${score.toInt()} / 100"

    /**
     * 画面是否已经水平（commonMain 的 [LevelMath]）。
     *
     * @param roll 横滚角（度），正值表示画面右侧偏低
     * @return 在容差内返回 true
     */
    fun isLevel(roll: Double): Boolean = LevelMath.isLevel(roll.toFloat())
}
