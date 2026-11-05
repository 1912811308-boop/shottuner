#!/usr/bin/env bash
# tools/ci/check-swift-contract-args.sh —— Swift 契约参数名门禁（单向）。
#
# ★ 这道门禁为什么存在（根因一句话）：
#   **Windows 上 CI 只跑 shell 门禁、不编 Swift，所以契约改动永远是"零告警通过"。**
#   `ScoreTypes.swift` 里 `var suggestMove: Boolean`（应为 `Bool`）、`isLevel` 用 1° 而
#   Android 用 2.5°、`pitchAvailable` 从缺 —— 这三连错的共同根因就是这句。
#   编译期本来能抓住的东西，在这条流水线上抓不住，只能拿纯文本补一道。
#
# ── 只做什么 ──────────────────────────────────────────────────────────────────
#   抓「调用点用了契约里不存在的参数名」。
#
#   `GuideSnapshot(suggestMove: false, ...)` 而契约里字段已改名为 `shouldMove`
#   → Swift 编译不过，但 Windows 不编 Swift → 静默。
#   这类错**名字对不上就是硬错**，没有误报空间。
#
# ── 不做什么（同样重要）──────────────────────────────────────────────────────
#   不比对参数**个数**。Swift 的 memberwise init 允许跳过带默认值的参数
#   （`hasReading: Bool = false` 就是这种情况），也允许省略尾部参数，所以下面两种都合法：
#
#       GuideSnapshot(rollDegrees: 0, isLevel: true, ...)                      # 跳过 hasReading
#       GuideSnapshot(hasReading: true, rollDegrees: 0, isLevel: true, ...)    # 都写上
#
#   按个数比对会大量误报，而**误报的门禁等于没有门禁**（团队会立刻学会忽略它）。
#
# ── 口径 ─────────────────────────────────────────────────────────────────────
#   ① 契约来源：下面的 CONTRACTS 清单里的 struct，字段取自存储属性
#      （`var` / `let` 声明）；跳过 `static`、`private` 等修饰、跳过计算属性
#      （同一行带 `{` 的，例如 `var hasTotal: Bool { !anyUnmeasured }`）。
#   ② 调用点：`ios/**/*.swift` 里所有 `结构体名(` 形式。跨行调用会正确合并
#      （`MissionSnapshot(task: MissionTaskSnapshot(...))` 这种嵌套也算）。
#   ③ 注释不算 —— 扫描前先过 lib/strip-comments.awk（它保证行数不变，行号可直接定位）。
#   ④ 类型标注不算 —— `var guide: GuideSnapshot` 后面没有 `(`，天然不匹配。
#   ⑤ `.init(` 形式不支持（本项目没用；将来若用，这里会漏检而不是误报）。
#
# ── 临时排除 ─────────────────────────────────────────────────────────────────
#   ContentView.swift 正在接线（team-lead 改完会挪出排除表）。
#   ★ 一旦它从 EXCLUDED_WHILE_EDITING 里删掉，tools/ci/test-gates.sh 里
#     「ContentView 里的坏参数名 → 绿」那条用例会跟着变红 —— 那是**故意的绊线**，
#     提醒改的人把用例一起改掉，而不是让排除表 quietly 留下来。
#
# 用法： tools/ci/check-swift-contract-args.sh [仓库根目录]
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"

ROOT="${1:-$(ci_repo_root "${SCRIPT_DIR}")}"
[ -d "${ROOT}" ] || ci_die "仓库根目录不存在：${ROOT}"

ci_log "▶ Swift 契约参数名门禁（根目录：${ROOT}）"

# ── 契约清单 ─────────────────────────────────────────────────────────────────
# 只放"会被别处构造"的数据契约。**不**放 View（View 的 init 参数是 UI 组合，不是契约）。
CONTRACTS=(
  "ios/ShotTuner/Score/ScoreTypes.swift"
  "ios/ShotTuner/Mission/MissionTypes.swift"
)

# ── 临时排除 ─────────────────────────────────────────────────────────────────
# ★ 见文件头「临时排除」。删掉这一行的人，请同步改 tools/ci/test-gates.sh 的对应用例。
EXCLUDED_WHILE_EDITING=(
  "ios/ShotTuner/ContentView.swift"
)

for contract in "${CONTRACTS[@]}"; do
  [ -f "${ROOT}/${contract}" ] || ci_die "缺少契约文件：${contract}（清单写错了，或被移动了）"
done

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# ── ① 抽契约字段 ─────────────────────────────────────────────────────────────
# awk 程序写到临时文件而不是塞进命令行：内嵌引号太多，塞进去必然被 shell 咬一口。
cat > "${TMP}/fields.awk" <<'AWK'
# 输出：<StructName>\t<fieldName>
# 只收「结构体直接成员」的存储属性（depth == 1），嵌套 enum / struct 的成员跳过。
/^struct[[:space:]]+[A-Za-z_]/ {
  name = $2
  sub(/[<:{].*$/, "", name)          # 去掉泛型 / 继承 / `{`
  cur = name
  a = $0; b = $0
  depth = gsub(/\{/, "{", a) - gsub(/\}/, "}", b)
  next
}
cur == "" { next }
{
  line = $0
  # 存储属性：行首（允许缩进）就是 var / let，且这一行没有 `{`（有就是计算属性）
  # `static let x` / `private var y` 因为行首不是 var/let，天然被跳过。
  if (depth == 1 && index(line, "{") == 0) {
    if (match(line, /^[[:space:]]*(var|let)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:/)) {
      tok = substr(line, RSTART, RLENGTH)
      sub(/^[[:space:]]*(var|let)[[:space:]]+/, "", tok)
      sub(/[[:space:]]*:$/, "", tok)
      print cur "\t" tok
    }
  }
  a = $0; b = $0
  depth += gsub(/\{/, "{", a) - gsub(/\}/, "}", b)
  if (depth <= 0) { cur = ""; depth = 0 }
}
AWK

FIELDS="${TMP}/fields.tsv"
: > "${FIELDS}"
for contract in "${CONTRACTS[@]}"; do
  stripped="${TMP}/stripped.$$"
  awk -f "${CI_LIB_DIR}/strip-comments.awk" "${ROOT}/${contract}" > "${stripped}" 2>/dev/null || {
    ci_die "无法解析契约文件：${contract}"
  }
  awk -f "${TMP}/fields.awk" "${stripped}" >> "${FIELDS}"
done

if [ ! -s "${FIELDS}" ]; then
  ci_die "契约字段表为空 —— awk 解析契约失败（比静默放行好：宁可红，也不要装作通过）"
fi

STRUCT_NAMES="$(cut -f1 "${FIELDS}" | sort -u | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
[ -n "${STRUCT_NAMES}" ] || ci_die "没抽到任何结构体名"

STRUCT_COUNT="$(cut -f1 "${FIELDS}" | sort -u | wc -l | tr -d ' ')"
FIELD_COUNT="$(wc -l < "${FIELDS}" | tr -d ' ')"

# 查找用的扁平表："Struct field"。用单空格，靠 grep -Fx 精确整行匹配。
FLAT="${TMP}/fields.flat"
tr '\t' ' ' < "${FIELDS}" > "${FLAT}"

# ── ② 枚举要扫的 Swift 文件 ──────────────────────────────────────────────────
mapfile -t ALL_FILES < <(ci_list_files_under "${ROOT}" "*.swift" "ios" | sort -u)
[ "${#ALL_FILES[@]}" -gt 0 ] || {
  ci_info "尚无 iOS 源码可扫 —— 通过"
  exit 0
}

FILES=()
for rel in "${ALL_FILES[@]}"; do
  skip=0
  for ex in "${EXCLUDED_WHILE_EDITING[@]}"; do
    [ "${rel}" = "${ex}" ] && skip=1
  done
  [ "${skip}" -eq 1 ] && continue
  FILES+=("${rel}")
done

# ── ③ 抽调用点的参数名 ───────────────────────────────────────────────────────
cat > "${TMP}/calls.awk" <<'AWK'
# 用法：awk -v names="A B C" -f calls.awk <file>
# 输出：<line>\t<Struct>\t<label>
# 跨行调用靠"拼成一个大字符串 + 括号配平"处理；字符串字面量里的括号不算。
{ buf = buf $0 "\n" }
END {
  n = length(buf)
  if (n == 0) exit 0

  split(names, NA, " ")
  for (k = 1; k <= length(NA); k++) {
    s = NA[k]
    re = "(^|[^A-Za-z0-9_.])" s "[[:space:]]*\\("
    pos = 1
    while (pos <= n) {
      rest = substr(buf, pos)
      if (!match(rest, re)) break
      absStart = pos + RSTART - 1
      frag = substr(buf, absStart, RLENGTH)
      parenStart = absStart + index(frag, "(") - 1

      # 配平括号，找到本次调用的右括号
      depth = 0; j = parenStart; instr = 0; closed = 0
      while (j <= n) {
        c = substr(buf, j, 1)
        if (instr) {
          if (c == "\\") { j += 2; continue }
          if (c == "\"") instr = 0
          else if (c == "\n") instr = 0     # 兜底：Swift 单行字符串不含裸换行
        } else if (c == "\"") {
          instr = 1
        } else if (c == "(") {
          depth++
        } else if (c == ")") {
          depth--
          if (depth == 0) { closed = 1; break }
        }
        j++
      }
      if (!closed) break
      parenEnd = j

      # 行号：数一下 `(` 之前有几个换行
      head = substr(buf, 1, parenStart)
      line = gsub(/\n/, "\n", head) + 1

      # 取顶层参数：按 depth==0 的逗号切分，每段开头的 `名字:` 就是参数名
      # ★ 变量名别叫 args —— 下面要按下标取字符，同名会被 awk 当成数组用而报
      #   "attempt to use scalar as an array"。
      argstr = substr(buf, parenStart + 1, parenEnd - parenStart - 1)
      m = length(argstr)
      d = 0; instr2 = 0; part = ""; count = 0
      for (i = 1; i <= m; i++) {
        c = substr(argstr, i, 1)
        if (instr2) {
          part = part c
          if (c == "\\" && i < m) { i++; part = part substr(argstr, i, 1); continue }
          if (c == "\"") instr2 = 0
          continue
        }
        if (c == "\"") { instr2 = 1; part = part c; continue }
        if (c == "(" || c == "[" || c == "{") d++
        else if (c == ")" || c == "]" || c == "}") d--
        if (c == "," && d == 0) {
          parts[++count] = part; part = ""; continue
        }
        part = part c
      }
      if (length(part) > 0) parts[++count] = part

      for (i = 1; i <= count; i++) {
        if (match(parts[i], /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:/)) {
          lab = substr(parts[i], RSTART, RLENGTH)
          sub(/^[[:space:]]*/, "", lab)
          sub(/[[:space:]]*:$/, "", lab)
          print line "\t" s "\t" lab
        }
      }

      pos = parenStart + 1     # 从 `(` 之后继续扫 → 嵌套调用也能抓到
    }
  }
}
AWK

CALLS="${TMP}/calls.tsv"
: > "${CALLS}"
for rel in "${FILES[@]}"; do
  abs="${ROOT}/${rel}"
  [ -f "${abs}" ] || continue
  stripped="${TMP}/c.$$"
  awk -f "${CI_LIB_DIR}/strip-comments.awk" "${abs}" > "${stripped}" 2>/dev/null || {
    ci_info "跳过无法解析的文件：${rel}"
    continue
  }
  awk -v names="${STRUCT_NAMES}" -f "${TMP}/calls.awk" "${stripped}" \
    | while IFS=$'\t' read -r ln struct label; do
        printf '%s\t%s\t%s\t%s\n' "${rel}" "${ln}" "${struct}" "${label}"
      done >> "${CALLS}"
done

CALL_COUNT="$(wc -l < "${CALLS}" | tr -d ' ')"

ci_info "契约：${STRUCT_COUNT} 个结构体 / ${FIELD_COUNT} 个字段（${STRUCT_NAMES}）"
ci_info "扫描：${#FILES[@]} 个 .swift，发现 ${CALL_COUNT} 处构造参数"
if [ "${#EXCLUDED_WHILE_EDITING[@]}" -gt 0 ]; then
  ci_info "临时排除：${EXCLUDED_WHILE_EDITING[*]}"
fi

if [ "${CALL_COUNT}" -eq 0 ]; then
  # ★ 这里刻意不报错：新仓库 / 新契约在没有调用点时本来就该通过。
  #   真正的保险是 tools/ci/test-gates.sh 的反向验证 —— 它注入一个坏参数名，
  #   如果这边的解析器坏了（扫不到调用点），那条用例就会由红转绿，立刻暴露。
  ci_info "尚无构造点 —— 通过"
  exit 0
fi

# ── ④ 比对：只报「用了不存在的名字」 ─────────────────────────────────────────
VIOLATIONS=0
while IFS=$'\t' read -r rel ln struct label; do
  [ -n "${rel}" ] || continue
  [ -n "${struct}" ] || continue
  [ -n "${label}" ] || continue

  if grep -Fxq -- "${struct} ${label}" "${FLAT}"; then
    continue
  fi

  ci_error "${rel}" "${ln}" \
    "${struct}(... ${label}: ...) —— 契约里没有这个字段名。契约改名了？调用点没跟上？"
  VIOLATIONS=$((VIOLATIONS + 1))
done < "${CALLS}"

if [ "${VIOLATIONS}" -gt 0 ]; then
  ci_log ""
  ci_log "✗ Swift 契约参数名门禁未通过：${VIOLATIONS} 处调用点用了契约里不存在的参数名"
  ci_log "  提示：看 ${CONTRACTS[*]} 里对应结构体的字段名（计算属性与 static 成员不是参数）"
  exit 1
fi

ci_log "✓ Swift 契约参数名门禁通过（${CALL_COUNT} 处构造点，全部命中契约字段）"
exit 0
