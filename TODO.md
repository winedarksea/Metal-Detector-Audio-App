# On Device Audio App TODO

## 0) Immediate Critical Cleanup (Do First)
- [x] Standardize `assets/detector_garden_map.csv` labels so each `sample_id` is unique and machine-readable.
- [x] Ensure every training-eligible audio sample has a clean label record (`target_name`, `class_label`, `pattern`, `mixed_flag`).

## 1) Shared Audio Foundation (Used by Both Paths)
- [x] Create one audio input pipeline: capture -> normalize -> band-limit -> frame.
- [x] Add audio connection check screen as part of inference path display.
  - [x] Live waveform/level meter.
  - [x] Optional speaker passthrough.
  - [x] Signal-present indicator and clipping warning.
- [x] Add test-only mock audio source for feeding `.wav` fixtures.
- [x] Unit tests for normalization, framing, and feature extraction parity.

## 2) Inference Path (Runtime Detection)
- [x] Load model and label metadata at app startup.
- [x] Run continuous inference from the shared pipeline.
- [x] Show top prediction, confidence, and class color (`TARGET`, `JUNK`, `AMBIENT`).
- [x] Add confidence threshold control.
- [x] Add latency and dropped-frame logging for performance checks.
- [x] Integration test: fixture audio -> expected prediction sequence.

## 3) Training Data Collection Path (On-Device Dataset Builder)
- [x] Recording flow: Start -> Stop -> Label -> Save.
- [x] Label form fields:
  - [x] `target_name` (or list when mixed).
  - [x] `class_label` (`TARGET`/`JUNK`/`AMBIENT`).
  - [x] `pattern` (`SWING`/`WIGGLE`).
  - [x] `depth_inches` (optional metadata).
  - [x] `mixed_flag` (metadata only, not model output class).
  - [x] `include_in_training` (default `false` if mixed).
- [x] Data review screen: playback, relabel, include/exclude, delete.
- [ ] Optional chirp crop tool for failed auto-segmentation.
- [x] Export/import dataset bundle (`audio + metadata + split manifest`).

## 4) Build-Time Starter Model (Required)
- [x] Add `scripts/train_starter_model.py` that trains from `/assets` using cleaned labels.
- [x] Fail build if required labels/files are missing or inconsistent.
- [x] Produce versioned artifacts:
  - [ ] `models/starter_model.tflite` (requires TensorFlow locally to run full training command)
  - [x] `models/starter_model_metadata.json`
  - [x] `models/starter_model_metrics.json`
- [x] Document exact command to rebuild starter model.

## 5) Test & Release Gate
- [x] End-to-end headless tests for both paths:
  - [x] Inference path (fixture stream -> predictions).
  - [x] Data collection path (record -> label -> persisted metadata).
  - [x] Starter-model build script (assets -> artifact validation).
- [x] CI gate blocks release if model build or E2E tests fail.
- [x] Prepare Android first; keep iOS compatibility tasks isolated.

## 6) On-Device Fine-Tuning - FUTURE EXTENSION
- [ ] Pre-train gate: show sample counts by class and exclusions.
- [ ] Train only on `include_in_training = true` samples.
- [ ] Checkpoint current model before each training run.
- [ ] Show epoch/loss progress and allow safe cancel.
- [ ] Post-train compare: previous vs new metrics; user chooses keep or revert.

## 7) Quality & Accuracy Improvements
- [ ] **Data Collection:** Create specific capture flow for "Hard Negatives" (handling noise, cable bumps, ground mineralization) to prevent "Energy = Target" bias.
- [ ] **Inference:** Investigate increasing window size (0.5s -> 1.0s) to capture full swing dynamics. (Parameter centralized: change `DEFAULT_WINDOW_SIZE_SAMPLES` in `train_starter_model.py` and `INFERENCE_WINDOW_SIZE_SAMPLES` in `AudioConstants.kt`.)
- [x] **Signal Processing:** Switch from "Per-Window Peak Normalization" to "Fixed-Scale Normalization" (Int16 / 32768.0). Removed per-block peak norm from `SharedAudioPipeline`, removed per-window peak norm from training windows and augmentation, synthetic ambient now uses RMS-scaled amplitude.
- [ ] **Model Architecture:** Evaluate **DS-CNN** (Depthwise Separable CNN) or YAMNet to replace the current vanilla CNN. This reduces ops by ~8x, allowing for a deeper network (more accuracy) at the same latency.
- [ ] **Training:** Revisit `cleanup_csv.py` logic; ensure "Mixed" (Target+Junk) samples don't pollute the `TARGET` class if the Junk signal is dominant.
