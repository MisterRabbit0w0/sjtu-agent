from __future__ import annotations

import ast
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_legacy_top_level_modules_are_not_packaged_or_present() -> None:
    legacy_modules = {
        "agent",
        "care_check",
        "daily_report",
        "ddl_checker",
        "login",
        "mcp_server",
        "news_digest",
        "remind_check",
        "setup_config",
        "shuiyuan_watcher",
        "telegram_bot",
        "wechat_bot",
    }

    for module in legacy_modules:
        assert not (ROOT / f"{module}.py").exists()

    pyproject = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
    assert "py-modules" not in pyproject
    for module in legacy_modules:
        assert f'"{module}"' not in pyproject


def test_cli_dispatches_to_package_modules_without_runpy() -> None:
    cli_source = (ROOT / "sjtu_agent" / "cli.py").read_text(encoding="utf-8")
    tree = ast.parse(cli_source)

    assert "runpy" not in {node.names[0].name for node in tree.body if isinstance(node, ast.Import)}
    assert "_run_module" not in cli_source
    assert "run_path" not in cli_source
    assert "run_module" not in cli_source

    expected_targets = {
        "sjtu_agent.agent.chat_loop",
        "sjtu_agent.auth.login",
        "sjtu_agent.setup_config",
        "sjtu_agent.ddl.checker",
        "sjtu_agent.reporting.daily",
        "sjtu_agent.bots.telegram",
        "sjtu_agent.bots.wechat",
        "sjtu_agent.reminder.daemon",
        "sjtu_agent.news_aggregator.cli",
        "sjtu_agent.mcp.server",
    }
    for target in expected_targets:
        assert target in cli_source


def test_new_package_entry_modules_import() -> None:
    import sjtu_agent.agent as agent
    import sjtu_agent.auth.login as login
    import sjtu_agent.bots.wechat as wechat
    import sjtu_agent.care.check as care
    import sjtu_agent.ddl.checker as checker
    import sjtu_agent.mcp.server as mcp_server
    import sjtu_agent.news_aggregator.cli as news_cli
    import sjtu_agent.reminder.daemon as reminder
    import sjtu_agent.reporting.daily as daily
    import sjtu_agent.setup_config as browser_cookies
    import sjtu_agent.watchers.shuiyuan as shuiyuan

    assert callable(agent.tool_check_setup)
    assert callable(login.main)
    assert callable(checker.main)
    assert callable(daily.main)
    assert callable(wechat.main)
    assert callable(reminder.main)
    assert callable(news_cli.main)
    assert callable(mcp_server.main)
    assert callable(browser_cookies.main)
    assert callable(care.run_care_check)
    assert callable(shuiyuan.main)

    telegram_source = (ROOT / "sjtu_agent" / "bots" / "telegram.py").read_text(encoding="utf-8")
    assert "def main(" in telegram_source

