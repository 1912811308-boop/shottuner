"""参数推荐核心（Kotlin 实现的等价版本）。

模型：曝光量 E = ISO × 快门时间。所谓「调参」就是把 E 调到让平均亮度
落到该场景的理想值，再在 ISO 与快门之间分配，受三重约束：
    快门下限  —— 手持不糊；
    快门上限  —— 场景允许的最长曝光；
    ISO 上限  —— 噪声容忍度与硬件能力。
当三重约束都被顶满仍不够亮时，说明物理上不可能，只能靠补光或长曝光支架。
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .scene import SceneType
from .stats import FrameStats

MS = 1_000_000


@dataclass(frozen=True)
class CameraCaps:
    iso_min: int = 50
    iso_max: int = 6400
    exposure_min_ns: int = 20_000
    exposure_max_ns: int = 500_000_000
    supports_manual_sensor: bool = False
    focal_length_mm: float = 0.0


@dataclass(frozen=True)
class ManualParams:
    iso: int
    shutter_ns: int

    @property
    def exposure(self) -> float:
        return self.iso * self.shutter_ns / 1e9


@dataclass(frozen=True)
class Preset:
    target_luma: float
    iso_start: int
    shutter_start_ns: int
    iso_cap: int
    shutter_floor_ns: int
    shutter_ceil_ns: int


@dataclass(frozen=True)
class Recommendation:
    scene: SceneType
    target_luma: float
    ev_delta: float
    iso: int
    shutter_ns: int
    kelvin: int
    needs_tripod: bool
    tip: str
    detail: str
    confidence: float

    @property
    def shutter_label(self) -> str:
        if self.shutter_ns <= 0:
            return "--"
        sec = self.shutter_ns / 1e9
        if sec >= 0.5:
            return f"{sec:.1f}s"
        return f"1/{round(1 / sec)}"

    def as_dict(self) -> dict:
        return {
            "scene": self.scene.value,
            "confidence": round(self.confidence, 3),
            "ev_delta": round(self.ev_delta, 2),
            "iso": self.iso,
            "shutter": self.shutter_label,
            "shutter_ns": self.shutter_ns,
            "kelvin": self.kelvin,
            "needs_tripod": self.needs_tripod,
            "tip": self.tip,
            "detail": self.detail,
        }


PRESETS: dict[SceneType, Preset] = {
    SceneType.NIGHT: Preset(0.20, 1600, 33 * MS, 6400, 33 * MS, 125 * MS),
    SceneType.BACKLIT: Preset(0.40, 100, 2 * MS, 1600, 2 * MS, 16 * MS),
    SceneType.PORTRAIT: Preset(0.50, 100, 4 * MS, 1600, 4 * MS, 16 * MS),
    SceneType.LANDSCAPE: Preset(0.46, 100, 2 * MS, 800, 2 * MS, 33 * MS),
    SceneType.INDOOR: Preset(0.42, 400, 16 * MS, 3200, 20 * MS, 66 * MS),
    SceneType.ACTION: Preset(0.45, 200, 1 * MS, 6400, 1 * MS, 4 * MS),
    SceneType.GENERAL: Preset(0.45, 100, 4 * MS, 1600, 16 * MS, 33 * MS),
}


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def _log2(x: float) -> float:
    return math.log(x, 2) if x > 0 else 0.0


#: 相机可设置的 ISO 档位（1/3 档序列）。给出 ISO 72 这种值在真机上根本设不了，
#: 所以最终建议必须吸附到档位上，宁可偏差 1/3 档也不能不可执行。
ISO_STOPS: tuple[int, ...] = (
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
    800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
    8000, 10000, 12800,
)


def quantize_iso(value: float, lo: int, hi: int) -> int:
    """把连续 ISO 吸附到最近的可用档位，按对数距离衡量远近。"""
    candidates = [v for v in ISO_STOPS if lo <= v <= hi]
    if not candidates:
        return int(round(_clamp(value, lo, hi)))
    safe = max(value, 1e-6)
    return min(candidates, key=lambda v: abs(_log2(v / safe)))


#: 快门档位里「快于 1 秒」的部分，用分母表示，1/60 这样的读数才对应相机拨盘。
FAST_SHUTTER_DENOMS: tuple[int, ...] = (
    8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
    800, 640, 500, 400, 320, 250, 200, 160, 125, 100,
    80, 60, 50, 40, 30, 25, 20, 15, 13, 10,
    8, 6, 5, 4, 3,
)

#: 快门档位里「慢于等于 0.4 秒」的部分，单位秒。
SLOW_SHUTTER_STOPS: tuple[float, ...] = (
    0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2,
    4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0,
)


def quantize_shutter(ns: int, min_ns: int, max_ns: int) -> int:
    """把连续快门吸附到相机拨盘上真实存在的档位。

    建议 1/63 秒等于让用户在专业模式的下拉列表里找不到东西，
    宁可差 1/3 档也要可执行。
    """
    target = _clamp(ns / 1e9, 1e-6, 30.0)
    candidates = [1.0 / d for d in FAST_SHUTTER_DENOMS] + list(SLOW_SHUTTER_STOPS)
    best = min(candidates, key=lambda s: abs(math.log(s / target)))
    return int(_clamp(round(best * 1e9), min_ns, max_ns))


def estimate_kelvin(stats: FrameStats) -> int:
    """灰世界假设反推色温。

    mean_r / mean_b 偏大说明画面偏暖，需要更低色温（更冷）去中和。
    该建议针对的是「自动白平衡校正后仍然残留的偏色」。
    """
    if stats.mean_r <= 0 or stats.mean_b <= 0:
        return 5500
    cast = _log2(stats.mean_r / stats.mean_b)
    return int(round(_clamp(5500 - cast * 1600, 2500, 8500)))


def recommend(
    stats: FrameStats,
    scene: SceneType,
    caps: CameraCaps,
    current: ManualParams | None = None,
    confidence: float = 0.5,
) -> Recommendation:
    preset = PRESETS.get(scene) or PRESETS[SceneType.GENERAL]

    # 1. 曝光偏差（EV）
    measured = _clamp(stats.mean_luma, 0.02, 0.98)
    ev_delta = _log2(preset.target_luma / measured)

    # 2. 高光保护
    if stats.highlight_clip > 0.04:
        ev_delta -= min(0.9, (stats.highlight_clip - 0.04) * 8.0)

    # 3. 暗部抬升（逆光不补亮，否则天空直接曝废）
    if scene not in (SceneType.NIGHT, SceneType.BACKLIT) and stats.shadow_clip > 0.20:
        ev_delta += min(0.5, (stats.shadow_clip - 0.20) * 2.0)

    # 4. 闭环阻尼：冷启动全量调整，闭环状态下只走 70%，避免来回震荡
    damping = 1.0 if current is None else 0.7
    applied_ev = _clamp(ev_delta * damping, -3.5, 3.5)

    # 5. 分配：先定快门，ISO 补足剩余
    anchor_iso = float(preset.iso_start if current is None else current.iso)
    anchor_shutter = float(preset.shutter_start_ns if current is None else current.shutter_ns)

    gain = 2.0**applied_ev
    total_exposure = anchor_iso * anchor_shutter / 1e9 * gain

    floor_ns = float(preset.shutter_floor_ns)
    ceil_ns = float(max(preset.shutter_ceil_ns, preset.shutter_floor_ns))
    shutter_ns = _clamp(anchor_shutter * gain, floor_ns, ceil_ns)

    iso = total_exposure / (shutter_ns / 1e9) if shutter_ns > 0 else anchor_iso

    iso_cap = min(preset.iso_cap, caps.iso_max)
    iso_floor = max(caps.iso_min, 50)

    if iso > iso_cap:
        iso = float(iso_cap)
        shutter_ns = _clamp(total_exposure / iso * 1e9, floor_ns, ceil_ns)
    elif iso < iso_floor:
        iso = float(iso_floor)
        shutter_ns = _clamp(total_exposure / iso * 1e9, floor_ns, ceil_ns)

    shutter_ns = _clamp(shutter_ns, float(caps.exposure_min_ns), float(caps.exposure_max_ns))

    final_iso = quantize_iso(_clamp(iso, iso_floor, iso_cap), iso_floor, iso_cap)
    final_shutter = quantize_shutter(
        max(int(round(shutter_ns)), 1_000), caps.exposure_min_ns, caps.exposure_max_ns
    )

    # 6. 判断是否已经顶到天花板（注意分母方向：曝光量 E ∝ ISO × 快门）
    achieved_ev = _log2((final_iso / anchor_iso) * (final_shutter / anchor_shutter))
    shortfall = applied_ev - achieved_ev

    kelvin = estimate_kelvin(stats)
    needs_tripod = final_shutter > 1_000_000_000 / 25 and scene in (
        SceneType.NIGHT,
        SceneType.INDOOR,
    )

    return Recommendation(
        scene=scene,
        target_luma=preset.target_luma,
        ev_delta=applied_ev,
        iso=final_iso,
        shutter_ns=final_shutter,
        kelvin=kelvin,
        needs_tripod=needs_tripod,
        tip=_build_tip(scene, shortfall, needs_tripod),
        detail=_build_detail(stats, kelvin, scene),
        confidence=confidence,
    )


def _build_tip(scene: SceneType, shortfall: float, needs_tripod: bool) -> str:
    if shortfall > 0.7:
        return "光线不足，已到硬件上限：建议找支撑物或补光，否则只能靠后期提亮"
    if shortfall < -0.7:
        return "画面偏亮，正在压曝光以保住高光细节"
    if needs_tripod:
        return "快门已低于 1/25s，手持容易糊：建议靠墙或使用三脚架"
    return {
        SceneType.NIGHT: "弱光场景：优先放慢快门保低 ISO，噪点更少",
        SceneType.BACKLIT: "逆光场景：压曝光保天空，主体可后期提亮或用补光",
        SceneType.PORTRAIT: "人像场景：用较快快门冻结微动，肤色更干净",
        SceneType.LANDSCAPE: "风景场景：低 ISO 保细节，注意高光不要溢出",
        SceneType.INDOOR: "室内暖光：先校色温再补曝光，避免越调越黄",
        SceneType.ACTION: "运动抓拍：快门优先，宁可提高 ISO 也要冻结动作",
        SceneType.GENERAL: "曝光接近理想值，可直接拍摄",
    }[scene]


def _build_detail(stats: FrameStats, kelvin: int, scene: SceneType) -> str:
    parts: list[str] = []
    dk = kelvin - 5500
    if abs(dk) >= 250:
        parts.append(f"{'偏暖' if dk < 0 else '偏冷'}，建议色温{'降' if dk < 0 else '升'}至 {kelvin}K")
    else:
        parts.append("白平衡基本准确")

    if stats.highlight_clip > 0.10:
        parts.append(f"高光溢出 {stats.highlight_clip:.0%}，注意保留层次")
    if stats.dynamic_range < 0.30 and scene != SceneType.NIGHT:
        parts.append("反差偏低，可能雾感或镜头脏污")
    if stats.saturation < 0.12 and scene != SceneType.NIGHT:
        parts.append("色彩淡，可适度提高饱和度")
    return "；".join(parts)
