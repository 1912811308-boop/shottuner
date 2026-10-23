# RELEASE-ARCH · 发布版技术方案（v3.0.0）

> 版本 v1.0 ｜ 作者：高见远（架构师）｜ 基于代码现状 v2.1.0（versionCode 22）
> 上游输入：`docs/RELEASE-PRD.md`（552 行合规包）、主理人已定决策（包名 / 五家商店 / 正式签名 / targetSdk 35）
> **本文只写方案，不改任何 `.kt` / `.xml` / `.kts`。**

---

## 0. 事实核对：主理人给的 11 条里，**3 条要修正**，另有 **3 个清单外的问题**

我把工程逐项复核了一遍，以下是我的实测结果。**打 ⚠️ 的是主理人清单里没提到、但会直接影响排期的**。

| # | 主理人的说法 | 实测 | 差异 |
|---|---|---|---|
| 1 | namespace/applicationId 都是 `com.clover.shottuner` | ✅ 确认（`build.gradle.kts:8,12`） | — |
| 2 | 签名密码明文 `shottuner`，jks 在工程目录 | ✅ 确认（`:22-28`，`app/shottuner.jks` 2638B） | ⚠️ **但没有 `.gitignore`**，且当前目录**不是 git 仓库**。一旦 `git init` 并 `git add .`，`.jks` 与 `local.properties` 会直接进库 |
| 3 | 无 INTERNET 权限 | ✅ 确认，且全仓无 `HttpURLConnection/OkHttp/Retrofit/Socket` | — |
| 4 | `allowBackup="true"` | ✅ 确认（`AndroidManifest.xml:22`） | — |
| 5 | 图标只有 `@drawable/ic_app_icon`，无 mipmap / 无自适应 | ✅ 确认 | — |
| 6 | targetSdk 34 | ✅ 确认（`build.gradle.kts:13`） | ⚠️ **`compileSdk` 也是 34，而 AGP 版本是 8.5.2**（`build.gradle.kts` 根文件）。**AGP 8.5 不支持 compileSdk 35**（首个支持 API 35 的是 AGP 8.6）。升 targetSdk 35 前**必须先升 AGP**，这是主理人清单里完全没提的硬前置 |
| 7 | 权限 6 项含 POST_NOTIFICATIONS | ✅ Manifest 声明了 | ⚠️ **代码里从未请求过 POST_NOTIFICATIONS**（`grep` 全仓零命中）。Android 13+ 未授权 → 前台服务通知**不显示** → 隐私政策里「通知栏常驻一条通知，点击即可立即停止」这句**当前是假的** |
| 8 | `ScreenCaptureService` 的 ACTION 常量是硬编码 | ✅ 确认（`ScreenCaptureService.kt:53-54` = `"com.clover.shottuner.screen.START/STOP"`） | — |
| 9 | FileProvider authorities 用占位符 | ✅ 确认（`${applicationId}.fileprovider`），代码侧用 `"$packageName.fileprovider"`（`MainActivity:1706`）→ **自动跟随，无需改** | — |
| 10 | 121 个 JVM 单测全绿，UI 零测试 | ✅ 8 个测试文件，无 `androidTest` | — |
| 11 | v2.1.0 / versionCode 22 | ✅ 确认 | — |

**另外 3 个我自己查出来的问题（都不在主理人清单里）**：

- ⚠️ **N1 · 通知没有 `setContentIntent`、没有停止按钮**（`ScreenCaptureService.kt:239-252`）。隐私政策 §2.3 明确承诺「系统通知栏会常驻一条本应用的通知，点击即可立即停止」——**代码做不到**。这是合规文案与实现直接冲突，比 UI 问题严重。
- ⚠️ **N2 · `ScreenCaptureService` 返回 `START_STICKY`**（`:100`），但 `onStartCommand` 只处理了 `ACTION_START/ACTION_STOP` 两个 action，**收到 null intent 时什么都不做就直接返回**。系统重建服务时不会调 `startForeground` → 在 targetSdk 34/35 上会抛 `ForegroundServiceDidNotStartInTimeException`。而且 MediaProjection 的 token 是一次性的，**重建后本来就不可能恢复录屏**。
- ⚠️ **N3 · UI v2 的改动已经落地了**（`llControlBar` / `tvShutterFlash` / `ivThumb` / `llReviewCard` / `btnMore` / `swVolumeShutter` / `tvAdvice` / `dispatchKeyEvent` 都在，`panel` 已是 `percent=0.45 + min240 + max560`）。所以本文 §9 的现状判断是**基于 v2.1.0 的真实代码**，不是基于 v2 方案本身。

---

## 1. 包名迁移影响面

### 1.1 必须先做的架构决策：改到哪一层

包名迁移有两条路，**选错会在发布前返工**。

| 方案 | 做法 | 改动量 | 后果 |
|---|---|---|---|
| **A · 最小改动**（不推荐） | 只改 `namespace` + `applicationId`，Kotlin 包名保持 `com.clover.shottuner` | 3 行 | 编译能过，但 `viewBinding` 生成的 `ActivityMainBinding` 会落在 `com.shottuner.app.databinding`，而代码 import 的是 `com.clover.shottuner.databinding` → **必须改 1 处 import**。结果：APK 里同时存在两套包名，审核方 grep 旧包名会命中，且每个后来读代码的人都要问一次"为什么有两个" |
| **B · 完整迁移**（推荐） | `namespace` + `applicationId` + Kotlin 源码目录 + `package`/`import` + XML 里的自定义 View FQN 全部改 | ~50 个文件的 package 行（机械替换） | 一处漏改就是**编译错误**（响亮失败，不是静默失效）。发布版应当只有一套包名 |

> **决策：选 B。** 理由是"发布级、一次做对"——包名改了就是永久不可改，不能留半套。

### 1.2 硬编码点清单（逐条，文件:行）

**必须改（漏了会编译失败或功能失效）**

| # | 位置 | 内容 | 处理 |
|---|---|---|---|
| 1 | `app/build.gradle.kts:8` | `namespace = "com.clover.shottuner"` | → `com.shottuner.app` |
| 2 | `app/build.gradle.kts:12` | `applicationId = "com.clover.shottuner"` | → `com.shottuner.app` |
| 3 | `app/src/main/java/com/clover/shottuner/screen/ScreenCaptureService.kt:53` | `const val ACTION_START = "com.clover.shottuner.screen.START"` | → `"com.shottuner.app.screen.START"` |
| 4 | 同上 `:54` | `const val ACTION_STOP = "com.clover.shottuner.screen.STOP"` | → `"com.shottuner.app.screen.STOP"` |
| 5 | `app/src/main/res/layout/activity_main.xml:18` | `<com.clover.shottuner.ui.GuideOverlayView>` | → `com.shottuner.app.ui.GuideOverlayView` |
| 6 | 同上（`HistogramView` 节点） | `<com.clover.shottuner.ui.HistogramView>` | → `com.shottuner.app.ui.HistogramView` |
| 7 | 全部 `.kt` 的 `package` / `import` 行（约 50 文件） | `com.clover.shottuner.*` | 机械替换为 `com.shottuner.app.*` |
| 8 | `app/src/test/.../PromptEngineTest.kt:416` | `com.clover.shottuner.analysis.ScoreBoard.CLIP_HIGH_CONVERTED`（全限定引用） | 同步改，否则**测试编译失败** |
| 9 | `app/src/main/java/.../knowledge/VideoMovePlanner.kt:28`、`model/FrameStats.kt:45` | KDoc 里的 `[com.clover.shottuner.*]` 链接 | 同步改（不改不影响编译，但会变成死链） |

> 第 3/4 条有个容易误判的点：这两个常量**只在本 App 内部自产自销**（`MainActivity:1755` 设 action → `Service:85/89` 比对），所以**不改也能跑**。但它们是 `public const val`，会把旧包名字符串固化进 APK dex，且审核方 grep 会命中。**要改，但改错不会崩** —— 属于低风险项。

**自动跟随，不需要改（但要验证）**

| 位置 | 为什么不用改 |
|---|---|
| `AndroidManifest.xml` 的 `android:authorities="${applicationId}.fileprovider"` | `${applicationId}` 是构建期占位符，自动变成 `com.shottuner.app.fileprovider` |
| `MainActivity:1706` `FileProvider.getUriForFile(this, "$packageName.fileprovider", f)` | 用运行时 `packageName`，自动一致 |
| `MainActivity:1755` `action = ScreenCaptureService.ACTION_START` | 走常量引用，改了常量就跟着变 |
| `CameraTileService.kt:20` `Intent(this, MainActivity::class.java)` | 走类引用 |
| `AndroidManifest.xml` 的 `.MainActivity` / `.screen.ScreenCaptureService` / `.tile.CameraTileService` | 相对类名，按 `namespace` 解析 |
| `debug` 的 `applicationIdSuffix = ".debug"` | 自动变成 `com.shottuner.app.debug`，FileProvider authority 也随之变化 ✅ |

**不含包名，无需迁移（但要决定旧数据怎么办）**

| 项 | 值 | 判断 |
|---|---|---|
| SharedPreferences 文件名 | `shottuner_prefs`（`MainActivity:1934`）、`shottuner_overlay`（`CoachOverlay:53`） | 不含包名，**但 Android 按包名隔离 prefs 目录** `data/data/<pkg>/shared_prefs/`。包名一变 = 全新 App = **旧 prefs 读不到** |
| 通知 channel | `coach_overlay`（`ScreenCaptureService:57`） | 不含包名，随应用重建 |
| 相册目录 | `Pictures/ShotTuner`（`MainActivity:1509`，提示文案在 `:1438`） | 见 §1.3 |
| `FileProvider` 的 `<cache-path name="shots" path=".">` | 相对路径 | 无需改 |

### 1.3 两个需要拍板的判断

**判断一：旧版用户数据要不要迁移 → 不迁。**

包名从 `com.clover.shottuner` 改成 `com.shottuner.app`，在系统眼里是**两个完全不同的 App**：

- 旧 prefs（提示密度、音量键开关、悬浮卡位置）读不到 → 用户升级后回到默认值；
- 旧 App 不会自动卸载，会和新 App 并存（桌面两个图标）；
- 两者都是"内部测试用户"，数量级是个位数，**没有迁移价值**。

**结论：不写迁移代码。** 但要在发布说明里明确一句「旧测试版请手动卸载」，并要求 clover 在发布前把自己手机上的旧版删掉，避免"两个图标"的截图被审核当成套壳证据。

**判断二：相册目录保持 `Pictures/ShotTuner`，不改成中文。**

PRD §风险 5 的附带建议是"目录名与应用名保持一致"。我不采纳，理由三条：

1. **`MediaStore.RELATIVE_PATH` 支持非 ASCII，但下游一堆工具不支持**：ADB `ls`、部分文件管理器、Windows 端 MTP 挂载、老版本相册 App 在读写中文路径时会出乱码或直接不显示。这是"为了文案一致，换来一类灵异 bug"。
2. **改目录 = 用户相册里出现两个文件夹**：旧版的照片在 `ShotTuner/`，新版写 `光影调参/`，用户会以为老照片丢了。保持同名则新旧照片**在同一个相册里**，对"内部测试用户升级"这个场景明显更好。
3. 审核真正关心的是**应用名、软著名、商店展示名**三者一致（PRD §1.2 差异一），相册子目录名不是申报字段。

**结论：目录保持 `Pictures/ShotTuner`；`app_name` 保持「光影调参」。** 若 clover 坚持要一致，改动只有 `MainActivity:1509` 一处常量 + `:1438` 一处提示文案，但请先接受上面两条代价。

### 1.4 迁移执行方式（避免手抖）

```bash
# 1) 移动源码目录
git mv app/src/main/java/com/clover/shottuner app/src/main/java/com/shottuner/app
git mv app/src/test/java/com/clover/shottuner app/src/test/java/com/shottuner/app
# 2) 全仓替换（先干跑看数量）
grep -rl "com\.clover\.shottuner" app/src | wc -l
grep -rl "com\.clover\.shottuner" app/src | xargs sed -i 's/com\.clover\.shottuner/com.shottuner.app/g'
# 3) 校验零残留
grep -rn "com\.clover\.shottuner" app/src app/build.gradle.kts || echo "OK"
```

> 注意 `sed` 会把 `package com.clover.shottuner` 也替换成 `package com.shottuner.app`，正是我们要的。
> **风险提示**：`git mv` 后必须确认目录层级，`com/shottuner/app` 是三层，比原来 `com/clover/shottuner` 少一层，Kotlin 的 `package` 声明决定编译结果、目录只是习惯，两者一致即可。

---

## 2. targetSdk 35 的真实工作量

我先说结论：**8 项变更里只有 3 项会真的坏，其余 5 项对本 App 无影响。** 不罗列凑数。

### 2.0 前置：AGP 必须先升（主理人清单未提）

| 项 | 现状 | 要求 |
|---|---|---|
| AGP | **8.5.2**（根 `build.gradle.kts:2`） | **≥ 8.6.0** 才支持 `compileSdk = 35` |
| Gradle | 项目**没有 `gradle/wrapper/`**，靠 Android Studio 自带或系统 gradle | 建议同时补 wrapper，否则"我在你机器上能编过"无法复现 |
| Kotlin | 1.9.24 | 无需改（AGP 8.6 仍兼容） |

**要改什么**：根 `build.gradle.kts` 的 `com.android.application` 版本 → `8.6.1`（或 8.7.x），`compileSdk = 35`、`targetSdk = 35`。
**怎么验证**：`./gradlew :app:assembleDebug` 通过 + `aapt2 dump badging` 显示 `targetSdkVersion:'35'`。
**优先级：发布必须（阻塞其它所有 35 相关项）。**

> 若 AGP 升级遇到阻力，**唯一的替代是把 targetSdk 停在 34 并逐家核对商店当期门槛**——PRD §风险 4 已注明门槛以各商店公示为准。这是需要 clover 决策的岔路，我给出方案但不下判断。

### 2.1 边缘到边缘（⚠️ 会坏，中等工作量）

| 项 | 内容 |
|---|---|
| **会不会影响** | **会，但比一般 App 轻**。本 App 已经 `setDecorFitsSystemWindows(false)` + `hide(systemBars())`（`MainActivity:419-423`），也就是**早就手动做了 edge-to-edge**。真正会变的是两处 |
| 具体变化 | ① `themes.xml` 的 `android:navigationBarColor = #0E1113` 与 `android:statusBarColor = transparent` 在 API 35 上**被系统忽略**（edge-to-edge 下栏底色由内容决定）→ 导航栏变透明，取景画面透上来；② `llControlBar` **没有底部 inset 处理**（`applyPanelInsets()` 只给 `panel` 加了 padding，`:362-370`），系统栏瞬时滑出时会压住 72dp 快门的下沿 |
| 要改什么 | ① `applyPanelInsets()` 扩展为同时给 `llControlBar` 加 bottom padding（`WindowInsetsCompat.Type.systemBars()`）；② `themes.xml` 删掉两个栏色（保留也无害，只是失效）；③ 显式设置 `WindowInsetsControllerCompat(window, decorView).isAppearanceLightNavigationBars = false` 保证导航栏图标是白色 |
| 怎么验证 | Android 15 真机或 35 模拟器：① 从底部上滑唤出导航栏，快门不被压住；② 导航栏区域图标可见；③ 顶栏在状态栏瞬时出现时不跳位 |

### 2.2 前台服务类型收紧（✅ 不坏，但要回归）

| 项 | 内容 |
|---|---|
| **会不会影响** | **不会**。`foregroundServiceType="mediaProjection"` 已声明（Manifest），`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限已声明，且 `startForeground(NOTIF_ID, n, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` 三参版本已用（`ScreenCaptureService:106-110`），启动时机在用户点击之后（前台） |
| **但有一个真问题** | **N2：`START_STICKY`**（`:100`）。系统重建时会用 null intent 调 `onStartCommand`，当前代码两个分支都不匹配 → 直接 `return START_STICKY`，**未调 `startForeground`** → API 34+ 抛 `ForegroundServiceDidNotStartInTimeException`（表现是崩溃或 ANR） |
| 要改什么 | ① 所有路径统一 `return START_NOT_STICKY`（MediaProjection token 一次性，重建也恢复不了，STICKY 只有害无利）；② `intent == null` 或 action 不认识 → `stopSelf()` 后返回 |
| 怎么验证 | ① 开发机 `adb shell am crash` 或开发者选项「不保留活动」反复杀；② `adb shell dumpsys activity services com.shottuner.app` 确认无残留；③ 观察 logcat 无 `ForegroundServiceDidNotStartInTime` |
| 优先级 | **发布必须**（N2 是崩溃级） |

### 2.3 MediaProjection 相关变更（⚠️ 一项会坏）

| 项 | 内容 |
|---|---|
| **会不会影响** | 录屏本身**不会**：Android 14 起要求的 `MediaProjection.Callback` 已注册（`ScreenCaptureService:128-133`，且在 `createVirtualDisplay` **之前**`，顺序正确） |
| **但隐私承诺会坏** | 见 N1：通知**没有 `setContentIntent`、没有停止 action**（`:239-252`）。隐私政策 §2.3 承诺「点击即可立即停止」→ **审核方只要点一下通知就发现是假的**，这比功能缺失严重，因为它是"隐私政策与实际行为不符" |
| 要改什么 | `buildNotification()` 加：① `setContentIntent(PendingIntent.getService(this, 0, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), FLAG_IMMUTABLE))`；② `addAction(图标, "停止", 同一个 PendingIntent)`；③ `setSmallIcon` 换成应用自己的图标（现在是 `android.R.drawable.ic_menu_camera`，系统图标，视觉上不属于本 App） |
| 怎么验证 | 开启悬浮教练 → 下拉通知 → 点通知本体 → 悬浮卡立即消失、通知消失；点「停止」按钮同效 |
| 优先级 | **发布必须（合规）** |

### 2.4 16KB page size（⚠️ 需验证，可能必须升 ML Kit）

| 项 | 内容 |
|---|---|
| **会不会影响** | **不确定，必须实测**。16KB 页设备要求 `.so` 按 16KB 对齐。本 App 的原生库只来自 **ML Kit（TFLite）**（`image-labeling 17.0.9` / `face-detection 16.1.6`，均为 2023 年的版本）。这类老版本 TFLite `.so` 几乎确定是 4KB 对齐 |
| 影响面 | 16KB 内核设备（部分 Android 15 新机、开发者选项可开）上 → **ML Kit 的 `System.loadLibrary` 失败** → 首次调用 `FaceDetection.getClient()` 时不崩但推理永远失败，或直接 `UnsatisfiedLinkError` 崩溃。前者更糟（场景识别静默失效，参数建议质量掉一档，用户无感知） |
| 要改什么 | **先验证再决定**：`unzip -o app-release.apk 'lib/*' -d /tmp/lib && $ANDROID_NDK/toolchains/llvm/prebuilt/*/bin/llvm-readelf -l /tmp/lib/arm64-v8a/lib*.so | grep LOAD`，看 `Align` 是否为 `0x4000`。若不是 → 需要把 ML Kit 升到支持 16KB 的版本 |
| 是否算"新增依赖" | **不算新增，算升版本**。但 ML Kit 升级会连带 `com.google.mlkit:common` 与 TFLite，可能改变 APK 体积与混淆行为 → **需要 clover 确认** |
| 判断 | 国内五家商店**目前不要求** 16KB（那是 Google Play 的政策），且 16KB 设备存量极小。**建议：验证一次留记录，不主动升级；若验证发现 4KB 且 clover 想面向未来，再单独排期** |
| 优先级 | **建议（不阻塞发布）** |

### 2.5 其余 5 项：明确不影响，不要浪费时间

| 变更 | 为什么不影响 |
|---|---|
| **通知权限（API 33+）** | 不是 35 的新变更。但本 App **确实没请求过**，见 U1/R3 与 §2.6 |
| **`screenOrientation="portrait"` 被忽略** | 是 **API 36 / Android 16** 对 ≥600dp 屏的行为，targetSdk 35 不受影响。但**分屏模式下系统本来就会忽略 orientation**（见 R11） |
| **前台服务 6 小时超时** | 只针对 `dataSync` / `mediaProcessing` 类型，`mediaProjection` 不在其中 |
| **`windowOptOutEdgeToEdgeEnforcement`** | 这是 API 35 提供的临时豁免开关。我们**不用**它——已经手动 edge-to-edge，用它反而要额外回归 |
| **后台启动 Activity 限制** | `moveTaskToBack(true)` 是退到后台，不是启动 Activity；悬浮教练流程不涉及 |

### 2.6 顺带必须修的一项：POST_NOTIFICATIONS 从未请求

- **事实**：Manifest 声明了，但 `grep POST_NOTIFICATIONS MainActivity.kt` **零命中**。
- **后果**：Android 13+ 上用户从未被询问 → 前台服务通知**不显示** → ① 隐私政策承诺的"通知栏常驻 + 可点击停止"落空；② 用户看不到"正在录屏"的系统提示，这对"录屏"这种敏感权限是**信任灾难**。
- **要改什么**：`startFloatingCoach()` 里在拿到 `Settings.canDrawOverlays` 之后、`createScreenCaptureIntent()` 之前，插入 `requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)`（仅 `SDK_INT >= 33`）。**顺序很重要**：先通知权限、再录屏授权，否则录屏会话开始时通知发不出来。
- **拒绝时怎么办**：不阻断。录屏仍可启动，但用一次 `AlertDialog` 说明「不开通知，你就看不到『正在录屏』的提示，也无法从通知栏一键停止」。
- **优先级：发布必须（合规）**

---

## 3. 崩溃上报：结论是「保持零网络」

### 3.1 结论

**保持零网络权限，不接任何崩溃上报 SDK。改用「本地崩溃日志 + 设置页导出诊断信息」。**

### 3.2 一句话理由

**"未申请 INTERNET 权限"是本产品唯一一条无法被质疑的合规证据，而它同时是「录屏 + 悬浮窗」这条最敏感权限的解释支柱（PRD §风险 2 的第 2 条论据）——为了拿到崩溃栈而拆掉它，等于用最硬的资产换最软的需求。**

### 3.3 利弊账（给 clover 决策用）

| 维度 | 保持零网络（推荐） | 接入 Crashlytics / Bugly |
|---|---|---|
| 隐私政策 | 只需修 §2.3 的通知承诺 | 需新增"第三方 SDK"条目 + "会出网"说明 + 崩溃数据字段清单 |
| 五家商店 SDK 披露 | 现在是 2 个条目（ML Kit ×2），华为对颗粒度要求最细，2 条很好写 | 变 3 个条目，且新 SDK 属"会采集设备标识/日志"，**华为几乎必然追问** |
| 「系统层面无法联网」论据 | 成立，且是审核问询时的杀手锏 | **作废**。以后所有隐私表述都要改成"我们承诺不上传"——从能力约束降级为口头承诺 |
| 崩溃可观测性 | 无自动上报，靠用户主动导出 + 邮箱 | 自动聚合、按版本/机型分组、崩溃率曲线 |
| 上线初期风险 | 崩溃率不可见，只能靠"用户说闪退" | 能第一时间看到 top crash |
| 工程成本 | 低（见 §3.4，约 120 行） | 中（接 SDK + 改 Compliance 材料 + 重新走一遍隐私评审） |
| 合规风险 | 低 | **中高**（新增 SDK 披露是五家共同的退回理由） |

### 3.4 替代方案的具体设计（可落地、零依赖）

新增 `diag/Diag.kt`（纯 Kotlin + `java.io`，无第三方）：

```kotlin
object Diag {
    /** 环形日志：3 个文件 × 64KB，超出滚动覆盖 */
    fun init(context: Context)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
    /** 崩溃时由全局 handler 调用，写 crash-<ts>.txt 并保留最近 3 份 */
    fun recordCrash(t: Throwable)
    /** 设置页「导出诊断信息」：打包成 zip 走 ACTION_SEND */
    fun exportBundle(): File
}
```

配套 `ShotTunerApp : Application`（新增，Manifest 注册 `android:name`）：

```kotlin
Thread.setDefaultUncaughtExceptionHandler { t, e ->
    Diag.recordCrash(e)                       // 先落盘
    defaultHandler?.uncaughtException(t, e)   // 再交给系统（保留标准崩溃行为）
}
```

**关键点：不要吞掉崩溃。** 必须先落盘再转发给原来的 handler，否则系统不再弹"应用已停止"，用户会看到一个冻住的界面。

设置页新增「导出诊断信息」按钮 → `Diag.exportBundle()` → `FileProvider` 分享（复用现有 `file_paths.xml`，需加 `<files-path name="diag" path="diagnostics/"/>`）。用户把这个 zip 发到 `1912811308@qq.com`，就是我们的"手动 Crashlytics"。

**若 clover 仍想要自动化**：唯一不破合规的做法是**只在 debug 包接**——新增 `app/src/debug/AndroidManifest.xml` 加 `INTERNET` + 只在 `debugImplementation` 引入 SDK。release 包依旧零网络。但这需要新增依赖，**请 clover 明确确认**，我不默认这么做。

---

## 4. 正式签名方案

### 4.1 生成命令（clover 本人执行，离线环境）

```bash
keytool -genkeypair -v \
  -keystore shottuner-release.jks \
  -alias shottuner \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=ShotTuner, OU=Mobile, O=ShotTuner, L=Beijing, ST=Beijing, C=CN"
```

| 参数 | 取值 | 理由 |
|---|---|---|
| 算法 | `RSA 4096` + `SHA256withRSA` | PRD 要求"RSA 2048 或更高"；4096 是当前无成本的最优选择（密钥只生成一次，签名耗时差异对单次构建可忽略） |
| 有效期 | `10000` 天（≈27 年） | Android 要求签名密钥有效期覆盖应用预期寿命，Google Play 的硬门槛是「至少到 2033-10-22」。10000 天远超 |
| 库格式 | `PKCS12` | JKS 已被 Oracle 弃用；PKCS12 是 Android Studio 默认。**注意：PKCS12 下 store 与 key 密码必须相同**，所以只记一个密码即可，少一个丢失点 |
| `-dname` | 建议填真实主体 | 部分商店会读证书主体做主体一致性核对 |

### 4.2 密码管理：gradle.properties + 环境变量双通道

**绝不把密码写进 `build.gradle.kts`。** 具体写法：

`gradle.properties`（**项目根，加入 .gitignore**）：
```properties
SHOTTUNER_STORE_FILE=../shottuner-release.jks
SHOTTUNER_KEY_ALIAS=shottuner
SHOTTUNER_STORE_PASSWORD=__填真实密码__
SHOTTUNER_KEY_PASSWORD=__填真实密码__
```

也可以放在 `~/.gradle/gradle.properties`（**推荐**，物理上不在仓库目录，即使误提交也不会漏）。

`app/build.gradle.kts`：
```kotlin
val keystorePath = providers.gradleProperty("SHOTTUNER_STORE_FILE").orNull
signingConfigs {
    if (keystorePath != null) {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = providers.gradleProperty("SHOTTUNER_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("SHOTTUNER_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("SHOTTUNER_KEY_PASSWORD").get()
        }
    }
}
buildTypes {
    release {
        // 没有正式签名就构建失败，而不是静默回落到测试密钥
        signingConfig = signingConfigs.findByName("release")
            ?: throw GradleException("缺少正式签名配置：请在 gradle.properties 提供 SHOTTUNER_* 四项")
    }
}
```

> ⚠️ 这行 `?: throw` 是整套方案里最重要的一行。没有它，CI 或别的机器上漏配密码时会**静默用测试密钥出包**，而这种包只能在本地装、永远上不了架，且直到备案才发现。
> ⚠️ 建议把 `signingConfigs.create("local")` 整段**删除**（连同 `app/shottuner.jks`），彻底断掉"用测试密钥发布"的可能。

`.gitignore`（**当前项目没有这个文件，必须新建**）：
```gitignore
# 签名与本地路径
*.jks
*.keystore
local.properties
gradle.properties
# 构建产物
build/
.gradle/
.gradle-native/
dist/
work/
```

> 现状风险：目录**不是 git 仓库**，所以今天没泄露。但只要有人 `git init && git add .`，`app/shottuner.jks` + `local.properties` 会一起进历史。**建议在执行任何 git 操作之前先建 `.gitignore`。**

### 4.3 备份清单（离线、两份、两个物理位置）

| # | 内容 | 获取方式 |
|---|---|---|
| 1 | `shottuner-release.jks` 文件本体 | 生成后立即复制 |
| 2 | `keyAlias` = `shottuner` | 记录在文本 |
| 3 | store password | 密码管理器 + 纸质 |
| 4 | key password（PKCS12 下同上） | 同上 |
| 5 | **SHA-256 指纹** | `keytool -list -v -keystore shottuner-release.jks -alias shottuner` |
| 6 | **SHA-1 / MD5 指纹** | 同上输出（部分商店表单仍要 MD5 大写无冒号） |
| 7 | `keytool -genkeypair` 的完整命令与 `-dname` | 便于核对主体 |
| 8 | 首次出包的 `versionCode` 与日期 | 备案与商店记录要用 |

> 备份建议：① 一个加密 U 盘；② 一个不与开发机同域的云盘（或打印成纸质放保险处）。**不要只存在开发机上**——重装系统就是永久失去更新能力。
> PRD §5.4 说得对：这是发布流程里**唯一不可逆**的一步。

### 4.4 换签名 + 换包名后，已装测试版会怎样

| 场景 | 结果 |
|---|---|
| 手机上已有 `com.clover.shottuner`（v2.1.0 测试版） | **不会自动更新、不会被覆盖、不会收到提示**。它会和新包 `com.shottuner.app` **并存**，桌面两个图标（都叫「光影调参」） |
| 覆盖安装新包 | `adb install -r` 会因签名或包名不同而失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_ALREADY_EXISTS`） |
| 旧版数据 | prefs 读不到（新包独立目录）；相册里的照片**不受影响**（在系统媒体库里，不属于 App） |
| 正确操作 | 发布前：`adb uninstall com.clover.shottuner` 和 `adb uninstall com.clover.shottuner.debug`，再装新包 |

> **对 clover 的提醒**：如果你手机上留着旧图标，给审核看的截图里可能出现两个一样的「光影调参」，容易被当成套壳。发布截图前务必先卸载旧版。

---

## 5. 图标与启动图

### 5.1 现状问题

`AndroidManifest.xml:23` 是 `android:icon="@drawable/ic_app_icon"`，一个 **VectorDrawable**，没有 mipmap、没有 `roundIcon`、没有自适应图标。三个后果：

1. **Android 8+ 显示为"白底小图标"**：系统找不到自适应图标时会把 vector 塞进一个纯色圆/方里，图标看起来比别的小一圈 —— 在国内商店的列表页截图里非常明显。
2. **部分 OEM 桌面（MIUI / EMUI / ColorOS）对 vector 图标的渲染有兼容问题**，个别机型会显示空白或黑块。
3. **没有 `mipmap` 目录 = 系统无法按密度挑最合适的位图**，桌面图标在 3x 屏上会被放大插值，发虚。

### 5.2 生成 mipmap 全套的具体做法

源文件是 Android VectorDrawable（`res/drawable/ic_app_icon.xml`），**不能直接喂给 ImageMagick / cairosvg**（它们吃 SVG）。两条可行路径：

**路径 A（推荐）· 手写一份 SVG 母版**
图标若只是"取景框 + 光圈"这类几何图形，用 SVG 重画一遍最省事（10 分钟），且以后改色/改形只改一处。

```bash
# 一次性装工具（不引入到 App 依赖里，只是构建期脚本）
python -m pip install cairosvg

# 512/1024 商店主图 + 各密度
python - <<'PY'
import cairosvg
sizes = {48:"mdpi",72:"hdpi",96:"xhdpi",144:"xxhdpi",192:"xxxhdpi"}
for px, dens in sizes.items():
    cairosvg.svg2png(url="tools/icon-master.svg",
                     write_to=f"app/src/main/res/mipmap-{dens}/ic_launcher.png",
                     output_width=px, output_height=px)
    cairosvg.svg2png(url="tools/icon-master.svg",
                     write_to=f"app/src/main/res/mipmap-{dens}/ic_launcher_round.png",
                     output_width=px, output_height=px)
for px in (512, 1024):
    cairosvg.svg2png(url="tools/icon-master.svg",
                     write_to=f"store/icon-{px}.png",
                     output_width=px, output_height=px)
PY
```

**路径 B · 用 ImageMagick（需 rsvg 代理）**
```bash
magick -background none -density 1200 tools/icon-master.svg -resize 512x512 store/icon-512.png
magick tools/icon-512.png -resize 48x48  app/src/main/res/mipmap-mdpi/ic_launcher.png   # 其余同理
```

> 目录必须真的叫 `mipmap-*`（不是 `drawable-*`）——`ic_launcher` 放 drawable 时，部分 OEM 桌面不认。

### 5.3 自适应图标（Android 8+）

新建 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

三层说明：

| 层 | 内容 | 约束 |
|---|---|---|
| `background` | 纯色（建议 `#0E1113`，与 App 主色一致） | 必须铺满 108×108dp，不留透明 |
| `foreground` | VectorDrawable，`viewportWidth/Height = 108` | **安全区：内容必须落在中心 66×66dp 内**（即四边各留 21dp）。超出部分会被各家桌面的遮罩裁掉 —— 这是自适应图标最常见的翻车点 |
| `monochrome` | 同 foreground（Android 13 主题图标用） | 只能是单色+透明，不能有色彩 |

配套：
- `values/colors.xml` 加 `<color name="ic_launcher_background">#0E1113</color>`
- Manifest 改 `android:icon="@mipmap/ic_launcher"` + `android:roundIcon="@mipmap/ic_launcher_round"`
- **`CameraTileService` 的 `android:icon` 也要改**（`Manifest:59` 现在指向 `@drawable/ic_app_icon`），否则磁贴图标仍是被缩放的 vector

### 5.4 Android 12+ 启动画面（零依赖做法）

**不要引 `androidx.core:core-splashscreen`**（那是新依赖）。API 31+ 系统原生就支持，只需加 `values-v31/themes.xml`：

```xml
<resources>
    <style name="Theme.ShotTuner" parent="Theme.Material3.Dark.NoActionBar">
        <item name="android:windowSplashScreenBackground">#0E1113</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="android:windowSplashScreenAnimationDuration">200</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">#0E1113</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowBackground">#0E1113</item>
    </style>
</resources>
```

- API < 31 的设备看不到这个（`windowBackground` 起同样作用），视觉上是"深色底直接进界面"，无突兀。
- `windowSplashScreenAnimationDuration` 只影响图标动画时长（≤1000ms），**不要设太大**——这是工具不是表演。

---

## 6. 隐私政策落地

### 6.1 用原生 TextView，不用 WebView

| 方案 | 判断 |
|---|---|
| **WebView + 本地 HTML** | ❌ 不用。① 本 App **无 INTERNET 权限**，只能 `loadUrl("file:///android_asset/...")`，一旦某天有人加了 `setAllowFileAccessFromFileURLs(true)` 就是本地文件读取漏洞；② WebView 会引入"应用内能打开网页"的观感，与"零网络"叙事冲突；③ 字体缩放、深色模式、无障碍都要自己再适配一遍 |
| **原生 `TextView` + `ScrollView`** | ✅ 用。零依赖、跟随系统字体缩放、Speech 朗读可用、`textIsSelectable` 支持复制（用户可能要复制联系方式） |

**内容存放**：`res/raw/privacy_v1.txt`（纯文本 + 极简标记），用一个渲染函数把 `# ` 开头转成粗体、`## ` 转成小标题、`- ` 转成项目符号。**不要**塞进 `strings.xml`（552 行会触发 XML 转义地狱，`%` 和 `'` 都是坑），也**不要**写成 Kotlin 常量（编译期字符串常量池会变大）。

### 6.2 首次启动同意流程

**位置**：一个全屏覆盖层（`ConstraintLayout` 顶层 + `z` 序最高），不是新 Activity。理由：新 Activity 会成为第二个启动入口，用户/审核可以直接跳过。

**四个入口（缺一不可）**：

| 入口 | 行为 |
|---|---|
| 摘要区 | 内联 5 行关键结论（不收集 / 不上传 / 录屏只读像素 / 无广告 / 可随时撤回），来源与隐私政策摘要表一致 |
| 「查看完整隐私政策」 | 打开只读全屏页（TextView + ScrollView），带返回 |
| 「同意并继续」 | 写入 `consent_version`，关闭覆盖层，进正常流程 |
| 「暂不同意」 | **不退出 App**，进入「受限模式」，见 §6.3 |

**判断"已同意过"**：`SharedPreferences` 存 `consent_version: Int`。
- 读到的值 `< CURRENT_CONSENT_VERSION` → 重新弹（PRD §八.2 要求"新增权限/新增收集时重新征求同意"）；
- 相等 → 不再弹。
- **必须存版本号而不是布尔值**——否则以后加权限没法重新征得同意，这是审核复查时的常见死穴。

### 6.3 「暂不同意」之后 App 表现成什么样

这是最容易做错的地方。**不能直接退出**（会被判"强制授权、不给权限就不让用"），也不能全功能可用（等于没同意）。

**设计：受限模式。**

| 功能 | 受限模式行为 |
|---|---|
| 技法页（83 条） | ✅ **完全可用**。这是最好的"价值先给"——不触任何权限，纯本地文本 |
| 录像页 / 功能页 | ✅ 可用（纯计算 + 本地读 profile，不触相机） |
| 照片页取景 | ❌ 不启动相机。显示一张说明卡：「看技法不需要权限。要用相机测光，需要先同意隐私政策」+「去同意」按钮 |
| 快门 / 音量键 | ❌ 禁用（`dispatchKeyEvent` 与 `onShutterClick` 各加一道 `consentGiven` 判断） |
| 悬浮教练 | ❌ 禁用 |
| 顶栏 | `tvMode` 位置常驻一行「未同意隐私政策 · 去同意」（可点） |

**为什么这样设计**：审核方对"不同意就退出"容忍度低，对"不同意仍可用非个人信息相关功能"评价正面。而且技法库是本产品内容量最大、最不需要权限的部分。

### 6.4 「关于」页与双清单（PRD 风险 6）

新增一个覆盖层页（与隐私政策同一套 UI），三个入口：

1. 隐私政策（全屏）
2. **个人信息收集清单** → 一页：「本应用不收集任何个人信息」，然后逐条列"我们不收集什么"（直接引 PRD §一）
3. **第三方共享清单** → 一页：ML Kit ×2 的表格（SDK / 提供方 / 版本 / 用途 / 收集信息 = 无 / 是否联网 = 否）

> 这三页的文案直接复用 `privacy_v1.txt` 的分节，**不要另写一套**——两套文案不同步是审核最常抓的矛盾点。

### 6.5 隐私政策的可访问 URL

商店要求一个**可公开访问的网页 URL**。三个方案：

| 方案 | 可行性 | 备注 |
|---|---|---|
| **腾讯云 COS / 阿里云 OSS 静态网站** | ✅ 推荐 | 国内访问稳定、可绑自有域名、有 HTTPS。成本 ≈ 每月几分钱。**备案时如果已经有这个域名，顺手就能用** |
| **Gitee Pages** | ✅ 次选 | 免费、国内可访问。缺点：需要实名 + 仓库公开（源码不能放，只放 HTML） |
| Gitee/GitHub 仓库里的 `.md` 直链 | ❌ 不要 | GitHub Raw 国内经常打不开，审核方打不开 = 视为"无有效隐私政策 URL" |
| 语雀 / 飞书文档公开链接 | ⚠️ 视商店而定 | 部分商店不接受第三方文档站，要求"独立网页"。提审前逐家确认 |

**部署建议**：`privacy.shottuner.app` 或 `shottuner.app/privacy` 单页 HTML，纯静态、无 JS、有 `<meta name="viewport">`，正文与 `privacy_v1.txt` **逐字一致**。改一次版本就同步改两处（App 内 + 网页），这也写进 §7 的发布检查清单。

---

## 7. 其他发布必做

| # | 项 | 判断 | 改法 |
|---|---|---|---|
| 1 | **`allowBackup`** | 改 `false` | `AndroidManifest.xml:22`。理由：① 我们的 prefs 只是 UI 设置，无备份价值；② `allowBackup=true` 会让 MIUI/华为云备份把 prefs 带出设备，而隐私政策写的是"数据只在你的手机本地"——**云备份会打自己的脸**；③ 同时补 `android:dataExtractionRules`（API 31+）与 `android:fullBackupContent="false"` |
| 2 | **版本号** | `versionName = "3.0.0"`，`versionCode = 30` | `build.gradle.kts:17-18`。跳过 2.2.0 直接 3.0.0 的理由：包名变了 = 全新应用，沿用 2.x 会让人以为"还是那个能覆盖升级的版本"；3.0.0 明确表示"新起点" |
| 3 | **R8 规则审查** | 够用，但**建议删两条** | 现有 `proguard-rules.pro` 里有 `-keep class com.google.mlkit.** { *; }` 与三个 `com.google.android.gms.internal.mlkit_vision_*`。ML Kit 的 AAR **自带 consumer rules**，这几条是冗余的（文件注释自己也说"正常情况无需手写"）。冗余 keep 会让 APK 大几百 KB。**但发布前不建议动**——收益小于回归风险，留到 v3.0.1 |
| 4 | **自己代码的反射/序列化点** | **无**。全仓无 `Class.forName` / `Gson` / `kotlinx.serialization` / `Parcelable` 自定义实现；`viewBinding` 生成的类由 AGP 自己 keep；`CameraTileService` 走类引用 | 不需要加规则 |
| 5 | **`-renamesourcefileattribute SourceFile`** | **建议保留**（现状已保留） | 它把崩溃栈的文件名抹成 `SourceFile`，但**保留行号**。若走 §3.4 的本地日志方案，行号 + `mapping.txt` 足够定位；若抹掉行号就没法用了。**必须归档每次发布包的 `mapping.txt`** |
| 6 | **`WRITE_EXTERNAL_STORAGE`（maxSdk 28）** | **保留** | `minSdk = 24`，Android 7/8/9 仍有存量。`maxSdkVersion="28"` 保证在 10+ 设备上**用户看不到这条权限**（这是合规加分项，别删）。若 clover 决定放弃 7/8/9，可把 `minSdk` 升到 29 并删掉这条权限，但会掉一批低端机 |
| 7 | **`uses-feature camera required=true`** | 保留 | 会过滤掉无摄像头的平板/车机，正确 |
| 8 | **`screenOrientation="portrait"`** | 保留 | 见 R11。分屏下系统会忽略，属预期 |
| 9 | **`tools:targetApi="34"`**（`Manifest:27`） | 改 `35` | 只是 lint 提示，但留着会误导下一个人 |
| 10 | **新增 `Application` 类** | 必做 | `ShotTunerApp`：初始化 `Diag` + 安装全局未捕获异常处理器（§3.4）。Manifest 加 `android:name=".ShotTunerApp"` |
| 11 | **`android:enableOnBackInvokedCallback`** | 设为 `true` | 配合 U7 的返回键改造。不设的话 targetSdk 33+ 默认行为不一致 |
| 12 | **`gradle/wrapper/`** | 建议补 | 当前缺失，构建不可复现。`gradle wrapper --gradle-version 8.9` 生成一次即可 |
| 13 | **`.gitignore`** | 必须新建 | 见 §4.2。当前不存在 |

---

## 8. 实施顺序

> 标注：**【C】** = 必须 clover 本人先做；**【串】** = 必须等前一步；**【并】** = 可并行。

### 阶段 0 · clover 前置（阻塞全部，串行）

| 序 | 事项 | 类型 |
|---|---|---|
| 0.1 | 生成正式 keystore，记录 SHA-256 / SHA-1 / MD5 指纹，双份离线备份 | **【C】【串】** |
| 0.2 | 确认应用名「光影调参」为软著/备案/商店统一名称 | **【C】** |
| 0.3 | 注册五家开发者账号 + 实名（越早越好，若某家限制个人上架敏感权限，需提前知道） | **【C】【并】** |
| 0.4 | 准备隐私政策托管域名/存储桶（§6.5） | **【C】【并】** |

> **0.1 必须先于 0.3、0.4 之后的任何出包动作**，因为备案要签名指纹。PRD §5.3 的顺序是对的：**定名 → 生成密钥 → 包名迁移 → 出正式包 → 备案 → 回填商店**。

### 阶段 1 · 工程骨架（串行，1 天）

| 序 | 文件:改动 | 依赖 |
|---|---|---|
| 1.1 | 新建 `.gitignore`（§4.2 清单） | 0.1 |
| 1.2 | 根 `build.gradle.kts`：AGP `8.5.2 → 8.6.1` | 无 |
| 1.3 | `app/build.gradle.kts`：`compileSdk/targetSdk 34 → 35` | 1.2 |
| 1.4 | 补 `gradle/wrapper/` | 1.2 |
| 1.5 | **包名完整迁移**（§1.4 的 3 条命令 + 手工核对 §1.2 的 9 个点） | 无（可与 1.2 并行） |
| 1.6 | `app/build.gradle.kts`：删 `signingConfigs.local`，加 `signingConfigs.release`（gradle.properties 注入 + `?: throw`） | 0.1、1.5 |
| 1.7 | `app/build.gradle.kts`：`versionCode 30 / versionName "3.0.0"` | 1.5 |
| 1.8 | 先跑一次 `assembleDebug` 确认编译通过（**此步之前不要动业务代码**，把"包名迁移导致编译失败"与"业务改动导致失败"分开） | 1.5 |

### 阶段 2 · 合规与签名（串行，1.5 天）

| 序 | 文件:改动 | 优先级 |
|---|---|---|
| 2.1 | 新增 `MainActivity` 隐私同意覆盖层 + `res/raw/privacy_v1.txt` + 关于页（三个入口） | **发布必须** |
| 2.2 | 受限模式（§6.3 的 6 条判断 + 顶栏常驻入口） | **发布必须** |
| 2.3 | `MainActivity:startFloatingCoach()` 插入 `POST_NOTIFICATIONS` 请求（§2.6） | **发布必须** |
| 2.4 | `ScreenCaptureService.buildNotification()` 加 `contentIntent` + `action` + 换 smallIcon（§2.3） | **发布必须（合规）** |
| 2.5 | `ScreenCaptureService` 全部路径改 `START_NOT_STICKY` + null intent 处理（§2.2） | **发布必须（崩溃）** |
| 2.6 | `AndroidManifest.xml`：`allowBackup=false` + `dataExtractionRules` + `tools:targetApi=35` + `enableOnBackInvokedCallback=true` + `android:name=".ShotTunerApp"` + `icon/roundIcon` 换 mipmap | **发布必须** |
| 2.7 | 图标：`tools/icon-master.svg` → 全套 mipmap + `mipmap-anydpi-v26/ic_launcher.xml` + 512/1024 商店图（§5.2-5.3） | **发布必须**（商店提审要图） |
| 2.8 | `values-v31/themes.xml` 启动画面（§5.4） | 建议 |
| 2.9 | 隐私政策网页部署（与 `privacy_v1.txt` 逐字一致） | **发布必须**（商店要 URL） |

### 阶段 3 · 可靠性加固（可与阶段 2 并行，2 天）

| 序 | 项 | 文件:改动 | 优先级 |
|---|---|---|---|
| 3.1 | 新增 `ShotTunerApp` + `diag/Diag.kt` + 全局异常处理器 | 新增 | **发布必须** |
| 3.2 | R2 相机失败回调 + 面板错误条 + 重试按钮 | `CameraEngine:84,222`、`MainActivity` | **发布必须** |
| 3.3 | R9 `onResume` 补 `startCamera()` 判断 | `MainActivity:onResume` | **发布必须** |
| 3.4 | R3 相机权限「不再询问」→ 去设置入口 | `MainActivity:242` 附近 | **发布必须** |
| 3.5 | U4 两处暴露异常类名/消息的文案 | `MainActivity:1469,1762` | **发布必须**（2 行） |
| 3.6 | U7 返回键三级处理 | `MainActivity` 新增 `OnBackPressedCallback` | **发布必须** |
| 3.7 | R1 A 类静默异常改 `Diag.e`（相机绑定、录屏建立、拍照） | 5 处 | **发布必须** |
| 3.8 | R1 B 类改 `Diag.w`（传感器/探针/坏帧） | 约 20 处 | 建议 |
| 3.9 | R8 AI 看门狗（`busy` 超 3s 复位） | `AiVision:116-123` | 建议 |
| 3.10 | U8 页签 `36dp` → `minHeight` | `activity_main.xml` 4 处 | 建议 |
| 3.11 | U9 `tvIso` 自动缩放字号 | `activity_main.xml` | 建议 |
| 3.12 | 阶段 1.3 的 edge-to-edge 收尾（`llControlBar` inset + 导航栏图标色） | `MainActivity:362-370` | **发布必须** |

### 阶段 4 · 出包、回归、备案（串行）

| 序 | 事项 |
|---|---|
| 4.1 | `./gradlew clean :app:assembleRelease` → 校验 `apksigner verify --print-certs` 的指纹 == 0.1 记录值 |
| 4.2 | 归档 `app/build/outputs/mapping/release/mapping.txt` |
| 4.3 | 真机回归矩阵：Android 9（旧权限路径）/ 13（通知权限）/ 14（逐次录屏确认）/ 15（edge-to-edge）；小屏 360dp + 大屏 412dp；字体 1.0x + 1.5x（§9 的 U8 清单） |
| 4.4 | 121 个 JVM 单测全绿 + 新增的 Diag/同意流程如有测试 |
| 4.5 | **备案**（要包名 + 签名指纹）→ 拿备案号 |
| 4.6 | 五家商店提审（建议先投审核流程最透明的一家，说辞打磨顺了再投其余四家 —— PRD §1.2 差异三） |

---

## 9. 可靠性与可用性加固清单

> 全部结论基于 v2.1.0 真实代码。**"现状"是我读代码写的，"判断"是我对这个 App 会不会中招的结论。**
> 判断不准的地方标「需真机验证」，不猜。

### 9.1 可靠性（R）

#### R1 · 未捕获异常与静默吞异常

**现状**
- **无全局 `Thread.setDefaultUncaughtExceptionHandler`**（全仓零命中）。
- **静默 `catch` 共 42 处 / 10 个文件**：

| 文件 | 处数 | 行号 |
|---|---|---|
| `camera/CameraEngine.kt` | 6 | `:84`（provider 获取）、`:93`（unbindAll）、`:215`/`:222`（bind 两次尝试）、`:254`（readCapabilities） |
| `screen/ScreenCaptureService.kt` | 7 | `:118`（getMediaProjection）、`:154`（acquireLatestImage）、`:171`（整帧处理）、`:257/262/267/272`（cleanup 4 处） |
| `MainActivity.kt` | 8 | `:1434/1464`（拍照文件删除）、`:1538/1542`（相册写入）、`:1694`（分享）、`:1707`（FileProvider） |
| `device/HardwareProbe.kt` | 7 | 能力探测各字段 |
| `device/OrientationTracker.kt` | 4 | sensor 注册/回调 |
| `screen/CoachOverlay.kt` | 3 | wm.addView/updateViewLayout/removeView |
| `analysis/AiVision.kt` | 2 | `:127` InputImage、`:221` close |
| `analysis/FrameAnalyzer.kt` | 2 | 坏帧丢弃 |
| `tile/CameraTileService.kt` | 2 | 磁贴启动 |
| `screen/ScreenStats.kt` | 1 | 帧统计 |

**判断：会中招，而且是发布版最痛的一类问题。** 43MB 的包、5 家商店、零崩溃上报——用户遇到"黑屏 / 点了没反应 / 悬浮卡不出"时，我们**什么都收不到**。

**分级方案（这是本节最重要的产出）**

| 级别 | 范围 | 处理 |
|---|---|---|
| **A · 必须给用户可见反馈** | `CameraEngine:215/222`（相机绑定失败）、`ScreenCaptureService:118`（录屏会话建立失败）、`MainActivity` 拍照失败路径 | `Diag.e` + **UI 可见提示** + 重试入口（见 R2） |
| **B · 记日志，不上 UI** | `HardwareProbe` 7 处、`OrientationTracker` 4 处、`FrameAnalyzer` 2 处、`ScreenStats` 1 处、`AiVision` 2 处 | `Diag.w(tag, msg, t)`；功能已降级（无传感器 → 水平度"未测"），用户不需要知道 |
| **C · 保持静默** | 所有 cleanup 里的 `release/close/unbindAll/delete`、`CoachOverlay` 的 wm 操作 | 保持原样。这些地方抛异常时"继续执行清理"就是正确行为，弹提示反而更糟 |

**改法**：新增 `diag/Diag.kt`（§3.4）+ `ShotTunerApp` 安装默认 handler（先落盘再转发）。
**优先级：A 级 → 发布必须；全局 handler → 发布必须；B 级 → 建议；C 级 → 不改。**

---

#### R2 · 相机初始化失败 / 被占用

**现状**
- `CameraEngine.start()` 的 future 回调 `:80-86`：`catch (_: Throwable) {}` —— **完全静默**，且不调 `onReady`。
- `bind()` `:210-224`：先试一次，失败再试一次，**两次都失败也静默**。
- 后果链：`onReady` 不回调 → `caps` 停在 `CameraCaps.UNKNOWN` → 参数区永远 `--`、`tvScene` 永远"正在识别场景"、`previewView` 全黑。**用户看到的是一块黑屏加一堆横杠，没有任何文字说明，也没有重试入口。**

**判断：大概率会遇到。** 相机是独占资源，微信视频通话、系统相机后台未释放、某些 ROM 的"相机占用"都会触发。这是发布版最容易收到"打开就是黑的"差评的点。

**改法**
1. `CameraEngine` 构造参数加 `onError: (reason: String) -> Unit`；`:84` 与 `:222` 都调用它（A 级）。
2. `bind()` 的第二次失败分支里，**先尝试 `provider.unbindAll()` 再只绑 `Preview`**（去掉 `ImageAnalysis` 与 `ImageCapture`）——很多"绑定失败"是三个 UseCase 组合超限导致的，降级到单 Preview 往往能出画面，至少用户看得见。
3. `MainActivity` 收到错误后：`panel` 顶部插入错误条「相机启动失败：可能被其他应用占用」+「重试」按钮（`engine.stop(); engine = null; startCamera()`）；首次失败自动重试 1 次（800ms 后）。
4. 相机就绪慢（>2.5s）时，`tvScene` 显示「相机启动中…」而不是"正在识别场景"（见 U3）。

**优先级：发布必须。**

---

#### R3 · 权限拒绝路径

| 场景 | 现状 | 判断 | 改法 |
|---|---|---|---|
| **CAMERA 被拒** | `:242` 只显示一行文字「未获得相机权限，无法分析画面」 | ⚠️ **有问题**：没有"去设置"入口。用户选了"不再询问"后，`requestCamera.launch()` 会**静默失败**（系统直接回调 denied，不弹窗），用户以为按钮坏了 | 区分 `shouldShowRequestPermissionRationale`：返回 false 且未授权 = 已永久拒绝 → 显示「去设置开启」按钮 → `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))` |
| **SYSTEM_ALERT_WINDOW 被拒** | `:1739-1748`：`canDrawOverlays` false → Toast + 跳设置，回来需**再点一次**按钮 | 可接受，但不够顺 | `onResume` 检测 `canDrawOverlays` 由 false 变 true 且有待续流程 → 自动继续 `createScreenCaptureIntent()` |
| **MediaProjection 取消** | `:261` Toast「没有录屏权限，悬浮卡起不来」 | 文案可读，但**没说清为什么需要**。PRD §风险 2 要求"专门的说明页" | 见 U6：改成说明页 + 「我已知晓」，取消后回到说明页而不是只弹 toast |

**优先级：CAMERA → 发布必须；另两项 → 建议。**

---

#### R4 · MediaProjection 中断 / 孤儿浮窗

**现状**
- `MediaProjection.Callback.onStop` → `cleanup()` + `stopSelf()`（`:128-133`）✅
- `cleanup()` `:278` `main.post { overlay?.hide(); overlay = null }` ✅
- `onDestroy` → `cleanup()` ✅
- 通知**无 `contentIntent`、无停止按钮**（`:239-252`）❌

**判断**
- **孤儿浮窗：不会发生。** WindowManager 的 window 属于本进程，进程被杀时 window 随之消失，不存在"留在屏幕上"的残留。这一点可以放心。
- **但"服务静默消失"会发生**：系统回收前台服务后，`START_STICKY` 触发重建 → null intent → （当前代码）不 `startForeground` → API 34+ 抛异常。用户视角是"悬浮卡突然没了，且没有通知提醒"。
- **通知无法停止 = 隐私政策承诺落空**（N1），这是真正的合规风险。

**改法**：① `START_NOT_STICKY` + null intent → `stopSelf()`（§2.2）；② 通知加 `contentIntent` + 「停止」action（§2.3）；③ `CoachOverlay` 的"停"按钮改为发 `ACTION_STOP` 的 `PendingIntent`（现在直接 `stopSelf()`，从外部拿到的是 Service 对象，跨进程语义不清）。

**优先级：①② → 发布必须；③ → 建议。**

---

#### R5 · 快门防重入

**现状：三重保护，已经做对了。**
- `onShutterClick()` 首行 `if (captureInProgress) return`
- `capturePhoto()` 首行再判一次 `if (captureInProgress) return`
- `dispatchKeyEvent` 里也判 `!captureInProgress`（`:392`）
- `binding.btnShutter.isEnabled = false` 兜 UI
- `captureInProgress` 在成功/失败两条回调里都复位（`:1456` / `:1470` 附近）

**判断：连点 5 次 = 只拍 1 张，不崩。无需改动。**
唯一提醒：`captureInProgress` 复位发生在**主线程回调**里，若相册写入线程卡住（存储慢），复位会延后 → 那段时间按快门无反应，属预期行为，但要保证 UI 有"正在保存"的反馈（当前 `btnShutter.isEnabled=false` 就是反馈，够用）。

**优先级：无需修（记录备查）。**

---

#### R6 · 相册写入失败 / 存储满

**现状：已经做对。**
- `saveShotToGallery()` 全路径 `try/catch` → 失败返回 `null`（`:1538/1542`）
- `insert` 成功但写流失败 → `contentResolver.delete(uri)` 清理半截文件（`:1530` 附近）
- 返回 `null` 时走缓存 + 复盘卡显示「未能写入相册，已存到缓存，长按快门可分享」✅ 文案可读
- 权限不足（API<29）也走同一分支 ✅

**判断：不会中招。** 存储满（`ENOSPC`）会以 `IOException` 形式被捕获，行为正确。

**唯一建议**：把「长按快门可分享」改成复盘卡上的一个显式按钮 —— 长按是不可发现交互，用户不会知道。**优先级：可延后。**

---

#### R7 · 生命周期

**现状**
- `CameraEngine.stop()` `:90-96`：`unbindAll()` + `analysisExecutor.shutdown()`。**shutdown 后不可复用**。
- 但 `stop()` 只在 `MainActivity.onDestroy` 调（`:1988` 附近），实例不再复用；配置变更/进程恢复会 `new` 新实例 ✅
- `aiVision` 在 `onDestroy` 里 `close()`；`orientationTracker` / `levelSensor` 在 `onPause` stop、`onResume` start ✅

**判断：不会崩，但有一个真实缺口。**
- **缺口（真问题）**：`onResume` 不检查相机是否还在跑。用户第一次拒绝相机权限 → 去系统设置授予 → 返回 App（`onResume` 触发）→ **画面仍然黑着**，因为 `startCamera()` 只在 `onCreate` 和权限回调里调。这是 R3 的延伸，必须一起修。
- 次要：配置变更（字体缩放/深色切换/分屏 resize）会重建 Activity → `promptScheduler` 的黑名单与冷却被重置。**可接受**（`uiPrefs` 里的密度/预设/音量键开关都走 SharedPreferences，已正确持久化）。

**改法**：`onResume` 加 `if (engine == null && 已授权) startCamera()`。
**优先级：发布必须（与 R3 合并修）。**
不建议加 `android:configChanges` —— 那会把状态同步问题变成布局适配问题，得不偿失。

---

#### R8 · 内存（ML Kit 链路 + 长时录屏）

**现状**
- `AiVision.analyze()`：三条路径都 `recycle()` —— busy 时立即回收（`:120`）、`InputImage` 失败回收（`:129`）、正常完成在 `main.post` 里回收（`:166`）✅
- `FrameAnalyzer` 每 45 帧产一张 160×120 bitmap（≈76KB）✅
- `ScreenCaptureService` 每 320ms 一帧、每 3 帧一次 AI；`ImageReader.newInstance(..., maxImages = 2)` + `acquireLatestImage()` + `finally image.close()` ✅

**判断：无持续增长点。** 但有一个**静默死锁风险**：`busy` 是 `@Volatile` 布尔，若 ML Kit 的 Task **永不回调**（模型加载失败、OOM），`busy` 永远为 true → 之后每帧 bitmap 立即回收（不泄漏）但 **AI 永久停摆**，场景识别静默失效，参数建议质量掉一档，用户与开发者都不知道。

**改法**：给 `busy` 加时间戳看门狗——记录 `busySince`，每次 `analyze` 先判 `now - busySince > 3000` 则强制复位（并 `Diag.w` 记一次）。
**优先级：建议。** 30 分钟长录屏的稳定性主要靠 `maxImages=2` + `KEEP_ONLY_LATEST`，这块已经是对的。

---

#### R9 · 后台恢复

**现状**
- 切后台再回来：`bindToLifecycle(this, ...)` 走 Activity 生命周期自动 rebind ✅；传感器 `onPause/onResume` 成对 ✅；`startCoachLoop/stopCoachLoop` 成对（`:1980-1990` 附近）✅
- 悬浮教练被系统杀：见 R4（不会留孤儿窗，但服务静默消失且通知不可停止）
- **相机权限后授予不重试**：见 R7 的缺口

**判断：主路径正常，两个已知缺口（R4 的服务恢复、R7 的权限重试）。**
**改法**：R4 + R7 的改法。**优先级：随 R4 / R7。**

---

#### R10 · 异常输入

**现状（逐项核对）**
| 输入 | 现有防护 | 判断 |
|---|---|---|
| `lastStats == null` | `buildShotReview()` 有 null 分支返回「还没测到画面」；`renderVideo` 用可空 `lastStats`；`render()` 只在有 stats 时调 | ✅ 安全 |
| `FrameStats` 全零 | `SceneClassifier.classify()` `if (s.width == 0) return GENERAL`；`CompositionAnalyzer` `if (cols < 6 \|\| rows < 6)`；`SharpnessMeter.normalize` 分母 `meanLuma*255 + 8` 恒 >0 | ✅ 安全（无除零） |
| 极端宽高比 | `FrameAnalyzer` `stepX/stepY = max(1, ...)`，`cols/rows >= 4` 才继续 | ✅ 安全 |
| `AiVisionResult` 无脸 | `faceBox = ZERO` + 中心 0.5，`FramingCoach` 只在 `faceCount > 0` 时使用 | ✅ 安全 |
| `caps = UNKNOWN` | `ParamRecommender` 对各字段有 `?:` 兜底 | ✅ 安全 |

**判断：不会中招。** 代码在关键路径上都有防护，且 121 个单测覆盖了纯逻辑边界。
**优先级：可延后**（做一轮"飞行模式 + 无权限 + 刚启动立刻点所有按钮"的手测即可）。

---

#### R11 · 分屏 / 折叠屏 / 平板

**现状**
- `screenOrientation="portrait"`（Manifest）
- `panel` 已是 `layout_height=0dp` + `percent=0.45` + `min=240dp` + `max=560dp` ✅ 自适应
- `llControlBar` 固定 72dp 快门 + 48dp 缩略图 ✅

**判断**
- **API 35 不会忽略 `portrait`**（那是 API 36 / Android 16 对 ≥600dp 屏的变更），所以现在不受影响。
- **分屏模式下系统本来就会忽略 orientation**（multi-window 强制 resize）。此时：高度被压到 400dp 左右 → `panel` percent 0.45 = 180dp，但 `min=240dp` 生效 → 取景区剩 160dp 左右，仍可用但很挤。**不会错乱**（因为用的是约束 + 百分比，不是写死 dp）。
- 折叠屏展开（宽 600dp+）：`panel` 内容横向拉伸，`btnMore` 展开行 4 个按钮会很空但不破；参数四列间距变大，可读性反而更好。
- **真正的风险点**：`tvIso` 23sp 在 4 列布局里，360dp 宽屏时每列仅 ~74dp，`"12800"` 5 位数字会挤（见 U9）。

**判断：不会错乱，但小屏有挤压。**
**优先级：可延后**（发布后按反馈），但 U9 的字号自适应建议在发布前做掉（成本 1 行）。

---

### 9.2 可用性（U）

#### U1 · 首次启动

**现状：完全没有。** 全仓 `grep "隐私\|privacy\|同意\|consent"` 零命中。首次启动直接弹系统相机权限。
**判断：这是五家商店的必查项，必然被拒。**（PRD 风险 1 已定级最高）
**改法**：见 §6.1–6.5。
**优先级：发布必须（最高）。**

---

#### U2 · 拒绝后的引导

**现状**
- 拒绝隐私（现在不存在此流程）→ 无
- 拒绝相机 → 一行文字，无设置入口（R3）
- 拒绝浮窗 → Toast + 跳设置（可用但需手动再点一次）
- 拒绝通知 → 代码根本没请求（§2.6）

**判断：三条路径里两条是死路。**
**改法**：受限模式（§6.3）+ R3 的「去设置」+ `onResume` 自动续跑。
**优先级：发布必须。**

---

#### U3 · 加载态

**现状**
- 相机启动到首帧：`tvScene` = 「正在识别场景」，`tvScore` = 「构图 --」，`tvScoreDetail` = 「参考分 --」，`tvAdvice` = 「把镜头对准要拍的画面」—— **有占位，不是白屏** ✅
- ML Kit 首次推理（模型加载，冷启动约 300–800ms）：期间 `aiResult == null`，`SceneClassifier` 走规则版，**用户无感知**（这其实是优点）
- 缺口：**没有"正在启动相机"与"相机就绪"的区分**。若相机绑定失败，用户看到的占位文案和"正在启动"完全一样，无法分辨（R2）

**判断：基本可用，与 R2 合并修即可。**
**改法**：`tvScene` 三态：`相机启动中…` → `正在识别场景` → `人像 92% · AI·人脸`。由 `onReady` / `onError` 驱动。
**优先级：建议。**

---

#### U4 · 错误文案暴露技术细节

**现状（全仓 20 处 Toast + 2 处 AlertDialog 扫过）**

| 位置 | 文案 | 判断 |
|---|---|---|
| `MainActivity:1469` | `"拍照失败：${e.message ?: e.javaClass.simpleName}"` | ❌ **会暴露** `NullPointerException` / `IllegalStateException` 这类字符串 |
| `MainActivity:1762` | `"启动失败：${e.javaClass.simpleName}"` | ❌ **会暴露** 类名 |
| `:261` | 「没有录屏权限，悬浮卡起不来」 | ✅ 可读 |
| `:1740` | 「先允许『显示在其他应用上层』，再点一次」 | ✅ 可读且可执行 |
| `:1796` | 「该设备不支持手动曝光写入，只能查看推荐值」 | ✅ 可读（且是能力说明，不是错误） |
| 其余 15 处 | 都是"还没测到画面，等一秒再试"这类 | ✅ 可读 |

**判断：2 处必须改，其余可保留。**
**改法**：`"拍照失败，请重试"` / `"悬浮教练启动失败，请检查权限后重试"` + `Diag.e` 记原始异常。**2 行改动，建议发布前必做。**
**优先级：发布必须（成本极低）。**

---

#### U5 · 空状态

**现状**
- 照片页：见 U3 ✅
- 技法页：`tvSkillTitle` = 「新手起步包」（无画面时）/ 「此刻最该注意的 N 条」 ✅；`llSkills` 为空时插一行「画面信息不足，等一秒再看」 ✅
- 功能页：`llFeatures` 为空时插「画面信息还不足，等一秒再看」 ✅
- 录像页：`tvIntentDesc` = 「选一个拍摄意图，给出完整录像方案」 ✅
- 相册缩略图：`ivThumb` 初始 `invisible` ✅

**判断：不会中招，占位齐备。**
**优先级：可延后。**

---

#### U6 · 悬浮教练流程（3 步说明）

**现状：3 步全靠 Toast。**
1. `:1740` 没浮窗权限 → Toast + 跳设置
2. `:261` 取消录屏 → Toast
3. `:1765` 启动成功 → Toast「悬浮卡已就位，去打开系统相机试试（长按按钮可关闭）」

**判断：⚠️ 这是**审核风险点**，不只是体验问题。** PRD §风险 2 明确要求「用一个专门的说明页（而非一行系统弹窗）向用户解释为什么要录屏，且必须点『我已知晓』才能继续。这个页面本身就是给审核看的证据」。当前实现没有这个页面。

**改法**：新增「悬浮教练说明页」（覆盖层），三条要点 + 「我已知晓，继续」+「取消」。内容：
- 为什么需要录屏：Android 不允许读别的 App 的相机画面
- 只做什么：只读像素算亮度/构图，**不录视频、不保存、不上传**（对应隐私政策 §2.3）
- 怎么停：通知栏「停止」/ 悬浮卡上「停」
点"我已知晓"后才走 `canDrawOverlays` → 通知权限 → `createScreenCaptureIntent()`。
另外：**默认关闭**（现在就是默认关闭，✅ 正确，这是"无滥用意图"的证据，不要改成默认开）。

**优先级：发布必须（审核要证据）。**

---

#### U7 · 返回键

**现状：无任何处理。** 全仓无 `onBackPressed` / `OnBackPressedCallback`。单 Activity + 4 页签 → **在技法页/功能页/录像页按返回，直接退出 App**。而且用户刚浏览完 83 条技法，一按返回整个 App 没了。

**判断：会中招，且是高频操作。**
**改法**：`onCreate` 注册 `OnBackPressedCallback(true)`，三级处理：
1. 有覆盖层（隐私政策全文 / 关于 / 悬浮教练说明 / 复盘卡展开）→ 关覆盖层；
2. `currentTab != TAB_PHOTO` → `selectTab(TAB_PHOTO)`；
3. 在照片页 → 二次确认退出（2 秒内再按才 `finish()`，第一次弹 Toast「再按一次退出」）。

**优先级：发布必须（成本 15 行，感知明显）。**

---

#### U8 · 字体缩放（列出所有固定高度控件）

**`activity_main.xml` 全部固定高度（26 处），逐个判断**

| 行 | 控件 | 高度 | 内含文字 | 1.5x 判断 |
|---|---|---|---|---|
| `:221/234/246/257` | `tabPhoto/Video/Feature/Skill` | **36dp** | 13sp 粗体 | ⚠️ **高风险**。13sp×1.5 = 19.5sp，行高约 26dp + 无 padding 余量，36dp 勉强；中文行高 1.4 时约 27dp，**再加两行就裁字**。且这 4 个是横向等分，字号大了会横向挤到换行 |
| `:416` | `histogram` | 46dp | 无 | ✅ 安全（无文字） |
| `:454/484/514` | 参数区 3 列容器 | 34dp | 10sp 标签 + 23sp 数值（换行两行） | ⚠️ 34dp 是**容器**高度；23sp 数值在 1.5x 下需 34dp 行高，**恰好卡在边界**。若数值换行（ISO 5 位数 + 窄屏）会溢出 |
| `:590/605/633/648` | `btnMode` 等 4 个 | 40dp | 11sp 单行 | ✅ 安全（40dp ≈ 29sp，11×1.5=16.5sp 单行无压力） |
| `:669/684` | `btnCopy/btnHelp` | 38dp | 11sp | ✅ 安全 |
| `:866` | `btnCopyVideo` | 40dp | 12sp | ✅ 安全 |
| `:1039` | `btnFloat` | 38dp | 11sp | ✅ 安全 |
| `:1089` | `ivThumb` | 48dp | 无 | ✅ |
| `:1104` | `btnShutter` | 72dp | 无 | ✅ |
| `:1098/1112/1118` | 分隔/占位 View | 1dp / 48dp | 无 | ✅ |
| `:137` | 分隔 | 8dp | 无 | ✅ |
| `:11/21/35/193` | 根/preview/overlay/panel | 0dp（约束） | — | ✅ |

**判断：4 个页签是唯一的真实风险点。**
**改法**：`layout_height="wrap_content"` + `android:minHeight="36dp"` + `android:gravity="center"`（4 处）。参数区容器改 `wrap_content` + `minHeight="34dp"`（3 处）。
**优先级：建议（改 7 行，避免无障碍用户完全没法用）。**

---

#### U9 · 小屏 360dp

**现状**
- `panel` margin 12×2 → 内容宽 336dp；参数四列各 ≈ 82dp
- `tvIso` 23sp 等宽字体，`"12800"` 5 字符 ≈ 5×14dp = 70dp → **勉强放下**，但 1.5x 字体时必然溢出
- `CoachOverlay` 宽度：`minOf(dp(340), widthPixels - dp(24))`（`CoachOverlay:140`）→ **已自适应，不会溢出** ✅

**判断：参数区在 360dp + 大字体下会挤，但不会崩。**
**改法**：`tvIso`/`tvShutter`/`tvKelvin` 加 `app:autoSizeTextType="uniform"` + `app:autoSizeMinTextSize="14sp"` + `app:autoSizeMaxTextSize="23sp"`（Material 组件已支持，**零新依赖**）。
**优先级：建议。**

---

#### U10 · 深色主题

**现状**
- `Theme.Material3.Dark.NoActionBar` 固定深色 ✅
- `android:windowLightStatusBar = false` ✅（状态栏图标白色）
- `navigationBarColor = #0E1113` + 默认（浅色图标）✅
- 系统处于浅色模式时：App 仍深色，**状态栏图标白、背景透明 → 正常** ✅

**判断：targetSdk 34 下没问题。API 35 后会有一个新问题**：`navigationBarColor` 被忽略 → 导航栏透明叠在取景画面上 → 图标颜色由系统的对比度算法决定，在"白色天空"背景上可能变成深色（反而更好）或保持白色（看不清）。**需真机验证。**
**改法**：显式 `WindowInsetsControllerCompat.isAppearanceLightNavigationBars = false`，把控制权拿回来。
**优先级：建议（随 §2.1 一起做）。**

---

### 9.3 「发布必须」项汇总（工程师据此排期）

| # | 项 | 类别 | 估算 |
|---|---|---|---|
| 1 | AGP 8.5.2 → 8.6.1 + compileSdk/targetSdk 35（§2.0） | 构建 | 0.5d |
| 2 | 包名完整迁移（§1） | 构建 | 0.5d |
| 3 | 正式签名注入 + `?: throw` + `.gitignore` + 删测试密钥（§4） | 构建 | 0.5d |
| 4 | 版本 3.0.0 / 30 | 构建 | 5min |
| 5 | 隐私同意覆盖层 + `privacy_v1.txt` + 关于页 + 双清单（§6） | 合规 | 1.5d |
| 6 | 受限模式（§6.3） | 合规 | 0.5d |
| 7 | `POST_NOTIFICATIONS` 请求（§2.6） | 合规 | 0.5h |
| 8 | 通知 `contentIntent` + 停止 action（§2.3 / N1） | 合规+可靠 | 0.5h |
| 9 | `START_NOT_STICKY` + null intent（§2.2 / N2） | 可靠（崩溃） | 0.5h |
| 10 | `allowBackup=false` + `dataExtractionRules` + `targetApi=35` + `enableOnBackInvokedCallback`（§7） | 合规 | 0.5h |
| 11 | 图标全套 + 自适应 + 512/1024 商店图（§5） | 素材 | 0.5d |
| 12 | `ShotTunerApp` + `Diag` + 全局异常 handler（§3.4 / R1） | 可靠 | 0.5d |
| 13 | R2 相机失败回调 + 错误条 + 重试（含 Preview-only 降级） | 可靠 | 0.5d |
| 14 | R3/R7 权限「去设置」+ `onResume` 重试 | 可靠 | 0.5d |
| 15 | U4 两处文案去技术细节 | 可用 | 10min |
| 16 | U7 返回键三级处理 | 可用 | 0.5h |
| 17 | U6 悬浮教练说明页 | 可用+审核 | 0.5d |
| 18 | §2.1 edge-to-edge 收尾（`llControlBar` inset + 导航栏图标） | 兼容 | 0.5h |
| 19 | 隐私政策网页部署（§6.5） | 合规 | 0.5d |

**合计约 8.5 人日**（不含真机回归与五家提审沟通）。

### 9.4 "大概率会出问题"的风险（单独列出）

按"我有多大把握它一定会坏"排序：

1. **`START_STICKY` + null intent → 前台服务启动超时崩溃（N2）**。复现条件明确（系统回收前台服务），targetSdk 34/35 下必然抛 `ForegroundServiceDidNotStartInTimeException`。**把握：高。**
2. **隐私政策承诺"点击通知即可停止"，但通知没有 `contentIntent`（N1）**。审核方点一下就会发现。**把握：高。**
3. **Android 13+ 通知权限从不请求 → 录屏期间用户看不到任何系统提示**。这是"录屏"这种敏感权限最容易被投诉的点。**把握：高。**
4. **相机被占用时不报错、无重试 → 黑屏（R2）**。相机独占是常态。**把握：高。**
5. **AGP 8.5.2 下 `compileSdk = 35` 构建失败**。**把握：高**（AGP 8.6 才支持 API 35；8.5 会报 "compileSdk 35 requires AGP 8.6.0"）。
6. **返回键直接退出（U7）**。**把握：高**（代码里确实没有）。
7. **AGP 升级后 R8 行为变化导致 ML Kit 初始化异常**。AGP 8.6 换了 R8 版本，`-keep class com.google.mlkit.**` 应该能兜住，但**必须真机跑一次三件套（人脸/打标/录制）**。**把握：中。**
8. **16KB 页设备上 ML Kit 加载失败**。需实测 `.so` 对齐。**把握：中（取决于设备存量）。**
9. **`llControlBar` 无底部 inset，系统栏滑出压住快门**。**把握：中**（immersive 下不出现，但 Android 15 上系统栏行为更活跃）。

---

## 10. 需要 clover 拍板/确认的 5 件事

| # | 事项 | 我的建议 | 不确认的后果 |
|---|---|---|---|
| 1 | keystore 生成与备份（**唯一不可逆**） | 立刻做，按 §4.1 / §4.3 | 密钥丢失 = 永久无法更新 + 备案作废 |
| 2 | 崩溃上报：保持零网络 | 保持零网络（§3.1） | 接了就要重写全部合规材料 |
| 3 | 相册目录保持 `ShotTuner` 不改中文 | 保持（§1.3） | 改了会出现两个相册文件夹 + 一类路径乱码 bug |
| 4 | ML Kit 是否升级（16KB 页） | 先验证 `.so` 对齐，暂不升 | 若目标机型含 16KB 内核，AI 会静默失效 |
| 5 | 是否接受 8.5 人日 + 真机回归期 | — | 时间不够时优先砍"建议"级，不要砍第 5/6/7/8/9/19 条 |

---

## 11. 与 PRD「发布前置动作汇总」的对照

PRD §7 列了 8 项，我逐项确认并补了 3 项它没列的：

| PRD # | 事项 | 本文档位置 | 状态 |
|---|---|---|---|
| 1 | 首次启动隐私弹窗 + 同意流程 | §6.2 / §6.3 / U1 / U2 | ✅ 覆盖，且补了"受限模式"的具体行为 |
| 2 | 包名改 `com.shottuner.app` | §1（9 个硬编码点） | ✅ 覆盖，且指出"必须连 Kotlin 包一起改" |
| 3 | 换正式签名，移除明文密码 | §4 | ✅ 覆盖，且补了 `?: throw` 防静默回落 |
| 4 | targetSdk 35 并回归 | §2 | ✅ 覆盖，**并补了 PRD 没提的 AGP 前置** |
| 5 | 关于页 + 双清单 | §6.4 | ✅ 覆盖 |
| 6 | 统一应用名称 | §1.3 / §0 | ✅ `app_name` 已是「光影调参」，不动 |
| 7 | 悬浮教练说明页（默认关闭） | U6 | ✅ 覆盖，强调这是"给审核看的证据" |
| 8 | `allowBackup` | §7 #1 | ✅ 明确改 false 并给出理由（云备份与"只在本机"矛盾） |
| **补 1** | `POST_NOTIFICATIONS` 从未请求 | §2.6 / §0-N | ⚠️ PRD 未覆盖，**合规级** |
| **补 2** | 通知不可点击停止（与隐私政策冲突） | §2.3 / R4 / §0-N1 | ⚠️ PRD 未覆盖，**合规级** |
| **补 3** | `START_STICKY` 崩溃 | §2.2 / R4 / §0-N2 | ⚠️ PRD 未覆盖，**崩溃级** |
