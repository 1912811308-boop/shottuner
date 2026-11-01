# T11 · iOS 侧 Apple Vision ↔ Android 侧 ML Kit 字段对照表

> **本表回答一个问题**：iOS 用 Vision、Android 用 ML Kit，两端产出的
> `AiVisionResult`（common 的纯数据类）能不能**逐字段对照**？
>
> 架构师 T11 验收 ①：「产出的 `AiVisionResult` 字段与 Android 侧**逐字段可对照**（产出一张对照表）」。
>
> ★ 本表**不含任何「已验证」的断言** —— 每一行都标了「取值方式」与「**验过没有**」。
>   我手上**没有 iPhone、没有 Mac**（见 docs/IOS-PRD.md §7.2），
>   所以第 ④ 段的「实测结论」全部是**待做**，不是**已做**。

---

## ① 权威口径

| 端 | 实现 | 位置 |
|---|---|---|
| Android | ML Kit `FaceDetection` + `ImageLabeling` | `app/src/main/java/com/shottuner/app/analysis/AiVision.kt` |
| iOS | Apple Vision `VNDetectFaceRectanglesRequest` + `VNClassifyImageRequest` | `ios/ShotTuner/Camera/VisionSceneDetector.swift` |
| 共用 | `AiScene` / `AiVisionResult` / `FaceBox`（纯数据） | `shared/src/commonMain/kotlin/com/shottuner/app/analysis/AiVisionModels.kt` |

> ★ `AiScene` 是 commonMain 的**纯枚举**（8 值 + 中文 label），两端共用同一份。
>   iOS 侧**直接 `import Shared` 用它**，不做 String 中转、不另建 Swift 枚举。

---

## ② 逐字段对照表

| `AiVisionResult` 字段 | 类型 | Android（ML Kit）取值方式 | iOS（Vision）取值方式 | 口径是否一致 |
|---|---|---|---|---|
| `scene` | `AiScene` | `decide()`：人脸优先，其次标签集合命中 | `decide()`：人脸优先，其次 `VisionSceneMap.aggregate()` 标签聚合 | ✅ 同构（决策顺序一致） |
| `confidence` | `Float` | **固定档位**：合影 0.92 / 人像 0.86+面积×0.5（上限 0.98）/ 食物 0.90 / 宠物 0.88 / 风景 0.86 / 建筑 0.82 / 花草 0.80 / 未识别 0.30 | **同档位**（`VisionSceneMap.sceneDecisionConfidence` + 人像同公式） | ✅ 同口径（**刻意对齐**，因为 common `SceneClassifier` 用 0.85/0.60 门槛） |
| `faceCount` | `Int` | `faces.size`（ML Kit 内置 `minFaceSize=0.06`） | Vision 检出数，**再按宽度 ≥ 0.06 过滤**（Vision 无此参数，iOS 侧自补） | ⚠️ 语义近似，**数值待实测** |
| `faceArea` | `Float` | 最大脸 `(box.width×box.height)/(imageW×imageH)` | 最大脸 `box.width×box.height`（Vision 框已归一化） | ✅ 同一公式 |
| `tags` | `List<String>` | ML Kit 标签 → `CN` 翻译表 → 前 6 条 | Vision 标识符 → `chineseLabels` 翻译表 → 前 6 条（**当前为空表**） | ⚠️ 两端词表不同，**无法逐词对照** |
| `ok` | `Boolean` | 两个请求都回调即 `true` | `perform` 未抛异常即 `true`；失败 ⇒ `EMPTY`（`ok=false`） | ✅ 语义一致 |
| `faceCenterX` | `Float` | 最大脸框中心 X（归一化，左上原点） | 同上，**Y 轴翻转后**算中心 | ✅（翻转后一致） |
| `faceCenterY` | `Float` | 最大脸框中心 Y（归一化，左上原点） | 同上，**Y 轴翻转后**算中心 | ✅（翻转后一致） |
| `faceBox` | `FaceBox` | ML Kit 框 ÷ 图像宽高（**左上原点**） | Vision 框（**左下原点**）→ 翻转 Y（**左上原点**） | ✅（**翻转后**一致） |
| `headEulerY` | `Float` | `f.headEulerAngleY`（**度**，正值＝脸朝画面右侧） | `observation.yaw`（**弧度**）× 180/π × `visionYawSign`（**度**） | ⚠️ 单位已换算；**符号待实测** |

---

## ③ 三处「口径陷阱」（写下来免得后人重踩）

### 陷阱 1 · 坐标系原点相反 ★ 最容易错

| | 原点 | Y 轴方向 |
|---|---|---|
| Vision `VNFaceObservation.boundingBox` | **左下角** | 向上为正 |
| ML Kit `Face.boundingBox` / common `FaceBox` | **左上角** | 向下为正 |

⇒ **必须翻转 Y**：`top = 1 - box.maxY`、`bottom = 1 - box.minY`。
不翻转的话，脸中心会上下颠倒（看起来像人在画面最下面），而这个 bug **肉眼很容易漏掉**。

### 陷阱 2 · 角度单位不同

| | 单位 | 范围 |
|---|---|---|
| Vision `yaw` | **弧度** | [-π/2, π/2] |
| ML Kit `headEulerAngleY` | **度** | [-90, 90] |

⇒ 必须 × 180/π。正负方向由 `VisionSceneThresholds.visionYawSign` 单点控制，
**真机对照后若相反，只改这一个常量**。

### 陷阱 3 · 置信度分布不同 → 阈值不能照搬

| | 强结果常见置信度 | 阈值 |
|---|---|---|
| ML Kit `ImageLabeler` | ≈ 0.98 | 0.5（Android 侧现值） |
| Vision `VNClassifyImageRequest` | ≈ 0.77 | **0.25**（iOS 侧独立取值） |

照搬 0.5f 会让 Vision **静默返回空结果**（不报错，看着像「什么都没识别出来」）。
⇒ 阈值写在 iOS 侧自己的表 `VisionSceneThresholds` 里，**不污染 Android 常量**。

---

## ④ 验收 ②「同一张图，faceCount / faceArea 差异 < 5%」—— 验法与现状

### 现状：**未验**（我无法验）

原因：**手上没有 iPhone，也没有 Mac**。iOS 侧一次真机运行都需要 CI 出包 + 侧载，
而对照还需要 Android 侧同一张图的 ML Kit 结果。**本表不提供任何编造的实测数字。**

### 验法（留给 T22 · CAL-2）

1. **准备同一批图片**：Android 与 iOS **必须用同一份字节**（不是各拍一张）——
   同一张测试图的 ISP、白平衡、光照全不同，比出来的差异没有意义。
   - 图片来源：拍摄原图（JPEG），两端各自解码。
   - ⚠️ JPEG 是有损格式，会带来解码差异；必要时先用无损 PNG 排除干扰。
2. **Android 侧**：对每张图跑 ML Kit，记录 `faceCount` / `faceArea`。
3. **iOS 侧**：对每张图跑 `VisionSceneDetector`（用 `analyze(cgImage:orientation:)`），
   记录同一批字段 —— `VisionSceneDetector` 已把结果直接填成 common 的 `AiVisionResult`，
   **取 `faceCount` / `faceArea` 即可，无需二次转换**。
4. **判定**：逐字段差异 ≤ 5%。**超标时的定位顺序**：
   ① 先怀疑 `minFaceSize` 语义（ML Kit 文档说「相对图像宽度」，实现里像是「相对短边」）；
   ② 再怀疑 Vision 与 ML Kit 的**人脸框紧致度差异**（不同检测器，框本身就不一样大，
      这类差异**可能无法消除** —— 需在报告里如实说明，而不是硬调参数凑数）；
   ③ 最后才怀疑坐标翻转 / 面积公式（那属于**实现 bug**，必须先修）。
5. **重点样本**：远景小脸（检验 `minFaceSize` 口径）、侧脸（检验 `yaw` 符号）、
   多张脸（检验 `faceCount`）。

### 预期风险（先说在前面）

- `faceArea` 的 5% 门槛**可能不成立**：两家的**人脸框定义本身不同**
  （框多紧、含不含下巴/额头），同一个物理人脸算出来的面积比可能有系统性差异。
  这种差异**不是 bug**，是检测器差异 —— 报告里要说清楚，而不是调参数掩盖。
- `headEulerY` 的**符号**（Vision 的 counterclockwise vs ML Kit 的「朝画面右侧」）
  **必然要真机确认一次**。已经用单点常量隔离，改动面 = 1 行。

---

## ⑤ 标定表（CAL-1）现状

| 项 | 现状 |
|---|---|
| `VisionSceneMap.table` | **空表**（`static let table: [Entry] = []`） |
| `VisionSceneMap.chineseLabels` | **空表** |
| `VisionSceneThresholds.minimumClassificationConfidence` | **0.25**（起始值，未标定） |
| 采集工具 | ✅ `ios/ShotTuner/Camera/VisionCalibrationCollector.swift` 已就位 |

### 采集工具怎么用

```swift
// 在真机上（★ 模拟器上 `VNClassifyImageRequest` 不可用，只会拿到空结果）：
let observations = VisionCalibrationCollector.collect(from: cgImage, orientation: .up)
let url = VisionCalibrationCollector.write(observations, sampleName: "pet_01")
```

- 产物：`Documents/vision-calibration/<样张名>.tsv`，每行 `样张名\t标识符\t置信度`。
- 覆盖 8 类样张：**人像 / 合影 / 风景 / 建筑 / 食物 / 宠物 / 花草 / 空镜**。
- ★ 采集器**只做原样导出**，不做任何归族判断 —— 归族是人的活（T22 · CAL-1）。
  否则就变成「用错误的映射表去标定映射表」的循环论证。

### 回填后的收尾（还没做）

- [ ] 把命中的标识符按族写进 `VisionSceneMap.table`
- [ ] 把标识符的中文名写进 `VisionSceneMap.chineseLabels`
- [ ] 按真机分布固化每族阈值（`Entry.minimumConfidence`）
- [ ] 样张 + 期望 `AiScene` + 置信度区间**锁进测试**（换 Vision revision 只更新表，不改逻辑）

---

## ⑥ 零第三方依赖

| 检查项 | 结果 |
|---|---|
| `ios/Package.swift` | **不存在** ✅ |
| `ios/Podfile` / `Podfile.lock` | **不存在** ✅ |
| `ios/Cartfile` | **不存在** ✅ |
| `ios/project.yml` 的 `packages:` 段 | **不存在** ✅ |
| 新增文件的 import | 仅 `Foundation` / `Vision` / `CoreGraphics` / `CoreVideo` / `ImageIO` / `Shared`（全为系统框架或本仓自有）✅ |

门禁：`tools/ci/check-ios-project-spec.sh` 第 4 段会主动扫这几个文件并判红 —— **已跑通**。
