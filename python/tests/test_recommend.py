"""算法回归测试。

这些断言不是形式化的「跑通即可」，每一条都对应一个真实会出问题的场景：
过曝、死黑、硬件上限、闭环震荡、场景误判。
"""

from __future__ import annotations

import numpy as np
import pytest

from shottuner.recommend import CameraCaps, ManualParams, estimate_kelvin, recommend
from shottuner.scene import SceneType, classify
from shottuner.simulate import run_closed_loop, synthesize_stats
from shottuner.stats import stats_from_rgb

BASE = ManualParams(iso=100, shutter_ns=4_000_000)


def test_dark_scene_requests_more_exposure():
    stats = synthesize_stats(mean_luma=0.12, shadow_clip=0.30)
    rec = recommend(stats, SceneType.GENERAL, CameraCaps(), BASE)
    assert rec.ev_delta > 0.5, "暗画面必须要求增加曝光"


def test_bright_scene_reduces_exposure():
    stats = synthesize_stats(mean_luma=0.85, highlight_clip=0.20)
    rec = recommend(stats, SceneType.GENERAL, CameraCaps(), BASE)
    assert rec.ev_delta < 0, "高光溢出时必须压曝光"


def test_iso_never_exceeds_cap():
    stats = synthesize_stats(mean_luma=0.02, shadow_clip=0.90)
    caps = CameraCaps(iso_min=100, iso_max=1600)
    rec = recommend(stats, SceneType.NIGHT, caps, ManualParams(1600, 33_000_000))
    assert rec.iso <= 1600


def test_shutter_respects_scene_bounds():
    # GENERAL 的可用快门区间是 16ms..33ms
    for luma in (0.05, 0.20, 0.50, 0.80):
        stats = synthesize_stats(mean_luma=luma, highlight_clip=max(0.0, luma - 0.7))
        rec = recommend(stats, SceneType.GENERAL, CameraCaps(), BASE)
        assert 16_000_000 <= rec.shutter_ns <= 33_000_000


def test_action_scene_keeps_fast_shutter():
    stats = synthesize_stats(mean_luma=0.45, edge_density=0.6)
    rec = recommend(stats, SceneType.ACTION, CameraCaps(), ManualParams(200, 1_000_000))
    assert rec.shutter_ns <= 4_000_000, "抓拍场景不能把快门放到 1/250 以下"


def test_night_scene_detected():
    stats = synthesize_stats(mean_luma=0.08, shadow_clip=0.60)
    assert classify(stats).scene == SceneType.NIGHT


def test_portrait_scene_detected():
    """构造一张真实比例的人像：中性背景 + 中央肤色主体。"""
    h, w = 240, 320
    img = np.full((h, w, 3), 120.0, dtype=np.float32)
    yy, xx = np.mgrid[0:h, 0:w]
    face = ((yy - h * 0.45) ** 2) / (60**2) + ((xx - w * 0.5) ** 2) / (45**2) < 1.0
    img[face] = [205, 158, 136]
    stats = stats_from_rgb(img)
    assert 0.05 < stats.skin_ratio < 0.6
    assert classify(stats).scene == SceneType.PORTRAIT


def test_kelvin_compensates_warm_cast():
    warm = synthesize_stats(mean_luma=0.4, cast=0.30)
    cool = synthesize_stats(mean_luma=0.4, cast=-0.30)
    assert estimate_kelvin(warm) < 5500, "偏暖应建议更低色温"
    assert estimate_kelvin(cool) > 5500, "偏冷应建议更高色温"


@pytest.mark.parametrize(
    "scene,gain",
    [
        (SceneType.INDOOR, 0.18),
        (SceneType.NIGHT, 0.02),
        (SceneType.LANDSCAPE, 1.0),
    ],
)
def test_closed_loop_converges(scene, gain):
    recs, lumas = run_closed_loop(scene, gain, frames=16)
    target = recs[-1].target_luma
    tail = lumas[-4:]
    assert all(abs(v - target) <= 0.08 for v in tail), (
        f"{scene.value} 未收敛：目标 {target:.2f}，末帧 {tail}"
    )


def test_iso_quantized_to_real_stops():
    """建议值必须是相机真的能设的档位，不能出现 ISO 72 这种值。"""
    for luma in (0.05, 0.15, 0.30, 0.60):
        stats = synthesize_stats(mean_luma=luma)
        rec = recommend(stats, SceneType.INDOOR, CameraCaps(), ManualParams(400, 16_000_000))
        assert rec.iso in (50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
                           800, 1000, 1250, 1600, 2000, 2500, 3200)


def test_flat_black_image():
    black = np.zeros((120, 160, 3), dtype=np.float32)
    stats = stats_from_rgb(black)
    assert stats.mean_luma == pytest.approx(0.0, abs=1e-6)
    assert stats.highlight_clip == pytest.approx(0.0)


def test_shutter_snaps_to_dial_stops():
    """快门建议必须落在专业模式拨盘存在的档位上，否则用户没法照抄。"""
    from shottuner.recommend import FAST_SHUTTER_DENOMS, SLOW_SHUTTER_STOPS, quantize_shutter

    dial = {int(round(1.0 / d * 1e9)) for d in FAST_SHUTTER_DENOMS}
    dial |= {int(round(s * 1e9)) for s in SLOW_SHUTTER_STOPS}

    for ns in (1_700_000, 4_000_000, 15_873_000, 33_000_000, 63_000_000, 2_400_000_000):
        out = quantize_shutter(ns, 20_000, 30_000_000_000)
        assert out in dial, f"{ns}ns 吸附后得到 {out}ns，不在拨盘档位上"


def test_quantize_shutter_respects_hardware_bounds():
    from shottuner.recommend import quantize_shutter

    out = quantize_shutter(100_000_000, 500_000, 30_000_000_000)
    assert 500_000 <= out <= 30_000_000_000
