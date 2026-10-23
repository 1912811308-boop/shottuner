# QA 独立验证报告 · ShotTuner 3.0.0 发布前（A–E 五组）

> 验证人：严过关（QA）｜ 日期：2026-10-20
> 验证对象：`versionCode 30` / `versionName 3.0.0`（阶段 1 构建基建 + 阶段 2 合规 + 阶段 3 可靠性/可用性）
> 本轮**真跑了测试与 APK 实测**，未跑 `assembleRelease`（主理人已验证构建），未改任何生产代码。
> 结论引用的 APK 是 `app/build/outputs/apk/release/app-release.apk`（40,345,338 B，SHA-256 `999a1e24…f95aa`）。

---

## 0. 一眼看板

| 组 | 结论 | 一句话 |
|---|---|---|
| A 构建与合规 | ⚠️ **7 项中 6 通过，1 项致命** | 包名/签名/凭据/Manifest/隐私文本都对，但 APK 里有 `INTERNET` 权限，与隐私政策正文**直接矛盾** |
| B APK 层 | ❌ **不通过** | 签名、版本、图标全对；**权限清单里有 INTERNET**（核心合规论据失效） |
| C 可靠性 | ✅ 通过（1 条 P2） | 崩溃处理器不吞异常、Diag 有 runCatching+锁、相机三级降级是三条真路径、快门可用性含 cameraBroken、START_NOT_STICKY 全覆盖 |
| D 可用性 | ⚠️ 基本通过（1 条 P1 + 1 条 P0 归属 A） | 隐私门在相机之前、受限模式页签可切、返回键全走 dispatcher、通知两个 requestCode 不同；但**「关于」页不存在**，且 17 个固定高度按钮会在大字体下截断 |
| E 发布就绪 | ❌ **不具备提交条件** | PRD §7 第 5 条（关于页 + 双清单）未落地；`dist/` 里没有 3.0.0 产物；非代码缺项 5 类 |

**ROUTE 结论：`ENGINEER` — 当前不能上架**，有 2 个 P0。

---

## A. 构建与合规

### A1 测试真跑结果 —— ✅ 通过

`tools/run-tests.ps1 -Rerun`：`52 actionable tasks: 52 executed`（**不是 UP-TO-DATE**），`BUILD SUCCESSFUL`，`GRADLE_EXIT=0`。
从 `app/build/test-results/` 的 XML 逐类统计：

| flavor | tests | failures | errors | skipped |
|---|---|---|---|---|
| testDebugUnitTest | **121** | 0 | 0 | 0 |
| testReleaseUnitTest | **121** | 0 | 0 | 0 |

逐类：FramingCoach 14 / LevelMath 13 / PhotographyBook 6 / PromptEngine 22 / PromptScheduler 26 / ScoreBoard 18 / TechniqueBookV15 13 / TechniqueMatcher 9 = 121。两侧一致，与声称吻合。

### A2 旧包名残留 —— ✅ 通过

`grep -rn "com\.clover\.shottuner" app/src app/*.kts build.gradle.kts settings.gradle.kts gradle.properties` → **零命中**（exit=1）。
对 APK 二进制做 `grep` → **零命中**。
> 说明：`app/build/` 下的 lints 中间产物（`lintAnalyzeDebug/debug.xml` 等）仍含旧包名字符串，那是**上一次构建的缓存残留**，不进 APK，也不影响产物；但提醒一句：这些缓存会让"全仓 grep"看起来有命中，验收时应排除 `app/build/`。

### A3 明文密码与 `?: throw` —— ✅ 通过

`app/build.gradle.kts:33-51`：四项全部 `project.findProperty(...) ?: throw GradleException(...)`，**没有一处明文口令**。签名块为 `signingConfigs.create("release")`，`buildTypes.release.signingConfig = signingConfigs.getByName("release")`，debug 走 `applicationIdSuffix = ".debug"` 不共用正式密钥 —— 这个隔离是对的。

### A4 `.gitignore` / 密钥文件 —— ✅ 通过（有 1 处提醒）

- `.gitignore` 含 `gradle.properties`、`*.jks`、`*.keystore`、`CREDENTIALS.txt` ✅
- 工程目录内 `*.jks` / `*.keystore` **零命中** ✅
- 密钥实体在 `C:/Users/19128/android-keys/shottuner-release.jks`（工程目录之外）✅
- `gradle.properties` 存在且**含明文口令**（这是 `findProperty` 的必然代价），已被 gitignore ✅
> ⚠️ 提醒（P2）：本机没有 git 仓库，`git check-ignore` 无法执行，我**只能核对 .gitignore 文本，无法证明该文件从未被提交过**。首次 `git init` 后请立刻 `git status` 确认 `gradle.properties` 不在待提交列表；若曾误提交，改 gitignore 也删不掉历史，必须换密钥（换密钥 = 备案作废，代价极高）。
> 另：`gradle.properties` 里以注释形式写了证书 SHA-256 指纹（与 B8 实测一致），这是给备案用的正确做法 ✅。

### A5 Manifest 逐项 —— ✅ 通过（从 APK 实测，不只看源码）

用 `aapt2 dump xmltree --file AndroidManifest.xml` 读**打包后**的清单：

| 要求 | 实测值 | 判定 |
|---|---|---|
| `allowBackup="false"` | `allowBackup=false` | ✅ |
| `icon="@mipmap/ic_launcher"` | `icon=@0x7f0d0000`（mipmap，见 B10） | ✅ |
| `roundIcon` | `roundIcon=@0x7f0d0000` | ✅（与 icon 同源，自适应图标下可接受） |
| `enableOnBackInvokedCallback="true"` | `=true` | ✅ |
| `dataExtractionRules` | `@0x7f130001` | ✅ |
| `fullBackupContent` | `@0x7f130000` | ✅ |
| `android:name=".ShotTunerApp"` | `com.shottuner.app.ShotTunerApp` | ✅ |
| `tools:targetApi="35"` | 源码 `AndroidManifest.xml:44` 有；**APK 里查不到是正确的** | ✅（`tools:` 是构建期属性，会被 aapt 剥离，不在产物中出现） |

两份备份规则都是"全部 exclude"（`backup_rules.xml` 5 个 domain、`data_extraction_rules.xml` cloud-backup + device-transfer 各 5 个 domain），与 `allowBackup=false` 同义 ✅。

### A6 `privacy_v1.txt` 完整性 —— ✅ 通过（内容完整）

`app/src/main/res/raw/privacy_v1.txt`：171 行 / **11,695 字节**，**九个一级小节齐全**（一 我们收集哪些信息 / 二 各项权限的用途 / 三 第三方 SDK 清单 / 四 数据的存储位置与保留期限 / 五 我们不会做的事 / 六 你的权利 / 七 未成年人保护 / 八 本政策的更新 / 九 联系我们），结尾停在联系方式与投诉渠道，未被截断。与 PRD §2 的九节结构一致。

**但内容里有一处致命矛盾**（归入 P0-1，见 §2）：正文第 41 行写「本应用未申请 `INTERNET` 网络访问权限…在操作系统层面不具备联网能力」，而 APK 实测**有 INTERNET 权限**。同类表述还有第 24 行、第 70 行。
另：raw 与 PRD §2 **不是逐字一致**（多了一段 FAQ「· 你们收集我的照片吗？—— 不收集。」，去掉了 markdown 的 `**` 与 `-`），属排版差异不算缺陷；但 ARCH §6.5 要求"网页版与 `privacy_v1.txt` **逐字一致**"，部署网页时必须抄 raw 这一版，不能抄 PRD。

---

## B. APK 层（实测）

### B7 版本与包名 —— ✅ 通过

```
package: name='com.shottuner.app' versionCode='30' versionName='3.0.0'
compileSdkVersion='35'  compileSdkVersionCodename='15'
sdkVersion:'24'  targetSdkVersion:'35'
```
与要求逐项吻合。

### B8 正式签名 —— ✅ 通过（关键项）

```
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=ShotTuner, OU=Android, O=ShotTuner, L=Dongguan, ST=Guangdong, C=CN
Signer #1 certificate SHA-256 digest: 08b209a35bb85b703943b4febf5b91ec593043fdb79b53d7ed1529000aca262b
Signer #1 key algorithm: RSA   key size: 4096
```
**SHA-256 与要求的 `08b209a3…262b` 完全一致**，且**不是**旧的测试密钥 `13c30884…90d1`。RSA 4096 / 10000 天（ARCH §4 口径）与 `gradle.properties` 注释里的指纹一致。v1/v3/v4 均为 false，对 minSdk 24 而言 v2-only 是标准做法 ✅。

### B9 权限清单 —— ❌ **不通过（P0-1）**

`aapt2 dump badging` 实测：

```
CAMERA / WRITE_EXTERNAL_STORAGE(maxSdk 28) / SYSTEM_ALERT_WINDOW
FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PROJECTION / POST_NOTIFICATIONS
ACCESS_NETWORK_STATE
INTERNET                      ← 不该存在
com.shottuner.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION（androidx 自动加，正常）
（另有隐含的 READ_EXTERNAL_STORAGE maxSdk 28，由 WRITE 隐含，非独立声明）
```

- `POST_NOTIFICATIONS` ✅ 在。
- **`INTERNET` 在，`ACCESS_NETWORK_STATE` 也在** ❌。
- 来源已追到具体库（`app/build/outputs/logs/manifest-merger-release-report.txt:456`）：
  - `INTERNET` **ADDED from `com.google.mlkit:vision-internal-vkp:18.2.3`**，并 **MERGED from `com.google.android.datatransport:transport-backend-cct:2.3.3` 与 `transport-runtime:2.2.6`**；
  - `ACCESS_NETWORK_STATE` 同源。
  - 即：**不是 app 自己声明的**（`app/src/main/AndroidManifest.xml` 里没有），是 ML Kit 图像标注的传递依赖带进来的。

### B10 图标资源 —— ✅ 通过

- `aapt2 dump badging` 的 `application-icon-160/240/320/480/640` 与 `application:` 全部指向 `res/BW.xml`（release 开了资源名混淆，路径被改写，属正常）。
- 源码侧齐全：`mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi/ic_launcher.png` + `mipmap-anydpi-v26/ic_launcher.xml`（自适应）。
- APK 内实测到 48/72/96/144/192 px 的正方形位图各 ≥1 张（`res/*.png`，名字已混淆）→ 五档密度都在。
- 商店用图 `docs/store-assets/app-icon-512.png` 与 `app-icon-1024.png` 存在 ✅。

---

## C. 可靠性

### C11 全局异常处理器 —— ✅ 通过（这条做对了）

`ShotTunerApp.kt:36-54`：

```kotlin
Thread.setDefaultUncaughtExceptionHandler { thread, error ->
    try { Diag.e(...); Log.e(...) } catch (_: Throwable) { /* 记不下来也必须继续 */ }
    if (previous != null) previous.uncaughtException(thread, error)
    else { Process.killProcess(Process.myPid()); System.exit(10) }
}
```

- `previous?.uncaughtException(...)` **在非空分支一定会被调用**；为空时 `killProcess + exit(10)`。
- **没有任何"吞掉异常就 return"的分支**：`try/catch` 只包住"写日志"这一件事，catch 体是空的后继续往下走，不是 return。
- 结论：线上崩溃仍然表现为"正常崩溃并被系统记录"，不会退化成"界面卡死的僵尸进程"。这正是我上一轮担心的失败模式，**没有踩**。

### C12 `Diag` 自身不抛异常 —— ✅ 通过（1 条 P2）

- `write()` 整体包在 `runCatching { synchronized(lock) { ... } }` 里 ✅（`Diag.kt:120-126`）
- `rotate()` 单独 `runCatching` ✅（:131）
- `exportTo()` 用 `try { ... } catch (_: Throwable) { null }`，失败安全返回 null ✅（:64-78）
- `tail()` 失败返回 `""` ✅；`init()` 的 `mkdirs` 也包了 runCatching ✅
- **P2 瑕疵**：`stamp: SimpleDateFormat` 是共享可变对象，而 `stamp.format(Date())` 在 **`synchronized(lock)` 之外**（:110-119 的 `buildString` 先执行，锁在 :121 才进）。`SimpleDateFormat` 非线程安全，多线程同时写日志（UI 线程 + 相机回调 + 崩溃线程）时可能输出乱码或抛异常，而这次异常发生在 `runCatching` 之外，会冒泡到调用方。建议把 `format` 挪进锁内，或换 `DateTimeFormatter`。
- 另一个 P2 nit：环形缓冲按 `line.length`（字符）与 `MAX_BYTES`（字节）比较，中文 UTF-8 占 3 字节，实际单文件可达 ~192KB、总计 ~576KB。有界，不会撑爆存储，只是与注释的 "192KB" 有出入。

### C13 相机三级降级 —— ✅ 通过（确实是三条不同路径）

`CameraEngine.bind()`（:228-249）：

1. `provider.bindToLifecycle(activity, selector, preview, capture, analysis)` —— 完整四 UseCase；
2. `catch (e: Throwable)` → `unbindAll()` + `imageCapture = null` + `bindToLifecycle(activity, selector, preview)` —— **只绑 Preview**，是**参数不同的另一次调用**，不是把第 1 行复制一遍；
3. `catch (e2: Throwable)` → `reportFailure("仅预览降级也失败", e2)` → `onError?.invoke(failure)`，由 UI 显示错误条。

工程师说的"原来那段是同一个调用写了两遍"在这版里已经不存在：第 2 层的实参列表只有 `preview`，且显式把 `imageCapture` 置空（保证降级后拍照走"相机尚未就绪"的错误回调而不是 NPE）。✅

### C14 快门可用性是否含 `cameraBroken` —— ✅ 通过

`MainActivity.applyShutterAvailability()`（:811-819）：

```kotlin
val cameraBroken = binding.llCameraError.visibility == View.VISIBLE
val usable = currentTab == TAB_PHOTO && privacyAccepted && !cameraBroken
binding.btnShutter.isEnabled = usable
```

四个条件齐全（页签 + 隐私 + 相机故障 + 可用），且**三个调用点**（切页 / 拍照成功 / 拍照失败）都走它，失败回调不会把快门重新点亮 ✅。附带把上一轮我报的 P1-c（屏幕快门无 tab 守卫）也一并收口了。

### C15 `START_NOT_STICKY` + `intent == null` —— ✅ 通过

`ScreenCaptureService.onStartCommand()`（:105-132）：`intent == null` → `stopSelf()` + 返回 `START_NOT_STICKY`；`ACTION_STOP` / `ACTION_START`（`data == null` 时）/ `else` 未知 action → 全部 `stopSelf()` + `START_NOT_STICKY`；**方法尾部兜底 `return START_NOT_STICKY`**。全仓已无 `START_STICKY`。ARCH §9.4 风险 1（必然崩溃）已消除 ✅。

---

## D. 可用性

### D16 隐私门在相机之前 —— ✅ 通过

`onCreate`（:385-393）：先 `selectTab(TAB_PHOTO)` / 渲染静态内容 → **`setupPrivacyGate()`（:391）→ `refreshCameraState()`（:392）**。
`refreshCameraState()`（:508-543）第一段就是 `if (!privacyAccepted) { showCameraOff(PRIVACY, ...); return }` —— 未同意时**既不 `requestCamera.launch`，也不 `startCamera()`**（`requestCamera.launch` 在 :534，`startCamera()` 在 :542，都在隐私分支之后）。`startFloatingCoach()` 里也有 `if (!privacyAccepted) { showPrivacyGate(); return }` 硬判断（:2039-2042）✅。

### D17 受限模式页签可用 —— ✅ 通过

`selectTab()`（:404-426 附近）**没有任何提前 return**，四个页面正常切换，只是调 `applyShutterAvailability()` 把快门置灰。
文案也与行为一致：未同意时占位写「技法库、录像方案与功能建议仍然可以正常查看」（layout:1200 与 :515-516）✅。
**这是本次改动里做得最干净的一处**：受限模式不是"锁死 App"，而是"只关掉相机通路"。

### D18 返回键 —— ✅ 通过

`grep "fun onBackPressed("` → **零命中**（无过期覆写）。
全部走 `onBackPressedDispatcher.addCallback(...)`（:425-459），五层优先级：隐私全文 → 隐私门（`passThrough`）→ 教练说明页 → 非照片页签回照片页 → 双击退出（`BACK_EXIT_MS`）。`passThrough()` 先 `isEnabled = false` 再 `onBackPressedDispatcher.onBackPressed()` 再 `isEnabled = true`，避免自递归死循环 —— 这个细节是对的 ✅。

### D19 通知 PendingIntent —— ✅ 通过

`ScreenCaptureService.kt`：`REQ_CONTENT = 1001`、`REQ_STOP = 1002`（两个不同 requestCode ✅，注释也明确写了"相同会互相覆盖"）。
- `contentIntent = PendingIntent.getActivity(..., REQ_CONTENT, Intent(MainActivity), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`
- `stopIntent = PendingIntent.getService(..., REQ_STOP, Intent(this, ScreenCaptureService).setAction(ACTION_STOP), ...)`
- `ACTION_STOP` → `onStartCommand` 的 `ACTION_STOP` 分支 → `stopSelf()` ✅ 真能停。
- 通知 `setOngoing(true)` + 一个「停止」action，与隐私政策 §2.3/§2.4 的承诺对得上 ✅。

> 一处只能真机验的点：`setSmallIcon(android.R.drawable.ic_menu_camera)` 用的是**系统内置图标**。多数 ROM 能显示，但部分厂商会在状态栏把它渲染成全白方块，且商店审核可能要求"自有品牌小图标"。不影响功能，列为 P2 观察项。

### D20 大字体截断 —— ❌ **不通过（P1）**

页签已按要求改成 `layout_height="wrap_content"` + `minHeight="48dp"` ✅（四个 tab 都是）。
**但其余 17 个按钮仍是固定 dp 高度 + 内嵌文本**，扫全布局结果：

| 控件 | 固定高度 | 字号 | 风险 |
|---|---|---|---|
| `btnMode` / `btnMore` / `btnLock` / `btnAuto` | 40dp | 11sp | 1.3× 起就可能上下被裁 |
| `btnCopy` / `btnHelp` / `btnFloat` / `btnExportDiag` | 38dp | 11sp | 同上，且这组只有 38dp，最紧 |
| `btnCopyVideo` | 40dp | 12sp | 同上 |
| `btnCameraOffAction` / `btnCameraRetry` / `btnPrivacy*` / `btnCoachIntro*` | 40–52dp | 13–15sp | 2.0× 时 15sp→30sp，行高 ≈36dp + padding，52dp 也紧 |

`minHeight` 全部是 `0dp` 或未设，没有一处用 `minHeight` + `wrap_content`。系统字体放大到 1.3×/1.5×/2.0× 时，11sp 变 14–22sp，38–40dp 的盒子装不下两行高度，文字会被垂直裁掉（`includeFontPadding` 也救不了固定高度）。
建议统一改为 `layout_height="wrap_content"` + `minHeight="40dp"`（或原值）+ 适度 `paddingVertical`，与页签的改法保持一致。

### D17b / E21-5「关于」页 —— ❌ **不存在（P0-2）**

全仓 `grep "关于"` → **只有 1 处命中**，而且是在 `privacy_v1.txt:162`：
> 「3. 本政策的历史版本可在应用内**「关于」页面**查阅。」

即：**隐私政策向用户承诺了一个 App 里不存在的页面**。这是审核最容易抓的一类矛盾（点进去找不到 → 判定"隐私政策与实际不符"）。
功能页（`pageFeature`）的实际结构只有：提示密度单选组 / 音量键快门开关 / 「这会儿该开什么」+ 功能卡片列表 —— **没有「关于」入口、没有隐私政策入口、没有权限清单/SDK 清单入口**。
后果还有一层：用户一旦在首次弹窗里点了「同意并继续」或「暂不同意」，**此后在整个 App 里再也找不到隐私政策全文**（只有相机占位页的「重新开启相机」能唤回覆盖层，且那是隐私门不是政策页）。
PRD §7 第 5 条把这条标为**阻塞**级，ARCH §9.3 第 5 条同样把「关于页 + 双清单」列为发布必须项 —— **未落地**。

---

## E. 发布就绪清单

### E21 对照 PRD §7 与 ARCH §9.3 逐条打勾

| # | 事项 | 阻塞级别 | 实测 | 判定 |
|---|---|---|---|---|
| PRD-1 | 首次启动隐私弹窗 + 同意流程 | 阻塞 | `setupPrivacyGate` + `llPrivacyGate` 覆盖层 + 同意/暂不同意双出口 + SharedPreferences 落盘 | ✅ |
| PRD-2 | 包名改 `com.shottuner.app`（含 Kotlin 包/目录） | 阻塞 | 源码与 APK 均零旧包名 | ✅ |
| PRD-3 | 正式签名 + 移除明文密码 | 阻塞 | `?: throw` 四项；APK 证书 `08b209a3…` RSA4096 | ✅ |
| PRD-4 | targetSdk 35 | 阻塞 | `targetSdkVersion:'35'` | ✅ |
| PRD-5 | **应用内「关于」页：隐私政策入口 + 双清单入口** | **阻塞** | **不存在** | ❌ **P0-2** |
| PRD-6 | 统一应用名称 | 高 | `app_name` = `tile_name` = 「光影调参」 | ✅（商店/软著/备案侧见 E22） |
| PRD-7 | 悬浮教练首次说明页（默认关闭） | 高 | `KEY_COACH_INTRO_SHOWN` 默认 false → 先弹 `llCoachIntro`，点「开始」才走权限流程；且隐私未同意时硬拦 | ✅ |
| PRD-8 | `allowBackup` | 中 | 已改 false + 两份全 exclude 规则 | ✅ |
| ARCH-1~4 | AGP/compileSdk/包名/版本 3.0.0-30 | — | `compileSdk=35`、versionCode 30 | ✅ |
| ARCH-6 | 受限模式 | 合规 | 页签可切、仅关相机 | ✅ |
| ARCH-7 | `POST_NOTIFICATIONS` 请求 | 合规 | 在 `beginCoachSetup` 里、发起录屏前请求 | ✅ |
| ARCH-8 | 通知 contentIntent + 停止 action | 合规+可靠 | 1001/1002 不同 requestCode，ACTION_STOP→stopSelf | ✅ |
| ARCH-9 | `START_NOT_STICKY` + null intent | 可靠 | 全分支覆盖 | ✅ |
| ARCH-10 | allowBackup/dataExtractionRules/targetApi/enableOnBackInvokedCallback | 合规 | APK 实测全部到位 | ✅ |
| ARCH-11 | 图标全套 + 自适应 + 512/1024 | 素材 | mipmap 5 档 + anydpi-v26 + store-assets | ✅ |
| ARCH-12 | ShotTunerApp + Diag + 全局异常 handler | 可靠 | 均已实现且行为正确 | ✅ |
| ARCH-13 | 相机失败回调 + 错误条 + 重试（含 Preview-only 降级） | 可靠 | 三条真路径 + `btnCameraRetry` | ✅ |
| ARCH-14 | 权限「去设置」+ onResume 重试 | 可靠 | `onCameraOffAction` 按 `cameraOffReason` 分流到设置页；onResume 收敛 | ✅ |
| ARCH-15 | 两处文案去技术细节 | 可用 | 占位文案为"相机已关闭/需要相机权限"白话 | ✅ |
| ARCH-16 | 返回键分级处理 | 可用 | dispatcher 五层 | ✅（比要求的"三级"更完整） |
| ARCH-17 | 悬浮教练说明页 | 可用+审核 | 同 PRD-7 | ✅ |
| ARCH-18 | edge-to-edge 收尾（`llControlBar` inset + 导航栏图标） | 兼容 | **`applyControlBarInsets()` 存在**（:665-672），`applyPanelInsets()` 也在；`isAppearanceLightNavigationBars=false` | ✅ |
| ARCH-19 | 隐私政策网页部署 | 合规 | 非代码，**未部署**（见 E22） | ❌ 非代码 |

### E22 上架前仍缺的**非代码**事项

这些不是工程师能改代码解决的，但只要缺一条，商店就过不了。列清楚，避免误以为"代码好了就能上架"：

1. **隐私政策的公网可访问 URL**（PRD §5 / ARCH §6.5）：商店后台要填一个能在浏览器打开的 HTTPS 页面，正文必须与 `privacy_v1.txt` **逐字一致**。目前 `privacy_v1.txt` 里只有邮箱，没有任何 URL。建议 `privacy.shottuner.app` 或独立静态页（COS/OSS 静态网站托管）。
2. **软件著作权登记证书**（PRD §5.2）：国内五家店基本都要；登记名称必须与「光影调参」一致（PRD 风险 5）。
3. **工信部 APP 备案**（PRD §5.3）：**必须在包名与签名最终确定之后**办 —— 现在这两项已经定稿（`com.shottuner.app` + `08b209a3…`），可以开始；但**备案完成前不要改动包名或更换密钥**，否则备案作废。
4. **五家商店的开发者账号实名认证**（PRD §5.1）：主体资格、对公账户/个人实名、税务信息。
5. **商店素材**：截图（各店尺寸不同）、应用简介 80 字、详细描述 500 字、更新说明 —— icon 512/1024 已有 ✅，其余未见。
6. **keystore 的离线备份**（ARCH §10 第 1 条，唯一不可逆）：目前密钥在 `C:/Users/19128/android-keys/`，属**单点存储**。至少两处离线介质 + 口令分开保管。丢了 = 永久无法更新 + 备案作废。
7. **应用名称与包名的一致性确认**：`app_name`「光影调参」、包名 `com.shottuner.app`、商店名、软著名四者需一致（PRD 风险 5 提到过）。

---

## 2. 问题清单

### P0 阻断上架（2 条）

**P0-1｜APK 含 `INTERNET` 权限，与隐私政策正文形成**正面矛盾**（核心合规论据失效）**
- 证据链：
  - APK 实测：`uses-permission: name='android.permission.INTERNET'`（`aapt2 dump badging` + `aapt2 dump xmltree --file AndroidManifest.xml` 双重确认，且在打包后的清单里真实存在）。
  - 来源：`com.google.mlkit:vision-internal-vkp:18.2.3` ADDED；`com.google.android.datatransport:transport-backend-cct:2.3.3` / `transport-runtime:2.2.6` MERGED（`app/build/outputs/logs/manifest-merger-release-report.txt:456-462`）。
  - **app 自己没声明**（`app/src/main/AndroidManifest.xml` 无 INTERNET 行）—— 是 ML Kit 图像标注的传递依赖带进来的。
  - 隐私文本 **3 处直接点名权限**、另有 2 处 SDK 条目声称不联网：`app/src/main/res/raw/privacy_v1.txt:41`（"本应用未申请 INTERNET 网络访问权限…在操作系统层面不具备联网能力"）、`:24`（"本应用未申请网络权限，在系统层面不具备联网能力"）、`:70`（"本应用无网络权限，无法外传数据"）；`privacy_v1.txt:97-98` 的两个 ML Kit 条目也各写了"不联网传输"。
  - PRD 把"零网络"当作**核心合规论据**（`docs/RELEASE-ARCH.md` §10 第 2 条："崩溃上报：保持零网络"）。
- 后果：① 应用详情页的权限列表会显示"网络访问"，与"完全离线"的宣传直接冲突；② 审核方点开隐私政策就能对上这条矛盾；③ `datatransport` 本身就是 Google 的遥测上传通道，只要权限在，理论上就可能发起请求 —— 我们无法证明它"从未请求"，只能证明"没权限时不可能请求"。
- **修法（推荐，一行级）**：在 `app/src/main/AndroidManifest.xml` 顶部加
  ```xml
  <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
  ```
  改完必须重跑 `aapt2 dump badging` 确认两条都消失。**并需真机回归一次 ML Kit**（人脸检测 + 图像标注 + 录屏），确认没有网络权限时不会因 telemetry 失败而影响推理 —— 理论上不会（bundled 模型），但这条没有真机就无法断言。
- 备选修法：`configurations.all { exclude(group = "com.google.android.datatransport") }`（更彻底，但可能触发 ML Kit 的 NoClassDefFoundError，风险高于 `tools:node="remove"`，不推荐首选）。

**P0-2｜「关于」页不存在，且隐私政策自指该页面**
- 证据：
  - 全仓 `grep "关于"` → 唯一命中是 `app/src/main/res/raw/privacy_v1.txt:162`：「本政策的历史版本可在应用内**「关于」页面**查阅。」
  - 功能页实测结构（`activity_main.xml` 的 `pageFeature` 子树）：仅「提示密度」单选组 / 「音量键拍照」开关 / 「这会儿该开什么」标题 + `llFeatures` 容器 —— 无「关于」、无隐私政策入口、无清单入口。
  - 用户同意后覆盖层消失，**全 App 再无任何隐私政策入口**。
- 依据：PRD §7 第 5 条 **阻塞**级；ARCH §6.4 明确要求"关于页 + 三页（隐私政策 / 个人信息收集清单 / 第三方共享清单）"；ARCH §9.3 第 5 条同样列为发布必须。
- 修法：新增一个覆盖层「关于」，三个入口复用 `privacy_v1.txt` 的分节文案（ARCH §6.4 明确要求"不要另写一套"），并在功能页加一行入口。工作量约半天。

### P1 应修（3 条）

**P1-1｜17 个固定 `layout_height` 的文本按钮在大字体下会截断**
- 位置：`activity_main.xml` 中 `btnMode`/`btnMore`/`btnLock`/`btnAuto`(40dp@11sp)、`btnCopy`/`btnHelp`/`btnFloat`/`btnExportDiag`(38dp@11sp)、`btnCopyVideo`(40dp@12sp)、`btnCameraOffAction`/`btnPrivacy*`/`btnCoachIntro*`(48–52dp@13–15sp)；`minHeight` 全为 `0dp` 或未设。
- 对照：页签已正确改为 `wrap_content + minHeight=48dp`，本次只改了页签这一组。
- 影响：系统字体 1.3×/1.5×/2.0× 时文字被垂直裁切；适老化/无障碍检查项，部分商店会抽查。
- 修法：与页签同构 —— `layout_height="wrap_content"` + `minHeight=<原值>`。

**P1-2｜`dist/` 里没有 3.0.0 产物，交付物只躺在 `build/outputs`**
- 事实：`dist/` 最新是 `ShotTuner-2.1.0-release.apk`；3.0.0 只在 `app/build/outputs/apk/release/app-release.apk`（40,345,338 B）。`build/` 在 `.gitignore` 里，一次 `clean` 就没了。
- 影响：上架用的安装包没有按历史命名规范归档（`dist/ShotTuner-3.0.0-release.apk`），容易误传旧包。
- 修法：`cp app/build/outputs/apk/release/app-release.apk dist/ShotTuner-3.0.0-release.apk`，并用 `apksigner verify` 复核一次归档副本的指纹。

**P1-3｜`Diag` 的 `SimpleDateFormat` 在锁外使用（线程不安全）**
- 位置：`app/src/main/java/com/shottuner/app/diag/Diag.kt:110-121` —— `stamp.format(Date())` 在 `buildString` 内、`synchronized(lock)` 外。
- 影响：多线程写日志时可能输出错乱时间戳或抛异常；异常发生在 `runCatching` 之外，会冒泡到调用方（相机回调、UI）。
- 修法：把 `stamp.format` 移进 `synchronized(lock)`，或改 `ThreadLocal<SimpleDateFormat>` / `DateTimeFormatter`（推荐后者）。

### P2 建议（6 条）

**P2-1｜`.gitignore` 只能核对文本，无法证明 `gradle.properties` 从未被提交** —— 本机无 git 仓库。首次 init 后请立刻确认；若曾误提交，必须换密钥（代价 = 备案作废）。
**P2-2｜`privacy_v1.txt` 与 PRD §2 不是逐字一致**（多了 FAQ 段、少了 markdown 标记）。不算缺陷，但**部署网页版时必须抄 raw 这一版**（ARCH §6.5 要求网页与 raw 逐字一致）。
**P2-3｜`Diag` 环形缓冲用字符数比字节数**（`line.length` vs `MAX_BYTES`），中文场景实际单文件可达 ~192KB 而非 64KB，总计 ~576KB。有界不致命，与注释不符。
**P2-4｜通知小图标用系统内置 `android.R.drawable.ic_menu_camera`** —— 部分 ROM 会渲染成白块，商店也可能要求品牌化小图标。建议补一个 monochrome 的 `ic_stat_notify`。
**P2-5｜`READ_EXTERNAL_STORAGE(maxSdk 28)` 出现在权限清单** —— 由 `WRITE_EXTERNAL_STORAGE` 隐含，非独立声明；但商店权限表单会列出来，隐私政策 §2.6 只提了 WRITE。补一句"写入权限在旧系统上同时包含读取"即可。
**P2-6｜`app/build/` 下的 lint 中间产物仍含旧包名** —— 不影响产物，但会让"全仓 grep 旧包名"看起来有命中，验收脚本应排除 `app/build/`。

---

## 3. 无法在无真机环境验证的项（必须真机回归）

以下是我**做不到**的部分，不要因为报告里没写问题就当作已验证：

1. **`INTERNET` 移除后 ML Kit 是否仍正常**（P0-1 的修法验证）：bundled 模型理论上不需要网络，但 `vision-internal-vkp` 的 telemetry 在无权限时是否静默失败、会不会拖慢首帧，只能装机看。**这是 P0-1 修完后最大的未知。**
2. **相机三级降级的实际触发**：`bindToLifecycle` 失败需要"相机被其他 App 占用"这种现场，静态读只能证明"三条路径写得不一样"，不能证明"降级后取景真的能用"。
3. **录屏 + 悬浮窗 + 通知**：`MediaProjection` 授权弹窗、前台服务通知的样式、「停止」action 的点击、Android 14 每次授权行为 —— 全部需要真机。
4. **隐私覆盖层的实际观感**：`privacy_v1.txt` 有 11.7KB，塞进一个覆盖层里的滚动体验、字号、是否被键盘/系统栏遮挡，无法静态判断。
5. **启动图 / 动画 / 大字体**：字体缩放到 2.0× 的实际截断程度、启动图是否有黑屏、动画流畅度 —— 需要真机 + 开发者选项调字体。
6. **返回键五层的实际操作手感**：尤其"隐私门不拦截返回 → 直接退出 App"这条，是否符合预期体验需要产品上手确认。
7. **`enableOnBackInvokedCallback` 在 Android 13/14/15 上的行为差异**（预测性返回手势）。
8. **16KB 页设备上 ML Kit `.so` 是否对齐**（ARCH §9.4 风险 8）—— 需要目标机型或 16KB 模拟器。
9. **targetSdk 35 的边缘到边缘行为**：Android 15 强制 edge-to-edge 后，`llControlBar` 的 inset 是否真的让开了三键导航条，需要真机量。
10. **图标在小 ROM 启动器上的自适应裁切**（`roundIcon` 与 `icon` 同源可能在某些启动器上被裁成方块）。

---

## 4. ROUTE 判定

```
ROUTE: ENGINEER
```

**当前不能上架。** 两个 P0：

1. **`INTERNET` 权限**（库传入，非自研代码）—— 与隐私政策正文 4 处承诺正面冲突，是"核心合规论据"级的失效。修法是一行 manifest + 一次 `aapt2` 复核 + 一次真机 ML Kit 回归。
2. **「关于」页缺失** —— PRD 与 ARCH 都标了阻塞，且隐私政策自指该页面，形成第二个可被审核直接抓到的矛盾。

其余 3 条 P1（大字体截断、交付物未归档、Diag 线程安全）都是"改起来很快、但会咬人"的类型，建议同批处理。

**做得好的三处，值得明确记一笔**（避免后续改动把它们改坏）：
- `ShotTunerApp.installCrashHandler()` 的"先落盘、再转发、绝不吞异常"顺序，正确；
- 受限模式只关相机通路、页签照常可用，没有把 App 锁死；
- 返回键的 `passThrough()`（先禁自己再派发）避免了自递归。

**判定为 `ENGINEER` 而非 `QA`**：所有结论都有可复现证据（`aapt2` 双份输出、manifest-merger 报告行号、XML 解析、121/0/0 的逐类统计、`grep` 零命中），不是我的脚本误判。
**判定不是 `NO_ONE`**：两个 P0 都在"上架即被拒 / 上架即虚假陈述"的级别，不能带着发。

---

# 复核轮（Round 2）· 2026-10-20

> 复核范围：只验上一轮 2 个 P0 + 3 个 P1 是否真关闭，不重做全量报告。全部结论基于**新产物实测**，不采信工程师的修复声明。
> 复核对象：`dist/ShotTuner-3.0.0-release.apk`，`40,346,750` 字节，SHA-256 `8a2457bc7bb837ffff0941b35f54b9c6fa3a30b5a728b8ba4f63997cb84a4b76`（与主理人给的数字逐字一致）。

## 复核结论总表

| 项 | 上轮结论 | 本轮 | 一句话 |
|---|---|---|---|
| P0-1 INTERNET 权限 | ❌ 存在，与政策矛盾 | ✅ **关闭** | 三份独立证据均确认 `INTERNET` 与 `ACCESS_NETWORK_STATE` 已不在产物里 |
| P0-2 关于页 | ❌ 不存在 | ✅ **关闭** | 控件/代码/可达性/单一数据源全部落实，且已同意用户可达 |
| P1-1 大字体截断 | ❌ 17 个固定高度文本按钮 | ✅ **关闭** | 38/40/52dp 归零；剩余固定高度节点全是不含文本的图形/占位控件 |
| P1-2 产物未归档 | ❌ `dist/` 无 3.0.0 | ✅ **关闭** | `dist/ShotTuner-3.0.0-release.apk` 已就位，指纹与实测一致 |
| P1-3 Diag 线程安全 | ❌ SDF 在锁外共享 | ✅ **关闭** | `ThreadLocal` + `initialValue`，唯一调用点走 `stampLocal.get()!!.format(...)` |
| 回归 | 121/0/0 | ✅ 未回退 | `-Rerun` 真跑（52 tasks executed），双 flavor 各 121/0/0/0 |
| 签名/版本 | 通过 | ✅ 未回退 | `08b209a3…262b`、v2 true、`30` / `3.0.0` / `targetSdk 35` |

## P0-1 复核 —— ✅ 关闭（三份独立证据）

**证据 1 · `aapt2 dump permissions`（主理人指定）**

```
package: com.shottuner.app
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'
uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
permission: com.shottuner.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.shottuner.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

→ **`INTERNET` 与 `ACCESS_NETWORK_STATE` 均不在。**

**证据 2 · `aapt2 dump badging`（第二条路径，防止单一统计口径的错觉）**

```
package: name='com.shottuner.app' versionCode='30' versionName='3.0.0' compileSdkVersion='35'
sdkVersion:'24'   targetSdkVersion:'35'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'
uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='com.shottuner.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='28'
```

→ 同样无 `INTERNET` / `ACCESS_NETWORK_STATE`。`READ_EXTERNAL_STORAGE(maxSdk 28)` 是 `WRITE_EXTERNAL_STORAGE` 的**隐含项**（只有 badging 会列出，`dump permissions` 不列），不是独立声明。

**证据 3 · 打包后清单字符串级核对**

`aapt2 dump xmltree --file AndroidManifest.xml` 全文匹配 `INTERNET|ACCESS_NETWORK_STATE` 的条数 = **0**。
这是最硬的一条：连"合并进来又被别处引用"的残留都不存在，不是只在某个视图里被过滤掉。

**源码侧核对**：`app/src/main/AndroidManifest.xml:27-32` 两条 `tools:node="remove"`；注释说明了为什么只移除权限、**不 exclude 依赖**（保留 datatransport 类以避免 `NoClassDefFoundError`，只是拿不到网络能力）。这个取舍比 exclude 更稳。

**与隐私政策的一致性已恢复**：`privacy_v1.txt` 的 `:24` / `:41` / `:70` 三处「本应用未申请 INTERNET 网络访问权限…在操作系统层面不具备联网能力」，现在与权限清单**一致**，不再构成虚假陈述；关于页里同样的表述也成立。

> 唯一未消除的未知（不属代码缺陷）：**移除权限后 ML Kit 在真机上是否仍正常**。bundled 模型不需要网络、telemetry 拿不到 socket 应静默失败，但这条只能装机验证。见文末清单第 1 条。

## P0-2 复核 —— ✅ 关闭

| 检查点 | 实测 | 判定 |
|---|---|---|
| 布局控件存在 | `btnAbout`(:1102)、`llAbout`(:1307)、`btnAboutPolicy`(:1353)、`btnAboutClose`(:1413) | ✅ |
| 代码接线 | `btnAbout→showAbout()`(:361)、`btnAboutClose→GONE`(:362)、`btnAboutPolicy→显示 llPrivacyFull`(:365-367)、`showAbout()`(:2103-2109) | ✅ |
| 隐私全文是**同一份**、不是第二副本 | 全仓 `R.raw` 只有 **1 处**引用（`MainActivity.kt:506` 的 `readPrivacyText()`）；`res/raw/` 只有 **1 个** `privacy_v1.txt`（11,695 B，mtime 未变）；`tvPrivacyFull` 只在 `:413` 赋值一次，且该行在 `if (!privacyAccepted)` **之前**执行 → 已同意用户进关于页同样有全文 | ✅ |
| 关于页内容 | 文本节点实测含：「隐私政策全文」按钮 +「个人信息收集清单」+「第三方共享清单」+ 版本号/邮箱 + 网络权限声明 | ✅ 与 ARCH §6.4 三条一致，且复用同一份 raw、未另写文案（正合"不要两套"的要求） |
| 已同意后仍可达 | `btnAbout` 父链 = 面板内容容器（`ScrollView(panel)` 直属），**不在任何 page 容器内** → 四个页签下都可见可点；`:413` 已提前灌好全文 | ✅ 比"仅功能页可达"更严格 |
| 覆盖层质量 | `llAbout` 四边锚 parent、`background=@color/st_bg`（不透明，不叠相机画面，避免"读着文字背后画面在动"的老问题）、`clickable/focusable=true`（不透传触摸）、初始 `gone` | ✅ |
| 返回键 | dispatcher 新增 `llAbout==VISIBLE → GONE`（:440-441），共六层 | ✅ 与声称一致 |
| z 序 | `llAbout`(index 9) 在 `llCoachIntro`(10) / `llPrivacyGate`(11) / `llPrivacyFull`(12) 之前 → 全文页能盖住关于页，符合 `:363-364` 注释的设计 | ✅ |

**一处与声明不符（不阻断，建议产品确认）**：工程师说"功能页底部新增 `btnAbout`"，实测它挂在**面板内容容器的末尾**（`llReviewCard → pagePhoto → pageVideo → pageFeature → pageSkill → btnFloat → tvCaps → btnExportDiag → btnAbout`），即**四个页签下都出现在面板滚动区底部**，不是只在功能页。对合规来说这是更严格的可达性（任何页都能找到隐私入口），但"导出诊断/关于"两个按钮在拍照页也会占用面板底部空间。请产品确认这是否是想要的位置；代码上无需改动。

## P1 复核

**P1-1 大字体截断 —— ✅ 关闭**
- `layout_height="38dp"` / `"40dp"` / `"52dp"` 的计数均为 **0**。
- 全布局剩余固定高度节点逐个查过，**全部不含文本**：`tvCoachTag`(8dp，已是圆点 View)、`histogram`(46dp，HistogramView)、3 个 34dp 的 `View` 分隔/占位、`ivThumb`(48dp ImageView)、`btnShutter`(72dp ImageButton)、2 个 1dp 分隔线、1 个 48dp `View`。
- 文本按钮现为 `wrap_content` + `minHeight`（如 `btnAbout` 44dp）。
- 结论：大字体下不会再出现文字被垂直裁切。

**P1-2 产物归档 —— ✅ 关闭**
- `dist/ShotTuner-3.0.0-release.apk` 存在，`40,346,750` B，SHA-256 `8a2457bc…4b76`，与主理人给的一致。

**P1-3 Diag 线程安全 —— ✅ 关闭**

```kotlin
private val stampLocal = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
}
...
append(stampLocal.get()!!.format(Date()))   // :129，全文件唯一调用点
```

- 每线程独立实例（`initialValue` 正确重写）✅
- 全文件已无共享可变的 `SimpleDateFormat` 字段 ✅
- 选型理由（minSdk 24 + 未开 desugaring，`java.time` 会 `NoClassDefFoundError`）成立，`ThreadLocal` 是等价且零 API 门槛的解法 ✅

## 回归与产物（复核轮重测）

- `tools/run-tests.ps1 -Rerun`：`52 actionable tasks: 52 executed`（真跑，非 UP-TO-DATE）、`BUILD SUCCESSFUL`、`GRADLE_EXIT=0`。
- 结果 XML 统计：**debug 121 / 0 / 0 / 0**，**release 121 / 0 / 0 / 0**（8 个测试类）。计数与上一轮完全一致 —— 无回退、无新增或删除用例。
- 签名：`apksigner verify --verbose` → `Verifies`，`Verified using v2 scheme: true`，cert SHA-256 `08b209a35bb85b703943b4febf5b91ec593043fdb79b53d7ed1529000aca262b`（RSA 4096），与上轮同一证书。
- 版本：`com.shottuner.app` / `versionCode 30` / `versionName 3.0.0` / `targetSdkVersion 35` / `compileSdk 35`。

## 上架前仍需完成（非代码，与上轮相比只少了一项）

代码侧已无 P0/P1。以下仍会挡住提审：

1. **真机回归一次**（与本次修复直接相关、新增的验证义务）：移除 `INTERNET` 后跑一遍 ML Kit 人脸检测 + 图像标注 + 录屏悬浮卡，确认无 `NoClassDefFoundError`、无首帧明显变慢、无功能静默失效。不做这条就无法承诺"零网络且功能完整"。
2. 隐私政策公网 URL（HTTPS，正文与 `privacy_v1.txt` 逐字一致）。
3. 软著登记（名称与「光影调参」一致）。
4. 工信部备案（包名与签名已定稿，之后不可再改，否则作废）。
5. 五家商店开发者账号实名 + 商店素材（截图/简介/详述）。
6. keystore 离线备份（当前仍是单点）。
7. 大字体（1.3×/2.0×）真机走查：布局已改，实际行数与观感需肉眼确认。

## 最终 ROUTE

```
ROUTE: NO_ONE
```

**代码侧两个 P0 与三个 P1 全部关闭，没有遗留缺陷需要工程师返工。**

但要读准这句话的含义：`NO_ONE` = **代码侧不再需要改动**，不等于"今天就能点提交"。上面 7 条里第 1 条（真机回归 ML Kit）是本次修复**直接引入**的验证义务，其余 6 条是发布流程本身的门槛。请不要把本判定读成"免真机"。
