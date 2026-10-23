"""画面统计特征提取。

与 Android 端 FrameAnalyzer.kt 一一对应。差别在于这里直接用 numpy 向量化计算，
不必像 Kotlin 那样手写步长采样循环——这是 Python 最适合的场景：
先把算法在 PC 上写清楚，再翻译成端侧的高性能实现。
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

HIGHLIGHT_THRESHOLD = 250
SHADOW_THRESHOLD = 8


@dataclass(frozen=True)
class FrameStats:
    """一帧画面的统计特征。亮度均归一化到 0..1。"""

    histogram: np.ndarray
    mean_luma: float
    p05: float
    p50: float
    p95: float
    highlight_clip: float
    shadow_clip: float
    mean_r: float
    mean_g: float
    mean_b: float
    saturation: float
    center_luma: float
    edge_density: float
    skin_ratio: float
    width: int
    height: int

    @property
    def dynamic_range(self) -> float:
        """P95 与 P05 之差，衡量画面的层次跨度。"""
        return float(np.clip(self.p95 - self.p05, 0.0, 1.0))

    @property
    def center_bias(self) -> float:
        """中心区域与全图亮度差，负值代表主体比背景暗（典型逆光）。"""
        return self.center_luma - self.mean_luma

    @property
    def color_cast_index(self) -> float:
        """灰世界色偏指数：正=偏暖（红多），负=偏冷（蓝多）。"""
        r = max(self.mean_r, 1e-6)
        b = max(self.mean_b, 1e-6)
        return float(np.log2(r / b))

    @property
    def is_flat(self) -> bool:
        return self.dynamic_range < 0.35

    def as_dict(self) -> dict:
        return {
            "mean_luma": round(self.mean_luma, 4),
            "p05": round(self.p05, 4),
            "p50": round(self.p50, 4),
            "p95": round(self.p95, 4),
            "dynamic_range": round(self.dynamic_range, 4),
            "highlight_clip": round(self.highlight_clip, 4),
            "shadow_clip": round(self.shadow_clip, 4),
            "saturation": round(self.saturation, 4),
            "center_luma": round(self.center_luma, 4),
            "center_bias": round(self.center_bias, 4),
            "edge_density": round(self.edge_density, 4),
            "skin_ratio": round(self.skin_ratio, 4),
            "color_cast_index": round(self.color_cast_index, 4),
            "size": f"{self.width}x{self.height}",
        }


def _luma(rgb: np.ndarray) -> np.ndarray:
    """BT.601 亮度。与端侧 YUV 的 Y 分量定义一致，保证两端可对齐。"""
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def stats_from_rgb(rgb: np.ndarray) -> FrameStats:
    """从 HxWx3 的 0..255 数组计算统计特征。"""
    if rgb.ndim != 3 or rgb.shape[2] < 3:
        raise ValueError("需要 HxWx3 的 RGB 数组")
    rgb = rgb[..., :3].astype(np.float32)
    h, w = rgb.shape[0], rgb.shape[1]

    # 为速度做 4 倍下采样；统计量对下采样不敏感
    step = max(1, min(h, w) // 240)
    rgb_s = rgb[::step, ::step]
    luma = _luma(rgb_s)

    hist, _ = np.histogram(luma, bins=256, range=(0.0, 255.0))
    hist_norm = hist.astype(np.float32) / max(luma.size, 1)

    total = luma.size
    p05, p50, p95 = (float(v) / 255.0 for v in np.percentile(luma, [5, 50, 95]))

    r = rgb_s[..., 0]
    g = rgb_s[..., 1]
    b = rgb_s[..., 2]

    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    sat_mask = mx > 8.0
    saturation = float(np.mean((mx - mn)[sat_mask] / mx[sat_mask])) if np.any(sat_mask) else 0.0

    # YCbCr 肤色判据，与端侧使用同一组边界
    cb = -0.168736 * r - 0.331264 * g + 0.5 * b
    cr = 0.5 * r - 0.418688 * g - 0.081312 * b
    skin_mask = (cb > -27) & (cb < 7) & (cr > 5) & (cr < 45) & (luma > 40)
    skin_ratio = float(np.mean(skin_mask))

    # 边缘密度：Sobel 的简化版（水平 + 垂直一阶差分）
    if luma.shape[0] > 2 and luma.shape[1] > 2:
        dx = np.abs(np.diff(luma, axis=1)).mean()
        dy = np.abs(np.diff(luma, axis=0)).mean()
        edge = float(np.clip((dx + dy) / 2.0 / 40.0, 0.0, 1.0))
    else:
        edge = 0.0

    y0, y1 = int(h * 0.28 / step), int(h * 0.72 / step)
    x0, x1 = int(w * 0.28 / step), int(w * 0.72 / step)
    center = luma[y0:y1, x0:x1]
    center_luma = float(center.mean() / 255.0) if center.size else float(luma.mean() / 255.0)

    return FrameStats(
        histogram=hist_norm,
        mean_luma=float(luma.mean() / 255.0),
        p05=p05,
        p50=p50,
        p95=p95,
        highlight_clip=float(np.sum(luma >= HIGHLIGHT_THRESHOLD) / total),
        shadow_clip=float(np.sum(luma <= SHADOW_THRESHOLD) / total),
        mean_r=float(r.mean() / 255.0),
        mean_g=float(g.mean() / 255.0),
        mean_b=float(b.mean() / 255.0),
        saturation=saturation,
        center_luma=center_luma,
        edge_density=edge,
        skin_ratio=skin_ratio,
        width=w,
        height=h,
    )


def stats_from_image(path: str) -> FrameStats:
    """从图片文件读取并计算统计特征。依赖 Pillow。"""
    from PIL import Image

    with Image.open(path) as img:
        rgb = np.asarray(img.convert("RGB"), dtype=np.float32)
    return stats_from_rgb(rgb)
