"""机位教练规则的回归测试。

每条用例都对应一个具体拍摄动作，断言的是「用户能不能照着做」，
不是内部实现细节：提示里必须有该做的动作，否则文案等于没说。
"""

import pytest

from shottuner.framing import FaceInfo, SubjectBox, advise


def _face(cx=0.5, cy=0.5, area=0.03, bottom=0.12, euler=0.0) -> FaceInfo:
    return FaceInfo(
        count=1,
        center_x=cx,
        center_y=cy,
        area=area,
        box_bottom=bottom,
        euler_y_deg=euler,
    )


def _subject(bottom=0.95, left=0.3, right=0.7, top=0.05) -> SubjectBox:
    return SubjectBox(left=left, top=top, right=right, bottom=bottom)


def _titles(advices) -> str:
    return " / ".join(a.title for a in advices)


def test_full_body_low_face_asks_user_to_squat():
    out = advise(_face(cy=0.28, area=0.03), _subject(bottom=0.95), 0.25, 24.0)
    assert out, "全身照脸偏上时必须给建议"
    assert "蹲下" in _titles(out)
    assert out[0].code == "T01"


def test_full_body_feet_cut_asks_to_step_back():
    out = advise(_face(cy=0.5, area=0.03), _subject(bottom=0.80), 0.25, 24.0)
    assert out and "退半步" in _titles(out)


def test_full_body_correct_framing_confirms():
    out = advise(_face(cy=0.5, area=0.03), _subject(bottom=0.95), 0.25, 24.0)
    assert out and "对了" in _titles(out)
    assert out[0].severity == 0


def test_face_near_edge_warns_about_wide_angle():
    out = advise(_face(cx=0.10, area=0.10), _subject(), 0.2, 24.0)
    assert out and "靠边" in _titles(out)
    assert out[0].severity == 2


def test_face_near_edge_is_fine_on_tele_lens():
    out = advise(_face(cx=0.10, area=0.10), _subject(), 0.2, 50.0)
    assert all("靠边" not in a.title for a in out), "长焦不该报广角变形"


def test_half_body_prefers_2x():
    out = advise(_face(area=0.10), _subject(bottom=0.85), 0.3, 24.0)
    assert out and any("2×" in a.title for a in out)


def test_closeup_asks_for_tele_not_leaning_in():
    out = advise(_face(area=0.25), _subject(bottom=0.85), 0.4, 24.0)
    assert out and "别用 1× 凑近" in _titles(out)


@pytest.mark.parametrize(
    "bottom,keyword",
    [(0.48, "腰"), (0.67, "膝盖")],
)
def test_joint_cut_is_flagged(bottom, keyword):
    out = advise(_face(area=0.10), _subject(bottom=bottom), 0.3, 50.0)
    assert out and keyword in _titles(out)


def test_no_joint_warning_between_waist_and_knee():
    out = advise(_face(area=0.10), _subject(bottom=0.55), 0.3, 50.0)
    assert all("膝盖" not in a.title and "腰上" not in a.title for a in out)


def test_orientation_decides_which_third():
    out = advise(
        _face(cx=0.7, area=0.10, euler=25.0),
        _subject(bottom=0.85, left=0.55, right=0.9),
        0.3,
        50.0,
    )
    assert out and "左三分线" in _titles(out)


def test_orientation_ignored_when_facing_camera():
    out = advise(_face(cx=0.7, area=0.10, euler=0.0), _subject(), 0.3, 50.0)
    assert all("三分线" not in a.title for a in out)


def test_no_face_returns_nothing():
    assert advise(None, _subject(), 0.3, 24.0) == []
    assert advise(FaceInfo(0, 0, 0, 0, 0), _subject(), 0.3, 24.0) == []


def test_whitespace_hint_only_when_nothing_urgent():
    out = advise(_face(cy=0.5, area=0.03), None, 0.05, 24.0, edge_density=0.10)
    assert out and "留白" in _titles(out)


def test_returns_at_most_two_hints():
    out = advise(_face(cx=0.10, cy=0.3, area=0.10), _subject(bottom=0.48), 0.2, 24.0)
    assert len(out) <= 2
