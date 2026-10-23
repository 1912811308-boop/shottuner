"""光影调参 · 算法参考实现。

本包是 Android 端 Kotlin 代码的等价 Python 实现，用途有两个：
1. 快速迭代与回归验证（改一个阈值不必重新编译 APK）；
2. 作为可读的算法说明书，参数含义与推导过程都在这里。

设计原则：不依赖任何相机硬件，输入是「图像统计特征」或「一张图片」，
输出是参数建议。这样同一套逻辑既能跑在手机上，也能在电脑上批量验证。
"""

from .stats import FrameStats, stats_from_image, stats_from_rgb
from .scene import SceneType, SceneGuess, classify
from .recommend import CameraCaps, ManualParams, Recommendation, recommend

__all__ = [
    "FrameStats",
    "stats_from_image",
    "stats_from_rgb",
    "SceneType",
    "SceneGuess",
    "classify",
    "CameraCaps",
    "ManualParams",
    "Recommendation",
    "recommend",
]

__version__ = "1.0.0"
