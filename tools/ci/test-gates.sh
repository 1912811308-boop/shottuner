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
. "$SCRIPT_DIR/lib/common.sh"

ci_log "▶ 门禁反向验证"

# 被注入的字面量从清单读（纪律：脚本里不写）
NETPAT="$(ci_load_patterns "network-source.txt" | head -1)"
CREDPAT="$(ci_load_patterns "apple-secrets.txt" | head -1)"
EMPTYVAR="$(ci_load_patterns "apple-signing-empty.txt" | head -1)"
[ -n "$NETPAT" ]  || ci_die "network-source.txt 为空"
[ -n "$CREDPAT" ] || ci_die "apple-secrets.txt 为空"
[ -n "$EMPTYVAR" ] || ci_die "apple-signing-empty.txt 为空"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0

# expect <期望: pass|fail> <用例名> <命令...>
expect() {
  local want="$1" name="$2"; shift 2
  local rc=0
  "$@" >"$WORK/out.txt" 2>&1 || rc=$?
  if [ "$want" = "pass" ] && [ "$rc" -eq 0 ]; then
    ci_info "  ✓ $name（期望绿，实得绿）"; PASS=$((PASS+1)); return 0
  fi
  if [ "$want" = "fail" ] && [ "$rc" -ne 0 ]; then
    ci_info "  ✓ $name（期望红，实得红，exit=$rc）"; PASS=$((PASS+1)); return 0
  fi
  ci_error "-" "-" "反向验证失败：$name —— 期望 $want，实得 exit=$rc"
  sed 's/^/      /' "$WORK/out.txt" | head -12 >&2
  FAIL=$((FAIL+1)); return 0
}

# ── 构造一个最小仓库 ────────────────────────────────────────────────────────
REPO="$WORK/repo"
mkdir -p "$REPO/ios" "$REPO/shared/src" "$REPO/docs" "$REPO/tools/ci"
cp -r "$SCRIPT_DIR/lib" "$SCRIPT_DIR/patterns" "$REPO/tools/ci/"
cp "$SCRIPT_DIR/check-no-network.sh" "$SCRIPT_DIR/check-no-network-config.sh" \
   "$SCRIPT_DIR/check-apple-credentials.sh" "$SCRIPT_DIR/check-ios-project-spec.sh" \
   "$REPO/tools/ci/"
chmod +x "$REPO/tools/ci/"*.sh

printf '// 初始内容\nval a = 1\n' > "$REPO/shared/src/Seed.kt"
printf 'import Foundation\n' > "$REPO/ios/Seed.swift"
printf '# 初始文档\n' > "$REPO/docs/seed.md"

L1="$REPO/tools/ci/check-no-network.sh"
L2="$REPO/tools/ci/check-no-network-config.sh"
G1D="$REPO/tools/ci/check-apple-credentials.sh"

ci_info ""
ci_info "【坑 1】注释行不应触发红灯"
printf '// 说明：这里不要用 %s\nval b = 2\n' "$NETPAT" > "$REPO/shared/src/Commented.kt"
expect pass "被禁词只出现在 .kt 注释里 → L1 绿" "$L1" "$REPO"
rm -f "$REPO/shared/src/Commented.kt"

ci_info ""
ci_info "【坑 2】文档散文不应触发红灯"
printf '文档里讨论了 %s 的用法\n' "$NETPAT" > "$REPO/docs/talk.md"
expect pass "被禁词出现在 docs/*.md → L1 绿" "$L1" "$REPO"
expect pass "被禁词出现在 docs/*.md → L2 绿" "$L2" "$REPO"
rm -f "$REPO/docs/talk.md"

ci_info ""
ci_info "【坑 3】门禁脚本与清单自身不应触发红灯"
expect pass "被禁词只在 patterns/*.txt 里 → L1 绿" "$L1" "$REPO"
expect pass "被禁词只在 patterns/*.txt 里 → G1-d 绿" "$G1D" "$REPO"

ci_info ""
ci_info "【真违规必须红】"
printf 'import Foundation\nlet x = %s.shared\n' "$NETPAT" > "$REPO/ios/Bad.swift"
expect fail "被禁词出现在 ios/*.swift 代码里 → L1 红" "$L1" "$REPO"
rm -f "$REPO/ios/Bad.swift"

printf 'val c = %s\n' "$NETPAT" > "$REPO/shared/src/Bad.kt"
expect fail "被禁词出现在 shared/src/*.kt 代码里 → L1 红" "$L1" "$REPO"
rm -f "$REPO/shared/src/Bad.kt"

ci_info ""
ci_info "【凭据门禁】"
printf 'jobs:\n  build:\n    env:\n      %s: whatever\n' "$CREDPAT" > "$REPO/ci.yml"
expect fail "凭据特征出现在 .yml → G1-d 红" "$G1D" "$REPO"
rm -f "$REPO/ci.yml"

printf '文档里提到了 %s 这个词\n' "$CREDPAT" > "$REPO/docs/cred.md"
expect fail "凭据特征出现在 .md → G1-d 红" "$G1D" "$REPO"
rm -f "$REPO/docs/cred.md"

ci_info ""
ci_info "【签名开关必须恒为空】"
printf 'build:\n  settings:\n    %s: "Apple Development: someone"\n' "$EMPTYVAR" > "$REPO/sig.yml"
expect fail "签名变量非空 → G1-d 红" "$G1D" "$REPO"
rm -f "$REPO/sig.yml"

printf 'build:\n  settings:\n    %s: ""\n' "$EMPTYVAR" > "$REPO/sig.yml"
expect pass "签名变量为空 → G1-d 绿" "$G1D" "$REPO"
rm -f "$REPO/sig.yml"

ci_info ""
ci_info "【L2 配置层】"
printf 'targets:\n  App:\n    settings:\n      %s: YES\n' "$(ci_load_patterns 'ios-config-must-be-no.txt' | head -1)" > "$REPO/ios/project.yml"
expect fail "签名开关非 NO → L2 红" "$L2" "$REPO"
rm -f "$REPO/ios/project.yml"

printf 'targets:\n  App:\n    settings:\n      %s: NO\n' "$(ci_load_patterns 'ios-config-must-be-no.txt' | head -1)" > "$REPO/ios/project.yml"
expect pass "签名开关为 NO → L2 绿" "$L2" "$REPO"
rm -f "$REPO/ios/project.yml"

# ── iOS 工程规格门禁（T10 新增）──────────────────────────────────────────
# ★ 这道门禁也必须被反向验证：它检查的是**纯文本**，
#   一旦规则写反（例如把签名开关的判断写反），就会变成「永远绿」的假门禁。
ci_info ""
ci_info "【iOS 工程规格门禁】"
SPEC="$REPO/tools/ci/check-ios-project-spec.sh"

mkdir -p "$REPO/ios/ShotTuner" "$REPO/ios/Scripts" "$REPO/shared/src/iosMain/kotlin"

cat > "$REPO/ios/project.yml" <<'YAML'
name: ShotTuner
options:
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

cat > "$REPO/ios/ShotTuner/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
	<key>NSCameraUsageDescription</key>
	<string>测试用的相机权限文案</string>
</dict>
</plist>
PLIST

printf '#!/bin/sh\n./gradlew :shared:embedAndSignAppleFrameworkForXcode\n' \
  > "$REPO/ios/Scripts/kmp-build-framework.sh"
printf 'package com.shottuner.app.ios\n' > "$REPO/shared/src/iosMain/kotlin/Seed.kt"

expect pass "规格合规 → 规格门禁绿" "$SPEC" "$REPO"

sed -i 's/CODE_SIGNING_ALLOWED: "NO"/CODE_SIGNING_ALLOWED: "YES"/' "$REPO/ios/project.yml"
expect fail "签名开关不是 NO → 规格门禁红" "$SPEC" "$REPO"
sed -i 's/CODE_SIGNING_ALLOWED: "YES"/CODE_SIGNING_ALLOWED: "NO"/' "$REPO/ios/project.yml"

sed -i 's/iOS: "16.0"/iOS: "16.1"/' "$REPO/ios/project.yml"
expect fail "部署目标不是 16.0 → 规格门禁红" "$SPEC" "$REPO"
sed -i 's/iOS: "16.1"/iOS: "16.0"/' "$REPO/ios/project.yml"

printf 'val x = 1\n' > "$REPO/shared/src/iosMain/kotlin/Seed.kt"
rm -f "$REPO/shared/src/iosMain/kotlin/Seed.kt"
expect fail "iosMain 源集是空的 → 规格门禁红" "$SPEC" "$REPO"

ci_log ""
if [ "$FAIL" -gt 0 ]; then
  ci_log "✗ 反向验证未通过：$PASS 项符合预期，$FAIL 项不符"
  exit 1
fi
ci_log "✓ 反向验证全部通过（$PASS 项）"
exit 0
