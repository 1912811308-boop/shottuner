# CODEX P0 实施报告

任务：`20261017-162513-workbuddy-按视频学习规格实现机位教练与人像取景规则-P0-588c`

日期：2026-10-17

工作目录：`D:\AI学习\Claw\shottuner`

版本：`versionName = "1.5.0"`，`versionCode = 11`

---

## 1. 六项实施明细

### P0-1 姿态跟踪（versionCode 6）

- 新增：`app/src/main/java/com/clover/shottuner/device/OrientationTracker.kt`
- 要点：
  - 只使用 `Sensor.TYPE_ROTATION_VECTOR`，未新增权限；
  - 由旋转矩阵第三行 `(ux, uy, uz)` 计算：
    - `pitch = atan2(uy, uz)`：平放 `uy=0, uz=1 → 0°`，竖持 `uy=1, uz=0 → 90°`；
    - `roll = atan2(ux, uy)`：竖持正对 `0°`，左右歪头得到带符号角度；
  - 传感器按 `SENSOR_DELAY_GAME` 采样，输出节流到 10Hz（100ms）；
  - 一阶低通 `α = 0.25`，10Hz 下时间常数约 0.35s；理由已写在类注释里；
  - `start()/stop()` 随 `MainActivity.onResume()/onPause()` 启停；
  - 无旋转矢量传感器时静默降级，不影响相机主流程。
- 构建：沙箱内未进入编译（见第 2 节）。
- CHANGELOG：已记 `versionCode 6`。

### P0-4 AiVision 人脸几何字段（versionCode 7）

- 修改：`app/src/main/java/com/clover/shottuner/analysis/AiVision.kt`
- 要点：
  - 新增纯 Kotlin `FaceBox(left, top, right, bottom)`，避免把 `android.graphics.RectF` 带进规则层；
  - `AiVisionResult` 新增 `faceCenterX`、`faceCenterY`、`faceBox`、`headEulerY`；
  - 按人脸框面积取最大人脸，`boundingBox` 按 `image.width/height` 归一化到 0..1；
  - 输入 bitmap 已在 `FrameAnalyzer` 里按传感器方向旋正，因此框坐标与用户所见画面一致；
  - 无脸时安全默认值：`0.5 / 0.5 / FaceBox.ZERO / 0f`；`EMPTY` 同步更新；
  - `headEulerAngleY` 原样透传，正值按规格约定为脸朝画面右。
- 构建：沙箱内未进入编译（见第 2 节）。
- CHANGELOG：已记 `versionCode 7`。

### P0-3 机位教练（versionCode 8）

- 新增：`app/src/main/java/com/clover/shottuner/coach/FramingCoach.kt`
- 新增：`app/src/test/java/com/clover/shottuner/coach/FramingCoachTest.kt`
- 要点：
  - 纯 Kotlin `object`，零 Android 依赖；输入 `FaceInfo?` + `SubjectBox?` + `CompositionHint` + `equivFocalMm`；
  - 实现 T01/T02/T04/T06，常数逐项照抄 `python/shottuner/framing.py`：
    - `WIDE_FOCAL_MIN=18`、`WIDE_FOCAL_MAX=28`
    - `FACE_FULL_BODY_MAX=0.06`
    - `EDGE_SAFE_X=0.18`
    - `FACE_CENTER_MIN_Y=0.42`、`FACE_CENTER_MAX_Y=0.58`、`FOOT_BOTTOM_MIN=0.88`
    - `WAIST 0.44..0.52`、`KNEE 0.62..0.72`
    - `HEAD_TURN_DEG=15`
  - 输出按 severity 降序（同 severity 稳定保持规则顺序），最多 2 条；
  - T01 包含「蹲下」「退半步」「这个机位对了」三种分支；
  - 注意：P0 范围只要求四条规则，未把 Python 里的 T03/T05 搬进 Kotlin，避免扩大本次行为面。
- 单测：`FramingCoachTest` 共 14 个 `@Test`，覆盖 dispatch 要求的全部场景，另加边界 0.18、长焦不误报、无脸、最多两条。
- 构建/单测：沙箱内未进入编译与测试（见第 2 节）。
- CHANGELOG：已记 `versionCode 8`。

### P0-5 FeatureAdvisor 扩展（versionCode 9）

- 修改：`app/src/main/java/com/clover/shottuner/feature/FeatureAdvisor.kt`
- 要点：
  - `advise()` 新增 `needsTripod: Boolean = false`，默认值保证 `ScreenCaptureService` 的旧调用不受影响；
  - 逆光三条，优先级 96/95/94，顺序稳定：
    1. `逆光 ① 点脸测光`：曝光补偿 -0.7～-1.0，先保高光；
    2. `逆光 ② 找反光面`：白墙 / 浅色地面 / 浅色衣服；
    3. `逆光 ③ 转 45° 侧逆光`：让太阳留在画面外；
  - 全程没有建议闪光灯直打；
  - `dark && needsTripod` 追加「按快门别用手指戳」：2 秒倒计时，或手表 / 耳机音量键；优先级 98；
  - `capabilitySheet()` 末尾追加两条专业模式 KNOW 卡（优先级 55、52）：
    - 「长按对焦框可锁 AE/AF」；
    - 「RAW+ 不是真 RAW 单帧」。
  - 放置位置理由：这两条是「知道就好」的通用能力，不属于当前画面诊断，因此放在首次打开的能力清单，而不是逐帧建议里；优先级放在 ISO 卡之后，保持设备硬信息优先。
  - 因为 `renderFeatures()` 原来固定 `take(4)`，首次能力清单改为最多 6 条，实时建议仍为 4 条，确保两张新卡可见。
- 构建：沙箱内未进入编译（见第 2 节）。
- CHANGELOG：已记 `versionCode 9`。

### P0-6 照片页「机位」提示（versionCode 10）

- 修改：`app/src/main/java/com/clover/shottuner/MainActivity.kt`
- 修改：`app/src/main/java/com/clover/shottuner/model/CompositionHint.kt`
- 修改：`app/src/main/java/com/clover/shottuner/analysis/CompositionAnalyzer.kt`
- 要点：
  - `CompositionHint` 增加 `subjectLeft/Top/Right/Bottom`；`EMPTY` 默认 `0/0/1/1`；
  - `CompositionAnalyzer` 把已算出的 `bx0/by0/bx1/by1` 原样透传；
  - `MainActivity.installFramingLine()` 在照片页 `pagePhoto` 容器中、构图行下方插入一个 `TextView`：
    - 13sp、`#E8F0F2`、上边距 6dp；
    - 只增加一个 View，未改 `activity_main.xml`，未新增布局层级；
    - 未使用 `tvCompose` 所在的横向行作为父容器，避免把提示挤进同一行；实际插入点是该行的父容器（照片页纵向容器），视觉上位于构图提示下方。
  - 文案：
    - 有建议：`机位：<FramingCoach 最该做的一条标题>`；
    - 无建议但有俯仰数据：`机位：当前俯仰 N°`；
    - 都没有：`机位：等画面再判断`。
  - 输入：`aiResult` 仅在 `ok && faceCount > 0` 时启用；`SubjectBox` 仅当 `subjectConfidence > 0.30` 时传入，避免把噪声当成人像外框；焦距用 `profile.equivFocalMm`；
  - 节流：与 `renderComposition` 一致约 400ms；构图结果更新时强制刷新一次。
- 构建：沙箱内未进入编译（见第 2 节）。
- CHANGELOG：已记 `versionCode 10`。

### P0-2 俯仰角刻度（versionCode 11，与 P0-6 合并实现）

- 修改：`app/src/main/java/com/clover/shottuner/ui/GuideOverlayView.kt`
- 要点：
  - 新增 `submitPitch(pitchDeg: Float)`，与 `submit(hint)` 解耦；
  - 未收到 pitch 时不画（`pitchDeg == null`）；
  - 右侧竖向刻度：0° 在底部、45° 在中点、90° 在顶部，标注「腰以下 / 45° / 90°」；
  - 橙色短横线随 pitch 上下移动；左下小指针以底端为轴旋转，直观表示手机抬起的角度；
  - 颜色沿用 `accent(#7FE3C0)` 与 `warn(#FFB74D)`；
  - `showGuides == false` 时与构图引导一起隐藏。
- 构建：沙箱内未进入编译（见第 2 节）。
- CHANGELOG：已记 `versionCode 11`。

---

## 2. 构建与单测结果

### assembleDebug

**最终结果：沙箱内未通过，且失败都发生在进入 Kotlin 编译之前；不能据此判断代码编译正确，也不能据此判断代码有错。**

实际尝试记录：

1. 按 dispatch 原样命令执行：
   - 失败信息：`Task '.compiler.execution.strategy=in-process' not found in root project 'ShotTuner'`；
   - 说明：Gradle 命令行在 PowerShell 下把 `-Pkotlin.compiler.execution.strategy=in-process` 拆错，未进入编译；
2. 用等价写法 `--project-prop="kotlin.compiler.execution.strategy=in-process"` 重试：
   - 失败信息：`SDK location not found`；
   - 细节：`local.properties` 中的 `D:\AI学习\...` 被 Java Properties 按字节解码成乱码 `D:\AIѧϰ\...`，目录不存在；
   - 未修改 `local.properties`（按硬约束保持原样）；
3. 尝试用 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 覆盖 SDK 路径：
   - 失败信息：无法读取 `app/build/gradle-user/caches/8.9/kotlin-dsl/accessors/.../metadata.bin`，路径同样显示为乱码 `D:\AIѧϰ\...`；
   - 仍是构建环境路径编码问题，未进入编译；
4. 之后本机 `exec` 后端持续返回 `CreateProcessWithLogonW failed: 1326`，无法继续执行任何命令或构建。

结论：本次没有可用的 `assembleDebug` 通过/失败结论。请 WorkBuddy 在沙箱外按 dispatch 第 4 节配方复跑；如 `-Pkotlin...` 仍被拆错，改用：

```powershell
--project-prop="kotlin.compiler.execution.strategy=in-process"
```

### FramingCoachTest

- 已写 14 个 `@Test`；
- **未执行**：单元测试同样依赖 Gradle/Android SDK 配置，被上述环境问题阻断；
- 预期结果：14/14 通过（按 Python 规则与用例逐条映射）。

### CHANGELOG

- 新建 `docs/CHANGELOG.md`；
- 已按六项分别记录 `versionCode 6..11`；
- 已补 `1.0.0`–`1.4.0` 五行简史（均为补录，日期按本次整理日）。

---

## 3. 未做的项及原因

- 六个 P0 功能项均已实现，没有主动删减。
- 未执行的部分只有「沙箱内构建」和「沙箱内 JVM 单测」，原因是构建环境路径编码/命令行解析问题，不是代码取舍。
- 未做 P1/P2（蓝调时刻、美食角度、稳定度、技巧库扩展等），按任务范围只做 P0。

---

## 4. 待真机清单（全部未经真机验证）

以下项目**没有**在真机上验证，报告中不得视为已验证：

1. **俯仰角刻度跟手（P0-2）**：平放/竖持/45° 转动时刻度是否连续、无明显跳动、无方向反转；
2. **蹲拍提示时机（P0-6/T01）**：真实人像场景里，`faceCenterY < 0.42` 的提示是否早于用户按快门，是否会被误判；
3. **逆光三条显示顺序（P0-5）**：在真实逆光画面里，功能页是否稳定按 ①→②→③ 显示，是否被其他高优先级卡片挤掉；
4. **人脸框归一化（P0-4）**：竖拍/横拍、前后台切换、不同人脸大小下，`faceCenterX/Y` 与 `faceBox` 是否与取景器一致；
5. **夜景倒计时提示（P0-5）**：`lastRecommendation.needsTripod == true` 时是否真的出现，且不会在普通手持夜景里误出现；
6. **「机位」行布局（P0-6）**：小屏/大屏、字体放大、面板滚动时是否被遮挡或换行异常。

建议真机至少跑：人像全身（站/蹲）、人脸贴边、切腰/切膝、朝右/朝左、逆光、夜景三脚架六组场景。

---

## 5. 给 WorkBuddy 的复核指引

### 关键代码位置

- `device/OrientationTracker.kt`：`onSensorChanged()` 的角度公式、10Hz 节流、`LOW_PASS_ALPHA`；
- `analysis/AiVision.kt`：`FaceBox`、`AiVisionResult` 新字段、`faceDetector.process(...).addOnSuccessListener` 内的最大人脸选择与归一化；
- `coach/FramingCoach.kt`：`advise()` 四条规则与常数；
- `coach/FramingCoachTest.kt`：14 条 JVM 用例；
- `feature/FeatureAdvisor.kt`：逆光 96/95/94、倒计时 98、能力卡 55/52；
- `MainActivity.kt`：`installFramingLine()`、`onPitchRoll()`、`updateFramingLine()`、`renderComposition()`、`onResume()/onPause()`；
- `ui/GuideOverlayView.kt`：`submitPitch()` 与 `drawPitchGauge()`；
- `model/CompositionHint.kt` / `analysis/CompositionAnalyzer.kt`：主体外框四字段的透传。

### 单测

```powershell
$env:JAVA_HOME="D:\AI学习\Claw\.toolchain\jdk-17.0.2"
$env:GRADLE_USER_HOME="D:\AI学习\Claw\shottuner\app\build\gradle-user"
$env:ANDROID_USER_HOME="D:\AI学习\Claw\shottuner\app\build\android-user-s"
& "D:\AI学习\Claw\.toolchain\gradle-8.9\bin\gradle.bat" :app:testDebugUnitTest --tests "*FramingCoachTest*" --console=plain --offline --project-prop="kotlin.compiler.execution.strategy=in-process"
```

预期：14 tests，14 passed。

### 构建

```powershell
$env:JAVA_HOME="D:\AI学习\Claw\.toolchain\jdk-17.0.2"
$env:GRADLE_USER_HOME="D:\AI学习\Claw\shottuner\app\build\gradle-user"
$env:ANDROID_USER_HOME="D:\AI学习\Claw\shottuner\app\build\android-user-s"
& "D:\AI学习\Claw\.toolchain\gradle-8.9\bin\gradle.bat" :app:assembleDebug --console=plain --offline --project-prop="kotlin.compiler.execution.strategy=in-process"
```

若仍报 SDK 路径乱码，需要由 WorkBuddy 在其可用构建环境里修正 `local.properties` 的编码/路径（本次按硬约束未改）。

### 产物

- 未移动/复制 APK 到 `dist/`；
- 未改 `python/`、`work/`、`dist/`、`docs/VIDEO_COACH_SPEC.md`；
- 未改 `activity_main.xml`、`AndroidManifest.xml`、`ScreenCaptureService` 浮窗链路；
- 未新增依赖或权限。

---

## 6. 可直接作为 `summary.md` 的内容草案

- 改了哪些文件：
  - 新增 `app/src/main/java/com/clover/shottuner/device/OrientationTracker.kt`
  - 新增 `app/src/main/java/com/clover/shottuner/coach/FramingCoach.kt`
  - 新增 `app/src/test/java/com/clover/shottuner/coach/FramingCoachTest.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/analysis/AiVision.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/analysis/CompositionAnalyzer.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/model/CompositionHint.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/feature/FeatureAdvisor.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/ui/GuideOverlayView.kt`
  - 修改 `app/src/main/java/com/clover/shottuner/MainActivity.kt`
  - 修改 `app/build.gradle.kts`（versionCode 11 / versionName 1.5.0）
  - 新增 `docs/CHANGELOG.md`
- 编译结果：沙箱内未进入编译；失败为 Gradle 命令行解析与中文路径编码问题，待 WorkBuddy 沙箱外复跑 `assembleDebug`。
- 单测：`FramingCoachTest` 14 条已写，沙箱内未执行，待复跑。
- 哪些项没做及原因：六项 P0 均已实现；仅构建/单测验证未完成，原因是构建环境问题。
- 待真机：俯仰角跟手、蹲拍提示时机、逆光三条顺序、人脸框归一化、夜景倒计时触发、机位行布局，均未经真机验证。
