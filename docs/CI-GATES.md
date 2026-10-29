# CI 门禁

> **一句话**：把「零网络」和「凭据不入库」从文档里的承诺，变成构建会失败的事实。

## 为什么需要机器拦

凭据一旦入库，CI 才有能力用它。README 里写「Apple ID 不进 secrets」**只是承诺** —— 承诺会被人绕过，机器不会。

同理，零网络承诺如果只靠人自觉，迟早有人为了图方便加一行网络调用。所以两道门禁都做成**可执行的检查**，命中即构建失败。

## 三道门禁

| 门禁 | 脚本 | 拦什么 | 跑在哪 |
|---|---|---|---|
| **L1 源码层** | `tools/ci/check-no-network.sh` | 我们自己写出来的联网调用 | Linux runner |
| **L2 配置层** | `tools/ci/check-no-network-config.sh` | iOS 工程配置里的联网放行 / 第三方 SDK / 非 NO 的签名开关 | Linux runner |
| **G1-d 凭据** | `tools/ci/check-apple-credentials.sh` | Apple 账号凭据、真私钥、非空的签名身份 | Linux runner |
| **common 纯度** | `tools/ci/check-common-purity.sh` | `commonMain` 里的 JVM-only 用法（`javaClass` / `SimpleDateFormat` 之类） | Linux runner |

> **common 纯度这一道是秒级的本地自查，不是权威检查。**
> 权威检查是 CI 里的 `:shared:compileCommonMainKotlinMetadata`（metadata 编译只允许 common API）。
> 本地脚本快但不完备（只覆盖 24 个已知模式），两道都要有。

另有一道 **L3 供应链层**（扫成品二进制里的第三方遥测符号），留给出包任务（T21）用，在 macOS runner 上跑。

### 为什么 L2 要挪到仓库内静态检查

出包后查要占 macOS 配额（免费私有仓库每月约 **200 macOS 分钟**，而 macOS 是 **10× 倍率**），而 L2 只需要读仓库里的文本文件 —— **Linux runner 就能跑**。把能提前拦的错误挪到不消耗 macOS 的地方，是保住配额的关键。

### 为什么 L1 不扫二进制

Foundation 永远链着系统自带的网络符号，任何用到 Foundation 的 iOS App 二进制里都有它们。**扫二进制必然 100% 误报**，而红一次的 CI 门禁等于没有门禁（团队会立刻学会忽略它）。所以二进制层只扫第三方遥测血统（`GoogleDataTransport` / `GTMSessionFetcher` / `FIRApp` 等），见 `patterns/network-binary.txt`。

## 三个「门禁命中自己」的坑

门禁如果老是自己报错，**红灯几天后团队就会开始绕过它** —— 那比没有门禁更糟。三处必须防住：

| 坑 | 现象 | 解法 |
|---|---|---|
| **1. 注释行** | 源码注释里出现被禁词（例如「这里不要用 XXX」） | 匹配前先 `awk -f lib/strip-comments.awk` 剥掉注释 |
| **2. 文档散文** | 文档正文引用了被禁词 | 扫描范围只限 `ios/**/*.swift` 与 `shared/src/**/*.kt`，**不扫 `docs/**`** |
| **3. 门禁脚本自己** | 脚本的 grep 参数里写着被禁词 | 被禁字面量**只放在** `patterns/*.txt`（脚本只读清单不写字面量）**+** 扫描排除 `tools/ci/**`，**双保险** |

## 门禁自己也要被测

`tools/ci/test-gates.sh` 是一张可自动跑的对照表：

| 注入 | 期望 |
|---|---|
| 被禁词写进 `.kt` 的**注释行** | 绿 |
| 被禁词写进 `docs/*.md` | 绿 |
| 被禁词只出现在 `patterns/*.txt` 里 | 绿 |
| 被禁词写进 `ios/*.swift` 的**代码** | 红 |
| 被禁词写进 `shared/src/*.kt` 的**代码** | 红 |
| 凭据特征写进 `.yml` / `.md` | 红 |
| 签名变量非空 | 红 |
| 签名变量为空 | 绿 |
| 签名开关非 `NO` | 红 |
| 签名开关为 `NO` | 绿 |

**它抓到过一个真 bug**：`lib/common.sh` 里 `local root="$1" pattern="$2" dir="$root/$3"` 写成了一行 —— bash 的 `local` 同行多变量会**先把这个语句里所有名字都声明成 unset、再依次赋值**，所以 `dir` 求值时 `root` 还没赋值。开了 `set -u` 会报 `root: unbound variable`，而这个报错在管道里被吞掉，表现为「这个目录下一个文件都没扫到」→ **门禁显示"通过"**。一道永远绿的门禁等于没有门禁。

## ⚠️⚠️ 最贵的教训：**Gradle 的 SKIPPED 任务，GitHub 上显示的 conclusion 也是 `success`**

第一次跑 CI 时，`:shared:compileKotlinIosSimulatorArm64` 在 Linux runner 上其实是 **SKIPPED**
（Kotlin/Native 的硬宿主限制：Apple 目标只能从 macOS 宿主编译），
而 GitHub 的 job conclusion 显示 **success** —— **只看结论会误判成「iOS 编译通过」**。
是翻日志才发现的。

**所以现在所有关键任务都带「反 SKIPPED 断言」**：日志里出现 `SKIPPED` 就 `exit 1`。

> **以后看 CI 结果，一律要进日志确认关键任务不是 `SKIPPED` / `NO-SOURCE`。**
> 这条对 `NO-SOURCE` 同样适用 —— `:shared:testDebugUnitTest` 曾经是 `NO-SOURCE`
> （测试还没迁进 `commonTest`），conclusion 也是 success，但它**什么都没验**。

## CI 的两个 job：一个常驻，一个手动

| job | runner | 触发 | 验什么 |
|---|---|---|---|
| `gates` | `ubuntu-latest` | **每次 push** | 四道门禁 + 门禁反向验证 13 项 |
| `common-gate` | `ubuntu-latest` | **每次 push** | `compileCommonMainKotlinMetadata`（**唯一权威的 common 纯度检查**）+ 单元测试 + Android 编译 |
| `ios-compile` | `macos-14` | **仅 `workflow_dispatch` 手动** | 真编 `iosSimulatorArm64` + `iosArm64` + XcodeGen + xcodebuild |

**为什么 iOS 编译要手动触发**：免费私有仓库的 macOS 配额约 200 分钟/月，
且 `macos-latest` 排队 15 分钟会被自动取消（换 `macos-14` 后 5 分钟内能排到）。
攒一批改动再编一次，而不是改一行编一次。

★ **SideStore 的 7 天重签不需要重新编译**（重签的是同一个 ipa）→ 配额只花在「代码变更后的重新编译」上。
实测 iOS 编译只要 **1 分 17 秒**（缓存命中）→ 月度可编译约 150 次，**配额不构成瓶颈**。

## CI 首跑踩过的三个坑（都修了，值得留档）

1. **`gradlew` 没有执行位** → `./gradlew: Permission denied`（exit 126）。
   根因：**Windows 上 git 的 `core.fileMode` 默认 false** —— 本地文件看着有执行位，
   但**提交进仓库的模式是 `100644`**，checkout 到 Linux runner 上就没有执行位。
   修：`git update-index --chmod=+x gradlew` + workflow 里加一步 `chmod +x gradlew`（双保险）。
2. **`signingConfigs` 在配置阶段抛异常** → CI 上没有 `gradle.properties`（被 gitignore，含明文口令），
   于是**连 `assembleDebug` 都构建失败**。
   修：改成惰性求值（且**空字符串也要当成「缺」**，因为 `gradle.properties.example` 里就是空串，
   `file("")` 会报 `path may not be null or empty string`）。
3. **DNS 把 `github.com` 解析到亚洲节点 `20.205.243.166` 时可能不通**（而 `api.github.com` 通）。
   备用 IP 段 `140.82.112/113/114/116.3` 与 `20.27.177.113` 都通。

## 本地怎么跑

```bash
bash tools/ci/test-gates.sh                    # 门禁自检（13 项）
bash tools/ci/check-no-network.sh              # L1
bash tools/ci/check-no-network-config.sh       # L2
bash tools/ci/check-apple-credentials.sh       # G1-d
bash tools/ci/check-common-purity.sh          # commonMain 纯度（秒级自查）
```

脚本接受可选的仓库根目录参数，省略时按脚本位置推导 —— 所以 `test-gates.sh` 能在临时目录里构造一个最小仓库来跑注入测试。

**环境要求**：bash 4+（Git Bash / Linux 自带满足；macOS 自带的是 bash 3.2，需 `brew install bash`）。脚本在 bash 3.2 下会**明确报错退出**，而不是静默漏检。

## 加一条新的被禁特征串

1. 打开对应的 `tools/ci/patterns/*.txt`，追加一行（`#` 之后是注释）
2. **不要**改脚本 —— 脚本只读清单
3. 如果新词会误伤文档或脚本自身，在 `patterns/credential-allowlist.txt` 里按 `路径::特征串` 精确豁免
4. 跑 `bash tools/ci/test-gates.sh` 确认门禁仍然自洽
5. 提交。**豁免条目在 git diff 里看得见** —— 属于「可审计的承诺」，不是隐藏

## 硬规则

1. **CI 一律不签名**，签名在设备端用 SideStore 侧载时完成
2. **Apple ID / 证书 / profile 绝不进 secrets**，也绝不入库
3. 若将来 CI 要直出可安装包，**必须用 App Store Connect API Key**，不得用 `-allowProvisioningUpdates` 搭配 Apple ID —— 出现该配置评审直接打回
