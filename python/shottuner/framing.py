"""机位与人像取景的参考实现（对应 Kotlin 侧 coach/FramingCoach.kt）。

这些规则来自 8 条手机摄影教学视频的共同结论，只保留能用画面几何判断的部分：

- 全身照要蹲下来拍，脸在中心、脚在底边，广角才能把腿拉长；
- 1× 主摄是 24mm 广角，脸放边缘会被拉宽；
- 半身照优先切 2×（等效 50mm），透视最接近人眼；
- 取景不要切在腰和膝盖上；
- 人物朝哪边看，就把他放在相反一侧的三分线。

坐标全部是 0..1 的归一化值，原点左上、y 向下，与 FrameStats 的网格一致。
这里不碰 Android API，纯函数，方便回归测试。
"""

from __future__ import annotations

from dataclasses import dataclass

# 广角主摄的等效焦距区间：落在这个区间才谈得上「边缘透视变形」
WIDE_FOCAL_MIN = 18.0
WIDE_FOCAL_MAX = 28.0

# 人脸占画面面积的分档（经验值，来自样张标定）
FACE_FULL_BODY_MAX = 0.06
FACE_CLOSEUP_MIN = 0.18

# 取景边界
EDGE_SAFE_X = 0.18
FACE_CENTER_MIN_Y = 0.42
FACE_CENTER_MAX_Y = 0.58
FOOT_BOTTOM_MIN = 0.88

# 关节禁区（人物外框底边落在这些区间就是「切在关节上」）
WAIST_RANGE = (0.44, 0.52)
KNEE_RANGE = (0.62, 0.72)

# 朝向判定：|eulerY| 超过这个角度才算「明显侧脸」
HEAD_TURN_DEG = 15.0


@dataclass
class FaceInfo:
    """ML Kit 人脸检测结果的精简版。"""

    count: int
    center_x: float
    center_y: float
    area: float
    box_bottom: float
    # 头部绕 Y 轴旋转角，正值表示脸转向画面右侧
    euler_y_deg: float = 0.0


@dataclass
class SubjectBox:
    """人物（或视觉主体）的外框，归一化坐标。"""

    left: float
    top: float
    right: float
    bottom: float

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2

    @property
    def width(self) -> float:
        return max(0.0, self.right - self.left)

    @property
    def height(self) -> float:
        return max(0.0, self.bottom - self.top)


@dataclass
class FramingAdvice:
    """一条机位建议。severity: 0=确认 1=建议 2=必须改。"""

    title: str
    reason: str
    severity: int
    code: str  # 规则编号，便于日志与测试定位


def _is_wide(equiv_focal_mm: float) -> bool:
    return WIDE_FOCAL_MIN <= equiv_focal_mm <= WIDE_FOCAL_MAX


def advise(
    face: FaceInfo | None,
    subject: SubjectBox | None,
    subject_size: float,
    equiv_focal_mm: float,
    edge_density: float = 0.0,
) -> list[FramingAdvice]:
    """按优先级返回机位建议，最多两条。

    subject_size 是显著性主体占画面的面积比（CompositionHint.subjectSize），
    用于「留白 / 填充」判断；edge_density 用来排除满屏纹理的误判。
    """

    if face is None or face.count <= 0:
        return []

    out: list[FramingAdvice] = []
    wide = _is_wide(equiv_focal_mm)

    # T02 广角透视：脸贴边会被拉宽
    if wide and (face.center_x < EDGE_SAFE_X or face.center_x > 1 - EDGE_SAFE_X):
        out.append(
            FramingAdvice(
                title="人脸太靠边了，往中间挪一点",
                reason="1× 主摄是广角，画面边缘会把脸拉宽；腿放边上反而显长，脸不行",
                severity=2,
                code="T02",
            )
        )

    # T01 全身照：蹲下来，脸在中心，脚在底边
    if face.area < FACE_FULL_BODY_MAX:
        if face.center_y < FACE_CENTER_MIN_Y:
            out.append(
                FramingAdvice(
                    title="蹲下，把手机降到腰以下",
                    reason="站着俯拍会把腿拍短、脸拍大；蹲下来用广角，腿自然就长了",
                    severity=2,
                    code="T01",
                )
            )
        elif subject is not None and subject.bottom < FOOT_BOTTOM_MIN:
            out.append(
                FramingAdvice(
                    title="退半步，把脚留在画面底部",
                    reason="脚被裁掉会显得人悬空，腿也短了一截",
                    severity=1,
                    code="T01",
                )
            )
        elif (
            subject is not None
            and FACE_CENTER_MIN_Y <= face.center_y <= FACE_CENTER_MAX_Y
            and subject.bottom >= 0.90
        ):
            out.append(
                FramingAdvice(
                    title="这个机位对了，可以按快门",
                    reason="脸在中间、脚在底边，广角把腿拉长又没让脸变形",
                    severity=0,
                    code="T01",
                )
            )

    # T03 半身与特写的焦段
    if FACE_FULL_BODY_MAX <= face.area < FACE_CLOSEUP_MIN and wide:
        out.append(
            FramingAdvice(
                title="切到 2×，半身用 50mm 更自然",
                reason="2× 的透视接近人眼，脸不会因为凑太近而变形",
                severity=1,
                code="T03",
            )
        )
    elif face.area >= FACE_CLOSEUP_MIN and equiv_focal_mm < 35:
        out.append(
            FramingAdvice(
                title="特写切 2× 或 3×，别用 1× 凑近",
                reason="广角贴脸会放大鼻子、压扁耳朵，比例失真",
                severity=1,
                code="T03",
            )
        )

    # T04 别切关节
    if subject is not None:
        bottom = subject.bottom
        if WAIST_RANGE[0] <= bottom <= WAIST_RANGE[1]:
            out.append(
                FramingAdvice(
                    title="取景别切在腰上",
                    reason="切在腰线会把人截成两段，往上或往下挪半格都更顺眼",
                    severity=1,
                    code="T04",
                )
            )
        elif KNEE_RANGE[0] <= bottom <= KNEE_RANGE[1]:
            out.append(
                FramingAdvice(
                    title="取景别切在膝盖上",
                    reason="膝盖是关节，切在这里会显得腿突然断掉",
                    severity=1,
                    code="T04",
                )
            )

    # T06 朝向决定放哪条三分线
    if abs(face.euler_y_deg) > HEAD_TURN_DEG and subject is not None:
        if face.euler_y_deg > 0 and subject.center_x > 0.55:
            out.append(
                FramingAdvice(
                    title="他朝右看，把人放到左三分线",
                    reason="视线方向要留出空间，否则像面壁思过",
                    severity=1,
                    code="T06",
                )
            )
        elif face.euler_y_deg < 0 and subject.center_x < 0.45:
            out.append(
                FramingAdvice(
                    title="他朝左看，把人放到右三分线",
                    reason="视线方向要留出空间，否则像面壁思过",
                    severity=1,
                    code="T06",
                )
            )

    # T05 留白与填充：只在没有更紧急的建议时给
    if not out and edge_density > 0:
        if subject_size < 0.10 and edge_density < 0.18:
            out.append(
                FramingAdvice(
                    title="试试留白：人再小一点，让环境占 2/3",
                    reason="主体占比小、环境干净时，留白比塞满更有情绪",
                    severity=0,
                    code="T05",
                )
            )
        elif subject_size > 0.55:
            out.append(
                FramingAdvice(
                    title="想拍特写就再靠近，想拍环境就退一步",
                    reason="主体已经塞满画面，这时要明确选一种，别停在中间",
                    severity=0,
                    code="T05",
                )
            )

    # 必须改的排前面；同级保持规则顺序（T01 先于 T03）
    out.sort(key=lambda a: -a.severity)
    return out[:2]
