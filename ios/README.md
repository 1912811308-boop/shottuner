# ios/ —— ShotTuner iOS 端

> 这一批（T10）只做**工程骨架**：让 iOS 侧有一条「能生成、能编译、能取到共享内核」的完整链路。
> 取景、评分、照拨文案的接入在后续批次做。

---

## 0. 一句话说明这个目录

| 文件 | 作用 |
|---|---|
| `project.yml` | **XcodeGen 规格 —— 工程的单一事实来源**。`.xcodeproj` 不入库，由它生成 |
| `ShotTuner/ShotTunerApp.swift` | `@main` 的 SwiftUI App 入口 |
| `ShotTuner/ContentView.swift` | 最小界面（骨架）：标题 + 相机权限 + 共享内核自检 |
| `ShotTuner/CameraPermissionModel.swift` | 相机授权状态机（骨架） |
| `ShotTuner/SharedKernelBridge.swift` | **Kotlin → ObjC → Swift 的取样调用**（本目录最重要的一个文件） |
| `ShotTuner/Info.plist` | 手工维护的 Info.plist（权限文案是产品口径，必须在 PR 里可审） |
| `ShotTuner/PrivacyInfo.xcprivacy` | 隐私清单（零数据采集、零追踪） |
| `ShotTuner/Assets.xcassets/` | 资源目录（目前只有 AccentColor；App Icon 属后续批次） |
| `Scripts/kmp-build-framework.sh` | Xcode 的 preBuild 阶段脚本：产出 Kotlin 共享内核的 framework |
| `Scripts/kmp-embed-framework.sh` | Xcode 的 postCompile 阶段脚本：把 framework 嵌进 `.app/Frameworks` |

---

## 1. 生成工程

`.xcodeproj` 是**生成物，不入库**（pbxproj 是合并地狱，而且这样没有 Mac 的工程师也能改工程结构）。

```bash
# 需要 macOS + XcodeGen >= 2.45
brew install xcodegen          # 或从 https://github.com/yonaskolb/XcodeGen/releases 直接下

cd ios
xcodegen generate --spec project.yml
# 产物：ios/ShotTuner.xcodeproj
```

从仓库根目录跑等价于：

```bash
xcodegen generate --spec ios/project.yml --project ios
```

> `project.yml` 里的相对路径（`sources: [ShotTuner]` 等）都以 **spec 文件所在目录**为基准，
> 所以 `sources: [ShotTuner]` 指的是 `ios/ShotTuner/`。

### ⚠️ 为什么 `project.yml` 里锁了 `projectFormat: xcode15_0`

XcodeGen **2.45+ 默认生成 objectVersion = 77**（Xcode 16 的工程格式），
而 macos-14 runner 上的默认 Xcode 是 **15.4** —— 打开会直接报：

```
The project 'ShotTuner' cannot be opened because it is in a future Xcode
project file format (77).
```

★ 这个报错**看起来像工程文件坏了**，但它只是格式版本对不上。
而且它会让整个 iOS 编译门禁变成「每次都红」—— **红一次的门禁等于没有门禁**。

所以 `project.yml` 里显式锁 `projectFormat: xcode15_0`（objectVersion 60）：
Xcode 15 与 16 都能打开，而本项目的 Kotlin 1.9.24 官方只测到 Xcode 15.x，
升 Xcode 16 会让 Kotlin/Native 的链接步骤失败 —— 那是另一个更大的坑。

> 连带要求：**XcodeGen 必须 >= 2.45**（`projectFormat` 选项是 2.45.0 才加的）。
> CI 里会显式断言版本，太旧直接报错，而不是让它悄悄生成 77。

---

## 2. 本地打开 / 编译

### 用 Xcode

```bash
cd ios && xcodegen generate && open ShotTuner.xcodeproj
```

选一个 iOS 模拟器直接 ⌘R。第一次构建会先跑 Gradle 产出 Kotlin 共享内核的 framework（几分钟），
之后有 Gradle 缓存，会快很多。

### 用命令行（和 CI 完全一致）

```bash
xcodebuild \
  -project ios/ShotTuner.xcodeproj \
  -scheme ShotTuner \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

### 装到真机

**工程本身永远不签名**（`CODE_SIGNING_ALLOWED=NO`，CI 一律不签名，这是硬规则）。
签名在设备端用侧载工具（SideStore / AltStore / Sideloadly）用你自己的开发者账号完成 ——
凭据绝不进仓库，也绝不进 CI secrets。

---

## 3. CI 怎么跑

`.github/workflows/ci-gates.yml`：

| job | runner | 触发 | 验什么 |
|---|---|---|---|
| `gates` | ubuntu-latest | push / PR | 零网络、凭据、commonMain 纯度、**iOS 工程规格自检** |
| `common-gate` | ubuntu-latest | push / PR | commonMain metadata 编译 + 单测 + Android 编译 |
| `ios-compile` | **macos-14** | **仅手动触发** | iOS target 编译 + **xcodegen generate + xcodebuild** |

```bash
# 手动触发 macOS 那档（保护配额：免费私有仓库的 macOS 配额有限）
gh workflow run ci-gates.yml --ref main
# 或
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" -d '{"ref":"main"}' \
  https://api.github.com/repos/1912811308-boop/shottuner/actions/workflows/ci-gates.yml/dispatches
```

> ⚠️ **看结论会误判**：Gradle 的 SKIPPED 任务在 GitHub 上显示的 conclusion 也是 `success`。
> 所以 `ios-compile` 里有显式的**反 SKIPPED 断言**，并且日志里会打
> `KMP_FRAMEWORK_READY=1` / `KMP_FRAMEWORK_EMBEDDED=1` 两个标记 ——
> **确认这两个标记出现，才说明 xcodegen 与 xcodebuild 真的跑过了**。

---

## 4. Kotlin ↔ Swift 的跨语言规则（照着这三条写就不会踩坑）

链路长这样：

```
shared/src/commonMain + shared/src/iosMain   （Kotlin 算法内核）
        │  Kotlin/Native 编译
        ▼
Shared.framework + Headers/Shared.h          （ObjC 头文件，自动生成）
        │  Xcode 的 Swift/ObjC 桥接
        ▼
Swift：import Shared  →  IosBridge.shared.describe()
```

### ① 模块名 = framework 名 = `Shared`

KGP 用 Gradle 项目名（`shared`）首字母大写作为 framework 名，所以 Swift 侧是 `import Shared`。
framework 名改了，`import` 与 `OTHER_LDFLAGS` 里的 `-framework Shared` 都要跟着改。

### ② Kotlin 的 `object` 在 Swift 里是「类属性单例」

```kotlin
object IosBridge { fun describe(): String = "…" }
```
```swift
IosBridge.shared.describe()   // ✅ 不是 IosBridge()
```

Kotlin 的 `class` 才是 `Xxx()`。

### ③ 跨语言边界只传标量，不传数组

`FloatArray` 会被桥接成 **NSArray of NSNumber**（装箱 + 拷贝）——性能差、Swift 侧难用。
需要结构时，由 commonMain 提供**扁平 DTO**（全是 Int/Double/String/Boolean），
Kotlin 侧算完，Swift 侧只负责渲染。详见 `docs/IOS-ARCH.md` §3.4。

> 另一个坑：**重载会被改名，且不可预测**。Kotlin 靠参数类型区分重载，ObjC 只能靠参数名，
> 所以同名重载在 Swift 侧会被加尾随下划线（`one(value:)` / `one(value_:)`），哪个被加无法预测。
> 所以导出给 Swift 的函数必须**签名唯一** —— 这正是 `IosBridge` 这一层存在的理由。

---

## 5. iOS 侧与 Android 侧的对应关系

### 5.1 共享内核（`shared/src/commonMain/`）—— 两端**同一份实现**，不是各写一遍

| commonMain 里的东西 | Android 侧怎么用 | iOS 侧怎么用 |
|---|---|---|
| `text/NumFmt.kt` | 档位/数值文案（小数点恒为 `.`） | 同上，Swift 侧调 `IosBridge.formatOne(value:)` |
| `sensors/LevelMath.kt` | 水平度计算（重力向量 → 横滚/俯仰） | 同上；`LevelMath.shared.isLevel(roll:)` 直接调 |
| `analysis/ScoreBoard.kt` | 四维**参考分**合成 | 同上（下一步接 `FrameStats` 之后） |
| `analysis/CompositionAnalyzer.kt` | 构图诊断（`CompositionHint`） | 同上 |
| `analysis/SharpnessMeter.kt` | 清晰度（估） | 同上 |
| `analysis/SceneClassifier.kt` | 场景分类 | 同上（平台标签映射见 `docs/IOS-ARCH.md` §10.2） |
| `analysis/AiVisionModels.kt` | AI 场景结果的数据结构 | 同上（**Vision 的标签映射要重标**） |
| `model/*`（`FrameStats` / `CompositionHint` / `ManualParams` / `CameraCaps` / `Recommendation` / `SceneType`） | 数据结构 | 同上；**跨语言时用扁平 DTO，不直接传** |
| `knowledge/*`（技法库、`PromptEngine`、`TechniqueMatcher`、`VideoMovePlanner` 等 8 个） | 技法内容与文案生成 | 同上 |
| `growth/*`（`TechniqueDex`、`MissionEngine`、`TaskPicker`、`ScoreHistory`） | 图鉴、任务、成长 | 同上 |
| `recommend/ParamRecommender.kt` | 参数建议（**建议值**，不替用户调） | 同上 |
| `coach/FramingCoach.kt` | 机位教练 | 同上 |
| `feature/FeatureAdvisor.kt` | 功能建议 | 同上 |
| `video/VideoRecommender.kt` | 录像建议 | 同上（iOS 第一批不做录像） |
| `device/DeviceProfile.kt`、`device/Brand.kt` | 设备档案 | 同上（iOS 侧由 `AVCaptureDevice` 采集） |

### 5.2 平台层（**两端各写各的**，这才是 iOS 侧真正的工作量）

| Android 侧 | iOS 侧对应 | 批次 |
|---|---|---|
| `CameraX` / `Camera2Interop` 取景 | `AVCaptureSession` + `AVCaptureVideoDataOutput` | T12 |
| `ImageProxy` / YUV 平面直读 | `CVPixelBuffer` | T12 |
| ML Kit 场景识别 | **Apple Vision**（`VNClassifyImageRequest` / `VNDetectFaceRectanglesRequest`） | T11 |
| `SensorManager`（水平仪） | `CoreMotion`（`CMDeviceMotion`） | T12 |
| `CameraManager` / `CameraCharacteristics` | `AVCaptureDevice.formats` / `activeFormat` | 后续 |
| `SharedPreferences` / 文件存储 | `UserDefaults` / Application Support | 后续 |
| `android.util.Log` + 文件 IO | `os_log` + `FileManager` | 后续 |
| Android View + viewBinding 的全部 UI | **SwiftUI**（不是 Compose Multiplatform） | 逐步 |
| `shared/src/androidMain/growth/DexClock.kt` | 不需要（`DexClock` 只被 Android 的存储层用） | — |

---

## 6. 本目录的硬规则（都有机器门禁盯着）

| 规则 | 门禁 |
|---|---|
| CI 一律不签名（`CODE_SIGNING_ALLOWED=NO`、身份恒为空） | `tools/ci/check-no-network-config.sh`、`tools/ci/check-apple-credentials.sh`、`tools/ci/check-ios-project-spec.sh` |
| ATS 不放宽（零网络承诺） | 同上 |
| 不引入任何第三方依赖（工程里不出现 CocoaPods / SPM 第三方包） | `tools/ci/check-no-network-config.sh`、规格自检 |
| 源码层零网络（`ios/**/*.swift` 里不出现联网调用） | `tools/ci/check-no-network.sh` |
| 部署目标 iOS 16.0、只有一个 target、第一批无 App Extension | `tools/ci/check-ios-project-spec.sh` |
| 权限清单只保留相机（不加麦克风、不加相册读取） | 规格自检 + PR 评审 |
| `.xcodeproj` 不入库 | `.gitignore` + 规格自检 |
| 只说「参考分」、文案用「照拨」「建议值」 | PR 评审 |

---

## 7. 第一批的已知限制（如实说）

- **只有骨架**：没有取景、没有实时评分、没有照拨文案，那些在 T11/T12 之后。
- **framework 的重新签名还没做**：真机侧载时，动态 framework 与 App 本体一样需要侧载工具一起签。
  目前工程侧不签名是对的（硬规则），但出包那一步要确认侧载工具会连带签 `Frameworks/Shared.framework`。
- **`embedAndSignAppleFrameworkForXcode` 在不签名环境下的行为**：`ios/Scripts/kmp-build-framework.sh`
  对它做了容错（失败就回退到显式 link 任务），但**首选路径仍是它** —— 与架构文档的方案 B 一致。
- **`iosMain` 目前只有一个门面文件**（`shared/src/iosMain/.../IosBridge.kt`）：
  它存在的意义是让 macOS CI 的 `:shared:compileKotlinIosArm64` 真的覆盖到 iOS 专属源集，
  以及把跨语言边界收窄成「标量进、标量出」。
