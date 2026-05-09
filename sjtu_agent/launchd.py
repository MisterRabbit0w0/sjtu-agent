"""Backward-compatible launchd helpers.

The maintained implementation lives in :mod:`sjtu_agent.scheduler.launchd`.
This module keeps older imports working without maintaining a second copy of
the launchd plist generation logic.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from sjtu_agent.scheduler import available_service_names
from sjtu_agent.scheduler.launchd import install as _install_launchd

DEFAULT_DAILY_REPORT_TIME = (22, 0)
DEFAULT_NEWS_DIGEST_TIME = (10, 0)
DEFAULT_REMIND_INTERVAL = 60
DEFAULT_TELEGRAM_THROTTLE = 10
DEFAULT_LAUNCH_AGENTS_DIR = Path.home() / "Library" / "LaunchAgents"


def write_launch_agent_plists(
    output_dir: Path = DEFAULT_LAUNCH_AGENTS_DIR,
    service_names: tuple[str, ...] | None = None,
    python_executable: Path | None = None,
    daily_report_time: tuple[int, int] = DEFAULT_DAILY_REPORT_TIME,
    news_digest_time: tuple[int, int] = DEFAULT_NEWS_DIGEST_TIME,
    remind_interval: int = DEFAULT_REMIND_INTERVAL,
    telegram_throttle: int = DEFAULT_TELEGRAM_THROTTLE,
) -> list[dict[str, object]]:
    payload = _install_launchd(
        service_names=service_names,
        python_executable=Path(os.path.abspath(os.fspath(python_executable or sys.executable))),
        daily_report_time=daily_report_time,
        news_digest_time=news_digest_time,
        remind_interval=remind_interval,
        telegram_throttle=telegram_throttle,
        load=False,
        output_dir=output_dir,
    )
    return payload["services"]


def install_launch_agents(
    output_dir: Path = DEFAULT_LAUNCH_AGENTS_DIR,
    service_names: tuple[str, ...] | None = None,
    python_executable: Path | None = None,
    daily_report_time: tuple[int, int] = DEFAULT_DAILY_REPORT_TIME,
    news_digest_time: tuple[int, int] = DEFAULT_NEWS_DIGEST_TIME,
    remind_interval: int = DEFAULT_REMIND_INTERVAL,
    telegram_throttle: int = DEFAULT_TELEGRAM_THROTTLE,
    load: bool = True,
) -> dict[str, object]:
    return _install_launchd(
        service_names=service_names,
        python_executable=Path(os.path.abspath(os.fspath(python_executable or sys.executable))),
        daily_report_time=daily_report_time,
        news_digest_time=news_digest_time,
        remind_interval=remind_interval,
        telegram_throttle=telegram_throttle,
        load=load,
        output_dir=output_dir,
    )
