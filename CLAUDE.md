# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SJTU Agent — 面向上海交通大学学生的校园助手。提供终端对话、Web UI、Telegram Bot、微信 Bot、MCP Server、定时日报和提醒守护进程。通过 OpenAI 兼容接口（默认致远一号 DeepSeek）驱动 LLM，使用 tool_use 循环调用 20+ 校园工具（DDL 查询、成绩查询、课表、Canvas 作业提交、邮件、my.sjtu.edu.cn 浏览等）。

## Build & Install

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate | macOS/Linux: source .venv/bin/activate
pip install -e .
python -m playwright install chromium   # 可选，Playwright 自动登录需要
```

安装后 `sjtu-agent` 命令可用（入口 `sjtu_agent/cli.py:main`）。

## Common Commands

```bash
sjtu-agent                # 启动终端对话（默认子命令 = chat）
sjtu-agent setup          # 首次配置向导
sjtu-agent doctor         # 检查配置状态和运行时路径
sjtu-agent web            # 启动 Web 配置界面（http://127.0.0.1:7860）
sjtu-agent telegram-bot   # 启动 Telegram Bot（长轮询）
sjtu-agent wechat-bot     # 启动微信 Bot
sjtu-agent mcp            # 启动 MCP Server（stdio 模式）
sjtu-agent mcp --http     # 启动 MCP Server（HTTP/SSE 模式）
sjtu-agent install-daemons # 安装后台守护进程（macOS launchd / Linux systemd / Windows Task Scheduler）
sjtu-agent update         # git pull + pip install -e .
sjtu-agent daily-report   # 生成日报
sjtu-agent ddl            # 运行 DDL 检查
sjtu-agent news-digest    # 智能新闻日报
```

也可以 `python -m sjtu_agent` 运行。

## Testing

项目已有少量测试（例如 `tests/test_config.py`），覆盖仍有限。运行现有测试：`pytest`；日常验证可配合 `sjtu-agent doctor`、`sjtu-agent daily-report --test`、`sjtu-agent telegram-bot --test`。

## Architecture

### 双层入口结构

项目根目录保留了一组**向后兼容的顶层脚本**（`agent.py`、`ddl_checker.py`、`login.py`、`telegram_bot.py`、`wechat_bot.py` 等），它们 re-export `sjtu_agent/` 包内的实际实现。CLI 子命令通过 `runpy.run_module()` 调用这些顶层模块名，因此这些文件不能删除。

实际逻辑在 `sjtu_agent/` 包中：

```
sjtu_agent/
  cli.py              — CLI 入口，argparse 子命令分发
  paths.py             — 所有运行时文件路径常量（DATA_DIR、CONFIG_PATH 等）
  config.py            — ConfigStore 单例，线程安全 + mtime 热重载
  agent/
    prompts.py         — 系统提示词和工具标签
    tools.py           — 20+ 工具函数定义（OpenAI function calling 格式）和 run_tool 分发
    runner.py          — LLM 客户端创建、流式处理、tool_use 循环（支持 OpenAI + Anthropic 双协议）
    chat_loop.py       — 配置加载、provider 管理、聊天主循环
    __init__.py        — 统一 re-export
  web/
    server.py          — 内置 HTTP Server（ThreadingHTTPServer），提供配置 API 和 SSE 聊天
    static/index.html  — 单页 Web UI
  scheduler/           — 跨平台守护进程安装（macOS launchd / Linux systemd / Windows Task Scheduler）
  news_aggregator/     — 新闻聚合（Canvas、教务处、水源社区、官方公告）
  setup_wizard.py      — 交互式首次配置向导
```

### LLM 调用流程

`runner.py` 是核心：根据模型名自动选择 OpenAI 或 Anthropic SDK（`_is_anthropic_model` 判断 `claude` 前缀），支持流式输出 + 思考标签处理（`<think>` / `reasoning_content`）+ tool_use 多轮循环。`_run_one_turn` 支持 fallback 模型链。

### 配置体系

运行时数据存储在用户数据目录（不在仓库根目录）：
- macOS: `~/Library/Application Support/sjtu-agent`
- Linux: `~/.local/share/sjtu-agent`
- Windows: `%APPDATA%/sjtu-agent`

关键配置文件（均在 DATA_DIR 下）：
- `.env` — jAccount 凭据、API Key
- `config.json` — 平台 Token/Cookie、Telegram/微信配置
- `agent_config.json` — LLM provider/model/base_url
- `llm_providers.json` — 多 provider 注册表（provider registry）

路径常量全部定义在 `sjtu_agent/paths.py`，所有模块从这里导入，不要硬编码路径。

### Provider 系统

`chat_loop.py` 实现了多 LLM provider 管理：`llm_providers.json` 存储 provider 注册表，终端聊天中通过 `/provider` 和 `/model` 斜杠命令切换。`_current_provider_config()` 解析当前选中的 provider 配置，`_make_client()` 根据模型名选择 SDK 实例化。

### Web UI 与终端聊天

Web Server（`web/server.py`）是纯 stdlib `http.server`，无框架依赖。SSE 聊天端点 `/api/chat` 复用 `agent.py` 的 tool_use 循环。终端聊天和 Web 聊天共享同一套工具和提示词，但各自维护独立的对话历史。

## Key Conventions

- Python >= 3.10，使用 `from __future__ import annotations`
- 中文用户界面（提示词、错误信息、CLI 输出均为中文）
- `atomic_write_json()` / `read_json_safe()` 用于状态文件读写（防崩溃数据丢失）
- Windows 兼容：Spinner 在 Windows 上降级为静态文本；stdout 使用 `errors="replace"` 防止编码崩溃
- 顶层脚本（`agent.py` 等）是兼容垫片，修改功能应编辑 `sjtu_agent/` 包内对应文件
