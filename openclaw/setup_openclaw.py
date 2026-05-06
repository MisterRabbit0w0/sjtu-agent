#!/usr/bin/env python3
"""
setup_openclaw.py — 将 sjtu-agent 注册为 OpenClaw 的 MCP 工具服务器

运行方式：
  python3 openclaw/setup_openclaw.py
  # 或在 openclaw/ 目录下：
  python3 setup_openclaw.py
"""

from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

# ── 路径 ──────────────────────────────────────────────────────────────────────

# sjtu-agent 项目根目录（本文件的上级目录）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
MCP_SERVER   = PROJECT_ROOT / "mcp_server.py"

# OpenClaw 配置文件候选路径（按平台）
def _openclaw_config_candidates() -> list[Path]:
    home = Path.home()
    candidates = [
        home / ".openclaw" / "config.json",
        home / ".config" / "openclaw" / "config.json",
    ]
    if platform.system() == "Windows":
        appdata = Path(os.environ.get("APPDATA", home / "AppData" / "Roaming"))
        candidates.insert(0, appdata / "openclaw" / "config.json")
    return candidates


def _find_openclaw_config() -> Path | None:
    for p in _openclaw_config_candidates():
        if p.exists():
            return p
    return None


def _detect_python() -> str:
    """返回当前 Python 可执行文件的绝对路径。"""
    return sys.executable


# ── 打印工具 ──────────────────────────────────────────────────────────────────

def _ok(msg: str)   -> None: print(f"  ✅  {msg}")
def _warn(msg: str) -> None: print(f"  ⚠️   {msg}")
def _info(msg: str) -> None: print(f"  ℹ️   {msg}")
def _err(msg: str)  -> None: print(f"  ❌  {msg}")


# ── 核心逻辑 ──────────────────────────────────────────────────────────────────

def check_openclaw() -> bool:
    """检查 openclaw 命令是否可用。"""
    path = shutil.which("openclaw")
    if path:
        try:
            result = subprocess.run(
                ["openclaw", "--version"],
                capture_output=True, text=True, timeout=5
            )
            ver = result.stdout.strip() or result.stderr.strip()
            _ok(f"openclaw 已安装：{ver or path}")
        except Exception:
            _ok(f"openclaw 已安装：{path}")
        return True
    else:
        _warn("未找到 openclaw 命令")
        _info("请先安装：npm install -g openclaw@latest --registry=https://registry.npmmirror.com")
        return False


def check_mcp_server() -> bool:
    """检查 mcp_server.py 是否存在。"""
    if MCP_SERVER.exists():
        _ok(f"mcp_server.py 已找到：{MCP_SERVER}")
        return True
    else:
        _err(f"未找到 mcp_server.py，预期路径：{MCP_SERVER}")
        return False


def check_config_json() -> bool:
    """检查 sjtu-agent 的 config.json 是否已配置。"""
    config_path = PROJECT_ROOT / "config.json"
    if config_path.exists():
        _ok(f"sjtu-agent config.json 已存在：{config_path}")
        return True
    else:
        _warn(f"未找到 config.json（{config_path}）")
        _info("请先运行 sjtu-agent setup 完成配置")
        return False


def write_openclaw_config(python_exec: str) -> bool:
    """将 sjtu-agent MCP 工具写入 OpenClaw 配置文件。"""
    config_path = _find_openclaw_config()

    if config_path is None:
        # 尝试自动创建默认路径
        default = _openclaw_config_candidates()[0]
        default.parent.mkdir(parents=True, exist_ok=True)
        config_path = default
        existing_cfg: dict = {}
        _info(f"未找到已有配置，将新建：{config_path}")
    else:
        _info(f"找到 OpenClaw 配置：{config_path}")
        try:
            existing_cfg = json.loads(config_path.read_text(encoding="utf-8"))
        except Exception:
            existing_cfg = {}

    # 构建 sjtu-agent 的 MCP 条目
    entry = {
        "command": python_exec,
        "args":    [str(MCP_SERVER)],
        "description": "SJTU 课程助手：查 DDL、查成绩、提交作业、查课表、管理提醒",
    }

    mcp_servers: dict = existing_cfg.setdefault("mcpServers", {})

    if "sjtu-agent" in mcp_servers:
        old = mcp_servers["sjtu-agent"]
        if old.get("args") == entry["args"] and old.get("command") == entry["command"]:
            _ok("sjtu-agent MCP 条目已是最新，无需更改")
            return True
        _info("更新已有的 sjtu-agent MCP 条目")

    mcp_servers["sjtu-agent"] = entry

    try:
        config_path.write_text(
            json.dumps(existing_cfg, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        _ok(f"已写入 OpenClaw 配置：{config_path}")
        return True
    except Exception as e:
        _err(f"写入配置失败：{e}")
        return False


def print_next_steps() -> None:
    print()
    print("══════════════════════════════════════════════════")
    print("  下一步：接入微信")
    print("══════════════════════════════════════════════════")
    print()
    print("  1. 在终端执行以下命令（需要 iPhone，iOS 微信 ≥ 8.0.70）：")
    print()
    print("     npx -y @tencent-weixin/openclaw-weixin-cli@latest install")
    print()
    print("     Windows 用户若报错，改用：")
    print("       openclaw plugins install \"@tencent-weixin/openclaw-weixin\"")
    print("       openclaw config set plugins.entries.openclaw-weixin.enabled true")
    print("       openclaw channels login --channel openclaw-weixin")
    print()
    print("  2. 终端出现二维码后，用微信扫码授权")
    print()
    print("  3. 启动 OpenClaw：")
    print()
    print("     openclaw start")
    print()
    print("  4. 微信搜索「ClawBot」，直接发消息即可使用！")
    print()
    print("  示例消息：")
    print("    「我今天有什么 DDL？」")
    print("    「帮我查一下高数成绩」")
    print("    「我下次物理实验是什么时候？」")
    print()


def main() -> int:
    print()
    print("══════════════════════════════════════════════════")
    print("  sjtu-agent × OpenClaw 配置向导")
    print("══════════════════════════════════════════════════")
    print()

    print("【检查环境】")
    openclaw_ok   = check_openclaw()
    mcp_server_ok = check_mcp_server()
    config_ok     = check_config_json()
    print()

    if not mcp_server_ok:
        _err("缺少 mcp_server.py，请确保在 sjtu-agent 项目根目录下运行本脚本")
        return 1

    python_exec = _detect_python()
    _info(f"使用 Python：{python_exec}")
    print()

    print("【写入 OpenClaw 配置】")
    write_ok = write_openclaw_config(python_exec)
    print()

    if not write_ok:
        return 1

    if not openclaw_ok:
        print("【⚠️  openclaw 未安装，请先安装后重新运行本脚本，或手动完成微信接入步骤】")
    
    if not config_ok:
        print("【⚠️  sjtu-agent 未配置，请先运行 'sjtu-agent setup' 填写交大账号及 API Key】")

    print_next_steps()
    return 0


if __name__ == "__main__":
    sys.exit(main())
