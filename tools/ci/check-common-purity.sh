#!/usr/bin/env bash
# tools/ci/check-common-purity.sh —— commonMain 的 JVM-only 用法扫描（**秒级**）。
#
# 为什么要有这个脚本（它不是权威检查，是"快速反馈"）：
#   权威检查是 CI 里的 `:shared:compileCommonMainKotlinMetadata` ——
#   metadata 编译只允许 common API，能抓到**任何** JVM-only 用法。
#   但它要跑 CI（Linux runner，约 2 分钟），改一行就得等一轮。
#   这个脚本在本地秒级出结果，用于**改完立刻自查**。
#
# ★ 它抓不到的东西（所以不能替代 metadata 编译）：
#   · 没被模式覆盖的 API（比如某个 JDK 独有的扩展函数）
#   · 签名/类型层面的差异
#   所以 CI 里两道都在：本地用这个秒级自查，CI 用 metadata 编译兜底。
#
# ★ 为什么不能只靠 Android 侧编译：
#   Android 有 javaClass、java.text.SimpleDateFormat、java.lang.Math …
#   **commonMain 里用了它们，Android 编译是绿的，iOS 上直接编译失败。**
#   这个坑真实发生过（ScoreHistory 的 javaClass），是 metadata 编译抓出来的。
#
# 用法： bash tools/ci/check-common-purity.sh [仓库根目录]
# 退出码： 0 = 通过；1 = 有 JVM-only 用法；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

ROOT="${1:-$(ci_repo_root "$SCRIPT_DIR")}"
[ -d "$ROOT" ] || ci_die "仓库根目录不存在：$ROOT"

ci_log "▶ commonMain 纯度扫描（根目录：$ROOT）"

SRC="$ROOT/shared/src/commonMain"
if [ ! -d "$SRC" ]; then
  ci_info "尚无 commonMain（KMP 模块还没建）—— 通过"
  exit 0
fi

# ★ 特征串全部来自清单，脚本里不写字面量（与另两道门禁同一纪律）
mapfile -t PATTERNS < <(ci_load_patterns "jvm-only-api.txt")
[ "${#PATTERNS[@]}" -gt 0 ] || ci_die "清单 jvm-only-api.txt 为空"

mapfile -t FILES < <(ci_list_files_under "$ROOT" "*.kt" "shared/src/commonMain")
if [ "${#FILES[@]}" -eq 0 ]; then
  ci_info "commonMain 里没有 .kt 文件 —— 通过"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

VIOLATIONS=0
for rel in "${FILES[@]}"; do
  abs="$ROOT/$rel"
  [ -f "$abs" ] || continue

  # 剥注释后再匹配（坑 1）：注释里出现被禁词不是违规
  stripped="$TMP/stripped"
  awk -f "$CI_LIB_DIR/strip-comments.awk" "$abs" > "$stripped" 2>/dev/null || continue

  for pat in "${PATTERNS[@]}"; do
    grep -Fq -- "$pat" "$stripped" || continue
    while IFS= read -r ln; do
      [ -n "$ln" ] || continue
      if ci_is_allowlisted "$rel" "$pat"; then
        ci_info "豁免：$rel:$ln （$pat）"
        continue
      fi
      ci_error "$rel" "$ln" "commonMain 里出现 JVM-only 用法：$pat —— Android 编译看不出来，iOS 上会编译失败"
      VIOLATIONS=$((VIOLATIONS + 1))
    done < <(grep -n -F -- "$pat" "$stripped" | cut -d: -f1)
  done
done

if [ "$VIOLATIONS" -gt 0 ]; then
  ci_log ""
  ci_log "✗ commonMain 纯度扫描未通过：$VIOLATIONS 处"
  ci_log "  提醒：这只是快速反馈，**权威检查是 CI 的 compileCommonMainKotlinMetadata**"
  exit 1
fi

ci_log "✓ commonMain 纯度扫描通过（扫了 ${#FILES[@]} 个文件，${#PATTERNS[@]} 个特征串）"
exit 0
