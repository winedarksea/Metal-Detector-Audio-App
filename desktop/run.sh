#!/usr/bin/env bash
# Convenience launcher for the desktop test app.
# Assumes the gpu311 conda env has sounddevice installed.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"
conda run --no-capture-output -n gpu311 python desktop/main.py "$@"
