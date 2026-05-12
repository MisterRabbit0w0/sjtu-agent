from __future__ import annotations

import re
from typing import Any, Callable


_THINK_RE = re.compile(r"<think>(.*?)</think>", re.DOTALL)


def _split_thinking(text: str) -> tuple[str, str]:
    reasoning = "\n".join(match.group(1) for match in _THINK_RE.finditer(text)).strip()
    visible = _THINK_RE.sub("", text).strip()
    return visible, reasoning


def stream_tool_loop_openai(
    client: Any,
    model: str,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    run_tool: Callable[..., str],
) -> tuple[str, str]:
    del model, tools, run_tool
    raw_parts: list[str] = []
    for chunk in client.chat.completions.create(stream=True):
        delta = chunk.choices[0].delta
        if getattr(delta, "content", None):
            raw_parts.append(delta.content)
    text, reasoning = _split_thinking("".join(raw_parts))
    messages.append({"role": "assistant", "content": text})
    return text, reasoning


def stream_tool_loop_anthropic(
    client: Any,
    model: str,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    run_tool: Callable[..., str],
) -> str:
    del model, tools, run_tool
    content_blocks: list[dict[str, Any]] = []
    text_parts: list[str] = []
    with client.messages.stream() as stream:
        for event in stream:
            if event.type == "content_block_start":
                block = event.content_block
                if block.type == "thinking":
                    content_blocks.append(
                        {
                            "type": "thinking",
                            "thinking": getattr(block, "thinking", ""),
                            "signature": getattr(block, "signature", ""),
                        }
                    )
                elif block.type == "text":
                    content_blocks.append({"type": "text", "text": ""})
            elif event.type == "content_block_delta":
                delta = event.delta
                block = content_blocks[event.index]
                if delta.type == "thinking_delta":
                    block["thinking"] += delta.thinking
                elif delta.type == "signature_delta":
                    block["signature"] = delta.signature
                elif delta.type == "text_delta":
                    block["text"] += delta.text
                    text_parts.append(delta.text)
    messages.append({"role": "assistant", "content": content_blocks})
    return "".join(text_parts)
