#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
PYTHON_CMD="python3"
INSTALL_PLAYWRIGHT=1
RUN_SETUP=1
FORCE_RECREATE_VENV=0

print_help() {
  cat <<'EOF'
SJTU Agent 一键安装脚本

用法:
  ./install.sh [选项]

选项:
  --python <cmd>          指定 Python 可执行文件，默认 python3
  --skip-playwright       跳过 Playwright Chromium 安装
  --no-setup              安装完成后不自动启动 sjtu-agent setup
  --force-recreate-venv   强制重建 .venv
  -h, --help              显示帮助
EOF
}

log() {
  printf '\n[%s] %s\n' "sjtu-agent-install" "$*"
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --python)
      [[ $# -ge 2 ]] || fail "--python 需要一个参数"
      PYTHON_CMD="$2"
      shift 2
      ;;
    --skip-playwright)
      INSTALL_PLAYWRIGHT=0
      shift
      ;;
    --no-setup)
      RUN_SETUP=0
      shift
      ;;
    --force-recreate-venv)
      FORCE_RECREATE_VENV=1
      shift
      ;;
    -h|--help)
      print_help
      exit 0
      ;;
    *)
      fail "未知参数: $1"
      ;;
  esac
done

[[ -f "$PROJECT_DIR/pyproject.toml" ]] || fail "请从仓库根目录运行这个脚本。"
command -v "$PYTHON_CMD" >/dev/null 2>&1 || fail "未找到 Python: $PYTHON_CMD"

"$PYTHON_CMD" - <<'PY'
import sys

if sys.version_info < (3, 10):
    raise SystemExit("Python 3.10 或更高版本是必需的。")
PY

VENV_DIR="$PROJECT_DIR/.venv"
VENV_PY="$VENV_DIR/bin/python"

if [[ $FORCE_RECREATE_VENV -eq 1 && -d "$VENV_DIR" ]]; then
  log "按要求重建虚拟环境: $VENV_DIR"
  rm -rf "$VENV_DIR"
fi

if [[ -d "$VENV_DIR" && ! -x "$VENV_PY" ]]; then
  log "检测到损坏的虚拟环境，准备重建: $VENV_DIR"
  rm -rf "$VENV_DIR"
fi

if [[ ! -d "$VENV_DIR" ]]; then
  log "创建虚拟环境: $VENV_DIR"
  "$PYTHON_CMD" -m venv "$VENV_DIR"
fi

log "升级 pip"
"$VENV_PY" -m pip install --upgrade pip

log "安装 SJTU Agent"
"$VENV_PY" -m pip install -e "$PROJECT_DIR"

if [[ $INSTALL_PLAYWRIGHT -eq 1 ]]; then
  log "安装 Playwright Chromium"
  "$VENV_PY" -m playwright install chromium
fi

if [[ $RUN_SETUP -eq 1 ]]; then
  log "启动 sjtu-agent setup"
  exec "$VENV_PY" -m sjtu_agent setup
fi

cat <<EOF

安装完成。

后续常用命令：
  source "$VENV_DIR/bin/activate"
  python -m sjtu_agent setup
  python -m sjtu_agent
EOF