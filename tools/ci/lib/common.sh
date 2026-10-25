#!/usr/bin/env bash
# tools/ci/lib/common.sh —— 门禁脚本共用的小工具集。
#
# ★ 纪律：本文件（以及 tools/ci/ 下所有脚本）**不允许出现任何"禁用特征串"字面量**。
#   特征串一律只放在 tools/ci/patterns/*.txt 里，脚本通过 ci_load_patterns 读取。
#   这是坑 3 的第一道保险；第二道保险是扫描范围排除 tools/ci/**。
#   两道都要做 —— 缺一道，就有人会改脚本/清单来绕过门禁。
#
# 兼容目标：Git Bash（Windows）/ Linux / macOS 均可运行。
#   · 不用 macOS 特有工具（grep -P / sed -i '' / md5 / shasum 一律不用）
#   · 不用任何第三方依赖
set -euo pipefail

# bash 4+ 才能用 mapfile / 关联数组。
# Git Bash（Windows）与 Linux 自带的 bash 都满足；macOS 自带的是 bash 3.2，
# 在那里跑会直接报错退出，而不是静默漏检 —— 宁可明确失败，也不要装作门禁通过。
if [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
  printf '::error::门禁脚本需要 bash 4+（Git Bash / Linux 满足；macOS 自带 bash 3.2，需 brew install bash）\n' >&2
  exit 2
fi

CI_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CI_DIR="$(cd "$CI_LIB_DIR/.." && pwd)"
CI_PATTERNS_DIR="$CI_DIR/patterns"
CI_ALLOWLIST="$CI_PATTERNS_DIR/credential-allowlist.txt"

# ── 路径 ──────────────────────────────────────────────────────────────────────
# ci_repo_root <脚本所在目录> —— 推导仓库根目录（脚本位于 <root>/tools/ci/ 下）。
ci_repo_root() {
  local script_dir="$1"
  (cd "$script_dir/../.." && pwd)
}

# ── 输出 ──────────────────────────────────────────────────────────────────────
ci_log()  { printf '%s\n' "$*"; }
ci_info() { printf '  %s\n' "$*"; }

# ci_error <file> <line> <message> —— 打一条 GitHub Actions 注解 + 人类可读行。
ci_error() {
  local file="$1" line="$2" msg="$3"
  if [ -n "$line" ] && [ "$line" != "-" ]; then
    printf '::error file=%s,line=%s::%s\n' "$file" "$line" "$msg" >&2
  else
    printf '::error file=%s::%s\n' "$file" "$msg" >&2
  fi
}

ci_die() {
  printf '::error::门禁自身出错：%s\n' "$*" >&2
  exit 2
}

# ── 特征串清单 ────────────────────────────────────────────────────────────────
# ci_load_patterns <清单文件名> —— 输出清单里的有效行（去掉 '#' 注释与空行）。
ci_load_patterns() {
  local name="$1" file="$CI_PATTERNS_DIR/$1"
  [ -f "$file" ] || ci_die "缺少特征串清单：$name"
  sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "$file"
}

# ci_is_allowlisted <仓库相对路径> <特征串> —— 该「路径::特征串」是否在豁免清单里。
ci_is_allowlisted() {
  local path="$1" pattern="$2"
  [ -f "$CI_ALLOWLIST" ] || return 1
  grep -Fxq -- "$path::$pattern" "$CI_ALLOWLIST"
}

# ── 文件枚举 ──────────────────────────────────────────────────────────────────
# ci_list_repo_files <仓库根> —— 逐行输出"将被提交进仓库"的文件（相对路径）。
#
# 口径说明：**只扫会被提交进仓库的东西**。
#   · 在 git 仓库里 → git ls-files（已跟踪 + 未跟踪但未被 .gitignore 排除）
#   · 不在 git 仓库里 → find 兜底（跳过 .git 与常见构建产物目录）
# 这样 build/ dist/ work/ gradle.properties 这些"本来就不入库"的目录天然不参与扫描，
# 而新建的、还没 git add 的文件一样会被扫到（本地预检同样有效）。
ci_list_repo_files() {
  local root="$1"
  if git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$root" -c core.quotepath=false ls-files --cached --others --exclude-standard
  else
    (cd "$root" && find . \
      -type d \( -name .git -o -name build -o -name .gradle -o -name .gradle-native \
                -o -name dist -o -name work -o -name node_modules -o -name .idea \) -prune \
      -o -type f -print | sed 's|^\./||')
  fi
}

# ci_list_files_under <根目录> <文件名通配> —— 在某目录树下找文件（相对仓库根输出）。
ci_list_files_under() {
  # ★ 必须拆开写：bash 的 local 同行多变量会先把所有名字声明成 unset 再依次赋值，
  #   所以 dir="$root/$3" 求值时 root 还没被赋值。开了 set -u 就报
  #   "root: unbound variable"，而该报错在管道里被吞掉，表现为「这个目录下一个文件
  #   都没扫到」→ 门禁显示通过。反向验证（test-gates.sh）抓到的就是这个。
  local root="$1"
  local pattern="$2"
  local dir="$root/$3"
  [ -d "$dir" ] || return 0
  (cd "$root" && find "$3" \
    -type d \( -name .git -o -name build -o -name .gradle -o -name DerivedData -o -name Pods \) -prune \
    -o -type f -name "$pattern" -print | sed 's|^\./||')
}
