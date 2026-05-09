"""Notification helpers shared by reports, digests, and reminders."""
from __future__ import annotations

from dataclasses import dataclass
import base64
import json
import random
import sys
import uuid

import requests

from sjtu_agent import paths as _paths
from sjtu_agent.paths import read_json_safe


@dataclass(frozen=True)
class NotifyResult:
    """Result returned by a notification channel."""

    channel: str
    ok: bool
    skipped: bool = False
    message: str = ""


def _chunks(text: str, max_len: int) -> list[str]:
    if not text:
        return []
    return [text[i : i + max_len] for i in range(0, len(text), max_len)]


def _parse_chat_ids(raw_ids) -> list[int]:
    if isinstance(raw_ids, str):
        candidates = raw_ids.replace(",", " ").split()
    elif isinstance(raw_ids, int) and not isinstance(raw_ids, bool):
        candidates = [raw_ids]
    else:
        try:
            candidates = list(raw_ids or [])
        except TypeError:
            candidates = []

    chat_ids: list[int] = []
    for value in candidates:
        try:
            chat_ids.append(int(value))
        except (TypeError, ValueError):
            continue
    return chat_ids


class NotificationDispatcher:
    """Send notifications through configured user channels."""

    def __init__(self, config: dict | None = None):
        self.config = config if config is not None else read_json_safe(_paths.CONFIG_PATH, default={})

    def send(
        self,
        title: str,
        body: str,
        *,
        channels: list[str] | tuple[str, ...] | None = None,
        priority: str = "normal",
        telegram_parse_mode: str = "HTML",
    ) -> dict[str, NotifyResult]:
        """Dispatch a message to the requested channels.

        ``priority`` is accepted for callers that already model notification
        urgency; current channels do not need special handling for it yet.
        """
        del priority
        selected = tuple(channels or ("telegram",))
        text = body or title
        results: dict[str, NotifyResult] = {}
        for channel in selected:
            if channel == "telegram":
                results[channel] = self.send_telegram(text, parse_mode=telegram_parse_mode)
            elif channel == "wechat":
                results[channel] = self.send_wechat(text)
            else:
                results[channel] = NotifyResult(channel=channel, ok=False, skipped=True, message="unsupported channel")
        return results

    def send_telegram(
        self,
        text: str,
        *,
        parse_mode: str = "HTML",
        disable_web_page_preview: bool = False,
        max_len: int = 4000,
    ) -> NotifyResult:
        token = self.config.get("telegram_token", "")
        raw_ids = self.config.get("telegram_allowed_ids", [])
        allowed_ids = _parse_chat_ids(raw_ids)

        if not token or not allowed_ids:
            return NotifyResult("telegram", ok=False, skipped=True, message="Telegram 未配置")

        errors: list[str] = []
        for uid in allowed_ids:
            for chunk in _chunks(text, max_len):
                try:
                    resp = requests.post(
                        f"https://api.telegram.org/bot{token}/sendMessage",
                        json={
                            "chat_id": uid,
                            "text": chunk,
                            "parse_mode": parse_mode,
                            "disable_web_page_preview": disable_web_page_preview,
                        },
                        timeout=15,
                    )
                    if not resp.ok:
                        errors.append(f"uid={uid}: {resp.text[:200]}")
                except Exception as exc:
                    errors.append(f"uid={uid}: {exc}")

        return NotifyResult("telegram", ok=not errors, message="; ".join(errors))

    def send_wechat(self, text: str, *, max_len: int = 2000) -> NotifyResult:
        token = self.config.get("wechat_bot_token", "")
        to_user = self.config.get("wechat_to_user_id", "")
        context_token = self.config.get("wechat_context_token", "")
        if not token or not to_user or not context_token:
            return NotifyResult(
                "wechat",
                ok=False,
                skipped=True,
                message="微信未配置（需要 wechat_bot_token / wechat_to_user_id / wechat_context_token）",
            )

        if str(_paths.PROJECT_ROOT) not in sys.path:
            sys.path.insert(0, str(_paths.PROJECT_ROOT))

        try:
            from wechat_bot import ILinkClient
        except ImportError:
            return self._send_wechat_fallback(text, token, to_user, context_token, max_len=max_len)

        client = ILinkClient(token)
        errors: list[str] = []
        for chunk in _chunks(text, max_len):
            try:
                client.send(chunk, to_user_id=to_user, context_token=context_token)
            except Exception as exc:
                errors.append(str(exc))

        return NotifyResult("wechat", ok=not errors, message="; ".join(errors))

    def _send_wechat_fallback(
        self,
        text: str,
        token: str,
        to_user: str,
        context_token: str,
        *,
        max_len: int,
    ) -> NotifyResult:
        try:
            import httpx
        except ImportError as exc:
            return NotifyResult("wechat", ok=False, message=f"httpx 不可用：{exc}")

        errors: list[str] = []
        headers = {
            "Content-Type": "application/json",
            "AuthorizationType": "ilink_bot_token",
            "Authorization": f"Bearer {token}",
            "X-WECHAT-UIN": base64.b64encode(str(random.randint(0, 0xFFFFFFFF)).encode()).decode(),
        }
        for chunk in _chunks(text, max_len):
            body = {
                "base_info": {"channel_version": "1.0.3"},
                "msg": {
                    "from_user_id": "",
                    "to_user_id": to_user,
                    "client_id": f"bot-{uuid.uuid4().hex[:12]}",
                    "message_type": 2,
                    "message_state": 2,
                    "context_token": context_token,
                    "item_list": [{"type": 1, "text_item": {"text": chunk}}],
                },
            }
            raw = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Length"] = str(len(raw))
            try:
                resp = httpx.post(
                    "https://ilinkai.weixin.qq.com/ilink/bot/sendmessage",
                    content=raw,
                    headers=headers,
                    timeout=35,
                )
                if resp.status_code != 200:
                    errors.append(resp.text[:200])
            except Exception as exc:
                errors.append(str(exc))

        return NotifyResult("wechat", ok=not errors, message="; ".join(errors))
