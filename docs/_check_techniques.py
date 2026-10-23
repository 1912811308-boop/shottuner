# -*- coding: utf-8 -*-
"""校验 TECHNIQUE-v15.md 的字段字数闸门。只看文件里实际写的内容，不做任何推断。"""
import re
import sys
import io

PATH = r"D:/AI学习/Claw/shottuner/docs/TECHNIQUE-v15.md"

RANGES = {
    "title": (8, 16),
    "oneLiner": (4, 20),
    "whenTo": (40, 80),
    "why": (60, 120),
    "check": (20, 70),
}

SCENES = {"NIGHT", "BACKLIT", "PORTRAIT", "LANDSCAPE", "INDOOR", "ACTION", "GENERAL"}
CONDS = {"maxLuma", "minLuma", "minHighlightClip", "minShadowClip", "maxHighlightClip",
         "minSaturation", "minEdgeDensity", "maxEdgeDensity", "minSkinRatio",
         "requiresTele", "requiresManual"}
CATS = {"PORTRAIT", "LANDSCAPE", "NIGHT", "LIGHT", "FOOD", "STREET", "BASIC",
        "ACTION", "MACRO", "INDOOR", "SILHOUETTE", "SELFIE", "CAMERA_MOVE", "PARAMS", "PITFALL"}


def parse(text):
    blocks = []
    cur = None
    mode = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("### "):
            if cur:
                blocks.append(cur)
            cur = {"_h": line[4:].strip(), "steps": [], "mistakes": []}
            mode = None
            continue
        if cur is None:
            continue
        m = re.match(r"^- (\w+):\s*(.*)$", line)
        if m:
            key, val = m.group(1), m.group(2)
            if key in ("steps", "mistakes") and val == "":
                mode = key
                continue
            mode = None
            cur[key] = val
            continue
        if mode == "steps" and re.match(r"^\s+\d+\.\s+", line):
            cur["steps"].append(re.sub(r"^\s+\d+\.\s+", "", line))
            continue
        if mode == "mistakes" and re.match(r"^\s+-\s+", line):
            cur["mistakes"].append(re.sub(r"^\s+-\s+", "", line))
            continue
    if cur:
        blocks.append(cur)
    return blocks


def main():
    with io.open(PATH, encoding="utf-8") as f:
        text = f.read()
    blocks = parse(text)
    print("条目数：%d" % len(blocks))

    problems = []
    seen = set()
    cat_count = {}

    for b in blocks:
        h = b["_h"]
        bid = b.get("id", "")
        if bid != h:
            problems.append("%s: 标题与 id 不一致 (%s)" % (h, bid))
        if bid in seen:
            problems.append("%s: id 重复" % bid)
        seen.add(bid)

        cat = b.get("category", "")
        if cat not in CATS:
            problems.append("%s: category 非法 %r" % (bid, cat))
        cat_count[cat] = cat_count.get(cat, 0) + 1

        for field, (lo, hi) in RANGES.items():
            if field not in b:
                problems.append("%s: 缺字段 %s" % (bid, field))
                continue
            v = b[field]
            # 标题后的人类可读字数标注不计入字段正文，先剥离再量
            m = re.search(r"（(\d+) 字）$", v)
            stated = int(m.group(1)) if m else None
            body = v[: m.start()] if m else v
            n = len(body)
            if stated is not None and stated != n:
                problems.append("%s.%s: 标注 %d 字，实测 %d 字" % (bid, field, stated, n))
            if not (lo <= n <= hi):
                problems.append("%s.%s: %d 字，超出 %d~%d ｜ %s" % (bid, field, n, lo, hi, body))

        steps = b["steps"]
        if not (3 <= len(steps) <= 6):
            problems.append("%s.steps: %d 条，超出 3~6" % (bid, len(steps)))
        if not b["mistakes"]:
            problems.append("%s.mistakes: 空" % bid)
        if not b.get("params", "").strip():
            problems.append("%s.params: 空" % bid)

        trig = b.get("trigger", "")
        if not trig:
            problems.append("%s.trigger: 缺失" % bid)
        else:
            for part in trig.split("；"):
                part = part.strip()
                if part.startswith("scenes="):
                    body = part[len("scenes="):].strip()
                    if body != "emptySet()":
                        for s in body.split(","):
                            s = s.strip()
                            if s and s not in SCENES:
                                problems.append("%s.trigger: 场景值非法 %r" % (bid, s))
                elif part.startswith("weight="):
                    try:
                        w = int(part[len("weight="):].strip())
                    except ValueError:
                        problems.append("%s.trigger: weight 非法 %r" % (bid, part))
                        continue
                    if not (8 <= w <= 32):
                        problems.append("%s.trigger: weight %d 超出 8~32" % (bid, w))
                else:
                    k = part.split("=")[0].strip()
                    if k not in CONDS:
                        problems.append("%s.trigger: 条件名非法 %r" % (bid, k))

    print("分类统计：%s" % ", ".join("%s=%d" % (k, v) for k, v in sorted(cat_count.items())))
    print("合计：%d" % sum(cat_count.values()))

    if problems:
        print("\n发现 %d 个问题：" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("\n全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
