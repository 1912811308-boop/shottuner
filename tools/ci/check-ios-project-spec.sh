#!/usr/bin/env bash
# tools/ci/check-ios-project-spec.sh —— iOS 工程规格自检。
#
# 验什么：ios/project.yml 与 ios/ShotTuner/Info.plist 是否满足本项目的**硬规则**。
# 为什么需要它：这两个文件是**纯文本**，Windows 上也能改 ——
# 也就意味着「改错了没人拦」。pbxproj 那种生成物有 Xcode 兜底，
# 而 YAML 只有人眼。所以把它变成机器可查的断言。
#
# ★ 为什么不放在 Xcode 那道门禁里（要 macOS）：
#   这道门禁**只需要读文本**，Linux runner 就能跑 —— 不占 macOS 配额。
#   macOS 只用来验「真能编出来」（ci-gates.yml 的 ios-compile job）。
#
# ★ 纪律：本脚本**不写任何被禁字面量** —— 需要检查的字面量从 patterns/*.txt 读。
#
# 用法： tools/ci/check-ios-project-spec.sh [仓库根目录]
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

ROOT="${1:-$(ci_repo_root "$SCRIPT_DIR")}"
[ -d "$ROOT" ] || ci_die "仓库根目录不存在：$ROOT"

ci_log "▶ iOS 工程规格自检（根目录：$ROOT）"

SPEC="$ROOT/ios/project.yml"
PLIST="$ROOT/ios/ShotTuner/Info.plist"
SCRIPT_BUILD="$ROOT/ios/Scripts/kmp-build-framework.sh"

VIOLATIONS=0
violation() {
  ci_error "$1" "-" "$2"
  VIOLATIONS=$((VIOLATIONS + 1))
}

# setting_value <文件> <键> —— 取 YAML 里 "键: 值" 的值（去掉引号与空白）。
setting_value() {
  local file="$1" key="$2"
  grep -E "^[[:space:]]*${key}[[:space:]]*:" "$file" 2>/dev/null \
    | head -1 \
    | sed -E 's/.*:[[:space:]]*//' \
    | tr -d '"'"'" \
    | tr -d '[:space:]'
}

# ── 0) 文件必须存在 ────────────────────────────────────────────────────────
if [ ! -f "$SPEC" ]; then
  ci_die "找不到 ios/project.yml —— iOS 工程的单一事实来源"
fi
if [ ! -f "$PLIST" ]; then
  violation "ios/ShotTuner/Info.plist" "缺少 Info.plist（权限文案是产品口径，必须在仓库里可审）"
fi

# ── 1) target：只有一个，且不含任何 App Extension ─────────────────────────
# ★ 第一批（D3 决策）不含 Live Activity / Widget / Share Extension / Watch / Action Button。
if ! grep -qE "^[[:space:]]+ShotTuner:[[:space:]]*$" "$SPEC"; then
  violation "ios/project.yml" "找不到 ShotTuner target"
fi

APP_TARGET_COUNT="$(grep -cE "^[[:space:]]*type:[[:space:]]*application[[:space:]]*$" "$SPEC" || true)"
if [ "$APP_TARGET_COUNT" -ne 1 ]; then
  violation "ios/project.yml" "application target 必须恰好 1 个，实际 $APP_TARGET_COUNT 个"
fi

if grep -qE "^[[:space:]]*type:[[:space:]]*(app-extension|extension|watch2-app|watch2-extension)[[:space:]]*$" "$SPEC"; then
  violation "ios/project.yml" "第一批不允许出现 App Extension target（推到第二批）"
fi

if [ -d "$ROOT/ios" ]; then
  if find "$ROOT/ios" -name '*.entitlements' -print -quit | grep -q .; then
    violation "ios/" "第一批没有 App Extension，就不需要 entitlements 文件"
  fi
fi

# ── 2) 部署目标：iOS 16.0 ─────────────────────────────────────────────────
# PRD 的下界由锁屏小组件决定（16.0+）；Live Activity 的 16.1 属于第二批。
if ! grep -qE "^[[:space:]]*iOS:[[:space:]]*\"?16\.0\"?[[:space:]]*$" "$SPEC"; then
  violation "ios/project.yml" "部署目标必须是 iOS 16.0"
fi

# ── 3) 签名：CI 一律不签名 ────────────────────────────────────────────────
# ★ 硬规则一：签名在设备端用侧载工具完成，CI 只出未签名产物。
ALLOWED="$(setting_value "$SPEC" "CODE_SIGNING_ALLOWED")"
REQUIRED="$(setting_value "$SPEC" "CODE_SIGNING_REQUIRED")"
IDENTITY="$(setting_value "$SPEC" "CODE_SIGN_IDENTITY")"

[ "$ALLOWED" = "NO" ] \
  || violation "ios/project.yml" "CODE_SIGNING_ALLOWED 必须是 NO（CI 一律不签名），实际为：${ALLOWED:-<未设置>}"
[ "$REQUIRED" = "NO" ] \
  || violation "ios/project.yml" "CODE_SIGNING_REQUIRED 必须是 NO，实际为：${REQUIRED:-<未设置>}"
[ -z "$IDENTITY" ] \
  || violation "ios/project.yml" "CODE_SIGN_IDENTITY 必须为空，实际为：$IDENTITY"

# ── 4) 零第三方依赖（D1 决策：Vision 是系统框架）─────────────────────────
if grep -qE "^[[:space:]]*packages:[[:space:]]*$" "$SPEC"; then
  violation "ios/project.yml" "第一批不允许出现 packages 段（工程里不出现 CocoaPods / SPM 第三方依赖）"
fi
for DEPFILE in Podfile Podfile.lock Cartfile Package.swift Package.resolved; do
  if find "$ROOT/ios" -name "$DEPFILE" -print -quit 2>/dev/null | grep -q .; then
    violation "ios/" "不允许出现 $DEPFILE（第一批零第三方依赖）"
  fi
done

# ── 5) 权限与 ATS ─────────────────────────────────────────────────────────
if [ -f "$PLIST" ]; then
  if ! grep -q "NSCameraUsageDescription" "$PLIST"; then
    violation "ios/ShotTuner/Info.plist" "缺少 NSCameraUsageDescription（相机权限文案）"
  fi
  if grep -q "NSMicrophoneUsageDescription" "$PLIST"; then
    violation "ios/ShotTuner/Info.plist" "不允许麦克风权限（Android 侧本来就没有视频录制）"
  fi
  # ATS 不得放宽 —— 被禁字面量从清单读，脚本里不写。
  # ★ Info.plist 与 project.yml 都要扫：
  #   万一将来有人改用 XcodeGen 的 `info:` 段生成 plist，放宽开关就会落到 project.yml 里。
  for ATS_TARGET in "$PLIST" "$SPEC"; do
    [ -f "$ATS_TARGET" ] || continue
    while IFS= read -r pat; do
      [ -n "$pat" ] || continue
      if grep -Fq -- "$pat" "$ATS_TARGET"; then
        violation "${ATS_TARGET#"$ROOT"/}" "出现被禁配置：$pat —— ATS 不得放宽（零网络承诺）"
      fi
    done < <(ci_load_patterns "ios-config-forbidden.txt")
  done
fi

# ── 6) KMP 集成：方案 B（Run Script + embedAndSignAppleFrameworkForXcode）──
if [ ! -f "$SCRIPT_BUILD" ]; then
  violation "ios/Scripts/kmp-build-framework.sh" "缺少产出 framework 的 Run Script"
elif ! grep -q "embedAndSignAppleFrameworkForXcode" "$SCRIPT_BUILD"; then
  violation "ios/Scripts/kmp-build-framework.sh" "必须调用 embedAndSignAppleFrameworkForXcode（KMP 集成方案 B）"
fi

# ── 7) .xcodeproj 不入库（单一事实来源是 project.yml）────────────────────
if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if git -C "$ROOT" ls-files -- 'ios/*.xcodeproj' 'ios/*.xcodeproj/*' | grep -q .; then
    violation "ios/" ".xcodeproj 是 XcodeGen 的生成物，不入库（单一事实来源是 project.yml）"
  fi
fi

# ── 8) iOS 源集必须非空 ───────────────────────────────────────────────────
# ★ 空的 iosMain 源集意味着 macOS 上那句 :shared:compileKotlinIosArm64
#   根本没编到任何 iOS 专属代码 —— 那道门禁就是空的。
if [ -d "$ROOT/shared/src/iosMain" ]; then
  if [ -z "$(find "$ROOT/shared/src/iosMain" -name '*.kt' -print -quit)" ]; then
    violation "shared/src/iosMain" "iosMain 源集里没有 .kt 文件 —— iOS 编译门禁会覆盖不到它"
  fi
else
  violation "shared/src/iosMain" "缺少 iosMain 源集目录"
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  ci_log ""
  ci_log "✗ iOS 工程规格自检未通过：$VIOLATIONS 处违规"
  exit 1
fi

ci_log "✓ iOS 工程规格自检通过"
exit 0
