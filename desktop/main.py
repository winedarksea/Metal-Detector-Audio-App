#!/usr/bin/env python3
"""Entry point for the Metal Detector Audio desktop test application.

Usage
-----
    conda run -n gpu311 python desktop/main.py

Loads the TFLite starter model from ``models/`` and opens a tkinter
window that mirrors the Android InferenceScreen.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Resolve project paths regardless of cwd.
_DESKTOP_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _DESKTOP_DIR.parent
_MODEL_DIR = _PROJECT_ROOT / "models"

# Ensure the desktop package is importable when invoked via ``python desktop/main.py``
if str(_DESKTOP_DIR) not in sys.path:
    sys.path.insert(0, str(_DESKTOP_DIR))


def main() -> None:
    from inference_engine import InferenceEngine
    from ui_app import MetalDetectorDesktopApp

    if not (_MODEL_DIR / "starter_model.tflite").exists():
        print(
            f"ERROR: model not found at {_MODEL_DIR / 'starter_model.tflite'}\n"
            "Run the training script first:\n"
            "  conda run -n gpu311 python scripts/train_starter_model.py --epochs 40"
        )
        sys.exit(1)

    print(f"Loading model from {_MODEL_DIR} …")
    engine = InferenceEngine(_MODEL_DIR)
    print(
        f"Model loaded — {len(engine.labels)} classes: {engine.labels}, "
        f"window={engine.window_size} samples"
    )

    app = MetalDetectorDesktopApp(engine)
    app.run()


if __name__ == "__main__":
    main()
