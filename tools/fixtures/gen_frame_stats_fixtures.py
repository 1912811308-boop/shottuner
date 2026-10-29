#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成跨端对齐用的固定测试图集 → docs/fixtures/frame-stats/

# 这是什么

一批**无损 PNG**，Android 与 iOS 读**同一批图**、各自算出 `FrameStats`，
用来验证两端**算法实现一致**（T13 验收①）与**阈值基线**（T22 CAL-2）。

# 为什么是「合成的」而不是真拍照片

- **可控**：每张图的亮度分布是**已知且写死在脚本里**的。
  合成图能精确控制「过曝区占画面 30%」这种量级；真拍照片做不到。
- **可再生**：脚本在 git 里，任何人任何时候都能重跑得到**逐字节相同**的图。
  真拍照片一旦重新拍就不是同一批了，跨端比对立刻失去意义。
- **小**：单张 < 200KB，图集整体 < 1.5MB，进 git 不心疼。
- **无隐私**：不含任何真人物。

# ★ 关键约束：iOS 与 Android 必须读**同一份字节**

所以格式固定为 **PNG（无损）**。JPEG 有损解码会因平台实现不同产生差异，
那属于「解码器差异」而不是「算法差异」，会把问题搅浑 —— 架构文档里
T12 的定位顺序第一条就是这个：先用无损 PNG 重跑，若差异消失则是 JPEG 解码差异（可接受）。

# 用法

    python tools/fixtures/gen_frame_stats_fixtures.py

生成后请核对 `docs/fixtures/frame-stats/README.md` 里的期望表是否仍然成立。
"""

import os
import struct
import zlib

# ── 尺寸 ────────────────────────────────────────────────────────────────────
# 320x240：够 FrameStatsCore 的最小尺寸要求（>= 32），又足够小。
# 再大只会让图集变大，而所有场景的统计特征在小图上已经稳定。
WIDTH = 320
HEIGHT = 240

OUT_DIR = os.path.join("docs", "fixtures", "frame-stats")


# ── 纯标准库 PNG 编码器 ────────────────────────────────────────────────────
def write_png(path, width, height, rows):
    """写 8 位 RGB 的 PNG。rows 是每行的 bytes 列表，长度 = width*3。"""
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def fill(r, g, b):
    return bytes((r, g, b))


def solid(color):
    """整幅同色。"""
    row = fill(*color) * WIDTH
    return [row] * HEIGHT


def vgradient(top, bottom):
    """上下渐变（用于「无人脸 + 低纹理」）。"""
    rows = []
    for y in range(HEIGHT):
        t = y / max(1, HEIGHT - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        rows.append(fill(*c) * WIDTH)
    return rows


def center_blob(bg, blob, cx, cy, rx, ry):
    """在背景上画一个椭圆色块（用来做「有人脸」的肤色区域）。"""
    rows = []
    for y in range(HEIGHT):
        row = bytearray(fill(*bg) * WIDTH)
        for x in range(WIDTH):
            dx = (x - cx) / float(rx)
            dy = (y - cy) / float(ry)
            if dx * dx + dy * dy <= 1.0:
                row[x * 3:x * 3 + 3] = bytes(blob)
        rows.append(bytes(row))
    return rows


def checker(a, b, cell):
    """高频棋盘（高纹理）。cell 越小纹理越密 → 拉普拉斯方差越大。"""
    rows = []
    for y in range(HEIGHT):
        row = bytearray(WIDTH * 3)
        for x in range(WIDTH):
            c = a if ((x // cell) + (y // cell)) % 2 == 0 else b
            row[x * 3:x * 3 + 3] = bytes(c)
        rows.append(bytes(row))
    return rows


def blur(rows, radius=4):
    """box blur —— 用来做「低纹理但不是纯色」的图。"""
    import collections

    out = []
    for y in range(HEIGHT):
        row = bytearray(WIDTH * 3)
        for x in range(WIDTH):
            acc = [0, 0, 0]
            n = 0
            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    sx = min(WIDTH - 1, max(0, x + dx))
                    sy = min(HEIGHT - 1, max(0, y + dy))
                    p = rows[sy][sx * 3:sx * 3 + 3]
                    for i in range(3):
                        acc[i] += p[i]
                    n += 1
            row[x * 3:x * 3 + 3] = bytes(a // n for a in acc)
        out.append(bytes(row))
    return out


# ── 图集定义 ────────────────────────────────────────────────────────────────
# name → (生成函数, 说明)
FIXTURES = [
    ("01-bright", lambda: solid((222, 222, 222)),
     "均匀亮场景。全画面高亮度但不溢出（无 clipHigh），用于测曝光维度的高档区间。"),
    ("02-dark", lambda: solid((28, 28, 28)),
     "均匀暗场景。低亮度但不欠曝（无 clipLow），用于测曝光维度的低档区间。"),
    ("03-overexposed", lambda: center_blob((120, 120, 120), (255, 255, 255), 160, 120, 120, 90),
     "过曝。中心一大片纯白（占画面约 44%），触发 clipHigh 统计。"),
    ("04-underexposed", lambda: center_blob((90, 90, 90), (0, 0, 0), 160, 120, 120, 90),
     "欠曝。中心一大片纯黑（占画面约 44%），触发 clipLow 统计。"),
    ("05-with-face", lambda: center_blob((70, 90, 120), (226, 178, 140), 160, 110, 62, 82),
     "有人脸。中心肤色椭圆（skinRatio 显著），背景偏冷 —— 用于测肤色占比与场景分类的人像分支。"),
    ("06-no-face", lambda: vgradient((60, 110, 90), (30, 70, 60)),
     "无人脸。冷绿调渐变，**不含任何肤色像素** —— 与 05 对照，确保 skinRatio 分支真的被区分开。"),
    ("07-high-texture", lambda: checker((20, 20, 20), (235, 235, 235), 2),
     "高纹理。2 像素棋盘 → 拉普拉斯方差极大，用于测清晰维度的上限区间。"),
    ("08-low-texture", lambda: blur(vgradient((90, 90, 100), (130, 130, 140)), 6),
     "低纹理。模糊后的平滑渐变 → 拉普拉斯方差很小，用于测清晰维度的下限区间。"),
]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    written = []
    for name, gen, desc in FIXTURES:
        rows = gen()
        assert len(rows) == HEIGHT, name
        assert all(len(r) == WIDTH * 3 for r in rows), name
        path = os.path.join(OUT_DIR, name + ".png")
        write_png(path, WIDTH, HEIGHT, rows)
        size = os.path.getsize(path)
        assert size < 200 * 1024, "%s 超过 200KB: %d" % (name, size)
        written.append((name, size, desc))
        print("  %-20s %6d bytes" % (name + ".png", size))

    readme = os.path.join(OUT_DIR, "README.md")
    with open(readme, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 固定测试图集（跨端对齐用）\n\n")
        f.write("> 由 `tools/fixtures/gen_frame_stats_fixtures.py` 生成，"
                "**请不要手工改这些 PNG** —— 要改就改脚本并重新生成。\n\n")
        f.write("## 为什么是合成的\n\n")
        f.write("- **可控**：每张图的亮度分布写死在脚本里（真拍做不到「过曝区恰好占 44%」）\n")
        f.write("- **可再生**：任何人重跑脚本都得到逐字节相同的图 —— 真拍照片重拍就不是同一批了，"
                "跨端比对立刻失去意义\n")
        f.write("- **小**：单张 < 200KB，整体 < 1.5MB\n")
        f.write("- **无隐私**：不含任何真人物\n\n")
        f.write("## ★ 为什么是 PNG 而不是 JPEG\n\n")
        f.write("JPEG 有损解码会因平台实现不同产生差异，那是**解码器差异**而不是**算法差异**，"
                "会把问题搅浑。架构文档 T12 的定位顺序第一条就是这个："
                "先用无损 PNG 重跑，若差异消失则是 JPEG 解码差异（可接受，调阈值即可）。\n\n")
        f.write("## 尺寸与格式\n\n")
        f.write("- %dx%d，8 位 RGB，无交错，无 alpha\n" % (WIDTH, HEIGHT))
        f.write("- 读法：Android 侧 `analysis/PngDecoder.kt`；iOS 侧用 CoreGraphics\n\n")
        f.write("## 每张图要触发什么\n\n")
        f.write("| 文件 | 预期特征 | 为什么需要它 |\n|---|---|---|\n")
        for name, size, desc in written:
            f.write("| `%s.png` | %s | %s |\n" % (name, desc.split("。")[0], desc))
        f.write("\n## 用法约定\n\n")
        f.write("- **T13 验收①**：两端读**同一批图** → 各自算 `FrameStats` → 四维分**逐分相同**"
                "（不是「接近」，是相同 —— 因为算法是同一份 common 代码）\n")
        f.write("- **T22 CAL-2**：用真机拍摄的样张另建一套（**不入 git**，涉及隐私），"
                "这里这批只管「实现一致」，不管「基线一致」\n")
    print("  README.md 已生成")
    print("共 %d 张图" % len(written))


if __name__ == "__main__":
    main()
