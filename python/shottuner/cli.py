"""命令行入口：分析一张照片，输出统计与调参建议。

用法：
    python -m shottuner.cli photos/demo.jpg
    python -m shottuner.cli photos/demo.jpg --json
    python -m shottuner.cli photos/demo.jpg --scene NIGHT   # 手动指定场景
"""

from __future__ import annotations

import argparse
import json
import sys

from .recommend import CameraCaps, recommend
from .scene import SceneType, classify
from .stats import stats_from_image


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="shottuner", description="基于画面统计的拍照调参建议")
    p.add_argument("image", help="图片路径")
    p.add_argument("--json", action="store_true", help="以 JSON 输出")
    p.add_argument(
        "--scene",
        choices=[s.name for s in SceneType],
        help="强制指定场景，跳过自动识别",
    )
    p.add_argument("--iso-max", type=int, default=6400, help="设备 ISO 上限")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        stats = stats_from_image(args.image)
    except FileNotFoundError:
        print(f"找不到文件：{args.image}", file=sys.stderr)
        return 2
    except Exception as exc:  # noqa: BLE001 - CLI 需要把任何解码错误都报给用户
        print(f"读取图片失败：{exc}", file=sys.stderr)
        return 2

    guess = classify(stats)
    scene = SceneType[args.scene] if args.scene else guess.scene
    confidence = 1.0 if args.scene else guess.confidence

    rec = recommend(stats, scene, CameraCaps(iso_max=args.iso_max), None, confidence)

    if args.json:
        print(json.dumps({"stats": stats.as_dict(), "recommendation": rec.as_dict()},
                         ensure_ascii=False, indent=2))
        return 0

    print(f"文件      {args.image}  ({stats.width}x{stats.height})")
    print(f"平均亮度  {stats.mean_luma:.3f}   P05 {stats.p05:.2f} / P95 {stats.p95:.2f}"
          f"   动态范围 {stats.dynamic_range:.2f}")
    print(f"高光截断  {stats.highlight_clip:.1%}      死黑 {stats.shadow_clip:.1%}"
          f"      饱和 {stats.saturation:.2f}")
    print(f"场景      {scene.value}  (置信度 {confidence:.0%})")
    print("-" * 46)
    print(f"建议      ISO {rec.iso}   快门 {rec.shutter_label}   色温 {rec.kelvin}K"
          f"   曝光补偿 {rec.ev_delta:+.1f}EV")
    print(f"判断      {rec.tip}")
    print(f"补充      {rec.detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
