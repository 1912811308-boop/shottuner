"""蓝调时刻与日落计算（对应 Kotlin 侧 astro/BlueHour.kt）。

夜景教学视频里最重要的一条不是参数，而是时机：太阳落山后、天空还发蓝的
那二十分钟，城市灯已经亮了，暗部还有细节，是手机最容易出片的窗口。

这里用 NOAA 简化太阳位置公式在本地算，不联网、不依赖年历表：
太阳高度角从 0° 降到 -4° 是「刚日落」，-4° 到 -6° 是蓝调窗口。
精度足够做「还有 12 分钟」这类提示，不要拿它做天文测量。
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone

# 蓝调窗口的太阳高度区间（度）
BLUE_START_ELEV = -4.0
BLUE_END_ELEV = -6.0

# 常驻时区：国内统一 UTC+8
CST = timezone(timedelta(hours=8))


@dataclass
class SunWindow:
    """一个时间窗口，带时区。"""

    start: datetime
    end: datetime

    def minutes_until(self, now: datetime) -> int:
        """距离窗口开始还有几分钟；已进入窗口返回 0，已结束返回负数。"""
        delta = (self.start - now).total_seconds() / 60
        return int(round(delta))

    def contains(self, now: datetime) -> bool:
        return self.start <= now <= self.end

    def label(self) -> str:
        return f"{self.start:%H:%M}–{self.end:%H:%M}"


def solar_elevation(lat: float, lon: float, when: datetime) -> float:
    """太阳高度角（度）。when 必须带时区，否则按东八区处理。"""

    if when.tzinfo is None:
        when = when.replace(tzinfo=CST)
    utc = when.astimezone(timezone.utc)
    day_of_year = utc.timetuple().tm_yday
    hour = utc.hour + utc.minute / 60 + utc.second / 3600

    gamma = 2 * math.pi / 365 * (day_of_year - 1 + (hour - 12) / 24)
    eqtime = 229.18 * (
        0.000075
        + 0.001868 * math.cos(gamma)
        - 0.032077 * math.sin(gamma)
        - 0.014615 * math.cos(2 * gamma)
        - 0.040849 * math.sin(2 * gamma)
    )
    decl = (
        0.006918
        - 0.399912 * math.cos(gamma)
        + 0.070257 * math.sin(gamma)
        - 0.006758 * math.cos(2 * gamma)
        + 0.000907 * math.sin(2 * gamma)
        - 0.002697 * math.cos(3 * gamma)
        + 0.00148 * math.sin(3 * gamma)
    )

    true_solar_time = (hour * 60 + eqtime + 4 * lon) % 1440
    hour_angle = math.radians(true_solar_time / 4 - 180)
    lat_r = math.radians(lat)
    cos_zenith = math.sin(lat_r) * math.sin(decl) + math.cos(lat_r) * math.cos(
        decl
    ) * math.cos(hour_angle)
    return 90 - math.degrees(math.acos(max(-1.0, min(1.0, cos_zenith))))


def _find_evening_crossing(
    lat: float, lon: float, day: date, target: float, tz: timezone
) -> datetime | None:
    """找当天傍晚太阳高度经过 target 的时刻（分钟级）。"""

    # 从正午开始扫，只找傍晚那次穿越；从 0 点扫会先撞上日出。
    start = datetime.combine(day, time(12, 0), tzinfo=tz)
    prev = solar_elevation(lat, lon, start)
    for minute in range(1, 12 * 60):
        when = start + timedelta(minutes=minute)
        cur = solar_elevation(lat, lon, when)
        if (prev - target) * (cur - target) <= 0 and abs(prev - cur) < 1.0:
            return when
        prev = cur
    return None


def sunset(lat: float, lon: float, day: date, tz: timezone = CST) -> datetime | None:
    """日落时刻（太阳高度过 0°）。"""

    return _find_evening_crossing(lat, lon, day, 0.0, tz)


def blue_hour(
    lat: float, lon: float, day: date, tz: timezone = CST
) -> SunWindow | None:
    """当天的蓝调窗口；极昼/极夜返回 None。"""

    start = _find_evening_crossing(lat, lon, day, BLUE_START_ELEV, tz)
    end = _find_evening_crossing(lat, lon, day, BLUE_END_ELEV, tz)
    if start is None or end is None or end <= start:
        return None
    return SunWindow(start=start, end=end)


def next_blue_hour(
    lat: float, lon: float, now: datetime | None = None, tz: timezone = CST
) -> SunWindow | None:
    """最近一个还没结束的蓝调窗口：今天没结束就给今天，否则给明天。"""

    if now is None:
        now = datetime.now(tz)
    if now.tzinfo is None:
        now = now.replace(tzinfo=tz)

    today = blue_hour(lat, lon, now.date(), tz)
    if today is not None and now <= today.end:
        return today

    return blue_hour(lat, lon, now.date() + timedelta(days=1), tz)


def hint_text(lat: float, lon: float, now: datetime | None = None) -> str:
    """给界面用的一句话。没有蓝调窗口时返回空串。"""

    if now is None:
        now = datetime.now(CST)
    window = next_blue_hour(lat, lon, now)
    if window is None:
        return ""

    if window.contains(now):
        left = int((window.end - now).total_seconds() / 60)
        return f"正在蓝调时刻，还有 {left} 分钟，抓紧拍"

    minutes = window.minutes_until(now)
    if minutes <= 90:
        return f"蓝调时刻 {window.label()}，还有 {minutes} 分钟"
    if window.start.date() > now.date():
        return f"今天蓝调已过，明天 {window.label()}"
    return f"蓝调时刻 {window.label()}"
