#!/usr/bin/env bash
# tools/ci/check-no-network-config.sh —— L2 配置层零网络门禁。
#
# 拦什么：iOS 工程配置里的"联网放行"与"第三方 SDK 引入"。
#
# ★ 为什么这一层是**仓库内静态检查**、而不是"出包后再查"：
#   出包后查要占 macOS 配额（免费私有仓库每月只有约 200 分钟），而这一层
#   完全可以只读仓库里的文本文件 —— **Linux runner 就能跑**。
#   把能提前拦的错误挪到不需要 macOS 的地方，是保住配额的关键。
#
# 检查项：
#   1. 第三方 SDK 血统：Package.swift / Podfile / Cartfile 里不得引入第三方 SDK
#      （第一批用 Apple Vision，不引任何第三方 SDK；D1 之后工程里不出现 CocoaPods）
#   2. ATS 不得放宽：Info.plist 不得出现任意加载开关
#   3. entitlements 不得带网络能力
#   4. 签名开关必须恒为 NO（CI 一律不签名）
#
# ★ 特征串一律从 patterns/*.txt 读，脚本里不写任何字面量。
#
# 用法： tools/ci/check-no-network-config.sh [仓库根目录]
#
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

ROOT="${1:-$(ci_repo_root "$SCRIPT_DIR")}"
[ -d "$ROOT" ] || ci_die "仓库根目录不存在：$ROOT"

ci_log "▶ L2 配置层零网络门禁（根目录：$ROOT）"

mapfile -t SDKPAT   < <(ci_load_patterns "network-binary.txt")
mapfile -t FORBID  < <(ci_load_patterns "ios-config-forbidden.txt")
mapfile -t MUSTNO  < <(ci_load_patterns "ios-config-must-be-no.txt")
[ "${#FORBID[@]}" -gt 0 ] || ci_die "清单 ios-config-forbidden.txt 为空"

VIOLATIONS=0

# ── 1. 第三方 SDK 血统：依赖声明文件里不得出现 ───────────────────────────────
DEP_FILES=""
for name in Package.swift Podfile Cartfile Package.resolved; do
  [ -f "$ROOT/$name" ] && DEP_FILES="$DEP_FILES $name"
done
if [ -n "$DEP_FILES" ]; then
  for dep in $DEP_FILES; do
    for pat in "${SDKPAT[@]}"; do
      if grep -Fq -- "$pat" "$ROOT/$dep"; then
        ci_error "$dep" "-" "依赖声明里出现第三方 SDK 血统：$pat —— 第一批只用 Apple Vision，不引第三方 SDK"
        VIOLATIONS=$((VIOLATIONS + 1))
      fi
    done
  done
else
  ci_info "无依赖声明文件（Package.swift / Podfile / Cartfile 均不存在）—— 符合 D1 决策"
fi

# ── 2. ATS 不得放宽 ─────────────────────────────────────────────────────────
mapfile -t PLISTS < <(ci_list_files_under "$ROOT" "Info.plist" "ios")
for rel in "${PLISTS[@]}"; do
  for pat in "${FORBID[@]}"; do
    if grep -Fq -- "$pat" "$ROOT/$rel"; then
      ci_error "$rel" "-" "Info.plist 出现被禁配置：$pat —— ATS 不得放宽"
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  done
done

# ── 3. entitlements 不得带网络能力 ──────────────────────────────────────────
mapfile -t ENTS < <(ci_list_files_under "$ROOT" "*.entitlements" "ios")
for rel in "${ENTS[@]}"; do
  for pat in "${FORBID[@]}"; do
    if grep -Fq -- "$pat" "$ROOT/$rel"; then
      ci_error "$rel" "-" "entitlements 出现被禁能力：$pat"
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  done
done

# ── 4. 签名开关必须恒为 NO ──────────────────────────────────────────────────
mapfile -t PROJFILES < <(ci_list_files_under "$ROOT" "project.yml" "ios")
for rel in "${PROJFILES[@]}"; do
  for var in "${MUSTNO[@]}"; do
    val="$(grep -E -- "^[[:space:]]*${var}[[:space:]]*:" "$ROOT/$rel" | head -1 | sed -E 's/.*:[[:space:]]*//' | tr -d '"' | tr -d "'" | tr -d '[:space:]')"
    [ -n "$val" ] || continue                     # 没这个变量 → 通过
    if [ "$val" != "NO" ]; then
      ci_error "$rel" "-" "构建开关 $var 必须为 NO（CI 一律不签名），实际为：$val"
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  done
done

if [ "$VIOLATIONS" -gt 0 ]; then
  ci_log ""
  ci_log "✗ L2 配置层零网络门禁未通过：$VIOLATIONS 处违规"
  exit 1
fi

ci_log "✓ L2 配置层零网络门禁通过"
exit 0
