#!/usr/bin/env bash
# tools/ci/scan-binary.sh —— G4 产物二进制扫描（供应链层，T21）。
#
# ★ 这道门禁补的是「五道源码门禁共同的盲区」：
#   check-no-network.sh / check-no-network-config.sh / check-apple-credentials.sh
#   以及另外两道，全部**只扫文本**。一旦有人引入**二进制形式**的第三方 SDK
#   （xcframework / 预编译 .a / 别人的 .framework），它会带着自己的网络符号、
#   自己的遥测域名被编译进 ipa —— 源码里搜不到任何特征串，因为根本没有源码。
#   五道门禁一道都不会红。
#   ⇒ 本脚本在**产物**上直接看：链了什么、调了什么符号、配置放没放宽。
#     位置：出 ipa **之后**、上传 Release **之前**（见 ci-gates.yml 的 ios-package job）。
#
# ── 判据（docs/IOS-ARCH-EXEC.md §4.3 / T21 验收第 ②③ 条）───────────────────────
#   G4-a  内嵌 framework / dylib 白名单：只允许 KMP 产物
#         （白名单在 patterns/binary-frameworks-allow.txt，不在脚本里写死）
#   G4-b  第三方遥测血统：
#         · 所有 Mach-O 的**原始字节**（全平台可跑，反向验证就是测这一条）
#         · macOS 上再补 `nm -u` 的**未定义符号**（真正的符号级判据）
#   G4-c  产物 Info.plist 里 ATS 未放宽（NSAllowsArbitraryLoads 等为 true ⇒ 红）
#   G4-d  产物 entitlements 无网络能力（com.apple.security.network* ⇒ 红）
#   G4-e  依赖清单落档（Frameworks 清单 + 每个 Mach-O 的架构）
#
# ★★ 明确**不扫** NSURLSession / CFNetwork / libnetwork：
#   Foundation 永远链着它们，任何用到 Foundation 的 App 二进制里都有
#   ⇒ 扫它们 100% 误报，而**红一次的 CI 门禁等于没有门禁**
#   （团队会立刻学会忽略它）。出处：docs/IOS-ARCH-EXEC.md §4.3 的「一条重要的技术更正」，
#   原文：真正的判据是三层 —— ① 源码 grep（主力）② ATS / entitlements（配置层）
#   ③ 第三方遥测符号（供应链层）。本脚本负责 ② 的**产物**部分与 ③。
#
# ★★ 为什么本脚本**刻意不 source lib/common.sh**（不是漏了）：
#   common.sh 开头要求 bash 4+（mapfile / 关联数组），而 macOS 自带的 /bin/bash
#   是 **3.2** —— 出包 job 里 `bash tools/ci/scan-binary.sh` 拿到的就是它。
#   一旦 source common.sh，本门禁会在**唯一真正能跑它的地方**（macOS runner）
#   以 exit 2 退出，而且是在最该拦的那道门前面。
#   ⇒ 本脚本只用 bash 3.2 也支持的语法（不用 mapfile、不用关联数组），
#     自己实现语义相同的 load_patterns 与输出格式
#     （::error:: 注解 / 两空格缩进 / ✓✗ 结尾行 / 退出码 0·1·2）。
#     「宁可多写二十行，也不要门禁在关键时刻跑不起来」。
#
# 用法： tools/ci/scan-binary.sh <产物路径> [--manifest <清单输出文件>]
#         <产物路径>：一个 .app 目录，或一个 .ipa 文件（自动解包取 Payload/*.app）
#         --manifest：可选；不给就写到临时目录（内容照样打印）
# 退出码： 0 = 通过（且**确实执行了**检查）
#          1 = 有违规
#          2 = 门禁自身出错（找不到产物 / 本机无法判定 —— 绝不静默通过）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATTERNS_DIR="${SCRIPT_DIR}/patterns"

VIOLATIONS=0

# ── 输出（格式与 lib/common.sh 的 ci_log / ci_info / ci_error / ci_die 对齐）────
say()  { printf '%s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
notice() { printf '::notice::%s\n' "$*"; }
warn()   { printf '::warning::%s\n' "$*" >&2; }

# gate_die <消息> —— 门禁自身出错（exit 2）。**绝不**用它表示"跳过"。
gate_die() {
  printf '::error::门禁自身出错：%s\n' "$*" >&2
  exit 2
}

# gate_fail <判据编号> <消息> —— 记一处违规（最后统一 exit 1）。
# ★ 不在命中处立即 exit：一次 macOS 运行把所有问题都报出来，
#   否则每次重跑只多知道一条（与 ci-gates.yml 反假绿断言同一取舍）。
gate_fail() {
  printf '::error::[%s] %s\n' "$1" "$2" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
}

# load_patterns <清单文件名> —— 输出清单里的有效行（去 '#' 注释与空行）。
# ★ 与 lib/common.sh 的 ci_load_patterns 同语义；此处重写的原因见文件头注释。
load_patterns() {
  local name="$1"
  local file="${PATTERNS_DIR}/${name}"
  [ -f "${file}" ] || gate_die "缺少特征串清单：${name}（期望位置：${file}）"
  sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "${file}"
}

# 读清单进数组。
# ★ 为什么重复三遍、不写成 read_array 函数：bash 3.2 没有 nameref，
#   函数里改调用方数组只能靠 eval —— 而 eval 会把特征串本身当代码重新解析。
#   特征串里出现引号 / $ / 反引号时，那会变成「清单能注入脚本」。
#   宁可多抄两遍循环体，也不要给门禁开这个口子。

# ── 参数 ─────────────────────────────────────────────────────────────────────
TARGET=""
MANIFEST=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest)
      [ "$#" -ge 2 ] || gate_die "--manifest 后面必须跟一个输出文件名"
      MANIFEST="$2"
      shift 2
      ;;
    --manifest=*)
      MANIFEST="${1#*=}"
      shift
      ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*)
      gate_die "不认识的选项：$1"
      ;;
    *)
      [ -z "${TARGET}" ] || gate_die "只能给一个产物路径（已经给了：${TARGET}）"
      TARGET="$1"
      shift
      ;;
  esac
done
[ -n "${TARGET}" ] || gate_die "用法：tools/ci/scan-binary.sh <产物路径 .app|.ipa> [--manifest <文件>]"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# ── 解析产物：.app 目录 / .ipa 文件 ─────────────────────────────────────────
APP=""
ARTIFACT_KIND=""
if [ -d "${TARGET}" ]; then
  APP="${TARGET}"
  ARTIFACT_KIND="目录"
elif [ -f "${TARGET}" ]; then
  case "${TARGET}" in
    *.ipa)
      command -v unzip >/dev/null 2>&1 \
        || gate_die "本机没有 unzip，无法解包 ipa：${TARGET}"
      mkdir -p "${TMP}/ipa"
      unzip -q "${TARGET}" -d "${TMP}/ipa" >/dev/null 2>&1 \
        || gate_die "ipa 解包失败：${TARGET}（真的是 zip 结构吗？）"
      APP="$(find "${TMP}/ipa" -maxdepth 2 -type d -name '*.app' 2>/dev/null | sed -n '1p' || true)"
      [ -n "${APP}" ] || gate_die "ipa 里没有 Payload/*.app：${TARGET}"
      ARTIFACT_KIND="ipa（已解包）"
      ;;
    *)
      gate_die "不认识的产物类型：${TARGET} —— 只接受 .app 目录或 .ipa 文件"
      ;;
  esac
else
  # ★ 这里必须是红，不能是"没东西可扫 → 通过"。
  #   静默通过的门禁比没有门禁更糟：它会让「产物路径写错」看起来像「扫描通过」。
  gate_die "找不到产物：${TARGET}（预期是一个存在的 .app 目录或 .ipa 文件）"
fi
[ -d "${APP}" ] || gate_die "产物里的 .app 目录不存在：${APP}"

APP_NAME="$(basename "${APP}")"

say "▶ G4 产物二进制扫描（供应链层 · 零网络承诺的最后一层）"
info "产物：${TARGET}（${ARTIFACT_KIND}）"
info ".app：${APP_NAME}"

# ── 特征串清单（一律从 patterns/*.txt 读，脚本里不写被禁字面量）─────────────
SYMPAT=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then
    SYMPAT+=("${line}")
  fi
done < <(load_patterns "network-binary.txt")
[ "${#SYMPAT[@]}" -gt 0 ] || gate_die "清单 network-binary.txt 为空"

IOSCFG=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then
    IOSCFG+=("${line}")
  fi
done < <(load_patterns "ios-config-forbidden.txt")
[ "${#IOSCFG[@]}" -gt 0 ] || gate_die "清单 ios-config-forbidden.txt 为空"

# ★ 同一条清单分两用，靠前缀区分（约定写在这里，不要靠记忆）：
#     NS*                    → ATS 的键，要判"值是不是 true"
#     com.apple.security.*   → entitlement 能力名，出现即红
ATS_KEYS=()
ENT_PATS=()
for cfg in "${IOSCFG[@]}"; do
  case "${cfg}" in
    com.apple.security.*) ENT_PATS+=("${cfg}") ;;
    NS*)                  ATS_KEYS+=("${cfg}") ;;
    *)                    warn "ios-config-forbidden.txt 里有一行既不是 ATS 键也不是 entitlement：${cfg}（已在 G4 忽略）" ;;
  esac
done
[ "${#ATS_KEYS[@]}" -gt 0 ] || gate_die "ios-config-forbidden.txt 里没有可用的 ATS 键"
[ "${#ENT_PATS[@]}" -gt 0 ] || gate_die "ios-config-forbidden.txt 里没有可用的 entitlement 能力名"

FW_ALLOW=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then
    FW_ALLOW+=("${line}")
  fi
done < <(load_patterns "binary-frameworks-allow.txt")
[ "${#FW_ALLOW[@]}" -gt 0 ] || gate_die "清单 binary-frameworks-allow.txt 为空"

# ── 能力探测（缺能力时"明确跳过 + ::notice::"，而不是假装通过）──────────────
HAS_NM=0
if command -v nm >/dev/null 2>&1; then HAS_NM=1; fi
HAS_PLUTIL=0
if command -v plutil >/dev/null 2>&1; then HAS_PLUTIL=1; fi
HAS_CODESIGN=0
if command -v codesign >/dev/null 2>&1; then HAS_CODESIGN=1; fi
HAS_LIPO=0
if command -v lipo >/dev/null 2>&1; then HAS_LIPO=1; fi

# is_macho <文件> —— 头 4 字节是不是 Mach-O / fat 魔数（不依赖 nm / file）。
is_macho() {
  local f="$1" magic=""
  [ -f "${f}" ] || return 1
  magic="$(od -An -tx1 -N4 "${f}" 2>/dev/null | tr -d ' \n' || true)"
  case "${magic}" in
    # 小端 64/32 位（真机 arm64 是 cffaedfe）、大端、fat / fat64
    cffaedfe|cefaedfe|feedface|feedfacf|fefaedce|fefacffe|cafebabe|cafebabf|bebafeca) return 0 ;;
    *) return 1 ;;
  esac
}

# rel <绝对路径> —— 转成便于阅读的产物内相对路径。
rel() {
  local p="$1"
  case "${p}" in
    "${APP}/"*) printf '%s' "${APP_NAME}/${p#"${APP}/"}" ;;
    "${TMP}/ipa/"*) printf '%s' "${p#"${TMP}/ipa/"}" ;;
    *) printf '%s' "${p}" ;;
  esac
}

# ── G4-a 内嵌 framework / dylib 白名单 ───────────────────────────────────────
say ""
say "【G4-a】内嵌 framework / dylib 白名单（只允许 KMP 产物）"
info "白名单（patterns/binary-frameworks-allow.txt）：$(printf '%s ' "${FW_ALLOW[@]}")"

if [ ! -d "${APP}/Frameworks" ]; then
  gate_fail "G4-a" "产物里没有 Frameworks/ 目录 —— KMP 内核没被嵌进去（空壳产物）；白名单检查也无从下手"
fi

FRAMEWORKS=()
while IFS= read -r fw; do
  if [ -n "${fw}" ]; then
    FRAMEWORKS+=("${fw}")
  fi
done < <(
  find "${APP}" -type d \( -name '*.framework' -o -name '*.xcframework' \) 2>/dev/null
  find "${APP}" -type f -name '*.dylib' 2>/dev/null
)

if [ "${#FRAMEWORKS[@]}" -eq 0 ]; then
  gate_fail "G4-a" "产物里一个 .framework / .dylib 都没有 —— 连 KMP 的 Shared.framework 都找不到（空壳产物）"
fi

for fw in "${FRAMEWORKS[@]}"; do
  base="$(basename "${fw}")"
  name="${base}"
  case "${base}" in
    *.framework)   name="${base%.framework}" ;;
    *.xcframework) name="${base%.xcframework}" ;;
    *.dylib)       name="${base%.dylib}" ;;
  esac
  allowed=0
  for ok in "${FW_ALLOW[@]}"; do
    if [ "${name}" = "${ok}" ]; then
      allowed=1
      break
    fi
  done
  if [ "${allowed}" -eq 1 ]; then
    info "OK：${name}（$(rel "${fw}")）"
  else
    case "${base}" in
      *.xcframework)
        gate_fail "G4-a" "出现非预期 xcframework：${name}（$(rel "${fw}")）—— 预编译 SDK 一定带着自己的网络代码，源码门禁看不见它"
        ;;
      *.dylib)
        gate_fail "G4-a" "出现非预期 dylib：${base}（$(rel "${fw}")）—— 第一批不引任何第三方预编译库"
        ;;
      *)
        gate_fail "G4-a" "出现非预期 framework：${name}（$(rel "${fw}")）—— 第一批只允许 KMP 产物；第三方 SDK 请走评审并显式加白"
        ;;
    esac
  fi
done

# ── G4-b 第三方遥测血统（原始字节 + macOS 上的 nm -u）───────────────────────
say ""
say "【G4-b】第三方遥测血统符号（不扫 Foundation 自带的网络符号）"
info "特征串（patterns/network-binary.txt，${#SYMPAT[@]} 条）：$(printf '%s ' "${SYMPAT[@]}")"

MACHO=()
while IFS= read -r f; do
  if is_macho "${f}"; then
    MACHO+=("${f}")
  fi
done < <(find "${APP}" -type f 2>/dev/null)

if [ "${#MACHO[@]}" -eq 0 ]; then
  gate_fail "G4-b" "产物里一个 Mach-O 二进制都没找到 —— 空壳产物，符号扫描无从下手（拒绝报通过）"
else
  info "Mach-O 二进制 ${#MACHO[@]} 个（主可执行文件 + 内嵌 framework 等）"
fi

for bin in ${MACHO[@]+"${MACHO[@]}"}; do
  # ① 原始字节：符号名本身就在 Mach-O 的字符串表里，任何平台都能 grep 到。
  for pat in "${SYMPAT[@]}"; do
    if grep -a -F -q -- "${pat}" "${bin}" 2>/dev/null; then
      gate_fail "G4-b" "二进制里出现遥测血统特征串：${pat}（$(rel "${bin}")）—— 供应链层混入了第三方遥测 SDK"
    fi
  done

  # ② 未定义符号（macOS 才有 nm 与 Mach-O 解析）：设计文档指定的判据 `nm -u`。
  if [ "${HAS_NM}" -eq 1 ]; then
    UNDEF="$(nm -u "${bin}" 2>/dev/null || true)"
    if [ -n "${UNDEF}" ]; then
      for pat in "${SYMPAT[@]}"; do
        if printf '%s\n' "${UNDEF}" | grep -Fq -- "${pat}"; then
          gate_fail "G4-b" "未定义符号里出现遥测血统：${pat}（$(rel "${bin}")）—— 这个 SDK 是被链接进来调用的，不只是字符串残留"
        fi
      done
    fi
  fi
done

if [ "${HAS_NM}" -eq 1 ]; then
  info "nm -u：已核对 ${#MACHO[@]} 个二进制的未定义符号（符号级判据）"
else
  # ★ 关键：说清楚这是「未执行」，不是「通过」。
  notice "G4-b 本机没有 nm（不是 macOS）→ 「nm -u 未定义符号」子检查**已跳过、未执行**。原始字节扫描照常做了；符号级验证会在 CI 的 macOS runner 上发生。请不要把这次绿当成符号扫描通过。"
fi

# ── G4-c 产物 Info.plist 的 ATS 未放宽 ──────────────────────────────────────
say ""
say "【G4-c】产物 Info.plist 的 ATS 未放宽"
PLIST="${APP}/Info.plist"
if [ ! -f "${PLIST}" ]; then
  gate_die "产物里没有 Info.plist：${PLIST} —— ATS 无法判定（拒绝静默通过）"
fi

if [ "${HAS_PLUTIL}" -eq 1 ]; then
  for key in "${ATS_KEYS[@]}"; do
    val="$(plutil -extract "NSAppTransportSecurity.${key}" raw -o - "${PLIST}" 2>/dev/null || true)"
    case "${val}" in
      ""|false|FALSE|False|0)
        info "ATS.${key} = ${val:-未设置} → 未放宽（OK）"
        ;;
      *)
        gate_fail "G4-c" "产物 Info.plist 的 ATS 被放宽：NSAppTransportSecurity.${key} = ${val}（$(rel "${PLIST}")）—— 违反零网络承诺"
        ;;
    esac
  done
else
  # 文本兜底（本机没有 plutil）。二进制 plist 无法用文本手段判定 → 明确报错，
  # 不猜、不装作通过；CI 的 macOS runner 上走的是上面 plutil 那条权威路径。
  HEAD8="$(head -c 6 "${PLIST}" 2>/dev/null || true)"
  if [ "${HEAD8}" = "bplist" ]; then
    gate_die "本机没有 plutil，而产物 Info.plist 是**二进制格式** —— ATS 无法用文本手段判定（拒绝装作通过）。请在 macOS 上运行本脚本（CI 的 ios-package job 就是 macOS）。"
  fi
  FLAT="${TMP}/plist-flat.txt"
  tr '\n\r\t' '   ' < "${PLIST}" > "${FLAT}" 2>/dev/null || true
  for key in "${ATS_KEYS[@]}"; do
    if grep -q -E "<key>[[:space:]]*${key}[[:space:]]*</key>[[:space:]]*<true[[:space:]]*/>" "${FLAT}" 2>/dev/null; then
      gate_fail "G4-c" "产物 Info.plist 的 ATS 被放宽：${key} = true（$(rel "${PLIST}")）—— 违反零网络承诺"
    else
      info "ATS.${key}：未放宽（OK · 文本兜底解析）"
    fi
  done
  notice "G4-c 本机没有 plutil（不是 macOS）→ 用的是**文本兜底解析**（只认 XML plist）。二进制 plist 会直接报错而不是通过；权威解析在 macOS runner 上。"
fi

# ── G4-d 产物 entitlements 无网络能力 ───────────────────────────────────────
say ""
say "【G4-d】产物 entitlements 无网络能力"
ENT_FILES=()
while IFS= read -r f; do
  if [ -n "${f}" ]; then
    ENT_FILES+=("${f}")
  fi
done < <(find "${APP}" -type f \( -name '*.entitlements' -o -name '*.xcent' \) 2>/dev/null)

if [ "${#ENT_FILES[@]}" -gt 0 ]; then
  info "找到 ${#ENT_FILES[@]} 个 entitlements 文件"
  for f in "${ENT_FILES[@]}"; do
    for pat in "${ENT_PATS[@]}"; do
      if grep -a -F -q -- "${pat}" "${f}" 2>/dev/null; then
        gate_fail "G4-d" "entitlements 出现网络能力：${pat}（$(rel "${f}")）"
      fi
    done
  done
else
  info "产物里没有 .entitlements / .xcent 文件（未签名产物常见）"
fi

# 主可执行文件（用于 codesign 读 entitlements 与依赖清单）
detect_main_exec() {
  local app="$1" nm_exec="" candidate=""
  if [ "${HAS_PLUTIL}" -eq 1 ] && [ -f "${app}/Info.plist" ]; then
    nm_exec="$(plutil -extract CFBundleExecutable raw -o - "${app}/Info.plist" 2>/dev/null || true)"
  fi
  if [ -z "${nm_exec}" ]; then
    nm_exec="$(basename "${app}" .app)"
  fi
  candidate="${app}/${nm_exec}"
  if [ -f "${candidate}" ]; then
    printf '%s' "${candidate}"
    return 0
  fi
  return 1
}
MAIN_EXEC="$(detect_main_exec "${APP}" || true)"

if [ "${HAS_CODESIGN}" -eq 1 ] && [ -n "${MAIN_EXEC}" ]; then
  ENT_DUMP="$(codesign -d --entitlements :- "${MAIN_EXEC}" 2>/dev/null || true)"
  if [ -n "${ENT_DUMP}" ]; then
    for pat in "${ENT_PATS[@]}"; do
      if printf '%s\n' "${ENT_DUMP}" | grep -Fq -- "${pat}"; then
        gate_fail "G4-d" "签名里的 entitlements 出现网络能力：${pat}（$(rel "${MAIN_EXEC}")）"
      fi
    done
    info "codesign -d --entitlements：已核对"
  else
    notice "G4-d 产物未签名（codesign 读不出 entitlements）—— 这与硬规则一「CI 一律不签名」一致；本判据改由上面的 .entitlements / .xcent 文件扫描兜底。"
  fi
else
  notice "G4-d 本机没有 codesign（不是 macOS）→ 「从签名里读 entitlements」子检查**已跳过、未执行**；文件扫描照常做了。"
fi

# ── G4-e 依赖清单落档 ───────────────────────────────────────────────────────
say ""
say "【G4-e】依赖清单落档"
MANIFEST_FILE="${MANIFEST:-${TMP}/dependency-manifest.txt}"
MANIFEST_DIR="$(dirname "${MANIFEST_FILE}")"
if [ ! -d "${MANIFEST_DIR}" ]; then
  mkdir -p "${MANIFEST_DIR}" || gate_die "无法创建清单输出目录：${MANIFEST_DIR}"
fi

{
  say "# G4 依赖清单（T21 · 供应链接口）"
  say "# 产物：${TARGET}"
  say "# 判据出处：docs/IOS-ARCH-EXEC.md §4.3 / §T21"
  say ""
  say "## 内嵌 framework / dylib（白名单：$(printf '%s ' "${FW_ALLOW[@]}")）"
  if [ "${#FRAMEWORKS[@]}" -eq 0 ]; then
    say "(无)"
  else
    for fw in "${FRAMEWORKS[@]}"; do
      say "$(basename "${fw}")"
    done
  fi
  say ""
  say "## Mach-O 二进制（路径 | 架构 | 未定义符号数）"
  if [ "${#MACHO[@]}" -eq 0 ]; then
    say "(无)"
  else
    for bin in "${MACHO[@]}"; do
      archs="unknown"
      if [ "${HAS_LIPO}" -eq 1 ]; then
        archs="$(lipo -archs "${bin}" 2>/dev/null || true)"
        if [ -z "${archs}" ]; then
          archs="unknown"
        fi
      fi
      undef_n="-"
      if [ "${HAS_NM}" -eq 1 ]; then
        undef_n="$(nm -u "${bin}" 2>/dev/null | wc -l | tr -d ' ' || true)"
        if [ -z "${undef_n}" ]; then
          undef_n="-"
        fi
      fi
      say "$(rel "${bin}") | ${archs} | ${undef_n}"
    done
  fi
  say ""
  say "## 结论"
  say "违规数：${VIOLATIONS}"
} > "${MANIFEST_FILE}"

info "依赖清单：${MANIFEST_FILE}"
sed 's/^/  /' "${MANIFEST_FILE}"

# ── 收尾 ─────────────────────────────────────────────────────────────────────
say ""
if [ "${VIOLATIONS}" -gt 0 ]; then
  say "✗ G4 产物二进制扫描未通过：${VIOLATIONS} 处违规 —— 这个包不能发布"
  exit 1
fi
say "✓ G4 产物二进制扫描通过（${#MACHO[@]} 个 Mach-O / ${#FRAMEWORKS[@]} 个内嵌 framework / ${#SYMPAT[@]} 条遥测特征串 / ${#ATS_KEYS[@]} 条 ATS 开关 / ${#ENT_PATS[@]} 条网络能力）"
exit 0
