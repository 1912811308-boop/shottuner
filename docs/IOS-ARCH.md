# IOS-ARCH · iOS 移植架构设计（KMP commonMain 边界与迁移路径）

> 版本 v1.0 ｜ 作者：高见远（架构师）｜ 基于代码现状 `com.shottuner.app` v3.1.0
> 上游输入：`docs/IOS-PRD.md`（813 行）、`software-product-manager-2` 的可移植资产实测报告
> **本文只做架构，不写业务代码、不改任何源文件。**

---

## 0. 一页纸结论

**PRD 与 PM 实测报告的"只有 1 个文件带 JVM 专属 API"这个结论不成立。** 我独立复核了当前代码，真实阻塞是 **10 个文件、13 处调用、4 类 API**，其中**最致命的一类（跨文件 Android 依赖）在两份报告里都没有出现**——它会让 `commonMain` 在第一步编译就失败，且失败原因与"技术可行性"无关，纯粹是文件摆放问题。

| # | 结论 | 影响 |
|---|---|---|
| 1 | **`DeviceProfile` / `VideoCaps` / `LensInfo` 三个纯数据类定义在 `device/HardwareProbe.kt`（Android 文件）里**，而 `feature/FeatureAdvisor.kt`、`video/VideoRecommender.kt` 都 import 它们 → **所谓"纯文件"并不自包含：类级传递闭包后会掉出去 3 个文件（L1 30 → L2 27）**（★ 最新实测见 §11.2；A 类阻塞实为**三条**，我原漏了 `SceneClassifier` 的同包依赖，见 §11.3） | 🔴 **头号阻塞**。不做抽取，commonMain 根本编不过 |
| 2 | **JVM 专属格式化共 10 处调用、跨 8 个文件**（`String.format` / `"%.1f".format` / `SimpleDateFormat`） | 🟠 PM 只报了 `TechniqueDex` 一处 |
| 3 | **`System.currentTimeMillis()` 真实使用 2 处**（`ScoreHistory.kt:153`、`TechniqueDex.kt:272`）；另 2 处出现在**注释里**（`PromptScheduler`、`MissionEngine`），不是阻塞 | 🟡 需注入化 |
| 4 | `java.lang.ThreadLocal`（`TechniqueDex.kt:252`）**无 common 等价物**，PM 未提 | 🟠 |
| 5 | `org.json` **没有被使用**（PM 的说法正确 —— `ScoreHistory.kt:17-21` 的注释解释了"为什么没用 org.json"，我的初扫是注释误命中） | ✅ 无阻塞 |
| 6 | `Brand.OTHER` 存在且 `BrandGuide.hint()` 有 `else` 兜底 → iOS 传 `Brand.OTHER` 即可复用全部品牌文案，**不需要重构 `FeatureAdvisor` 的 13 处调用** | ✅ 好消息，边界干净 |
| 7 | 工程已建立「纯内核 + Android 侧 Store」分家模式（`TechniqueDex`/`DexStore`、`ScoreHistory`/`ScoreHistoryStore`）→ A 类抽取**沿用既有模式**，不是新发明 | ✅ 阻力小 |

**建议的迁移路径**：先把 commonMain 变成**能编译的**（Spike #0，约 1 人日，零新依赖），再做**端口-适配器**改造（相机/AI/落盘），最后才是 UI。第一步不做就往后推，后面每一步都建立在"编不过"的地基上。

---

## 1. 复核：commonMain 的真实阻塞清单

> 复核方法：`app/src/main/java/com/shottuner/app` 下 47 个 `.kt`（15,535 行）逐文件扫 `import android*` / `import androidx*`，得 28 个"无 android import"文件（PM 报 28，差 1 个是 `device/Brand.kt`，见 §1.5），再对这 28 个做两轮 JVM 专属 API 扫描（排除注释命中）。

### 1.1 A 类 · 跨文件 Android 依赖（🔴 头号阻塞，PM 未提）

| # | 位置 | 事实 | 后果 |
|---|---|---|---|
| A1 | `device/HardwareProbe.kt:14 / :42 / :86` | `LensInfo`、`VideoCaps`、`DeviceProfile` 三个**纯数据类**与 `object HardwareProbe`（`:162`，Android-only）**同处一个文件**，该文件 import 了 7 个 `android.*`（`Context` / `SurfaceTexture` / `CameraCharacteristics` / `CameraManager` / `Build` / `Size`） | `FeatureAdvisor.kt:5`、`VideoRecommender.kt:5` 都 `import com.shottuner.app.device.DeviceProfile` → **commonMain 编译时这两个"纯文件"会顺着 import 找到 Android 文件，直接失败** |
| A2 | `DeviceProfile.companion.unknown()` `:133-151` | 用了 `Build.MANUFACTURER / Build.BRAND / Build.MODEL / Build.VERSION.RELEASE / Build.VERSION.SDK_INT` | 工厂方法必须留在 Android 层，不能进 common |

**这是"28 个纯文件（类级闭包后只剩 26 个）可直接进 commonMain"这个结论唯一的硬伤**，而且它**不是**"某个文件用了 JVM API"，是"纯文件依赖了不纯的文件"——按文件清单逐个数是数不出来的。

### 1.2 B 类 · JVM 专属格式化（🟠 10 处调用 / 8 个文件，PM 只报了 1 处）

| # | 位置 | 调用 | 备注 |
|---|---|---|---|
| B1 | `analysis/CompositionAnalyzer.kt:188` | `"画面有 %.0f° 的倾斜，%s转一点手机…".format(abs(tilt), dir)` | 用户可见文案 |
| B2 | `feature/FeatureAdvisor.kt:137-138` | `"…噪点明显更多…".format(ln(...)/ln(2))` | 跨两行的单个调用 |
| B3 | `feature/FeatureAdvisor.kt:309` | `"%.0fs".format(sec)` | |
| B4 | `growth/TechniqueDex.kt:7, 252-253, 265` | `SimpleDateFormat` + `java.util.Locale` + **`java.lang.ThreadLocal`** | `ThreadLocal` 是 `java.lang` 的，**common 无等价物**，比 `SimpleDateFormat` 更难替代 |
| B5 | `knowledge/PromptEngine.kt:152` | `private fun pct(value: Float) = "%.0f".format(...)` | 提示卡副文案 |
| B6 | `model/Recommendation.kt:63` | `String.format("%.1fs", sec)` | **核心输出模型**，UI 与悬浮卡都在用 |
| B7 | `sensors/LevelMath.kt:41` | `"LevelReading(roll=%.2f, pitch=%.2f)".format(...)` | `toString()` |
| B8 | `video/VideoRecommender.kt:328` | `"%.1fs".format(sec)` | |
| B9 | `device/HardwareProbe.kt:36-37` | `LensInfo.label` 里的 `"%.1f"` ×2 | 随 A1 一起搬迁 |

**为什么 Kotlin 的 `String.format` 扩展在 common 里不可用**：Kotlin 标准库把 `kotlin.text.String.format(vararg args: Any?)` 声明在 `kotlin/text/StringsJVM.kt`，实现直接调 `java.lang.String.format` —— **JVM-only**。Kotlin/Native 的 common 源集里没有这个函数，写了就是 `Unresolved reference: format`。

**顺带一个现存的正确性 bug（不是 KMP 引入的）**：`String.format` 走**默认 Locale**，在德语等使用逗号作小数点的地区，`"%.1f"` 会输出 `1,5` 而不是 `1.5`。中文用户无感知，但 iOS 面向全球时这是可见缺陷。→ **新的 common 格式化器必须是 locale 无关的**，顺带把这个 bug 修掉。

### 1.3 C 类 · 时间 API（🟡 2 处真实使用）

| # | 位置 | 调用 | 说明 |
|---|---|---|---|
| C1 | `growth/ScoreHistory.kt:153` | `val from = System.currentTimeMillis() - span * DAY_MILLIS` | **真实调用**，用于"最近 N 天"的窗口计算 |
| C2 | `growth/TechniqueDex.kt:272` | `fun today(): String = format(System.currentTimeMillis())` | **真实调用** |

**不是阻塞的（初扫误命中）**：`knowledge/PromptScheduler.kt:69` 与 `growth/MissionEngine.kt:22`、`growth/MissionTask.kt:62` 里的 `System.currentTimeMillis` / `java.time` **只出现在注释里**，且注释内容恰恰是在声明"禁止使用"（PromptScheduler 的注释原文："所有时间都必须由外部传进来的 `now` 决定，内部一次都不许调 `System.currentTimeMillis()`"）。→ **调度器的时间注入设计已经是对的，直接可移植。**

### 1.4 D 类 · 建议排除出 commonMain（不是阻塞，是不该进）

| 文件 | 理由 |
|---|---|
| `device/Brand.kt` | 纯 Kotlin 且可移植（`Brand.OTHER` + `hint()` 的 `else` 兜底让 iOS 能复用全部品牌文案），但 iOS 无 OEM 差异 → **iOS 侧只传 `Brand.OTHER`**，文案自动落到通用兜底。**建议放 common**，因为 `FeatureAdvisor`/`VideoRecommender` 依赖它 |

### 1.5 与 PM 报告的差异汇总

| 项 | PM 报告 | 我的复核 | 差异原因 |
|---|---|---|---|
| 纯文件数 | 28 | **28** | ✅ **PM 的 28 是对的，我先前写的 29 是我自己的算术失误**（文件级口径 = 自身无 `import android*`）。`device/Brand.kt` 本来就在这 28 个里，且它自包含（无任何项目内 import） |
| **类级闭包口径** | 未提 | **L2 = 27 个 / 7,786 行**（★ **以 §11.2 为准**，数字随工程推进会变） | ★ **唯一正确的可移植口径**。被排除的是**三个**文件：`FeatureAdvisor.kt` / `VideoRecommender.kt`（依赖 `DeviceProfile`）+ **`SceneClassifier.kt`（依赖 `AiVisionResult`，同包引用无 import —— 本文原漏，见 §11.3）** |
| 行数 | 8,075 | 未逐一核对（总仓 15,535 行 / 47 文件） | 以实测为准 |
| JVM 专属 API 的文件数 | **1**（`TechniqueDex`） | **10** | PM 扫的是 `import java.*` 这类**显式 import**，漏掉了：① `String.format`（无 import，`java.lang` 是自动导入的）；② `"%.1f".format`（Kotlin 扩展，同样无 import）；③ `ThreadLocal`（`java.lang`，自动导入）；④ **A 类的跨文件依赖根本不是 import 问题** |
| `org.json` | 未使用 | **确认未使用** | PM 正确。我的初扫命中的是 `ScoreHistory.kt:17` 的注释「为什么没用 org.json」 |
| `AiVisionResult` 下沉 | 建议 | **支持，且有先例** | `AiVisionResult` 已是纯数据类（`faceCount/faceArea/faceBox/scene/confidence`），下沉后 commonMain 与 ML Kit 解耦，正好绕开 PRD R5 |

---

## 2. 最小改造方案（零新依赖）

> 硬约束：不引 `kotlinx-datetime`、不引 `kotlinx-serialization`、不引任何第三方。以下方案全部用 Kotlin 标准库 + 手写算法实现。

### 2.1 改法一：抽一个 locale 无关的数字格式化器（解决 B 类 10 处里的 9 处）

新增 `commonMain` 文件 `text/NumFmt.kt`，约 40 行，**只依赖 Kotlin 标准库**：

```kotlin
object NumFmt {
    /** 定点小数。locale 无关：永远用 '.'，不做千分位。 */
    fun fixed(value: Double, decimals: Int): String
    fun fixed(value: Float, decimals: Int): String
    /** 百分比整数，如 92 -> "92" */
    fun pct(value: Float): String
}
```

实现要点：
1. `decimals` ∈ {0, 1, 2}；负数带 `-`；`-0.0` 归一为 `0`（避免出现 `-0.0`）。
2. **不使用 `String.format`**（common 不可用），也不使用 `kotlin.math.round`（会丢精度语义）→ 用 `kotlin.math.round(value * 10^decimals).toLong()` 做整数化。
3. 输出**永不带千分位、不带 locale 分隔符** → 德语环境下也输出 `1.5`。
4. 用 `buildString` 拼，不建中间列表。

**替换点（9 处）**：`CompositionAnalyzer:188`、`FeatureAdvisor:137-138/309`、`PromptEngine:152`、`Recommendation:63`、`LevelMath:41`、`VideoRecommender:328`、`HardwareProbe:36-37`（随 A1 搬迁）。
> `Recommendation.shutterLabel` 与 `VideoRecommender.shutterLabel` 都是 `"1/60"` / `"0.5s"` 形态，替换后**输出必须逐字不变**（有单测兜底，见 §5）。

### 2.2 改法二：日期格式化不走 `SimpleDateFormat`（解决 B4 的 `SimpleDateFormat` + `ThreadLocal`）

`TechniqueDex` 要的是 `yyyyMMdd`（例如 `20250601`）。两条路：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A · `expect/actual`**（推荐） | `commonMain` 声明 `expect fun formatYmd(millis: Long): String`；androidMain 用 `SimpleDateFormat`（保留现实现），iosMain 用 `DateFormatter` | ✅ 最省事、平台原生、无算法风险。缺点是 commonMain 有 1 个 expect |
| B · 手写 civil-from-days | Howard Hinnant 算法（~30 行，`daysFromCivil` / `civilFromDays`），纯 common，无 expect | ✅ 零 expect，但需要 20 个边界单测（闰年、世纪、时区） |

**建议：先 A 后 B。** Spike #0 用 A 快速跑通；等 commonMain 稳定后再考虑 B（避免手写日历算法引入隐蔽 bug）。**注意：无论 A/B，`ThreadLocal` 都要删掉** —— A 方案里 `actual` 实现可以直接用 `SimpleDateFormat`（Android 侧 `actual` 不用线程池，每次新建实例成本可忽略，`yyyyMMdd` 格式化极廉价）。

**时区口径必须钉死**：`yyyyMMdd` 是"设备本地日期"。A 方案在 Android 用默认时区、iOS 用 `TimeZone.current` → 口径一致。**B 方案要显式传时区偏移**，否则会出现跨时区差一天。

### 2.3 改法三：把时间改成注入（解决 C1 / C2）

| # | 现状 | 改法 |
|---|---|---|
| C1 `ScoreHistory.kt:153` | `System.currentTimeMillis()` 直接调用 | `ScoreHistory` 的公共 API 增一个 `now: Long` 参数（**与 `PromptScheduler` 的既有风格一致**）；`ScoreHistoryStore`（Android 侧）在调用点注入 `System.currentTimeMillis()` |
| C2 `TechniqueDex.kt:272` | `DexClock.today()` 内部取当前时间 | 拆成 `DexClock.today(now: Long)`；调用方（`DexStore`）注入 |

> 这条与 `PromptScheduler` 已经建立的"时钟靠入参、类内不读系统时钟"完全同构，**架构上已经是项目的既定风格，不是新发明**。而且它让 `commonTest` 能写确定性的时间测试。

### 2.4 改法四：抽 `DeviceProfile`（解决 A1 / A2）

沿用项目既有的「纯内核 + Android 侧 Store」分家模式：

```
新增  device/DeviceProfile.kt     （纯，commonMain）
        class LensInfo { ... }        ← 从 HardwareProbe.kt:14-39 原样搬
        class VideoCaps { ... }       ← 从 HardwareProbe.kt:42-77 原样搬
        class DeviceProfile { ... }   ← 从 HardwareProbe.kt:86-153 原样搬，但
                                         companion.unknown() 里的 Build.* 全部移出

新增  device/DeviceProfileFactory.kt（纯，commonMain）
        // 提供一个不依赖 Build 的构造入口，例如
        fun deviceProfile(brand, modelName, osLabel, androidRelease, androidSdk, ...)
        // iOS 侧用同一入口构造一份"伪 Android 档案"

修改  device/HardwareProbe.kt
        只保留 object HardwareProbe（Android-only），它负责探测后调用上面的构造入口
```

**副作用与连带改动（必须一起做，否则会静默出错）**：

| 连带 | 说明 | 处理 |
|---|---|---|
| `DeviceProfile` 的 `androidRelease` / `androidSdk` 字段名 | 语义是 Android 专属 | **不改字段名**（改名会波及 6+ 处调用点且无收益），在 KDoc 标注"iOS 侧填等价描述"。字段是 `String`/`Int`，iOS 填 `"iOS 17.4"` / `1704` 即可 |
| `DeviceProfile.unknown()` | 依赖 `Build.*` | 移到 Android 侧 `HardwareProbe.unknownProfile()`；iOS 侧由 Swift 提供等价工厂 |
| `DeviceProfile.brand` | `Brand` 枚举 | iOS 传 `Brand.OTHER` → `BrandGuide.hint()` 走 `else` 兜底文案 |
| `LensInfo.label` 的 `%.1f` | JVM 格式化 | 随搬迁换成 `NumFmt.fixed`（B9） |
| `BrandGuide.hasManualVideo(brand)` | `when(brand)` 的 `else -> true` | iOS 传 `OTHER` → 得到 `true`。**iOS 侧不需要"手动录像"这个概念**（视频页只出建议、不真实录制），但字段先留着，不做无谓改造 |

### 2.5 改法五：`AiVisionResult` + `AiScene` 下沉（D1 决策后的最终形态）

> **⚠️ 本节已按 D1（改用 Apple Vision，不引 ML Kit）重写。** 之前的设计把 `AiScene` 降级为 `String`，理由是"枚举住在 import 了 ML Kit 的文件里"。**这个理由不成立** —— 枚举本身是 8 行纯数据，可以单独搬走；**真正与平台绑定的是「标签词表」和「置信度阈值」，不是枚举**。PM-2 指出了这一点，是对的，改为更简单的方案。

```kotlin
// ── commonMain（纯 Kotlin，零 ML Kit、零 Vision） ──────────────────────
/** 场景分类。纯枚举，直接进 commonMain（D1 之后不再降级为 String）。 */
enum class AiScene(val label: String) {
    PORTRAIT("人像"), GROUP("合影"), LANDSCAPE("风景"), ARCHITECTURE("建筑"),
    FOOD("食物"), PET("宠物"), FLOWER("花草"), UNKNOWN("未识别")
}

class AiVisionResult(
    val scene: AiScene,          // ← 不再是 String
    val confidence: Float,
    val faceCount: Int,
    val faceArea: Float,
    val faceCenterX: Float,
    val faceCenterY: Float,
    val faceBox: FaceBox,
    val headEulerY: Float,
    val tags: List<String>       // 已翻译的中文标签（各平台各自翻译，≤6 个）
)
```

**分家边界（关键：什么进 common，什么留在平台侧）**

| 内容 | 归属 | 原因 |
|---|---|---|
| `AiScene` 枚举（含中文 label） | **commonMain** | 纯数据，8 行；两端共用同一套「场景 → 展示文案」口径 |
| `AiVisionResult` 数据类 | **commonMain** | 纯数据 |
| `FaceBox` | **commonMain** | 已是纯数据 |
| **标签 → `AiScene` 的映射表** | **平台侧**（Android: ML Kit 英文标签；iOS: Vision 标识符） | ★ **两套平台的词表天然不同**（ML Kit ~400 通用标签 vs Vision 1,303 层级标识符），映射表不可能共用 |
| **`decide()` 决策逻辑** | **平台侧** | 它读的是各自平台的标签集 |
| **置信度阈值** | **平台侧** | ★ **Vision 的置信度分布整体低于 ML Kit**（ML Kit 常见 0.98；Vision 常见 0.77 已是强结果），阈值必须独立标定 |

**因此 commonMain 的 ML Kit / Vision 依赖都是零**，而**转换层少了一层**（不再需要「枚举 → String → 枚举」的往返）。

**iOS 侧产出 `AiVisionResult` 的路径**：Vision 的 `VNDetectFaceRectanglesRequest` + `VNClassifyImageRequest` → **Vision 标识符映射表** → `AiScene` 枚举 → 填 `AiVisionResult`。映射表规格见 §10.2。

**这正是"端口-适配器"在 KMP 里的标准形态**：common 定义数据与规则，平台侧负责产出数据。

---

## 3. 模块与工程结构

### 3.1 Gradle 模块划分

```
shottuner/
├── shared/                      ← 新增：KMP 共享模块
│   ├── build.gradle.kts         ← kotlin("multiplatform")，androidTarget + iosX64/iosArm64/iosSimulatorArm64
│   └── src/
│       ├── commonMain/kotlin/com/shottuner/app/     ← 28 个纯文件（改完 §2 后类级闭包为 26 个）（改完 §2 后）+ 新增 text/NumFmt.kt
│       ├── commonTest/kotlin/                        ← 纯 JVM 逻辑单测（可直接沿用现有 121 个测试）
│       ├── androidMain/kotlin/                       ← 空（或仅 expect/actual 实现）
│       └── iosMain/kotlin/                           ← 仅 expect/actual 实现 + Swift 桥接
├── app/                         ← 现有 Android 模块，保留
└── ios/                         ← 新增：XcodeGen 工程（project.yml 为单一事实来源）
```

**共享模块的命名与包名**：包名沿用 `com.shottuner.app`，**不新建 `com.shottuner.shared`**。理由：现有 28 个文件的 `package` 声明与 `import` 全部是 `com.shottuner.app.*`，改包名等于 28 个文件 × N 行 import 的机械改动 + 全部 `import com.shottuner.app.databinding.*` 的连带风险。**包名一致 = 迁移时这 28 个文件一行都不用改 import**，只需要移动目录。

> `app` 模块的 `namespace` 已是 `com.shottuner.app` → `shared` 模块的 `namespace` 也设 `com.shottuner.app` 会冲突吗？**会**（Kotlin 允许多模块同 namespace，但 Android 侧 `com.shottuner.app.R` 与 `com.shottuner.app.databinding` 的归属会歧义）。
> **解法**：`shared` 模块用 `namespace = "com.shottuner.app.shared"`（仅影响它生成的 `R`/`BuildConfig`，commonMain 代码不引用这些），**Kotlin 包名仍写 `com.shottuner.app.*`**（Kotlin 包名与 namespace 无关）。这样 28 个文件一行不改。

### 3.2 XcodeGen 工程结构（不提交 `.xcodeproj`）

```yaml
# ios/project.yml（单一事实来源）
name: ShotTuner
options:
  bundleIdPrefix: com.shottuner
  deploymentTarget: { iOS: "16.1" }
  createIntermediateGroups: true
settings:
  base:
    SWIFT_VERSION: "5.9"
    MARKETING_VERSION: "3.1.0"      # 来自 version.properties
    CURRENT_PROJECT_VERSION: "1"     # iOS 独立递增
targets:
  ShotTuner:
    type: application
    platform: iOS
    sources: [ShotTuner]
    dependencies:
      - target: ShotTunerShared          # 由 Run Script 嵌入的 KMP framework
      - target: ShotTunerShareExtension   # 第二批才加
    info:
      path: ShotTuner/Info.plist
      properties:
        NSCameraUsageDescription: "..."
        NSPhotoLibraryAddUsageDescription: "..."
    entitlements: { path: ShotTuner/ShotTuner.entitlements, properties: { ... } }
    preBuildScripts:
      - name: "Embed KMP framework"
        script: |
          cd "$SRCROOT/.."
          ./gradlew :shared:embedAndSignAppleFrameworkForXcode
        basedOnDependencyAnalysis: false
```

**三个必须遵守的工程约定（PM-2 已提出，我确认并补充理由）**：

| 约定 | 补充理由 |
|---|---|
| `project.yml` 是单一事实来源，**不提交 `.xcodeproj`** | PR 冲突是 `.xcodeproj` 最典型的痛点；且**没有 Mac 的工程师也能改工程结构**（改 YAML 不需要 Xcode）——这是本项目最关键的一条 |
| KMP 集成用 **Run Script 直调 `embedAndSignAppleFrameworkForXcode`**（方案 B），不用官方 cocoapods 插件 | **D1 之后工程里根本不出现 CocoaPods**（Vision 是系统框架）→ KMP 集成链路只有 Gradle 一条，无第二套依赖管理；出问题好定位（`xcodebuild` 日志里能直接看到 gradle 输出） |
| CI 产出**未签名** ipa，本机侧载工具用 Apple ID 签名 | **Apple ID 绝不能进 CI secrets**。CI 里一旦有账号凭据，等于把"能签任意 App"的能力交给 CI 环境 |

### 3.3 KMP 与 Xcode 的集成时序（Run Script 方案 B 的实际流程）

```
CI / 本机：
  1. ./gradlew :shared:embedAndSignAppleFrameworkForXcode   （产出 shared.xcframework 到 build/xcode-frameworks/）
  2. xcodegen generate                                          （由 project.yml 生成 .xcodeproj）
  3. xcodebuild -project ShotTuner.xcodeproj -scheme ShotTuner -archive ...
  4. 从 .xcarchive 提取 .app → 去掉签名（去掉 _CodeSignature 与 embedded.mobileprovision）→ ipa
  5. 本机：SideStore / AltStore / Sideloadly 用自己的 Apple ID 签
```

> **D1 之后的简化（无 CocoaPods）**：整条链路**只有 Gradle 一条依赖管理路径**。原本要处理的「KMP × CocoaPods 插件 × ML Kit iOS」三方冲突**整条消失**：
> - 没有 `pod install`，没有 `Podfile.lock`，没有 CocoaPods 插件与 KMP 的集成坑；
> - `project.yml` 里不需要 `packages:` 段（Swift Package Manager 也不用 —— **Vision 是系统框架，`Package.swift` 可以不存在**）；
> - CI 门禁的「依赖白名单」退化为一条断言：**`ios/Package.swift` 不存在，或其 `dependencies` 为空**；
> - 二进制扫描的 `Frameworks/` 清单检查**只剩一个条目**：`ShotTunerShared.framework`（KMP 产物）。
>
> **新的头号风险从「集成冲突」变成「Vision 分类精度」**（见 §10.2 与 §7 K5）。

**已知代价（必须写进 CI 排查路径）**：
- Run Script 每次 Xcode 构建都会触发一次 gradle（即使代码没变）→ **构建变慢**。缓解：gradle 侧开 `org.gradle.caching=true` + 脚本里先判断 xcframework 是否存在且比源码新，**是则跳过 gradle 调用**。
- `embedAndSignAppleFrameworkForXcode` 会**改写 Xcode 的构建设置**（它自己写一个临时 xcconfig）。用 `xcodegen generate` 时要注意**不要让 XcodeGen 把这个设置覆盖掉** → xcconfig 的 include 要放在 `project.yml` 显式声明，且 Run Script 放在 `preBuildScripts`（早于 compile）。

---

## 4. commonMain 边界契约

### 4.1 三条硬规则

| 规则 | 内容 | CI 门禁 |
|---|---|---|
| **B1** | `commonMain` 不得出现 `import android.*` / `import androidx.*` / `import java.*` / `import javax.*`；不得出现 `String.format` / `.format(` / `System.currentTimeMillis` / `ThreadLocal` / `SimpleDateFormat` | 见 §6.1 门禁 G2/G3 |
| **B2** | `commonMain` 不得依赖任何**平台特定数据类**。平台数据（`DeviceProfile`、`AiVisionResult`）必须以**纯数据结构**进 common | 代码评审 + 架构文档 |
| **B3** | `commonMain` 只允许 `expect` 声明，**不允许 `actual` 实现**（actual 在平台源集） | 编译期天然保证 |

### 4.2 必须进 commonMain（改完之后）

| 分组 | 文件 |
|---|---|
| 技法内容 | `knowledge/` 全部 8 个（`Technique` / `PhotographyBook` / `TechniqueBookV15` / `TechniqueMatcher` / `PromptEngine` / `PromptScheduler` / `PromptSignals` / `VideoMovePlanner`） |
| 评分与构图 | `analysis/ScoreBoard.kt` / `analysis/CompositionAnalyzer.kt` / `analysis/SharpnessMeter.kt` / `analysis/SceneClassifier.kt` / `model/CompositionHint.kt` / `model/FrameStats.kt` |
| 参数推荐 | `recommend/ParamRecommender.kt` / `model/Recommendation.kt` / `model/ManualParams.kt` / `model/CameraCaps.kt` |
| 功能建议 | `feature/FeatureAdvisor.kt` / `coach/FramingCoach.kt` |
| 成长 | `growth/` 6 个（`TechniqueDex` / `DexEntry` / `MissionEngine` / `MissionTask` / `TaskPicker` / `ScoreHistory`） |
| 设备档案（**新增抽取**） | `device/DeviceProfile.kt`（`LensInfo`/`VideoCaps`/`DeviceProfile`） + `device/Brand.kt` |
| 传感器数学 | `sensors/LevelMath.kt` |
| 录像建议 | `video/VideoRecommender.kt` |
| **新增** | `text/NumFmt.kt`（locale 无关格式化） |

### 4.3 绝不能进 commonMain（平台层）

| 文件 | 平台依赖 | iOS 对应实现 |
|---|---|---|
| `analysis/AiVision.kt` | ML Kit | Vision 框架（`VNDetectFaceRectanglesRequest` + `VNClassifyImageRequest`） |
| `analysis/FrameAnalyzer.kt` | CameraX `ImageProxy` / YUV 平面直读 | `AVCaptureVideoDataOutput` + `CVPixelBuffer` |
| `camera/CameraEngine.kt` | CameraX + Camera2Interop | `AVCaptureSession` |
| `device/HardwareProbe.kt` | `CameraManager` / `CameraCharacteristics` | `AVCaptureDevice.formats` / `activeFormat` |
| `device/OrientationTracker.kt` | `SensorManager` | `CoreMotion` |
| `sensors/LevelSensor.kt` | `SensorManager` | `CoreMotion` |
| `diag/Diag.kt` | `android.util.Log` + 文件 IO | `os_log` + `FileManager` |
| `growth/DexStore.kt` / `growth/ScoreHistoryStore.kt` | `SharedPreferences` / 文件 | `UserDefaults` / `Application Support` |
| `ui/*` / `screen/*` / `tile/*` / `share/*` | Android View / WindowManager | SwiftUI |

### 4.4 `expect/actual` 清单（**v1.1 修订：最终数量为 0**）

> ⚠️ **本节已被 §11.5 取代。** 原设计列了 2 个 `expect/actual`（`currentTimeMillis` + `formatYmd`）；**采纳 architect-2 的方案后两个都不需要了**：
> - `DexClock`（SimpleDateFormat + Date + Locale + **ThreadLocal**）**只被 Android 层的 `growth/DexStore.kt` 使用**（`:136/:173/:216`）→ **整块搬进 `androidMain`**，common 侧 `TechniqueDex` 保持纯算法 → 不需要 `formatYmd`；
> - `ScoreHistory` 的时间改成**注入 `now: Long`**（与 `PromptScheduler` 既有风格同构）→ 不需要 `currentTimeMillis`。
>
> **最终：`expect/actual` 数量 = 0。**
>
> | 内容 | 最终归属 |
> |---|---|
> | `object DexClock`（含 `ThreadLocal`） | **androidMain**（`shared/src/androidMain/.../growth/DexClock.android.kt`） |
> | `TechniqueDex` 的纯算法 | commonMain（无任何日期逻辑） |
> | `ScoreHistory` 的时间 | commonMain + **注入 `now: Long`** |
>
> **纪律不变**：**能用入参解决的绝不用 expect**。每个 `expect` 都要生成一套平台代码（PRD R6 已把 Kotlin/Native 编译慢列为中高风险）→ **降到 0 是白拿的收益**。

---

## 5. 迁移顺序（每步都有可验收的产出）

> 原则：**先让 commonMain 编得过，再改行为**。任何一步都不能"边搬边改逻辑"，否则出问题无法二分定位。

### Spike #0 · commonMain 可编译性最小实验（1 人日，**第一批开工第一周**）

| 步 | 动作 | 验收 |
|---|---|---|
| 0.1 | 建 `shared` 模块（KMP，androidTarget + iosArm64 + iosSimulatorArm64），把 28 个纯文件原样**复制**进去（先不动 Android 侧） | `./gradlew :shared:compileKotlinIosSimulatorArm64` **报错清单 = 预期清单**（§1 的 13 处），不多不少 |
| 0.2 | 改 A 类：抽 `device/DeviceProfile.kt`，`HardwareProbe` 只留 object | 0.1 的报错里 `DeviceProfile` / `VideoCaps` / `LensInfo` 三类 **unresolved reference 消失** |
| 0.3 | 改 B 类：新增 `text/NumFmt.kt`，替换 9 处格式化 | 0.1 的报错里所有 `format` 相关 **unresolved reference 消失** |
| 0.4 | 改 B4：`SimpleDateFormat` → `expect/actual`，删 `ThreadLocal` | `TechniqueDex` 编译通过 |
| 0.5 | 改 C 类：`now` 注入，`System.currentTimeMillis` 清零 | `grep -rn "System.currentTimeMillis" shared/src/commonMain` **零命中** |
| 0.6 | 把现有 121 个单测中**纯逻辑的那部分**搬进 `commonTest` | `commonTest` 全绿（**这一步是"移植没改变行为"的唯一硬证据**） |
| 0.7 | 加 CI 门禁 G2/G3（§6.1） | 故意提交一条违规代码 → CI 必须红 |

**Spike #0 的产出**：一份 1 页结论 + 一个可编译的 `shared` 模块。**这是整个 iOS 路线成立与否的第一个可证伪点**，且完全不依赖 Mac、不依赖出包、3 分钟就能跑一次。

### 第一批 · 端口-适配器改造（与 Spike #0 串行）

| 步 | 动作 | 验收 |
|---|---|---|
| 1.1 | 定义 `AiVisionResult` 下沉 + Android 适配层（ML Kit → 纯结构） | Android 端**零行为变化**，121 单测全绿 |
| 1.2 | `DeviceProfile` 的 Android 探测结果 → 纯结构；iOS 侧实现同等探测 | 两端 `DeviceProfile` 字段一一对应（列一张对照表） |
| 1.3 | iOS 侧 `AVCaptureSession` + `FrameStats` 生产（YUV/像素缓冲 → 与 Android 等价的统计量） | **同一张测试图，iOS 与 Android 算出的 `FrameStats` 差异 < 2%**（这是评分基线一致性的基础，见 PRD R2） |
| 1.4 | iOS 侧 `ScoreBoard` / `CompositionAnalyzer` / `PromptEngine` 全部走 common | iOS 复用现有单测 |
| 1.5 | iOS 侧相机/落盘/隐私门/UI（SwiftUI） | PRD §5.4 第一批验收标准 |

### 第二批 · 扩展能力（前置：Spike #1 的 App Group / Extension 存活结论）

见 PRD §5.4 第二批范围 + §3 Live Activity + §4 Widget。**架构上只有两条新增约束**：
1. **Live Activity 不依赖 App Group**（PRD 已定）→ 它的数据源只能是 `ActivityKit.ContentState`，而 ContentState 必须可序列化 → **ContentState 里只能放 `Codable` 的基础类型**，不能放 Kotlin 对象（除非走 `NSCoding` 手工桥接，**建议不用**）。这意味着 Live Activity 需要的字段必须**在 Swift 侧有一个扁平结构**，由 common 层的输出转换而来 → **common 要提供"扁平快照"的转换函数**（例如 `SnapshotDto`），而不是让 Swift 直接消费 common 的领域对象。
2. **Widget 的分层降级**（Tier 1 App Group / Tier 0 深链）→ 同样需要一份扁平快照，且 **App Group 不可用时不能崩**（读不到就显示"去打开 App"）。

> **给 architect-2 的接口建议**：common 层最好提供一个 `CoachSnapshot` 扁平 DTO（taskTitle / action / score / scoreLabel / hintLine / 4 维分数 / 3 个建议），由 common 生成、Swift/Kotlin 各自转成平台原生格式。这样 Live Activity / Widget / Watch / 控制中心四个扩展**共用同一份数据**，不用各写一遍转换，也避免了 `Codable` 序列化 Kotlin 复杂对象的问题。

---

## 6. CI 门禁设计

> 目标：把"没有 Mac、每次出包等 5–10 分钟"这个约束的代价压到最低。**能在 3 分钟内发现的错误，绝不让它活到出包那一步。**

### 6.1 三档门禁

| 门禁 | 跑在 | 内容 | 耗时 | 失败后果 |
|---|---|---|---|---|
| **G1 · 静态合规** | push / PR | ① 零网络 grep（禁 `URLSession` / `URLRequest` / `Network` / `CFNetwork` / `NWConnection` / `WebSocket` / `URL(` / `NSURLConnection`）；② 依赖白名单比对（`Podfile` / `Package.swift` / `build.gradle.kts` 新增项必须显式加白） | < 30s | 阻断合并 |
| **G2 · commonMain 可编译性** | push / PR | `./gradlew :shared:compileKotlinIosSimulatorArm64`（**只编 commonMain + iosMain，不出包**） | 3–5 min | 阻断合并 |
| **G3 · commonTest** | push / PR | `./gradlew :shared:iosSimulatorArm64Test` + Android 单测 | 3–8 min | 阻断合并 |
| **G4 · 完整出包 + 二进制扫描** | `workflow_dispatch` / tag | ① 完整 xcodebuild 出未签名 ipa；② **二进制符号扫描**（`nm` / `otool` 扫 `CFNetwork` / `NSURLSession` / `GoogleDataTransport` / `FBSDK` 等）；③ 依赖清单落档 | 10–20 min | 阻断发布 |

### 6.2 「commonMain 可编译性检查」的具体做法（PM-2 提的，我给实现要点）

```bash
# 只编 iOS 侧、不出包、不需要 Mac
./gradlew :shared:compileKotlinIosSimulatorArm64 --console=plain
```

**为什么它是最值钱的一道门**：
- Kotlin/Native 编译**比 JVM 慢**，但**只编 commonMain + 平台源集、不链接、不打包**，通常在 3 分钟内；
- 它挡住的是最常见、也最贵的失败：**"以为这个文件是纯的，搬进去才发现它 import 了 Android 类"** —— 这类错误在出包后才发现，代价是一次 10–20 分钟的 CI + 一轮排查；
- 它对**没有 Mac** 的人特别友好：不需要 Xcode、不需要签名、不需要 macOS runner —— **Linux runner 就能跑**（Kotlin/Native 交叉编译不需要 macOS）。

**配套加一条"commonMain 纯净性"检查（比编译更快，秒级）**：

```bash
# 任何一条命中即失败
grep -rnE "^import (android|androidx|java|javax)\." shared/src/commonMain   # 禁止 import
grep -rnE "System\.currentTimeMillis|String\.format|\.format\(|ThreadLocal|SimpleDateFormat" shared/src/commonMain
```

> 注意第二条的误报：`PromptScheduler` 的**注释里**就写着 `System.currentTimeMillis`（"内部一次都不许调"）→ **必须排除注释行**，否则这道门会立刻误红并教会团队忽略它。实现上用"去掉 `//` 与 `*` 开头行后再 grep"，或者干脆把这条从 grep 换成编译期检查（编译期更准，因为它只管真实引用）。

### 6.3 零网络门禁的两个注意点

1. **Android 的 `tools:node="remove"` 兜底在 iOS 没有等价物**。iOS 侧只能靠"代码层不调用 + 依赖白名单 + 二进制扫描"三道。**其中二进制扫描是唯一能发现"某个第三方 SDK 偷偷带了网络栈"的手段** —— 因为 ML Kit → `datatransport` → `GoogleDataTransport` 这条链就是这么进 Android 的（Android 侧靠显式 remove 挡住，iOS 侧没有这层兜底）。**所以 G4 的二进制扫描不是形式主义，是 iOS 版的最后一道防线。**
2. **grep 要覆盖 `import` 之外的形式**：Swift 里 `URLSession.shared.dataTask` 不需要 import（`Foundation` 已导入），所以只查符号名不够。扫描清单至少包括：`URLSession` / `URLRequest` / `NSURLConnection` / `CFNetwork` / `Network`（import Network）/ `NWConnection` / `WebSocket` / `Data(contentsOf:)`（URL 形式）/ `CFStream`。

### 6.4 版本号单一来源（PM-2 提的，我确认并补充）

`version.properties`：
```properties
marketing.version=3.1.0
android.versionCode=31
ios.buildNumber=1
```
- **营销版本两端严格一致**（跨端认知一致，PRD §1.2）；
- **`ios.buildNumber` 从 1 独立起算**，不复用 Android 的 versionCode 序列 —— 否则会出现「iOS build 31」这种跨平台误读。PRD §8.4 的判断是对的。

---

## 7. 风险与降级

| # | 风险 | 概率 | 降级路径 |
|---|---|---|---|
| K1 | **commonMain 编不过，且原因不是 API 而是依赖结构**（A 类） | **高**（已确认存在） | Spike #0 第一天就会暴露。降级：**先只搬不依赖 `DeviceProfile` 的子集**（技法库 8 + 评分 6 + 调度 4 ≈ 18 个文件），相机/设备档案相关（`FeatureAdvisor` / `VideoRecommender` / `ParamRecommender`）放到第二批 iOS 侧重写。→ **代价：iOS 第一批的"功能页/录像页"要自己写建议逻辑**，比复用多花 1–2 人日，但能保证第一批按时出包 |
| K2 | Kotlin/Native 编译慢，拖慢迭代（R6） | 中高 | ① `expect` 控制在 2 个（§4.4）；② `commonMain` 不放任何"重计算"（图像统计在平台层做，common 只做算术）；③ CI 分层（§6.1），G2/G3 在 Linux runner 上跑，不占 macOS runner |
| K3 | iOS 评分基线与 Android 不一致（R2，PRD 已标高） | **高** | ① 阈值**独立存放**，不污染 Android 常量（PM-2 已强调）；② iOS 侧首屏必须做基线标定（P0-I11）；③ 标定前，iOS 端**只显示「参考分 --」而不是假数字** —— 与 Android 的"未测不显示数字"口径一致 |
| K4 | App Group / Extension 在免费账号侧载下被剥离（R1） | 高 | PRD 已定：Live Activity 不依赖 App Group；Widget 分层降级。**架构上再补一条**：所有扩展只消费 §5 建议的 `CoachSnapshot` 扁平 DTO，**不直接读 App Group 里的复杂结构** → 剥离时最多数据为空，不会崩 |
| K5 | ~~ML Kit iOS 的 CocoaPods 与 KMP 集成冲突（R5）~~ | **已由 D1 消解** | **Vision 是系统框架 → 工程里零 CocoaPods、零第三方 Swift 包、零 `.cinterop`**。commonMain 的 ML Kit/Vision 依赖都是零（§2.5），KMP 集成只有 Gradle 一条链路。**残留风险改为「Vision 分类精度」**，缓解见 §10.2（真机采集映射表 + 独立阈值 + ≤10fps 节流 + 模拟器不可用需真机） |
| K6 | Kotlin runtime 静态链接导致 iOS 包体积大（R7） | 中 | ① 只编 `commonMain` 里真正需要的部分，不要整仓塞；② `shared` 的 `device/Brand.kt`（品牌枚举）在 iOS 只用 `OTHER`，仍要编进去（枚举整体编），但体积可忽略；③ 验收时对比体积，若超 Android 版 1.5 倍，先查"是不是把 Android 的整个 app 模块编进去了" |
| K7 | 跨语言调试体验弱（R8） | 中 | ① **核心逻辑全在 common（Kotlin），能用 JVM 跑单测** —— 80% 的缺陷在 commonTest 就能发现，不需要 Xcode；② 只把"相机取帧/AI 推理/UI"留在 Swift，这部分用 Xcode 调试；③ 建议在 CI 里加一个"用固定输入图片 → 跑 commonTest → 比对快照"的回归门禁 |
| K8 | 出包链路过长，代码写完却没在真机跑过（R12） | 中 | ① **侧载自用 3–7 天**作为第一批与第二批之间的硬边界（PRD 已定）；② CI 出未签名 ipa，本机侧载工具签名 → **每天可以自己装一次**，不等 CI 全量出包 |

---

## 8. 与 `software-architect-2` 预研报告的分工边界

> 我把边界写清楚，避免两份文档打架。architect-2 负责 #16「技术可行性预研报告」。

| 议题 | 归属 | 说明 |
|---|---|---|
| KMP 是否可行、工具链版本矩阵、CI runner 选型、Mac 获取方案 | **architect-2** | 我不重复 |
| commonMain 的**内容边界与依赖抽取**（本文 §1–§4） | **我** | architect-2 的预研是"能不能做"，我是"怎么做" |
| `expect/actual` 清单、commonTest 迁移范围（§4.4 / §5.0.6） | **我** | |
| CI 门禁的**具体实现**（§6） | **我**（architect-2 提供 runner 层结论） | |
| **iOS 评分基线标定**（PRD P0-I11） | architect-2 或 QA | 需要真机采样，属实验任务，不是架构 |
| **App Group / Extension 存活验证**（Spike #1） | architect-2 / 工程师 | 需要真机侧载，属实验任务 |

**我给 architect-2 的一句话交接**：预研结论无论正负，**Spike #0（commonMain 可编译性）都值得先做**——它不依赖 Mac、不依赖出包、3 分钟一次，而且能独立证伪"这 28 个文件能不能进 commonMain"。若预研结论是"KMP 不可行"，Spike #0 的产出仍然有价值（它等于一次高质量的依赖解耦重构）。

---

## 9. 给 PM 与团队的 5 条修正 / 确认

| # | 内容 | 需要谁改 |
|---|---|---|
| 1 | **PRD §0 第 1 条的"1 个文件带 JVM 专属 API"改为"10 个文件、13 处调用、4 类 API"** | product-manager-2 |
| 2 | **PRD 应新增 A 类阻塞清单**（不是一条，是**三条**）：`DeviceProfile`/`VideoCaps`/`LensInfo` 定义在 Android 文件里（`FeatureAdvisor`/`VideoRecommender` 依赖它）+ **`AiScene`/`AiVisionResult` 定义在 `analysis/AiVision.kt`（`SceneClassifier` 同包依赖它）** → 类级闭包 L2 见 §11.2 | product-manager-2 |
| 3 | `org.json` 确实未使用（PM 正确），但**要写明"是因为 `ScoreHistory` 手写了 JSON 序列化"**——这在 iOS 侧是直接可复用的资产，不是障碍 | 记录 |
| 4 | iOS 侧**不要引入 `kotlinx-datetime`**：`TechniqueDex` 的日期需求只有 `yyyyMMdd` 一个格式，用 `expect/actual` 两行解决。引入新依赖会让"零新依赖"这条纪律破功，也会给二进制扫描添变数 | 团队共识 |
| 5 | **common 层应提供一个扁平 DTO（如 `CoachSnapshot`）供 Live Activity / Widget / Watch 共用** —— 这条 PRD 里没有，但缺了它，四个扩展会各写一遍转换，且 ContentState 序列化会碰到 Kotlin 复杂对象 | 我 + product-manager-2 |

---

## 10. 补充规格（与 PM-2 对齐后追加）

### 10.1 可移植口径的权威定义（唯一正确版本）

**两级口径，必须分开引用：**

| 口径 | 定义 | 数字 | 用途 |
|---|---|---|---|
| **L1 · 文件级** | 文件自身没有 `import android.*` / `import androidx.*` | **28 个 / 8,075 行** | 只用于说明"哪些文件看起来是纯的"，**不能用来估工作量** |
| **L2 · 类级传递闭包** ★ | 文件自身无 android import，**且它引用的每个项目内类也都满足此条件（递归，★ 含同包引用）** | **以 §11.2 为准**（本文撰写时为 26 个 / 7,336 行，**代码推进后已变为 27 个 / 7,786 行**） | **唯一正确的可移植口径，所有排期都用这个**。⚠️ 同包引用不需要 import → **只扫 import 会漏**（本文与 architect-2 各漏过一次） |

**L2 被排除的恰好 2 个文件**：

| 文件 | 行数 | 依赖了 Android 文件里的类 |
|---|---|---|
| `feature/FeatureAdvisor.kt` | 311 | `com.shottuner.app.device.DeviceProfile` → 定义在 `device/HardwareProbe.kt`（import 6 个 `android.*`） |
| `video/VideoRecommender.kt` | 428 | 同上 |

8,075 − 311 − 428 = **7,336** ✓

**为什么"按文件扫描"一定会算错（这个坑值得写进代码评审规范）**：
Kotlin 的 `import com.shottuner.app.device.DeviceProfile` 解析到的是**类**，而 `DeviceProfile` 这个类**并不在 `DeviceProfile.kt` 里**（它定义在 `HardwareProbe.kt` 里）。所以：
- 按"import 的包路径 → 同名文件"去找定义 → **找不到 → 误判为无污染**；
- 按"文件路径"扫描 → 同样数不出来；
- **必须先建立"类名 → 定义它的文件"的映射**（扫 `class/object/enum class` 声明），再解析 import。

> 这就是 PM-2 说的"我第一版脚本也漏了，改按类名映射后才报出 2 个不可移植文件"。**两条独立路径（我的扫描 + PM 的复现）得出同一结论，这条可以定案。**

**KMP 改造量：3–4 人日**（不是 1 天）。

### 10.2 `AiScene` 与「平台标签 → `AiScene`」映射（D1 之后 J7 的唯一新增工作量）

#### 10.2.1 枚举本身：直接进 commonMain

| `AiScene` | 中文 label（common 内） | Android 现状来源 | iOS（Vision）来源 |
|---|---|---|---|
| `PORTRAIT` | 人像 | 人脸检测命中且面积 ≥1.2% | `VNDetectFaceRectanglesRequest` 命中 |
| `GROUP` | 合影 | 人脸数 ≥ 3 | 人脸数 ≥ 3 |
| `LANDSCAPE` | 风景 | `decide()`：sky/nature 标签 | Vision 标识符族 `sky` / `nature_*` |
| `ARCHITECTURE` | 建筑 | `decide()`：building 标签 | Vision 标识符族 `building*` / `structure*` |
| `FOOD` | 食物 | `decide()`：food 标签 | Vision 标识符族 `food*` / `dish*` |
| `PET` | 宠物 | `decide()`：pet 标签 | Vision 标识符族 `animal*`（哺乳/鸟类/爬行） |
| `FLOWER` | 花草 | `decide()`：flower 标签 | Vision 标识符族 `plant*` / `flower*` |
| `UNKNOWN` | 未识别 | 兜底（置信度 0.30） | 兜底 |

**现状映射（`analysis/SceneClassifier.kt:46-48`）保持不变**：`PORTRAIT/GROUP → SceneType.PORTRAIT`、`LANDSCAPE/ARCHITECTURE → LANDSCAPE`、`FOOD/PET/FLOWER → GENERAL`。**iOS 侧必须复用同一张映射表**（这也是 common 化的意义：映射只在 `SceneClassifier` 一处）。

#### 10.2.2 两套映射表的关系：**语义对齐，不是字面对齐**

| 平台 | 标签词表 | 规模 | 匹配方式 |
|---|---|---|---|
| Android | ML Kit `ImageLabeler` 英文标签（`sky`/`cloud`/`mountain`/…） | ~400 通用标签 | **全等匹配**（现有 `AiVision.kt:260-294` 的 7 个集合 `SKY`/`NATURE`/`PEOPLE`/`BUILDING`/`FOOD`/`PET`/`FLOWER`），已上线，不动 |
| iOS | Vision `VNClassifyImageRequest` 标识符 | **1,303 标识符的层级词表**（Revision 1，多标签网络） | **前缀匹配**（见下） |

**iOS 侧的映射规则（我的设计，三条）**

1. **按标识符族做前缀匹配，不做全等** —— Vision 词表是层级树，标识符形如 `animal_mammal_dog`、`food_dish`、`sky_cloud`。**全等匹配会因为 Vision 的一次 revision 变化就整片失效**；前缀匹配（`animal_` → PET）则只增不改。
2. **多标签必须显式聚合，不能取 `results[0]`** —— Vision 是多标签网络，`results[0]` 往往是最泛化的标签（如 `object`/`scene`），直接取第一个会把"一只狗在草地上"判成"风景"。**规则：遍历全部结果 → 命中映射表 → 取置信度最高的那个 → 再与 `faceCount` 规则（人脸优先）仲裁。**
3. **阈值必须独立标定，且不能照搬 Android 的 `0.5f`** —— `AiVision.kt:96-99` 的 `setConfidenceThreshold(0.5f)` 是 ML Kit 的 ImageLabeler 选项，**在 Vision 上会静默返回空结果**（Vision 的强结果常见 0.77 已是高分，但其分布整体低于 ML Kit 的 0.98 级）。iOS 侧用自己的阈值（起始建议 **0.25**，真机标定后固化）。

#### 10.2.3 ★ 映射表不凭记忆写，用「真机采集 → 固化 → 锁测试」

> **采集窗口**：这一整套流程落在 **IOS-ARCH-EXEC 的 T22 · CAL-1**（第一批里唯一有 clover iPhone 在手的侧载自用期）。**T11 只验「机制通」，映射准不准由 T22 验收 C 判定。**

**我不建议直接手写 Vision 的 1,303 个标识符**：词表随 Vision revision 变化，凭记忆写必然埋雷。正确做法三步：

| 步 | 动作 | 产出 |
|---|---|---|
| ① **采集** | 真机跑一次 `VNClassifyImageRequest`，对一批**覆盖 8 个场景的样张**（人像/合影/风景/建筑/食物/宠物/花草/空镜），把 `results` 的 `(identifier, confidence)` 全部打到日志并落文件 | 一份原始标识符清单 |
| ② **归族** | 按 §10.2.2 的前缀规则，把命中的标识符归入 8 个 `AiScene` 族；**只保留真实出现过的标识符**（不做全集） | `VisionSceneMap`（Swift 侧常量表，或 common 侧的纯数据表 + 一份映射函数） |
| ③ **锁死** | ①的样张 + 期望 `AiScene` 进 `commonTest`（或 iOS 侧单测），断言映射结果与置信度区间 | **以后换 Vision revision，只需重跑 ①并更新表，不会静默改行为** |

**降级路径**：若某标识符未归族 → 计入 `UNKNOWN`；若整张图无命中 → `UNKNOWN`（与 Android 现状的置信度 0.30 兜底一致）。

#### 10.2.4 三条 iOS 实现约束（PM 已查证并写入 PRD §7.2，我确认并落到实现规格）

| 约束 | 影响 | 实现要求 |
|---|---|---|
| **`VNClassifyImageRequest` 模拟器不可用** | QA 验收**必须真机** | 「分类无结果」在模拟器上是**预期行为不是 bug**；测试计划里要显式标注，避免误判为回归 |
| **`perform(_:)` 是阻塞调用**（老机型 50–300ms） | 不能在主线程调 | ① 放**后台队列**；② **≤10fps 节流**，与 1Hz 的评分管线**解耦**（分类结果只用于场景判定，不参与逐帧统计）；③ 连续调用时丢弃上一帧未完成的结果（同 `AiVision.busy` 的既有策略） |
| **Vision 置信度分布与 ML Kit 不同** | 阈值不能照搬 | 独立阈值 + 真机标定（§10.2.2 规则 3） |

#### 10.2.5 下沉后的 `AiVisionResult` 字段规格（与现有 `analysis/AiVision.kt` 逐字段对齐）

| 字段 | 类型 | 备注 |
|---|---|---|
| `scene` | `AiScene` | **枚举直接进 commonMain**（D1 之后不再降级为 String） |
| `confidence` | `Float` | 0..1 |
| `faceCount` | `Int` | |
| `faceArea` | `Float` | 最大人脸占画面面积比 0..1 |
| `tags` | `List<String>` | 已翻译的中文标签，最多 6 个；**各平台各自翻译**（common 不做翻译表） |
| `ok` | `Boolean` | 本轮是否成功出结果 |
| `faceCenterX` / `faceCenterY` | `Float` | 归一化 0..1，默认 0.5 |
| `faceBox` | `FaceBox` | `left/top/right/bottom` 归一化 0..1 |
| `headEulerY` | `Float` | 头部绕 Y 轴旋转角（度） |

### 10.3 Spike #1 验证脚本（App Group / Extension 存活）

> 前置：Spike #0 通过。目的：**在投入第二批之前**判定「免费 Apple ID 侧载后，App Extension 与 App Group 是否还活着」。
> 产出：1 页结论，直接决定第二批 8 项里的 5 项是否作废。

```bash
#!/usr/bin/env bash
# tools/spike/spike1-extension-survival.sh
# 在 iOS 设备上通过 SideStore 装好壳 App 后执行（或用 CI 出的壳包在 Mac 上装）
set -euo pipefail
APP_BUNDLE_ID="${1:?用法: spike1-extension-survival.sh <bundleId>}"
APP_GROUP_ID="${2:?用法: spike1-extension-survival.sh <bundleId> <groupId>}"
OUT="spike1-result-$(date +%Y%m%d-%H%M%S).txt"

# ---- A. Extension 是否存活 ----
# 方法：枚举系统已注册的 extension bundle id（可在 App 内用 NSExtension 枚举，或用
# `pluginkit -m -A -D -i <bundleId>` 在 macOS 侧查；设备侧用下面的代码在 App 内自检）
cat > /tmp/Spike1Probe.swift <<'SWIFT'
import Foundation
// 列出本进程可见的 extension point（App Group 存活时 widget 扩展会被注册）
let eps = NSExtensionPointIdentifier._allExtensions?.compactMap { $0.rawValue } ?? []
print("registered_extension_points=\(eps.count)")
print(eps.joined(separator: "\n"))
// App Group 容器是否可用
let url = FileManager.default.containerURL(
    forSecurityApplicationGroupIdentifier: ProcessInfo.processInfo.environment["SPIKE_GROUP"] ?? "")
print("app_group_container=\(url?.path ?? "nil")")
SWIFT

echo "== A. Extension / App Group 探测 ==" | tee "$OUT"
echo "app_bundle_id=$APP_BUNDLE_ID" | tee -a "$OUT"
echo "app_group_id=$APP_GROUP_ID" | tee -a "$OUT"

# ---- B. 判定标准（人工确认 + 记录）----
cat <<'RULE' | tee -a "$OUT"
判定标准：
  A1 Extension 存活：设备「设置 → 通用 → 后台 App」里能看到本 App 的扩展，
     且 Widget 库中能添加出小组件            → PASS / FAIL
  A2 App Group 保留：app_group_container 不为 nil → PASS / FAIL
  A3 group id 是否被重写：实际 id 与预期不一致 → REWRITTEN
  结论：
    A1 PASS 且 A2 PASS → 第二批 8 项全部保留
    A1 PASS 且 A2 FAIL → Widget 走 Tier 0 降级；Live Activity 不受影响（不依赖 App Group）
    A1 FAIL          → 第二批的 J1/J2/J4/J5 降级为 P2，只保留 Action Button /
                       控制中心 / Spotlight（这三项不需要 Extension）
RULE
echo "（结果需人工填写本文件后归档到 docs/）"
```

**配套的壳工程要求**（给工程师，不是脚本）：空壳 App + 一个空 Widget Extension + 一个声明了 App Group 的 entitlements，三者一起侧载。**不要用完整 App 做这个验证** —— 完整 App 的失败原因会混进来。

> **CI 门禁脚本的位置**：完整脚本在 **`docs/IOS-ARCH-EXEC.md` §4**（`ci-linux.yml` / `check-common-purity.sh` / `check-no-network.sh` / `check-apple-credentials.sh` / `scan-binary.sh` 全文）。本文不重复。

### 10.4 CI 门禁脚本的索引与「与 PRD §7.2 的一致性订正」

**脚本位置**：完整脚本在 **`docs/IOS-ARCH-EXEC.md` §4**（`ci-linux.yml` / `check-common-purity.sh` / `check-no-network.sh` / `scan-binary.sh`）。本文不重复。

**⚠️ 必须与 PRD §7.2 对齐的一处订正（PM 原文写"出包二进制扫 `CFNetwork`/`NSURLSession`/`GoogleDataTransport` 等符号"，其中前两个会造成必然误报）**：

| 层 | 判据 | 为什么要放在这一层 |
|---|---|---|
| **L1 源码层（主力）** | grep `URLSession` / `URLRequest` / `NSURLConnection` / `CFNetwork` / `NWConnection` / `WebSocket` / `Data(contentsOf:)` | ✅ 只有源码层能区分"调用了"与"框架自带的"。**PRD §7.2 第 2 条（代码层零网络调用）本来就是主力** |
| **L2 配置层** | `Info.plist` 的 `NSAppTransportSecurity` 未放宽；entitlements 无 `com.apple.security.network` | ✅ 零网络承诺的**声明层**证据 |
| **L3 供应链层（二进制）** | 只扫**第三方遥测血统**符号：`GoogleDataTransport` / `GTMSessionFetcher` / `FIRApp` / `FirebaseCore` / `GoogleUtilities`；以及 `Frameworks/` 目录清单（第一批只允许 `ShotTunerShared.framework`） | ✅ **不要扫 `NSURLSession` / `CFNetwork` 符号 —— Foundation 永远链着这两个，必然误报**。判"有没有第三方 SDK"要看 Frameworks 清单和第三方专属符号 |

> **结论：PRD §7.2 的"零网络"三层结构本身是对的**（源码 grep + 依赖白名单 + 二进制扫描），**只有第 4 条里"扫 `CFNetwork`/`NSURLSession` 符号"这一项需要落到 L1 而不是 L3**。**判定条件"一致"可以做到，但实现位置必须分清** —— 否则这条门禁会在第一次跑就红，而**红一次的 CI 门禁等于没有门禁**。

### 10.5 Q6 决策（两端一起修）在设计里的落点

**决策**：common 侧 `text/NumFmt.kt` **同时服务两端**，Android 侧 9 处调用点一并替换。

| 项 | 落点 |
|---|---|
| `NumFmt` 的位置 | `shared/src/commonMain/kotlin/com/shottuner/app/text/NumFmt.kt`（**T01 之后**才能进 common；但在 T01 之前，Android 侧可以直接建 `app/src/main/java/com/shottuner/app/text/NumFmt.kt`，KMP 接线时原样搬过去） |
| 两端共用的前提 | **Kotlin 包名沿用 `com.shottuner.app.*` 不改** → Android 侧的 `import com.shottuner.app.text.NumFmt` 与 iOS 侧完全一致，**不需要任何条件编译** |
| API 形态 | 只暴露**无 locale 依赖**的定点格式化：`fixed(value, decimals)` / `pct(value)`。**不提供** `format(locale, ...)` 这类入口 —— 一旦提供，调用方就会传 `Locale.getDefault()`，bug 原样复现 |
| **必加的回归单测** | ① 显式指定 `de-DE` / `fr-FR` Locale 时输出仍为 `1.5`（**不是 `1,5`**）；② `-0.0` → `0`；③ `decimals = 0/1/2` 的进位与舍入；④ `0.05` → `0.1`；⑤ 与替换前的输出**逐字相同**（对 `Recommendation.shutterLabel` / `VideoRecommender.shutterLabel` / `PromptEngine.pct` 三处） |
| QA 验收项（PM 已提出，我确认） | 设备切德语/法语，检查**曝光档位「1.5 档」/ 快门「1/60s」/ 倾斜角「3.0°」/ 提示卡百分比**四处数值文案 |
| 缺陷定性 | **Android 版现存缺陷，非 KMP 引入**。只修 iOS 会让两端「参考分」数值口径不一致 —— 对一个以参考分为唯一产出的产品，**口径不一致比小数点错误更伤** |
| 任务归属 | **已由主理人单独派发给工程师**（独立小改动、独立验收），**不并入 KMP 大重构** —— 混进去会被大重构的回归埋掉。**`NumFmt` 已产出**（`one`/`fixed`/`signed`/`int` 四个 locale 无关入口 + 16 个单测，含 de-DE/fr-FR 回归），**剩余工作只是切换 13 处调用点**，逐点拆解见 `IOS-ARCH-EXEC.md` 的 T02 |

---

## 11. 与 architect-2 预研报告的合并结论（v1.1 修订 · 本节为权威口径）

> 依据：`docs/IOS-TECH-FEASIBILITY.md`（870 行）。**本节修正了本文 §0/§1/§2.5/§10.5 的若干数字与方案**；凡与本节冲突，以本节为准。
> 所有数字为**我在当前代码上重跑脚本实测**的结果（脚本同时解析**显式 import** 与**同包引用** —— 后者不需要 import，按 import 扫描必然漏）。

### 11.1 我接受的 4 条修正（全部复核通过）

| # | architect-2 的指出 | 我的复核 | 处置 |
|---|---|---|---|
| A | 纯文件数 28 不是 29 | ✅ **我早已改正**（§1.5 已写「PM 的 28 是对的，29 是我自己的算术失误」）。**但当前代码又变了** —— `text/NumFmt.kt` 与 `analysis/FrameStatsCore.kt` 已在工程里（工程师在做 D5 与 T12 的共用核心），**L1 现为 30 个 / 8,666 行** | §11.2 给三口径；**数字随工程推进会变，以后一律以「重跑脚本」为准** |
| B | 🔴 B 类漏了 `java.lang.Math` | ✅ **确认漏了**。`java.lang` 是**自动导入**的，`import java.*` 扫描必然漏。**commonMain 里没有 `Math` 类**（只有 `kotlin.math`）→ **5 处 / 5 个文件在 common 边界内必须改**（§11.4） | 已进 T02 |
| C | 🔴 `Math.round` 是语义陷阱 | ✅ **确认**。`java.lang.Math.round(Double)`→Long，**向 +∞ 取整**；`kotlin.math.round(Double)`→Double，**平局向偶数**。`Math.round(2.5)=3` vs `kotlin.math.round(2.5)=2.0`。它用在**用户直接看到的快门标签**（`1/60`、`1/125`）→ 换错会出 `1/2` vs `1/3` | §11.4 给出正确改写 |
| D | 🔴 漏了第三条跨文件 Android 依赖 | ✅ **确认漏了**。`analysis/SceneClassifier.kt` **同包引用**（不需要 import）`AiScene` / `AiVisionResult`（定义在 `analysis/AiVision.kt`，该文件 import `android.graphics.Bitmap` + ML Kit）→ commonMain 同样编不过 | §11.3 升为 A 类必做项 |
| E | `ThreadLocal` 不该「消灭」，该把 `DexClock` 整块搬进 androidMain | ✅ **确认，且比我原方案好**。`DexClock`（SimpleDateFormat+Date+Locale+ThreadLocal）**只被 `growth/DexStore.kt`（Android 层）使用**（`:136/:173/:216`）→ 搬走即可，**common 侧 `TechniqueDex` 保持纯算法** | §11.5，`expect/actual` 数量从 2 降到 **0** |

### 11.2 三口径权威表（截至本次实测；**代码在动，数字会变**）

| 口径 | 定义 | 实测 |
|---|---|---|
| **L1 · 文件级** | 文件自身无 `import android.*` / `androidx.*` | **30 个 / 8,666 行**（含新增的 `text/NumFmt.kt`、`analysis/FrameStatsCore.kt`） |
| **L0 · 再排 JVM** | L1 再排除 `import java.*` / `javax.*` | **28 个 / 7,899 行**（差的是 `analysis/FrameStatsCore.kt`、`growth/TechniqueDex.kt`） |
| **L2 · 类级闭包** ★ | 自身无 android import，**且它引用的每个项目内类都在集合内（递归，含同包引用）** | **27 个 / 7,786 行** |

**L2 当前排除的 3 个文件（就是全部 A 类阻塞）**

| 文件 | 行数 | 依赖了哪个不可搬文件 |
|---|---:|---|
| `feature/FeatureAdvisor.kt` | 314 | `device/HardwareProbe.kt`（`DeviceProfile`） |
| `video/VideoRecommender.kt` | 429 | 同上 |
| **`analysis/SceneClassifier.kt`** | 137 | **`analysis/AiVision.kt`（`AiScene` / `AiVisionResult`）← 本文原漏** |

### 11.3 A 类补第三条：`SceneClassifier` 的同包依赖

`analysis/SceneClassifier.kt`（**同包，不需 import**）引用：
- `AiVisionResult`（`:37` 函数签名）
- `AiScene`（`:46-48` 的 `when` 分支：`.PORTRAIT` `.GROUP` `.LANDSCAPE` `.ARCHITECTURE` `.FOOD` `.PET` `.FLOWER`）

而 `AiScene` / `AiVisionResult` / `FaceBox` 定义在 **`analysis/AiVision.kt`**（import `android.graphics.Bitmap` + ML Kit）→ **commonMain 编不过**。

**必要性等级：不是「可选改造」，是 A 类阻塞，必做** —— 且它与 §2.5 的 `AiVisionResult` 下沉是**同一件事**，做完这一项，`SceneClassifier` 自动回到闭包内。

> ⚠️ **扫描方法论的教训（值得写进代码评审规范）**：
> **「import 扫描」只能发现显式依赖。同包引用（import 可省）必须靠「类名 → 定义文件」的映射 + 剥掉注释与字符串后的正文扫描才能发现。**
> 我第一次写这个脚本时，注释里提到的类名被当成了依赖，得出「L2 = 7 个文件」的荒谬结果 —— **扫描工具自己要先被验证**：拿已知答案（architect-2 与 PM 各自独立测出的 28）当基准校准，再信它的输出。

### 11.4 B 类补 `java.lang.Math`：5 处 / 5 个文件（common 边界内）

| 文件:行 | 调用 | 改法 |
|---|---|---|
| `analysis/CompositionAnalyzer.kt:160` | `Math.toDegrees(...)` | `kotlin.math` 没有 `toDegrees` → **自己写** `x * 180.0 / PI`（与 `Math.toDegrees` 一致：先乘 180 再除 PI） |
| `analysis/SceneClassifier.kt:85` | `Math.abs(...)` | → `kotlin.math.abs` ✅ 直接换 |
| `model/FrameStats.kt:70` | `Math.log(x)` ×2 | → `kotlin.math.ln`（**Kotlin 的 `ln` 就是自然对数，与 `Math.log` 等价**；这里要的是 log2 → `ln(x) / ln(2.0)`）✅ |
| `model/Recommendation.kt:66` | `Math.round(1.0/sec)` | ⚠️ **见下方语义陷阱** |
| `sensors/LevelMath.kt:69` | `Math.PI` | → `kotlin.math.PI` ✅ 直接换 |

**另 2 处在 Android 层，不进 common，无需改**：`device/OrientationTracker.kt:103/104`（`Math.toDegrees`）、`ui/GuideOverlayView.kt:414`（`Math.PI`）。

**🔴 `Math.round` 的语义陷阱（必须显式实现，不能机械替换）**

| 写法 | 返回 | `2.5` 的结果 | 平局方向 |
|---|---|---|---|
| `java.lang.Math.round(Double)` | `Long` | **3** | **向 +∞** |
| `kotlin.math.round(Double)` | `Double` | **2.0** | **平局向偶数** |

它用在 `Recommendation.shutterLabel` —— **用户直接看到的快门标签**（`1/60`、`1/125`）。换错 → `1/2` 与 `1/3` 的差别，是可见的错。

**正确改法**：显式实现「向 +∞ 取整」，**不要用 `kotlin.math.round`**：

```kotlin
// 向 +∞ 取整（与 java.lang.Math.round 同语义）；1.0/sec 恒正
val denom = kotlin.math.floor(1.0 / sec + 0.5).toInt()
```

> 现有 `NumFmt.roundHalfAwayFromZero`（**半值远离零**）对**正数**与「向 +∞」等价，而 `1.0 / sec` **恒正** → 当前用法安全。但**语义名不同**（远离零 ≠ 向 +∞），将来若这个值可能为负，**必须重新对齐**。建议在 `Recommendation` 的调用处加一行注释写明「这里要的是向 +∞，不是远离零」。

**另：`PromptEngine.kt:152` 与 `FeatureAdvisor.kt:305` 各自实现了一个同名私有 `pct()`**（重复代码）。
**处置：不把 `pct()` 加进 `NumFmt`**（`NumFmt` 只做**通用定点格式化**，百分比是语义不是格式）→ 把两处私有实现**合并成一个共享 helper**，或直接用 `NumFmt.fixed(value * 100, 0)`。

### 11.5 `ThreadLocal` 与 `expect/actual` 数量的修正（方案改用「整块搬走」）

**新方案（采纳 architect-2 的，更优）**

| 内容 | 归属 | 理由 |
|---|---|---|
| `object DexClock`（`SimpleDateFormat` + `Date` + `Locale` + **`ThreadLocal`**） | **整块搬进 `androidMain`** | 它**只被 `growth/DexStore.kt`（Android 层）使用**（`:136/:173/:216`）→ common 侧根本不需要它 |
| `TechniqueDex` 的纯算法 | **commonMain** | 不含任何日期逻辑 → 不需要 `expect/actual` 桥接 |
| `ScoreHistory` 的时间 | **commonMain + 注入 `now: Long`**（T04） | 与 `PromptScheduler` 既有风格同构 |

**结果：`expect/actual` 从 2 个降到 0 个。**
- 原设计的 `expect fun formatYmd(millis)` —— **删掉**（`DexClock` 搬走后没人需要它）；
- 原设计的 `expect fun currentTimeMillis()` —— **删掉**（`ScoreHistory` 改成注入 `now`，`TechniqueDex` 不再取当前时间）。

> 这正是本文 §4.4 的纪律「**能用入参解决的绝不用 expect**」的完整落地。**`expect/actual` 数量是 Kotlin/Native 编译成本的一个真实变量**（PRD R6），降到 0 是白拿的收益。
> ⚠️ **唯一前提**：`TechniqueDex` 内部若还有任何「自己取当前时间」的调用，必须一并改成入参 —— 我核对过：`today()` 只被 `DexStore` 调用 ✅。

### 11.6 architect-2 的三条可直接用结论（我采纳）

| # | 结论 | 影响 |
|---|---|---|
| 1 | **`CompositionAnalyzer` / `SharpnessMeter` 都不碰 Bitmap**（只吃 `FloatArray`）→ **算法逐字可搬**；且 Vision 的 `VNDetectHorizonRequest` 是**更差**的替代（实测 39 张只检出 47%、水平照片常 nil，结构张量法对任意主方向边缘都有效）→ **不要换 Vision** | ✅ 与本文 §2.4 / §10.2.4 一致（我用结构张量 + CoreMotion 重力，不用 Vision 的地平线检测）。**水平度一律走 `LevelMath` + CoreMotion** |
| 2 | **iOS 手动曝光能 1:1 对上，且 iOS 更简单**：`setExposureModeCustom(duration:iso:completionHandler:)` 是**设备级锁定**（预览/成片天然一致），Android 是 per-CaptureRequest（我们代码注释里踩过这个坑）。**唯一差异：iOS 的 ISO/快门能力是 `activeFormat` 级、会随分辨率帧率变**，不是 Android 的传感器级固定 → **`CameraCaps` 必须动态重读** | ★ **这条我原文档没有**，已进 T12 验收 |
| 3 | 任务书里的 `linkDebugFrameworkIosSimulator` 是旧名，正确是 `linkDebugFrameworkIosSimulatorArm64` | ✅ **本文 §4.1 用的已是正确名**，无需改 |

### 11.7 `NumFmt` 的落位：必须**移动**不是复制

`text/NumFmt.kt` 目前落在 **Android 模块**（`app/src/main/java/.../text/`）。
→ **最终必须移动到 `shared/src/commonMain/kotlin/com/shottuner/app/text/`** 才能给 iOS 共用。
→ ⚠️ **要移动不是复制**：复制会在 Android 侧产生**两个同名类**（`app` 与 `shared` 各一份）→ **dex merge 报 duplicate class**。
→ 移动后 Android 侧 `import com.shottuner.app.text.NumFmt` **路径不变**（Kotlin 包名沿用 `com.shottuner.app.*`）→ **调用点一行都不用改** ✅。
