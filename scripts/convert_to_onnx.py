#!/usr/bin/env python3
"""Convert the TFLite starter model to ONNX format for desktop JVM inference."""
import subprocess
import sys

result = subprocess.run(
    [sys.executable, "-m", "tf2onnx.convert",
     "--tflite", "models/starter_model.tflite",
     "--output", "models/starter_model.onnx",
     "--opset", "13"],
    capture_output=True, text=True
)
print(result.stdout)
if result.stderr:
    print(result.stderr, file=sys.stderr)
sys.exit(result.returncode)
