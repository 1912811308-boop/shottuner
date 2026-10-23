"""蓝调时刻计算的回归测试。

数值用公开天文年历对照：北京 2026-10-17 日落约 17:30，
蓝调（太阳高度 -4°～-6°）约 17:51–18:02。允许 3 分钟误差——
这是给人看「还有多久」的提示，不是天文测量。
"""

from datetime import date, datetime

from shottuner.astro import (
    CST,
    blue_hour,
    hint_text,
    next_blue_hour,
    solar_elevation,
    sunset,
)

BEIJING = (39.9042, 116.4074)
GUANGZHOU = (23.1291, 113.2644)
URUMQI = (43.8256, 87.6168)
DAY = date(2026, 10, 17)


def _minutes(dt: datetime) -> int:
    return dt.hour * 60 + dt.minute


def test_noon_elevation_is_reasonable():
    elevation = solar_elevation(*BEIJING, datetime(2026, 10, 17, 12, 10, tzinfo=CST))
    assert 35 <= elevation <= 45


def test_midnight_sun_is_below_horizon():
    elevation = solar_elevation(*BEIJING, datetime(2026, 10, 17, 0, 0, tzinfo=CST))
    assert elevation < -20


def test_beijing_sunset_and_blue_hour():
    sun = sunset(*BEIJING, DAY)
    assert sun is not None
    assert abs(_minutes(sun) - (17 * 60 + 30)) <= 3

    window = blue_hour(*BEIJING, DAY)
    assert window is not None
    assert abs(_minutes(window.start) - (17 * 60 + 51)) <= 3
    assert abs(_minutes(window.end) - (18 * 60 + 2)) <= 3
    assert window.end > window.start


def test_southern_and_western_cities_shift_as_expected():
    gz = sunset(*GUANGZHOU, DAY)
    urumqi = sunset(*URUMQI, DAY)
    assert gz is not None and urumqi is not None
    assert abs(_minutes(gz) - (17 * 60 + 57)) <= 3
    assert abs(_minutes(urumqi) - (19 * 60 + 20)) <= 3
    assert urumqi > gz, "乌鲁木齐经度更西，日落必须更晚"


def test_window_contains_and_countdown():
    window = blue_hour(*BEIJING, DAY)
    assert window is not None
    inside = window.start
    assert window.contains(inside)
    assert window.minutes_until(inside) == 0


def test_next_blue_hour_rolls_to_tomorrow_after_end():
    window = blue_hour(*BEIJING, DAY)
    assert window is not None
    late = window.end.replace(hour=19, minute=30)
    nxt = next_blue_hour(*BEIJING, late)
    assert nxt is not None and nxt.start.date() > late.date()


def test_hint_text_is_human_readable():
    window = blue_hour(*BEIJING, DAY)
    assert window is not None
    before = window.start.replace(minute=window.start.minute - 12)
    text = hint_text(*BEIJING, before)
    assert "蓝调" in text and "分钟" in text
