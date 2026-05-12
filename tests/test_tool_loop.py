from types import SimpleNamespace

from sjtu_agent.agent.tool_loop import stream_tool_loop_anthropic, stream_tool_loop_openai


class _OpenAICompletions:
    def __init__(self, chunks):
        self._chunks = chunks

    def create(self, **_kwargs):
        return iter(self._chunks)


class _OpenAIClient:
    def __init__(self, chunks):
        self.chat = SimpleNamespace(completions=_OpenAICompletions(chunks))


def _chunk(content):
    delta = SimpleNamespace(content=content, tool_calls=None)
    return SimpleNamespace(choices=[SimpleNamespace(delta=delta)])


def test_openai_think_tags_closed_in_same_chunk_preserve_answer_text():
    messages = [{"role": "user", "content": "hi"}]
    client = _OpenAIClient([_chunk("<think>hidden</think>visible")])

    text, reasoning = stream_tool_loop_openai(client, "test-model", messages, [], lambda *_: "{}")

    assert text == "visible"
    assert reasoning == "hidden"
    assert messages[-1]["content"] == "visible"


class _AnthropicStream:
    def __init__(self, events):
        self._events = events

    def __enter__(self):
        return iter(self._events)

    def __exit__(self, *_args):
        return False


class _AnthropicMessages:
    def __init__(self, events):
        self._events = events

    def stream(self, **_kwargs):
        return _AnthropicStream(self._events)


class _AnthropicClient:
    def __init__(self, events):
        self.messages = _AnthropicMessages(events)


def test_anthropic_thinking_block_is_preserved_in_assistant_history():
    events = [
        SimpleNamespace(
            type="content_block_start",
            content_block=SimpleNamespace(type="thinking", thinking="", signature="sig-1"),
        ),
        SimpleNamespace(
            type="content_block_delta",
            index=0,
            delta=SimpleNamespace(type="thinking_delta", thinking="reasoning "),
        ),
        SimpleNamespace(
            type="content_block_delta",
            index=0,
            delta=SimpleNamespace(type="signature_delta", signature="sig-2"),
        ),
        SimpleNamespace(
            type="content_block_start",
            content_block=SimpleNamespace(type="text"),
        ),
        SimpleNamespace(
            type="content_block_delta",
            index=1,
            delta=SimpleNamespace(type="text_delta", text="answer"),
        ),
    ]
    messages = [{"role": "user", "content": "hi"}]
    client = _AnthropicClient(events)

    text = stream_tool_loop_anthropic(client, "claude-test", messages, [], lambda *_: "{}")

    assert text == "answer"
    assert messages[-1]["content"][0] == {
        "type": "thinking",
        "thinking": "reasoning ",
        "signature": "sig-2",
    }
    assert messages[-1]["content"][1] == {"type": "text", "text": "answer"}

