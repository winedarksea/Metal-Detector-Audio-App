# Model Artifacts

## Architecture (v0.3.0)

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

### Future: pretrained audio model (YAMNet)

For even better results, consider using [YAMNet](https://tfhub.dev/google/yamnet/1)
(MobileNet pretrained on AudioSet) as a feature extractor.  YAMNet produces
1024-dim audio embeddings that can be classified with a simple Dense head.
This requires `tensorflow_hub` and internet access for model download.

## Rebuild command

```bash
conda run -n gpu311 python scripts/train_starter_model.py --epochs 40 --batch-size 16
```

## Expected files

- `starter_model.tflite` — quantized TFLite model
- `starter_model_metadata.json` — label order, input config, training params
- `starter_model_metrics.json` — accuracy, loss, window counts
