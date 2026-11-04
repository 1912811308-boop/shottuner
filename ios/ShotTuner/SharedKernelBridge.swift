// SPDX-License-Identifier: MIT
//
// ios/ShotTuner/SharedKernelBridge.swift —— Kotlin 共享内核到 Swift 的取样调用。
//
// ★ 这条链路长什么样：
//
//   shared/src/commonMain + shared/src/iosMain   （Kotlin 算法内核）
//        │  Kotlin/Native 编译
//        ▼
//   Shared.framework + Shared.framework/Headers/Shared.h   （ObjC 头文件，自动生成）
//        │  Xcode 的 Swift/ObjC 桥接
//        ▼
//   Swift：import Shared → IosBridge.shared.describe()
//
// ★ 三条跨语言规则（这个文件就是它们的活样本）：
//
//   1. **模块名 = framework 名 = `Shared`**
//      KGP 用 Gradle 项目名（`shared`）首字母大写作为 framework 名，
//      Swift 侧因此是 `import Shared`。framework 名改了，这里要跟着改。
//
//   2. **Kotlin 的 `object` 在 Swift 里是「类属性单例」**
//      所以是 `IosBridge.shared`，**不是** `IosBridge()`。
//      （Kotlin 的 `class` 才是 `Xxx()`。）
//
//   3. **跨语言边界只传标量，不传数组**
//      `FloatArray` 会被桥接成 NSArray of NSNumber（装箱 + 拷贝）——
//      性能差、Swift 侧难用、还容易在两端产生类型认知偏差。
//      需要结构时，由 commonMain 提供**扁平 DTO**（全是 Int/Double/String/Boolean），
//      Kotlin 侧算完，Swift 侧只渲染。详见 docs/IOS-ARCH.md §3.4。
//
// ★ 另一个坑：**重载会被改名，且不可预测**。
//   Kotlin 靠参数类型区分重载，ObjC 只能靠参数名，所以同名重载在 Swift 侧
//   会被加上尾随下划线（`one(value:)` / `one(value_:)`），哪个被加下划线无法预测。
//   所以导出给 Swift 的函数必须**签名唯一** —— 这正是 `IosBridge` 这一层存在的理由。

import Foundation
import Shared

/// 共享内核（KMP `shared` 模块）在 Swift 侧的取样门面。
enum SharedKernelBridge {

    /// 共享内核自检串。
    ///
    /// 能拿到这串文字，就说明：**Kotlin → ObjC 头文件 → Swift** 整条链路是通的，
    /// 且 `shared/src/iosMain` 确实参与了编译（这串文字只有 iosMain 里才有）。
    static var kernelDescription: String {
        IosBridge.shared.describe()
    }

    /// 定点数值格式化：小数点恒为 `.`，不跟随系统语言。
    ///
    /// - Parameter value: 待格式化的数值
    /// - Returns: 例如 `1.5`
    static func formatOne(_ value: Double) -> String {
        IosBridge.shared.formatOne(value: value)
    }

    /// 带正负号的定点数值：曝光补偿这类数值必须带号，用户要一眼看出加还是减。
    ///
    /// - Parameter value: 待格式化的数值
    /// - Returns: 例如 `+0.3` / `-0.3`
    static func formatSigned(_ value: Double) -> String {
        IosBridge.shared.formatSigned(value: value)
    }

    /// 参考分文案。
    ///
    /// ★ 口径铁律：只说「参考分」。它是取景时的实时参考值，不是专业评测。
    ///
    /// - Parameter score: 参考分（0..100）
    /// - Returns: 例如 `参考分 82 / 100`
    static func referenceScoreText(_ score: Double) -> String {
        IosBridge.shared.referenceScoreText(score: score)
    }

    /// 画面是否已经水平。
    ///
    /// - Parameter roll: 横滚角（度），正值表示画面右侧偏低
    /// - Returns: 在容差内返回 `true`
    static func isLevel(roll: Double) -> Bool {
        IosBridge.shared.isLevel(roll: roll)
    }

    /// 技法图鉴的条目总数（与 Android 版读同一份 commonMain 数据）。
    ///
    /// ★ `count()` 的 Kotlin `Int` 映射到 Swift 是 `Int32`，显式转成 `Int` 给 SwiftUI 用。
    static var techniqueCount: Int {
        Int(TechniqueDexBridge.shared.count())
    }

    /// **直接**调用 commonMain 的纯函数（不经 iosMain 门面）。
    ///
    /// 用来证明：commonMain 里的实现**原样**就能被 Swift 调到 —— 两端共用同一份代码，
    /// 不是「Android 一份、iOS 再抄一份」。
    ///
    /// ★ 陷阱：**可空**的原始类型过语言边界时，Kotlin/Native 走的是**装箱类型**：
    ///   Kotlin `Float?` → Swift `KotlinFloat?`（**不是** `Float?`）。
    ///   同理 `Int?` → `KotlinInt?`、`Double?` → `KotlinDouble?`。
    ///   而**非可空**的原始类型是直接映射到 C 原语的：
    ///   `Float` → `Float`、`Double` → `Double`、`Int` → `Int32`、`Long` → `Int64`。
    ///   ⇒ 所以跨语言边界的参数**尽量用非可空的原始类型**。
    ///   （实测踩过：2026-10-29 第一次编 iOS 就挂在 `Float?` → `KotlinFloat?` 上。）
    ///
    /// - Parameter degrees: 角度（度）
    /// - Returns: 夹到 [-180, 180] 之后的角度
    static func commonNormalizeDegrees(_ degrees: Float) -> Float {
        LevelMath.shared.normalizeDegrees(degrees: degrees)
    }
}
