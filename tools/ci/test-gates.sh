#!/usr/bin/env bash
# tools/ci/test-gates.sh —— 门禁的反向验证（门禁自己也要被测试）。
#
# 为什么必须有这个文件：
#   一道门禁如果永远绿，它可能在装样子；一道门禁如果永远红，它就是噪声，
#   而**红一次的 CI 门禁等于没有门禁**（团队会立刻学会忽略它）。
#   所以门禁必须有一张"注入什么、期望什么"的对照表，而且这张表要能自动跑。
#
# ★ 本脚本**不写任何被禁字面量** —— 测试要注入的那个词，从 patterns/*.txt 里读。
#   否则本文件自己就成了违规源（坑 3）。
#
# 用法： tools/ci/test-gates.sh
# 退出码： 0 = 全部符合预期；1 = 有用例与预期不符；2 = 测试自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"

ci_log "▶ 门禁反向验证"

# 被注入的字面量从清单读（纪律：脚本里不写）
NETPAT="$(ci_load_patterns "network-source.txt" | head -1)"
CREDPAT="$(ci_load_patterns "apple-secrets.txt" | head -1)"
EMPTYVAR="$(ci_load_patterns "apple-signing-empty.txt" | head -1)"
[ -n "${NETPAT}" ]  || ci_die "network-source.txt 为空"
[ -n "${CREDPAT}" ] || ci_die "apple-secrets.txt 为空"
[ -n "${EMPTYVAR}" ] || ci_die "apple-signing-empty.txt 为空"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

PASS=0
FAIL=0

# expect <期望: pass|fail> <用例名> <命令...>
expect() {
  local want="${1}" name="${2}"; shift 2
  local rc=0
  "${@}" >"${WORK}/out.txt" 2>&1 || rc=${?}
  if [ "${want}" = "pass" ] && [ "${rc}" -eq 0 ]; then
    ci_info "  ✓ ${name}（期望绿，实得绿）"; PASS=$((PASS+1)); return 0
  fi
  if [ "${want}" = "fail" ] && [ "${rc}" -ne 0 ]; then
    ci_info "  ✓ ${name}（期望红，实得红，exit=${rc}）"; PASS=$((PASS+1)); return 0
  fi
  ci_error "-" "-" "反向验证失败：${name} —— 期望 ${want}，实得 exit=${rc}"
  sed 's/^/      /' "${WORK}/out.txt" | head -12 >&2
  FAIL=$((FAIL+1)); return 0
}

# ── 构造一个最小仓库 ────────────────────────────────────────────────────────
REPO="${WORK}/repo"
mkdir -p "${REPO}/ios" "${REPO}/shared/src" "${REPO}/docs" "${REPO}/tools/ci"
cp -r "${SCRIPT_DIR}/lib" "${SCRIPT_DIR}/patterns" "${REPO}/tools/ci/"
cp "${SCRIPT_DIR}/check-no-network.sh" "${SCRIPT_DIR}/check-no-network-config.sh" \
   "${SCRIPT_DIR}/check-apple-credentials.sh" "${SCRIPT_DIR}/check-ios-project-spec.sh" \
   "${SCRIPT_DIR}/check-swift-contract-args.sh" \
   "${REPO}/tools/ci/"
chmod +x "${REPO}/tools/ci/"*.sh

printf '// 初始内容\nval a = 1\n' > "${REPO}/shared/src/Seed.kt"
printf 'import Foundation\n' > "${REPO}/ios/Seed.swift"
printf '# 初始文档\n' > "${REPO}/docs/seed.md"

L1="${REPO}/tools/ci/check-no-network.sh"
L2="${REPO}/tools/ci/check-no-network-config.sh"
G1D="${REPO}/tools/ci/check-apple-credentials.sh"

ci_info ""
ci_info "【坑 1】注释行不应触发红灯"
printf '// 说明：这里不要用 %s\nval b = 2\n' "${NETPAT}" > "${REPO}/shared/src/Commented.kt"
expect pass "被禁词只出现在 .kt 注释里 → L1 绿" "${L1}" "${REPO}"
rm -f "${REPO}/shared/src/Commented.kt"

ci_info ""
ci_info "【坑 2】文档散文不应触发红灯"
printf '文档里讨论了 %s 的用法\n' "${NETPAT}" > "${REPO}/docs/talk.md"
expect pass "被禁词出现在 docs/*.md → L1 绿" "${L1}" "${REPO}"
expect pass "被禁词出现在 docs/*.md → L2 绿" "${L2}" "${REPO}"
rm -f "${REPO}/docs/talk.md"

ci_info ""
ci_info "【坑 3】门禁脚本与清单自身不应触发红灯"
expect pass "被禁词只在 patterns/*.txt 里 → L1 绿" "${L1}" "${REPO}"
expect pass "被禁词只在 patterns/*.txt 里 → G1-d 绿" "${G1D}" "${REPO}"

ci_info ""
ci_info "【真违规必须红】"
printf 'import Foundation\nlet x = %s.shared\n' "${NETPAT}" > "${REPO}/ios/Bad.swift"
expect fail "被禁词出现在 ios/*.swift 代码里 → L1 红" "${L1}" "${REPO}"
rm -f "${REPO}/ios/Bad.swift"

printf 'val c = %s\n' "${NETPAT}" > "${REPO}/shared/src/Bad.kt"
expect fail "被禁词出现在 shared/src/*.kt 代码里 → L1 红" "${L1}" "${REPO}"
rm -f "${REPO}/shared/src/Bad.kt"

ci_info ""
ci_info "【凭据门禁】"
printf 'jobs:\n  build:\n    env:\n      %s: whatever\n' "${CREDPAT}" > "${REPO}/ci.yml"
expect fail "凭据特征出现在 .yml → G1-d 红" "${G1D}" "${REPO}"
rm -f "${REPO}/ci.yml"

printf '文档里提到了 %s 这个词\n' "${CREDPAT}" > "${REPO}/docs/cred.md"
expect fail "凭据特征出现在 .md → G1-d 红" "${G1D}" "${REPO}"
rm -f "${REPO}/docs/cred.md"

ci_info ""
ci_info "【签名开关必须恒为空】"
printf 'build:\n  settings:\n    %s: "Apple Development: someone"\n' "${EMPTYVAR}" > "${REPO}/sig.yml"
expect fail "签名变量非空 → G1-d 红" "${G1D}" "${REPO}"
rm -f "${REPO}/sig.yml"

printf 'build:\n  settings:\n    %s: ""\n' "${EMPTYVAR}" > "${REPO}/sig.yml"
expect pass "签名变量为空 → G1-d 绿" "${G1D}" "${REPO}"
rm -f "${REPO}/sig.yml"

ci_info ""
ci_info "【L2 配置层】"
printf 'targets:\n  App:\n    settings:\n      %s: YES\n' "$(ci_load_patterns 'ios-config-must-be-no.txt' | head -1)" > "${REPO}/ios/project.yml"
expect fail "签名开关非 NO → L2 红" "${L2}" "${REPO}"
rm -f "${REPO}/ios/project.yml"

printf 'targets:\n  App:\n    settings:\n      %s: NO\n' "$(ci_load_patterns 'ios-config-must-be-no.txt' | head -1)" > "${REPO}/ios/project.yml"
expect pass "签名开关为 NO → L2 绿" "${L2}" "${REPO}"
rm -f "${REPO}/ios/project.yml"

# ── iOS 工程规格门禁（T10 新增）──────────────────────────────────────────
# ★ 这道门禁也必须被反向验证：它检查的是**纯文本**，
#   一旦规则写反（例如把签名开关的判断写反），就会变成「永远绿」的假门禁。
ci_info ""
ci_info "【iOS 工程规格门禁】"
SPEC="${REPO}/tools/ci/check-ios-project-spec.sh"

mkdir -p "${REPO}/ios/ShotTuner" "${REPO}/ios/Scripts" "${REPO}/shared/src/iosMain/kotlin"

cat > "${REPO}/ios/project.yml" <<'YAML'
name: ShotTuner
options:
  projectFormat: xcode15_0
  deploymentTarget:
    iOS: "16.0"
targets:
  ShotTuner:
    type: application
    platform: iOS
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.shottuner.app
        CODE_SIGNING_ALLOWED: "NO"
        CODE_SIGNING_REQUIRED: "NO"
        CODE_SIGN_IDENTITY: ""
YAML

cat > "${REPO}/ios/ShotTuner/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>NSCameraUsageDescription</key>
	<string>测试用的相机权限文案</string>
</dict>
</plist>
PLIST

printf '#!/bin/sh\n./gradlew :shared:embedAndSignAppleFrameworkForXcode\n' \
  > "${REPO}/ios/Scripts/kmp-build-framework.sh"
printf 'package com.shottuner.app.ios\n' > "${REPO}/shared/src/iosMain/kotlin/Seed.kt"
printf 'kotlin {\n    iosArm64().binaries.framework {\n        baseName = "Shared"\n    }\n}\n' \
  > "${REPO}/shared/build.gradle.kts"

# ── 导出选项（未签名口径）───────────────────────────────────────────────────
# ★ 为什么要给它单独准备 fixture：
#   ios/ExportOptions.plist 是「要签成什么样」的声明 —— 它是硬规则一
#   （CI 一律不签名）在出包链路上的落点，所以必须被反向验证。
# ★ 纪律：这里写的是**要被注入的坏值**，但它不是「凭据」——
#   它是凭空捏造的占位串，仓库里任何地方都不该有真的证书名。
write_export_opts() {
  cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string></string>
	<key>provisioningProfiles</key>
	<dict/>
	<key>teamID</key>
	<string></string>
</dict>
</plist>
PLIST
}
write_export_opts

expect pass "规格合规 → 规格门禁绿" "${SPEC}" "${REPO}"

sed -i 's/CODE_SIGNING_ALLOWED: "NO"/CODE_SIGNING_ALLOWED: "YES"/' "${REPO}/ios/project.yml"
expect fail "签名开关不是 NO → 规格门禁红" "${SPEC}" "${REPO}"
sed -i 's/CODE_SIGNING_ALLOWED: "YES"/CODE_SIGNING_ALLOWED: "NO"/' "${REPO}/ios/project.yml"

sed -i 's/iOS: "16.0"/iOS: "16.1"/' "${REPO}/ios/project.yml"
expect fail "部署目标不是 16.0 → 规格门禁红" "${SPEC}" "${REPO}"
sed -i 's/iOS: "16.1"/iOS: "16.0"/' "${REPO}/ios/project.yml"

sed -i 's/projectFormat: xcode15_0/projectFormat: xcode16_0/' "${REPO}/ios/project.yml"
expect fail "工程格式不是 xcode15_0 → 规格门禁红" "${SPEC}" "${REPO}"
sed -i 's/projectFormat: xcode16_0/projectFormat: xcode15_0/' "${REPO}/ios/project.yml"

printf 'val x = 1\n' > "${REPO}/shared/src/iosMain/kotlin/Seed.kt"
rm -f "${REPO}/shared/src/iosMain/kotlin/Seed.kt"
expect fail "iosMain 源集是空的 → 规格门禁红" "${SPEC}" "${REPO}"

sed -i 's/binaries\.framework {//' "${REPO}/shared/build.gradle.kts"
expect fail "shared 没声明 framework 二进制 → 规格门禁红" "${SPEC}" "${REPO}"

printf '#!/bin/sh\nif [ 1 ; then\n' > "${REPO}/ios/Scripts/Broken.sh"
expect fail "iOS 的 shell 脚本语法错 → 规格门禁红" "${SPEC}" "${REPO}"
rm -f "${REPO}/ios/Scripts/Broken.sh"

# ── 导出选项的反向验证（硬规则一在出包链路上的落点）────────────────────────
ci_info ""
ci_info "【导出选项必须恒为未签名】"

# ① 非空的签名身份 → 必须红
cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string>Fake Signing Identity For Test</string>
	<key>provisioningProfiles</key>
	<dict/>
	<key>teamID</key>
	<string></string>
</dict>
</plist>
PLIST
expect fail "导出选项里有非空签名身份 → 规格门禁红" "${SPEC}" "${REPO}"

# ② 非空的 teamID → 必须红
cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string></string>
	<key>provisioningProfiles</key>
	<dict/>
	<key>teamID</key>
	<string>FAKETEAM12</string>
</dict>
</plist>
PLIST
expect fail "导出选项里有非空 teamID → 规格门禁红" "${SPEC}" "${REPO}"

# ③ 非空的 provisioningProfiles → 必须红
cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string></string>
	<key>provisioningProfiles</key>
	<dict>
		<key>com.shottuner.app</key>
		<string>FakeProfile</string>
	</dict>
	<key>teamID</key>
	<string></string>
</dict>
</plist>
PLIST
expect fail "导出选项里有非空 provisioningProfiles → 规格门禁红" "${SPEC}" "${REPO}"

# ④ signingStyle=automatic → 必须红（automatic 会让 Xcode 自己去「找」证书）
cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>signingCertificate</key>
	<string></string>
	<key>provisioningProfiles</key>
	<dict/>
	<key>teamID</key>
	<string></string>
</dict>
</plist>
PLIST
expect fail "导出选项的 signingStyle 是 automatic → 规格门禁红" "${SPEC}" "${REPO}"

# ⑤ method 用了旧版拼写 → 必须红
#   实测踩过（2026-10-29）：Xcode 15 不认 `release` 这个 method 值了，
#   报错是 "expected one {app-store-connect, release-testing, ...} but found release" ——
#   看起来像「签名做不了」，其实只是版本改名。写进门禁就不用再靠人记。
cat > "${REPO}/ios/ExportOptions.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>method</key>
	<string>release</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string></string>
	<key>provisioningProfiles</key>
	<dict/>
	<key>teamID</key>
	<string></string>
</dict>
</plist>
PLIST
expect fail "导出选项的 method 是旧版拼写 → 规格门禁红" "${SPEC}" "${REPO}"

# ⑥ 恢复合规状态 → 必须绿（证明上面 5 条不是「永远红」）
#   ★ 注意：前面那几条用例为了制造违规，**故意破坏了 shared 的 fixture**
#     （删了 iosMain 的 .kt、刮掉了 binaries.framework）且没有还原。
#     所以这里必须先把它复原，否则这条「期望绿」会带着前面积累的违规一起红 ——
#     那就不是在测导出选项了。
printf 'package com.shottuner.app.ios\n' > "${REPO}/shared/src/iosMain/kotlin/Seed.kt"
printf 'kotlin {\n    iosArm64().binaries.framework {\n        baseName = "Shared"\n    }\n}\n' \
  > "${REPO}/shared/build.gradle.kts"
write_export_opts
expect pass "导出选项恢复未签名口径 → 规格门禁绿" "${SPEC}" "${REPO}"

# ── Swift 契约参数名门禁（单向）────────────────────────────────────────────
# ★ 这道门禁守的坑：Windows 上 CI 只跑 shell 门禁、不编 Swift，
#   所以「契约把字段改名、调用点还写着旧名字」这类硬错会静默通过，
#   一路潜伏到 Mac 第一次 build。
#
# ★ 它自己也会坏：括号配平写错 → 一个调用点都扫不到 → 永远绿（最坏的形态）。
#   所以下面这几条用例**必须包含"期望红"**，其中第 ② 和 ⑦ 条正好能抓住那种坏法。
ci_info ""
ci_info "【Swift 契约参数名门禁】"
CONTRACT="${REPO}/tools/ci/check-swift-contract-args.sh"

mkdir -p "${REPO}/ios/ShotTuner/Score" "${REPO}/ios/ShotTuner/Mission"

# 契约 fixture：故意把四种"不是初始化参数"的东西都摆进来
#   · beta        带默认值 → 调用点可以跳过（用来证明本门禁**不比参数个数**）
#   · computed    计算属性   → 不是参数
#   · zero        static 成员 → 不是参数
#   · Nested.inner 嵌套类型成员 → 不是参数
cat > "${REPO}/ios/ShotTuner/Score/ScoreTypes.swift" <<'SWIFT'
struct SampleSnapshot: Equatable {
    var alpha: Float
    var beta: Bool = false
    var child: OtherSnapshot?

    var computed: Bool { alpha > 0 }
    static let zero: Float = 0

    struct Nested {
        var inner: Int
    }
}
SWIFT

printf 'struct OtherSnapshot {\n    var gamma: Int\n}\n' \
  > "${REPO}/ios/ShotTuner/Mission/MissionTypes.swift"

CALLER="${REPO}/ios/Caller.swift"

# ① 合规：只传必填、跳过带默认值的 beta → 绿（证明不比个数）
printf 'let a = SampleSnapshot(alpha: 1)\n' > "${CALLER}"
expect pass "契约 + 只传必填参数（跳过带默认值的字段）→ 契约门禁绿" "${CONTRACT}" "${REPO}"

# ①-b 全字段都传 → 绿
printf 'let a = SampleSnapshot(alpha: 1, beta: true, child: nil)\n' > "${CALLER}"
expect pass "契约 + 全字段都传 → 契约门禁绿" "${CONTRACT}" "${REPO}"

# ② 不存在的名字 → 红（核心用例：契约改了名、调用点没跟上）
printf 'let a = SampleSnapshot(alpha: 1, delta: true)\n' > "${CALLER}"
expect fail "调用点用了契约里没有的参数名 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ③ 计算属性当参数 → 红
printf 'let a = SampleSnapshot(alpha: 1, computed: true)\n' > "${CALLER}"
expect fail "把计算属性当初始化参数 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ④ static 成员当参数 → 红
printf 'let a = SampleSnapshot(alpha: 1, zero: 0)\n' > "${CALLER}"
expect fail "把 static 成员当初始化参数 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ⑤ 嵌套类型的成员当参数 → 红
printf 'let a = SampleSnapshot(alpha: 1, inner: 2)\n' > "${CALLER}"
expect fail "把嵌套类型的成员当初始化参数 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ⑥ 坏名字只出现在注释里 → 绿（剥注释是前提，否则会误报）
printf '// 反例：SampleSnapshot(omega: 1)\nlet a = SampleSnapshot(alpha: 1)\n' > "${CALLER}"
expect pass "坏参数名只出现在注释里 → 契约门禁绿" "${CONTRACT}" "${REPO}"

# ⑦ 跨行 + 嵌套调用里的坏名字 → 红
#    这一条同时验证两件事：多行调用能被合并，嵌套调用里的参数不会被外层吞掉。
printf 'let b = SampleSnapshot(\n    alpha: 1,\n    child: OtherSnapshot(gamma: 1, oops: 2)\n)\n' > "${CALLER}"
expect fail "跨行嵌套调用里的坏参数名 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ⑧ 第二个契约文件里的结构体也要被覆盖 → 红
printf 'let c = OtherSnapshot(gamma: 1, oops: 2)\n' > "${CALLER}"
expect fail "第二个契约文件里的结构体同样受检 → 契约门禁红" "${CONTRACT}" "${REPO}"

# ⑨ 类型标注不是构造点 → 绿
printf 'var typed: SampleSnapshot { SampleSnapshot(alpha: 3) }\n' > "${CALLER}"
expect pass "类型标注 + 内部的合法构造 → 契约门禁绿" "${CONTRACT}" "${REPO}"

# ⑩ ContentView.swift 临时排除（★绊线）
#    接线期间它被排除在扫描之外。等 team-lead 把它从 EXCLUDED_WHILE_EDITING 里删掉，
#    这条「期望绿」会自动变成红 —— 那是故意的：逼改的人把本条一起改成 expect fail。
rm -f "${CALLER}"
printf 'let z = SampleSnapshot(omega: 1)\n' \
  > "${REPO}/ios/ShotTuner/ContentView.swift"
expect pass "ContentView.swift 在临时排除表里 → 契约门禁绿" "${CONTRACT}" "${REPO}"
rm -f "${REPO}/ios/ShotTuner/ContentView.swift"

# ⑪ 排除表清空后，同一个坏调用必须变红（证明 ⑩ 的绿是「被排除」而不是「扫不到」）
printf 'let z = SampleSnapshot(omega: 1)\n' > "${CALLER}"
expect fail "同一个坏调用放在被扫文件里 → 契约门禁红" "${CONTRACT}" "${REPO}"
rm -f "${CALLER}"

ci_log ""
if [ "${FAIL}" -gt 0 ]; then
  ci_log "✗ 反向验证未通过：${PASS} 项符合预期，${FAIL} 项不符"
  exit 1
fi
ci_log "✓ 反向验证全部通过（${PASS} 项）"
exit 0
