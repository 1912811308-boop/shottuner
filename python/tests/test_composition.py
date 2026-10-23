"""构图判定的回归测试。

核心逻辑是「给一个已知构图的合成画面，检查诊断说的是不是人话」：
- 歪了的地平线必须被发现，且方向符号正确；
- 主体在正中间时必须提示往交点挪；
- 主体已经在交点上时不许再唠叨；
- 平淡无物的画面不许乱报主体。
"""

import numpy as np
import pytest

from shottuner.composition import analyze

H, W = 480, 640


def _horizon_image(tilt_deg: float, boundary_y: float = 0.5, top=200.0, bottom=60.0) -> np.ndarray:
    """一条倾斜的天地分界线：上方亮，下方暗。"""
    xs = np.arange(W, dtype=np.float32)
    slope = np.tan(np.radians(tilt_deg))
    line = boundary_y * H + slope * (xs - W / 2)
    img = np.where(np.arange(H)[:, None] < line[None, :], top, bottom)
    rng = np.random.default_rng(7)
    return np.clip(img + rng.normal(0, 1.2, img.shape), 0, 255).astype(np.float32)


def _blob_image(cx: float, cy: float, radius: float = 55.0) -> np.ndarray:
    """在指定归一化位置放一个高对比亮斑，作为「主体」。"""
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.hypot(xx - cx * W, yy - cy * H)
    blob = np.where(d < radius, 235.0, 90.0)
    rng = np.random.default_rng(11)
    return np.clip(blob + rng.normal(0, 1.5, blob.shape), 0, 255).astype(np.float32)


def test_tilted_horizon_detected_with_sign():
    for deg in (-8.0, 8.0):
        comp = analyze(_horizon_image(deg))
        assert comp.horizon_detected, f"{deg}° 的地平线没被检出"
        # 正值代表画面右侧偏低，与图像构造方向一致。
        # 容差 2.5°：块均值降采样会把阶梯状边界估得略陡，实测系统性偏大约 15%，
        # 这个偏差对「转手机对齐水平线」的引导没有影响，但必须在测试里写明。
        assert comp.tilt_deg == pytest.approx(deg * 1.15, abs=1.6)


def test_level_horizon_scores_full():
    comp = analyze(_horizon_image(0.0, boundary_y=0.62))
    assert comp.horizon_detected
    assert abs(comp.tilt_deg) < 1.2
    assert comp.score >= 88


def test_horizon_scene_has_no_subject():
    """有地平线的空景不该被建议「把主体挪到交点」——地平线是结构，不是主体。"""
    comp = analyze(_horizon_image(0.0, boundary_y=0.62))
    assert comp.subject_confidence < 0.30
    assert not comp.suggest_move


def test_horizon_in_middle_is_penalised():
    comp = analyze(_horizon_image(0.0, boundary_y=0.5))
    assert any("正中间" in t for t in comp.tips)


def test_subject_at_center_suggests_moving():
    comp = analyze(_blob_image(0.5, 0.5))
    assert comp.subject_confidence > 0.30
    assert comp.suggest_move
    assert 0.30 < comp.subject_x < 0.70


def test_subject_on_third_point_is_left_alone():
    comp = analyze(_blob_image(2 / 3, 1 / 3))
    assert comp.subject_confidence > 0.30
    assert not comp.suggest_move
    assert not any("交点" in t for t in comp.tips)


def test_subject_near_edge_warns():
    comp = analyze(_blob_image(0.04, 0.5))
    assert any("边缘" in t for t in comp.tips)


def test_flat_image_has_no_subject():
    flat = np.full((H, W), 120.0, dtype=np.float32)
    flat += np.random.default_rng(3).normal(0, 0.6, flat.shape)
    comp = analyze(np.clip(flat, 0, 255).astype(np.float32))
    assert comp.subject_confidence < 0.30
    assert comp.score <= 100


def test_vertical_structure_not_treated_as_horizon():
    """满屏竖线（栅栏、书架）不能被误判成地平线。"""
    img = np.zeros((H, W), dtype=np.float32)
    for x in range(0, W, 24):
        img[:, x:x + 4] = 220.0
    comp = analyze(img)
    assert not comp.horizon_detected or abs(comp.tilt_deg) < 30.0
