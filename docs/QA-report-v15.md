# QA 独立验证报告 · ShotTuner v1.5.0

> 验证人：严过关（QA）｜ 日期：2026-10-17
> 复核对象：v1.4.0 → v1.5.0 增量（44 条技法 / 提示调度器 / 教练提示槽；四维参考分仅核接线，见 §1 范围声明）
> 本轮定位：**静态与数据层独立验证**，不跑构建、不改源码。APK 与 108 用例的实跑验证排在 Round 2。
> 本报告**独立取证**，不复述工程师自测结论。所有结论均附文件:行号或脚本输出。

---

## 0. 取证快照与前置偏差（重要）

本轮取证期间工程师的改动**在 17:34 落盘**（R09/R11 优先级 + 生产密度 + 测试同步）。我在落盘前后各取了一次证，下表是**落盘后**的最终状态，本报告全部结论以此为准：

| 项 | 落盘后的实际值 | 出处 |
|---|---|---|
| `versionCode` | **12**（不是任务书里说的 6） | `app/build.gradle.kts:16` |
| `dist/ShotTuner-1.5.0-release.apk` | 40,272,193 B ≈ **38.40 MiB**，`versionCode='12'` | `aapt2 dump badging` |
| R09 优先级 | `CONTEXT`（原 GUIDE） | `knowledge/PromptEngine.kt:309` |
| R11 优先级 | `CONTEXT`（原 GUIDE） | `knowledge/PromptEngine.kt:356` |
| 生产提示密度 | `STANDARD`（原 CHATTER） | `MainActivity.kt:206` |
| `@Test` 总数 | **108**（与声称一致） | 8 个测试类求和 |

**结论偏差更正**：任务书里「engineer 声称 versionCode=6」已被产物推翻 —— 当前 `dist` 里的 release APK 就是 12。CHANGELOG 仍写着 1.5.0 (versionCode 6…11) 六行，属文档未同步（见 P2-3）。

**必须一起读的因果链**：R09/R11 升 CONTEXT + 密度回 STANDARD 这两个改动是配套的（工程师在 `MainActivity.kt:194-205` 写了完整推导，逻辑自洽）。但它们**没有同步调整 `PromptScheduler.show()` 的记账条件**，由此引出本报告的两条 P1（§3 P1-1、P1-2）。这不是"改动写错了"，是"改动只做了一半"。


---

## 1. 验证范围与方法

**做了**（纯静态 + 数据层，零构建、零改动项目文件）：

1. 写了一个项目外的 Python 比对器（`D:/tmp/qa_check_v15.py`，用完已删），把 `docs/TECHNIQUE-v15.md` 与 `TechniqueBookV15.kt` 都解析成结构化记录，做**逐字段严格串比较**（不是模糊匹配，全角/半角标点差一个字符也会红）。
2. **反向验证比对器本身**：故意注入 4 类转写失真（params 串键、trigger 数值错位、把 `why` 塞进 `check`、删掉 1 条），确认比对器能精确命中且不误报。结果见 §2.1。
3. 用 grep + 常量枚举法计算「引擎可能吐出的 tipId 全集」，与「禁推集合」求交，判断测试是否可能假阳性。
4. 用 `aapt2 dump badging` 与 APK Signing Block magic 静态读产物（不涉及 gradle）。
5. 通读 4 个提示层测试类，逐条判断断言是否具备"可失败性"。

**范围声明**：「四维参考分（水平度 25 / 曝光 30 / 构图 25 / 清晰度 20）」不在本轮 6 项清单内，我只核了它的 UI 落点存在且接线正确（`MainActivity.kt:739` → `tvScoreDetail`，布局 `activity_main.xml:265`），**没有校验权重求和、未测维度的降级口径、以及分数与实际画面的相关性**。若要覆盖，请派给下一轮或单列一条任务。

**没做**（本轮明确排除，见 §4 局限）：不跑 gradle、不跑 JVM 测试、不装机、不启动模拟器。

---

## 2. 逐项结论

### 2.1 44 条数据与文档的逐字段一致性 —— ✅ 通过

| 检查点 | 结果 | 证据 |
|---|---|---|
| 条数 | 44 = 44 | Markdown `^### ` 计数 44；Kotlin `Technique(` 计数 44 |
| id 集合 | **完全一致，不多不少不改名** | `diff` 两集合输出为空 |
| 重复 id | 0 | 集合大小 = 列表大小 |
| 11 个构造字段逐一比对 | **0 处不一致** | 比对器输出「字段不一致：0 处」 |
| 字数闸门（title 8–16 / oneLiner 4–20 / whenTo 40–80 / why 60–120 / steps 3–6 / check 20–70 / mistakes 非空 / params 非空） | **0 违规** | 比对器输出「闸门违规数 = 0」 |
| 产品自报字数是否可信 | 44 条 × 5 字段的 `（N 字）` 标注**全部与实测相符** | 「MD 自报字数 vs 实际：异常数 = 0」 |
| 字数标注是否漏进 UI | 未漏（Kotlin 侧无 `字）` 残留） | 严格串比较通过 + `TechniqueBookV15Test.kt:97` 亦有断言 |
| 分类分布 | portrait 10 / move 8 / selfie 5 / pitfall 4 / landscape 4 / params 3 / night 3 / light 3 / street 2 / food 2 = 44 | 比对器统计，与 `TechniqueBookV15.kt:19-21` 及产品口径一致 |

**关于「值搬到错误的键」这个重点怀疑项**（`params` 是 `List<Pair>`、`trigger` 是构造参数），我做了变异测试自检，确认比对器真的能抓到而不是"看起来没报错"：

```
原始:                      0 处不一致
mut1(params 串键):        [('selfie_arm_extended', 'params')]
mut2(trigger 数值错位):   [('selfie_arm_extended', 'trigger')]
mut3(why 塞进 check):     [('selfie_arm_extended', 'check')]
mut4(删掉 1 条):          条数 = 43
```

比对器能精确命中单点失真、不产生连带误报。**据此判定：44 条转写在字段级别是干净的，没有串行/串位/丢字段。**

人工抽查 12 条（`selfie_side_light` / `move_orbit_30deg` / `params_white_balance_mood` / `pitfall_auto_everything` / `portrait_group_low_wide` / `night_protect_highlights` / `landscape_ultrawide_center` / `light_avoid_noon` / `food_tele_compress` / `street_vertical_lines` / `night_slow_shutter` / `move_no_whip_pan`）的 `params` 键值对与 `trigger` 参数，肉眼逐字对齐，无异常。

> 口径说明：`Technique` 构造函数共 **11** 个字段（`id/title/category/oneLiner/whenTo/steps/params/why/mistakes/check/trigger`），任务书说的"12 个字段"应是把 Markdown 侧的 11 个键算多了一个。11 个键在 44 条里全部非空、全部一一对应。

---

### 2.2 三条降级条目是否真的进不了推送池 —— ✅ 通过

`BROWSE_ONLY_IDS` 定义在 `knowledge/TechniqueBookV15.kt:39-43`，成员为 `night_slow_shutter` / `move_no_whip_pan` / `pitfall_beauty_overdo`，三条都在技法库中真实存在（:1109 / :326 / :542）。

**下游是否真的读它（不是定义了没人用）**：读了，且只有一个读点 —— `knowledge/TechniqueMatcher.kt:40` 取出、`:43` 无条件 `return@filter false`：

```kotlin
// TechniqueMatcher.kt:40-43
val browseOnly = TechniqueBookV15.BROWSE_ONLY_IDS
val selfie = TechniqueBookV15.SELFIE_IDS
return TechniqueBookV15.combined.filter { tech ->
    if (tech.id in selfie || tech.id in browseOnly) return@filter false
```

这一句在 `scenes`/`category` 任何判断之前，属于"无条件出局"，不易被后续条件绕过。

**三条独立推送路径逐个查证**：

| 推送路径 | 检查结果 | 证据 |
|---|---|---|
| `PromptEngine` 规则表 | 三条 id 一次都没出现 | 全库 grep：仅命中 `TechniqueBookV15.kt` 本体与 `VideoMovePlanner.kt:33` 注释 |
| `VideoMovePlanner` 运镜池 | `move_no_whip_pan` 不在池中，池里只有 7 条 `move_*` | `VideoMovePlanner.kt:38-55`，`:33-36` 有明确说明为何剔除 |
| `TechniqueMatcher.suggest()` 推荐池 | 被 `poolFor` 挡掉；`hasStats=false` 分支只取 `PhotographyBook.all` 的 `BASIC`（老库），也碰不到 | `TechniqueMatcher.kt:63-72`、`:74` |

**「仅浏览」的反向验证也成立**（不会变成"哪儿都看不到"）：`byCategory()` 走的是 `combined` 未过滤（`TechniqueMatcher.kt:96-102`），三条分别能在「夜景 / 运镜 / 避坑」分类下浏览到，与"技法页可见"的设计意图一致。

---

### 2.3 `selfie_*` 5 条是否真的不会被推送 —— ✅ 通过

前置条件复核：项目确实没有前置通路，`camera/CameraEngine.kt:106` 只有 `CameraSelector.DEFAULT_BACK_CAMERA`，且全文件不存在 `DEFAULT_FRONT_CAMERA` / `lensFacing`。所以「后摄拍人也会命中 PORTRAIT+minSkinRatio」这个风险是真实的，必须靠代码挡住。

- `SELFIE_IDS` 定义于 `TechniqueBookV15.kt:46-52`，5 条与数据中的 5 条 `selfie_*` 完全一致（无拼写偏差，`TechniqueBookV15Test.kt:142-145` 亦有断言）。
- 下游唯一读点同样是 `TechniqueMatcher.kt:41`+`:43`，与 browse-only 同一句无条件过滤。
- `PromptEngine` 规则表：grep `selfie` 在 `app/src/main/java` 只命中三处 —— `TechniqueBookV15.kt` 本体、`TechniqueMatcher.kt:41/43` 的过滤、以及 `analysis/AiVision.kt:273/305` 的**图像标签词表**（是 ML Kit 的英文标签 `selfie`，与技法 id 无关，不构成推送）。
- 运镜池：`VideoMovePlanner` 只收 `move_*`，`all { it.startsWith("move_") }` 由 `PromptEngineTest.kt:307` 守着。

---

### 2.4 UI 接入的静态风险 —— ⚠️ 基本通过（有 3 处非阻断缺陷）

**(a) 6 个新控件是否都在布局里定义** —— 全部定义，且层级正确：

| 控件 | 布局 | 说明 |
|---|---|---|
| `llCoachSlot` | `activity_main.xml:154` | 初始 `visibility="gone"`（:162） |
| `tvCoachTag` | `:172` | `llCoachSlot` 的后代 |
| `tvCoachAction` | `:180` | 同一行的 `weight=1` 主文案 |
| `tvCoachDots` | `:191` | 行尾"还有 N 条" |
| `tvCoachReason` | `:201` | 第二行副文案 |
| `tvScoreDetail` | `:265` | 四维参考分表格区 |

子控件全部是 `llCoachSlot` 的后代，因此 `renderCoachSlot` 里对父容器设 `GONE`（`MainActivity.kt:832`）能整体隐藏，不会出现"卡片藏了字还在"的孤儿控件。

**(b) `binding.*` 引用与布局 id 全量对账** —— 用机器对账，`MainActivity.kt` 里出现的 48 个 `binding.xxx` 中，除 `ActivityMainBinding`（类名，误命中）与 `root`（ViewBinding 内置）外，**其余 46 个全部能在布局中找到对应 id，无一悬空**。反向缺口（布局有、代码没用）不影响编译。

**(c) viewBinding 与 findViewById 混用 / NPE** —— 无风险：`app/build.gradle.kts:53-55` 开启 `viewBinding = true`，`MainActivity.kt` 内 `findViewById` 出现次数为 **0**，全部走 `binding`（`onCreate` 里 `ActivityMainBinding.inflate`，:182-183）。不存在混用，也就没有 `findViewById` 返回 null 的路径。

**(d) `postDelayed` 是否在 `onDestroy`/`onPause` 移除** —— 配对正确，不会泄漏 Activity：

- 循环本体：`coachRunnable` 自我续期（`MainActivity.kt:787-793`），节拍 500ms。
- 停止：`stopCoachLoop()`（:781-785）里 `coachHandler.removeCallbacks(coachRunnable)` + `pause(true)`。
- `onPause` → `stopCoachLoop()`（:1217）；`onDestroy` → `coachHandler.removeCallbacksAndMessages(null)` + `coachLoopRunning = false`（:1225-1226）。
- 另有 `if (isFinishing || isDestroyed) return`（:796）与 `coachLoopRunning` 双保险，即使某一帧处在销毁窗口也不会再 tick。

**(e) `LevelSensor` 注册/反注册是否配对** —— 配对，且 `start()`/`stop()` 自身幂等（`running` 标志位拦住重复注册，`sensors/LevelSensor.kt:51/59`）。`onResume`→`start()`（:1209）、`onPause`→`stop()`（:1216）、`onDestroy`→`stop()`（:1224），重复调用 `stop()` 安全。

**以下 3 处是通过审查发现的实际缺陷（非阻断，详见 §3）**：

- P2-4：`setMuted()` 全项目**零调用**，静音功能未接线；且一旦将来接线，`PromptScheduler.kt:173-177` 的 `changed = current != null` 在 `current` 已被置空时恒为 `false`，UI 不会撤卡 → 静音后槽位继续显示上一句话（潜伏 bug）。
- P2-5：`resetSession()` 全项目**零调用**（死代码），与 ARCH §412「切页/退出时清会话态」不符；实际切 tab 不清冷却/黑名单。
- P2-6：`PromptCandidate.actionLabel`（`PromptEngine.kt:226` R05 的「切 2x」）在 `renderCoachSlot`（:832-856）里从未被读取 → 模型里定义了按钮文案但 UI 不渲染，该字段目前是死字段。

---

### 2.5 测试的真实性审查 —— ⚠️ 大部分是真断言，但**有 1 条是结构性假阳性**

先给结论性判断：4 个测试类 **108 个用例里没有"只断言不抛异常"的空壳** —— 全部含 `assertEquals`/`assertTrue`/`assertNull` 级别断言，且 `PromptEngineTest`/`PromptSchedulerTest` 的边界值取到了 3.1°/2.9°、0.13/0.11、799/800ms、2.999/3.001s、7 900/8 001ms 这种临界精度，属于合格水准。

**但是** `TechniqueBookV15Test.engineNeverEmitsSelfieOrBrowseOnlyTips`（:163-223）是**不可能失败的测试**，属团队要求重点排查的"假阳性"：

- 它遍历 10 080 组信号，断言"引擎从没吐过那 8 条禁推 id"。
- 问题在于 `PromptEngine` 的 output 空间是**有限常量子集**：12 个 `TIP_*` 常量 + `VideoMovePlanner` 的 7 个 `move_*`，共 **19 个 id**。我把这 19 个枚举出来与 8 条禁推集合求交：

```
引擎可能吐出的 tipId 全集（19）:
    basic_protect_highlights / landscape_horizon_level / light_white_add_black_minus
    night_handheld_static / night_portrait_no_direct_flash / pitfall_grid_on
    portrait_backlit_face_first / portrait_eyes_thirds / portrait_headroom
    portrait_wide_face / street_get_closer / food_angle_45_top
    move_elbow_tucked / move_fixed_support / move_hold_ends / move_orbit_30deg
    move_push_in_slow / move_small_steps / move_walk_not_zoom

交集：[]        ← 空集
```

**交集为空 ⇒ 无论喂什么信号，这条断言都恒为真。** 这 10 080 组网格对结论没有任何增益，`emitted > 0`（:221）只能证明"网格没退化"，不能证明"没有泄漏"。它给人的安全感是虚的。

需要说明的是：**"8 条不会泄漏"这个事实本身是成立的**（由 §2.2/§2.3 的静态论证保证），只是**这条测试无法作为证据**。若将来有人往 `PromptEngine` 加一条点名 `move_no_whip_pan` 的规则，这条测试大概率仍然绿 —— 因为 `availableTipIds` 传的是 `TechniqueBookV15.byId.keys`（全 83 条，见 `MainActivity.kt:143`），禁推 id 也在白名单里。

其余三条审查结论：
- `PromptEngineTest.missingTipIdsAreSkippedSilently`（:293-298）断言的是"过滤机制有效"，逻辑没问题，但**注释已过期**（写"阶段一技法库是空的"，实际 83 条已落地）。
- `TechniqueMatcherTest` 9 条全部断言"池子里不许出现 X"这类负向行为，其中 `reasonFor` 的两条真正区分了"分类兜底文案 vs 通用兜底文案"，是实现级断言，合格。
- 覆盖盲区之一：**没有任何一条测试防"转写失真"**。`TechniqueBookV15Test.textFieldsMeetTheSameGateAsLegacyBook`（:81-101）只校验"params/mistakes 非空"，如果哪天有人把 `params` 的键值对搬错位置，全部 108 条用例照样全绿（本报告 §2.1 的比对器就是在补这个洞）。
- 覆盖盲区之二：**R10 只在引擎层被测过，生产链路上没人管**。`PromptEngineTest.fallbackGuideNeedsIdleAndStableScene`（:286-291）直接调 `PromptEngine.evaluate`，绕过了密度过滤；而密度过滤在 `PromptScheduler.densityAllows`（:291-295）。也就是说"R10 在生产档位下到底会不会出现"这件事，**108 个用例里零覆盖**。这条盲区在本次改动后变成了实际问题（见 P1-2）。

---

### 2.6 一致性交叉检查 —— ✅ 通过

| 检查点 | 结果 | 证据 |
|---|---|---|
| `PhotographyBook.all` 是否 83 条 | 是 | `legacy` 39 条（`PhotographyBook.kt:25`）+ `TechniqueBookV15.list` 44 条，`all = legacy + TechniqueBookV15.list`（:1138）；机器对账：39/44/交集 0/并集 83 |
| 是否有 id 撞车 | 无 | 老库 39 个 id 与新库 44 个 id 交集为空 |
| `byId` 是否覆盖全库 | 是 | `TechniqueBookV15.kt:1300` `combined.associateBy{it.id}`，combined = `PhotographyBook.all` |
| 新分类 label 是否中文、风格是否一致 | 是（有 1 处风格微差） | `Technique.kt:27/30/33/36`：`SELFIE("自拍")` `CAMERA_MOVE("运镜")` `PARAMS("参数")` `PITFALL("避坑")`，与既有的「人像/风光/夜景/食物/街头/用光」同为 2 字短名；`避坑` 是动宾结构、其余是名词，属可接受的语义差异 |
| `reasonFor()` 4 个新分支是否有独立文案 | 是，4 条都不同，且都不是 `else` 兜底复制品 | `TechniqueMatcher.kt:170-173`：自拍=`自拍专用的距离和机位`、运镜=`运镜先解决稳，再谈好看`、参数=`按这套起手参数去拨`、避坑=`这条是习惯问题，改一次长期受益`；`else` 是 `适合当前画面的通用提醒`（:174），四者互不重复 |
| 枚举加值是否会静默落 `else` | 不会（`reasonFor` 是穷尽 `when`，漏分支编译失败） | `Technique.kt:9-11` 的注释与 `TechniqueMatcher.kt:166-175` 的实现一致 |

---

## 3. 发现的问题清单

### P0 阻断（必须先修才能发）

**无。** 数据层、推送纪律、签名结构、生命周期在海量取证下均未发现阻断级缺陷。

### P1 应修

**P1-1｜`lastGuideAt` 记账在改动后彻底失效，R09 的「8 秒节拍」变成死逻辑**
- 位置：`knowledge/PromptScheduler.kt:276`（唯一写点，条件是 `priority == GUIDE`）× `knowledge/PromptEngine.kt:313-315`（唯一读点，R09）
- 机理：`show()` 只在 `candidate.priority == PromptPriority.GUIDE` 时回填 `lastGuideAt`。改动后 R09 与 R11 都成了 CONTEXT，**规则表里只剩 R10 一条 GUIDE**；而 R10 在 STANDARD 档位下被 `densityAllows` 直接过滤（`PromptScheduler.kt:293`），且密度无 UI 可调（见 P1-2）。结果：`lastGuideAt` 永远停在初始值 `-1`，R09 的 `ready = w.lastGuideAt < 0L || …` **恒真**，这个判断再也不会拦住任何东西。
- 用户可感知度：低。因为 R09 的 tipId 由 `(now / 8000)` 取整轮转（`PromptEngine.kt:319`），8 秒换一条的观感「碰巧」被保住了；换条过快时还有调度器的 3s 最短驻留兜底（`PromptScheduler.kt:196-200`）。
- 真正的风险：这是一处**语义已经死去但代码还在跑**的机制。下一个改 R09 的人（换池子、加规则、改轮转式）会以为"有 8s 节拍保护"，实际没有；而且 `lastGuideAt` 的文档注释（`PromptEngine.kt:376`）仍在描述一个已不存在的契约。
- 复现方式（下一轮补用例）：`PromptScheduler` 在 default/STANDARD 下连续 tick 30 秒，断言 `snapshot()` 无法反映节拍；或直接断言"没有任何规则能让 `lastGuideAt` 前进"，把死逻辑钉在测试里。
- 建议：删除 `lastGuideAt`（节拍已由 `now / GUIDE_INTERVAL_MS` 轮转承担），或把它改成通用"上一次任何提示出牌时间"并让 R09 读它。

**P1-2｜`R10` 兜底教学轮播在生产环境完全不可达（整块功能是死的）**
- 位置：`MainActivity.kt:206`（`setDensity(STANDARD)`）× `PromptScheduler.kt:293`（STANDARD 过滤掉 GUIDE）× `knowledge/PromptEngine.kt:329-347`（R10 仍是 GUIDE）
- 事实链：R10 是 GUIDE → STANDARD 档位把它整条过滤 → 生产固定 STANDARD → **R10 及其配套的 `FALLBACK_TEXT`（`:118-126` 七条文案）、`fallbackPool()`（`:129-137`）、`PromptFeedback` 的 `EXPOSURE_OK` 分支里由 R10 触发的部分，一次都不会出现**。
- 而密度**没有任何切换入口**：全项目 `setDensity` 只有 `MainActivity.kt:206` 一处调用，`setMuted` 更是零调用 —— 工程师注释里写的"只有纯轮播要用户自己调到 CHATTER"（`MainActivity.kt:202`）**在 UI 上做不到**。
- 测试侧完全没感知到这件事：`PromptSchedulerTest.autoResolveWithdrawsAndBlacklistsPermanently`（:302-337）为了让合成的 GUIDE 候选不被过滤掉，显式 `setDensity(CHATTER)`。于是这条用例跑通的同时，**「生产 STANDARD 下 GUIDE 全灭」这一事实从未被任何断言表达过**。108 条用例可以全绿而 R10 已死 —— 这是 §2.5「覆盖盲区之二」的具体后果。
- 复现：真机开 App，保持画面稳定不动 30 秒以上，观察教练槽 —— 预期永远没有"白加黑减/先保高光"这类兜底口诀出现。
- 建议（三选一，需产品或架构定）：① 加密度切换 UI（槽位右侧 chip，ARCH Q4 本来也写了）；② 把 R10 也归到 CONTEXT 但加 `holdMs ≥ 15s` 之类的强约束；③ 承认 v1.5.0 不做兜底轮播，把 R10 + FALLBACK_TEXT 标 `@Deprecated` 或删掉，别留一具尸体。

**P1-3｜R11 的 `gridOn` 语义与实际信号不符，会产生"自相矛盾提示"**
- 位置：`knowledge/PromptEngine.kt:363` + `MainActivity.kt:151`、`:982-985`
- 事实：`PromptSignals.gridOn` 取的是 `guidesOn`，即**本 App 自己叠加层**的网格/水平仪开关（`MainActivity.kt:819`），初始 `true`（:151），由用户点「网格」按钮取反（:982）。R11 的文案是"把九宫格和水平仪打开"。
- 后果：用户在拍照页主动关掉网格（通常是为了看清画面）→ 下一 tick（≤500ms）教练槽立刻要求他"把九宫格打开"；他把网格再打开 → `autoResolve` 命中 → 永久拉黑。提示在跟用户的显式选择对着干。
- 同时说明：`PITFALL` 分类的定义是"命中的是设置/习惯层面的错误，不是画面层面的"（`knowledge/Technique.kt:35-36`），而 `gridOn` 只能观测到**我们自己的叠加层**、观测不到系统相机网格，属"信号不可得"。这正好撞上三条 browse-only 的降级理由（"拿不到信号就别推"，`TechniqueBookV15.kt:29-38`）—— 同一条纪律在 `pitfall_grid_on` 上没有被执行。改成 CONTEXT 之后它更容易出现了，问题因此被放大。
- 建议：要么与 browse-only 一致降级，要么明确"网格指本 App 叠加层"并把文案改成不自相矛盾的表述（如「网格关着，构图的参照线也没了」），并考虑"用户主动关闭后 60 秒内不再提"。
- 复现：真机开 App → 点「网格」关闭 → 观察教练槽文案。

### P2 建议

**P2-1｜`engineNeverEmitsSelfieOrBrowseOnlyTips` 是恒真测试，建议删除或改成结构断言**
- 位置：`app/src/test/java/com/clover/shottuner/knowledge/TechniqueBookV15Test.kt:163-223`
- 证据：§2.5，19 个可发射 id 与 8 条禁推 id 交集为空，10 080 组网格零增益。
- 建议改法：把"结构"直接断言出来 —— 例如把引擎可发射 id 收敛成一个 `PromptEngine.allEmittableTipIds()` 常量集合，断言 `allEmittableTipIds() ∩ (SELFIE_IDS ∪ BROWSE_ONLY_IDS) == ∅`。这样以后新增规则时若点名了禁推条，测试**会**红。

**P2-2｜推送白名单粒度太粗，禁推纪律只有一道闸**
- 位置：`MainActivity.kt:143` `PromptScheduler(availableTipIds = TechniqueBookV15.byId.keys)`
- 现状：白名单传的是**全 83 条**（含 5 自拍 + 3 仅浏览）。目前不泄漏，靠的是"恰好没有规则点名它们"。
- 建议：新增 `TechniqueBookV15.pushEligibleIds = byId.keys - BROWSE_ONLY_IDS - SELFIE_IDS`，生产传这个集合，形成第二道闸。

**P2-3｜CHANGELOG 与 `versionCode` 不同步**
- 位置：`docs/CHANGELOG.md:3-8`
- 现象：1.5.0 被拆成 `versionCode 6/7/8/9/10/11` 六行，而代码与产物都是 **12**；六行同版本号容易被误读成"六个版本"。
- 建议：合并为一行 `## 1.5.0 (versionCode 12)`，把 P0-1…P0-6 作为子条目列出。

**P2-4｜静音功能未接线，且接线后会 UI 不撤卡**
- 位置：`knowledge/PromptScheduler.kt:231-234`（`setMuted` 全项目零调用）、`:173-177`
- 潜在地雷：`setMuted(true)` 已把 `current` 置空但没通知 UI；下一次 tick 的 `changed = current != null` 恒为 `false`，`MainActivity.kt:799` 的 `if (decision.changed)` 不会触发 `renderCoachSlot` → 槽位停在上一句。
- 同一类问题在 `setDensity`（:236-241）上也存在（改档位后 `current` 可能被置空，同样不通知 UI）。密度缺 UI 入口已单列为 P1-2；若后续补入口，这两处 `changed` 语义必须一并修。
- 建议：`setMuted`/`setDensity` 时把 `changed` 语义外提（或让 `tick` 比对"上一次渲染的 tipId"而不是内部状态），再接线 UI。

**P2-5｜`resetSession()` 是死代码，与 ARCH「切页清会话态」不符**
- 位置：`knowledge/PromptScheduler.kt:248-257`，全项目零调用点。
- 影响：切到录像页再切回，8s 冷却/黑名单/已执行都保留 —— 可能是好事，但这是"没接线"而不是"决定保留"。建议要么在切 tab 时调用，要么在文档里写明"本版不做会话重置"并删掉方法。

**P2-6｜`actionLabel`（R05「切 2x」）未渲染**
- 位置：`knowledge/PromptEngine.kt:226` 定义，`MainActivity.kt:832-856` 从不读取。
- 建议：要么在槽位加一个小按钮，要么删掉字段，别留着让下一个人以为"已经有按钮了"。

**P2-7｜两处注释已过期**
- `MainActivity.kt:485` 写「全部 + 11 个分类」，实际 `TechCategory` 已 15 个。
- `PromptEngineTest.kt:14`、`:294` 写「阶段一技法库是空的」，实际 83 条已落地。

**P2-8｜冷却/驻留的精确边界有一处缺口**
- 位置：`PromptSchedulerTest.kt:94-115`
- 现状：测了 7 900 / 8 001，没测 `COOLDOWN_MS` 本身（8 000）。`cooldownOk` 用的是 `until <= now`（`PromptScheduler.kt:282`），8000 应当放行 —— 但这条例外没被钉住，将来改成 `<` 不会有人发现。建议补 `assertNotNull(... 8_000L)`。

---

## 4. 本轮的局限（必须与结论一起读）

1. **没有跑任何构建与测试**。108 个用例"全绿"这个结论我**既没有证实也没有证伪** —— 我只做了静态计数（`@Test` = 108，与声称一致）和逐条阅读判断断言质量。下一轮必须真跑一次 `./gradlew test`，否则"全绿"只是转述。
2. **没有真机/模拟器**。教练槽的实际观感（会不会因为 500ms tick + `changed` 判断导致闪动）、`llCoachSlot` 在窄屏上的换行、Toast 与对话框的遮挡，全部未验证。
3. **签名只做了结构验证**：`APK Sig Block 42` magic 存在、`META-INF` 下无 v1 签名文件（符合 minSdk 24 只签 v2/v3 的预期）。**没有做密码学校验** —— 本 shell 的 `JAVA_HOME` 未配置，`apksigner verify` 跑不起来。下一轮用 JDK 跑一次 `apksigner verify -v --print-certs` 才能算"v2 签名通过"。
4. **取证窗口与工程师改码重叠**：工程师的改动在 17:34 落盘（R09/R11→CONTEXT、密度→STANDARD、两个测试类同步），我落盘前后各取了一次证，§0 与 §3 的结论已按**落盘后**的状态重写。但落盘后再无新改动确认，若 17:34 之后又有提交，P1-1/P1-2 需要重新核对。
5. **`TechniqueMatcher` 的推荐排序质量未评估**。我只验证了"该出的出、不该出的不出"，没有验证"三张卡是否真的从不同角度说话"（那需要真实画面样本）。
6. 临时脚本 `D:/tmp/qa_check_v15.py` 及 4 个变异副本已删除；项目目录内**零新增、零修改**（唯一新增文件是本报告本身）。

---

## 5. 路由判定

```
ROUTE: ENGINEER
```

**判定理由**：本轮**没有发现 P0**，数据层与推送纪律是干净的（§2.1 的 0 处不一致、§2.2/2.3 的无条件过滤都已取证）。但落盘后的改动引出 3 条 P1，都要工程师处理：

- **P1-1** `lastGuideAt` 记账随 R09/R11 改 CONTEXT 而失效，R09 的 8s 节拍变成死逻辑（语义已死、代码还在跑）。
- **P1-2** R10 兜底轮播在生产档位（STANDARD）下彻底不可达，而密度没有 UI 可调 —— 整块功能是死的。这条影响最大：`FALLBACK_TEXT` 七条文案、`fallbackPool` 的场景映射全部白写。
- **P1-3** R11 的 `gridOn` 观测的是本 App 叠加层，会产生"用户刚关掉网格就被要求打开"的自相矛盾提示。

其中 P1-1 与 P1-2 是**同一次改动的两个未被察觉的副作用**：改动的推导本身是对的（`MainActivity.kt:194-205` 的注释写得很清楚），但它只改了"规则归属 + 档位"，没有回头检查"谁在写 `lastGuideAt`"和"R10 还要不要活"。测试 108 条会全绿 —— 因为这三点全在测试覆盖之外。

之所以不是 `ROUTE: QA`：我的比对器已通过 4 组变异测试自检（§2.1），原始输入下 0 处不一致；§2.5 的"恒真测试"结论有 19∩8=∅ 的枚举证据；P1-1/P1-2 均为读落盘代码 + 追调用链得出，不依赖任何脚本假设。

之所以不是 `ROUTE: NO_ONE`：P1-1/P1-2 会让"教练提示"这个 v1.5 的核心卖点出现功能缺口（兜底轮播不可达），不宜带着发版；另有 2 条 P2（恒真测试 P2-1、白名单单闸 P2-2）属"现在不炸、以后难查"，建议同批处理。

**下一轮（Round 2）建议的验证清单**：
1. 真跑 `./gradlew test` 与 `assembleRelease`，核 108 用例 + APK 体积/versionCode。
2. 用 JDK 跑 `apksigner verify -v --print-certs`，把"v2 签名"从结构证据升级为密码学证据。
3. 重跑本报告的逐字段比对器（`TechniqueBookV15.kt` 时间戳 17:29:39，本轮比对在其之后，数据层应仍为 0 处不一致）；建议把比对器改写成 JVM 测试固化进 CI，补上"转写失真"盲区。
4. 针对 P1-2 新增一条端到端用例：`PromptScheduler(STANDARD)` 下稳定画面 idle 30s，断言 R10 **不**出现（把现状钉死）；再按最终产品决策改成"应出现"。
5. 针对 P1-1 断言 `lastGuideAt` 是否还有写点，或直接删除该机制。
6. 真机验 P1-3 的自相矛盾提示。

之所以不是 `ROUTE: NO_ONE`：8 条 P2 里有 2 条（P2-1 恒真测试、P2-2 白名单粒度）会削弱未来回归防护能力，属"现在不炸、以后难查"的类型，不宜带着进下一轮。

**下一轮（Round 2）建议的验证清单**：
1. 真跑 `./gradlew test` 与 `assembleRelease`，核 108 用例 + APK 体积/versionCode。
2. 用 JDK 跑 `apksigner verify -v --print-certs`。
3. 重跑本报告的比对器（R09/R11 落盘后数据层未变，应仍为 0 处不一致）。
4. 针对 P1-1 新增一条"R09 为 CONTEXT 时 R10 仍能在录像页兜底"的用例。
5. 真机验 P1-2 的自相矛盾提示。
