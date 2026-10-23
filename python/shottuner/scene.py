"""场景判定规则。

这里刻意保留「打分表」而不是 if-elif 链：每个场景独立累计证据，
最终取最高分，并给出 top1/top2 的比值作为置信度。
好处是新增场景不影响既有判断，阈值调整也可以逐条回归。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .stats import FrameStats


class SceneType(str, Enum):
    NIGHT = "夜景 / 弱光"
    BACKLIT = "逆光"
    PORTRAIT = "人像"
    LANDSCAPE = "风景"
    INDOOR = "室内"
    ACTION = "运动 / 抓拍"
    GENERAL = "通用"


@dataclass(frozen=True)
class SceneGuess:
    scene: SceneType
    confidence: float

    def __str__(self) -> str:
        return f"{self.scene.value} ({self.confidence:.0%})"


def _score_night(s: FrameStats) -> float:
    score = 0.0
    if s.mean_luma < 0.14:
        score += 0.55
    if s.mean_luma < 0.22:
        score += 0.20
    if s.shadow_clip > 0.45:
        score += 0.25
    if s.p95 < 0.55:
        score += 0.10
    return score


def _score_backlit(s: FrameStats) -> float:
    """逆光的本质是「大光比」：既有接近饱和的亮部，也有压得极深的暗部。

    早期版本只数 highlight_clip，问题在于很多逆光片天空是 240 而不是 255，
    根本进不了溢出统计，结果被判成风景。改用 P95/P05/动态范围三元组更稳。
    """
    score = 0.0
    if s.highlight_clip > 0.03:
        score += 0.25
    if s.p95 > 0.90 and s.p05 < 0.20 and s.dynamic_range > 0.60:
        score += 0.50
    if s.center_bias < -0.08:
        score += 0.10
    if s.mean_luma > 0.30:
        score += 0.05
    return score


def _score_portrait(s: FrameStats) -> float:
    score = 0.0
    if s.skin_ratio > 0.05:
        score += 0.40
    if s.skin_ratio > 0.10:
        score += 0.35
    if abs(s.center_bias) < 0.12:
        score += 0.10
    if s.saturation < 0.45:
        score += 0.05
    # 肤色占比过半时，更可能是暖色墙面或木质桌面，而不是真的拍人
    if s.skin_ratio > 0.75:
        score -= 0.30
    return score


def _score_landscape(s: FrameStats) -> float:
    """饱和度是风景的必要条件，不满足时不能只靠动态范围拿分，
    否则逆光片（大光比、低饱和）会被误判成风景。
    """
    score = 0.0
    if s.saturation > 0.32:
        score += 0.35
        if s.dynamic_range > 0.55:
            score += 0.30
    if 0.05 <= s.edge_density <= 0.55:
        score += 0.15
    if 0.30 <= s.mean_luma <= 0.75:
        score += 0.10
    return score


def _score_indoor(s: FrameStats) -> float:
    """暖色偏是室内的最强特征，分两档给分：一般偏暖与强偏暖（白炽灯/暖光射灯）。

    额外加一条硬门槛：室内几乎没有接近饱和的亮部。若 P95 > 0.90，
    说明画面里有窗光或灯具直射，直接排除室内，否则逆光片会被误吞。
    """
    if s.p95 > 0.90:
        return 0.0
    score = 0.0
    if s.color_cast_index > 0.15:
        score += 0.40
    if s.color_cast_index > 0.35:
        score += 0.20
    if 0.16 <= s.mean_luma <= 0.50:
        score += 0.20
    if s.highlight_clip < 0.03:
        score += 0.10
    return score


def _score_action(s: FrameStats) -> float:
    score = 0.0
    if s.edge_density > 0.40:
        score += 0.35
    if s.mean_luma > 0.32:
        score += 0.20
    if 0.30 <= s.p50 <= 0.70:
        score += 0.15
    return score


_SCORERS = {
    SceneType.NIGHT: _score_night,
    SceneType.BACKLIT: _score_backlit,
    SceneType.PORTRAIT: _score_portrait,
    SceneType.LANDSCAPE: _score_landscape,
    SceneType.INDOOR: _score_indoor,
    SceneType.ACTION: _score_action,
    SceneType.GENERAL: lambda _s: 0.30,
}


def classify(stats: FrameStats) -> SceneGuess:
    scores = {scene: fn(stats) for scene, fn in _SCORERS.items()}
    ranked = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    best_scene, best_score = ranked[0]
    second_score = ranked[1][1]
    total = best_score + second_score
    confidence = 0.35 if total <= 0 else float(min(max(best_score / total, 0.35), 0.98))
    return SceneGuess(scene=best_scene, confidence=confidence)
