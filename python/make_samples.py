"""生成合成样张，用于在没有真实照片时验证整条分析链路。

不是美术素材，只是四类典型光照分布的数学近似：
高光/暗部比例、色偏、纹理密度各自对齐真实场景的量级。
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

W, H = 960, 720
OUT = Path(__file__).resolve().parent / "samples"


def _noise(rgb: np.ndarray, amount: float = 6.0, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return rgb + rng.normal(0.0, amount, rgb.shape)


def _vignette(rgb: np.ndarray, strength: float = 0.25) -> np.ndarray:
    yy, xx = np.mgrid[0:H, 0:W]
    cy, cx = H / 2, W / 2
    r = np.sqrt(((yy - cy) / cy) ** 2 + ((xx - cx) / cx) ** 2)
    mask = np.clip(1.0 - strength * r**2, 0.0, 1.0)[..., None]
    return rgb * mask


def outdoor() -> np.ndarray:
    """晴天风景：上半天空、下半草地，动态范围大，饱和度高。"""
    y = np.linspace(0, 1, H)[:, None, None]
    sky = np.concatenate([110 + 80 * y, 160 + 70 * y, 225 + 25 * y], axis=2)
    sky = np.broadcast_to(sky, (H, W, 3)).copy()

    gy = np.linspace(0, 1, H)[:, None, None]
    ground = np.concatenate([60 + 30 * gy, 110 + 45 * gy, 55 + 20 * gy], axis=2)
    ground = np.broadcast_to(ground, (H, W, 3)).copy()

    # 草地纹理，制造边缘密度
    rng = np.random.default_rng(3)
    texture = rng.normal(0, 18, (H // 4, W // 4, 1))
    texture = np.kron(texture, np.ones((4, 4, 3)))
    ground = ground + texture

    horizon = int(H * 0.45)
    img = np.concatenate([sky[:horizon], ground[horizon:]], axis=0)
    return _noise(np.clip(img, 0, 255), 4.0, 1)


def night() -> np.ndarray:
    """夜晚街景：整体极暗，少量高光点（路灯）。"""
    img = np.full((H, W, 3), [10, 13, 22], dtype=float)
    rng = np.random.default_rng(7)

    for _ in range(14):
        cx, cy = rng.integers(30, W - 30), rng.integers(30, H - 30)
        rad = rng.integers(6, 18)
        yy, xx = np.mgrid[0:H, 0:W]
        d = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
        glow = np.clip(1.0 - d / (rad * 3), 0, 1) ** 2
        img[..., 0] += glow * 235
        img[..., 1] += glow * 205
        img[..., 2] += glow * 140

    # 远处建筑轮廓，制造微弱明暗层次
    for i in range(6):
        x0 = i * (W // 6)
        top = rng.integers(int(H * 0.3), int(H * 0.6))
        img[top: H - 40, x0: x0 + W // 7] *= 0.55

    return _noise(np.clip(img, 0, 255), 3.0, 2)


def backlit() -> np.ndarray:
    """逆光窗边：大面积极亮背景，中间主体严重欠曝。"""
    img = np.zeros((H, W, 3))
    yy, xx = np.mgrid[0:H, 0:W]
    window = ((xx > W * 0.18) & (xx < W * 0.82) & (yy > H * 0.10) & (yy < H * 0.72))
    img[window] = [246, 244, 238]
    img[~window] = [38, 34, 32]

    # 中央人物剪影
    cy, cx = int(H * 0.55), int(W * 0.5)
    head = ((yy - cy + 90) ** 2 + (xx - cx) ** 2) < 70**2
    body = (np.abs(xx - cx) < 85) & (yy > cy - 30) & (yy < H)
    img[head | body] = [46, 40, 36]

    return _noise(np.clip(img, 0, 255), 3.0, 3)


def indoor() -> np.ndarray:
    """室内暖光：整体偏黄，亮度中等，纹理密集。"""
    base = np.array([176, 148, 104], dtype=float)
    img = np.broadcast_to(base, (H, W, 3)).copy()

    rng = np.random.default_rng(11)
    # 家具/墙面纹理
    for _ in range(40):
        x0, y0 = rng.integers(0, W - 60), rng.integers(0, H - 60)
        w, h = rng.integers(30, 120), rng.integers(30, 120)
        shade = rng.uniform(0.75, 1.15)
        img[y0:y0 + h, x0:x0 + w] *= shade

    img = _vignette(img, 0.30)
    return _noise(np.clip(img, 0, 255), 4.0, 4)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    samples = {
        "outdoor.jpg": outdoor(),
        "night.jpg": night(),
        "backlit.jpg": backlit(),
        "indoor.jpg": indoor(),
    }
    for name, arr in samples.items():
        Image.fromarray(arr.astype(np.uint8)).save(OUT / name, quality=92)
        print(f"已生成 {OUT / name}")


if __name__ == "__main__":
    main()
