#!/usr/bin/env bash
# tools/ci/check-no-network.sh —— L1 源码层零网络门禁。
#
# 拦什么：我们自己写出来的联网调用。
#
# ★ 为什么只在源码层拦，不在二进制层拦：
#   Foundation 永远链着系统自带的网络符号，任何用到 Foundation 的 iOS App 二进制里
#   都有它们。扫二进制必然 100% 误报，而红一次的 CI 门禁等于没有门禁
#   （团队会立刻学会忽略它）。所以二进制层只扫第三方遥测血统，见 network-binary.txt。
#
# ★ 三个「门禁命中自己」的坑，这里对应第 1、2 个：
#   1) 注释行 —— 源码注释里可能出现被禁词（例如"这里不要用 XXX"）→ 先剥注释再匹配；
#   2) 文档散文 —— 文档正文里可能引用被禁词 → 扫描范围只限 ios/ 与 shared/src/，
#      不扫 docs/**；
#   3) 门禁脚本自身 —— 被禁字面量只放在 patterns/*.txt，本脚本不写任何字面量，
#      且扫描范围天然不含 tools/ci/**（双保险）。
#
# 用法： tools/ci/check-no-network.sh [仓库根目录]
#        省略仓库根时按脚本所在位置推导。
#
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"

ROOT="${1:-$(ci_repo_root "${SCRIPT_DIR}")}"
[ -d "${ROOT}" ] || ci_die "仓库根目录不存在：${ROOT}"

ci_log "▶ L1 源码层零网络门禁（根目录：${ROOT}）"

# 扫描范围：只有我们自己写的 iOS 与共享层源码。
# ★ 刻意不含 docs/**（坑 2）与 tools/ci/**（坑 3）。
mapfile -t FILES < <(
  {
    ci_list_files_under "${ROOT}" "*.swift" "ios"
    ci_list_files_under "${ROOT}" "*.kt" "shared/src"
  } | sort -u
)

if [ "${#FILES[@]}" -eq 0 ]; then
  ci_info "尚无 iOS / 共享层源码可扫（第一批尚未开工）—— 通过"
  exit 0
fi

# 被禁字面量一律从清单读，脚本里一个字面量都不写。
mapfile -t PATTERNS < <(ci_load_patterns "network-source.txt")
[ "${#PATTERNS[@]}" -gt 0 ] || ci_die "清单 network-source.txt 为空"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

VIOLATIONS=0
for rel in "${FILES[@]}"; do
  abs="${ROOT}/${rel}"
  [ -f "${abs}" ] || continue

  # 剥注释（坑 1）。strip-comments.awk 保证输出行数与原文件一致，
  # 所以下面 grep -n 得到的行号可以直接用来定位原文件。
  stripped="${TMP}/stripped"
  awk -f "${CI_LIB_DIR}/strip-comments.awk" "${abs}" > "${stripped}" 2>/dev/null || {
    ci_info "跳过无法解析的文件：${rel}"
    continue
  }

  for pat in "${PATTERNS[@]}"; do
    grep -Fq -- "${pat}" "${stripped}" || continue
    while IFS= read -r ln; do
      [ -n "${ln}" ] || continue
      # 精确豁免：只豁免「某一个文件的某一个特征串」，豁免不会自动扩大。
      if ci_is_allowlisted "${rel}" "${pat}"; then
        ci_info "豁免：${rel}:${ln} （${pat}）"
        continue
      fi
      ci_error "${rel}" "${ln}" "源码层出现被禁网络调用：${pat} —— 违反零网络承诺"
      VIOLATIONS=$((VIOLATIONS + 1))
    done < <(grep -n -F -- "${pat}" "${stripped}" | cut -d: -f1)
  done
done

if [ "${VIOLATIONS}" -gt 0 ]; then
  ci_log ""
  ci_log "✗ L1 源码层零网络门禁未通过：${VIOLATIONS} 处违规"
  exit 1
fi

ci_log "✓ L1 源码层零网络门禁通过（扫了 ${#FILES[@]} 个文件，${#PATTERNS[@]} 个特征串）"
exit 0
