"""构图诊断的参考实现。

这不是 Kotlin 版的翻译练习，而是刻意的「同一算法两份实现」：
手机端负责实时，PC 端负责回归。任何一次调参，先在 Python 里跑通
语义正确的样张，再把常数抄回 Kotlin，避免只能在真机上盲试。

坐标系与手机端一致：原点在左上角，y 向下，倾角为正表示画面右侧偏低。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

GRID_COLS = 160
GRID_ROWS = 120
TILT_TOLERANCE = 1.8
MIN_COHERENCE = 0.52
THIRD = 1.0 / 3.0


@dataclass
class Composition:
    score: int
    tilt_deg: float
    horizon_detected: bool
    horizon_y: float
    subject_x: float
    subject_y: float
    subject_confidence: float
    subject_size: float
    target_x: float
    target_y: float
    suggest_move: bool
    tips: list[str] = field(default_factory=list)

    @property
    def primary_tip(self) -> str:
        return self.tips[0] if self.tips else "构图没问题，可以按快门了"


def _grid(luma: np.ndarray, cols: int, rows: int) -> np.ndarray:
    """块均值降采样。手机端用步长抽样，这里用均值，二者在低频画面上等价。"""
    h, w = luma.shape
    ys = np.linspace(0, h, rows + 1).astype(int)
    xs = np.linspace(0, w, cols + 1).astype(int)
    out = np.zeros((rows, cols), dtype=np.float32)
    for r in range(rows):
        y0, y1 = ys[r], max(ys[r] + 1, ys[r + 1])
        band = luma[y0:y1]
        for c in range(cols):
            x0, x1 = xs[c], max(xs[c] + 1, xs[c + 1])
            out[r, c] = band[:, x0:x1].mean()
    return out


def _resize_mask(mask: np.ndarray, cols: int, rows: int) -> np.ndarray:
    h, w = mask.shape
    ys = np.linspace(0, h, rows + 1).astype(int)
    xs = np.linspace(0, w, cols + 1).astype(int)
    out = np.zeros((rows, cols), dtype=np.float32)
    for r in range(rows):
        y0, y1 = ys[r], max(ys[r] + 1, ys[r + 1])
        for c in range(cols):
            x0, x1 = xs[c], max(xs[c] + 1, xs[c + 1])
            out[r, c] = mask[y0:y1, x0:x1].mean()
    return out


def analyze(luma: np.ndarray, skin: np.ndarray | None = None) -> Composition:
    luma = np.asarray(luma, dtype=np.float32)
    if luma.ndim != 2:
        raise ValueError("luma 必须是二维灰度图")
    if luma.shape[0] < GRID_ROWS or luma.shape[1] < GRID_COLS:
        raise ValueError("画面太小，无法做构图分析")

    g = _grid(luma, GRID_COLS, GRID_ROWS)
    skin_grid = None if skin is None else _resize_mask(skin.astype(np.float32), GRID_COLS, GRID_ROWS)

    rows, cols = g.shape

    # 1. 中心差分梯度（不含边界）
    gx = np.zeros_like(g)
    gy = np.zeros_like(g)
    gx[1:-1, 1:-1] = (g[1:-1, 2:] - g[1:-1, :-2]) * 0.5
    gy[1:-1, 1:-1] = (g[2:, 1:-1] - g[:-2, 1:-1]) * 0.5

    # 2. 显著性权重
    mag = np.abs(gx) + np.abs(gy)
    weight = mag + 0.35 * np.abs(g - float(luma.mean()))
    if skin_grid is not None:
        weight = weight + skin_grid * 110.0

    peak = float(weight.max())
    mean_w = float(weight.mean())
    if peak <= 1e-3:
        raise ValueError("画面完全无特征")

    confidence = float(np.clip((peak - mean_w) / (mean_w + 6.0), 0.0, 1.0))
    thr = mean_w + (peak - mean_w) * 0.55
    mask = weight >= thr
    if not mask.any():
        raise ValueError("显著性掩膜为空")

    wy = weight * mask
    total = float(wy.sum())
    ry, rx = np.mgrid[0:rows, 0:cols]
    subject_x = float(((rx + 0.5) * wy).sum() / total / cols)
    subject_y = float(((ry + 0.5) * wy).sum() / total / rows)
    subject_size = float(mask.mean())

    # 主体外框。用于判断「贴边」与「留白」，比重心可靠：
    # 重心会被画面外的部分拉回中间，外框不会。
    cols_idx = np.where(mask.any(axis=0))[0]
    rows_idx = np.where(mask.any(axis=1))[0]
    box_x0 = float(cols_idx[0]) / cols
    box_x1 = float(cols_idx[-1] + 1) / cols
    box_y0 = float(rows_idx[0]) / rows
    box_y1 = float(rows_idx[-1] + 1) / rows

    # 长条形的显著性区域（地平线、栏杆、书架的竖线）不是「主体」，
    # 它是结构。不排除掉的话，任何一张有地平线的照片都会被建议「把主体挪到交点」。
    line_like = (box_x1 - box_x0 > 0.75 and box_y1 - box_y0 < 0.22) or (
        box_y1 - box_y0 > 0.75 and box_x1 - box_x0 < 0.22
    )

    # 显著性散布程度。噪声和满屏纹理会让「最亮的那些块」撒满整个画面，
    # 此时重心和交点建议毫无意义——先把话说成「你还没有主体」，而不是乱指挥。
    mx = subject_x * cols
    my = subject_y * rows
    sx = math.sqrt(float((wy * ((rx + 0.5) - mx) ** 2).sum() / total) / (cols ** 2))
    sy = math.sqrt(float((wy * ((ry + 0.5) - my) ** 2).sum() / total) / (rows ** 2))
    diffuse = sx > 0.20 or sy > 0.20

    if line_like or diffuse:
        confidence = 0.0

    # 3. 结构张量求主方向：只用更接近水平的边缘
    inner = np.zeros_like(g, dtype=bool)
    inner[1:-1, 1:-1] = True
    strong = inner & (mag > 3.0) & (np.abs(gy) >= np.abs(gx) * 0.9)
    jxx = float((gx[strong] ** 2).sum())
    jxy = float((gx[strong] * gy[strong]).sum())
    jyy = float((gy[strong] ** 2).sum())

    tilt = 0.0
    horizon_detected = False
    horizon_y = 0.5
    trace = jxx + jyy
    if trace > 1e-3:
        coherence = math.hypot(jxx - jyy, 2 * jxy) / trace
        angle = math.degrees(0.5 * math.atan2(2 * jxy, jxx - jyy)) + 90.0
        if angle > 90.0:
            angle -= 180.0
        if angle <= -90.0:
            angle += 180.0
        if coherence > MIN_COHERENCE and abs(angle) <= 30.0:
            tilt = angle
            horizon_detected = True
            wsum = float(mag[strong].sum())
            hy = float((((ry[strong] + 0.5) * mag[strong]).sum()) / wsum) / rows
            horizon_y = float(np.clip(hy, 0.0, 1.0))

    target_x = THIRD if subject_x < 0.5 else 2 * THIRD
    target_y = THIRD if subject_y < 0.5 else 2 * THIRD
    dist = math.hypot(subject_x - target_x, subject_y - target_y)

    tips: list[str] = []
    score = 100

    near_edge = box_x0 < 0.05 or box_x1 > 0.95 or box_y0 < 0.05 or box_y1 > 0.95
    if confidence > 0.30 and near_edge:
        tips.append("主体贴到画面边缘了，退半步或把镜头往主体方向挪一点")
        score -= 15

    if horizon_detected and abs(tilt) > TILT_TOLERANCE:
        direction = "顺时针" if tilt > 0 else "逆时针"
        tips.append(f"画面有 {abs(tilt):.0f}° 的倾斜，{direction}转一点手机")
        score -= int(min(20.0, 4.0 * abs(tilt)))

    if horizon_detected and 0.44 < horizon_y < 0.56:
        tips.append("地平线横在正中间会显得呆板，压低或抬高取景让它落在三分线上")
        score -= 12

    if confidence > 0.30:
        if dist > 0.10:
            where = ("左" if target_x < 0.5 else "右") + ("上" if target_y < 0.5 else "下")
            tips.append(f"把主体放到{where}角的交点上")
            score -= int(min(18.0, 110.0 * dist))
        if subject_size > 0.60:
            tips.append("主体把画面塞满了，退一步留点环境")
            score -= 8
    elif float(mag.mean()) < 4.0:
        tips.append("画面比较空，走近一点或等一个主体进入画面")
        score -= 10
    else:
        tips.append("画面元素比较散，先选一个主体（人、物、建筑），再把它放到交点上")
        score -= 8

    if not tips:
        tips.append("构图稳，可以按快门")

    return Composition(
        score=int(np.clip(score, 0, 100)),
        tilt_deg=float(tilt),
        horizon_detected=horizon_detected,
        horizon_y=float(horizon_y),
        subject_x=subject_x,
        subject_y=subject_y,
        subject_confidence=confidence,
        subject_size=subject_size,
        target_x=target_x,
        target_y=target_y,
        suggest_move=bool(confidence > 0.30 and dist > 0.10),
        tips=tips[:2],
    )
