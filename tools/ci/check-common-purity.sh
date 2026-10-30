#!/usr/bin/env bash
# tools/ci/check-common-purity.sh —— commonMain / commonTest 的 JVM-only 用法扫描（**秒级**）。
#
# 为什么必须有这个脚本（它不是权威检查，是"快速反馈"）：
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
# ★ 为什么扫描范围要扩到 commonTest（2026-10-29）：
#   commonTest 是**跨端共享**的测试源集 —— Android 与 iOS 跑同一份源码。
#   所以 commonTest 里的 JVM-only 用法，**后果与 commonMain 完全一样**：
#   Android 侧一路绿灯（commonTest 只在 JVM 上编），iOS 侧编 commonTest 时直接失败。
#   真实发生过：T07 把 13 个测试迁进 commonTest，其中两个文件用了
#   `java.util.Locale` / `Math.round` / `"...".format(...)` ——
#   **是人发现的，不是机器**（Android 编译不报错，metadata 编译只管 commonMain）。
#   ⇒ 门禁必须自己盯住 commonTest，否则下一次还会靠人。
#
# ★ 豁免（留后路）：
#   commonTest 里将来可能有**合法**的 JVM-only 用法（某个测试就是要验证 JVM 行为）。
#   这类用例给一条精确豁免：`patterns/jvm-only-allowlist.txt`（路径::特征串）。
#   同一个文件里出现**另一个**新特征串 → 仍然红（豁免不会自动扩大）。
#
# 用法： bash tools/ci/check-common-purity.sh [仓库根目录]
# 退出码： 0 = 通过；1 = 有 JVM-only 用法；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"

ROOT="${1:-$(ci_repo_root "${SCRIPT_DIR}")}"
[ -d "${ROOT}" ] || ci_die "仓库根目录不存在：${ROOT}"

ci_log "▶ commonMain / commonTest 纯度扫描（根目录：${ROOT}）"

# ★ 扫描范围：两个源集。
#   commonMain  —— 生产代码，iOS 端要跑。
#   commonTest  —— 跨端共享的测试源码，iOS 端同样要跑。
#   两个都要盯，缺一个就留下一个"Android 绿、iOS 红"的缺口。
SOURCE_SETS=("shared/src/commonMain" "shared/src/commonTest")

FOUND_ANY=0
for src in "${SOURCE_SETS[@]}"; do
  if [ ! -d "${ROOT}/${src}" ]; then
    ci_info "尚无 ${src} —— 跳过"
    continue
  fi
  FOUND_ANY=1
done
if [ "${FOUND_ANY}" -eq 0 ]; then
  ci_info "尚无 commonMain / commonTest（KMP 模块还没建）—— 通过"
  exit 0
fi

# ★ 特征串全部来自清单，脚本里不写字面量（与另两道门禁同一纪律）
mapfile -t PATTERNS < <(ci_load_patterns "jvm-only-api.txt")
[ "${#PATTERNS[@]}" -gt 0 ] || ci_die "清单 jvm-only-api.txt 为空"

# 豁免清单：路径::特征串 的精确豁免（留后路：commonTest 里可能有合法的 JVM-only 用法）
JVM_ALLOWLIST="${CI_PATTERNS_DIR}/jvm-only-allowlist.txt"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

VIOLATIONS=0
SCANNED=0
for src in "${SOURCE_SETS[@]}"; do
  [ -d "${ROOT}/${src}" ] || continue

  mapfile -t FILES < <(ci_list_files_under "${ROOT}" "*.kt" "${src}")
  if [ "${#FILES[@]}" -eq 0 ]; then
    ci_info "${src} 里没有 .kt 文件 —— 跳过"
    continue
  fi
  SCANNED=$((SCANNED + ${#FILES[@]}))

  for rel in "${FILES[@]}"; do
    abs="${ROOT}/${rel}"
    [ -f "${abs}" ] || continue

    # 剥注释后再匹配（坑 1）：注释里出现被禁词不是违规
    stripped="${TMP}/stripped"
    awk -f "${CI_LIB_DIR}/strip-comments.awk" "${abs}" > "${stripped}" 2>/dev/null || continue

    for pat in "${PATTERNS[@]}"; do
      grep -Fq -- "${pat}" "${stripped}" || continue
      while IFS= read -r ln; do
        [ -n "${ln}" ] || continue
        # 精确豁免：同一个文件 + 同一个特征串 才豁免；别的特征串仍然红。
        if [ -f "${JVM_ALLOWLIST}" ] && grep -Fxq -- "${rel}::${pat}" "${JVM_ALLOWLIST}"; then
          ci_info "豁免：${rel}:${ln} （${pat}）"
          continue
        fi
        ci_error "${rel}" "${ln}" "${src} 里出现 JVM-only 用法：${pat} —— Android 编译看不出来，iOS 上会编译失败"
        VIOLATIONS=$((VIOLATIONS + 1))
      done < <(grep -n -F -- "${pat}" "${stripped}" | cut -d: -f1)
    done
  done
done

if [ "${VIOLATIONS}" -gt 0 ]; then
  ci_log ""
  ci_log "✗ commonMain / commonTest 纯度扫描未通过：${VIOLATIONS} 处"
  ci_log "  提醒：这只是快速反馈，**权威检查是 CI 的 compileCommonMainKotlinMetadata**"
  exit 1
fi

ci_log "✓ commonMain / commonTest 纯度扫描通过（扫了 ${SCANNED} 个文件，${#PATTERNS[@]} 个特征串）"
exit 0
