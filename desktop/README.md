# Desktop Test App (macOS / Linux)

A Python + tkinter desktop mirror of the Android inference screen.
Uses the **same TFLite model** and audio pipeline logic so you can
validate detection behaviour without deploying to an Android device.

## Quick Start

```bash
# From the project root
conda run -n gpu311 pip install sounddevice
conda run -n gpu311 python desktop/main.py
```

Or via the convenience script:

```bash
./desktop/run.sh
```

## Features

- Real-time 16 kHz microphone capture
- Sliding-window inference (0.5 s window, 0.25 s hop → ~4 predictions/sec)
- Sticky TARGET banner (5 s persistence)
- Rolling detection history (last 30 s)
- Live waveform visualisation
- Confidence threshold slider

## Architecture

| File | Responsibility |
|---|---|
| `main.py` | Entry point, wires components together |
| `audio_capture.py` | Mic stream → ring buffer → frame extraction |
| `inference_engine.py` | TFLite model wrapper matching Android pipeline |
| `ui_app.py` | tkinter GUI with waveform + detection display |

## Notes

- This is a **test harness**, not the shipping product. The Android APK remains
  the primary target.
- Audio capture uses `sounddevice` (PortAudio). Grant microphone permission
  when macOS prompts.
- The model file is loaded from `../models/starter_model.tflite` relative to
  this directory.
