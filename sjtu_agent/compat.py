from __future__ import annotations

import sys


def fix_windows_encoding() -> None:
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(errors="replace")
            sys.stderr.reconfigure(errors="replace")
        except Exception:
            pass
