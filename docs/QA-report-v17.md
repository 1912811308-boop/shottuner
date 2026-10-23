# QA 独立验证报告 · ShotTuner v1.7.0（第一批 UI 优化）

> 验证人：严过关（QA）｜ 日期：2026-10-17
> 复核范围：v1.6.0(15) → **v1.7.0(16)**，依据 `docs/UI-v2-design.md` 的 P0-1～P0-5 + 两个连带修复（共 7 处）
> 本轮**真跑了构建与测试**（`tools/run-tests.ps1 -Rerun`），并做了 APK 密码学校验。
> 本报告不复述工程师自测结论；每条结论都附「我怎么验的」+ 文件:行号 或 实算数据。

---

## 0. 产物与测试的独立复核（先给硬指标）

| 项目 | 工程师声称 | 我的实测 | 判定 |
|---|---|---|---|
| 用例数 | 121 | **121**（debug）+ **121**（release） | ✅ |
| 失败数 | 0 | **0 failures / 0 errors / 0 skipped**（两侧） | ✅ |
| 测试是否真跑过 | — | 首次跑是 `UP-TO-DATE`（复用旧结果），加 `-Rerun` 后 **52 tasks executed** 才作数 | ✅ 已排除"假跑" |
| `versionCode` | 16 | `aapt2 dump badging` → `versionCode='16' versionName='1.7.0'` | ✅ |
| 签名 | 未声称 | `apksigner verify` → **Verified using v2 scheme: true**；1 signer，`CN=ShotTuner, O=Clover, C=CN`，RSA 2048，证书 SHA-256 `13c30884…90d1` | ✅ |
| APK 体积 | 未声称 | `40,325,686` 字节；SHA-256 `21bac2d65636468dd77d2cbeb944b3c7bfebf306125969bf3346fa88eab156b9` | 记录 |

> 注：上一轮（v1.5.0）给你的 `30fca519…a85d` 是 1.5.0 的哈希，与 1.7.0 无关。

**本轮改动面（按 mtime 判定，本轮窗口 ≥22:00）**：只有 7 个文件 —— `bg_panel.xml`、`bg_pill.xml`、`colors.xml`、`GuideOverlayView.kt`、`bg_thumb.xml`、`activity_main.xml`、`MainActivity.kt`（+ `build.gradle.kts`）。`knowledge/`、`analysis/`、`recommend/`、`video/`、`feature/`、`camera/`、`coach/`、`sensors/` **全部早于 22:00，本轮零改动**。

---

## 1. 七项逐条结论

### 1.1 布局结构 —— ⚠️ 基本通过，有 1 处结构性风险 + 1 处算术不达标

| 检查点 | 结果 | 证据 |
|---|---|---|
| `llControlBar` 是否不参与任何滚动容器 | **✅ 是** | 用 XML 树反查父链：`llControlBar` 的父节点就是根 `ConstraintLayout`，**不在 `ScrollView(panel)` 里**。根的直接子节点顺序为 `previewView → guideOverlay → tvShutterFlash → topBar → panel → llControlBar` |
| `panel.constraintBottom` → `llControlBar.toTopOf` | ✅ | `activity_main.xml:114` `app:layout_constraintBottom_toTopOf="@id/llControlBar"` |
| `panel` 高度改 0dp + percent 0.52 | ⚠️ **有风险** | 见下「结构风险」 |
| `btnShutter` 72dp 且在 `llControlBar` 内 | ✅ | `btnShutter`(:1024) 父链 = `llControlBar`(:995) → `ConstraintLayout`，`layout_width/height=72dp` |
| `ivThumb` 48dp 存在 | ✅ | `:1009`，在 `llControlBar` 内，48dp，初始 `invisible` |
| `tvShutterFlash` 层级 | ✅ **完全符合** | `tvShutterFlash`(:33) 声明顺序 index=2：在 `previewView(0)`/`guideOverlay(1)` 之后、`topBar(3)`/`panel(4)` 之前 —— 只盖画面不盖文字。且四边全锚 parent、`clickable=false`、`focusable=false`、`alpha=0`，不会挡触摸 |
| `android:id` 是否重复 | ✅ 无重复 | 全布局 72 个 id，`Counter` 检查重复项为空 |
| `binding.xxx` 与布局 id 对账 | ✅ 全通 | 66 个 `binding.*` 引用中，除 `ActivityMainBinding`（类名误命中）与 `root`（ViewBinding 内置）外**全部能在布局里找到**；反方向"布局有、代码未引用"的 5 项经查是 `R.id.*` 形式引用或纯容器（`llControlBar`/`panel`/`topBar` 无代码引用属正常，`rbDensity*` 走 `R.id`） |

#### 结构风险（P1-a）：`panel` 只有**单边**纵向锚点 + `0dp` + `percent`

`panel` 的全部纵向约束只有一条 `layout_constraintBottom_toTopOf="@id/llControlBar"`（`activity_main.xml:116`），**没有 `layout_constraintTop_*`**，同时又写了 `layout_height="0dp"`（:107）+ `constraintHeight_percent="0.52"`（:118）。

- 官方文档对 `0dp`（MATCH_CONSTRAINT）的定义是"占满两侧锚点之间的可用空间"，percent 的官方示例也是**上下双锚点**；单边锚点 + 0dp 不在文档给出的用法里。
- 我本来想用 `lint`（`MissingConstraints`）给出机器裁决，但**离线跑不起来**：`:app:generateDebugAndroidTestLintModel` 依赖 `kotlin-stdlib-jdk8:1.8.20` / `androidx.exifinterface:1.0.0`，本机 Gradle 缓存里没有、`--offline` 无法下载（已试两次，换 `-x` 跳过也失败）。
- 因此这条是**二元风险**：ConstraintLayout 2.x 对 `MATCH_CONSTRAINT_PERCENT` 的尺寸解析是"按 parent 高度 × 百分比"，与锚点数量无关 —— 大概率正常；但**若解析落回 MATCH_CONSTRAINT 语义，`panel` 高度会是 0，整个面板（四个页签 + 全部内容）不可见**。
- **建议（零风险、一行）**：补 `app:layout_constraintTop_toBottomOf="@id/topBar"`。有双锚点后 percent 仍然生效（文档示例即如此），同时把"面板从哪开始"变成显式契约。
- **Round 2 第一件事**：装上看面板有没有出来。若没出来，本条直接从 P1 升 **P0**。

#### 算术不达标（P1-b）：P0-2 自己的验收标准在最小屏上不成立

设计文档 P0-2 的验收是「360×640dp / 393×873dp / 412×915dp 三种屏宽下，取景区可见高度 ≥ 34% 屏高」。按当前数值推演（面板 0.52 屏高、控制条 84dp 高 + 14dp 底边距、面板 12dp 外边距）：

| 屏高 | 面板高度 | 面板上沿 y | 面板以上区域 | 扣掉 topBar 遮挡后的净取景 |
|---|---|---|---|---|
| 640dp | 332.8dp | 197.2dp | **30.8%**（<34% ❌） | **≈17.7%** |
| 873dp | 454.0dp | 309.0dp | 35.4%（✅ 刚过线） | ≈25.8% |
| 915dp | 475.8dp | 329.2dp | 36.0%（✅） | ≈26.8% |

（topBar 高度按 `marginTop=44dp` + 一行 15sp/11sp 文字估 ≈42dp 计，±4dp 不影响结论量级。）

也就是说：**卖点屏（640dp 小屏）上取景区只剩三成，比改动前的"面板 94% 把取景挤没"确实好了很多，但仍没到自己立的 34% 线**。若把 `percent` 从 0.52 调到 0.45，640dp 上能到 ≈38.6%，三档全过。

---

### 1.2 音量键逻辑 —— ✅ 通过（有 1 处"半消费"瑕疵）

`MainActivity.kt:315-328` 的实际代码：

```kotlin
val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
val firstPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
if (isVolumeDown && firstPress &&
    currentTab == TAB_PHOTO && engine != null &&
    !captureInProgress && uiPrefs.getBoolean(KEY_VOLUME_SHUTTER, true)
) { onShutterClick(); return true }
return super.dispatchKeyEvent(event)
```

逐个组合推演（要求"四前提任一不成立就走 super"，即系统音量面板正常出现）：

| 失效组合 | 实际走向 | 是否吞键 | 结论 |
|---|---|---|---|
| `currentTab != TAB_PHOTO` | `return super` | 否 | ✅ 音量正常 |
| `engine == null`（相机未就绪/权限未给） | `return super` | 否 | ✅ 音量正常 |
| `captureInProgress`（正在写盘） | `return super` | 否 | ✅ 音量正常（拍照期间按音量下会调音量，属可接受，且 `onShutterClick` 内部还有 `if (captureInProgress) return` 双保险） |
| `uiPrefs` 开关为 false | `return super` | 否 | ✅ 与"没实现这功能"完全一致 |
| 四个前提全满足 | `onShutterClick(); return true` | **是** | ✅ 快门触发 |
| `VOLUME_UP` | 条件第一项即 false → `return super` | 否 | ✅ 放行 |

- **`ACTION_DOWN + repeatCount == 0`**：✅ 正确。长按不会连拍（`onShutterClick` 内还有 `captureInProgress` 防重入）。
- **"吞了键但没做事"的中间态**：**不存在**。所有不满足前提的分支都在 `return true` 之前被排除。
- **但有反向的中间态（P2-d）**：首次 DOWN 被吞掉拍照后，**后续 `repeatCount>0` 的 DOWN 和 ACTION_UP 都会走 `super`**。AOSP 的未处理音量键最终由 `PhoneWindowManager.dispatchUnhandledKey` 处理，因此"按住音量下不放"的观感是：**拍一张 → 之后开始把系统音量往下拉**（具体是否触发取决于 ROM）。建议在照片页且开关开启时把整个 volume-down 事件流都吞掉：`if (isVolumeDown && 生效中) return true`（放在 `firstPress` 判断外），或者在注释里明确接受这个副作用。

---

### 1.3 对比度 —— ⚠️ 主目标达成，两处边界未达 AA/非文本标准

**全部用 WCAG 2.1 相对亮度公式实算，不引用设计文档的数字。**

**(a) `#7A8B96` 在 `#12161A` 上**

| 前景 | 实测 | 文档声称 | 判定 |
|---|---|---|---|
| `#7A8B96`（新 `st_text_faint`） | **5.16:1** | 5.1:1 | ✅ 达 AA 正文（≥4.5），与声称一致 |
| `#5F6E77`（旧值） | **3.45:1** | 3.5:1 | ✅ 说明旧值确实不合格，改动方向正确 |

**(b) 面板 95% / 胶囊 88% 透光后（考虑透光，这是文档自己强调的主因）**

`bg_panel` 的 `solid=#F212161A` = 95% 不透明，`bg_pill` = `#E012161A` = 88%。把"画面最暗（纯黑）/ 最亮（纯白）"两种极端按 alpha 混合后再算：

| 前景 | panel95 黑画面 | panel95 白画面 | pill88 黑画面 | pill88 白画面 |
|---|---|---|---|---|
| `st_text #F2F6F8` 13sp+ | 16.87 | **14.73** | 17.13 | 11.89 |
| `st_accent #7FE3C0` | 11.92 | 10.41 | 12.11 | 8.41 |
| `st_text_dim #8A9AA5` 11sp | 6.33 | 5.53 | 6.43 | **4.46** |
| `st_text_faint #7A8B96` 10sp | 5.21 | **4.55** | 5.29 | **3.67** |

- 结论 1：panel 95% 把"最坏情况"从旧 80%（`#8A9AA5` 约 2:1 量级）拉到 **最低 4.55:1** —— 10sp 最小字号刚好压在 AA 线上，**余量只有 0.05**。方向正确，但已经没有任何缓冲。
- 结论 2（**P2-b**）：`bg_pill` 88% 直接叠在实时画面上（`topBar`），最亮画面下 `tvLuma`（11sp `st_text_dim`）只有 **4.46:1**，略低于 4.5；`st_text_faint` 在胶囊上最差 **3.67:1** —— 目前胶囊里没有 10sp faint 文字（`tvCaps`/`tvDensityHint` 都在 panel 里，实际是 4.55），但这是一个"以后往里加一行字就踩线"的坑。建议 pill 也提到 92–95%，或把 `tvLuma` 换成更亮一档。

**(c) `st_priority_*` 三色对比度与语义映射**

| 优先级 | 色值 | panel95 黑底 | panel95 白底 | 语义 | 代码映射 |
|---|---|---|---|---|---|
| URGENT | `#FF8A65` | 7.93:1 | 6.92:1 | 硬伤（橙） | `MainActivity.kt:1027` → `R.color.st_priority_urgent` ✅ |
| CONTEXT | `#7FE3C0` | 11.92:1 | 10.41:1 | 场景强相关（主色） | `:1028` → `st_priority_context` ✅ |
| GUIDE | `#9FC6E8` | 10.23:1 | 8.94:1 | 教学（冷蓝） | `:1029` → `st_priority_guide` ✅ |

三个色与 `PromptPriority` 的 `when` 分支**逐条一一对应**，没有错位（这正是架构 §4.2 点名的"错位不报错"雷区，我逐个核过 `colors.xml:47-49` 的实际色值与 `when` 分支的对应关系）。三色最低对比度 6.92:1，远高于 10sp 标签所需。

**(d) 布局里是否还有残留十六进制** —— **✅ 零残留**：`grep '#[0-9A-Fa-f]{6,8}' activity_main.xml` 无输出。
但 **Java 侧没有清零（P2-a）**：`MainActivity.kt` 仍有 **18 处** `Color.parseColor("#...")`，包括 `selectTab()` 里的页签选中/未选中色（:417/:420）、`AdviceKind` 三色（:562-564）、以及两个**根本不在 `colors.xml` 里**的值 `#C8D4DA`（:567）与 `#E6EEF1`（:670）。所以"下次调色只改一处"这个目标只完成了一半。

---

### 1.4 拍照反馈链路 —— ✅ 通过（复盘卡位置见 P1-d）

| 检查点 | 结果 | 证据 |
|---|---|---|
| `AlertDialog` 是否从拍照路径删干净 | ✅ | `showShotReview` 全仓 **0 命中**（连注释都没有）；`AlertDialog` import 还在，但只剩 `showHelp()`（:694）与分享面板（:1519）两处**与拍照无关**的用途 |
| 成功路径零 toast | ✅ | 成功分支（:1225-1237）只有 `flyThumbTo` + `showReviewCard`；toast 仅两处 —— 相册写入失败（:1240）与拍照异常（:1264），与设计一致 |
| 白闪 90ms | ✅ 45+45 | `FLASH_IN_MS=45` / `FLASH_OUT_MS=45`（:1691-1692），`alpha 0→0.55→0` 链式动画（:1410-1416） |
| 缩略图 180ms | ✅ | `THUMB_FLY_MS=180`（:1697），`DecelerateInterpolator`（:1462），从快门中心 tween 到 `ivThumb` |
| 复盘卡 5000ms 自动收起 | ✅ | `REVIEW_CARD_MS=5_000`（:1687），`postDelayed`（:1397）；点卡片与点「看技法」都会 `removeCallbacks`（:340-343）；`onDestroy` 里也撤（:1765） |
| 白闪 View `clickable=false` | ✅ | `activity_main.xml` 里 `android:clickable="false"` + `android:focusable="false"`，全屏但透明、不拦触摸 |
| 复盘卡内容映射 | ✅ | `buildShotReview()` 返回 `head \n dims \n 建议：advice`，`showReviewCard` 按行切给 `tvReviewHead/tvReviewDims/tvReviewAdvice`，`removePrefix("建议：")` 正确（:1388-1393 vs :1365-1374） |

**P2-c：工程师自报的"面板滚下去就看不见复盘卡"** —— 我确认结构上成立（`llReviewCard` 在 `ScrollView(panel)` 的内容容器里，`activity_main.xml:133`）。评估：**P2 而非 P1**。因为 A5 要求"已保存的唯一确认"是缩略图飞入 + 白闪，这两个是覆盖层、任何滚动位置都看得见；复盘卡是补充信息。但建议加一行 `panel.scrollTo(0,0)`，成本极低。

---

### 1.5 网格线双描边 —— ✅ 通过（交点也做了，但"面"的颜色与线不一致）

`ui/GuideOverlayView.kt`：

- **底描边**：`gridShadowPaint` = `argb(102,0,0,0)`（黑 40%）+ `strokeWidth dp(2.5f)`（:50-54）✅ 与设计一致。
- **面描边**：`gridPaint` = `argb(89,255,255,255)`（白 35%）+ `dp(1f)`（:33-37）✅ 颜色对；文档写的是"1px"，实现是 **1dp**（xxhdpi 上约 2.6px），比文档粗一点，观感更好，属可接受偏差。
- **绘制顺序**：先整组画黑底（:189-194），再整组画白面（:195-200）✅ 正确（不是逐线交替，避免后画的线盖住前一条的黑底）。
- **交点圆是否也双描边**：✅ **做了**。`drawCircle(..., gridShadowPaint)` 后 `drawCircle(..., crossPaint)`（:205-206）。但**"面"用的是 `crossPaint`（白 47%，`argb(120,…)`），不是线条用的 35%** —— 交点比线更亮。可能是刻意的强调，但与"同一套双描边"的表述不完全一致，记录在 P2-e。

**亮/暗背景可辨识度（实算，交点为圆环 `Style.STROKE`）**：

| 背景 | 黑40%底（相对背景） | 白35%面 | 旧白18%单线 |
|---|---|---|---|
| 亮背景 `#E6E6E6`（luma≈0.9） | **2.77:1** ✅ 可见 | 1.09:1（本身不可见） | 1.05:1 ❌ **确实等于没有** |
| 暗背景 `#1A1A1A`（luma≈0.1） | 1.09:1 | **3.22:1** ✅ 可见 | 1.76:1（勉强） |
| 中灰 `#808080` | 2.14:1 | 1.74:1 | 1.35:1 |
| 天空亮蓝 `#BFD8F0` | **2.68:1** ✅ | 1.15:1 | 1.08:1 |

结论：双描边成立 —— **任何背景总有一侧 ≥2.14:1**，而旧实现在亮背景上只有 1.05:1（设计文档的诊断被数值复现，问题真实）。
**P2-f**：亮背景侧最高只到 2.77:1，低于 WCAG 1.4.11「非文本图形对象 3:1」。若想彻底达标，黑底从 40% 提到 50%（实测 3.4:1 左右）或 2.5dp→3dp 即可。

---

### 1.6 `installFramingLine()` 删除彻底性 —— ✅ 通过

| 检查点 | 结果 | 证据 |
|---|---|---|
| 方法本体与调用点 | ✅ **零命中** | 全仓 grep：`installFramingLine(` 作为调用**0 处**；残留的 6 处全是**说明性文字** —— 2 处代码注释（`MainActivity.kt:230`、`:1046` 都在解释"已删除"）+ 1 处布局注释（`activity_main.xml:372`）+ 3 处历史文档 |
| `tvFraming` 静态声明 | ✅ | `activity_main.xml:378` 在 `pagePhoto` 内静态声明，`textSize=13sp`、`maxLines=2`、`ellipsize=end` |
| `updateFramingLine()` 能选中它 | ✅ | `MainActivity.kt:1130` `if (binding.tvFraming.text != text) binding.tvFraming.text = text`；400ms 节流（:1088）；三档文案（有建议 / 有俯仰角 / 等画面）都在 |
| `TextUtils` import 是否删 | ✅ 已删 | `grep TextUtils app/src/main/java` 零命中 |

> 说明：任务书要求"全仓 grep 零命中"。严格的零命中做不到也不该做 —— 删掉的东西留一句注释说明"为什么删"，比抹掉痕迹好。我按"**有无残留调用 / 有无残留死代码**"判定，结论是通过。

---

### 1.7 回归 —— ✅ 通过

| 检查点 | 结果 | 证据 |
|---|---|---|
| 121 用例 0 失败 | ✅ | debug: 121/0/0/0，release: 121/0/0/0（8 个测试类逐类对数：FramingCoach 14、LevelMath 13、PhotographyBook 6、PromptEngine 22、PromptScheduler 26、ScoreBoard 18、TechniqueBookV15 13、TechniqueMatcher 9） |
| 逻辑层零改动 | ✅ | 本轮 mtime 窗口内只有 7 个 UI 文件；`knowledge/`(≤18:02)、`analysis/`(≤17:04)、`recommend/`(15:25)、`video/`(15:52)、`feature/`(16:59)、`coach/`(16:58)、`sensors/`(17:01) 全部早于 22:00 |
| `camera/` 是否被 P0-3 碰过 | ✅ **没碰** | `CameraEngine.kt` mtime 18:38（v1.6.0 内建拍照那轮），本轮未修改。P0-3 的拦截写在 `MainActivity.dispatchKeyEvent`，没有侵入相机层 —— 这个分层是对的 |
| `PromptCandidate` / `PromptSignals` 字段一个没碰 | ✅ | 两个文件 mtime 分别停在 17:47 / 17:59（v1.5.0 回修那轮），本轮未触及。字段清单与我上一轮记录一致（`PromptSignals` 14 个、`PromptCandidate` 含 `paced`），架构 §4.2 红线守住 |
| 测试文件是否被改动 | ✅ 未动 | 本轮窗口内无 `app/src/test` 下文件 —— 所以"121 全绿"是**同一套断言 + 新代码**的结果，不是改测试改绿的 |

---

## 2. 问题清单

### P0 阻断

**无（在当前静态证据下）。** 但有一条"待真机即可能升级为 P0"的项，见 P1-a。

### P1 应修

**P1-a｜`panel` 单边锚点 + `0dp` + `percent`，失败时面板高度会是 0（= 整块 UI 不可见）**
- 位置：`app/src/main/res/layout/activity_main.xml:105-120`（`panel` 起始 :106，唯一纵向锚点 `constraintBottom_toTopOf` 在 :114，`layout_height="0dp"` 在 :107，`constraintHeight_percent="0.52"` 在 :118，**无任何 `constraintTop`**）
- 为什么是 P1 而不是 P0：ConstraintLayout 2.x 对 `MATCH_CONSTRAINT_PERCENT` 的尺寸是按 parent 高度 × 百分比解析的，大概率正常；但这是"要么没事、要么全没"的二元赌局，而且没人渲染过。
- 复现：装 APK → 看面板是否出现；面板高度为 0 或退化成 wrap 即命中。
- 修法（1 行）：加 `app:layout_constraintTop_toBottomOf="@id/topBar"`。双锚点后 percent 仍生效（官方示例即此形态）。

**P1-b｜P0-2 的验收标准「三档屏宽下取景区 ≥34%」在 360×640dp 上不成立**
- 位置：`activity_main.xml:118` 的 `0.52` 与 `:995-1024`（`llControlBar` 底边距 14dp + 快门 72dp + 上下 padding 6dp×2 = 84dp）的组合
- 数据：640dp 屏上面板上沿 y≈197dp → 取景区 **30.8%**（扣掉 topBar 遮挡的净可见 ≈17.7%）；873dp: 35.4%；915dp: 36.0%。只有 640dp 不达标。
- 修法（1 个数字）：`percent` 0.52 → **0.45**，三档分别变成 ≈38.6% / 41.9% / 42.4%，同时仍满足 `min=240dp`（0.45×640=288dp > 240）。

**P1-c｜两个快门入口的可用范围不一致**
- 位置：`MainActivity.kt:264`（`btnShutter` 点击，**无 tab 判断**）× `:319`（`dispatchKeyEvent` 要求 `currentTab == TAB_PHOTO`）× `selectTab()`（:405-426，**从不改 `llControlBar` 可见性**）
- 现象：在「录像 / 功能 / 技法」页，底部 72dp 快门仍然常驻且可点、点了真会拍照（`engine` 一直在跑）；但同样这三页按音量下键不会有任何反应。同一块屏幕上两个"快门"入口行为不同。
- 需要产品/架构定夺：要么快门按 tab 隐藏、要么去掉音量键的 tab 限制。当前状态是"两个都对不上"。

**P1-d｜复盘卡插在页签**之上**，每次拍照把整个面板内容下推约 100dp**
- 位置：`activity_main.xml:133`（`llReviewCard` 是 panel 内容容器的 **index 0**，页签行是 index 1）
- 现象：卡片 `GONE→VISIBLE` 时，页签行与四个页面整体下移约 100–115dp（4 行文字 + 3dp 间距 + 卡片内边距），5 秒后再弹回。拍照是最高频动作，这个 reflow 每次都发生；若用户手指正悬在页签/按钮上方，插入瞬间落点会变（误触）。
- 修法：把卡片放在页签**之下**（`pagePhoto` 顶部），或改成覆盖式（`FrameLayout` 叠在 panel 上、不参与流式布局）。设计文档只写了"在 panel 顶部插入"，没考虑页签这个"位置锚点"被推走。

### P2 建议

**P2-a｜`MainActivity` 仍有 18 处硬编码十六进制，P0-4「只改一处」只完成了一半**
- 位置：`MainActivity.kt:417/420`（`selectTab` 页签选中态）、`:461/464/621/624`（意图/技能 chip）、`:552/567/568/646/669/670/672/678`（`labelText` 调用）、`:562-564`（`AdviceKind` 三色）
- 其中 `#C8D4DA`（:567）与 `#E6EEF1`（:670）**不在 `colors.xml` 里**，属于色板外的"野值"。
- 影响：下次调色仍要全文搜索；且 `#7FE3C0` 在代码里和 `@color/st_accent` 是两份真相。

**P2-b｜`bg_pill` 88% 在纯白画面下 11sp 次要文本 4.46:1，卡在 AA 线下 0.04**
- 位置：`bg_pill.xml` 的 `solid=#E012161A` + `activity_main.xml` 的 `tvLuma`（11sp `@color/st_text_dim`）
- 数据：见 §1.3(b)。panel 已经提到 95%，pill 仍留在 88%，两处都直接叠在实时画面上，标准不统一。

**P2-c｜复盘卡在 ScrollView 内，用户滚下面板时看不见**（工程师自报，我确认结构成立）
- 位置：`activity_main.xml:133`
- 评估：**P2**。A5 的"保存确认"由白闪 + 缩略图飞入承担（覆盖层，任何滚动位置可见），卡片是补充信息。建议 `showReviewCard()` 里加 `binding.panel.scrollTo(0, 0)`（1 行）。

**P2-d｜音量键"半消费"：长按 = 拍一张 + 之后交给系统调音量**
- 位置：`MainActivity.kt:315-328`（`firstPress` 不满足就 `return super`，包括 `repeatCount>0` 的 DOWN 与 ACTION_UP）
- 建议：在"照片页且开关开启"时对整个 volume-down 事件流返回 `true`；或把"长按会顺带调音量"写进注释明确接受。

**P2-e｜交点圆的"面"用白 47%，与线条的白 35% 不一致**
- 位置：`GuideOverlayView.kt:205-206`（`crossPaint` = `argb(120,255,255,255)`）vs `gridPaint` = `argb(89,…)`
- 影响：交点比网格线更亮。若是刻意的强调，建议在注释里写明；否则统一成 `gridPaint`。

**P2-f｜网格双描边在极亮背景只到 2.77:1，低于 WCAG 1.4.11 非文本 3:1**
- 位置：`GuideOverlayView.kt:50-54`（黑 40%、2.5dp）
- 建议：黑底 40%→50%，或 2.5dp→3dp。

**P2-g｜`scaledDensity` 已 deprecated（编译警告）**
- 位置：`GuideOverlayView.kt:106` `textSize = 10f * resources.displayMetrics.scaledDensity`
- 建议改 `TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)`。这是本轮唯一一条由本次改动文件引入的新警告（另外两条 `FramingCoach:94` / `VideoRecommender:71` 的未使用变量来自未改动文件，属历史遗留）。

---

## 3. 本轮的局限（必须与结论一起读）

1. **无法真机/模拟器渲染**。`percent` 约束是否真的解析成 52% 屏高、取景区实际观感、白闪与飞入动画的流畅度、复盘卡插入时的 reflow 有多刺眼 —— 全部只能推演，不能断言。**P1-a 与本报告里所有 dp 换算都建立在"percent 生效"这个假设上**，真机必须首先确认这一条。
2. **`lint` 跑不起来**。`:app:lintDebug` 在 `--offline` 下因 `kotlin-stdlib-jdk8:1.8.20`、`androidx.exifinterface:1.0.0` 不在缓存而失败（试过加 `-x` 跳过 lint model，仍失败）。所以 `MissingConstraints` 这个能机器裁决 P1-a 的检查项**没拿到**。这是环境限制，不是我没做。
3. **没有 git，无法做真正的 diff**。本轮"改了什么"是靠文件 mtime 反推的（7 个文件）。如果有人在 22:00 之前就改了某个逻辑文件、22:00 之后没再动，我的"逻辑零改动"结论会漏掉它。建议后续让工程师用 git 或有明确的改动清单。
4. **`PromptCandidate`/`PromptSignals` 字段"没碰"是 mtime 级证据**，不是 diff 级证据。字段清单我逐条列了并与上一轮记录比对一致，但严格说只能证明"本轮没有写这两个文件"。
5. **APK 内容未逐字节核对**。release 开了资源名混淆（`res/-1.xml` 这种），无法用 `aapt2 dump xmltree` 直接定位 `activity_main.xml` 去核对"发布包里就是新布局"。间接证据是：`versionCode=16` 正确、APK 构建时间（22:41）晚于源码最后修改（22:37）、签名 v2 通过。
6. **三档屏宽的百分比结论基于估算的 topBar 高度**（44dp margin + ≈42dp 行高）。±4dp 的误差不改变"640dp 不达 34%"的结论，但精确数字需真机量。

---

## 4. 路由判定

```
ROUTE: ENGINEER
```

**理由**：测试与产物层是干净的（121/0/0 双 flavor、v2 签名密码学通过、versionCode 16、逻辑层零改动、架构红线守住），7 处改动里 **6 处实质落地**。但有一组问题必须回工程：

- **P1-a 是"要么不出事、要么整个面板消失"的结构性赌局**，且没有 lint / 渲染兜底。修法只有一行，没有任何理由留着。
- **P1-b 是自定验收标准未达成**（640dp 屏取景区 30.8% vs 要求 34%），改一个数字即可。
- **P1-c / P1-d 是新引入的行为不一致与布局跳动**：一个快门两个可用范围、每次拍照把页签推走 100dp。这两条不是"风格问题"，是用户每天都会碰到的动作。

之所以不是 `ROUTE: QA`：所有结论都有可复现证据（XML 树反查父链、72 个 id 去重、66 个 binding 对账、WCAG 公式实算、`-Rerun` 强制重跑后的 52 tasks executed、`apksigner verify` 输出）。P1-a 的不确定性我也如实标注，没有把"猜"写成"查"。

之所以不是 `ROUTE: NO_ONE`：P1-a/b/c/d 都不宜带着进 1.7.0 发布；P2-a 与 P2-g 属于"今天不炸、下次调色/升级必踩"。

**Round 2 最小验证清单**：
1. 真机第一件事：面板有没有出来（P1-a 的裁决）。
2. 量一次 360×640dp 上的取景区高度（P1-b）。
3. 在「技法」页按快门，看是否拍照（P1-c 的现场确认）+ 拍照后页签是否被推走（P1-d）。
4. 面板滚到底再拍一张，看是否需要 `scrollTo(0,0)`（P2-c）。
5. 长按音量下键 3 秒，看是否"拍一张 + 音量被拉低"（P2-d）。
