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

# -- Test count summary --------------------------------------------------------
# Why this block exists:
#   `gradle test` prints only "BUILD SUCCESSFUL"; it never says how many cases
#   ran. Counting build/test-results by hand goes wrong in two ways, both seen
#   for real during acceptance:
#     1) testDebugUnitTest and testReleaseUnitTest run the SAME cases twice;
#        adding both up doubles the total (105 + 172 gets reported as 554);
#     2) a class marked @Ignore still appears in the XML as tests=N / skipped=N,
#        so summing `tests` alone silently counts skipped cases as executed.
#   => count the debug variant only, and report executed = tests - skipped.
#   Keep this block ASCII-only: PS 5.1 reads .ps1 as ANSI when there is no BOM.
function Write-TestCountSummary {
    param([string]$ProjectDir)

    $rows = @()
    foreach ($module in @('app', 'shared')) {
        $resultDir = Join-Path (Join-Path $ProjectDir $module) 'build\test-results\testDebugUnitTest'
        if (-not (Test-Path -LiteralPath $resultDir)) { continue }

        $classes = 0; $tests = 0; $skipped = 0; $failures = 0; $errors = 0
        foreach ($xmlFile in (Get-ChildItem -LiteralPath $resultDir -Filter 'TEST-*.xml' -File)) {
            $suite = ([xml](Get-Content -LiteralPath $xmlFile.FullName -Raw -Encoding UTF8)).testsuite
            if ($null -eq $suite) { continue }
            $classes = $classes + 1
            $tests = $tests + [int]$suite.tests
            $skipped = $skipped + [int]$suite.skipped
            $failures = $failures + [int]$suite.failures
            $errors = $errors + [int]$suite.errors
        }
        $rows += [pscustomobject]@{
            Module   = $module
            Classes  = $classes
            Tests    = $tests
            Skipped  = $skipped
            Executed = $tests - $skipped
            Failures = $failures
            Errors   = $errors
        }
    }

    ""
    "================ TEST COUNT (testDebugUnitTest only) ================"
    if ($rows.Count -eq 0) {
        "no test-results found -- did the test task actually run?"
        return
    }
    foreach ($row in $rows) {
        "{0,-6} classes={1,-3} tests={2,-4} skipped={3,-3} executed={4,-4} failures={5} errors={6}" -f `
            $row.Module, $row.Classes, $row.Tests, $row.Skipped, $row.Executed, $row.Failures, $row.Errors
    }
    $sumClasses = ($rows | Measure-Object -Property Classes -Sum).Sum
    $sumTests = ($rows | Measure-Object -Property Tests -Sum).Sum
    $sumSkipped = ($rows | Measure-Object -Property Skipped -Sum).Sum
    $sumFailures = ($rows | Measure-Object -Property Failures -Sum).Sum
    $sumErrors = ($rows | Measure-Object -Property Errors -Sum).Sum
    "{0,-6} classes={1,-3} tests={2,-4} skipped={3,-3} executed={4,-4} failures={5} errors={6}" -f `
        "TOTAL", $sumClasses, $sumTests, $sumSkipped, ($sumTests - $sumSkipped), $sumFailures, $sumErrors
    "NOTE: release variant runs the SAME cases -- never add debug + release."
    "NOTE: executed = tests - skipped (@Ignore manual tools are not executed)."
}

Write-TestCountSummary -ProjectDir $project

"GRADLE_EXIT=$code"
exit $code
