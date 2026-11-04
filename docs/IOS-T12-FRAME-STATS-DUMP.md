# iOS 侧怎么跑 FrameStats dump（T12 验收①）

> 本文回答一件事：**`docs/fixtures/frame-stats/*.png` 在 iOS 侧的 dump 到底怎么产出、在哪里产出、怎么和 Android 侧比**。
>
> ★ 本机（Windows）**没有 Xcode / macOS**，Swift 代码在本机无法编译、更无法运行。
> 下面所有「跑」的步骤都是**给 macOS CI 看的**，本文不提供任何 iOS 侧的统计数值 —— 数值全部由
> Kotlin 在真机上算出来，跑不出来就如实写「需要 macOS CI 验」，绝不编造。

---

## 1. 先说清楚产出链路

```
docs/fixtures/frame-stats/01-bright.png        （同一份字节）
   │  FileHandle 读 bytes
   ▼
FrameStatsProducer.yuv420(fromImageBytes:)      （CGImageSource → RGBA → 紧凑 I420）
   │  KotlinByteArray × 3（Y / U / V）
   ▼
IosBridge.shared.dumpFrameStats(...)            （Kotlin，iosMain）
   │  FrameStatsDump.computeDump(...)            （Kotlin，commonMain → FrameStatsCore）
   ▼
String  ★ 整段文本由 Kotlin 生成，Swift 一个字段都不拼
   │  String.write(to:atomically:encoding: .utf8)   ★ 原样落盘
   ▼
<输出目录>/01-bright.png.ios.dump.txt
```

`label` 传的是**图集原文件名**（含 `.png`），它会被 Kotlin 写进首行 `### <label>`。
⇒ **Android 侧的 dump 必须用逐字相同的 label**，否则 `diff` 会假报不一致（详见 §5）。

---

## 2. 前置条件（缺一不可）

| # | 条件 | 谁负责 | 状态 |
|---|---|---|---|
| 1 | `shared/src/iosMain/.../IosBridge.kt` 里有 `dumpFrameStats(...)`，且 `FrameStatsDump.kt` 在 commonMain | 另一位成员 | 已落地（签名与 `FrameStatsProducer.swift` 的调用点逐项一致） |
| 2 | `ios/ShotTuner/Camera/FrameStatsProducer.swift` | 本任务 | 已交付 |
| 3 | `ios/project.yml` 里加一个 `bundle.unit-test` target（`ShotTunerTests`） | **主理人**（`project.yml` 归主理人管，本任务不改） | ⚠️ 待办 |
| 4 | macOS runner（`.github/workflows/ci-gates.yml` 的 `ios-compile` job，`runs-on: macos-14`，`workflow_dispatch` 手动触发） | 已存在 | ✅ |

> 为什么必须走 XCTest：`FrameStatsProducer` 是一个 Swift API，它自己不会执行。
> App target 里没有任何地方会主动调 `dumpFixtures(...)`（接线由主理人统一做），
> 所以在 CI 上最干净的驱动方式是一个 unit test target。
> **`xcodebuild build` 只能证明「能编译」，不能证明「能跑出 dump」** —— 两件事别混为一谈。

---

## 3. 测试文件（放进 `ios/ShotTunerTests/FrameStatsDumpTests.swift`）

```swift
import XCTest
@testable import ShotTuner

final class FrameStatsDumpTests: XCTestCase {

    func testDumpFixtures() throws {
        // ★ #filePath 是 macOS runner 上的真实源码路径。
        //   iOS Simulator 是宿主上的一个进程（不是虚拟机），能直接访问宿主文件系统，
        //   所以用 #filePath 反推仓库根目录、直接写回 ios/build/ 是可行的，
        //   不需要把图集拷进 app bundle，也不需要 simctl get_app_container。
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // ios/ShotTunerTests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // 仓库根
        let fixtures = repoRoot.appendingPathComponent(
            "docs/fixtures/frame-stats", isDirectory: true
        )
        let output = repoRoot.appendingPathComponent(
            "ios/build/frame-stats-dumps", isDirectory: true
        )

        let results = try FrameStatsProducer.dumpFixtures(
            in: fixtures,
            outputDirectory: output
        )
        XCTAssertEqual(results.count, 8, "图集应有 8 张 PNG")
        for result in results {
            let text = try String(contentsOf: result.outputURL, encoding: .utf8)
            // 首行由 Kotlin 生成，Swift 只做「有没有写出来」的断言，不改文本。
            XCTAssertTrue(text.hasPrefix("### \(result.label)\n"))
            print("IOS_DUMP=\(result.outputURL.path)")
        }
    }
}
```

`project.yml` 需要的那一处（示意，**由主理人落笔**）：

```yaml
  ShotTunerTests:
    type: bundle.unit-test
    platform: iOS
    sources:
      - path: ShotTunerTests
    dependencies:
      - target: ShotTuner
```

---

## 4. CI 命令（加在 `ios-compile` job 的 `xcodebuild ... build` 之后）

```bash
# ── 产出 iOS 侧 dump ────────────────────────────────────────────────────────
set -euo pipefail
xcodebuild test \
  -project ios/ShotTuner.xcodeproj \
  -scheme ShotTuner \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 15' \
  -derivedDataPath ios/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO \
  -only-testing:ShotTunerTests/FrameStatsDumpTests \
  2>&1 | tee /tmp/ios-dump.log

grep -q 'Test Succeeded' /tmp/ios-dump.log \
  || { echo "::error::iOS dump 测试没跑过"; exit 1; }
test -d ios/build/frame-stats-dumps \
  || { echo "::error::dump 输出目录没生成"; exit 1; }
ls -l ios/build/frame-stats-dumps/*.ios.dump.txt
```

留档（GitHub Actions）：

```yaml
- uses: actions/upload-artifact@v4
  with:
    name: ios-frame-stats-dumps
    path: ios/build/frame-stats-dumps/*.ios.dump.txt
```

> ★ 触发方式沿用现有纪律：`ios-compile` 是 **`workflow_dispatch` 手动触发**，
> 不占每次 push 的 macOS 配额。Actions 页面 → `CI Gates` → `Run workflow` 即可。

---

## 5. 和 Android 侧怎么比（验收①：逐字段差异 ≤ 2%）

**两端必须传同一个 `label`。** `FrameStatsProducer` 用的是 `fixture.lastPathComponent`
（例如 `01-bright.png`）；Android 侧 dump 工具请同样用「文件名（含扩展名）」。
Kotlin 的 `FrameStatsDump.render()` 会把它写进 `### <label>` 首行，label 不同 ⇒ 第一行就 diff。

```bash
# Android 侧 dump 的输出路径由负责该侧的成员确定后替换下面的占位路径
ANDROID_DIR="app/build/frame-stats-dumps"
IOS_DIR="ios/build/frame-stats-dumps"

for f in "${IOS_DIR}"/*.ios.dump.txt; do
  base="$(basename "$f" .ios.dump.txt)"          # 例如 01-bright.png
  diff -u "${ANDROID_DIR}/${base}.android.dump.txt" "$f"
done
```

判据（架构师定的，**不要自己改**）：`meanLuma` / `centerLuma` / `clipHigh` / `clipLow` /
`gridLuma` 各格逐字段差异 ≤ 2%；`clipHigh`/`clipLow` 落在阈值附近（±0.02 绝对值）时放宽到 3%。

### ★ 第一次跑 dump 必须先做的两件事

1. **比 `grid`，不是只比均值。** `meanLuma` / `clipHigh` / `clipLow` 对行序不敏感，
   图像上下镜像了它们也一模一样。`06-no-face.png` 是脚本里 `vgradient()` 生成的
   **从上到下渐变**，`05-with-face.png` 的椭圆中心在 `cy=110`（不是 120）——
   这两张就是内置的行序探针。
2. 若 `grid` 呈**上下镜像**，说明 `CGContext` 位图内存的行 0 不是 PNG 的顶行。
   改 `ios/ShotTuner/Camera/FrameStatsProducer.swift` 里**一行**：

   ```swift
   private static let reversesDecodedRows = true
   ```

   其余代码一律不动。本机没有 Xcode，这一条只能由 macOS CI 的第一次 dump 来定。

---

## 6. 本机（Windows）能验 / 不能验的清单

| 项 | 本机 | 怎么验 |
|---|---|---|
| 零网络门禁（`tools/ci/check-no-network.sh`） | ✅ **已跑通**（扫 60 个文件 / 7 个特征串，EXIT=0） | `bash tools/ci/check-no-network.sh .` |
| Swift 语法 / 类型检查 | ❌ 无法验 | 需要 macOS + Xcode 15.4（`ios-compile` job 的 `xcodebuild build`） |
| `KotlinByteArray(size:)` / `set(index:value:)` 的导出名 | ❌ 无法验 | 第一次 macOS 编译；若报错，**只改 `FrameStatsProducer.kotlinByteArray(_:)` 一个函数** |
| `Brand.other` / `FrameStatsCore.shared` 的导出名 | ❌ 无法验 | 同上；`CameraSession.CommonBrand` 是单点 |
| `CMVideoFormatDescriptionGetDimensions(format.formatDescription)` 的类型匹配 | ❌ 无法验 | 同上（若报类型不符，补一次显式转换即可） |
| `CGContext` 行序 | ❌ 无法验 | §5 的 `grid` 对照 |
| **任何 iOS 侧统计数值** | ❌ **不验、不编** | 全部由 Kotlin 在 macOS CI 上算出并落盘 |

> ★ 本轮**没有产出任何 iOS 侧数值**，也没有提交任何 dump 结果文件。
> 这是刻意的：数值不在本机跑出来，就不该出现在仓库里。

---

## 7. 口径与合规

- 只说「**参考分**」；文案用「照拨」「建议值」；教用户怎么调，不替用户调。
- `FrameStatsProducer.swift` / `CameraSession.swift` 只用系统框架
  （AVFoundation / CoreMedia / CoreVideo / Foundation / CoreGraphics / ImageIO），
  零第三方依赖、零联网调用、不签名、不加麦克风权限、不加 App Extension、不放宽 ATS。
- 两端共用 `shared/src/commonMain` 的 `FrameStatsCore` + `FrameStatsDump`，
  **expect/actual 总量保持 0**。
