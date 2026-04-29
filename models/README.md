# Model Artifacts

## Architecture (v0.4.0)

**waveform → STFT → mel filterbank → log → 2D CNN → softmax**

- Input: 8 000 samples (0.5 s @ 16 kHz), peak-normalized mono float32.
- STFT: frame_length=256, frame_step=128, fft_length=256.
- 40 mel bins (80 Hz – 7 600 Hz), log-compressed.
- 3-layer CNN (32 → 64 → 64 filters) with BatchNorm + GlobalAvgPool.
- Output: 3-class softmax (TARGET, JUNK, AMBIENT).

### Why not MobileNetV2 / ImageNet?

The original v0.1.0 model used MobileNetV2 pretrained on ImageNet as a frozen
backbone.  ImageNet features are *not* relevant for audio spectrograms, and the
frozen backbone means only the final Dense layer receives gradient signal —
resulting in ≈ 55 % accuracy (barely above chance for 3 classes).

A small CNN trained directly on mel spectrograms with **energy-gated windowing**
(silent windows from TARGET/JUNK files are discarded) and **data augmentation**
(time-shift + noise injection) reaches much higher accuracy with the same data.

### Future Improvements

#### 1. Depthwise Separable CNN (DS-CNN)
The current model uses standard `Conv2D` layers. Switching to **Depthwise Separable Convolutions**
(as used in MobileNet) would reduce multiply-accumulate operations (MACs) by ~8-9x.
This efficiency gain allows for a deeper network (e.g., 6 layers + residual connections)
to improve accuracy without increasing inference latency.

#### 2. Pretrained audio model (YAMNet)
For maximum robustness, consider using YAMNet
(MobileNet pretrained on AudioSet) as a feature extractor. YAMNet produces
1024-dim audio embeddings that can be classified with a simple Dense head.

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
