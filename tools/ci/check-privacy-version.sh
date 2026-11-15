#!/usr/bin/env bash
# tools/ci/check-privacy-version.sh —— 隐私政策版本一致性门禁。
#
# ★ 拦的是**合规缺陷**，不是编译错误：两端都能编过、能跑、首次安装看起来也正常，
#   但**老用户会静默沿用旧同意**，新政策从不生效。
#
# ── 缺陷长什么样（Android 侧）─────────────────────────────────────────────────
#   政策正文：app/src/main/res/raw/privacy_v1.txt        ← 文件名里带版本号
#   同意标记：MainActivity.kt  KEY_PRIVACY_ACCEPTED = "privacy_accepted_v1"
#                                                    ↑ 版本号编在**键名**里
#   于是「升级政策」这个动作被拆成了两件互不相干的事：
#     ① 改正文（privacy_v1.txt → privacy_v2.txt）
#     ② 改键名（privacy_accepted_v1 → privacy_accepted_v2）
#   只做了 ① 的话，`uiPrefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)` 读到的
#   仍是那个老键 = 老用户手上早就是 `true` ⇒ **不会重新征得同意**，
#   而代码里一切正常、崩溃日志一片安静 —— 这类事故永远不会被"跑一下"发现。
#
#   ★ 为什么这里不改代码、只用门禁盯：
#     把版本号编进键名本身不是错（它让「同意过哪一版」可追溯），
#     错的是**两处版本号可以不一致而没人报警**。门禁就是把那条不变量写成机器可执行。
#
# ── 判据 ─────────────────────────────────────────────────────────────────────
#   1) app/src/main/res/raw/privacy_v<N>.txt 必须**恰好一个**（N 从文件名取）
#   2) MainActivity.kt 里 KEY_PRIVACY_ACCEPTED = "privacy_accepted_v<M>" 的 M
#      —— M 必须等于 1) 里的 N
#   3) 同一个文件里 `R.raw.privacy_v<K>` 的 K 必须等于 N
#      （改文件名却忘了改资源引用，虽然编译期会报错，但提前一秒红掉更省事）
#   4) iOS 侧 ios/ShotTuner/Onboarding/PrivacyText.swift 的 `static let version: Int = P`
#      —— P 也必须等于 N（iOS 存的是值不是键名，但两端政策版本应当**同源**；
#         两端版本号跑偏会让「同一份条款」在两端显示成不同版本）
#
# ★ 拿不到任何一个输入 ⇒ exit 2（拒绝静默通过）。
#   一条判据的全部输入都读不到时，"通过"只说明它没检查，不说明合规。
#
# 用法： tools/ci/check-privacy-version.sh [仓库根目录]
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"

ROOT="${1:-$(ci_repo_root "${SCRIPT_DIR}")}"
[ -d "${ROOT}" ] || ci_die "仓库根目录不存在：${ROOT}"

RAW_DIR="app/src/main/res/raw"
KT_FILE="app/src/main/java/com/shottuner/app/MainActivity.kt"
IOS_FILE="ios/ShotTuner/Onboarding/PrivacyText.swift"

ci_log "▶ 隐私政策版本一致性门禁（根目录：${ROOT}）"

VIOLATIONS=0

# violation <文件(相对仓库根)> <说明> —— 记一处违规（不立即退出，一次报全）。
violation() {
  ci_error "$1" "-" "$2"
  VIOLATIONS=$((VIOLATIONS + 1))
}

# ── 1) 政策正文：必须恰好一个 privacy_v<N>.txt ──────────────────────────────
RAW_ABS="${ROOT}/${RAW_DIR}"
[ -d "${RAW_ABS}" ] || ci_die "找不到 res/raw 目录：${RAW_DIR} —— 政策正文无法定位（拒绝静默通过）"

POLICY_FILES=""
if [ -d "${RAW_ABS}" ]; then
  # ★ 用 find 而不是 ls 通配：文件名里不会有点号以外的东西，但 find 不会因为
  #   "一个都没匹配上"而把 glob 原样当成文件名（那样会把 `privacy_v*.txt` 当成文件）。
  POLICY_FILES="$(cd "${RAW_ABS}" && find . -maxdepth 1 -type f -name 'privacy_v*.txt' 2>/dev/null | sed 's|^\./||' | sort)"
fi

POLICY_COUNT=0
if [ -n "${POLICY_FILES}" ]; then
  POLICY_COUNT="$(printf '%s\n' "${POLICY_FILES}" | wc -l | tr -d ' ')"
fi

if [ "${POLICY_COUNT}" -eq 0 ]; then
  ci_die "在 ${RAW_DIR}/ 下找不到 privacy_v*.txt —— 政策正文不存在，版本无从判定（拒绝静默通过）"
fi

FILE_VERSION=""
if [ "${POLICY_COUNT}" -ne 1 ]; then
  violation "${RAW_DIR}" "政策正文必须**恰好一个**（privacy_v<N>.txt），实际找到 ${POLICY_COUNT} 个：$(printf '%s ' ${POLICY_FILES}) —— 两个版本共存时版本号是二义的，谁都说不清用户在同意哪一份"
else
  POLICY_NAME="${POLICY_FILES}"
  FILE_VERSION="$(printf '%s' "${POLICY_NAME}" | sed -n 's/^privacy_v\([0-9][0-9]*\)\.txt$/\1/p')"
  [ -n "${FILE_VERSION}" ] \
    || ci_die "政策正文文件名不符合 privacy_v<N>.txt 约定：${POLICY_NAME} —— 版本号无从提取（拒绝静默通过）"
  ci_info "政策正文：${RAW_DIR}/${POLICY_NAME} → 版本 ${FILE_VERSION}"

  SIZE="$(wc -c < "${RAW_ABS}/${POLICY_NAME}" | tr -d ' ')"
  if [ "${SIZE}" -eq 0 ]; then
    violation "${RAW_DIR}/${POLICY_NAME}" "政策正文是空文件 —— 用户点同意时看到的是空白，这比版本不一致更严重"
  else
    ci_info "正文大小：${SIZE} 字节"
  fi
fi

# ── 2) Android 同意标记：键名里编的版本号必须与文件名一致 ────────────────────
KT_ABS="${ROOT}/${KT_FILE}"
[ -f "${KT_ABS}" ] || ci_die "找不到 ${KT_FILE} —— 同意标记的版本无从判定（拒绝静默通过）"

# 找出「给了字符串值」的那一行（赋值语句，不是注释、不是读取处的变量引用）。
KEY_LINES="$(grep -n 'KEY_PRIVACY_ACCEPTED[[:space:]]*=[[:space:]]*"' "${KT_ABS}" 2>/dev/null || true)"
[ -n "${KEY_LINES}" ] \
  || ci_die "在 ${KT_FILE} 里找不到 KEY_PRIVACY_ACCEPTED = \"…\" 的声明 —— 版本无从判定（拒绝静默通过）"

KEY_VERSIONS=""
while IFS= read -r line; do
  [ -n "${line}" ] || continue
  line_no="${line%%:*}"
  # 取该行的字符串字面量，再要求它形如 privacy_accepted_v<N>。
  literal="$(printf '%s' "${line}" | sed -n 's/.*"\(privacy_accepted[a-z0-9_]*\)".*/\1/p')"
  if [ -z "${literal}" ]; then
    violation "${KT_FILE}" "第 ${line_no} 行的 KEY_PRIVACY_ACCEPTED 取值不是 privacy_accepted_v<N> 形态 —— 版本号必须编在键名里"
    continue
  fi
  ver="$(printf '%s' "${literal}" | sed -n 's/^privacy_accepted_v\([0-9][0-9]*\)$/\1/p')"
  if [ -z "${ver}" ]; then
    violation "${KT_FILE}" "第 ${line_no} 行的键名是 \"${literal}\" —— 必须带版本号后缀（privacy_accepted_v<N>），否则政策升版时老用户会静默沿用旧同意"
    continue
  fi
  ci_info "Android 键名：${literal}（第 ${line_no} 行）→ 版本 ${ver}"
  case " ${KEY_VERSIONS} " in
    *" ${ver} "*) ;;
    *) KEY_VERSIONS="${KEY_VERSIONS} ${ver}" ;;
  esac
done <<< "${KEY_LINES}"

KEY_VERSION_COUNT="$(printf '%s' "${KEY_VERSIONS}" | wc -w | tr -d ' ')"
if [ "${KEY_VERSION_COUNT}" -gt 1 ]; then
  violation "${KT_FILE}" "同一个文件里出现多个不同版本的键名（${KEY_VERSIONS}）—— 只允许一个：多版本共存意味着有的读取点永远拿不到新同意"
elif [ "${KEY_VERSION_COUNT}" -eq 1 ]; then
  KEY_VERSION="$(printf '%s' "${KEY_VERSIONS}" | tr -d ' ')"
  if [ -n "${FILE_VERSION}" ] && [ "${KEY_VERSION}" != "${FILE_VERSION}" ]; then
    violation "${KT_FILE}" "政策版本不一致：正文是 privacy_v${FILE_VERSION}.txt，键名却是 privacy_accepted_v${KEY_VERSION} —— ★ 改政策正文时必须同步改键名，否则老用户会静默沿用旧同意，新政策不生效"
  fi
fi

# ── 3) 资源引用 R.raw.privacy_v<K> 必须与文件名一致 ──────────────────────────
RAW_REFS="$(grep -o 'R\.raw\.privacy_v[0-9][0-9]*' "${KT_ABS}" 2>/dev/null | sed 's/^R\.raw\.privacy_v//' | sort -u || true)"
if [ -z "${RAW_REFS}" ]; then
  violation "${KT_FILE}" "找不到 R.raw.privacy_v<N> 的资源引用 —— 政策正文没有被读进 UI（或引用方式变了，本门禁需要同步更新）"
else
  for ref in ${RAW_REFS}; do
    ci_info "资源引用：R.raw.privacy_v${ref}"
    if [ -n "${FILE_VERSION}" ] && [ "${ref}" != "${FILE_VERSION}" ]; then
      violation "${KT_FILE}" "资源引用 R.raw.privacy_v${ref} 与政策正文文件名 privacy_v${FILE_VERSION}.txt 不一致 —— 改文件名必须同步改引用"
    fi
  done
fi

# ── 4) iOS 侧静态版本号必须同源 ──────────────────────────────────────────────
IOS_ABS="${ROOT}/${IOS_FILE}"
[ -f "${IOS_ABS}" ] || ci_die "找不到 ${IOS_FILE} —— iOS 侧政策版本无从判定（拒绝静默通过）"

IOS_VERSION="$(sed -n 's/.*static let version[[:space:]]*:[[:space:]]*Int[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "${IOS_ABS}" | head -1)"
[ -n "${IOS_VERSION}" ] \
  || ci_die "在 ${IOS_FILE} 里找不到 'static let version: Int = <N>' —— iOS 侧版本无从判定（拒绝静默通过）"

ci_info "iOS 版本：PrivacyText.version = ${IOS_VERSION}"
if [ -n "${FILE_VERSION}" ] && [ "${IOS_VERSION}" != "${FILE_VERSION}" ]; then
  violation "${IOS_FILE}" "iOS 的 PrivacyText.version = ${IOS_VERSION}，而 Android 政策正文是 privacy_v${FILE_VERSION}.txt —— 两端政策版本必须同源（用户拿两台手机对比会看到两份条款）"
fi

# ── 收尾 ─────────────────────────────────────────────────────────────────────
ci_log ""
if [ "${VIOLATIONS}" -gt 0 ]; then
  ci_log "✗ 隐私政策版本一致性未通过：${VIOLATIONS} 处违规"
  exit 1
fi
ci_log "✓ 隐私政策版本一致（正文 privacy_v${FILE_VERSION}.txt / Android 键名 / R.raw 引用 / iOS PrivacyText.version）"
exit 0
