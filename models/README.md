# Model Artifacts

## Architecture (v0.4.0)

**waveform → STFT → mel filterbank → log → 2D CNN → softmax**

- Input: 8 000 samples (0.5 s @ 16 kHz), peak-normalized mono float32.
- STFT: frame_length=256, frame_step=128, fft_length=256.
- 40 mel bins (80 Hz – 7 600 Hz), log-compressed.
- 3-layer CNN (32 → 64 → 64 filters) with BatchNorm + GlobalAvgPool.
- Output: 3-class softmax (TARGET, JUNK, AMBIENT).

## Rebuild command

```bash
conda run -n gpu311 python scripts/export_onnx_cnn_only.py --epochs 20 --batch-size 16
```

## Expected files

- `starter_model.tflite` — waveform-input TFLite model with STFT + mel baked in
- `starter_model_cnn.tflite` — float32 CNN-only TFLite model that consumes log-mel tensors
- `starter_model_cnn_int8.tflite` — int8 CNN-only TFLite model for Android LiteRT `NPU -> GPU -> CPU`
- `starter_model_cnn.onnx` — CNN-only ONNX model for desktop inference
- `starter_model_metadata.json` — label order, input config, artifact file names, accelerator input shape
- `starter_model_metrics.json` — training accuracy/loss plus artifact parity metrics
