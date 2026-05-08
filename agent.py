#!/usr/bin/env python3
"""
agent.py — 向后兼容入口（实际逻辑已迁移到 sjtu_agent/agent/）

保留此文件使现有脚本（telegram_bot.py、wechat_bot.py 等）无需修改。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

# 全量 re-export，外部 `import agent` 后所有符号仍可用
from sjtu_agent.agent import *  # noqa: F401, F403
from sjtu_agent.agent import (  # noqa: F401
    SYSTEM_PROMPT, _TOOL_LABELS, TOOLS, run_tool,
    Spinner, _make_client, _is_anthropic_model, _anthropic_tools,
    _run_one_turn, _run_one_turn_openai, _run_one_turn_anthropic,
    _stream_with_think_tags, _ANSI_OK,
    load_agent_config, setup_agent_config, chat_loop, main,
    _prefetch_ddls_background, _check_for_updates, _UPDATE_AVAILABLE,
    _fetch_ddls_parallel, _ddl_cache_get,
)

if __name__ == "__main__":
    main()
