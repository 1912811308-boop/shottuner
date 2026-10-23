# UI / 交互优化方案 · ShotTuner v1.7

> 版本 v1.0 ｜ 作者：高见远（架构师）｜ 基于代码现状 v1.6.0（versionCode 15）
> 范围：**只做 UI/交互增量**。不改算法、不改技巧库、不改评分口径、不引新依赖、不推翻 4 页签。
> 依据编号沿用团队给的 A1–A7 / B1–B5 / C1–C3 / D1–D3。

---

## 0. 结论速览

**最大的问题不是"控件多"，而是"同一件事被说了四遍"。**

照片页上有 4 个地方在说"你现在该怎么做"：`tvTip`（参数建议）、`tvDetail`（建议细节）、`tvCompose`（构图建议）、`tvFraming`（动态插入的机位行），再加上 `llCoachSlot`（调度器提示）—— **共 5 处**。用户看不懂不是因为字小，是因为四个声音在抢同一句话。这比"一屏塞太多"更致命。

P0 只有 5 条，都在修"相机 App 不像相机 App"的部分：

| # | P0 项 | 违反依据 | 一句话 |
|---|---|---|---|
| P0-1 | 快门移出滚动面板，做成底部固定控制条 | A1 A2 | 快门是"面板内容的最后一行"，内容一超高就滚走 |
| P0-2 | 面板高度从固定 600dp 改成屏高百分比 | A7 | 600dp 在小屏占 90%、在长屏占 57%，取景被压成一条 |
| P0-3 | 音量键快门 | A6 | 全仓 `grep KeyEvent` 零命中，这条硬指标完全没做 |
| P0-4 | 强光可读性：面板不透明度 + 一处 3.5:1 文字 | D1 | 10sp 的 `tvCaps` / `tvDensityHint` 只有 3.5:1，太阳下等于没有 |
| P0-5 | 拍照反馈：删 AlertDialog、删两条 toast、改白闪 + 缩略图 + 底部卡 | A5 A4 | 拍完弹一个要按"好"的对话框，是相机 App 最不该有的东西 |

---

## 1. 现状诊断

### 1.1 逐条对照设计依据

#### A. 相机 App 硬性反模式

| 依据 | 现状 | 判定 | 证据 |
|---|---|---|---|
| **A1 快门必须圆形** | `bg_shutter.xml` = 外圈 2dp 描边 + 内圈实心圆，`ImageButton` 56×56dp | ✅ **已做对**（且已符合 B3 的"实心圆环"） | `layout:498-505` |
| A1 附带：视觉分量 | 56dp 偏小。业界相机快门 68–76dp；Material 触摸目标建议 ≥48dp，但快门是**主动作**，应显著大于次要动作 | ⚠️ 偏小 | `activity_main.xml:500` |
| **A2 快门/翻转/闪光必须常驻** | ① 快门在 `ScrollView(panel)` 内的 `pagePhoto` 末尾（`layout:491-506`）→ 是**内容流的一部分**，内容超高（小屏 / 长文案 / 未来加东西）就滚出屏幕，且位置随内容长度浮动；② **翻转镜头：不存在**（`CameraEngine:147` 写死 `DEFAULT_BACK_CAMERA`）；③ **闪光/手电：不存在**（`CameraEngine` 全仓无 `torch` / `flashMode`） | ❌ **违规** | 见上 |
| **A3 叠加层禁用渐变** | `GuideOverlayView` 全部用纯色 `Paint` + `DashPathEffect`，**零 `Shader`/`LinearGradient`** | ✅ **已做对** | `GuideOverlayView.kt:30-91` |
| A3 精神：叠加层不抢画面 | 单帧要画：三分线（8 条线 + 4 个圈）→ 俯仰刻度（1 竖轴 + 3 刻度 + 3 段文字 + 1 指针 + 1 支点）→ 水平参考（2 段）→ 实际地平线（1 条虚线）→ 主体圈（2 个圆）→ 目标圈 + 连线。**最多 6 组元素、约 25 个图元同时在取景器上** | ⚠️ 过载 | `GuideOverlayView.kt:124-168, 190-225` |
| A3 附带：线条可见性 | `gridPaint` = 白 18%（`argb(46,255,255,255)`），1px。**对着亮天空/雪地时与背景几乎同色，等于没有网格** | ❌ 违规（功能性失效） | `GuideOverlayView.kt:33-37` |
| **A4 转场 ≤400ms** | 当前**没有任何属性动画**，只有 Toast（系统时长） | ✅ 合规（靠"没有"） | — |
| **A5 不要 toast 确认"已保存"** | 拍完走 `showShotReview()` → **`AlertDialog`**（比 toast 更重，且必须点"好"才能继续拍），标题"拍摄复盘"；另在自动曝光路径额外弹一条 toast「当前是自动曝光，参数建议已复制」；失败时才该提示的 toast 也在成功路径存在 | ❌ **严重违规** | `MainActivity:1300-1306, 1142-1150` |
| **A6 音量键快门** | `grep -n "KeyEvent\|KEYCODE\|dispatchKeyEvent\|onKeyDown"` → **零命中** | ❌ **完全没做** | — |
| A7 边缘到边缘不裁预览 | `enterImmersive()` 用 `WindowCompat.setDecorFitsSystemWindows(false)` + hide systemBars，`PreviewView` 四边贴 parent | ✅ **已做对** | `MainActivity:286-292`、`layout:8-16` |
| A7 附带：手势导航条避让 | 面板 `layout_margin="12dp"` 固定边距，未用 `WindowInsets` → 系统栏瞬时滑出时会压住面板底部 | ⚠️ 轻微 | `layout:80` |

#### B. Material 3 Expressive 相机改版

| 依据 | 现状 | 判定 | 证据 |
|---|---|---|---|
| **B1 底部 control pill 收纳常用控件** | 无 pill 概念。所有操作（3 个模式按钮 + 2 个工具按钮 + 快门）平铺在面板里，与参数数值同级 | ❌ 未做 | `layout:403-506` |
| B1 附带：拇指可达 | 5 个按钮中，`btnCopy`/`btnHelp` 在滚动区中部，拇指需伸展；快门在滚动区末尾 | ⚠️ | `layout:454-488` |
| **B2 提示文字缩小并上移到顶部** | `llCoachSlot` 在**底部面板内**、页签正下方，2 行文字（`tvCoachTag`+`tvCoachAction`+`tvCoachDots` 一行 / `tvCoachReason` 一行）。提示与取景画面被面板分隔在两个区域 | ⚠️ 位置相反 | `layout:153-208` |
| B2 附带：信息密度 | 实际是「标签(10sp) + 动作(13sp 粗) + 还有N条(10sp)」+ 第二行理由(11sp)，**不是 4 个圆点**（`tvCoachDots` 是文字） | 需更正团队描述 | 同上 |
| **B3 快门改实心圆环** | 已是实心圆环 | ✅ 已做对 | `bg_shutter.xml` |
| B4 左右功能键统一圆角矩形 | 全部 `MaterialButton` + `cornerRadius=12dp`；参数数值是裸文字 | ✅ 基本合规 | `layout:409-487` |
| B5 选中用圆形高亮、非活跃用圆角矩形 | 页签选中 = `bg_chip` 圆角矩形 + 主色文字；未选中 = 无背景 + 灰字。**没有"圆形高亮"**，但页签是横向 4 等分文字块，圆形高亮不适用；意图胶囊/技法分类同理 | ⚠️ 形态不同但合理，建议**不改**（见 §3） |

#### C. vivo 模块化

| 依据 | 现状 | 判定 |
|---|---|---|
| C1 顶部快捷栏最多 4 个可固定控件 | `topBar` 是**只读信息条**（`tvScene` + `tvMode` + `tvLuma`），零可点元素 | ⚠️ 浪费了最好的拇指/视线位置 |
| C2 工具栏可长按拖动重排 | 无 | 见 §3（不做） |
| **C3 UI 预设（沉浸/专业），切换改布局** | 无。所有档位的用户看到完全一样的界面 | ❌ 未做 |

#### D. 强光/弱光可用性

| 依据 | 现状 | 判定 | 证据 |
|---|---|---|---|
| **D1 强光下高对比** | 见下方对比度实测表。**两处 10sp 文字不达 4.5:1** | ❌ | — |
| D1 附带：面板不透明度 | `bg_panel` = `#CC12161A`（**80%**）、`bg_pill` = `#B312161A`（**70%**）。户外太阳下，20–30% 的取景画面会透上来，文字对比度随背景剧烈波动 | ❌ | `drawable/bg_panel.xml`、`bg_pill.xml` |
| D2 弱光用直方图辅助 | 有 `HistogramView`，46dp 高，位于面板第三行 | ✅ 已有，但**只有 46dp**，暗场看直方图不如看数字 | `layout:274-278` |
| D3 图标尺寸可缩放、风格统一 | 图标全部用文字代替（"网格"/"已关"/"怎么拨"/"停"），**零图标**。好处是统一，坏处是占宽 | ⚠️ 一致但非最优 | — |

### 1.2 强光对比度实测（WCAG 相对亮度公式，背景取面板底色 `#12161A`）

| 前景 | 用途 | 字号 | 计算对比度 | 判定（正文需 ≥4.5:1） |
|---|---|---|---|---|
| `#F2F6F8` | 主文本 / 参数数值 | 13–23sp | **16.7:1** | ✅ |
| `#7FE3C0` | 主色（模式/页签/参考分） | 11–17sp | **12.3:1** | ✅ |
| `#9FC6E8` | 四维明细 | 11sp | **10.1:1** | ✅ |
| `#FFB74D` | 警示（倾斜/别用） | 11–12sp | **10.5:1** | ✅ |
| `#FF8A65` | URGENT 提示标签 | 10sp | **7.9:1** | ✅ |
| `#E8F0F2` | 正文（建议/说明） | 11–13sp | **15.0:1** | ✅ |
| `#8A9AA5` | 次要文本（单位/说明） | 10–11sp | **6.3:1** | ✅ |
| **`#5F6E77`** | `tvCaps`（设备档案）、`tvDensityHint`（密度说明） | **10sp** | **3.5:1** | ❌ **不合格** |

> 注意：以上是**面板完全不透明**时的理论值。实际 `bg_panel` 只有 80% 不透明，透上来的画面如果是雪地/天空（亮度 0.8+），`#8A9AA5` 的实际对比度会掉到 2:1 以下。**这是强光问题的主因，不是字色。**

### 1.3 「一屏塞太多」的量化诊断

页签 + 面板初始态（`pagePhoto` 可见）包括：

- **可点目标 9 个**：`btnGuide`、`btnMode`、`btnLock`、`btnAuto`、`btnCopy`、`btnHelp`、`btnShutter`、`llCoachSlot`（点击+长按两种手势）、4 个页签
- **纯信息块 8 个**：`tvScore`、`tvCompose`、`tvScoreDetail`、`histogram`、4 个参数值+标签、`tvTip`、`tvDetail`、`tvFraming`
- **同语义重复 5 处**：`tvTip`（参数建议 tip）/ `tvDetail`（detail）/ `tvCompose`（构图建议）/ `tvFraming`（机位建议）/ `llCoachSlot`（调度器提示）
- **数值字号 4 档**：23sp（ISO/快门/色温）× 3 + 13sp（偏差）+ 17sp（参考分）+ 11sp（四维）—— **同一条信息轴上 4 种字号，没有主次层级**

**结论**：真正的病是"5 个声音说同一件事 + 4 档字号平铺"。控件的绝对数量（9 个）对相机 App 来说并不算多，问题在于它们**没有一个共享的视觉语法**。

### 1.4 结构性问题（比视觉更值得先修）

| # | 问题 | 影响 | 证据 |
|---|---|---|---|
| S1 | 快门在 `ScrollView` 的内容流里 | 内容超高就滚走；快门位置随文案长度浮动；与"快门必须固定"直接冲突 | `layout:76-87, 491-506` |
| S2 | 面板 `constraintHeight_max=600dp` 硬编码 | 640dp 高的机型上取景只剩 ~28dp；873dp 高的机型上占 57%。**同一份布局在两端体验完全相反** | `layout:86` |
| S3 | `installFramingLine()` 用 `binding.tvCompose.parent as? LinearLayout` 再取 `.parent` 插 view | **布局一改就静默失效**（`?: return`）；若层级变化，行会插到错误容器或不插 | `MainActivity:979-998` |
| S4 | `tvMode` 是纯展示、无点击监听，却写着"教练模式 · 照拨" | 用户会去点它，没反应 | `layout:58-64` |
| S5 | 每次自动曝光下拍照都弹 toast「参数建议已复制」 | 连拍时连续弹，遮挡取景 | `MainActivity:1142-1150` |

---

## 2. 优化清单

> 格式：**改什么 / 为什么 / 落点 / 优先级**。
> P0 = 不修就不像相机 App；P1 = 明显提升；P2 = 有余力再说。

### P0-1 快门移出滚动面板，做成底部固定控制条

- **改什么**：把 `btnShutter` 从 `pagePhoto` 内（`layout:490-506`）整块移出 `ScrollView`，放进 `ConstraintLayout` 底部一个新建的 `llControlBar`：`layout_height="wrap_content"`、`bottom_toBottomOf=parent`、水平居中、内边距 10dp，快门尺寸 **56dp → 72dp**。`panel` 的 `constraintBottom` 改为 `toTopOf="@id/llControlBar"`。
- **为什么**：A1（圆形✓但尺寸偏小）+ **A2（必须常驻，不能藏）**。开启内建拍照后，快门是这个 App 的第二主功能（第一是给建议），把它放在内容流末尾意味着"文案越长、快门越远"，这是不可接受的耦合。
- **落点**：`activity_main.xml:490-506`（移出）、`activity_main.xml:84`（改约束）、新增 `llControlBar` 节点在 `previewView` 与 `panel` 之间（z 序上在 `panel` 之后）。
- **附带收益**：`pagePhoto` 少 68dp（12 margin + 56），滚动概率显著下降。

### P0-2 面板高度改百分比约束

- **改什么**：`panel` 的 `android:layout_height="wrap_content"` + `app:layout_constraintHeight_max="600dp"` → 改为 `android:layout_height="0dp"` + `app:layout_constraintHeight_percent="0.52"` + `app:layout_constraintHeight_max="560dp"` + `app:layout_constraintHeight_min="240dp"`。同时给 `panel` 加 `android:clipToPadding="false"`，让最后一行不被圆角裁掉。
- **为什么**：**A7（不要为 UI 裁掉预览）+ A3 精神**。600dp 硬编码在 640dp 屏上占 94%（取景几乎为零），在 900dp 屏上占 67%。取景器是这个产品的全部意义，它的高度必须按屏幕比例而不是绝对 dp。
- **落点**：`activity_main.xml:79, 86`。
- **验收**：360×640dp / 393×873dp / 412×915dp 三种屏宽下，取景区可见高度 ≥ 34% 屏高。

### P0-3 音量键快门

- **改什么**：在 `MainActivity` 覆写 `dispatchKeyEvent`，拦 `KEYCODE_VOLUME_DOWN`（**只拦 DOWN，不拦 VOLUME_UP**）：

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    // 只处理首次按下：长按会以 repeatCount>0 连发，那会把连拍变成"按住打空"
    if (isVolDown && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        if (uiPrefs.getBoolean(KEY_VOLUME_SHUTTER, true)) {
            onShutterClick()
            return true          // 吞掉，不再传给系统音量
        }
    }
    return super.dispatchKeyEvent(event)
}
```

- **为什么**：**A6 —— 「休闲用户不知道，进阶用户没有就不用」**。这是唯一一条"没有就丢用户"的硬指标。当前零实现。
- **落点**：新增 `MainActivity:dispatchKeyEvent`（放在 `onCreate` 之后）；`onShutterClick` 已有，直接复用（它内部有 `captureInProgress` 防重入，正是音量键连发需要的）。
- **三个必须交代的副作用**（不能只说"加个拦截"）：
  1. **保留 VOLUME_UP**：全拦会让用户在 App 内无法调音量，投诉成本高于收益。
  2. **只在 Activity 有焦点时有效**：悬浮卡阶段（我们在后台）收不到，这是系统限制，不要试图绕。
  3. **TalkBack 冲突**：无障碍模式下音量键另有语义 → 提供开关（预设在「专业档」的设置里，默认开），并在 `HELP_TEXT` 加一行说明。
- **验收**：息屏亮起后直接按音量下 → 出现快门动效 + 相册多一张；按音量上 → 系统音量面板正常出现。

### P0-4 强光可读性：面板不透明度 + 一处文字色

- **改什么**（三条，都可量化）：
  1. `bg_panel` 的 `solid` 从 `#CC12161A`（80%）→ **`#F212161A`（95%）**；`bg_pill` 从 `#B312161A`（70%）→ **`#E012161A`（88%）**。理由：面板后面是实时画面，亮度不可控；95% 基本消除串色，同时仍能看出"这是浮在画面上的一层"。
  2. `#5F6E77` → **`#7A8B96`**（对比度 3.5:1 → **5.1:1**，达 AA 正文标准）。影响 `tvCaps`、`tvDensityHint` 两处共 4 个字符串。
  3. 新增 `res/values/colors.xml`，把散落在 `activity_main.xml` 的 6 个十六进制（`#0E1113`/`#F2F6F8`/`#7FE3C0`/`#8A9AA5`/`#9FC6E8`/`#E8F0F2`/`#FFB74D`/`#5F6E77`）与 `MainActivity.priorityColor()` 里的 3 个色值统一命名（`st_bg` / `st_text` / `st_accent` / `st_text_dim` / `st_text_warm` / `st_danger` / `st_text_faint`）。**替换本身不改视觉，只是让下一次调色只改一处。**
- **为什么**：**D1**。户外直射下屏幕有效对比度会被环境光吃掉 60%+，所以目标应比 AA 高一档：正文 ≥7:1（现状已达 10–16:1，不用动）、**最小字号（10sp）≥4.5:1（现状 3.5:1 不合格）**。而"面板透光"是比字色更大的变量——80% 不透明度意味着对比度会随取景内容在 2:1 与 12:1 之间摆动，这是"同一份 UI 有时清楚有时糊"的根因。
- **落点**：`drawable/bg_panel.xml`、`drawable/bg_pill.xml`、`layout:755, 835`、新增 `values/colors.xml`、`MainActivity:956-960`。
- **不改什么**：强调色 `#7FE3C0` 保持。它在深底上 12.3:1，换色会牵动 6 个 drawable 且收益为零。

### P0-5 拍照反馈：删对话框、删 toast、改"白闪 + 缩略图 + 底部卡"

- **改什么**（三步，全部替换现有实现）：
  1. **按下瞬间**：在 `previewView` 之上加一个全屏白色 `View`（`id=tvShutterFlash`，`alpha=0`、`clickable=false`），`onShutterClick` 里跑 `alpha 0→0.55→0`，总时长 **90ms**（A4 要求 ≤400ms）。同时 `btnShutter` 做 `scale 1.0→0.92→1.0`（120ms）。
  2. **成功瞬间**：`llControlBar` 左侧加 `ivThumb`（48dp 圆角 8dp），显示刚拍的那张；从 `btnShutter` 位置 `translationX/Y` 飞到 `ivThumb` 位置，**180ms**，`Interpolator` 用 `DecelerateInterpolator`。**这就是"已保存"的唯一确认**（A5）。
  3. **复盘**：删掉 `showShotReview()` 的 `AlertDialog`，改为在 `panel` 顶部插入一张 `llReviewCard`（与 P1-1 腾出来的位置复用）：一行"这张参考分 82 · 良好"、一行四维、一行一条建议、右侧一个"看技法 ›"。**5 秒后自动收起**（点一下保持常驻）。保存失败时才 toast。
  4. 删掉 `capturePhoto()` 里自动曝光路径的 toast（`MainActivity:1142-1150`），把"参数已复制"降级为 `llReviewCard` 里的一行灰字。
- **为什么**：**A5（缩略图飞入角落本身就是确认）+ A4（≤400ms）+ A3 精神**。`AlertDialog` 是模态阻断：用户拍完要按"好"才能继续取景，而在取景场景里"打断"的代价是错过画面。现在的实现还有一个隐患——`AlertDialog` 弹出期间 `onFrame` 仍在跑、`render()` 仍在 `setText`，对话框背后的画面在变，用户对着一个"活着的"背景读分数。
- **落点**：`MainActivity:1300-1306`（改写 `showShotReview`）、`MainActivity:1154-1175`（成功回调里换成缩略图+卡片）、`activity_main.xml` 新增 `tvShutterFlash` / `ivThumb` / `llReviewCard`。
- **验收**：连拍 3 张，全程不出现任何对话框、不出现"已保存"toast；每次按快门到缩略图更新 ≤400ms。

### P1-1 教练提示槽上移到取景区（含"5 个声音"合并）

- **改什么**：
  1. `llCoachSlot` 从 `panel` 内（`layout:153-208`）移出到 `ConstraintLayout`，约束为 `top_toBottomOf=topBar`、水平居中、`width=0dp` + `marginHorizontal=24dp`，背景 `bg_pill`（88% 不透明）。文字层级压缩为 **2 行**：`tvCoachAction`（14sp 粗，最多 2 行）+ `tvCoachReason`（11sp 暗色，最多 1 行，`ellipsize=end`）；`tvCoachTag` 改成 `tvCoachAction` 前面的**小色点**（8dp 圆，按优先级取色）；`tvCoachDots` 删除（"还有 N 条"对用户无意义，他只关心当前这条）。
  2. **合并同语义通道（关键）**：`tvTip` + `tvDetail` + `tvFraming` 三行**合一**为 panel 内的一个 `tvAdvice`（`tvTip` 粗体一行 + `tvDetail` 灰字一行，`tvFraming` 内容并入 `tvDetail` 的第二句）。判据：三者在同一帧里说的都是"接下来手怎么动"，合并后信息量不减、重复度归零。
  3. `tvCompose`（构图建议）**保留在 panel 内不动**——它讲的是"画面结构"，与"手怎么动"是两个层级，强行合并会丢信息。
- **为什么**：**B2（提示上移到顶部）+ 本方案 §1.3 的核心诊断**。提示紧贴取景画面，用户的眼睛在"画面 → 提示"之间移动距离从 ~400dp 降到 ~40dp；同时给 panel 腾出 ~48dp，正好接 P0-5 的复盘卡。
- **落点**：`activity_main.xml:153-208`（移出并重写）、`MainActivity:renderCoachSlot()`（去掉 `tvCoachDots`）、`MainActivity:updateFramingLine()`（改为写 `tvDetail` 的第二句，删掉 `installFramingLine` 的整个动态插入逻辑 → 同时消灭结构问题 S3）。
- **风险与退路**：提示条会遮住取景画面上部 ~13%。若真机测试发现遮挡主体（尤其竖构图人像），退化为"留在 panel 内但置顶到页签之上"——位置变化一行 `constraint`，不影响其它逻辑。

### P1-2 「沉浸 / 专业」两档预设

- **改什么**：`tvMode` 加上点击监听，点击循环切换两档；档位存 `SharedPreferences`（`ui_preset`，默认 `immersive`）：

| 档位 | 显示 | 隐藏 |
|---|---|---|
| **沉浸（默认）** | `tvScene`、教练提示条、3 个大参数（ISO/快门/色温）、`tvAdvice`、`btnShutter`、`ivThumb`、4 个页签（收成一行） | `tvScoreDetail`（四维）、`histogram`、`tvCompose`、`tvFraming`、`btnLock`/`btnAuto`/`btnCopy`/`btnHelp`、俯仰刻度、主体圈/目标圈 |
| **专业** | 全开 | — |

- **为什么**：**C3（预设切换的是布局，不是主题）**。这是解决"一屏塞太多"的最低成本路径：不是删功能，而是**让默认档只留 6 个元素**，想要全量的用户一键展开。也顺带解决 S4（`tvMode` 现在点了没反应，用户会当成 bug）。
- **落点**：`MainActivity:updateModeUi()`（文案改成 `沉浸 · 点切换` / `专业 · 点切换`）、新增 `applyUiPreset(preset)` 统一控制 `visibility`；`GuideOverlayView` 新增 `var showPitchGauge: Boolean`（默认 true，沉浸档设 false）。
- **不改什么**：4 个页签的**结构与文案完全不动**，只在其容器上加 `visibility` 由档位控制，避免推翻信息架构。

### P1-3 参数区视觉层级

- **改什么**：
  1. 三格主参数（`tvIso` / `tvShutter` / `tvKelvin`）字号统一 23sp 保持，但**快门加到 26sp**、`tvIso`/`tvKelvin` 降到 20sp。理由：三者不是平权的——快门决定"糊不糊"，是唯一不可逆的量（ISO 高了能减噪、色温错了能后期），所以它该最大。
  2. 四格之间加 1dp `#1FFFFFFF` 竖分隔线（用 `<View>` 三条）。
  3. `tvEv`（偏差）从 13sp 灰字改为 **11sp + 前缀符号色**：`+` 用 `#FFB74D`、`−` 用 `#9FC6E8`、`±` 用 `#8A9AA5`（现状已经区分但只靠文字，加色更快读）。
  4. **数值变化高亮**：`render()` 里比较新旧值，变的那一格做 200ms `alpha 0.6→1.0` 闪一下。这是"哪个数变了"最省事的指示器，比箭头便宜。
- **为什么**：**B1（拇指区/层级）+ §1.3（4 档字号平铺）**。参数是这个页的核心输出，但现在 4 个数字视觉权重相同，用户每次都要重新扫一遍才知道看哪个。变化高亮把"读数"变成"看变化"，认知成本直接减半。
- **落点**：`layout:293-381`（字号+分隔线）、`MainActivity:711-716`（记录旧值 + 触发高亮）。
- **注意**：高亮动画 200ms，**不可用 `ValueAnimator` 每帧 setText**（会触发 measure/layout 抖动）；只对 `alpha` 做动画。

### P1-4 按钮收敛：常驻 1 个 + 「更多」折叠

- **改什么**：删掉 `btnLock`/`btnAuto`/`btnCopy`/`btnHelp` 的常驻行（`layout:403-488`），改为：
  - 常驻：`btnMode`（"本机写入"，主按钮，宽 50%）+ `btnMore`（"更多 ⌄"，次按钮，宽 50%）；
  - `btnMore` 点击展开一行 4 个次按钮（`btnLock`/`btnAuto`/`btnCopy`/`btnHelp`），再点收起；展开状态不持久化（每次进页面都是收起）。
- **为什么**：**B1（常用进 pill、罕用收起）**。"复制参数"和"怎么拨"是**首次使用 learn-once** 动作，不是每次拍摄都要点；把它们和"本机写入"（每次调参都可能点）放在同一行，等于让 40% 的常用区被两个低频按钮占据。
- **落点**：`layout:403-488`（重构为两行，第二行 `visibility=gone`）。
- **不改什么**：5 个按钮的**文案与行为完全不变**，只改显隐，避免牵动 `onCreate` 里的 5 处 `setOnClickListener`。

### P1-5 网格线在亮背景可见

- **改什么**：`gridPaint` 从「白 18% 单线」改为**双描边**：先画 `#66000000`（黑 40%）宽 3dp，再画 `#59FFFFFF`（白 35%）宽 1dp。交点圆同样处理。俯仰刻度线 `pitchScalePaint` 从 `argb(180,127,227,192)` 降到 `argb(150,127,227,192)` 并同样加黑色底描边。
- **为什么**：**A3 附带 + D1**。白 18% 叠在亮度 0.9 的天空上是 (0.9*0.82+0.18) = 0.918 —— **和背景差 0.018，等于没有线**。这是功能性失效，不是审美问题。双描边（黑底 + 白面）是相机网格的标准做法，因为它对**任何**背景都保证 ±40% 的局部反差。
- **落点**：`GuideOverlayView.kt:33-43`（新增 `gridShadowPaint`）、`170-183`（`drawThirds` 里先画阴影层）。
- **成本**：+8 行，单帧多 12 条 `drawLine`，可忽略。

### P2-1 悬浮卡与主界面共用"提示"语义

- **改什么**：`CoachOverlay` 现在 6 行（场景/参数/录像/建议4行/构图/参考分），在系统相机上方会挡掉画面 1/4。改为：**参数 1 行 + 建议 1 行（最多 2 行）+ 参考分角标**，构图建议删掉（系统相机里用户看不到我们的引导线，构图文字无参照）。
- **为什么**：A3 精神（浮层不抢画面）。悬浮卡的使用场景是"用户在用别人的相机"，那时候他的注意力 90% 在取景上。
- **落点**：`CoachOverlay.kt:51-118`（减 2 个 TextView）、`render()` 签名同步删 2 个参数。
- **优先级理由**：README 已注明悬浮卡"未在真机验证"，在验证前做视觉优化是浪费。

### P2-2 布局脆弱点清理

- **改什么**：① 删掉 `installFramingLine()` 的动态插入（P1-1 已消化）；② `panel` 加 `WindowInsetsCompat` 的 bottom padding 响应（系统栏瞬时滑出不压面板）；③ 把 `MainActivity` 里 9 处 `Color.parseColor("#...")` 换成 `ContextCompat.getColor(this, R.color.st_*)`。
- **为什么**：S3（布局一改就静默失效）+ A7 附带 + 可维护性。`installFramingLine` 这种"靠父容器类型猜位置"的写法，在下一次布局调整时会导致**功能静默消失而不是报错**，这是最难查的一类 bug。

---

## 3. 不做清单

| 提议 | 出处 | 为什么不做 |
|---|---|---|
| 工具栏长按拖动重排 | C2 | 成本高（拖拽手势 + 顺序持久化 + 越界处理 + 空白位占位），收益低：我们常驻控件收敛后只有 2 个（`btnMode` / `btnMore`），2 个元素谈不上"重排"。等控件 ≥5 个再考虑。 |
| 底部 pill 里放「反馈 / 相册」 | B1 | 我们没有反馈通路（无账号、无服务端），相册是**系统相册**（`MediaStore`），做成按钮只能"跳转系统相册"，是个假功能。`ivThumb` 点击跳系统相册已足够。 |
| 非活跃项圆形高亮（B5） | B5 | 页签是 4 等分文字块，圆形容器会让 36dp 的行高撑到 44dp，反而挤掉取景。胶囊状的 `bg_chip` 在这里语义正确。**不为了对齐设计语言而牺牲密度。** |
| Compose 重写 / 换主题引擎 | — | 硬约束禁止；且现有 viewBinding + 手写 `LinearLayout` 的卡片渲染在 83 条技法列表上是够用的（没有复杂状态）。 |
| 手势缩放（pinch-to-zoom） | — | 与产品定位冲突：这个 App 的立场是"教你拨原生相机"，内建 pinch 会让人误以为它是相机 App，进而期待完整相机功能（滤镜/美颜/相册管理），期待落空比没有更糟。 |
| 图标库（Material Icons / 自绘 SVG） | D3 | 现在全文字，风格统一、无需资源、无 license 风险。换成图标会引入 8–12 个 `VectorDrawable`，且中文单字按钮（"网格"/"停"）在 11sp 下比图标更明确。 |
| 主题色可定制 | — | 与"教练"定位无关，且会把 6 个 drawable 变成运行时着色，得不偿失。 |
| 关掉/重建 `GuideOverlayView` 以省电 | — | 它只在 `renderComposition()` 400ms 节流里 `invalidate()`，成本可忽略；停掉反而要处理状态同步。 |
| 前摄 + 闪光（A2 这两条） | A2 | **不是不做，是不在"纯 UI"范围**：`CameraEngine` 没有 `lensFacing` 参数、没有 `flashMode`，要加得改 `CameraEngine:147` 的 `CameraSelector` 与 `ImageCapture` 构建。**建议单独开一个 v1.7 迭代做**（前摄约 40 行 + 镜像处理；闪光约 15 行），它同时能解锁 5 条自拍技法（当前只能浏览）。这里是**范围声明**，不是拒绝。 |

---

## 4. 改动影响面

### 4.1 会动的文件

| 文件 | 改动性质 | 风险 |
|---|---|---|
| `res/layout/activity_main.xml` | **大改**：快门移出、新增 `llControlBar`/`tvShutterFlash`/`ivThumb`/`llReviewCard`/`btnMore`、`llCoachSlot` 移位、panel 约束改、字号与色值替换 | 中（纯布局，逐个 id 核对即可） |
| `res/drawable/bg_panel.xml`、`bg_pill.xml` | 改 `solid` 透明度 | 极低 |
| `res/drawable/bg_shutter.xml` | 尺寸由 View 控制，不需改；若要"按下态"再加 `state_pressed` | 极低 |
| `res/values/colors.xml` | **新增**（不是新依赖，是资源文件） | 无 |
| `MainActivity.kt` | 中改：`dispatchKeyEvent` 新增、`showShotReview` 重写、`capturePhoto` 成功分支改、`applyUiPreset` 新增、`renderCoachSlot` 去 dots、`installFramingLine` 删除、参数高亮 | 中 |
| `ui/GuideOverlayView.kt` | 小改：加 `gridShadowPaint`、加 `var showPitchGauge` | 低 |
| `screen/CoachOverlay.kt` | 小改（P2）：减 2 个 TextView，`render()` 签名变 | 低（该模块未真机验证） |
| `themes.xml` | 不改 | — |

### 4.2 测试影响核对

现有 **8 个 JUnit 测试全部是纯 JVM**（`app/src/test`，无 `androidTest`，无 Robolectric）：

| 测试 | 是否受影响 | 理由 |
|---|---|---|
| `PhotographyBookTest` | ❌ 不受影响 | 只读 `PhotographyBook.all` 的字段长度，不涉 UI |
| `TechniqueBookV15Test` | ❌ 不受影响 | 同上 |
| `TechniqueMatcherTest` | ❌ 不受影响 | 纯匹配逻辑 |
| `PromptEngineTest` | ❌ 不受影响 | 只吃 `PromptSignals` |
| `PromptSchedulerTest` | ❌ 不受影响 | 时钟靠 `now: Long` 注入，与 UI 无关 |
| `ScoreBoardTest` | ❌ 不受影响 | 纯计算 |
| `LevelMathTest` | ❌ 不受影响 | 纯计算 |
| `FramingCoachTest` | ❌ 不受影响 | 纯规则 |

**但有两处"看不见的耦合"必须守住**：

1. `renderCoachSlot()` 会读 `PromptDecision.prompt.candidate.action` / `.reason` / `.priority`。P1-1 删 `tvCoachDots` 时**不要动 `PromptCandidate` 的字段**（那是 `PromptEngineTest` 断言的对象）。UI 只做"读"，不做"改"。
2. `MainActivity.priorityColor()` 是私有函数，P0-4 换成 `R.color.*` 时，**色值必须一一对应**（`URGENT #FF8A65` / `CONTEXT #7FE3C0` / `GUIDE #9FC6E8`），否则"硬伤用橙色"这个语义会静默改变，而没有任何测试会报错。

### 4.3 需要真机验证的三件事（无法靠代码推断）

1. **P1-1 提示条是否遮挡主体**：竖构图人像时上部 13% 通常正是头顶/天空，风险中等。用"退路方案"一行改回。
2. **P0-2 百分比高度在小屏的实际观感**：`constraintHeight_percent=0.52` 在 640dp 屏上是 333dp，可能装不下"专业档"的全量内容 → 需要 `min=240dp` + 滚动兜底。
3. **P0-3 音量键在某些 ROM 上被系统相机/游戏助手抢**：拦截在 `dispatchKeyEvent`，有焦点时一定先到 Activity，理论安全；但小米/荣耀的"音量键功能自定义"可能在上游拦截。

---

## 5. 实施顺序

> 每条粒度到"改哪个文件的哪个控件"。**顺序即依赖顺序**，前一条不做的话后一条会返工。

### 第 1 批（P0，一次改完，约一次构建可验收）

| 序 | 文件:控件 | 动作 |
|---|---|---|
| 1 | `values/colors.xml` | 新建，定义 8 个 `st_*` 色值（含 `st_text_faint = #7A8B96`） |
| 2 | `drawable/bg_panel.xml` / `bg_pill.xml` | `solid` → `#F212161A` / `#E012161A` |
| 3 | `activity_main.xml:498-506` | 把 `btnShutter` 整块从 `pagePhoto` **剪切**到新节点 `llControlBar`（放在 `panel` 之后，保证 z 序在上）；尺寸 56→72dp |
| 4 | `activity_main.xml:84, 86` | `panel` 的 `constraintBottom` → `toTopOf=llControlBar`；`layout_height` → `0dp`；加 `constraintHeight_percent=0.52` / `max=560dp` / `min=240dp` |
| 5 | `activity_main.xml`（新增） | `tvShutterFlash`（全屏白 View，`alpha=0`，`clickable=false`）、`ivThumb`（48dp）、`llReviewCard`（3 行 + "看技法"） |
| 6 | `activity_main.xml:755, 835` | 两处 `#5F6E77` → `@color/st_text_faint`；其余 6 处十六进制 → `@color/st_*` |
| 7 | `MainActivity.kt`（`onCreate` 后） | 新增 `dispatchKeyEvent`（P0-3 代码），`uiPrefs` 用现有 `PREFS_NAME` |
| 8 | `MainActivity.kt:1154-1175` | 成功回调：改为 `playShutterFlash()` + `flyThumbTo(uri)` + `showReviewCard(...)`，删两处 toast |
| 9 | `MainActivity.kt:1300-1306` | `showShotReview()` 整段删除，改为 `showReviewCard(statusLine)`（写 `llReviewCard` 三个 TextView，5s 后 `postDelayed` 收起） |

**第 1 批验收**：连拍 3 张无对话框、无"已保存"toast；音量下键能拍；360dp 与 412dp 两档屏宽下取景区 ≥34%；面板内 10sp 文字在户外可读。

### 第 2 批（P1，可分两次构建）

| 序 | 文件:控件 | 动作 |
|---|---|---|
| 10 | `activity_main.xml:153-208` | `llCoachSlot` 移到 `topBar` 之下（`ConstraintLayout` 直属）；`tvCoachTag` 换成 8dp 色点 View；删 `tvCoachDots` |
| 11 | `activity_main.xml:384-401` + `MainActivity:979-998` | 删 `installFramingLine()`；`tvFraming` 内容并入 `tvDetail` 第二句 |
| 12 | `MainActivity:929-954` | `renderCoachSlot` 去掉 `tvCoachDots` 赋值，加色点着色 |
| 13 | `MainActivity:updateModeUi` + 新增 `applyUiPreset` | 沉浸/专业两档；`tvMode` 加点击 |
| 14 | `ui/GuideOverlayView.kt` | 新增 `var showPitchGauge`（默认 true）+ `gridShadowPaint` 双描边 |
| 15 | `activity_main.xml:293-381` + `MainActivity:711-716` | 快门 26sp / 另两个 20sp；加 3 条 1dp 竖分隔；`tvEv` 符号着色；数值变化 200ms alpha 闪 |
| 16 | `activity_main.xml:403-488` | 5 按钮 → `btnMode` + `btnMore` 两行，次按钮行默认 gone |

**第 2 批验收**：沉浸档首屏只有 6 个元素；切档不丢当前取景状态；网格线在对着白色墙面时仍可见；改一个参数只有那一格闪。

### 第 3 批（P2，可延后）

| 序 | 文件:控件 | 动作 |
|---|---|---|
| 17 | `screen/CoachOverlay.kt:51-118` | 6 行 → 3 行，删构图行 |
| 18 | `activity_main.xml:76-87` | `panel` 加 `WindowInsetsCompat` bottom padding 响应 |
| 19 | `MainActivity` 9 处 `Color.parseColor` | 换 `ContextCompat.getColor(this, R.color.st_*)` |

### 与后续版本的衔接

- **v1.7.1（建议）**：`CameraEngine` 加 `lensFacing` + `flashMode` → 补齐 A2 的"翻转/闪光"，同时把 5 条自拍技法从"仅浏览"提升为可推送。这是本轮唯一被"范围"挡住的 A2 缺口。
- **v1.7.2（建议）**：悬浮卡真机验证后再做 P2-1。
