#!/usr/bin/env bash
# tools/ci/scan-apk.sh —— APK 产物扫描（Android 侧的「产物层」零网络门禁）。
#
# ★ 这道门禁补的是**现有全部门禁共同的第二个盲区**：
#   · 六道源码门禁（check-no-network.sh / check-no-network-config.sh /
#     check-apple-credentials.sh / check-common-purity.sh /
#     check-ios-project-spec.sh / check-swift-contract-args.sh）**只扫文本**；
#   · 唯一的产物门禁 scan-binary.sh **只跑 ipa**（ci-gates.yml 的 ios-package job，macOS）。
#   ⇒ 一个第三方网络 SDK 以**二进制**形式混进 Android 包时（Java 代码进了 dex、
#     原生库进了 lib/<abi>/*.so），六道门禁一道都不会红，产物门禁也不看 apk。
#     本脚本就是补这个洞：直接在**要发出去的那个 apk** 上看。
#
# ── 判据（三条，按可靠性从高到低）────────────────────────────────────────────
#
#   A1  lib/**/*.so 白名单（穷举允许）
#       APK 里每个 *.so 的文件名必须在 patterns/apk-native-allow.txt 里。
#       ★ 为什么是白名单：原生库数量少、来源单一，允许什么必须一个个写出来。
#         一个 .so 可以自带 http 栈自己打点上报，**dex 里一行痕迹都没有**，
#         所以这一层不靠特征串，靠「没批过的一律不许进」。
#       ★ 空壳产物（一个 .so 都没有）同样判红：ML Kit 的推理管线是必需依赖，
#         少了它这个包根本拍不出结果 —— 那说明构建链路出了问题。
#
#   A2  第三方网络 / 遥测 SDK 血统（黑名单）
#       ★ 判据分两级，**权威的是 mapping.txt，不是 dex**：
#         · A2·r：R8 的 `mapping.txt` 记录「原始类名 → 混淆名」，且只列真的留在包里的类。
#                 用它匹配黑名单 —— 不受混淆影响，只反映发布产物。（CI 的 release 步骤传 --mapping）
#         · A2·d：dex 扫描降级为**参考**，只打 ::notice:: 不判红。
#       ★ 为什么（2026-11-15 首次真跑时踩的）：R8 会把 `okhttp3` 改名成 `a.b.c`，
#         dex 字符串匹配在 release 产物上必然失效；而 debug 包搜到就判红 —— debug 根本不发布，
#         且那个 okhttp 是 `com.google.mlkit:vision-internal-vkp` 的传递依赖，删不掉。
#         结果是门禁在正常路径上永远红。实测真实 release 的 mapping.txt 里
#         `com.squareup.okhttp3.*` 与 `okio.*` 各 0 个类 ⇒ R8 确实把它们剥掉了。
#       ★★ 零网络的真正兜底是 A3（没有 INTERNET 权限）：Android 在 socket 层就拦住，
#          包里就算留着网络代码也开不了连接。A2 是供应链层的补充信号，不是唯一防线。
#       ★ 为什么 dex 侧用黑名单而 .so 侧用白名单，见清单文件头注释
#         （patterns/apk-dex-forbidden.txt「分工」一段）：
#         dex 的对象是整棵依赖树，合法类名前缀上百个，白名单必然被例外加到失效。
#
#   A3  产物 manifest 的网络权限（用 aapt2 判，不自己解析 AXML）
#       产物里的 AndroidManifest.xml 是**二进制 XML**，字符串存 UTF-16 池里，
#       grep 直接搜字节搜不到。⇒ 用 build-tools 的 `aapt2 dump permissions`。
#       **拿不到 aapt2 就 exit 2**（拒绝静默通过）—— 见 find_aapt2 的注释。
#
# ★★ 明确**不做**的三件事（做了 100% 误报，误报的门禁等于没有门禁）：
#   ① 不用网络特征串去扫 .so —— ML Kit 是 Google 的库，二进制里必然有
#      http / telemetry 的痕迹（同理 scan-binary.sh 不扫 NSURLSession / CFNetwork）。
#   ② 不用网络特征串去扫**整个 dex** 的所有字符串 —— 同上，也会命中 ML Kit 内部的
#      URL 常量。A2 只搜**类名/包名形态**的第三方 SDK 血统。
#   ③ 不给 dex 的类名前缀做白名单 —— 依赖树太深，最后一定被加例外加到失效。
#
# ★ 本脚本**刻意不 source lib/common.sh**（与 scan-binary.sh 同因）：
#   只用 bash 3.2 也支持的语法（不用 mapfile、不用关联数组），
#   这样在任何一台开发机 / runner 上都不会因为 bash 版本而「门禁自己先挂掉」。
#
# 用法： tools/ci/scan-apk.sh <apk 路径> [--mapping <mapping.txt>] [--manifest <清单输出文件>]
#         --mapping：Release 产物**必须**传 —— R8 的 mapping.txt 是 A2 的权威判据。
#                    不传则该判据不执行（会打 ::notice:: 明说「没跑」，不是静默通过）。
#         --manifest：可选；不给就写到临时目录（内容照样打印）
# 退出码： 0 = 通过（且**确实执行了**判据）
#          1 = 有违规
#          2 = 门禁自身出错（apk 不存在 / 解不开 / 没有 aapt2 / 一个 dex 都没找到 /
#              给了 --mapping 但文件不存在）
#              —— 绝不静默通过
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATTERNS_DIR="${SCRIPT_DIR}/patterns"

VIOLATIONS=0

# ── 输出（格式与 scan-binary.sh / lib/common.sh 对齐）────────────────────────
say()    { printf '%s\n' "$*"; }
info()   { printf '  %s\n' "$*"; }
notice() { printf '::notice::%s\n' "$*"; }
warn()   { printf '::warning::%s\n' "$*" >&2; }

# gate_die <消息> —— 门禁自身出错（exit 2）。**绝不**用它表示"跳过"。
gate_die() {
  printf '::error::门禁自身出错：%s\n' "$*" >&2
  exit 2
}

# gate_fail <判据编号> <消息> —— 记一处违规（最后统一 exit 1）。
# ★ 不在命中处立即退出：一次运行把所有问题都报出来。
gate_fail() {
  printf '::error::[%s] %s\n' "$1" "$2" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
}

# load_patterns <清单文件名> —— 输出清单里的有效行（去 '#' 注释与空行）。
load_patterns() {
  local name="$1"
  local file="${PATTERNS_DIR}/${name}"
  [ -f "${file}" ] || gate_die "缺少特征串清单：${name}（期望位置：${file}）"
  sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "${file}"
}

# ── 参数 ─────────────────────────────────────────────────────────────────────
TARGET=""
MANIFEST=""
MAPPING=""
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
    --mapping)
      [ "$#" -ge 2 ] || gate_die "--mapping 后面必须跟一个 mapping.txt 路径"
      MAPPING="$2"
      shift 2
      ;;
    --mapping=*)
      MAPPING="${1#*=}"
      shift
      ;;
    -h|--help)
      sed -n '2,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*)
      gate_die "不认识的选项：$1"
      ;;
    *)
      [ -z "${TARGET}" ] || gate_die "只能给一个 apk 路径（已经给了：${TARGET}）"
      TARGET="$1"
      shift
      ;;
  esac
done
[ -n "${TARGET}" ] || gate_die "用法：tools/ci/scan-apk.sh <apk 路径> [--mapping <mapping.txt>] [--manifest <文件>]"

# ★ 这里必须是红，不能是"没东西可扫 → 通过"。
#   静默通过的门禁比没有门禁更糟：它会让「产物路径写错」看起来像「扫描通过」。
[ -f "${TARGET}" ] || gate_die "找不到产物：${TARGET}（预期是一个存在的 .apk 文件）"
[ -s "${TARGET}" ] || gate_die "产物是空文件：${TARGET}"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

command -v unzip >/dev/null 2>&1 \
  || gate_die "本机没有 unzip，无法读 apk 内容（拒绝静默通过）"

say "▶ APK 产物扫描（Android 侧 · 零网络承诺的产物层）"
info "产物：${TARGET}"
info "大小：$(wc -c < "${TARGET}" | tr -d ' ') 字节"

# ── 清单（一律从 patterns/*.txt 读，脚本里不写被禁字面量）───────────────────
NATIVE_ALLOW=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then NATIVE_ALLOW+=("${line}"); fi
done < <(load_patterns "apk-native-allow.txt")
[ "${#NATIVE_ALLOW[@]}" -gt 0 ] || gate_die "清单 apk-native-allow.txt 为空"

DEX_FORBID=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then DEX_FORBID+=("${line}"); fi
done < <(load_patterns "apk-dex-forbidden.txt")
[ "${#DEX_FORBID[@]}" -gt 0 ] || gate_die "清单 apk-dex-forbidden.txt 为空"

# 已知的「随合法依赖进来、但不可达」的网络组件（见清单文件头的准入三条）。
# ★ 允许文件**缺失时按空处理**（不 die）：它是本清单的补充，不是判据的前提；
#   而真正的判据 A3（无 INTERNET 权限）与之无关，不会因此被跳过。
NET_ALLOW=()
if [ -f "${PATTERNS_DIR}/apk-network-allow.txt" ]; then
  while IFS= read -r line; do
    if [ -n "${line}" ]; then NET_ALLOW+=("${line}"); fi
  done < <(load_patterns "apk-network-allow.txt")
fi

# 判断某个特征串是否在允许清单里（精确相等，不做前缀/子串匹配）
is_net_allowed() {
  local pat="$1" a
  for a in ${NET_ALLOW[@]+"${NET_ALLOW[@]}"}; do
    [ "${a}" = "${pat}" ] && return 0
  done
  return 1
}

PERM_FORBID=()
while IFS= read -r line; do
  if [ -n "${line}" ]; then PERM_FORBID+=("${line}"); fi
done < <(load_patterns "apk-forbidden-permissions.txt")
[ "${#PERM_FORBID[@]}" -gt 0 ] || gate_die "清单 apk-forbidden-permissions.txt 为空"

# ── 先证明「这个 apk 真的能被解开」──────────────────────────────────────────
# ★ 解不开 = 门禁自身出错（exit 2），不是违规 —— 因为此时三条判据一条都没真跑，
#   报"违规"会让人以为扫过了。
ENTRIES="${TMP}/entries.txt"
if ! unzip -Z1 "${TARGET}" > "${ENTRIES}" 2>"${TMP}/unzip.err"; then
  gate_die "apk 解不开（不是合法的 zip 结构）：${TARGET}；unzip 输出：$(tr -d '\r' < "${TMP}/unzip.err" | head -3 | tr '\n' ' ')"
fi
ENTRY_COUNT="$(wc -l < "${ENTRIES}" | tr -d ' ')"
[ "${ENTRY_COUNT}" -gt 0 ] || gate_die "apk 里一个条目都没有：${TARGET}"

# ★ 有的打包工具（`zip -r out.apk .` 就是这样）会把条目名写成 "./classes.dex"。
#   带前缀会让后面 `^classes[0-9]*\.dex$` 这种锚定匹配**全部落空** ——
#   表现是「apk 里没有 dex → 门禁自身出错」，或者更糟：A1 什么都没扫却报通过。
#   ⇒ 统一去掉一层 "./"（只去一层，其余路径原样）。
sed 's|^\./||' "${ENTRIES}" > "${TMP}/entries-norm.txt"
ENTRIES="${TMP}/entries-norm.txt"
info "zip 条目：${ENTRY_COUNT} 个"

# ── A1 原生库白名单 ─────────────────────────────────────────────────────────
say ""
say "【A1】lib/**/*.so 白名单（穷举允许；出现清单外的 .so ⇒ 红）"
info "白名单（patterns/apk-native-allow.txt，${#NATIVE_ALLOW[@]} 个）：$(printf '%s ' "${NATIVE_ALLOW[@]}")"

SO_LIST="${TMP}/so.txt"
# ★ 不只扫 lib/：任何路径下的 *.so 都要过白名单（万一有人把库塞进 assets/）。
grep -E '\.so$' "${ENTRIES}" > "${SO_LIST}" 2>/dev/null || true

SO_COUNT="$(wc -l < "${SO_LIST}" | tr -d ' ')"
if [ "${SO_COUNT}" -eq 0 ]; then
  gate_fail "A1" "apk 里一个 .so 都没有 —— 空壳产物（ML Kit 的推理管线是必需依赖，少了它这个包拍不出结果）"
else
  info "找到 ${SO_COUNT} 个 .so："
  while IFS= read -r so; do
    [ -n "${so}" ] || continue
    base="$(basename "${so}")"
    allowed=0
    for ok in "${NATIVE_ALLOW[@]}"; do
      if [ "${base}" = "${ok}" ]; then allowed=1; break; fi
    done
    if [ "${allowed}" -eq 1 ]; then
      info "  OK：${so}"
    else
      gate_fail "A1" "出现白名单外的原生库：${so} —— 一个 .so 可以自带 http 栈自己上报，dex 里没有痕迹；要放行必须显式加进 patterns/apk-native-allow.txt 并说明来源"
    fi
  done < "${SO_LIST}"
fi

# ── A2 dex 里的第三方网络 / 遥测 SDK 血统 ───────────────────────────────────
say ""
# ── A2 第三方网络 / 遥测 SDK 血统 ────────────────────────────────────────────
#
# ★★ 这一条判错过一次（2026-11-15 首次在 CI 上跑），别再改回「只看 dex」。
#
#   判据必须对准**发出去的包**，不是编译中间产物。而 R8 会把类名改成 `a.b.c`，
#   所以 dex 上的字符串匹配在 release 产物上必然失效 —— 只剩两种结局：
#     ① 永远绿（改了名，搜不到）；
#     ② 或者像那次一样：debug 包里搜到 `okhttp3` 就判红。
#   可 debug **根本不发布**，而那个 okhttp 是 ML Kit 的传递依赖：
#       com.google.mlkit:vision-internal-vkp:18.2.3
#         \--- com.squareup.okhttp3:okhttp:3.0.0 \--- okio:1.6.0
#   删不掉（删了 ML Kit 就没了）⇒ 门禁在正常路径上永远红 ⇒ 最后一定被绕过。
#
#   ★ 正确判据是 R8 的 `mapping.txt`：它记录「原始类名 → 混淆名」，且**只列真的
#     留在包里的类**。用原始类名匹配黑名单，既不受混淆影响，又只反映发布产物。
#     实测（2026-11-15，本仓真实 release 产物）：
#       grep -c "^com\.squareup\.okhttp3\." mapping.txt  → 0
#       grep -c "^com\.squareup\.okio\."   mapping.txt  → 0
#     即 R8 把 ML Kit 那条路径上的 okhttp 整块剥掉了 —— 发布包里确实没有。
#
#   ★ dex 扫描降级为**参考**（只 ::notice::，不判红）：它回答的是「依赖树里有没有」，
#     而这个问题对 ML Kit 的答案恒为「有」。
#
#   ★★ 零网络的真正兜底是 A3（没有 INTERNET 权限）：Android 在 socket 层就拦住了，
#     包里即使留着网络代码也开不了连接。A2 是供应链层的补充信号，不是唯一防线。

say "【A2·r】发布产物里实际保留的网络 SDK（R8 mapping，${#DEX_FORBID[@]} 条特征串）"
if [ -n "${MAPPING}" ]; then
  # ★ 给了 --mapping 却找不到文件，是**配置错误**，必须 exit 2。
  #   不能静默降级成「只做 dex 参考扫描」—— 那会让「忘了传参数」看起来像「扫描通过」。
  [ -f "${MAPPING}" ] || gate_die "给了 --mapping 但文件不存在：${MAPPING}（拒绝静默降级）"
  [ -s "${MAPPING}" ] || gate_die "mapping.txt 是空文件：${MAPPING}（R8 没跑？）"
  info "mapping：$(basename "${MAPPING}")（$(wc -l < "${MAPPING}" | tr -d ' ') 行）"

  # ★ 只看**类声明行**（顶格、无缩进），不看成员行。
  #
  #   为什么必须区分：mapping.txt 里除了「原始类名 -> 混淆名」的顶格行，还有大量
  #   **内联帧**的缩进行，形如
  #       63:72:java.text.DateFormat okhttp3.internal.http.HttpDate$1.initialValue():42:42 -> initialValue
  #   R8 把某个方法体内联进别的类之后，会保留这条 retrace 元数据。于是 `grep okhttp3`
  #   在真实产物上能搜到东西，但 `grep '^com\.squareup\.okhttp3\.'` 是 0 ——
  #   **okhttp 的类一个都没活下来，只是某个日期格式化方法被内联了**。
  #
  #   判据要回答的是「这个 SDK 的类有没有留在发出去的包里」，所以必须锚在顶格类声明上。
  #   拿任意子串判会把「被内联的工具方法」也算成「SDK 还在」，那就又变回永远红。
  CLASS_LINES="${TMP}/mapping-classes.txt"
  grep -a -E '^[^[:space:]]' "${MAPPING}" > "${CLASS_LINES}" 2>/dev/null || true
  class_total="$(wc -l < "${CLASS_LINES}" | tr -d ' ')"
  info "其中类声明 ${class_total} 条（只看这些；内联帧不计）"

  mapping_hits=0
  allowed_hits=0
  for pat in "${DEX_FORBID[@]}"; do
    if grep -a -F -q -- "${pat}" "${CLASS_LINES}"; then
      n="$(grep -a -F -c -- "${pat}" "${CLASS_LINES}" | tr -d ' ')"
      if is_net_allowed "${pat}"; then
        # ★ 允许 ≠ 隐身：每一条都打 notice，并说清「靠什么保证不可达」。
        allowed_hits=$((allowed_hits + 1))
        notice "「${pat}」有 ${n} 个类留在发布包里，但在允许清单内（来源见 patterns/apk-network-allow.txt）。零网络由 A3 保证：manifest 无 INTERNET 权限 ⇒ socket 层不可达。"
      else
        gate_fail "A2" "发布产物里**保留了**第三方网络 / 遥测 SDK 的类：${pat}（${n} 个类，R8 mapping 类声明行证实）—— 违反零网络承诺，且不在允许清单内"
        mapping_hits=$((mapping_hits + 1))
      fi
    else
      # 只在内联帧里出现 ⇒ 记一条 notice，让它不可见地消失，但不判红
      if grep -a -F -q -- "${pat}" "${MAPPING}"; then
        notice "「${pat}」只出现在 mapping 的**内联帧**里（方法被内联进别的类），类声明 0 条 ⇒ 不算违规，但记在此处备查"
      fi
    fi
  done
  info "类声明 × ${#DEX_FORBID[@]} 条特征串：违规 ${mapping_hits} 条 / 允许清单内 ${allowed_hits} 条"
else
  notice "A2 未收到 --mapping ⇒ **发布产物级的 SDK 判据没有执行**，下面只有 dex 参考扫描。CI 的 release 步骤必须传 --mapping，否则这条等于没跑。"
fi

say ""
say "【A2·d】dex 参考扫描（只列，不判红 —— 理由见上方注释）"

DEX_ENTRIES="${TMP}/dex-entries.txt"
grep -E '^classes[0-9]*\.dex$' "${ENTRIES}" > "${DEX_ENTRIES}" 2>/dev/null || true
DEX_COUNT="$(wc -l < "${DEX_ENTRIES}" | tr -d ' ')"
if [ "${DEX_COUNT}" -eq 0 ]; then
  # ★ 没有 dex 说明这压根不是可安装的 APK —— 连 A3 的权限都无从谈起。
  gate_die "apk 里没有 classes*.dex（不是可安装的 APK？）—— 无从判定，拒绝静默通过"
fi
info "找到 ${DEX_COUNT} 个 dex"

# ★ 先解压到临时文件再 grep，**不要**写 `unzip -p … | grep -q`：
#   管道 + `grep -q` + `set -o pipefail` 三者一起用时，grep 命中后立即退出会让
#   unzip 收到 SIGPIPE（rc=141），pipefail 把整条管道判成失败 ——
#   于是「命中」被读成「没命中」，A2 变成永远绿。这正是最难发现的那类门禁 bug。
#   解到文件再搜，就完全没有这个陷阱（顺带还能对多个特征串复用一次解压）。
i=0
dex_hit=0
while IFS= read -r dex; do
  [ -n "${dex}" ] || continue
  i=$((i + 1))
  out="${TMP}/dex_${i}.bin"
  if ! unzip -p "${TARGET}" "${dex}" > "${out}" 2>/dev/null; then
    gate_die "解不出 dex：${dex}（apk 可能损坏）"
  fi
  [ -s "${out}" ] || gate_die "解出来的 dex 是空的：${dex}"
  size="$(wc -c < "${out}" | tr -d ' ')"
  info "${dex}：${size} 字节"
  for pat in "${DEX_FORBID[@]}"; do
    if grep -a -F -q -- "${pat}" "${out}"; then
      notice "dex（${dex}）里出现「${pat}」—— 依赖树里有它，但这不是发布判据（见 A2·r / 文件头注释）"
      dex_hit=$((dex_hit + 1))
    fi
  done
done < "${DEX_ENTRIES}"
info "已扫 ${i} 个 dex × ${#DEX_FORBID[@]} 条特征串：dex 参考命中 ${dex_hit} 处"
if [ "${dex_hit}" -gt 0 ] && [ -z "${MAPPING}" ]; then
  notice "有 dex 命中但没给 --mapping ⇒ **无法判断这些类是否真的留在发布包里**。release 产物请务必传 --mapping。"
fi

# ── A3 产物 manifest 的网络权限（aapt2）─────────────────────────────────────
say ""
say "【A3】产物 manifest 的网络权限（aapt2 dump permissions）"
info "禁用权限（patterns/apk-forbidden-permissions.txt，${#PERM_FORBID[@]} 条）：$(printf '%s ' "${PERM_FORBID[@]}")"

# find_aapt2 —— 依次尝试，输出可用的 aapt2 路径。
#
# ★ 为什么去 gradle 缓存里捞：
#   AGP 构建时会从 maven 下 `com.android.tools.build:aapt2:<ver>-<os>.jar`，
#   那个 jar 里就是 aapt2 可执行文件（Windows 是 aapt2.exe、Linux 是 aapt2）。
#   于是「刚构建完的机器」一定有 aapt2，不需要额外装一层 build-tools。
#   本地开发机（Windows + Git Bash）没有 ANDROID_HOME，靠的就是这一条。
#
# ★ 为什么还是要 exit 2（找不到就失败）而不是「跳过 A3」：
#   A3 是三条判据里唯一看**权限**的，跳过它 = 零网络承诺里最硬的那条没人验。
#   一个静默跳过的门禁比没有门禁更危险（它把"没验"显示成"通过"）。
find_aapt2() {
  local cand=""
  # ① 显式指定（CI / 反向验证用）：SHOTTUNER_AAPT2=/path/to/aapt2
  if [ -n "${SHOTTUNER_AAPT2:-}" ]; then
    if [ -x "${SHOTTUNER_AAPT2}" ]; then printf '%s' "${SHOTTUNER_AAPT2}"; return 0; fi
    return 1
  fi
  # ② ANDROID_HOME / ANDROID_SDK_ROOT 下的 build-tools（取版本最高的一个）
  local root best
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "${root}" ] || continue
    [ -d "${root}/build-tools" ] || continue
    best="$(ls -1 "${root}/build-tools" 2>/dev/null | sort -V 2>/dev/null | tail -1 || true)"
    [ -n "${best}" ] || continue
    for cand in "${root}/build-tools/${best}/aapt2" "${root}/build-tools/${best}/aapt2.exe"; do
      if [ -x "${cand}" ]; then printf '%s' "${cand}"; return 0; fi
    done
  done
  # ③ PATH
  if command -v aapt2 >/dev/null 2>&1; then command -v aapt2; return 0; fi
  if command -v aapt2.exe >/dev/null 2>&1; then command -v aapt2.exe; return 0; fi
  # ④ gradle 模块缓存里的 aapt2-<ver>-<os>.jar（AGP 构建时下下来的）
  local jar jdir n exe
  n=0
  if [ -n "${HOME:-}" ]; then
    for jar in $(ls -1 "${HOME}/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2"/*/*/aapt2-*.jar 2>/dev/null || true); do
      n=$((n + 1))
      jdir="${TMP}/aapt2jar_${n}"
      mkdir -p "${jdir}"
      unzip -o -q "${jar}" -d "${jdir}" >/dev/null 2>&1 || continue
      for exe in "${jdir}/aapt2" "${jdir}/aapt2.exe"; do
        [ -f "${exe}" ] || continue
        chmod +x "${exe}" 2>/dev/null || true
        # 只认「真的能跑起来」的那一个（同一台机器上可能同时有 windows 与 linux 的 jar）
        if "${exe}" version >/dev/null 2>&1; then printf '%s' "${exe}"; return 0; fi
      done
    done
  fi
  return 1
}

if ! AAPT2="$(find_aapt2)"; then
  gate_die "拿不到 aapt2 —— A3（产物 manifest 权限）无法判定，拒绝静默通过。请设 ANDROID_HOME/ANDROID_SDK_ROOT 指向 Android SDK，或用 SHOTTUNER_AAPT2=<路径> 显式指定。"
fi
info "aapt2：${AAPT2}"
# ★ aapt2 的 banner 打在 **stderr**（不是 stdout）—— 只重定向 stdout 会得到空串。
#   这里用 sed 而不是 head：head 提前关管道会给 aapt2 一个 SIGPIPE，
#   配合 pipefail 会把这条纯展示用的命令判成失败。
AAPT2_VER="$({ "${AAPT2}" version 2>&1 || true; } | tr -d '\r' | sed -n '1p')"
[ -n "${AAPT2_VER}" ] || AAPT2_VER="(读不出，但不影响下面的判定)"
info "aapt2 版本：${AAPT2_VER}"

PERMS_RAW="${TMP}/perms.raw"
if ! "${AAPT2}" dump permissions "${TARGET}" > "${PERMS_RAW}" 2>"${TMP}/aapt2.err"; then
  gate_die "aapt2 dump permissions 失败：${TARGET}；aapt2 输出：$(tr -d '\r' < "${TMP}/aapt2.err" | head -3 | tr '\n' ' ')"
fi
PERMS="${TMP}/perms.txt"
tr -d '\r' < "${PERMS_RAW}" > "${PERMS}"

# ★ 先用 `package:` 行证明「aapt2 真的读懂了这个 apk」。
#   连 package 都没有 = 输出是坏的/空的，此时**不能**把「读不到权限」当成
#   「没有网络权限」—— 那会把解析失败静默变成合规。
if ! grep -q '^package:' "${PERMS}"; then
  gate_die "aapt2 没解析出 package 行 —— 产物 manifest 读不动，A3 无法判定（拒绝静默通过）"
fi
info "$(grep '^package:' "${PERMS}" | head -1)"
info "$(grep -c '^uses-permission:' "${PERMS}" | tr -d ' ') 条 uses-permission："
sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" "${PERMS}" > "${TMP}/perm-names.txt"
while IFS= read -r p; do
  [ -n "${p}" ] && info "  ${p}"
done < "${TMP}/perm-names.txt"

for bad in "${PERM_FORBID[@]}"; do
  if grep -Fxq -- "${bad}" "${TMP}/perm-names.txt"; then
    gate_fail "A3" "产物 manifest 声明了网络权限：${bad} —— 违反零网络承诺（这个包即便代码里没联网，也**有能力**联网）"
  fi
done

# ── 依赖清单落档 ────────────────────────────────────────────────────────────
say ""
say "【清单】落档"
MANIFEST_FILE="${MANIFEST:-${TMP}/apk-manifest.txt}"
MANIFEST_DIR="$(dirname "${MANIFEST_FILE}")"
[ -d "${MANIFEST_DIR}" ] || mkdir -p "${MANIFEST_DIR}" || gate_die "无法创建清单输出目录：${MANIFEST_DIR}"

{
  say "# APK 产物清单（A1 原生库 / A2·r mapping / A2·d dex 参考 / A3 权限）"
  say "# 产物：${TARGET}"
  say "# 消费者：tools/ci/scan-apk.sh（ci-gates.yml 的 android-package job）"
  say ""
  say "## .so（白名单：$(printf '%s ' "${NATIVE_ALLOW[@]}")）"
  if [ "${SO_COUNT}" -eq 0 ]; then say "(无)"; else cat "${SO_LIST}"; fi
  say ""
  say "## dex（A2·d 参考扫描的对象）"
  say "# mapping：$([ -n "${MAPPING}" ] && echo "${MAPPING}" || echo "(未提供 —— A2·r 未执行)")"
  cat "${DEX_ENTRIES}"
  say ""
  say "## uses-permission"
  if [ -s "${TMP}/perm-names.txt" ]; then cat "${TMP}/perm-names.txt"; else say "(无)"; fi
  say ""
  say "## 结论"
  say "违规数：${VIOLATIONS}"
} > "${MANIFEST_FILE}"

info "清单：${MANIFEST_FILE}"
sed 's/^/  /' "${MANIFEST_FILE}"

# ── 收尾 ─────────────────────────────────────────────────────────────────────
say ""
if [ "${VIOLATIONS}" -gt 0 ]; then
  say "✗ APK 产物扫描未通过：${VIOLATIONS} 处违规 —— 这个包不能发布"
  exit 1
fi
say "✓ APK 产物扫描通过（${SO_COUNT} 个原生库 / ${DEX_COUNT} 个 dex / ${#DEX_FORBID[@]} 条 SDK 特征串 / ${#PERM_FORBID[@]} 条禁用权限 · aapt2 判定）"
exit 0
