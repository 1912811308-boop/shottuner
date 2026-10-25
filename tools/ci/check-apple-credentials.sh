#!/usr/bin/env bash
# tools/ci/check-apple-credentials.sh —— G1-d 凭据门禁。
#
# 拦什么：Apple 账号凭据、签名身份、私钥文件。
#
# ★ 为什么这道门禁必须存在：
#   README 里写"Apple ID 不进 secrets"**只是承诺**。凭据一旦入库，CI 才有能力用它。
#   承诺会被人绕过，机器不会。所以这条是**机器保障**，不是文档条款。
#
# ★ 三档特征串的分工：
#   apple-secrets.txt          = "提到就算"（可按 路径::特征串 精确豁免，因为文档要引用它们）
#   apple-secrets-strict.txt   = "真私钥长什么样"（**不允许任何豁免**，命中即红）
#   apple-signing-empty.txt    = "必须恒为空的构建变量"（CI 一律不签名）
#
# ★ 扫描范围：**全仓库所有文本文件**（含 .yml / .md / .plist / .entitlements）——
#   凭据可能藏在任何地方。但排除 tools/ci/**（坑 3：特征串清单本身含这些字面量）。
#
# 用法： tools/ci/check-apple-credentials.sh [仓库根目录]
#
# 退出码： 0 = 通过；1 = 有违规；2 = 门禁自身出错。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

ROOT="${1:-$(ci_repo_root "$SCRIPT_DIR")}"
[ -d "$ROOT" ] || ci_die "仓库根目录不存在：$ROOT"

ci_log "▶ G1-d Apple 凭据门禁（根目录：$ROOT）"

mapfile -t PATTERNS < <(ci_load_patterns "apple-secrets.txt")
mapfile -t STRICT   < <(ci_load_patterns "apple-secrets-strict.txt")
mapfile -t EMPTYVAR < <(ci_load_patterns "apple-signing-empty.txt")
[ "${#PATTERNS[@]}" -gt 0 ] || ci_die "清单 apple-secrets.txt 为空"
[ "${#STRICT[@]}"   -gt 0 ] || ci_die "清单 apple-secrets-strict.txt 为空"

# 只扫文本文件：二进制与图片跳过（grep -I 也会做，但先过滤掉明显非文本更快）。
mapfile -t FILES < <(
  ci_list_repo_files "$ROOT" \
    | grep -v '^tools/ci/' \
    | grep -vE '\.(apk|ipa|aab|jar|png|jpg|jpeg|gif|webp|ico|so|dylib|zip|keystore|jks|p12|der|ttf|otf|woff2?|pdf|mp4|mov)$'
)

VIOLATIONS=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for rel in "${FILES[@]}"; do
  abs="$ROOT/$rel"
  [ -f "$abs" ] || continue

  # ── 第一档：可精确豁免 ──────────────────────────────────────────────────────
  for pat in "${PATTERNS[@]}"; do
    grep -Iq . "$abs" 2>/dev/null || continue          # 非文本，跳过
    grep -Fq -- "$pat" "$abs" || continue
    if ci_is_allowlisted "$rel" "$pat"; then
      ci_info "豁免：$rel（$pat）"
      continue
    fi
    ci_error "$rel" "-" "仓库里出现被禁凭据特征：$pat —— 凭据必须走 CI secrets，本机不外传"
    VIOLATIONS=$((VIOLATIONS + 1))
  done

  # ── 第二档：真私钥，不允许任何豁免 ─────────────────────────────────────────
  for pat in "${STRICT[@]}"; do
    grep -Iq . "$abs" 2>/dev/null || continue
    grep -Fq -- "$pat" "$abs" || continue
    ci_error "$rel" "-" "仓库里出现真实私钥/证书内容：$pat —— 没有任何豁免空间"
    VIOLATIONS=$((VIOLATIONS + 1))
  done
done

# ── 私钥文件（.p8 等）单独查：光扫内容不够，文件名本身就是信号 ──────────────
mapfile -t KEYFILES < <(
  ci_list_repo_files "$ROOT" | grep -v '^tools/ci/' | grep -E '\.(p8|p12|pem|key)$' || true
)
for rel in "${KEYFILES[@]}"; do
  ci_error "$rel" "-" "仓库里出现密钥/证书文件 —— 私钥绝不入库"
  VIOLATIONS=$((VIOLATIONS + 1))
done

# ── 第三档：必须恒为空的构建变量 ────────────────────────────────────────────
# 只扫"会被提交进仓库"的文件（build/ dist/ work/ 天然不参与）。
for rel in "${FILES[@]}"; do
  abs="$ROOT/$rel"
  [ -f "$abs" ] || continue
  for var in "${EMPTYVAR[@]}"; do
    # ★ 必须锚定行首，否则会误伤文档。
    #   锚定前，`| \`CODE_SIGN_IDENTITY\` | 构建配置层锁死 |` 这种 Markdown 表格行
    #   会被当成"赋值"（因为后面跟着 `|` 再跟着别的字面量），实测在
    #   docs/IOS-ARCH-EXEC.md 上一次误报 7 处 —— **第一次跑就红的门禁等于没有门禁**。
    #   锚定后仍然能拦住的写法：YAML 映射、YAML 序列项、shell export。
    #   而文档里的"提及"（表格、代码片段、行内引用）不再误伤。
    while IFS= read -r raw; do
      [ -n "$raw" ] || continue
      val="$(printf '%s' "$raw" | sed -E 's/.*[:=][[:space:]]*//' | tr -d '"'"'" | tr -d '[:space:]')"
      [ -n "$val" ] || continue                       # 空值 → 通过
      ci_error "$rel" "-" "构建变量 $var 必须恒为空（CI 一律不签名），但发现非空赋值：$val"
      VIOLATIONS=$((VIOLATIONS + 1))
    done < <(grep -InE -- "^[[:space:]]*(-[[:space:]]+)?(export[[:space:]]+)?${var}[[:space:]]*[:=]" "$abs" 2>/dev/null || true)
  done
done

if [ "$VIOLATIONS" -gt 0 ]; then
  ci_log ""
  ci_log "✗ G1-d Apple 凭据门禁未通过：$VIOLATIONS 处违规"
  exit 1
fi

ci_log "✓ G1-d Apple 凭据门禁通过（扫了 ${#FILES[@]} 个文件）"
exit 0
