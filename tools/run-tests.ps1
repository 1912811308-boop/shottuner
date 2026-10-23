# 运行单元测试（Windows / 本机）。
#
# 为什么需要这个脚本，而不是直接 `gradle test`：
# 本仓库位于含中文的目录（D:\<中文>\Claw\shottuner）。中文版 Windows 上 JDK 17 的
# sun.jnu.encoding 默认落在 ANSI 代码页，Gradle fork 出去的 Test Worker 用错字符集
# 解析 -cp 参数，中文路径被读成乱码，结果是**所有**测试类统一报
# java.lang.ClassNotFoundException（连原有的 PhotographyBookTest 一起挂），
# 看起来像代码挂了，其实是环境问题。
#
# 绕法：用 subst 把中文目录映射成 S:\，整条路径变纯 ASCII，worker 就能正常加载类。
#
# 附带两点：
#   1) 脚本内容刻意不含任何非 ASCII 字面量 —— Windows PowerShell 5.1 按 ANSI 读 .ps1，
#      写中文会被读乱（目标路径因此从 $PSScriptRoot 反推）。
#   2) Kotlin 增量缓存会把"绝对路径"当键，D:\ 与 S:\ 两套路径混用会让 IC 报
#      "different roots" 然后退化成全量编译；本脚本固定走 S:\，另加
#      -Pkotlin.incremental=false 保证结果确定。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\run-tests.ps1
#   powershell -ExecutionPolicy Bypass -File tools\run-tests.ps1 -Rerun
param(
    [switch]$Rerun
)

$ErrorActionPreference = 'Continue'

$projectDir = Split-Path $PSScriptRoot -Parent            # ...\shottuner
$clawDir = Split-Path $projectDir -Parent                 # ...\Claw
$target = Split-Path $clawDir -Parent                     # D:\<中文>
$clawName = Split-Path $clawDir -Leaf                     # Claw

if (-not (Test-Path 'S:\')) {
    subst S: $target
    if ($LASTEXITCODE -ne 0) { throw "subst S: failed with exit code $LASTEXITCODE" }
}

$project = 'S:\' + $clawName + '\shottuner'
$tempDir = 'S:\' + $clawName + '\shottuner\work\tmp'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

# local.properties 里写 sdk.dir 的 S: 形式，退出时还原，避免污染仓库状态。
$localProperties = Join-Path $project 'local.properties'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$hasOriginal = Test-Path $localProperties
$original = ''
if ($hasOriginal) { $original = [System.IO.File]::ReadAllText($localProperties) }

$env:JAVA_HOME = 'S:\' + $clawName + '\.toolchain\jdk-17.0.2'
$env:ANDROID_HOME = 'S:\' + $clawName + '\.toolchain\sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:TEMP = $tempDir
$env:TMP = $tempDir

$code = 1
try {
    [System.IO.File]::WriteAllText($localProperties, 'sdk.dir=S:/' + $clawName + '/.toolchain/sdk' + "`n", $utf8)

    $gradle = 'S:\' + $clawName + '\.toolchain\gradle-8.9\bin\gradle.bat'
    $arguments = @(
        '-p', $project,
        '--no-daemon', '--console=plain', '--offline',
        '-Pkotlin.incremental=false'
    )
    if ($Rerun) { $arguments += '--rerun-tasks' }
    $arguments += 'test'

    & $gradle @arguments
    $code = $LASTEXITCODE
} finally {
    if ($hasOriginal) {
        [System.IO.File]::WriteAllText($localProperties, $original, $utf8)
    } else {
        Remove-Item $localProperties -ErrorAction SilentlyContinue
    }
}

"GRADLE_EXIT=$code"
exit $code
