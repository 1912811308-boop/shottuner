"""闭环仿真：验证「自动调参」真的会收敛，而不是越调越离谱。

这是整个项目里最值得先跑起来的一段代码。它把相机抽象成一个
「曝光量 → 亮度」的映射，然后在循环里反复调用 recommend()，
观察亮度是否稳定在场景目标值上。

如果算法在仿真里都收敛不了，装到手机上一定更糟。
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .recommend import CameraCaps, ManualParams, Recommendation, recommend
from .scene import SceneType
from .stats import FrameStats


@dataclass
class FakeCamera:
    """极简相机模型。

    scene_gain = 1.0 代表「ISO100 + 1/250s」刚好曝光正常（亮度 0.45）。
    真实手机在这个条件下约等于晴天室外 f/1.8，用于校准量级足够。
    """

    scene_gain: float
    reference_iso: int = 100
    reference_shutter_ns: int = 4_000_000
    reference_luma: float = 0.45
    noise: float = 0.02

    def capture(self, iso: int, shutter_ns: int, rng: np.random.Generator | None = None) -> FrameStats:
        exposure = iso * shutter_ns / 1e9
        reference = self.reference_iso * self.reference_shutter_ns / 1e9
        luma = self.scene_gain * exposure / reference * self.reference_luma
        luma = float(np.clip(luma, 0.0, 1.35))

        if rng is not None:
            luma *= 1.0 + rng.normal(0.0, self.noise)
            luma = float(np.clip(luma, 0.0, 1.35))

        # 亮度与截断比例的经验关系：越亮，高光溢出与死黑越极端
        highlight = max(0.0, (luma - 0.75) / 0.35) ** 2 * 0.45
        shadow = max(0.0, (0.12 - luma) / 0.12) ** 2 * 0.55

        return synthesize_stats(
            mean_luma=luma,
            highlight_clip=highlight,
            shadow_clip=shadow,
        )


def synthesize_stats(
    mean_luma: float,
    highlight_clip: float = 0.0,
    shadow_clip: float = 0.0,
    cast: float = 0.0,
    saturation: float = 0.30,
    edge_density: float = 0.30,
    skin_ratio: float = 0.0,
    center_bias: float = 0.0,
) -> FrameStats:
    """构造一个统计对象，避免为仿真真的渲染图像。"""
    luma255 = float(np.clip(mean_luma, 0, 1)) * 255
    p05 = float(np.clip(mean_luma - 0.28, 0, 1))
    p95 = float(np.clip(mean_luma + 0.28, 0, 1))
    hist = np.zeros(256, dtype=np.float32)
    idx = int(np.clip(round(luma255), 0, 255))
    hist[idx] = 1.0

    r = 0.5 * (1 + cast / 2)
    b = 0.5 * (1 - cast / 2)
    return FrameStats(
        histogram=hist,
        mean_luma=float(np.clip(mean_luma, 0.0, 1.0)),
        p05=p05,
        p50=float(np.clip(mean_luma, 0, 1)),
        p95=p95,
        highlight_clip=float(highlight_clip),
        shadow_clip=float(shadow_clip),
        mean_r=float(r),
        mean_g=0.5,
        mean_b=float(b),
        saturation=saturation,
        center_luma=float(np.clip(mean_luma + center_bias, 0, 1)),
        edge_density=edge_density,
        skin_ratio=skin_ratio,
        width=640,
        height=480,
    )


def run_closed_loop(
    scene: SceneType,
    scene_gain: float,
    frames: int = 16,
    caps: CameraCaps | None = None,
    seed: int = 7,
) -> tuple[list[Recommendation], list[float]]:
    """跑一次闭环，返回每帧的建议与对应的实拍亮度。"""
    caps = caps or CameraCaps()
    camera = FakeCamera(scene_gain=scene_gain)
    rng = np.random.default_rng(seed)

    current: ManualParams | None = ManualParams(iso=100, shutter_ns=4_000_000)
    recs: list[Recommendation] = []
    lumas: list[float] = []

    for _ in range(frames):
        if current is None:
            # 读不到实时参数时的兜底：先按预设曝光拍一帧
            stats = camera.capture(100, 4_000_000, rng)
        else:
            stats = camera.capture(current.iso, current.shutter_ns, rng)

        rec = recommend(stats, scene, caps, current)
        recs.append(rec)
        lumas.append(stats.mean_luma)
        current = ManualParams(iso=rec.iso, shutter_ns=rec.shutter_ns)

    return recs, lumas


def main() -> None:
    cases = [
        ("晴天室外", SceneType.LANDSCAPE, 1.0),
        ("室内暖光", SceneType.INDOOR, 0.18),
        ("傍晚街道", SceneType.NIGHT, 0.02),
        ("逆光窗边", SceneType.BACKLIT, 1.4),
    ]

    for name, scene, gain in cases:
        recs, lumas = run_closed_loop(scene, gain)
        target = recs[-1].target_luma
        tail = lumas[-4:]
        print(f"\n=== {name} | {scene.value} | 场景增益 {gain} ===")
        print(f"目标亮度 {target:.2f}，末 4 帧实测 {[f'{v:.3f}' for v in tail]}")
        last = recs[-1]
        print(f"最终参数 ISO {last.iso} / {last.shutter_label} / {last.kelvin}K")
        print(f"提示：{last.tip}")

        converged = all(abs(v - target) <= 0.06 for v in tail)
        print("收敛：" + ("是" if converged else "否（需检查阻尼或上限设置）"))


if __name__ == "__main__":
    main()
