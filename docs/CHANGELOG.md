# CHANGELOG

## 3.2.0 (versionCode 32) - 2026-10-29 - 「横屏可用 + 数值口径修复」

- **悬浮教练卡跟随物理方向**：横屏拍照时提示卡不再侧躺 —— 之前 MainActivity 锁死 portrait，
  而 Android 的方向请求是 per-display 的，**连带把同一 display 上的悬浮窗也锁成了竖屏**，
  系统相机转横屏而提示卡没转，用户根本读不了。现在悬浮窗自管方向：
  读 `Display.getRotation()`（物理旋转，不受方向锁影响）+ `Display.getRealSize()`（物理尺寸），
  横屏时内容预旋转、落位改居中、拖动增量做坐标系换算；**竖屏路径逐像素等价，零行为变化**
- **录屏画布等比例跟随物理方向**：原来画布尺寸取 `resources.displayMetrics`（受方向锁影响，
  永远是竖屏值），横屏时画面被塞进竖画布**留黑边**，黑边拉低平均亮度 → **夜景/日落判断跟着错**。
  现在按物理尺寸等比例缩放（横屏时 720×332，与屏幕同形，不留黑边），转屏时重建录屏管线
- **数值口径修复（用户可见）**：
  - `NumFmt` 舍入顺序从「先减整数部分再放大」改为「先放大再拆分」——
    旧写法会把双精度表示误差保留下来再放大 10 倍，「进光量差约 **1.2** 档」被输出成「**1.1** 档」
    （档位差一位 = 进光量差一档）。现与 `java.util.Formatter` 的 `%.Nf` 逐字一致
  - 大负数走科学计数法时不再丢负号（`-1e20` 曾输出成 `1.0E20`）
  - 负零归一（用户可见文案里不再出现「倾斜角 -0.00°」）
- **Locale 归一**：德语/法语区不再输出逗号小数点（`1,5` → `1.5`）。
  原用 `String.format` 走默认 Locale，是 **Android 版现存缺陷**，本次一并修掉
- 内部重构：算法内核迁入 `shared` KMP 公共模块（Android 与 iOS 共用同一份实现），
  单元测试迁入 `commonTest`（iOS 端复用同一份测试）

## 3.1.0 (versionCode 31) - 2026-10-20 - 「成长四件套」

- **G1 首屏任务卡**：取景时浮出**一条**当前可完成的任务 + 引导环灰→绿 + 到位震动 + 成绩卡；
  **不按快门也能得分**
- **G2 成绩水印**：成片右下角烧「光影调参 · 参考分 78 · 良好」，默认关闭、设置可开
- **G3 技法图鉴**：83 条技法的收集进度册子（有限可穷尽，是与竞品的差异化）
- **G4 分数历史**：每次拍照落盘四维分（`score_history.json`，环形 500 条），供成长曲线与首末对比

## 3.0.0 (versionCode 30) - 2026-10-20 - 「首个上架发布版」

- **包名迁移到 `com.shottuner.app`** + 正式签名（此前是 `com.shottuner.app.debug` / 测试签名）
- 阶段 1 构建基建 + 阶段 2 合规 + 阶段 3 可靠性/可用性，**QA 五组（A–E）独立验证**
- 产物按历史命名规范归档到 `dist/ShotTuner-3.0.0-release.apk`（避免误传旧包）

## 1.9.0 (versionCode 19) - 2026-10-19 - 「沉浸 / 专业两档」

解决「一屏塞太多」的终极方案：不删功能，让默认档只留必要元素。

- `tvMode` 从「模式状态显示」改为「档位切换入口」，点击在 `沉浸 / 专业` 间循环，`SharedPreferences` key `ui_preset` 持久化，默认沉浸
- 沉浸档隐藏：四维明细 `tvScoreDetail`、`histogram`、构图细节 `tvCompose`、`llMoreActions` 整行（锁定/恢复自动/复制参数/怎么拨）、俯仰刻度、主体圈与目标交点
- 沉浸档保留：场景标签、教练提示条、参考分、三格参数 + 偏差、`tvAdvice`（手怎么动的唯一出口）、快门、缩略图、四页签
- `GuideOverlayView` 新增 `showPitchGauge` / `showSubjectMarks` 开关
- `tvMode` 不再显示「本机写入中 / 参数已锁定」（职责重复），改由 `btnMode` / `btnLock` 文案承担
- 档位只改可见性，不触碰提示调度器、不重置冷却、不改参数
- drawable 色值收口：`bg_chip` / `bg_thumb` / `bg_shutter` / `ic_app_icon` 的重复色值改用 `@color/st_*`

## 1.8.0 (versionCode 18) - 2026-10-19 - 「合并五个声音」

照片页曾有 5 处同时在说「现在该怎么做」（`tvTip` / `tvDetail` / `tvCompose` / `tvFraming` / 教练槽），用户每次要扫 5 遍。本版收敛：

- `tvTip` + `tvDetail` + `tvFraming` 三行合并为 `tvAdvice`（同一个 TextView 内用 Spannable 做两级字号：主句 13sp 粗 + 副句 11sp 灰），机位信息并入副句末尾
- `llCoachSlot` 从面板内移到取景区顶部（`top_toBottomOf=topBar`），紧贴画面；**无内容时 GONE 不占空间**；`tvCoachAction` 14sp 粗 2 行 + `tvCoachReason` 11sp 暗淡 1 行
- `tvCoachTag` 改为 8dp 色点（`bg_dot.xml` + `backgroundTintList` 按优先级上色），删除 `tvCoachDots`
- 参数区建立层级：快门 23→**26sp**（唯一不可逆的量）、ISO/色温 23→**20sp**、偏差 13→11sp + 符号配色、三格之间加 1dp 竖分隔线
- 数值变化高亮：值真变了才 setText，随后 200ms `alpha 0.6→1`（只动 alpha，避免 measure/layout 抖动）
- 按钮收敛：`btnMode` 与新增 `btnMore`（更多 ⌄）各占 50%，4 个低频按钮收进 `llMoreActions` 展开行，默认收起
- `panel` 加 `WindowInsetsCompat` bottom padding，系统导航栏滑出不压面板

## 1.7.1 (versionCode 17) - 2026-10-19 - 「UI 修复版」

QA 对 1.7.0 的独立验证查出 4 个 P1，本版全部回收（功能未变，纯修）：

- **P1-a 面板可能塌成 0 高**：`panel` 只有 `constraintBottom` 单锚点 + `0dp` + `percent` → 补 `constraintTop_toBottomOf="@id/topBar"`，并补 `constraintVertical_bias="1"`（双锚点后固定高度子 View 默认居中，不压底会从"贴着快门条"漂到屏幕中间）
- **P1-b 取景区不达标**：`percent 0.52 → 0.45`，360×640dp 小屏取景区从 30.8% 提到 38.6%（三档均 ≥ 验收线 34%）
- **P1-c 两个快门口径不一致**：屏幕快门在录像/功能/技法页也能拍，音量键却要求照片页 → 统一为「都只在照片页」，`applyShutterAvailability()` 挂在 `selectTab` + 拍照成功/失败三处回调（只挂 `selectTab` 会在拍照中切页后被点亮）
- **P1-d 复盘卡推走整页**：卡从内容容器 index 0 移到 index 2（页签之后），拍照不再把页签下推 100dp 再弹回
- P2-a 硬编码色值清零：`MainActivity` 18 处 + `GuideOverlayView`/`HistogramView`/`CoachOverlay` 13 处全部收进 `colors.xml`（`Color.parseColor` 与布局十六进制均零命中）
- P2-c 复盘卡显示时 `scrollTo(0,0)`，面板滚下去也看得见
- P2-b `bg_pill` 88% → 92%（纯白画面下 11sp 次要文本 4.46:1 → 达标）
- P2-f 网格黑底 40% → 50%（极亮背景下反差 1.71:1 → 1.90:1）
- P2-g `scaledDensity` deprecated → `TypedValue.applyDimension`

## 1.7.0 (versionCode 16) - 2026-10-19 - 「界面重构 P0」

依据 `docs/UI-v2-design.md`（对照 Google Material 3 Expressive 相机改版、vivo 模块化、相机 UX 十条反模式）做的第一批优化：

- **快门移出滚动面板**：新增底部固定 `llControlBar`，快门 56→72dp，任何时候都不会被滚走
- **面板高度改百分比**：`600dp` 硬编码 → `0.52 + min240/max560`，修掉「640dp 小屏上面板占 94%、取景只剩一条缝」
- **音量键快门**：`dispatchKeyEvent` 只拦 `VOLUME_DOWN`（`repeatCount==0`），功能页带开关，关掉后按键行为与未实现时完全一致
- **强光可读性**：新建 `colors.xml`；正文灰 `#5F6E77`(3.5:1) → `#7A8B96`(5.1:1)；`bg_panel` 80%→95%、`bg_pill` 70%→88%（透光才是强光下"有时清楚有时糊"的根因）
- **拍照反馈**：去掉「必须点确认」的 AlertDialog 与成功 toast，改为 90ms 白闪 + 180ms 缩略图飞入 + 面板顶部 5 秒自动收起的复盘卡；仅保存失败时 toast
- **构图网格线功能性修复**：白 18% 单描边在亮天空下不可见 → 黑 40% 底 + 白 35% 面的双描边，交点同步处理
- **删除 `installFramingLine()`**：原来靠「取父容器再插 view」实现，布局一改就静默失效；`tvFraming` 改为静态声明，机位信息不再动态插入

## 1.6.0 (versionCode 15) - 2026-10-17 - 「内建拍照 + 悬浮卡增强」

- 内建拍照：`CameraEngine` 新增 `ImageCapture` + `takePhoto()`，手动 ISO/快门通过 `Camera2Interop` 同步作用于拍照（预览手动档则成片同档，不再两张皮）
- 照片页新增圆形快门按钮（点按拍照、长按分享），拍照存系统相册 `Pictures/ShotTuner`，拍后弹四维参考分复盘
- 悬浮卡增强：建议 2→4 行、构图建议 2→3 行、新增参考分显示、卡片限宽 340dp 防窄屏溢出
- 新增 `FileProvider`（file_paths.xml）+ `WRITE_EXTERNAL_STORAGE` 权限，相册写入失败回退到缓存分享

## 1.5.0 (versionCode 13) - 2026-10-17 - 「技巧教练」

一次版本内的六个工作包（此前被误记成 versionCode 6/7/8/9/10/11 六行，实为同一版本）：

- P0-1：新增 `device/OrientationTracker.kt`，TYPE_ROTATION_VECTOR → pitch/roll，10Hz 输出 + 低通滤波
- P0-2：`GuideOverlayView` 叠加俯仰角刻度与「腰以下 / 45° / 90°」标记
- P0-3：新增纯 Kotlin `coach/FramingCoach.kt`（T01/T02/T04/T06）与 JVM 单测
- P0-4：`AiVisionResult` 补 faceCenterX/Y、faceBox、headEulerY，人脸框归一化到 0..1
- P0-5：逆光三步按序提示、夜景倒计时提示、两条专业模式 KNOW 卡
- P0-6：照片页新增一行「机位」提示，输入人脸 + 主体框 + 等效焦距

同版本内追加（技巧教练主体）：

- 技法库 39 → 83 条：新增 44 条（自拍 5 / 运镜 8 / 参数 3 / 避坑 4 / 人像 10 / 街头 2 / 风光 4 / 夜景 3 / 用光 3 / 食物 2），`TechCategory` 11 → 15
- 提示链路：`PromptEngine` 11 条规则 + `PromptScheduler` 状态机 + 教练槽 UI（500ms 独立循环，`elapsedRealtime`）
- 四维参考分（水平 25 / 曝光 30 / 构图 25 / 清晰度 20），未测维度显示「未测」而不计 0
- 功能页新增「提示密度」三档选择器（安静 / 标准 / 话多），`SharedPreferences` 持久化

## 1.4.0 - 2026-10-17 - 补录：端侧 AI 场景识别、技法库与 39 条摄影技法（versionCode 5）
## 1.3.0 - 2026-10-17 - 补录：构图分析、取景引导层与系统相机悬浮教练
## 1.2.0 - 2026-10-17 - 补录：功能顾问、能力清单与品牌模式入口
## 1.1.0 - 2026-10-17 - 补录：录像方案、帧率与 180 度快门建议
## 1.0.0 - 2026-10-17 - 补录：参数推荐、直方图与实时测光
