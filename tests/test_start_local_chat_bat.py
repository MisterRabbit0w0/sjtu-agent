from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_start_local_chat_compiles_sources_before_launching_chat() -> None:
    script = (ROOT / "start-local-chat.bat").read_text(encoding="utf-8")

    assert 'set "PYTHON_EXE=%~dp0.venv\\Scripts\\python.exe"' in script
    assert 'if not exist "%PYTHON_EXE%" (' in script
    assert '"%PYTHON_EXE%" -m compileall -q sjtu_agent' in script
    assert "[sjtu-agent] Python source compilation failed." in script
    assert 'if not "%COMPILE_EXIT_CODE%"=="0" (' in script
    assert '"%SJTU_AGENT_EXE%" chat' in script
