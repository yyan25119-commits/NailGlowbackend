#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PYTHON_COMMAND=${PYTHON_COMMAND:-python3.13}
VENV_DIR="$PROJECT_ROOT/.venv"

if [ ! -x "$VENV_DIR/bin/python" ]; then
    "$PYTHON_COMMAND" -m venv "$VENV_DIR"
fi

"$VENV_DIR/bin/python" -m pip install \
    -r "$PROJECT_ROOT/requirements-agent-graph.txt" \
    -r "$PROJECT_ROOT/requirements-trend-agent.txt" \
    -r "$PROJECT_ROOT/requirements-score-model.txt"

echo "Project Python environment ready: $VENV_DIR"
