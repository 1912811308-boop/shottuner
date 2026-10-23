# 光影调参 · iOS 版增量 PRD

> 版本 v1.0 ｜ 作者：许清楚（产品）｜ 目标版本：iOS v1.0.0（对齐 Android v3.1.0）
> 上游依据：`docs/GROWTH-PRD.md`（爆款化方案，产品基调）、`docs/RELEASE-PRD.md`（合规与隐私口径模板）
> 适用范围：`D:/AI学习/Claw/shottuner` 的 iOS 端（KMP 共享内核 + SwiftUI 表现层）
> **本文档只做产品定义，不含任何代码改动。**

---

## 0. 一页纸结论

iOS 版不是「Android 版换个壳」，而是**同一个产品内核在 iOS 平台上的重做一遍表现层与平台适配**。内核（83 条技法、四维评分、任务引擎、提示调度）全部复用，端侧 AI 与相机这两块按 iOS 原生能力重写。

三句话说明本次 PRD 的核心判断：

1. **可复用的部分比预想的多。** 实测 28 个源文件、8,075 行是零 Android 依赖的纯 Kotlin（评分、两大技法库、提示引擎、任务引擎、图鉴、分数历史），具备直接进 KMP `commonMain` 的条件。但有 **1 个文件带 JVM 专属 API**（`TechniqueDex.kt` 用 `java.text.SimpleDateFormat`），需要一次改造。
2. **最大的体验缺口（悬浮窗）不是「换个方案」，而是「换一种满足方式」。** iOS 禁止跨 App 叠加与截屏他人界面，这是系统级红线，无解。但这个功能真正要满足的需求是「拍照时视线不在手机屏幕上，也能拿到建议」——iOS 的等价解不是叠加，而是**把教练搬到第二块屏幕（Apple Watch）和系统层（Live Activity / 锁屏 / 控制中心 / 动作按钮）**，并用 **Share Extension 做「系统相机拍完一键复盘」**（这一条比 Android 的截屏分析更准：分析的是原图，不是屏幕像素）。
3. **免费 Apple ID 侧载会卡住一个东西：App Group。** 而 App Group 是 Widget 读取 App 数据的常规通道。所以 **Live Activity 设计成不依赖 App Group**（数据走 ActivityKit 的 ContentState，由系统投递），**Widget 设计成分层降级**。这不是保守，是为了保证「第一批一定能装上跑起来」。

**本次迭代的产品基调沿用 GROWTH-PRD 的三条铁律**（不改）：
- 零网络、照片不上传 —— iOS 版换一套证明方式（见 §7）。
- 只说「参考分」，不说「正确/错误」「专业评测」；任一维度未测时总分显示「参考分 --」。
- 文案用「照拨」「建议值」。

---

## 1. iOS 版定位与差异陈述

### 1.1 一句话定位

**光影调参（iOS）—— 手机里的摄影陪练。拍之前就知道好不好 · 离线实时构图教练，照片不上传。**

主标题/副标题与 Android 版完全一致，不做端差异（品牌一致性 > 端上文案微调）。

### 1.2 与 Android 版的关系

| 维度 | 结论 |
|---|---|
| 产品身份 | **同一产品的两个端**，不是互补的两个产品。用户认知、数据口径、技法内容、评分算法完全一致 |
| 内核复用 | 评分内核（`ScoreBoard`）、技法库（83 条）、提示引擎、任务引擎、图鉴、分数历史 → 进 KMP `commonMain`，**单一数据源** |
| 表现层 | 100% 重写。Android 用 XML + ViewBinding + 单 Activity 多页签；iOS 用 SwiftUI + TabView + NavigationStack |
| 平台适配层 | 相机（CameraX→AVFoundation）、端侧 AI（ML Kit Android→ML Kit iOS / Vision）、UI 组件，全部按 iOS 原生重做 |
| 数据 | 两端**不互通**（零网络，无账号无同步）。图鉴进度、分数历史各自独立 |
| 版本 | 营销版本号与 Android 同步（v3.1.0），构建号各自独立单调递增 |

### 1.3 iOS 用户画像与 Android 的差异

| 差异点 | Android | iOS | 对产品的影响 |
|---|---|---|---|
| 设备性能 | 中端机占比高，SoC 参差 | 机型普遍更强（A 系列以上），芯片与 ISP 稳定 | 端侧分析（ML/Vision）可以跑更重的模型；评分计算不是瓶颈 |
| 相机硬件 | 传感器小、ISP 厂商杂 | 传感器大、OIS 普遍、ISP 强、计算摄影介入深 | **评分基线必须重标定**（见 §5 P0-I11），照搬 Android 阈值会系统性偏科 |
| 取景习惯 | 大量用户用第三方相机 App | 大量用户用系统相机 | iOS 必须提供「拍完一键复盘」路径（Share Extension），否则接不住这批人 |
| 原生感期待 | 弱，能用就行 | 强，Material 风格会被骂 | 导航、动效、手势全面按 iOS HIG；不做「仿 Android」 |
| 隐私敏感度 | 中 | 高（且 iOS 用户更熟悉「照片不上传」这类承诺） | 零网络是**卖点**，不只是合规底线；但 iOS 不能像 Android 那样靠「移除权限」自证，需要另一套证明（§7） |
| 付费意愿 | 弱 | 相对强 | 但本产品不做付费（沿用 GROWTH-PRD 反向清单），本轮不引入任何 IAP |

### 1.4 差异化补一句（竞品视角）

GROWTH-PRD §1.1 已确认：**「实时构图教练」这个品类在 iOS 上是有玩家的（Camera51 仅 iOS）**，但所有同类（Camera51 / 绘心 / 浅影）都依赖云端 AI。Android 端的这个位置是空的，iOS 端不是。

所以 iOS 版的机会点不在「功能对齐 Camera51」，而在**「离线 + 零延迟 + 不上传 + 有完整可收集的技法图鉴」**这组 Android 版已经建立的差异化。iOS 版要守住的正是这一点：**一旦为了追 iOS 原生体验引入任何联网能力，产品的核心优势就归零。**

---

## 2. 平台能力对照表（核心表）

> 判定口径：
> - **原生等价** —— iOS 有同类系统能力，行为可对齐
> - **iOS 替代** —— iOS 无同类能力，但有 iOS 原生能力可满足同一用户需求（本表需写明用什么）
> - **仅 iOS 独有新增** —— Android 没有、iOS 有
> - **不移植** —— 明确不做，写明理由
>
> 判定依据为 Android v3.1.0 现有 11 项功能的代码走查 + iOS 平台能力文档。

| # | Android 功能 | iOS 可行性 | iOS 方案 | 判定 |
|---|---|---|---|---|
| 1 | **照片页**：CameraX 实时取景 + 四维参考分（水平 25 / 曝光 30 / 构图 25 / 清晰 20）+ 参数建议（ISO/快门/WB/EV）+ 构图引导线 + 实时直方图 | 可行 | `AVCaptureSession`（`.videoDataOutput` 取帧分析 + `.photoOutput` 拍照）+ `AVCaptureVideoPreviewLayer` 预览；四维评分复用 `commonMain` 的 `ScoreBoard`；引导线用 SwiftUI `Canvas` 覆盖层；直方图用 `Canvas` 重绘（Android `HistogramView` 的 SwiftUI 对应物）；水平改用 CoreMotion 重力向量（**iOS 加速度计必有，等级判定恒可测**，比 Android 的「重力传感器兜底」更稳） | **原生等价** |
| 2 | **首屏任务卡（G1）**：取景中央浮出可完成任务 + 引导环灰→绿 + 到位震动 + 成绩卡；不按快门也能得分 | 可行 | 任务引擎（`MissionEngine`/`TaskPicker`）复用；引导环用 SwiftUI 覆盖层（**注意：任务文字卡不放在取景正中，改放顶部安全区下方**，因为 iOS 灵动岛/刘海占据顶部，且中央要给主体与目标交点留位）；到位震动用 `UIImpactFeedbackGenerator`（Android 是 `VIBRATE` 权限，iOS 无需权限） | **原生等价**（布局有平台化调整） |
| 3 | **视频页**：8 条运镜技法建议 | 可行 | 纯建议页，**不做真实录制**（Android 版也没有录制 —— 全仓无 `MediaRecorder` / `CameraX.VideoCapture`，见 §附录B）。运镜建议复用 `commonMain` 的 `VideoMovePlanner` / `VideoRecommender`（8 个意图场景），iOS 用 `Picker` + `List` 呈现。**因此 iOS 版不需要麦克风权限** | **原生等价** |
| 4 | **功能页**：场景特化建议 + 设备能力探测 | 可行 | 场景建议复用 `PromptEngine` + `FeatureAdvisor`；设备探测重写：`AVCaptureDevice.formats` / `activeFormat` / `videoSupportedFrameRateRanges` / ISO 与快门范围 / 低光增强支持度 → 映射到既有 `DeviceProfile` 结构。iOS 侧**取消 `Brand.kt` 的 OEM 差异逻辑**（iOS 设备统一，无分品牌适配必要） | **原生等价** |
| 5 | **技法页**：83 条技法 + 15 分类筛选 + 图鉴进度「已解锁 N / 83」 | 可行 | 83 条内容与 15 个分类（`TechCategory` 枚举 15 值）复用 `commonMain`；UI 用 `NavigationStack` + `List` + `Picker` 分类筛选；图鉴进度沿用「照做 + 达标」双条件解锁 | **原生等价** |
| 6 | **内建拍照**：CameraX ImageCapture → 写系统相册 → 缩略图飞入 → 复盘卡 → 分享（`ACTION_SEND`） | 可行 | `AVCapturePhotoOutput` 拍照 → `PHPhotoLibrary.performChanges` + `PHAssetCreationRequest` 写入（「仅添加」权限即可，不需要读相册）→ 缩略图动画（SwiftUI `matchedGeometryEffect`）→ 复盘卡 → `UIActivityViewController` 系统分享 | **原生等价** |
| 7 | **成绩水印**：成片右下角烧「光影调参 · 参考分 78 · 良好」，默认关闭，设置可开 | 可行 | `Core Graphics` 绘制 + `CGImageDestination` 编码；位置按图片短边等比缩放；默认关闭、设置可开（**与 Android 现状一致**：`KEY_WATERMARK` 默认 `false`）。**未测全时烧「参考分 --」，不烧假数字** | **原生等价** |
| 8 | **分数历史（G4）**：每次拍照落盘四维分（`filesDir/score_history.json`，环形 500 条），供成长曲线与首末对比卡 | 可行 | `ScoreHistory` / `MAX_RECORDS = 500` 逻辑在 `commonMain`（纯 Kotlin 手写 JSON，无第三方序列化依赖）；落盘改 `Application Support` 目录（不参与 iCloud 备份，见 §7）；曲线用 SwiftUI `Path` 自绘，不引图表库 | **原生等价** |
| 9 | **悬浮窗教练（招牌功能）**：覆盖在系统相机上的浮窗 + MediaProjection 截屏分析 + 前台服务 + 通知栏停止 + 快捷设置磁贴 | **不可行（系统级禁止）** | **拆成 4 条 iOS 原生替代路径**（详见 §3、§4、§5 J3/J4/J5）：<br>① **Live Activity / 灵动岛 / 锁屏** —— 常驻可见的教练条（数据走 ActivityKit ContentState，**不依赖 App Group**）；<br>② **Share Extension** —— 用系统相机拍完，通过分享菜单送进「光影调参」做**原图复盘**，评分精度高于 Android 的截屏像素分析；<br>③ **Apple Watch App** —— 把参考分搬到手腕，等价解决「视线不在手机屏上」；<br>④ **Action Button / 控制中心 / Spotlight / App Intents** —— 等价替代「快捷设置磁贴」的一键启动 | **iOS 替代**（4 条路径） |

#### 9 号拆分：Android 侧三项子能力逐条处置

Android 版「悬浮窗教练」实际由三件独立的事组成，它们的 iOS 可行性**完全不同**，不能打包判定：

| Android 子能力 | iOS 可行性 | 处置 | 判定 |
|---|---|---|---|
| **跨 App 悬浮窗**（`TYPE_APPLICATION_OVERLAY` 盖在系统相机上） | **系统级禁止**，无任何对等 API | 拆成 4 条替代路径（见上表 9 号）。**核心洞察**：这个功能真正要满足的是「拍照时视线不在手机屏幕上也能拿到建议」，iOS 的等价解不是「叠加」，而是**换承载面** —— 搬到灵动岛/锁屏（系统层）、Apple Watch（第二块屏）、Share Extension（拍后原图复盘） | **iOS 替代** |
| **MediaProjection 截屏他人界面** | **系统级禁止**，无任何对等 API | **不移植「实时截屏分析他人 App」**。理由：iOS 不提供读取其他 App 画面的任何途径，绕过的唯一途径是越狱/私有 API，与零网络承诺和合规基线冲突。**替代**：Share Extension 分析用户**主动分享给我们的原图** —— 分析对象是真实原图而非屏幕像素，**精度反而高于 Android 版**（屏幕像素受缩放、压缩、系统 UI 遮挡影响） | **iOS 替代**（语义转换：实时 → 拍后） |
| **前台服务 + 通知栏「停止」按钮**（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` / `POST_NOTIFICATIONS`） | iOS 无等价物（后台持续跑摄像头需特殊 entitlement） | **不移植**。iOS 版取景会话只在 App 前台运行，进后台即冻结（Live Activity 保留最后状态）。用户无需「停止」按钮 —— App 一旦离开前台，摄像头本就停了 | **不移植**（理由：iOS 后台模型下无「常驻后台采集」这个状态，不需要停止开关） |
| **快捷设置磁贴**（`TileService`） | **系统级禁止**，无对等 API | **iOS 替代**：Action Button（iOS 17+ / iPhone 15 Pro+）+ 控制中心控件（iOS 18+）+ Spotlight + App Intents，一键直达取景页。**这四个都需要 App Extension**（控制中心控件与 Spotlight 索引），受 R1 风险影响 | **iOS 替代** |
| 10 | **隐私门**：首次启动隐私同意页（不同意进受限模式）；关于页内嵌完整隐私政策 | 可行 | iOS 首次启动展示同一份隐私政策全文（本地资源，非网页）；「同意 / 不同意」双按钮，不同意进受限模式（不申请相机权限、不启动会话）；关于页内嵌全文 + 「个人信息收集清单 / 第三方共享清单」双清单（沿用 RELEASE-PRD §6 风险 6 的口径） | **原生等价** |
| 11 | **水平传感器**：重力传感器兜底水平判定（无地平线时） | 可行（更好） | `CoreMotion`（`CMMotionManager.deviceMotion` / 加速度计）算重力分量 → 复用 `commonMain` 的 `LevelMath`（同一套角度→扣分曲线）。**iOS 设备必有加速度计，等级维度恒可测**，不再需要「无地平线则记满分并标未测」的兜底 | **原生等价（能力更强）** |

### 2.1 对照表之外：仅 iOS 独有新增

| # | 新增 | 依据 | 价值 |
|---|---|---|---|
| N1 | **Apple Watch App**：手腕显示参考分 + 当前任务 + 参数建议 | iPhone 独占 | 直接补上 Android 悬浮窗的核心体验缺口（拍照时视线离开手机屏幕） |
| N2 | **Action Button 一键进入取景**（iPhone 15 Pro+，iOS 17+） | iOS 17 起 | 「拍照前先问教练」的动作成本从「打开 App」降到「按一下」 |
| N3 | **控制中心控件**（iOS 18+） | iOS 18 起 | 等价 Android 快捷设置磁贴 |
| N4 | **Spotlight / App Intents 索引 83 条技法** | iOS 原生 | 零网络的「内容检索」能力：用户搜「逆光 测光」直接进技法详情 |
| N5 | **锁屏小组件 + 主屏小组件 + 灵动岛三处回访位** | WidgetKit | GROWTH-PRD 说的「回来的理由」，iOS 的载体比 Android 桌面更自然 |
| N6 | **系统相机拍完一键复盘**（Share Extension） | Share Extension | 接住 iOS 用户「用系统相机」的主力习惯，且**分析原图更准** |

---

## 3. Live Activity 详细设计

> 定位：**「教练随身条」——把参考分与当前任务从 App 界面里搬到系统层，让用户在拍照时视线不用回到 App。**
> 它是 Android 悬浮窗在 iOS 上最接近的等价物，但**不是**实时跨 App 分析（iOS 相机会话进入后台即被系统挂起，做不到）。这条限制必须写进产品预期，不做虚假承诺。

### 3.1 何时启动 / 何时结束

| 事件 | 行为 |
|---|---|
| 用户首次进入拍摄页且权限就绪 | **自动启动**一个 Live Activity（`pushType: nil`，纯本地） |
| 任务卡切换 / 参考分变化 | 原地更新 ContentState（不新建活动） |
| 用户拍摄并弹出复盘卡 | **结束**活动（复盘已在 App 内展示，无需继续占屏） |
| 用户离开拍摄页（切到技法/视频/功能页） | **结束**活动 |
| App 进入后台 | **不结束，但冻结内容**（不再更新）。锁屏保留最后一次分数，供用户回看 |
| 系统 8 小时上限 | 系统自动结束（见 §3.3） |

**结束策略**：默认 `.after(Date() + 5 分钟)` 之外的立即移除？—— 不。统一用系统默认移除策略：结束后在锁屏最多保留 4 小时。这是「用户拍完顺手锁屏看一眼复盘」的合理窗口。

### 3.2 各形态显示什么

| 形态 | 显示内容 | 约束 |
|---|---|---|
| **锁屏展开形态**（横幅下方卡片） | ① 参考分数字 + 等级（如「参考分 78 · 良好」）<br>② 当前任务一句话（如「把主体挪到右上交点」）+ 进度指示<br>③ 三格参数建议（ISO / 快门 / WB）<br>④ 未测全时显示「参考分 --」 | 静态 + 动态数据合计 ≤ 4KB（Apple 硬限制）。实际只放十几字节，安全 |
| **灵动岛 紧凑形态（leading）** | 参考分数字（纯数字，最小可读） | 单色、无图标堆砌 |
| **灵动岛 紧凑形态（trailing）** | 任务状态：引导环进度（未达标灰 / 到位绿）用 SF Symbol 表达 | — |
| **灵动岛 展开形态** | 锁屏内容的精简版：参考分 + 任务 + 到位状态 | — |
| **灵动岛 最小形态** | 一个点或极简图标 | 仅在两个其他 App 占用时出现，不承载信息 |
| **点击行为** | 打开 App 并直接落到拍摄页 | 用 `Link` / `widgetURL` 深链 `shottuner://camera` |

**文案铁律**：Live Activity 上同样只说「参考分」，不出现「正确/错误」「专业」。等级词沿用现有「良好」等表述。

### 3.3 刷新频率与耗电权衡

- **数据源**：iOS 端分析管线与 Android 对齐，取景时按 1 Hz 产出统计量。
- **更新策略**：**仅在值真正变化时更新**（沿用 Android 版「值真变了才 setText」的既有做法），且分数变动 < 1 分、参数不变时不更新。目标有效更新频率 ≲ 1 Hz。
- **Apple 的限制（已核实）**：
  - Live Activity 最多**活跃 8 小时**，超时由系统自动结束并从灵动岛移除；
  - 结束后在锁屏最多再保留 **4 小时**（锁屏上从出现到被移除最长 12 小时）；
  - 静态 + 动态数据合计**不得超过 4KB**；
  - Apple 未公布「本地更新」的次数上限（公开的限流说明针对 ActivityKit **推送**更新，用优先级 5 / 10 区分。本项目**不用推送更新**，故不受该预算约束）；
  - 同设备上「同时活动的 Live Activity 数量」有上限，`Activity.request` 可能失败 —— **必须处理启动失败**，失败时静默降级为「只有 App 内 HUD」，不弹错、不崩。
- **耗电权衡**：更新频率高会显著增加功耗。本项目的选择是「前台 1 Hz 且仅在变化时更新，后台冻结」，并在用户离开拍摄页时结束活动。

### 3.4 交互能力与系统版本门槛

| 能力 | 最低版本 | 说明 |
|---|---|---|
| Live Activity 展示 + 点击深链打开 App | **iOS 16.1** | ActivityKit 基础能力 |
| **灵动岛**紧凑/展开/最小三形态 | iOS 16.1 + **iPhone 14 Pro 及以后机型**（有灵动岛硬件） | 无灵动岛的机型只有锁屏形态 |
| **交互式按钮**（灵动岛上直接「到位确认」） | **iOS 17.0** | 通过 `ActivityConfiguration` 的 `Button` + `AppIntent` 实现。**iOS 16.1–16.x 无按钮，只能点击跳转** |
| 交互式小组件 | iOS 17.0 | — |

**「到位确认」按钮**：iOS 17+ 可在灵动岛展开形态放一个「到位」按钮，点击后经 `AppIntent` 回调写入图鉴解锁记录。**注意**：按钮回调会拉起/唤醒 App 进程，属于「用户明确交互」，不违反零网络。但**它只是记账，不产生新的分析** —— 到位判定早在 App 内分析时就已经完成，按钮的作用是「确认解锁 + 给出即时反馈」。

**能否在灵动岛直接快门？—— 不能，且不应做。** 理由：① 拍照需要活跃的 `AVCaptureSession`，App 在后台时相机会被系统挂起；② 让系统在后台触发拍照涉及用户对相机的隐式控制，与 iOS 的隐私模型相悖；③ 灵动岛区域极小，快门误触率高。**替代**：交互按钮提供「打开取景」（带上下文直达），以及「到位确认」。

### 3.5 需要哪些配置

**Info.plist（主 App 与 Widget Extension 两处都要加）**
```
NSSupportsLiveActivities = YES
NSSupportsLiveActivitiesFrequentUpdates = NO   // 本项目不做推送更新，无需频繁刷新能力
```
（`NSSupportsLiveActivitiesFrequentUpdates` 仅对 ActivityKit **推送**更新有意义，本项目零网络、无推送，保持关闭更诚实。）

**Widget Extension Target**
- `NSExtensionPointIdentifier = com.apple.widgetkit-extension`
- `NSExtensionAttributes.NSExtensionActivationRule` = `TRUEPREDICATE`
- Deployment Target ≥ **iOS 16.1**（主 App Target 同版本）
- Widget Extension 的 Bundle ID 必须是主 App Bundle ID 的前缀形式（如 `com.shottuner.app.LiveActivity`）
- **不启用 App Groups**（本条是 §5 J1 能在免费账号侧载下存活的关键，见 §9 风险 R1）

**Entitlements**
- 主 App 与 Extension **均不需要任何付费账号专属 entitlement**
- 明确**不使用**：Push Notifications、App Groups、iCloud、Sign in with Apple、Associated Domains、Time Sensitive Notifications
- Live Activity 用 `pushType: nil` 的纯本地更新路径

**权限**
- Live Activity 本身**不触发任何权限弹窗**，用户可在「设置 → 通知 → 实时活动」里关掉

### 3.6 降级路径（iOS 16.1 以下）

iOS 16.0 及以下设备：Live Activity **完全不可用**（不是「体验差」，是系统没有这套 API）。降级链：

1. **iOS 16.0 及以下** → 只有 App 内 HUD（取景器上的参考分与任务卡）。功能不缺，只是少一处系统级曝光位。**第一批交付必须保证这一档体验完整**，因为 App 的 Deployment Target 定在 iOS 16.0。
2. **iOS 16.1–16.x** → 有 Live Activity 与锁屏形态，但**没有灵动岛**、**没有交互按钮**。
3. **iOS 17.0+** → 全部能力（含灵动岛交互按钮、交互式小组件）。
4. **所有 iOS 16.1+ 设备** → Live Activity 启动失败时（设备活动数达上限 / 用户在系统设置里关掉了实时活动）→ 静默降级到 App 内 HUD，**不提示错误**。

**部署目标定为 iOS 16.0** 的理由：ML Kit iOS 要求 iOS 15.5+，锁屏小组件要求 iOS 16.0+，而 iOS 16.1 才引入 Live Activity。定 16.0 可以让「锁屏小组件」在最大范围的设备上可用，Live Activity 用 `@available(iOS 16.1, *)` 单独开闸。

---

## 4. Widget 设计

> 定位：**回访钩子。** GROWTH-PRD §5 说得很直白：「没有图鉴进度，用户第二次打开没有任何理由」。Widget 是 iOS 上把「回来的理由」放到用户每天都会看的桌面与锁屏上的载体。

### 4.1 分形态内容

| 形态 | 尺寸/家族 | 显示内容 | 优先级 |
|---|---|---|---|
| **主屏小组件** | `systemSmall` | 今日参考分（数字 + 等级），未拍照时显示「今天还没开张」 | P1 |
| **主屏小组件** | `systemMedium` | ① 今日参考分 + 等级 ② 图鉴进度「已解锁 N / 83」+ 进度条 ③ 连续天数（若已实现） | P1 |
| **主屏小组件** | `systemLarge` | ① 今日参考分 ② 30 天成长曲线缩略（SwiftUI `Path` 自绘） ③ 图鉴进度 ④ 最近 3 条解锁的技法 | P2 |
| **锁屏小组件** | `accessoryCircular` | 参考分环形进度（环形显示今日相对最好成绩的比例），中心是分数 | P1 |
| **锁屏小组件** | `accessoryRectangular` | 今日参考分 + 一行当前建议（取自 `PromptEngine` 的高分优先规则） | P1 |
| **锁屏小组件** | `accessoryInline` | 一行文字「参考分 78 · 良好」 | P1 |

**锁屏小组件的形态由系统决定（用户配置），App 不能指定** —— 所以三个 accessory 家族都要实现，不能只做一个。

### 4.2 数据通路与分层降级（**本节是本 PRD 最重要的工程约束**）

Widget 与主 App 是两个进程，读不到对方的沙盒。常规方案是 **App Group 共享容器**。但：

- **免费 Apple ID 侧载时，App Group entitlement 会被签名工具剥离**（已核实：免费签名工具在签发时移除该 entitlement，App Group 相关能力随之失效）；
- 即使保住 entitlement，AltStore/SideStore 类工具会**按签名账号重写 App Group 标识符**，硬编码 group id 会失效；
- 同时，部分侧载工具会提供「移除 App Extension」选项，**扩展 target 可能整体不被安装**。

因此 Widget 按**能力可用性分层**实现：

| 层 | 条件 | 内容 | 是否依赖 App Group |
|---|---|---|---|
| **Tier 0** | 扩展被剥离 / App Group 不可用 | 只提供**深链入口型小组件**：显示「光影调参 · 打开开始今天的练习」，点击进 App。**不显示任何虚假数据** | ❌ 不依赖 |
| **Tier 1** | 扩展存活且 App Group 可用 | 完整数据小组件（今日参考分 / 图鉴进度 / 连续天数 / 曲线） | ✅ 依赖 |

**实现方式**：主 App 启动时检测 `FileManager.default.containerURL(forSecurityApplicationGroupIdentifier:)` 是否可访问 + `Bundle` 内是否含 Widget Extension；不可用则注册 Tier 0 配置，可用则注册 Tier 1 配置。**Tier 0 必须是所有情况下都能工作的**（它是纯静态视图 + 深链，零共享状态依赖）。

### 4.3 刷新策略与后台预算

- **预算（Apple 已公布）**：WidgetKit 对每个小组件实例维护一个 **24 小时滚动窗口**的刷新预算。**用户经常查看的小组件，典型每日 40–70 次刷新**，约相当于每 15–60 分钟一次。预算窗口**不一定在午夜重置**，系统会按用户使用习惯调整。
- **不计入预算的情况**（重要，直接决定了我们的刷新策略）：**主 App 在前台时的重载不计入预算**；系统语言区/外观变化等系统触发的刷新也不计入。
- **本项目的刷新策略**：
  1. 小组件使用 `TimelineProvider` 生成**预排期时间线**（timeline entry 间隔不小于 5 分钟，Apple 的最低要求），而不是靠 App 频繁调用 reload；
  2. 关键节点排期在「自然的时间边界」上（如每天 00:00 跨天、每小时更新「今天」相关文案），减少无谓刷新；
  3. **用户产生关键动作后（拍完一张 / 解锁一条技法 / 拿到新参考分），主 App 在前台调用 `WidgetCenter.shared.reloadTimelines(ofKind:)`** —— 此时不计入预算，是最划算的刷新时机；
  4. **不使用 `reloadAllTimelines()`**，只重载受影响的 kind；
  5. `.never` 刷新策略 + 显式 reload 用于「内容完全由用户行为驱动」的组件。
- **Widget 扩展的硬约束**：Widget 扩展是独立进程，有内存上限（约 30MB 量级），超限会被系统杀掉 → 小组件白屏。**因此小组件里绝不能加载大图或重计算**：曲线用 App 预先算好的降采样点集（存 App Group），不在扩展里重算 500 条历史。

### 4.4 深链行为

统一 URL Scheme：`shottuner://`

| 深链 | 目标 | 触发源 |
|---|---|---|
| `shottuner://camera` | 拍摄页（直接进入取景，权限就绪则直接起会话） | Live Activity、Action Button、控制中心控件、Spotlight |
| `shottuner://dex` | 技法图鉴（带进度总览） | 主屏小组件 |
| `shottuner://today` | 今日概览（今日参考分 + 当前任务 + 成长摘要） | `systemSmall` / `accessoryRectangular` |
| `shottuner://technique/<id>` | 指定技法详情 | Spotlight、Share Extension 的分析结论跳转 |

**深链必须处理「App 未安装 / 冷启动 / 未过隐私门」三种情况**：冷启动时先过隐私门，再路由到目标页；未过隐私门则先落隐私门，通过后再续跳。

---

## 5. 功能需求池（P0 / P1 / P2）

> **P0 定义：「不做就不算 iOS 版成立」。** 也就是「装得上、跑得起来、核心 Aha 30 秒内发生」这条链上缺一不可的东西。
> **P1**：做完 P0 立刻做，主要是 iOS 原生增强与第二平台能力。
> **P2**：有余力再说。

### 5.1 P0 —— iOS 版成立线（14 项）

| ID | 需求 | 用户故事 | 验收标准 | 依赖 |
|---|---|---|---|---|
| **I1** | 隐私门 + 权限流程 | 作为新用户，我第一次打开 App 时先看到「这 App 不联网、不上传照片」的说明，再决定要不要给相机权限 | ① 首次启动全屏展示隐私政策全文（本地资源，非网页）；② 「同意 / 不同意」双按钮；③ 不同意 → 受限模式：不申请相机权限、不启动会话，界面明确说明受限原因；④ 同意后才请求相机权限；⑤ 相册写入权限**在第一次拍照时才请求**（不提前索要）；⑥ 隐私政策全文与「双清单」入口在「关于」页可查 | I1 依赖 `res/raw/privacy_v1.txt` 同源文案 |
| **I2** | 取景 + 四维参考分 | 作为拍照小白，我打开相机就看到「这张照片大概几分、差在哪」 | ① `AVCaptureSession` 实时预览稳定（不卡顿、不掉帧）；② 四维分实时刷新，满分口径 25/30/25/20；③ 任一维度不可测时总分显示「参考分 --」；④ 水平维度用 CoreMotion，**恒可测**（不再走「无地平线记满分」兜底） | 复用 `commonMain` 的 `ScoreBoard` / `LevelMath` / `SharpnessMeter` / `CompositionAnalyzer` |
| **I3** | 首屏任务卡（G1 等价） | 作为拍照小白，我一打开就有人告诉我现在该做什么，而不是给我一串看不懂的参数 | ① 进入取景 5 秒内中央/顶部出现**一条**可完成任务；② 引导环灰→绿；③ 到位时震动（`UIImpactFeedbackGenerator`，无需权限）+ 出成绩卡「参考分 78 · 良好」；④ **不按快门也能得分**；⑤ 20 秒未达标自动换一条（沿用 Android 的 `MissionEngine` 冷却规则） | 复用 `MissionEngine` / `TaskPicker` / `MissionTask` |
| **I4** | 参数建议（ISO / 快门 / WB / EV） | 作为进阶用户，我能看到「照拨」的参数和偏差 | ① 三格参数 + 偏差显示；② **只建议不写入白平衡**（沿用 TEAM.md 的既有裁决：R/G/B 增益跨机型差异极大）；③ ISO/快门的手动写入在设备不支持时**明示降级为纯推荐模式**（对应 Android `CameraEngine` 的能力探测降级） | 复用 `ParamRecommender` |
| **I5** | 构图引导线 + 实时直方图 | 作为新手，我能看到三分线与直方图，知道画面亮不亮 | ① 三分线 / 目标交点高亮（SwiftUI `Canvas` 覆盖层）；② 实时直方图；③ 引导线与直方图不遮挡关键构图区；④ 可开关 | Android `GuideOverlayView` / `HistogramView` 的 SwiftUI 重写 |
| **I6** | 内建拍照 + 写相册 + 复盘卡 + 分享 | 作为用户，我拍完能存进相册、看到复盘、并分享出去 | ① 快门拍照成功 → 写入系统相册（「仅添加」权限）；② 缩略图飞入动画；③ 复盘卡展示四维分与建议；④ 分享走系统 `UIActivityViewController`，照片带水印（若开关打开） | I7 |
| **I7** | 成绩水印 | 作为爱分享的用户，我发出去的图能带上我的成绩 | ① 成片右下角烧「光影调参 · 参考分 78 · 良好」；② **默认关闭，设置可开**（与 Android 现状一致）；③ 位置按短边等比缩放，4:3 / 16:9 均不溢出；④ 未测全烧「参考分 --」 | Android `Watermark.kt` 的 Core Graphics 对应物 |
| **I8** | 技法页 + 图鉴进度 | 作为想进步的用户，我能学技法并看到自己解锁了多少 | ① 83 条技法、15 个分类筛选；② 每条含「当下能立刻做的一个动作」；③ 首页/图鉴页显示「已解锁 N / 83」；④ 解锁双条件（照做 + 达标），同一条每天最多解锁 1 条 | 复用 `commonMain` 的 `TechniqueBookV15` + `PhotographyBook` + `TechniqueDex` |
| **I9** | 分数历史 + 首末对比卡 | 作为用户，我能回答「我变强了吗」 | ① 每次拍照落盘一条四维记录，环形上限 500 条；② 首末对比卡出「+N 分」；③ 30 天曲线自绘（不引图表库） | 复用 `ScoreHistory`（`MAX_RECORDS = 500`） |
| **I10** | 隐私政策 / 关于页 + 双清单 | 作为谨慎的用户，我能随时查清这 App 到底拿了我什么 | ① 关于页内嵌隐私政策全文；② 「个人信息收集清单」（内容为「本应用不收集任何个人信息」）；③ 「第三方共享清单」（内容为端侧 AI 组件清单，注明不共享）；④ 隐私政策版本与 App 版本对应 | 沿用 RELEASE-PRD 的隐私政策模板 |
| **I11** | **iOS 评分基线标定** | 作为用户，App 给我打的分要跟 iPhone 的实际表现对得上 | ① 用同一组标准场景（暗光 / 逆光 / 高对比 / 稳定手持）在 Android 与 iOS 各取样，输出分数对照表；② 曝光与清晰维度的阈值按 iOS 实际表现重新标定（iOS 预览流经 ISP 与计算摄影处理，直方图与锐度分布与 Android 不同，照搬阈值会系统性偏科）；③ 标定结论以常量表形式落在 iOS 侧，**不污染 Android 侧阈值**；④ 标定前后的差异结论写进 QA 报告 | 依赖 I2 完成 |
| **I12** | 深色视觉体系 + iOS 导航结构 | 作为 iPhone 用户，这 App 看起来像 iOS 做的，不是移植的 | ① 沿用现有品牌色板（`st_bg #0E1113` / `st_accent #7FE3C0` / `st_warn #FFB74D` 等）保持跨端品牌一致；② 导航用 `TabView`（4 页签）+ `NavigationStack` 承担二级页；③ 拍摄页进入沉浸态时隐藏 TabBar（对应 Android 的「沉浸 / 专业」两档）；④ 全部按 iOS HIG 的间距、字号、点击热区；⑤ **不做无障碍适配**（见 §6.4） | — |
| **I13** | CI 构建 + 侧载可用 | 作为开发者，我能在没有 Mac 的情况下拿到能装到 iPhone 上的包 | ① GitHub Actions macOS runner 出包；② 产物为可侧载 ipa；③ 第一批**不含任何 App Extension**，确保签名零摩擦（见 §5.3） | 见 §8 |
| **I14** | 数据与重签说明 + 导出/导入骨架 | 作为用侧载的用户，我重装后不至于丢掉图鉴进度 | ① 关于页显式说明「7 天需重签一次」及数据保留规则；② 提供「导出进度 / 导入进度」的入口骨架（实现见 J6，但入口与格式先落地） | §7.4 |

### 5.2 P1 —— 第二批与 iOS 原生增强（8 项）

| ID | 需求 | 用户故事 | 验收标准 | 依赖 |
|---|---|---|---|---|
| **J1** | **Live Activity 教练随身条** | 作为拍照的人，我不用切回 App 也能知道现在几分、该做什么 | ① 拍摄页自动启动，结束时机按 §3.1；② 锁屏 + 灵动岛三形态字段符合 §3.2；③ 仅在值变化时更新；④ **不依赖 App Group**；⑤ 启动失败静默降级；⑥ iOS 16.1 以下无此能力且 App 正常 | **必须先过 §5.4 的 Spike #1** |
| **J2** | **Widget（桌面 + 锁屏）** | 作为每天想看看自己进度的人，我在桌面/锁屏就能看到今天几分、解锁了多少 | ① `systemSmall` / `systemMedium` / `accessoryCircular` / `accessoryRectangular` / `accessoryInline` 全部实现；② 刷新按 §4.3 策略，**不滥用预算**；③ 深链按 §4.4；④ App Group 不可用时自动降级到 Tier 0 入口型小组件；⑤ 小组件进程不加载大图、不重算 500 条历史 | **必须先过 Spike #1** |
| **J3** | **一键入口矩阵**（Action Button / 控制中心 / Spotlight / App Intents） | 作为 iPhone 用户，我按一下就能直接进入取景 | ① Action Button（iOS 17+ / iPhone 15 Pro+）可绑定「打开光影调参取景」；② 控制中心控件（iOS 18+）一键启动；③ Spotlight 可搜到 App 与 83 条技法；④ App Intents 支持「今天该练什么」这类语音指令 | J2 的 App Group 判定结果（Tier 0 也要能深链） |
| **J4** | **Share Extension：系统相机拍完一键复盘** | 作为用系统相机的人，我拍完分享到「光影调参」就能知道这张好不好 | ① 分享菜单出现「光影调参」；② 收到的**原图**做四维评分（精度高于 Android 的截屏像素分析）；③ 出成绩卡与建议；④ 分析全程本地，**不上传**；⑤ 扩展被剥离时不影响主 App | **必须先过 Spike #1**（扩展存活） |
| **J5** | **Apple Watch App：手腕看分** | 作为拍照的人，我抬腕就知道现在几分 | ① Watch 端显示参考分 + 当前任务 + 参数建议；② 手机侧分析结果经 `WatchConnectivity` 传给手表；③ 手表端**不做独立分析**（不可能），只做显示；④ 手机不在身边时显示「最后同步于 xx:xx」 | I2 / I3 稳定 |
| **J6** | **数据导出 / 导入** | 作为侧载用户，我换 Apple ID 或重装 App 时能把进度带走 | ① 导出图鉴进度 + 分数历史为单个 JSON 文件；② 导出经系统分享面板，用户自选去向（**App 本身不发起任何网络请求**）；③ 导入可恢复；④ 导入做版本兼容与坏数据降级（读失败回空，不崩） | I14 |
| **J7** | **端侧 AI 场景分类补齐** | 作为用户，App 能认出我在拍什么（人像 / 食物 / 宠物…），从而给对建议 | ① 补齐 Android 端 `AiVision` 的图像标签能力（对应 ML Kit `ImageLabeling`）；② 人脸检测优先用 **Apple Vision**（`VNDetectFaceRectangles`），不引入第三方；③ 端侧推理，**全程离线** | 见 §7.2 的方案选择 |
| **J8** | **前后对比卡（同场景版）** | 作为用户，我能直观看到「听教练之前 / 之后」 | ① 教练建议变化时自动存一张低清缩略当「听之前」；② 成片当「听之后」；③ 左右拼图 + 中间「+N 分」 | I6 / I9 |

### 5.3 P2 —— 有余力再说（6 项）

| ID | 需求 | 说明 |
|---|---|---|
| K1 | `systemLarge` 成长曲线小组件 | 数据量与刷新成本更高，等 Tier 1 验证稳定再做 |
| K2 | 交互式小组件（iOS 17+ `Button`） | 如「直接拍一张」「标记已到位」 |
| K3 | Spotlight 深度索引 83 条技法正文 | 增强 N4 |
| K4 | 浅色模式 | 取景页强制深色；技法/图鉴页可选跟随系统 |
| K5 | Apple Watch 端独立任务卡 | 手表上直接确认到位 |
| K6 | 每周挑战卡（本地预置 12 周主题） | 沿用 GROWTH-PRD G7 |

### 5.4 分批交付计划（**硬性节奏约束**）

> 前提：clover **没有 Mac**，出包必须走 GitHub Actions 的 macOS runner，**每次出包要等 CI 跑 5–10 分钟**。这决定了下面这个分批边界不是「按重要性切」，而是「**按能不能一次装成功切**」。

#### 第一批 —— MVP（能装上跑起来的 iOS 版）

**范围：P0 全部 14 项（I1–I14）。**

| 项 | 内容 | 说明 |
|---|---|---|
| 相机 | `AVCaptureSession` 取景 + `AVCapturePhotoOutput` 拍照 | 不含任何 App Extension |
| 评分 | 四维参考分 + 参数建议 + 引导线 + 直方图 | 全部复用 `commonMain` |
| 任务卡 | 首屏任务卡 + 到位反馈 + 免拍照得分 + 成绩卡 | 复用 `MissionEngine` |
| 技法 | 83 条 + 15 分类 + 图鉴进度「已解锁 N / 83」 | 复用 `commonMain` |
| 隐私门 | 首次启动同意页 + 权限流程 + 关于页双清单 | — |
| 成长 | 分数历史（500 条）+ 首末对比卡 | 复用 `commonMain` |
| 拍摄闭环 | 写相册 + 缩略图 + 复盘卡 + 分享 + 水印（默认关） | — |
| 工程 | KMP `commonMain` 落地 + `TechniqueDex` 的 JVM API 改造 + XcodeGen + CI 出包 | 见 §8 |
| **明确不含** | Live Activity、Widget、Share Extension、Watch、Action Button | 全部依赖 App Extension 存活，见 Spike #1 |

**第一批完成的验收标准**：侧载装上 → 首次启动过隐私门 → 3 秒内出取景 → 30 秒内出现任务卡并能到位 → 拿到参考分 → 拍一张存进相册 → 技法页能看 83 条 → 图鉴显示「已解锁 N / 83」→ 重启 App 数据还在。

#### 第二批 —— iOS 原生增强

**范围：P1 的 J1–J8（8 项）。**

**开工前置条件（Spike #1，必须在第一批开工后尽早并行做掉）**：

用**最小的空壳工程**（不是完整 App）验证三件事，产出一份 1 页结论：

| 验证项 | 通过标准 | 不通过怎么办 |
|---|---|---|
| **A. App Extension 在侧载后是否存活** | 用 Sideloadly / AltStore / SideStore 分别装一个带空 Widget Extension 的壳 App，扩展在「设置 → 通用 → 后台 App」或小组件库里可见且能添加 | 若不存活 → 全部 Extension 功能（J1/J2/J4/J5）降级为 P2，iOS 版只保留 Action Button / 控制中心 / Spotlight（**这三个不需要 Extension**），并在 PRD 中明确记录取舍 |
| **B. App Group entitlement 在免费账号侧载后是否保留** | 壳 App 启动时打印 `FileManager.default.containerURL(forSecurityApplicationGroupIdentifier:)` 是否非空 | 若被剥离 → Widget 走 Tier 0 降级（§4.2），**Live Activity 不受影响**（本就不依赖 App Group） |
| **C. 硬编码 group id 是否被工具重写** | 装完后读回实际 group 标识符，与预期比对 | 若被重写 → group id 改为**运行时探测**而非硬编码 |

**为什么 Spike #1 不能等到第二批**：如果结论是「扩展装不上」，那么第二批的 8 项里有 5 项直接作废，架构师需要提前改设计（比如把「Action Button / 控制中心」提到第二批第一批做）。**这个结论的获得时间，直接决定第二批的排期形态。**

#### 两批之间的时间边界

- 第一批结束 → **不要立刻进第二批**。先做「侧载自用 3–7 天」，验证 §5.1 的 P0 验收标准，尤其是 I11（iOS 评分基线标定）—— 阈值不对，后面所有留存机制都建在沙子上。
- Spike #1 建议在第一批**开工第一周内**就并行启动，不要等第一批做完。

---

## 6. 交互与视觉设计

### 6.1 导航结构方案

Android 版是「单 Activity + 4 页签（照片 / 视频 / 功能 / 技法）+ 折叠面板」。iOS 不能照抄，理由有三：

1. iOS HIG 期望的是**清晰的层级与返回路径**，不是「一个页面里塞四个模式」；
2. 二级内容（技法详情、图鉴、关于、设置）在 iOS 上应该是**推入式**，可以下拉返回；
3. 拍摄页需要**沉浸**，与浏览页的导航诉求相反。

**方案：`TabView`（4 页签，保留 Android 的信息架构）+ `NavigationStack` 承担全部二级页。**

| 页签 | 内容 | 导航行为 |
|---|---|---|
| **拍摄** | 取景 + 任务卡 + 四维 + 参数 | 进入拍摄页时**隐藏 TabBar**（沉浸态），向上滑或点顶部按钮呼出（对应 Android 的「沉浸 / 专业」两档）；其他页签切换回来时恢复 |
| **视频** | 8 条运镜技法建议 | `NavigationStack` 推入意图详情（分辨率/帧率/180°快门/码率建议） |
| **技法** | 15 分类 + 83 条 + 图鉴进度 | `NavigationStack` 推入技法详情；图鉴作为二级页 |
| **功能** | 场景特化建议 + 设备能力探测 + 设置（水印开关 / 数据导出 / 关于 / 隐私政策） | `NavigationStack` 推入 |

**理由**：保留 4 页签是为了**跨端认知一致**（同一个用户可能在两端用），iOS 端不应该改产品结构去追 HIG —— HIG 追的是**交互形式**（推入、返回、TabBar 行为），不是信息架构。这条边界要守住。

### 6.2 任务卡在 iOS 上放哪

Android 版把任务卡浮在取景器**中央**。iOS 版**不放中央**，理由：

- 灵动岛 / 刘海占据顶部，中央是主体与目标交点的位置 —— 遮住构图就等于遮住要评判的东西；
- iOS 视频/照片内容常在画面中部，遮挡体验更差。

**iOS 方案**：
- **任务文字卡**：取景器**顶部安全区下方**（灵动岛之下），一行主句 + 状态圆点，横向毛玻璃底（`st_glass_bg` 一类材质）；可上滑展开详情。
- **引导环 / 目标交点高亮**：留在**画面中的实际目标位置**（与 Android 一致，这是空间性提示，必须在真实位置才有意义），用 SwiftUI `Canvas` 覆盖层绘制。
- **到位反馈**：引导环变绿 + 轻震动 + 成绩卡从底部升起（iOS 惯用）。
- **参考分**：取景器**左上角**常驻一个紧凑徽标（数字 + 等级），点按展开四维明细（半透明 `sheet`）。

**层级沿用 GROWTH-PRD 的 L1/L2/L3**：
- L1（主位）：任务 + 到位反馈 + 参考分
- L2：四维小条
- L3：ISO / 快门 / WB —— 放在底部可上拉的参数面板里，**不删参数**（ProShot 那批用户的唯一理由）

### 6.3 参考分的展示位置与形态

| 位置 | 形态 | 说明 |
|---|---|---|
| 取景器左上角 | 紧凑徽标：数字 + 等级 | 常驻，不遮挡构图 |
| 底部参数面板首行 | 四维明细（水平/曝光/构图/清晰），每维一条细条 + 数值 | 上滑面板可见 |
| 灵动岛 leading | 纯数字 | Live Activity 内 |
| 锁屏小组件 | 环形进度 + 中心数字 | Widget 内 |
| 成绩卡 / 复盘卡 | 大号数字 + 等级 + 逐维明细 | 拍照后 |

**口径**：所有位置统一「参考分 N · 等级」；未测全时统一「参考分 --」。等级词沿用 Android 现有表述（良好 / 一般等），不新增评价性词汇。

### 6.4 视觉与配色

- **色板**：沿用 Android 现有色板，保证跨端品牌一致 —— 底 `#0E1113`、正文 `#F2F6F8`、主色 `#7FE3C0`、警示 `#FFB74D`、暖色辅助 `#9FC6E8`、优先级三色 `#FF8A65` / `#7FE3C0` / `#9FC6E8`。
- **主题**：**App 内统一深色**（与 Android 版一致；取景场景本就要求深色背景）。浅色模式列为 P2（K4），且**仅作用于非取景页**。
- **材质**：iOS 端用系统材质（`.ultraThinMaterial` / `.regularMaterial`）替代 Android 的自定义玻璃层；玻璃底色映射到 `st_glass_*` 一组色值，**不重新设计色板**。
- **图标**：SF Symbols 为主，App 自有图形仅限品牌标识与技法图鉴插画。
- **动效**：沿用 Android 的既有语义（值真变了才动、数值高亮 200ms 淡出），但**动效曲线改用 iOS 原生 spring**，不逐帧照搬 Android 的 `alpha` 动画。

### 6.5 明确不做无障碍适配

**iOS 版不做无障碍适配。** 与 Android 版一致（GROWTH-PRD §9 反向清单已记：「clover 明确要求不做」—— 48dp 热区、`contentDescription`、`reduce motion` 一律不加）。

具体不做的项：
- 不加 `VoiceOver` 标签与朗读顺序
- 不做 `Dynamic Type` 缩放适配（文字尺寸固定）
- 不做 `reduce motion` / `reduce transparency` 降级
- 不做高对比度模式适配
- 不做 `accessibilityIdentifier` 自动化测试标记

**仅保留一条底线**：因为 iOS 版不上架（侧载自用），不存在 App Store 审核的无障碍审查风险，因此这条决策**没有合规代价**。

**给工程师的提醒**：本项目自用、不上架，因此**不要**为了「像一个正经 iOS App」而顺手加 VoiceOver —— 这会引入大量工作量且与既定偏好冲突。但**动效与布局仍要按 HIG 走**（这是「原生感」的来源，与无障碍无关）。

---

## 7. 隐私与合规

### 7.1 Info.plist 用途说明字符串清单（逐条中文文案）

| Key | 中文文案 | 说明 |
|---|---|---|
| `NSCameraUsageDescription` | **光影调参需要使用相机来实时分析你眼前的画面，帮你判断曝光、构图和拍摄参数。画面分析全部在你的手机本地完成，不会被上传，也不会离开这台设备。** | 首次进入拍摄页时请求 |
| `NSPhotoLibraryAddUsageDescription` | **光影调参会把你在应用内拍下的照片保存到你的相册，方便你继续编辑或分享。仅写入你主动拍摄的照片，不会读取你相册里的其他内容。** | **第一次拍照时**才请求，不提前索要 |
| `NSMicrophoneUsageDescription` | **不需要申请。** | iOS 版**视频页只提供运镜与参数建议，不做真实录制**（与 Android 版一致 —— Android 全仓无 `MediaRecorder` / `CameraX.VideoCapture`），因此**不申请麦克风权限** |
| `NSPhotoLibraryUsageDescription` | **不需要申请。** | 只用「仅添加」权限写相册，**不读相册**。用户如需从相册选图复盘，走 `PHPickerViewController`（系统选择器，**无需任何相册权限**） |

**这条「不申请」也是产品决策，不只是技术事实**：iOS 用户对「相册读权限」的警惕远高于 Android。**能不给就不给**是 iOS 版的隐私优势，不要主动放弃。

### 7.2 零网络策略在 iOS 上的落地（**Android 证明方式在这里失效**）

Android 版可以靠「manifest 里 `tools:node="remove"` 移除 `INTERNET` / `ACCESS_NETWORK_STATE`」在系统层面自证不可联网。**iOS 没有这个机制** —— iOS 应用默认就有网络能力，Apple 不提供「移除网络权限」的开关。

所以 iOS 版必须换一套证明链，四道防线：

| 防线 | 做法 | 可验证性 |
|---|---|---|
| **1. ATS 配置** | `Info.plist` 显式声明 `NSAllowsArbitraryLoads = false`（不放宽），`NSAllowsArbitraryLoadsInWebContent = false` | 配置可读。但**注意：ATS 只约束 `URLSession` 等系统网络 API，挡不住自建 socket，也挡不住第三方 SDK 内部的网络行为** |
| **2. 代码层零网络调用** | 全仓不使用 `URLSession` / `URLRequest` / `Network.framework` / `CFNetwork` / `NWConnection` / `WebSocket`；**不引入任何联网 SDK** | **CI 里加一条 grep 门禁**：命中即构建失败（对应 Android 版 RELEASE-PRD §0 F2「全仓网络调用零命中」的同等强度） |
| **3. 依赖白名单** | 只允许显式列出的依赖进入构建：Kotlin/Native runtime、KMP `commonMain` 产物、端侧 AI 组件。**任何新增依赖必须显式加白** | CI 里比对依赖清单 |
| **4. 二进制校验** | 出包后扫描最终二进制/Framework 的链接库与可疑符号（`CFNetwork`、`NSURLSession` 相关符号、`GoogleDataTransport` 等），命中即告警 | CI 出包阶段自动执行 |

**⚠️ 必须专门点名的风险：端侧 AI SDK 的遥测血统。**

Android 版已经踩过这个坑：`com.google.mlkit:vision-internal-vkp` 会传递引入 `datatransport`，往 manifest 里塞 `INTERNET` 与 `ACCESS_NETWORK_STATE`，Android 版是**显式 remove** 才守住承诺。iOS 侧的 ML Kit Pods 有同类血统的**可能性存在**，且 iOS 侧**没有 `tools:node="remove"` 这种兜底**。

因此 §5.2 的 J7 采用**分级策略**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **首选（建议采纳）** | **P0/P1 全部使用 Apple Vision**（`VNDetectFaceRectangles` 人脸检测 + `VNClassifyImageRequest` 基础分类），**完全不引入 Google ML Kit** | ① 零第三方 SDK，防线 1–4 全部干净；② 无 CocoaPods 依赖，**彻底绕开「ML Kit 仅 CocoaPods 分发 × KMP 集成」的冲突**；③ 包体积更小；④ 不存在遥测血统问题 | 分类标签体系与 Android 的 ML Kit 标签不完全一致，场景分类精度可能低于 Android |
| **备选** | 引入 ML Kit iOS（`GoogleMLKit/ImageLabeling` + `GoogleMLKit/FaceDetection`，Pod 分发） | 与 Android 端 1:1 对齐，行为可复现 | ① 引入 CocoaPods（KMP 集成复杂点）；② **存在遥测血统风险**，须靠防线 4 的二进制扫描把关；③ 包体积增大 |

**建议：P0/P1 走「首选」，J7 用 Vision 落地；把 ML Kit 作为可选增强，且必须在引入前用二进制扫描验证其不引入网络能力。** 若最终选择备选方案，需在 QA 报告中附二进制符号扫描证据。

**旁证**：Android 版的 `SceneClassifier` 本来就有**不依赖 AI 的规则路径**（`classify(s: FrameStats)`），AI 只是增强。因此 Vision 降级不会让功能塌陷，只是场景识别精度下降 —— 这与 GROWTH-PRD §5「AI 不在线也能用」的基调一致。

### 7.3 免费 Apple ID 侧载对签名 / entitlements 的要求与限制

| 限制 | 影响 | 本项目的应对 |
|---|---|---|
| **Provisioning Profile 7 天过期** | 每 7 天必须重签，否则 App 无法启动 | ① 优先用 **SideStore**（纯手机端刷新，无需电脑常驻）；② 次选 AltStore（需 AltServer 与电脑同网，自动后台刷新）；③ 兜底 Sideloadly（一次性手动）；④ **在「关于」页给出重签提醒**（见 §7.4） |
| **同一 Apple ID 最多 3 个活跃侧载 App**（AltStore 自身占 1 个） | 本项目自己占 1 个，余量充足 | 无影响 |
| **7 天内最多注册 10 个 App ID** | 含扩展的 App 可能占用多个 App ID | 第二批引入扩展时重新评估；**建议用一个专门的「副号 / 一次性 Apple ID」签署** |
| **Push Notifications / iCloud / App Groups 等 entitlement 不可用** | 不能用推送更新 Live Activity；App Group 可能在签名时被剥离 | ① Live Activity 用 `pushType: nil` 纯本地；② **不申请任何付费专属 entitlement**；③ Widget 分层降级（§4.2） |
| **App Extension 可能被侧载工具剥离**（部分工具有「移除 App Extension」选项） | Live Activity / Widget / Share Extension 可能整体不生效 | **Spike #1 专门验证**；失败则这些能力降级为 P2，**第一批完全不含扩展，保证一定能装上** |
| **不签名则装不上** | CI 无法产出「已签名」的可直装包 | CI 出**未签名 ipa**，由本机侧载工具用 Apple ID 签署（见 §8.3） |
| **App Group 标识符可能被工具按账号重写** | 硬编码 group id 失效 | group id **运行时探测**，不硬编码（Spike #1 验证项 C） |

**⚠️ 侧载工具的选择直接影响产品可用性**：SideStore / AltStore / Sideloadly 对扩展与 entitlement 的处理策略不同。**本项目必须固定一个主用工具并在 QA 报告中记录验证结论**（建议主用 SideStore：纯手机端刷新，7 天重签成本最低）。

### 7.4 数据与重签（产品决策）

**问题**：侧载重签后，App 容器**可能被重建** → 图鉴进度、分数历史、连续天数可能全部丢失。对一个把「收集进度」当留存核心的产品（GROWTH-PRD §5），这是硬伤。

**iOS 侧的实际机制（与 Android 不同）**：重签后若 **Bundle ID 与 Team ID 不变**，系统通常会保留原容器数据（同 App 覆盖安装）；但**换 Apple ID、换工具导致的 Bundle ID 重写、卸载重装**，都会导致数据丢失。

**产品决策（三条）**：

1. **提供导出 / 导入**（J6，P1）—— 把图鉴进度 + 分数历史导出为单个 JSON 文件，经系统分享面板由用户自选去向。**App 本身不发起任何网络请求**；用户若把它存到 iCloud Drive，那是系统与用户的行为，不是本 App 的联网行为，**必须在导出说明里写清这一层区别**（否则会与「照片不上传」的承诺产生歧义）。
2. **关于页显式说明重签规则**（I14）：写清「7 天需重签一次」「换 Apple ID 或重写 Bundle ID 会导致进度丢失」「建议先导出再重签」。**把风险讲在前头，比让用户踩坑再解释更符合本产品的坦诚口径。**
3. **不引入 iCloud 同步**（免费账号不支持 iCloud entitlement），也不做账号体系 —— 与 GROWTH-PRD 反向清单一致。

### 7.5 隐私政策 / 关于页在 iOS 版的处理

**沿用 RELEASE-PRD 的模板，但要为 iOS 改写三处**（不能直接复制）：

| RELEASE-PRD 原文 | iOS 版改法 |
|---|---|
| 「本应用未申请 `INTERNET` 网络访问权限……在操作系统层面不具备联网能力」 | **iOS 不适用**。改为：「本应用不包含任何网络请求代码，未引入任何具备联网能力的第三方组件；iOS 应用本身不具备『移除网络权限』的开关，因此我们通过四条防线自证（ATS 不放宽 / 代码零网络调用 / 依赖白名单 / 出包二进制扫描），扫描结果随构建产出。」 |
| 「录屏权限（MediaProjection）」「悬浮窗权限（SYSTEM_ALERT_WINDOW）」「前台服务」 | **iOS 版没有这三项**，对应章节整段删除，替换为：Live Activity / Widget / Watch 的说明（这些**不采集任何数据**，只是展示本机算出的结果） |
| 「存储权限（WRITE_EXTERNAL_STORAGE，仅 Android 9 及以下）」 | 替换为 iOS 的「仅添加相册权限」说明，强调**只写不读** |

**双清单**（「个人信息收集清单」/「第三方共享清单」）在 iOS 版同样要有，内容按 iOS 实际依赖重写：若采用 Vision 方案，「第三方 SDK 清单」一栏为**空**（Apple 系统框架不属第三方 SDK），这反而是 iOS 版最漂亮的一页。

---

## 8. 分发与构建方案

### 8.1 GitHub Actions macOS runner 流水线设计

**触发方式**

| 触发 | 做什么 | 耗时预期 |
|---|---|---|
| `push` 到主分支 | **快检作业**：Kotlin `commonMain` 编译 + `commonTest` 单测 + 静态门禁（网络调用 grep、依赖白名单比对） | 数分钟，不出包 |
| `pull_request` | 同上，不出包 | 同上 |
| `workflow_dispatch`（手动） | **完整出包**：出 ipa 产物 | 5–10 分钟 |
| 打 tag（`v*`） | 完整出包 + 归档 | 5–10 分钟 |

**这个分层的意义**：clover 没有 Mac，**每一次真机验证都必须等 CI**。所以必须把「5–10 分钟」只花在真正需要出包的时候；日常改一行 Kotlin 逻辑，用「快检作业」在几分钟内就能知道编不编得过、逻辑对不对。这条流水线设计直接服务于「没有 Mac」这个约束。

**流水线步骤（完整出包）**

1. **Checkout** 仓库
2. **选 Xcode 版本**：`macos-14` / `macos-15` runner，固定具体 Xcode 版本（不跟 latest —— Xcode 大版本升级会静默改变编译行为）
3. **安装 XcodeGen**：`brew install xcodegen`
4. **生成工程**：`xcodegen generate`（**从 `project.yml` 生成 `.xcodeproj`**，不提交 `.xcodeproj`）
5. **Kotlin 产物**：`./gradlew :shared:linkDebugFrameworkIosArm64` / `embedAndSignAppleFrameworkForXcode`（与 Xcode Run Script 阶段二选一，见 §8.2）
6. **CocoaPods**（仅当采用 ML Kit 方案时）：`pod install`，并缓存 `~/Library/Caches/CocoaPods` 与 `Pods/`
7. **编译**：`xcodebuild -workspace ShotTuner.xcworkspace -scheme ShotTuner -configuration Release -destination 'generic/platform=iOS' build`
8. **打包**：组装 `Payload/ShotTuner.app` → 压缩为 `ShotTuner-ios-<version>-<build>.ipa`
9. **二进制零网络扫描**（§7.2 防线 4）：扫描链接库与可疑符号，命中即失败
10. **上传产物** + 保留 7 天

**缓存策略**

| 缓存项 | 键 | 收益 |
|---|---|---|
| Gradle 依赖 + K/N 工具链 | `gradle.lockfile` / `gradle-wrapper.properties` 哈希 | Kotlin/Native 工具链下载量大，是最大头 |
| CocoaPods 缓存 | `Podfile.lock` 哈希 | 避免每次拉 spec 仓库 |
| Xcode DerivedData | 源码哈希 | 增量编译 |

**为什么必须用 XcodeGen**：本项目有 **3 个 target**（主 App / Widget Extension / 可选 Share Extension）+ 各自 entitlements + Info.plist + build phase（Kotlin 产物嵌入）。手写 `.xcodeproj` 在 PR 里必然冲突，且「同一个工程在 CI 与本地要一致」无法保证。`project.yml` 是可 review、可 diff、可复现的单一事实来源。**这是本项目在没有 Mac 的条件下唯一可靠的工程组织方式** —— 工程师本地也不需要 Xcode 就能改工程结构。

### 8.2 KMP 与 Xcode 的集成方式（二选一，倾向 B）

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. CocoaPods 集成**（Kotlin 官方 `cocoapods` 插件） | KMP 模块生成 podspec，App 的 `Podfile` 里 `pod 'shared', :path => '../shared'` | 官方支持，自动构建 framework | 与 ML Kit 的 Pod 混用时 `use_frameworks!` / `use_modular_headers!` 组合复杂，是已知的冲突高发点 |
| **B. Run Script 阶段直接调 Gradle**（**建议**） | Xcode 的 Build Phase 调 `./gradlew :shared:embedAndSignAppleFrameworkForXcode`，产出 framework 嵌入 App | ① **KMP 与 CocoaPods 完全解耦**，ML Kit 是否存在不影响 KMP 集成；② 出问题好定位；③ 不需要维护 `Podfile` 与 KMP 的版本耦合 | 每次 Xcode 编译都会触发 Gradle（可用增量 + 缓存缓解） |

**建议采纳 B**，并把 ML Kit 的引入（J7）作为**独立于 KMP 的旁路**：ML Kit 只在 iOS 表现层使用，不进入 `commonMain`。这样「KMP × CocoaPods 冲突」这条风险（§9 R5）被结构性消解，而不是靠小心翼翼地配置绕开。

**若最终采用 Vision 方案**（§7.2 首选），则**项目里根本不出现 CocoaPods**，冲突风险归零。

### 8.3 免费 Apple ID 侧载的具体流程与 7 天重签提醒

**为什么 CI 出的是未签名包**：签名需要 clover 的 Apple ID 凭据。**把 Apple ID 放进 CI 的 secrets 是不可接受的安全做法**（凭据泄露 = 账号被锁）。因此：

- **CI 产出未签名 ipa**（`.app` 内的二进制不带签名）
- **本机用侧载工具签名**：把 ipa 拖进 Sideloadly → 填 Apple ID → 签名并安装
- 首次安装后需在手机「设置 → 通用 → VPN 与设备管理」信任一次

**7 天重签提醒机制（产品设计）**：

| 载体 | 内容 | 频率 |
|---|---|---|
| App 内「关于」页 | 固定说明：7 天有效期 + 数据保留规则 + 导出建议 | 常驻 |
| 侧载工具自身 | SideStore / AltStore 会显示剩余天数并支持后台自动刷新 | 工具侧 |
| **App 内提醒（可选，P2 级别的小事）** | 冷启动时读本机证书有效期（`SecCertificateCopyValues`），剩余 < 48 小时时在「关于」页顶部提示「证书即将过期，请尽快重签」 | 仅在有剩余有效期信息时显示 |

**工具选型建议（写进 README）**：
- **主用 SideStore** —— 纯手机端刷新，无需电脑常驻，7 天成本最低；
- **备选 AltStore** —— 需 AltServer 与电脑同 Wi-Fi 自动刷新；
- **兜底 Sideloadly** —— 一次性手动，适合临时验证；
- **注意**：部分工具提供「移除 App Extension」选项，**若要让 Live Activity / Widget 生效，不要勾选**。

### 8.4 产物命名与版本号策略

**单一事实来源**：营销版本号写在仓库根的一个 `version.properties`，Android 的 Gradle 与 iOS 的 XcodeGen（CI 侧从同一文件读取）**都从它取值**，从机制上杜绝两端版本号漂移。

| 字段 | Android | iOS | 规则 |
|---|---|---|---|
| 营销版本 | `versionName = "3.1.0"` | `CFBundleShortVersionString = "3.1.0"` | **两端严格一致**（用户认知是同一个产品） |
| 构建号 | `versionCode = 31` | `CFBundleVersion = 1` | **各自独立单调递增**。iOS 构建号**不复用 Android 的数字序列** —— iOS 侧的发版节奏与 Android 不同，共用一个序列会导致「iOS build 31」被误读成「Android v3.1.0 的第 31 次构建」 |
| iOS 构建号递增 | — | 每次出包 +1 | 只增不减（Apple 要求） |
| 产物名 | `ShotTuner-android-<ver>-<build>.apk` | `ShotTuner-ios-<ver>-<build>.ipa` | 含平台标记，避免两端产物混淆 |

**与 Android 版本的对应关系**：iOS v1.0.0（营销版本 `3.1.0`）对应 Android v3.1.0（versionCode 31）。iOS 首发即对齐当前 Android 最新版，用户认知成本最低。

### 8.5 CI 失败时的排查路径

按失败概率从高到低：

| 症状 | 最可能的原因 | 排查动作 |
|---|---|---|
| Gradle 步骤超时 / K/N 工具链下载失败 | 缓存未命中或 runner 网络问题 | 看该步骤日志是否有 `Downloading Kotlin/Native`；重跑一次；确认 `gradle-wrapper.properties` 哈希未变导致缓存失效 |
| `xcodebuild` 报找不到 framework | Kotlin 产物未生成或路径不匹配 | 看 Run Script 阶段是否真的跑了 Gradle；确认 `embedAndSignAppleFrameworkForXcode` 的产物路径与 Xcode 搜索路径一致 |
| `pod install` 失败（仅 ML Kit 方案） | CocoaPods 版本 / Ruby 版本不匹配 | 确认 runner 上的 CocoaPods 版本；`Podfile.lock` 是否被改动；先单独跑 `pod install --repo-update` 定位 |
| 签名相关报错 | 本地签名环节问题（CI 不签名，理论上不应出现） | 确认 CI 是否误开了 `CODE_SIGNING_ALLOWED` |
| 二进制零网络扫描失败 | 引入了带网络能力的依赖 | 看扫描出的具体符号/库名 → 定位到依赖 → 加白名单或换掉 |
| `commonTest` 失败 | KMP 化过程中引入了平台差异 | 本地无法跑（无 Mac）→ 在 CI 上跑，重点看 `TechniqueDex` 的日期格式化改造 |
| 单测在 KMP 下编译不过 | `commonMain` 里残留了 JVM 专属 API | **当前已知只有 `TechniqueDex.kt` 一处**（`java.text.SimpleDateFormat` / `java.util.Date` / `java.util.Locale`），需改造为 `kotlinx-datetime` 或 expect/actual |

**建议**：CI 在**出包前**强制跑一次「commonMain 可编译性检查」（只编 `commonMain`，不带任何平台源集），这个检查能在 3 分钟内挡住最常见的移植失败，比出包后才发现快得多。

---

## 9. 风险清单

> 排序依据：**概率 × 影响**。概率是我基于当前信息（代码走查 + 平台文档 + 侧载机制）的判断，影响是「如果发生，对本项目意味着什么」。

### R1 · 免费账号侧载下 App Extension / App Group 被剥离，导致 Live Activity、Widget、Share Extension、Watch 集体失效 —— **最高**

- **概率**：中高。多个侧载工具在签发时会移除付费账号专属 entitlement，且部分工具提供「移除 App Extension」选项。
- **影响**：**高**。第二批 P1 的 8 项里有 5 项直接作废，Live Activity 这个「iOS 招牌替代方案」落空，iOS 版相对 Android 版的最大差异化（系统级教练位）消失。
- **缓解**：
  1. **Spike #1 提前做**（§5.4），用空壳工程在三个工具上各装一次，拿到结论再定第二批排期 —— **这一条能把风险从「未知」变成「已知且有方案」，是整个风险清单里投入产出比最高的动作**；
  2. **第一批完全不含扩展**，保证「iOS 版成立」这件事不被这个风险绑架；
  3. **Live Activity 设计成不依赖 App Group**（数据走 ActivityKit ContentState 由系统投递）—— 这样即使 App Group 被剥离，J1 仍可用；
  4. **Widget 分层降级**（Tier 0 / Tier 1，§4.2），Tier 0 不依赖任何共享状态；
  5. 若 Spike 结论为全灭，**J3（Action Button / 控制中心 / Spotlight）仍可用**（不需要 Extension），保证第二批至少有产出。

### R2 · iOS 评分基线与 Android 不一致，阈值照搬导致分数系统性失真 —— **高**

- **概率**：高。**这不是「可能出问题」，是「不处理一定出问题」**：iOS 预览流经 ISP、计算摄影、降噪处理，直方图分布与锐度结构跟 Android 完全不同。
- **影响**：**高**。参考分是这个产品**唯一**的产出物。分数失真 = 整个产品价值失真，而且**用户不会知道**，只会在心里默默认为「这 App 乱打分」然后不再用。这是**静默失败**，最伤留存。
- **缓解**：
  1. **I11 列为 P0**，不是 P1 —— 必须在第一批内完成标定；
  2. 标定方式：同一组标准场景（暗光 / 逆光 / 高对比 / 稳定手持 / 运动模糊）两端同拍，产出分数对照表，按 iOS 实际表现重新推导曝光与清晰阈值；
  3. iOS 阈值**独立存放**，不污染 Android 侧常量；
  4. 标定结论进 QA 报告，**作为 iOS 版的发版门槛**；
  5. 附带收益：标定过程会暴露「iOS 相机硬件更强」带来的天花板效应（iPhone 拍得更清楚 → 清晰维度普遍接近满分 → 该维度失去区分度）。**若确实如此，清晰维度在 iOS 上应重新定义或重新配权**，这是一个必须在标定阶段就面对的设计问题。

### R3 · 7 天重签导致本地数据丢失，图鉴进度归零 —— **高**

- **概率**：中高。换 Apple ID、侧载工具重写 Bundle ID、卸载重装，都会触发。
- **影响**：**高**。「已解锁 N / 83」是 GROWTH-PRD 定义的核心留存机制，进度清零 = 用户第二次打开的理由消失 = 留存漏斗断在最关键的一环。
- **缓解**：
  1. **J6 导出/导入**（P1）；
  2. **I14 在关于页讲清重签规则**（7 天有效期 / 什么操作会丢数据 / 建议导出后再重签）—— 知情比补救重要；
  3. **主用 SideStore**（保留容器数据的概率高于换工具重装）；
  4. **不用 iCloud 同步**（免费账号不可用），也**不做账号体系**；
  5. 导出文件是纯 JSON，**不带任何设备标识**，符合「数据只在本机」的承诺。

### R4 · CI 出包是唯一验证手段（没有 Mac），迭代节奏被 CI 时延锁死 —— **高**

- **概率**：确定发生（这是既定约束）。
- **影响**：**高**。每次真机验证 5–10 分钟起步，改一个 UI 文案也要走一轮。**「快检作业」（只跑 KMP 编译 + 单测 + 静态门禁，不出包）**是唯一的缓解手段。若不设这道分层，纯 UI 改动也要等 10 分钟，迭代节奏会被拖垮。
- **缓解**：
  1. **三档触发分层**（快检 / PR / 手动出包 / tag），§8.1；
  2. **CI 里加 `commonMain` 可编译性专项检查**（3 分钟级），挡住最常见的移植失败；
  3. **缓存 K/N 工具链与 CocoaPods**（K/N 工具链是最大头，命中缓存后出包时间显著缩短）；
  4. **用 XcodeGen + `project.yml`**：工程师本地没有 Xcode 也能改工程结构，不需要「有 Mac 才能改配置」；
  5. 接受一个现实：**iOS 版的开发节奏天然比 Android 慢**。排期上不要按 Android 的速度估。

### R5 · ML Kit iOS 仅 CocoaPods 分发，与 KMP 集成冲突；且可能引入遥测血统，威胁零网络承诺 —— **中高**

- **概率**：中高。ML Kit iOS 明确「仅 CocoaPods 分发」，与 KMP 的 `cocoapods` 插件集成是已知的高复杂区；遥测血统的问题在 Android 版已确凿出现过（`datatransport` 传递引入 `INTERNET`）。
- **影响**：**高**。既有工程风险（集成卡住、CI 失败），也有**信任风险**（一旦 iOS 版真的带了联网行为，「照片不上传」这个产品的立身之本就没了，且用户比 Android 更容易发现并公开）。
- **缓解**：
  1. **首选 Vision 方案**（§7.2）：不引入任何第三方 SDK，从根上消解冲突与遥测风险；
  2. 若采用 ML Kit：**KMP 走 Run Script 直调 Gradle**（§8.2 方案 B），**ML Kit 只在表现层使用、不进 `commonMain`**，两者解耦；
  3. **出包二进制扫描**（§7.2 防线 4）作为强制门禁，命中 `GoogleDataTransport` / `CFNetwork` 等符号即失败；
  4. 若 Vision 的场景分类精度不够（对照 Android 的标签体系实测），再评估 ML Kit，**且必须先过二进制扫描**；
  5. 无论哪条路，**不得为了「AI 更准」引入任何需要下载模型的联网方案**（Firebase ML 的动态下载模型直接违反零网络）。

### R6 · Kotlin/Native 编译比 JVM 慢，拖慢整个迭代循环 —— **中高**

- **概率**：确定发生（K/N 是 AOT 编译，无 JVM 的增量优势）。
- **影响**：中高。不直接影响功能，但叠加 R4（必须走 CI）后，**编译耗时成为迭代延迟的主要来源**。
- **缓解**：
  1. **Gradle 构建缓存 + K/N 工具链缓存**；
  2. **快检作业只编 `commonMain`**（不编 `iosMain` 的完整 framework），KMP 逻辑改动可在数分钟内验证；
  3. **`commonMain` 保持零平台依赖** —— 越少 `expect/actual`，KMP 编译越快、跨语言边界越少；
  4. **Kotlin 代码与 Swift 代码的职责边界划死**：业务逻辑全在 Kotlin，Swift 只做 UI 与平台调用，**避免把逻辑写进 Swift**（那会导致每次改逻辑都要走完整 iOS 编译）。

### R7 · Kotlin runtime 静态链接导致 iOS 包体积显著大于 Android 版（40.3MB） —— **中**

- **概率**：确定发生。Kotlin/Native 的 runtime 与标准库随二进制静态链接。
- **影响**：中。不影响功能，但影响 CI 产物体积、侧载安装耗时、以及用户对「一个拍照 App 为什么这么大」的观感。
- **缓解**：
  1. **Release 构建开启 Kotlin/Native 的符号剥离与优化**（`-Xbackend-threads` 之外还要开 strip）；
  2. **不打包无用架构**：只出 `arm64`（真机），不出模拟器切片；
  3. **端侧 AI 组件用 Vision 而非 ML Kit**（ML Kit 的模型与运行时是实打实的体积）；
  4. **CI 出包后记录产物体积并设阈值告警**（例如超过某值即提示），与 Android 版 40.3MB / GROWTH-PRD §10 的 45MB 上限形成同口径对照；
  5. 资源侧沿用 Android 的自绘优先原则（直方图、曲线、引导线全部自绘，不引图表/图形库）。

### R8 · Swift / Kotlin 跨语言调试体验弱于纯 Swift，缺陷定位成本高 —— **中**

- **概率**：确定发生（KMP 场景的固有代价）。
- **影响**：中。**但这个风险被 R4 放大** —— 纯 Swift 项目至少能在本地 Xcode 断点调试，而本项目连 Xcode 都没有。
- **缓解**：
  1. **逻辑全部收敛到 Kotlin**（可脱离 Xcode 单测），**Swift 侧只做薄 UI**；
  2. **`commonMain` 逻辑靠 `commonTest` 单测覆盖**（复用 Android 版已有的 JVM 单测资产，迁到 KMP 后仍可在任意平台跑）；
  3. **CI 加「commonMain 可编译性检查」**，把跨语言编译错误挡在出包之前；
  4. 用 `@ObjCName` / `@HiddenFromObjC` 控制 Swift 侧的可见符号面，**减少需要跨语言理解的接口数量**；
  5. 崩溃符号化：确认 Kotlin 侧的 dSYM 能在 CI 产出中保留，否则线上崩溃无法定位。

### R9 · Live Activity / Widget 的系统刷新预算限制，导致数据显示陈旧或更新被系统丢弃 —— **中**

- **概率**：中。预算机制确定存在，但具体行为受系统调度影响。
- **影响**：中。表现是「小组件上的数字不更新」—— 不致命，但会让用户觉得数据不可信。
- **缓解**：
  1. **主 App 在前台时的重载不计入 Widget 预算**（Apple 已明确）→ 把刷新时机锚定在「用户刚做完动作、App 还在前台」这一刻，这是最划算的时机；
  2. **预排期时间线**（entry 间隔 ≥ 5 分钟），不靠频繁 reload；
  3. **只重载受影响的 kind**，不用 `reloadAllTimelines()`；
  4. Live Activity 侧**仅在值变化时更新**，并接受「后台冻结」这一 iOS 平台事实，在 UI 上不承诺「后台实时」；
  5. **产品预期写清**：Live Activity 是「拍照时的随身条」，不是「后台监控器」。

### R10 · `TechniqueDex.kt` 等纯 Kotlin 文件含 JVM 专属 API，`commonMain` 编译失败 —— **中低（范围已查清）**

- **概率**：确定发生，但**范围已完全查清**（不是未知风险）。
- **影响**：中低。影响首批开工的前 1–2 天。
- **缓解**：
  1. **已实测**：28 个零 Android 依赖的纯 Kotlin 文件中，**仅 `TechniqueDex.kt` 使用了 `java.text.SimpleDateFormat` / `java.util.Date` / `java.util.Locale`**；其余（含 `ScoreHistory` 的手写 JSON、`ScoreBoard`、`PromptEngine`、两大技法库）**无 JVM 专属依赖**；
  2. 改造方案二选一：改用 `kotlinx-datetime`，或用 `expect/actual` 拆出日期格式化（iOS 用 `DateFormatter`，Android 保留现状）；
  3. **开工第一步就做这个改造**，作为「KMP 可行性验证」的最小实验 —— 它能低成本地证明整个移植路线成立。

### R11 · iOS 侧「不做无障碍适配」的决策与 iOS 平台期待存在张力 —— **低**

- **概率**：低（不上架，无审核风险）。
- **影响**：低。仅为长期产品观感上的隐患。
- **缓解**：
  1. 已在 §6.5 显式声明并与 Android 版保持一致（GROWTH-PRD §9 反向清单已有记录）；
  2. 因为**不上架、不公开分发**，无合规代价；
  3. 若将来真的要上架或公开分发，**这是一项必须重新评估的决策**（届时补 VoiceOver 的工作量不小，且与现有 UI 布局耦合）。

### R12 · 出包链路过长导致「代码写完但始终没在真机上跑过」—— **中低**

- **概率**：中低。
- **影响**：中低，但一旦发生就是**全量返工**（所有未经真机验证的改动一起废掉）。
- **缓解**：
  1. **分批交付的第一批必须尽早出包**，哪怕只实现了 P0 的一部分；
  2. 首批目标：**先出「能装上、能取景、能出一个参考分」的壳**，把 CI + 侧载 + 真机取景这条**全链路先跑通一遍**，再往上堆功能；
  3. 每批交付后**侧载自用 3–7 天**（含一次完整的 7 天重签周期，验证 R3 的实际表现）。

---

## 10. 待确认问题（需 clover 拍板）

> 只列真正会改变工作内容的问题。按「是否阻塞第一批开工」排序。

| # | 问题 | 我的建议 | 阻塞性 |
|---|---|---|---|
| **Q1** | **端侧 AI 方案选型：Apple Vision（推荐）还是 ML Kit iOS？**<br>背景：Android 版用的是 ML Kit（图像标注 + 人脸检测）。iOS 版用 Apple Vision 可以做到零第三方 SDK、彻底避开 CocoaPods 与 KMP 的集成冲突、体积更小、且**从根上消除「引入联网遥测血统」的风险**；代价是分类标签体系与 Android 不完全一致，场景分类精度可能略降。 | **推荐 Vision**。理由是这个产品的核心资产是「零网络」这个承诺，而不是「AI 标签多两个」。Android 版的 `SceneClassifier` 本来就有不依赖 AI 的规则路径，AI 只是增强。若实测 Vision 精度不够，再单独评估 ML Kit，且必须先过二进制扫描 | **阻塞 I2 / J7 的技术选型**（不阻塞开工，但阻塞实现选型定稿） |
| **Q2** | **侧载工具主用哪个：SideStore（推荐）/ AltStore / Sideloadly？**<br>背景：三个工具对「App Extension 是否保留」「App Group 是否保留」「7 天刷新是否需要电脑常驻」的处理不同，直接决定第二批的 5 项 P1 功能能不能做。 | **主用 SideStore**（纯手机端刷新，重签成本最低）。同时在 SideStore / AltStore / Sideloadly 上各跑一次 Spike #1，把结论写进 QA 报告 | **不阻塞第一批**（第一批不含扩展），但**必须在第一批开工第一周内启动 Spike #1** |
| **Q3** | **iOS 端 AI 是否降级为「规则优先 + AI 增强」的口径？**<br>背景：iOS 端若用 Vision，图像标签能力弱于 ML Kit，场景判定准确率会略降。这会影响 `PromptEngine` 里依赖场景的文案命中范围。 | **接受降级**，并在 App 内与隐私政策里如实说明「场景识别为端侧启发式判断，不保证准确」。**不做「假装和 Android 一样」的宣传** | 不阻塞 |
| **Q4** | **是否接受「第一批不含任何 App Extension」这个节奏？**<br>背景：Live Activity 是 iOS 版最亮眼的能力，但它依赖扩展。第一批不做，iOS 版首发就会比 Android 晚一批。 | **接受**。理由：扩展存活性未知（Spike #1 未做），把不确定的依赖放进「能装上跑起来」的 MVP 里，等于用首发赌一个未验证假设。而 iOS 版的 P0 部分（相机 + 四维 + 任务卡 + 83 技法 + 图鉴 + 隐私门）**已经是一个完整可用的产品**，与 Android v3.1.0 对等 | **需拍板**（这是节奏取舍，不是技术问题） |
| **Q5** | **iOS 端的「导出进度」落地到哪个优先级？**<br>背景：J6（导出/导入）在 PRD 里是 P1。但如果 clover 认为 7 天重签丢进度这件事的实际发生概率高于我的判断，应该提到 P0。 | **保持 P1**，但 **I14（关于页的重签说明 + 导出入口骨架）必须在 P0**。即：第一次用的人一定会看到「重签会丢数据，先导出」这句话 | 不阻塞 |

**除以上 5 条外，无其他阻塞项。** 其余决策（技术选型细节、阈值标定方法、CI 流水线形态等）均可由架构师在既有约束下自主判断。

---

## 附录 A · 口径铁律速查（iOS 版必须逐字继承）

| 项 | 口径 |
|---|---|
| 分数名称 | 只说「**参考分**」。禁止「正确 / 错误」「专业评测」「评分等级」 |
| 未测全时 | 总分显示「**参考分 --**」（UI 与水印一致），**不烧假数字** |
| 建议文案 | 用「**照拨**」「**建议值**」 |
| 等级词 | 沿用 Android 现有表述（如「良好」），不新增评价性词汇 |
| 隐私表述 | 「**离线实时构图教练，照片不上传**」；**只写本地**、**不联网** |
| 零网络表述 | iOS 版必须用 §7.2 的四道防线自证，**不得复制 Android 的「系统层面不具备联网能力」**（iOS 无此机制） |
| 隐私政策 | 保留 RELEASE-PRD 的整体骨架与措辞风格，但**改写三处**（网络自证 / 删除录屏与悬浮窗章节 / 存储权限换成相册仅添加） |
| 品牌 | 主标题「手机里的摄影陪练」+ 副标题「拍之前就知道好不好 · 离线实时构图教练，照片不上传」 |
| 无障碍 | **明确不做**。48dp 热区、VoiceOver、Dynamic Type、reduce motion 一律不加 |
| 参数 | **不删参数，只降级到 L3**（底部可上拉面板）。只建议不写入白平衡 |

---

## 附录 B · 本文数字出处表

> 规则：本文出现的所有数字，要么能在代码里定位到，要么标注为「设计值 / 平台文档 / 需实测」。

| 数字 | 出处 | 类型 |
|---|---|---|
| 四维满分 25 / 30 / 25 / 20（合计 100） | `analysis/ScoreBoard.kt` 的 `MAX_LEVEL=25` / `MAX_EXPOSURE=30` / `MAX_COMPOSITION=25` / `MAX_CLARITY=20` | 代码常量 |
| 83 条技法 | `PhotographyBook.legacy` 39 条 + `TechniqueBookV15.list` 44 条 = 83；`TechniqueDex.all()`；id 唯一性由 `DexStoreTest.idsAreUniqueAcrossSources` 守护 | 代码常量 |
| 15 个分类 | `knowledge/Technique.kt` 的 `enum class TechCategory` 共 15 个值 | 代码常量 |
| 8 条运镜技法 / 8 个视频意图 | `knowledge/VideoMovePlanner.kt` 的 8 个 `TECH_MOVE_*` 与 8 个 `POOLS`；`video/VideoRecommender.kt` 的 8 个场景分支（walk/talk/sport/concert/cinema/slowmo/timelapse/scan） | 代码常量 |
| 分数历史 500 条上限 | `growth/ScoreHistory.kt` 的 `MAX_RECORDS = 500` | 代码常量 |
| 落盘路径 `filesDir/score_history.json` | `growth/ScoreHistoryStore.kt` 的 `FILE_NAME` | 代码常量 |
| 28 个纯 Kotlin 文件 / 8,075 行 | **本次实测**（逐文件扫描 `import android` / `import androidx` 零命中后按 `wc -l` 统计）。注：任务说明中的「约 7,748 行」与实测略有出入，**以实测为准** | 实测 |
| 7 天重签 / 3 个活跃 App / 10 个 App ID / 7 天 | Apple 免费账号个人团队的侧载限制；7 天为 provisioning profile 有效期 | 平台规则 |
| Live Activity 活跃 8 小时 / 结束后锁屏保留 4 小时 / 锁屏总时长最长 12 小时 / 数据 4KB 上限 | Apple《Displaying live data with Live Activities》官方文档 | 平台文档 |
| iOS 16.1（Live Activity 起始版本）/ 16.0（锁屏小组件）/ 17.0（交互式 Live Activity、交互式小组件）/ 18.0（控制中心控件） | Apple 平台版本要求 | 平台文档 |
| Widget 每日 40–70 次刷新预算 / 约 15–60 分钟一次 / 24 小时滚动窗口 / timeline entry 间隔不小于 5 分钟 / App 前台重载不计入预算 | Apple《Keeping a widget up to date》官方文档 | 平台文档 |
| ML Kit iOS 要求 Xcode 13.2.1+ / iOS 15.5+ / 仅 64 位 / 仅 CocoaPods 分发 | Google ML Kit 官方迁移文档 | 平台文档 |
| iOS 部署目标 iOS 16.0 | **设计值**：由「ML Kit 需 15.5+」「锁屏小组件需 16.0+」「Live Activity 需 16.1+」三项取交集决定 | 设计值 |
| Android v3.1.0 / versionCode 31 / 包名 `com.shottuner.app` / minSdk 24 / targetSdk 35 | `app/build.gradle.kts` | 代码常量 |
| Android 包体 40.3MB | GROWTH-PRD §10 | 既有文档 |
| 品牌色 `#0E1113` / `#F2F6F8` / `#7FE3C0` / `#FFB74D` / `#9FC6E8` / `#FF8A65` | `app/src/main/res/values/colors.xml` 的 `st_*` | 代码常量 |
| Android 版视频页**无真实录制** | 全仓 `MediaRecorder` / `CameraX.VideoCapture` / `setVideoSource` **零命中**；`camera/CameraEngine.kt` 仅有 `takePhoto` | 实测 |
| Android 版白平衡**只建议不写入** | `docs/TEAM.md` 技术负责人裁决 | 既有文档 |
| Android 版**不做无障碍** | GROWTH-PRD §9 反向清单 | 既有文档 |
| 现有可解锁 tipId 仅 7 条 | `growth/TechniqueDex.kt` 类注释明确指出：`ScoreBoard` 目前只产得出 7 个 tipId，「首页写『已解锁 12 / 83』在今天的工程上是走不到的」 | **代码注释（既有已知问题）** |
| 端侧 AI 三方案（Vision / ML Kit / 降级规则） | **设计建议**，基于 §7.2 的四条防线推导 | 设计值，待 Q1 拍板 |
| 四周保守计划 / 分批边界 / Spike #1 三项验证 | **本文设计** | 设计值 |

> **⚠️ 附注（需要在第一批开工时处理，与本文档无关但会影响验收预期）**：Android 版当前 `ScoreBoard` 只能产出 7 个可解锁 tipId，而首页/图鉴按 83 条展示进度。这意味着**「已解锁 N / 83」在现有工程上解锁速度远低于设计预期**。iOS 版直接复用同一套数据，**这个既有问题会原样继承到 iOS**。本 PRD 不修改它（不改 Android 行为），但**建议 QA 在 iOS 版验收时单独记录该现象**，并在图鉴文案上避免暗示「很快就能解锁很多」（例如不显示「还差 N 条解锁」这类进度诱导文案，避免制造无法兑现的预期）。
