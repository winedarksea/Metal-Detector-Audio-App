# Metal Detector Audio App

Android-first, minimal on-device audio app for metal detector signals with two core paths:
- Runtime inference (`TARGET` / `JUNK` / `AMBIENT`) from live audio.
- High-quality labeled recording workflow for building a training dataset.

## What is implemented

### Inference path
- Shared pipeline: `capture -> normalize -> band-limit -> frame`.
- Connection check integrated into inference screen:
  - live RMS level meter
  - live waveform preview
  - signal-present indicator
  - clipping warning
  - optional speaker passthrough
- Continuous inference loop with thresholding.
- Real-time guardrails:
  - dropped-frame accounting (drops windows when classifier is behind)
  - latency/inference-time stats
- Color-coded prediction output:
  - `TARGET` green
  - `JUNK` red
  - `AMBIENT` gray
- Model and metadata load at startup from `models/`.
- Starter model loading is strict by default. If model/metadata/ONNX assets are missing, startup fails fast instead of silently switching to fallback heuristics.

### Data collection path
- Recording flow: `Start -> Stop -> Label -> Save`.
- 48kHz mono PCM16 WAV capture for high-quality training reuse.
- Label fields:
  - `target_name` (supports multiple names, each in `category:object:material` form)
  - `class_label`
  - `pattern`
  - `depth_inches`
  - `notes` (short free-text description)
  - `gps_latitude`, `gps_longitude` (captured via `Use Current GPS`)
  - `mixed_flag`
  - `include_in_training` (auto-disabled by default when `mixed_flag=true`)
- Data review screen:
  - playback
  - relabel names and class
  - include/exclude toggle
  - delete
- Dataset bundle export/import (zip):
  - `audio/*.wav`
  - `metadata/recordings_metadata.json`
  - `metadata/split_manifest.json`

### Build-time starter model pipeline
- `scripts/train_starter_model.py` validates assets and trains a starter mel-spectrogram CNN.
- Class strategy assessment outcome: explicit 3-class output (`TARGET`, `JUNK`, `AMBIENT`) is currently more reliable than binary + ambient-threshold negatives on this dataset.
- Adds synthetic `AMBIENT` windows (white + Brownian noise) by default to reduce false-positive bias when real ambient captures are sparse.
- Enforces strict CSV/WAV consistency checks.
- Emits versioned artifacts:
  - `models/starter_model.tflite`
  - `models/starter_model_metadata.json`
  - `models/starter_model_metrics.json`
- Gradle `preBuild` runs validation (`--dry-run`) and fails on inconsistent labels/assets.

## Project layout

- `app/` Android app (Jetpack Compose + TFLite runtime)
- `desktopApp/` Kotlin Multiplatform desktop app (Compose Desktop)
- `assets/` source WAV + label CSV
- `models/` generated model artifacts
- `scripts/train_starter_model.py` starter-model training pipeline
- `tests/` python validation tests for build-time model script

## Data Labeling navigation and storage

### Desktop (`desktopApp`)
- Bottom navigation includes `Detect`, `Record`, and `Review`.
- Use `Record` for `Start -> Stop -> Label -> Save`.
- Use `Review` for playback, relabeling, include/exclude toggles, delete, and bundle export/import.
- Local dataset path:
  - `~/.metaldetector-audio/dataset/audio/*.wav`
  - `~/.metaldetector-audio/dataset/recordings_metadata.json`

### Android (`app`)
- Bottom navigation includes `Detect`, `Record`, and `Review`.
- Use `Record` for capture + labeling, then `Review` for export/import.
- Local dataset path (private app storage):
  - `<filesDir>/dataset/audio/*.wav`
  - `<filesDir>/dataset/recordings_metadata.json`
  - Typical absolute path: `/data/user/0/<package-name>/files/dataset/...`
- Because Android app storage is private, use `Review -> Export Bundle` to move data off-device for training.

### Bundle format (Desktop + Android)
- `audio/*.wav`
- `metadata/recordings_metadata.json`
- `metadata/split_manifest.json`

## Starter model commands

### 1) Validate dataset consistency (no TensorFlow required)
```bash
python3 scripts/train_starter_model.py --dry-run
```

### 2) Train and export the starter model
```bash
conda run -n gpu311 python scripts/train_starter_model.py \
  --epochs 20 \
  --batch-size 8
```

Disable synthetic ambient generation if needed:
```bash
conda run -n gpu311 python scripts/train_starter_model.py --no-synthesize-ambient-noise
```

Export desktop ONNX (CNN-only):
```bash
conda run -n gpu311 python scripts/export_onnx_cnn_only.py --epochs 20 --batch-size 16
```

If TensorFlow is not installed:
```bash
python3 -m pip install tensorflow numpy scipy
```

## Test commands

Python:
```bash
python3 -m unittest tests.test_train_starter_model
```

Android unit tests (from repo root):
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
```
Requires Android SDK configured (`ANDROID_HOME` or `local.properties` with `sdk.dir=...`).

Desktop KMP build (from repo root):
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:desktopJar
```

Run locally
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:run
```

## Notes

- `desktop/` (Python tkinter app) is an inference-only test harness and does not include Record/Review data-labeling UI.
- Current `assets/` has no real recorded `AMBIENT` WAV files; synthetic ambient windows are used to balance the starter model.
- Initial release intentionally skips on-device fine-tuning UI/engine. Dataset capture + export is implemented so training can run off-device and ship new model artifacts.
