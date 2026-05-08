"""sjtu_agent/agent/chat_loop.py — 配置加载、聊天主循环、程序入口。"""
from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
import datetime as _dt
from pathlib import Path
from dotenv import load_dotenv
from openai import OpenAI

import ddl_checker as dc

from sjtu_agent.paths import AGENT_CONFIG_PATH, ENV_PATH, DDL_CACHE_PATH, PROVIDERS_CONFIG_PATH
from sjtu_agent.terminal_ui import print_markdown_message, print_rule
from sjtu_agent.agent.prompts import SYSTEM_PROMPT
from sjtu_agent.agent.runner import _make_client, _run_one_turn, Spinner, _is_anthropic_model
from sjtu_agent.agent.tools import (
    TOOLS, run_tool, _fetch_ddls_parallel, _ddl_cache_get,
    tool_check_setup, _load_reminders,
)

load_dotenv(ENV_PATH)

_ZHIYUAN_BASE_URL_ENV  = "ZHIYUAN_BASE_URL"
_ZHIYUAN_API_KEY_ENV   = "ZHIYUAN_API_KEY"
_ZHIYUAN_DEFAULT_BASE  = "https://models.sjtu.edu.cn/api/v1"
_ZHIYUAN_DEFAULT_MODEL = "deepseek-chat"


def _prefetch_ddls_background() -> None:
    """在独立子进程中静默预热 DDL 缓存，不阻塞主进程，不向终端输出任何内容。
    子进程的 stdout/stderr 统一重定向到 devnull，完全不干扰主进程终端。
    """
    import subprocess as _sp
    import sys as _sys
    import os as _os

    cached = _ddl_cache_get("False,False,False")
    if cached is not None:
        return  # 缓存仍有效，无需预热

    # 用 -c 片段在子进程里静默执行拉取
    _script = (
        "import sys, os; sys.path.insert(0, os.path.dirname(sys.argv[0]) or '.'); "
        "import agent as _a, ddl_checker as _dc; "
        "_a._fetch_ddls_parallel(_dc.load_config())"
    )
    try:
        _sp.Popen(
            [_sys.executable, "-c", _script],
            stdout=_sp.DEVNULL,
            stderr=_sp.DEVNULL,
            cwd=str(Path(__file__).resolve().parent),
        )
    except Exception:
        pass  # 预热失败不影响主进程




def _check_for_updates() -> None:
    """
    在后台线程中检查 git 远程是否有新提交。
    若检测到更新，启动完成后打印一行提示，引导用户运行 sjtu-agent update。
    非 git 仓库 / 无网络时静默失败，不影响任何功能。
    """
    import shutil as _shutil
    import subprocess as _sub

    git = _shutil.which("git")
    if not git:
        return

    project_root = str(Path(__file__).resolve().parent)
    try:
        # 检查是否在 git 仓库内
        r = _sub.run(
            [git, "rev-parse", "--is-inside-work-tree"],
            cwd=project_root, capture_output=True, timeout=5,
        )
        if r.returncode != 0:
            return

        # 静默 fetch（只更新远端引用，不改变本地分支）
        _sub.run(
            [git, "fetch", "--quiet", "--no-tags", "origin"],
            cwd=project_root, capture_output=True, timeout=15,
        )

        # 比较本地 HEAD 与 origin/HEAD（或 origin/main）
        local_hash = _sub.run(
            [git, "rev-parse", "HEAD"],
            cwd=project_root, capture_output=True, timeout=5,
        ).stdout.decode().strip()

        # 尝试 @{u}（跟踪分支），失败则 origin/main
        r2 = _sub.run(
            [git, "rev-parse", "@{u}"],
            cwd=project_root, capture_output=True, timeout=5,
        )
        if r2.returncode == 0:
            remote_hash = r2.stdout.decode().strip()
        else:
            r3 = _sub.run(
                [git, "rev-parse", "origin/main"],
                cwd=project_root, capture_output=True, timeout=5,
            )
            if r3.returncode != 0:
                return
            remote_hash = r3.stdout.decode().strip()

        if local_hash and remote_hash and local_hash != remote_hash:
            # 统计落后几个提交
            r4 = _sub.run(
                [git, "rev-list", "--count", f"{local_hash}..{remote_hash}"],
                cwd=project_root, capture_output=True, timeout=5,
            )
            behind = r4.stdout.decode().strip() if r4.returncode == 0 else "?"
            # 存入模块级变量，启动完成后打印
            _UPDATE_AVAILABLE["behind"] = behind
    except Exception:
        pass  # 网络不通或其他异常，静默忽略


# 用于在主线程启动完成后读取后台更新检查结果
_UPDATE_AVAILABLE: dict = {}



def _read_agent_config_file() -> dict:
    if not AGENT_CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(AGENT_CONFIG_PATH.read_text(encoding="utf-8-sig"))
    except Exception:
        return {}


def save_agent_config(cfg: dict) -> None:
    """Persist LLM config without transient runtime-only keys."""
    data = {k: v for k, v in cfg.items() if not k.startswith("_")}
    AGENT_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    AGENT_CONFIG_PATH.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def _read_providers_config_file() -> dict:
    if not PROVIDERS_CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(PROVIDERS_CONFIG_PATH.read_text(encoding="utf-8-sig"))
    except Exception:
        return {}


def save_providers_config(data: dict) -> None:
    PROVIDERS_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    PROVIDERS_CONFIG_PATH.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def _provider_slug_from_base(base_url: str, used: set[str] | None = None) -> str:
    used = used or set()
    base = (base_url or "").lower()
    if "models.sjtu.edu.cn" in base:
        name = "zhiyuan"
    elif "zenmux.ai" in base:
        name = "zenmux"
    elif "api.openai.com" in base:
        name = "openai"
    else:
        try:
            from urllib.parse import urlparse
            host = urlparse(base_url).hostname or "provider"
        except Exception:
            host = "provider"
        name = re.sub(r"[^a-zA-Z0-9_-]+", "-", host.split(".")[0].lower()).strip("-") or "provider"
    if name not in used:
        return name
    i = 2
    while f"{name}-{i}" in used:
        i += 1
    return f"{name}-{i}"


def _provider_display_name(name: str) -> str:
    return {
        "zhiyuan": "致远一号",
        "zenmux": "ZenMux",
        "openai": "OpenAI",
    }.get(name, name)


def _provider_from_llm_config(cfg: dict, used: set[str]) -> tuple[str, dict] | None:
    base_url = (cfg.get("base_url") or "").strip().rstrip("/")
    api_key = (cfg.get("api_key") or "").strip()
    if not base_url or not api_key:
        return None
    name = _provider_slug_from_base(base_url, used)
    provider = {
        "display_name": _provider_display_name(name),
        "base_url": base_url,
        "api_key": api_key,
        "models_path": "/models",
        "default_model": cfg.get("model") or _ZHIYUAN_DEFAULT_MODEL,
    }
    if name == "zhiyuan":
        provider["api_key_env"] = _ZHIYUAN_API_KEY_ENV
    return name, provider


def _build_provider_registry_from_legacy() -> dict:
    legacy = _read_agent_config_file()
    providers: dict[str, dict] = {}
    used: set[str] = set()

    primary = _provider_from_llm_config(legacy, used)
    if primary:
        name, provider = primary
        providers[name] = provider
        used.add(name)
        current_provider = name
        current_model = legacy.get("model") or provider.get("default_model")
    else:
        current_provider = "zhiyuan"
        current_model = legacy.get("model") or _ZHIYUAN_DEFAULT_MODEL

    for fallback in legacy.get("fallbacks") or []:
        if not isinstance(fallback, dict):
            continue
        base = (fallback.get("base_url") or "").strip().rstrip("/")
        if any((p.get("base_url") or "").rstrip("/") == base for p in providers.values()):
            continue
        item = _provider_from_llm_config(fallback, used)
        if not item:
            continue
        name, provider = item
        providers[name] = provider
        used.add(name)

    zhiyuan_key = os.environ.get(_ZHIYUAN_API_KEY_ENV, "").strip()
    if zhiyuan_key and "zhiyuan" not in providers:
        providers["zhiyuan"] = {
            "display_name": "致远一号",
            "base_url": os.environ.get(_ZHIYUAN_BASE_URL_ENV, "").strip() or _ZHIYUAN_DEFAULT_BASE,
            "api_key": zhiyuan_key,
            "api_key_env": _ZHIYUAN_API_KEY_ENV,
            "models_path": "/models",
            "default_model": current_model or _ZHIYUAN_DEFAULT_MODEL,
        }
        current_provider = "zhiyuan"

    return {
        "current_provider": current_provider,
        "current_model": current_model or _ZHIYUAN_DEFAULT_MODEL,
        "providers": providers,
    }


def load_providers_config() -> dict:
    data = _read_providers_config_file()
    if not isinstance(data.get("providers"), dict) or not data.get("providers"):
        data = _build_provider_registry_from_legacy()
        save_providers_config(data)

    providers = data.setdefault("providers", {})
    zhiyuan_key = os.environ.get(_ZHIYUAN_API_KEY_ENV, "").strip()
    if zhiyuan_key:
        zhiyuan = providers.setdefault("zhiyuan", {
            "display_name": "致远一号",
            "base_url": os.environ.get(_ZHIYUAN_BASE_URL_ENV, "").strip() or _ZHIYUAN_DEFAULT_BASE,
            "models_path": "/models",
            "default_model": _ZHIYUAN_DEFAULT_MODEL,
        })
        zhiyuan.setdefault("display_name", "致远一号")
        zhiyuan["base_url"] = os.environ.get(_ZHIYUAN_BASE_URL_ENV, "").strip() or zhiyuan.get("base_url") or _ZHIYUAN_DEFAULT_BASE
        zhiyuan["api_key_env"] = _ZHIYUAN_API_KEY_ENV
        zhiyuan.setdefault("api_key", zhiyuan_key)
        data.setdefault("current_provider", "zhiyuan")
        data.setdefault("current_model", zhiyuan.get("default_model") or _ZHIYUAN_DEFAULT_MODEL)

    if not data.get("current_provider") and providers:
        data["current_provider"] = next(iter(providers))
    if not data.get("current_model"):
        cur_provider = providers.get(data.get("current_provider"), {})
        data["current_model"] = cur_provider.get("default_model") or _ZHIYUAN_DEFAULT_MODEL
    return data


def _provider_api_key(provider: dict) -> str:
    env_name = (provider.get("api_key_env") or "").strip()
    if env_name and os.environ.get(env_name, "").strip():
        return os.environ.get(env_name, "").strip()
    return (provider.get("api_key") or "").strip()


def _current_provider_config(registry: dict | None = None) -> dict:
    registry = registry or load_providers_config()
    providers = registry.get("providers") or {}
    provider_id = registry.get("current_provider")
    provider = providers.get(provider_id) or {}
    if not provider and providers:
        provider_id, provider = next(iter(providers.items()))
    return {
        "provider": provider_id or "",
        "base_url": (provider.get("base_url") or "").strip().rstrip("/"),
        "api_key": _provider_api_key(provider),
        "model": registry.get("current_model") or provider.get("default_model") or _ZHIYUAN_DEFAULT_MODEL,
        "models_path": provider.get("models_path") or provider.get("model_endpoint") or "/models",
        "_provider": provider_id or "",
        "_provider_display": provider.get("display_name") or provider_id or "",
        "_from_provider_registry": True,
    }


def _set_current_provider_model(provider_id: str | None = None, model: str | None = None) -> dict:
    registry = load_providers_config()
    providers = registry.get("providers") or {}
    provider_changed = provider_id is not None and provider_id != registry.get("current_provider")
    if provider_id is not None:
        if provider_id not in providers:
            raise ValueError(f"未找到 provider: {provider_id}")
        registry["current_provider"] = provider_id
    current_provider = registry.get("current_provider")
    if model is not None and model.strip():
        registry["current_model"] = _resolve_model_name(model)
    elif provider_changed:
        provider = providers.get(current_provider, {})
        registry["current_model"] = provider.get("default_model") or registry.get("current_model") or _ZHIYUAN_DEFAULT_MODEL
    elif not registry.get("current_model"):
        provider = providers.get(current_provider, {})
        registry["current_model"] = provider.get("default_model") or _ZHIYUAN_DEFAULT_MODEL
    save_providers_config(registry)
    current_cfg = _current_provider_config(registry)
    save_agent_config({
        "provider": current_cfg.get("_provider"),
        "base_url": current_cfg.get("base_url"),
        "api_key": current_cfg.get("api_key"),
        "model": current_cfg.get("model"),
    })
    return current_cfg


def _print_provider_list(registry: dict) -> None:
    current = registry.get("current_provider")
    providers = registry.get("providers") or {}
    if not providers:
        print("  本地 provider 文件为空。可用 /provider add <name> 添加。\n")
        return
    for name, provider in providers.items():
        marker = " *" if name == current else ""
        display = provider.get("display_name") or name
        base = provider.get("base_url") or ""
        print(f"  {name}{marker}  {display}  {base}")
    print()


def load_agent_config() -> dict:
    """加载 Agent LLM 配置，优先级：provider registry > agent_config.json > 空配置。"""
    cfg = _current_provider_config()
    if cfg.get("api_key") and cfg.get("model"):
        return cfg
    return _read_agent_config_file()


def _strip_fallbacks(cfg: dict) -> dict:
    return {k: v for k, v in cfg.items() if k != "fallbacks"}


def get_llm_configs(cfg: dict | None = None) -> list[dict]:
    """Return the primary LLM config followed by any configured fallbacks."""
    cfg = cfg or load_agent_config()
    configs: list[dict] = []
    primary = _strip_fallbacks(cfg)
    if primary.get("api_key") and primary.get("model"):
        configs.append(primary)
    if cfg.get("_from_provider_registry"):
        return configs
    for fallback in cfg.get("fallbacks") or []:
        if not isinstance(fallback, dict):
            continue
        item = _strip_fallbacks(fallback)
        if item.get("api_key") and item.get("model"):
            configs.append(item)
    return configs


_MODEL_ALIASES = {
    "ds": "deepseek-v3.2",
    "deepseek": "deepseek-v3.2",
    "reasoner": "deepseek-reasoner",
    "glm": "glm-5.1",
}


def _resolve_model_name(name: str) -> str:
    stripped = name.strip()
    return _MODEL_ALIASES.get(stripped.lower(), stripped)


def _list_provider_models(cfg: dict) -> tuple[list[str], str]:
    """Fetch model ids from the current OpenAI-compatible provider."""
    if _is_anthropic_model(cfg.get("model", "")):
        return [], "Anthropic 协议暂不支持通过此命令动态列模型，请直接输入模型名切换。"
    base_url = (cfg.get("base_url") or "").strip().rstrip("/")
    api_key = (cfg.get("api_key") or "").strip()
    if not base_url or not api_key:
        return [], "当前 base_url/api_key 不完整，无法列出模型。"
    paths = []
    configured_path = (cfg.get("models_path") or cfg.get("model_endpoint") or "").strip()
    if configured_path:
        paths.append(configured_path)
    paths.extend(["/models", "/model"])
    seen: set[str] = set()
    urls: list[str] = []
    for path in paths:
        url = path if path.startswith(("http://", "https://")) else f"{base_url}/{path.lstrip('/')}"
        if url not in seen:
            urls.append(url)
            seen.add(url)
    last_error = ""
    try:
        import requests as _req
    except ImportError:
        return [], "需要安装 requests 库才能列出模型：pip install requests"
    try:
        payload = None
        for url in urls:
            try:
                resp = _req.get(
                    url,
                    headers={"Authorization": f"Bearer {api_key}"},
                    timeout=20,
                )
                resp.raise_for_status()
                payload = resp.json()
                break
            except Exception as exc:
                last_error = f"{url}: {type(exc).__name__}: {exc}"
        if payload is None:
            return [], f"模型列表请求失败：{last_error}"
    except Exception as exc:
        return [], f"模型列表请求失败：{type(exc).__name__}: {exc}"

    raw_items = payload.get("data") if isinstance(payload, dict) else payload
    if not isinstance(raw_items, list):
        return [], "模型列表响应格式不符合 OpenAI /models 约定。"
    models: list[str] = []
    for item in raw_items:
        if isinstance(item, dict):
            model_id = item.get("id") or item.get("name") or item.get("model")
        else:
            model_id = str(item)
        if model_id:
            models.append(str(model_id))
    return sorted(set(models)), ""


def _reset_chat_history(messages: list, system_content: str | None = None) -> None:
    system_content = system_content or (messages[0].get("content") if messages else SYSTEM_PROMPT)
    messages.clear()
    messages.append({"role": "system", "content": system_content})


def _test_llm_connection_simple(base_url: str, api_key: str, model: str) -> tuple[bool, str]:
    """测试 LLM API 连接是否正常。返回 (ok, error_msg)。"""
    _url = base_url.strip().rstrip("/")
    if _url and not _url.startswith(("http://", "https://")):
        return False, f"Base URL 格式不正确（缺少 http:// 或 https://）：{_url!r}"
    if not api_key.strip():
        return False, "API Key 为空"
    try:
        client = OpenAI(api_key=api_key.strip(), base_url=_url or None)
        client.chat.completions.create(
            model=model.strip(),
            messages=[{"role": "user", "content": "hi"}],
            max_tokens=1,
            timeout=15,
        )
        return True, ""
    except Exception as e:
        err = str(e)
        if "Connection error" in err or "UnsupportedProtocol" in err or "missing an 'http" in err:
            return False, f"无法连接到 API（{_url or 'openai 官方'}），请检查 Base URL"
        if "401" in err or "Unauthorized" in err or "Invalid API key" in err.lower():
            return False, "API Key 无效或已失效"
        if "timeout" in err.lower() or "timed out" in err.lower():
            return False, "连接超时（15s），请检查网络或 Base URL"
        return False, f"连接失败：{err[:120]}"


def setup_agent_config() -> dict:
    print("\n=== SJTU DDL Agent 首次配置 ===")
    print("请填写用于驱动 Agent 的大模型 API 信息")
    print("（支持 DeepSeek、学校超算集群等任意 OpenAI 兼容接口）")
    print("输入 quit / skip 可跳过配置直接进入 Agent（功能受限）\n")

    def _prompt(msg: str) -> str:
        """带退出检测的 input，Ctrl+C / EOF / quit / skip 均触发跳出。"""
        try:
            val = input(msg).strip()
        except (EOFError, KeyboardInterrupt):
            raise SystemExit(0)
        if val.lower() in ("quit", "exit", "skip", "q"):
            raise SystemExit(0)
        return val

    while True:
        try:
            base_url = _prompt("API Base URL（如 https://api.openai.com/v1，回车使用 OpenAI 官方）: ")
            api_key  = _prompt("API Key: ")
            model    = _prompt("模型名称（如 deepseek-chat，回车默认 deepseek-chat）: ") or "deepseek-chat"
        except SystemExit:
            print("\n已跳过 API 配置。部分依赖 LLM 的功能将不可用。")
            print("你可以后续运行 sjtu-agent setup 补充配置，或使用 /model 命令修改。\n")
            # 返回一个"空"配置，让 chat_loop 仍可启动（工具调用不受影响）
            return {"base_url": "", "api_key": "", "model": "deepseek-chat"}

        resolved_url = base_url or "https://api.openai.com/v1"
        print("正在测试 API 连接，请稍候…", end="", flush=True)
        ok, err_msg = _test_llm_connection_simple(resolved_url, api_key, model)
        if ok:
            print(" ✅ 连接成功")
            break
        print(f"\n⚠️  连接测试失败：{err_msg}")
        print("请重新输入（直接回车可重用上次输入的值；输入 quit 可跳过配置）\n")

    cfg = {
        "base_url": resolved_url,
        "api_key":  api_key,
        "model":    model,
    }
    save_agent_config(cfg)
    print("\nAgent 配置已保存。\n")
    return cfg


# ══════════════════════════════════════════════════════════════════════════════
# 主聊天循环
# ══════════════════════════════════════════════════════════════════════════════


def chat_loop(client, model: str, fallback_configs: list[dict] | None = None):
    import datetime as _dt
    _now = _dt.datetime.now()
    _year = _now.year
    _month = _now.month
    # 判断当前学期：9-1月=第1学期(秋), 2-6月=第2学期(春), 7-8月=第3学期(夏)
    if _month >= 9:
        _cur_xnm = _year       # 如 2025（即2025-2026学年）
        _cur_xqm = "1"
        _prev_xnm = _year - 1  # 上学期 = 上一学年第2学期
        _prev_xqm = "2"
    elif _month <= 6:
        _cur_xnm = _year - 1   # 如 2025（即2025-2026学年）
        _cur_xqm = "2"
        _prev_xnm = _year - 1  # 上学期 = 同一学年第1学期
        _prev_xqm = "1"
    else:  # 7-8月
        _cur_xnm = _year - 1
        _cur_xqm = "3"
        _prev_xnm = _year - 1
        _prev_xqm = "2"

    _date_ctx = (
        f"\n\n## 当前时间（自动注入，每次对话刷新）\n"
        f"现在：{_now.strftime('%Y年%m月%d日 %H:%M')}，星期{'一二三四五六日'[_now.weekday()]}。\n"
        f"当前学期：{_cur_xnm}-{_cur_xnm+1}学年第{_cur_xqm}学期。\n"
        f"「上学期」= {_prev_xnm}-{_prev_xnm+1}学年第{_prev_xqm}学期"
        f"（query_grades: year='{_prev_xnm}', semester='{_prev_xqm}'）。\n"
        f"「本学期」= {_cur_xnm}-{_cur_xnm+1}学年第{_cur_xqm}学期"
        f"（query_grades: year='{_cur_xnm}', semester='{_cur_xqm}'）。\n"
        f"「本学年」= {_cur_xnm}学年"
        f"（query_grades: year='{_cur_xnm}', semester=''）。"
    )
    messages = [{"role": "system", "content": SYSTEM_PROMPT + _date_ctx}]
    model_box  = [model]   # 用列表包裹使内部可修改
    client_box = [client]  # 同理，切换模型时可替换 client
    initial_cfg = load_agent_config()
    provider_box = [(initial_cfg.get("_provider") or initial_cfg.get("provider") or "default")]
    fallback_box = [list(fallback_configs or [])]

    # ── 启动时后台预热 DDL 缓存 + 检查更新（完全不阻塞主线程）──────────────────
    _prefetch_ddls_background()
    _update_thread = threading.Thread(target=_check_for_updates, daemon=True)
    _update_thread.start()
    # 不在这里 join()，避免 git fetch 慢网络时卡住启动

    # ── 启动检查：直接调本地函数，无需 LLM roundtrip ─────────────────────────

    print("正在检查配置状态…", flush=True)
    setup = tool_check_setup()
    all_ok = (
        setup["jaccount"]["has_credentials"]
        and setup["canvas"]["has_token"]
        and setup["aihaoke"]["has_cookies"]
        and setup["phycai"]["has_cookies"]
        and setup["icourse"]["has_cookies"]
    )
    if all_ok:
        uname = setup["jaccount"].get("username") or ""
        print(f"✅ 所有平台已就绪（{uname}）\n")
        print("输入问题继续对话，输入 quit 退出。\n")
    else:
        # 有未完成配置，让 LLM 引导
        setup_json = json.dumps(setup, ensure_ascii=False)
        messages.append({
            "role": "user",
            "content": f"配置检查结果：{setup_json}\n请根据结果告知我缺少哪些配置，并引导我完成设置。",
        })
        _run_one_turn(client_box[0], model_box[0], messages, fallback_box[0])
        print("输入问题继续对话，输入 quit 退出。\n")

    # ── 启动时检查即将到期的提醒事项（30分钟内）────────────────────────────
    import datetime as _dt2
    _now2 = _dt2.datetime.now(dc.CST)
    _soon = _now2 + _dt2.timedelta(minutes=30)
    _due_reminders = []
    for _r in _load_reminders():
        for _key in ("start", "end"):
            _ts = _r.get(_key, "")
            if not _ts:
                continue
            try:
                _rdt = _dt2.datetime.fromisoformat(_ts)
                if _rdt.tzinfo is None:
                    _rdt = _rdt.replace(tzinfo=dc.CST)
                if _now2 <= _rdt <= _soon:
                    _due_reminders.append(
                        f"  ⏰ 【{_r['title']}】{'开始' if _key=='start' else '结束'}"
                        f" 于 {_rdt.strftime('%H:%M')}"
                        + (f"（{_r['note']}）" if _r.get("note") else "")
                    )
            except Exception:
                pass
    if _due_reminders:
        print_rule("即将到期的提醒事项（30分钟内）")
        for _line in _due_reminders:
            print(_line)
        print()

    # ── 在第一次等待用户输入前，最多等 2 秒看更新检查结果 ─────────────────
    _update_thread.join(timeout=2)
    if _UPDATE_AVAILABLE.get("behind"):
        behind = _UPDATE_AVAILABLE["behind"]
        print(f"💡 有 {behind} 个新提交可用，运行 sjtu-agent update 即可一键更新。\n")

    while True:
        try:
            user_input = input(f"你[{provider_box[0]}/{model_box[0]}]: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见！")
            break

        if not user_input:
            continue
        if user_input.lower() in ("quit", "exit", "退出", "q"):
            print("再见！")
            break

        if user_input.startswith("/provider"):
            registry = load_providers_config()
            parts = user_input.split()
            subcmd = parts[1].lower() if len(parts) > 1 else ""

            if not subcmd or subcmd in ("help", "-h", "--help", "list", "ls"):
                print_rule("Provider")
                print(f"  当前 provider: {registry.get('current_provider')}")
                print(f"  当前模型: {registry.get('current_model') or model_box[0]}")
                print("  用法:")
                print("    /provider list               列出本地 provider 文件里的供应商")
                print("    /provider <name>             切换供应商")
                print("    /provider <name> <model>     切换供应商并指定模型")
                print("    /provider add <name>         添加供应商到本地文件")
                print("    /provider config [name]      修改供应商 base_url/api_key/model endpoint\n")
                _print_provider_list(registry)
                continue

            if subcmd == "add":
                name = parts[2].strip() if len(parts) > 2 else input("Provider 名称: ").strip()
                if not name:
                    print("  Provider 名称不能为空。\n")
                    continue
                providers = registry.setdefault("providers", {})
                if name in providers:
                    print(f"  Provider 已存在: {name}\n")
                    continue
                display = input(f"显示名称（默认 {name}）: ").strip() or name
                base = input("Base URL（例如 https://models.sjtu.edu.cn/api/v1）: ").strip().rstrip("/")
                key = input("API Key: ").strip()
                path = input("模型列表路径（默认 /models，也兼容 /model）: ").strip() or "/models"
                default_model = input("默认模型（可留空，稍后用 /model 选择）: ").strip()
                providers[name] = {
                    "display_name": display,
                    "base_url": base,
                    "api_key": key,
                    "models_path": path,
                    "default_model": default_model,
                }
                registry["current_provider"] = name
                if default_model:
                    registry["current_model"] = default_model
                save_providers_config(registry)
                print(f"  已添加并切换到 provider: {name}\n")
                continue

            if subcmd in ("config", "edit"):
                name = parts[2].strip() if len(parts) > 2 else registry.get("current_provider")
                provider = (registry.get("providers") or {}).get(name)
                if not provider:
                    print(f"  未找到 provider: {name}\n")
                    continue
                print_rule(f"Provider: {name}")
                new_display = input(f"显示名称（当前: {provider.get('display_name','')}，回车不变）: ").strip()
                new_base = input(f"Base URL（当前: {provider.get('base_url','')}，回车不变）: ").strip()
                new_key = input(f"API Key（当前: {'*'*8 if _provider_api_key(provider) else '未设置'}，回车不变）: ").strip()
                new_path = input(f"模型列表路径（当前: {provider.get('models_path','/models')}，回车不变）: ").strip()
                new_default = input(f"默认模型（当前: {provider.get('default_model','')}，回车不变）: ").strip()
                if new_display:
                    provider["display_name"] = new_display
                if new_base:
                    provider["base_url"] = new_base.rstrip("/")
                if new_key:
                    provider["api_key"] = new_key
                if new_path:
                    provider["models_path"] = new_path
                if new_default:
                    provider["default_model"] = _resolve_model_name(new_default)
                save_providers_config(registry)
                print(f"  已更新 provider: {name}\n")
                continue

            provider_id = parts[1]
            model_name = _resolve_model_name(" ".join(parts[2:])) if len(parts) > 2 else None
            try:
                updated = _set_current_provider_model(provider_id, model_name)
            except ValueError as exc:
                print(f"  {exc}\n")
                continue
            client_box[0] = _make_client(updated)
            provider_box[0] = updated.get("_provider") or provider_id
            model_box[0] = updated["model"]
            fallback_box[0] = []
            _reset_chat_history(messages)
            proto = "Anthropic" if _is_anthropic_model(updated["model"]) else "OpenAI"
            print(f"  已切换到: {provider_box[0]}/{model_box[0]}  [协议: {proto}]（已保存，对话已重置）\n")
            continue

        if user_input.startswith("/model"):
            cur = load_agent_config()
            parts = user_input.split()
            subcmd = parts[1].lower() if len(parts) > 1 else ""

            if not subcmd or subcmd in ("select", "choose"):
                print_rule("模型")
                models, error = _list_provider_models(cur)
                if error:
                    print(f"  {error}\n")
                    continue
                for i, name in enumerate(models, 1):
                    marker = " *" if name == cur.get("model") else ""
                    print(f"  {i:>2}. {name}{marker}")
                choice = input("选择模型编号或输入模型名（回车取消）: ").strip()
                if not choice:
                    print()
                    continue
                if choice.isdigit() and 1 <= int(choice) <= len(models):
                    target_model = models[int(choice) - 1]
                else:
                    target_model = _resolve_model_name(choice)
            elif subcmd in ("list", "ls"):
                print_rule("可用模型")
                models, error = _list_provider_models(cur)
                if error:
                    print(f"  {error}\n")
                else:
                    for name in models:
                        marker = " *" if name == cur.get("model") else ""
                        print(f"  {name}{marker}")
                    print()
                continue
            elif subcmd in ("help", "-h", "--help", "current", "status"):
                print_rule("模型")
                print(f"  当前: {cur.get('_provider') or cur.get('provider')}/{cur.get('model') or model_box[0]}")
                print("  用法:")
                print("    /model                       拉取当前 provider 模型列表并交互选择")
                print("    /model list                  只列出当前 provider 模型")
                print("    /model <model-name>          直接切换模型并保存\n")
                continue
            else:
                target_model = _resolve_model_name(" ".join(parts[1:]))

            updated = _set_current_provider_model(model=target_model)
            client_box[0] = _make_client(updated)
            provider_box[0] = updated.get("_provider") or provider_box[0]
            model_box[0] = updated["model"]
            fallback_box[0] = []
            _reset_chat_history(messages)
            proto = "Anthropic" if _is_anthropic_model(updated["model"]) else "OpenAI"
            print(f"  已切换到: {provider_box[0]}/{model_box[0]}  [协议: {proto}]（已保存，对话已重置）\n")
            continue

        messages.append({"role": "user", "content": user_input})
        try:
            _run_one_turn(client_box[0], model_box[0], messages, fallback_box[0])
        except KeyboardInterrupt:
            print("\n[已中断当前请求，可继续输入]")
            # 移除未完成的 user 消息，保持历史干净
            if messages and messages[-1].get("role") == "user":
                messages.pop()
        except Exception as e:
            print(f"\r[错误] 本轮请求失败（{type(e).__name__}: {e}），请重新输入。")
            # 移除未完成的 user 消息
            if messages and messages[-1].get("role") == "user":
                messages.pop()


def main():
    cfg = load_agent_config()
    if not cfg or not cfg.get("api_key"):
        cfg = setup_agent_config()
    configs = get_llm_configs(cfg)
    if not configs:
        configs = [{
            "base_url": cfg.get("base_url", ""),
            "api_key": cfg.get("api_key", ""),
            "model": cfg.get("model", _ZHIYUAN_DEFAULT_MODEL),
        }]
    primary = configs[0]
    client = _make_client(primary)
    chat_loop(client, primary["model"], configs[1:])


if __name__ == "__main__":
    main()
