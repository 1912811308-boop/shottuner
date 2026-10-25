# tools/ci/lib/strip-comments.awk —— 剥掉源码里的注释，保留字符串字面量。
#
# 为什么需要它（坑 1）：
#   knowledge/PromptScheduler.kt 的 KDoc 里写着 "内部一次都不许调 System.currentTimeMillis" ——
#   这是"禁令的说明"，不是"调用"。纯 grep 会把注释里的词当成违规，红一次，团队就开始忽略它。
#
# 语义：
#   · 行注释 // ...        → 丢弃
#   · 块注释 /* ... */     → 丢弃（可跨行）
#   · 字符串 "..." / '...' → 原样保留（里面的 // 不算注释）
#   · 行数与输入行数严格一致 → grep -n 得到的行号仍指向原文件，可直接定位。
#
# 用法：awk -f strip-comments.awk <file>

BEGIN {
  in_block = 0   # 1 = 正在块注释里
  in_str   = 0   # 1 = 正在字符串里
  qchar    = ""
}

{
  line = $0
  out  = ""
  i    = 1
  n    = length(line)

  while (i <= n) {
    c  = substr(line, i, 1)
    c2 = (i < n) ? substr(line, i + 1, 1) : ""

    if (in_block) {
      if (c == "*" && c2 == "/") { in_block = 0; i += 2 } else { i += 1 }
      continue
    }

    if (in_str) {
      out = out c
      if (c == "\\" && i < n) { out = out substr(line, i + 1, 1); i += 2; continue }
      if (c == qchar) { in_str = 0 }
      i += 1
      continue
    }

    if (c == "/" && c2 == "/") { break }                      # 行注释：丢弃剩余
    if (c == "/" && c2 == "*") { in_block = 1; i += 2; continue }  # 块注释开始
    if (c == "\"" || c == "'") {                             # 字符串开始
      in_str = 1
      qchar  = c
      out    = out c
      i      = i + 1
      continue
    }

    out = out c
    i   = i + 1
  }

  print out
}
