#!/usr/bin/env python3
"""Export the CNN-only portion of the mel-CNN model to ONNX for desktop JVM inference.

The full model: waveform → STFT → mel → log → CNN → softmax
ONNX export:    log_mel_spectrogram → CNN → softmax

The STFT/mel/log feature extraction will be computed in Kotlin on the desktop
side, matching the parameters baked into the training script.

Usage:
    conda run -n gpu311 python scripts/export_onnx_cnn_only.py

TODO: make this file share more with train_starter_model.py to avoid duplication.  Maybe refactor the model-building.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Same STFT/mel constants as train_starter_model.py
STFT_FRAME_LENGTH = 256
STFT_FRAME_STEP = 128
FFT_LENGTH = 256
NUM_MEL_BINS = 40
MEL_LOWER_HZ = 80.0
MEL_UPPER_HZ = 7600.0
WINDOW_SIZE = 8000
SAMPLE_RATE = 16000


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--assets-dir", type=Path, default=Path("assets"),
        help="Directory containing training WAV files",
    )
    parser.add_argument(
        "--labels-csv", type=Path, default=Path("assets/cleaned_labels.csv"),
        help="Path to cleaned labels CSV",
    )
    parser.add_argument(
        "--tflite-output", type=Path, default=Path("models/starter_model.tflite"),
        help="Where to write the TFLite model",
    )
    parser.add_argument(
        "--onnx-output", type=Path, default=Path("models/starter_model_cnn.onnx"),
        help="Where to write the CNN-only ONNX model",
    )
    parser.add_argument(
        "--no-mixed", action="store_true",
        help="Exclude all records marked as mixed_flag = True.",
    )
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    import numpy as np
    import tensorflow as tf

    # Re-use the training pipeline to build + train the model
    sys.path.insert(0, str(Path(__file__).parent))
    from train_starter_model import (
        load_label_rows,
        collect_wav_files,
        build_audio_sample_records,
        create_training_windows,
        synthesize_ambient_noise_windows,
        augment_training_data,
        MODEL_OUTPUT_LABELS,
    )

    labels_by_id = load_label_rows(
        args.labels_csv,
        metadata_csv_path=args.assets_dir / "recordings_metadata.csv"
    )
    wav_files = collect_wav_files(args.assets_dir)
    sample_records = build_audio_sample_records(labels_by_id, wav_files)

    if args.no_mixed:
        original_count = len(sample_records)
        sample_records = [r for r in sample_records if not r.mixed_flag]
        print(f"Excluding records where mixed_flag = True. "
              f"Reduced training pool from {original_count} to {len(sample_records)}.")

    label_order = list(MODEL_OUTPUT_LABELS)
    labels_to_index = {label: i for i, label in enumerate(label_order)}

    x_all, y_all, kept, skipped, excluded = create_training_windows(
        sample_records=sample_records,
        labels_to_index=labels_to_index,
        sample_rate=SAMPLE_RATE,
        window_size=WINDOW_SIZE,
        hop_size=WINDOW_SIZE // 2,
        rms_gate_threshold=0.015,
    )
    if "AMBIENT" in labels_to_index:
        non_ambient = kept["TARGET"] + kept["JUNK"]
        synth_count = max(1, round(non_ambient * 0.35))
        ambient_windows = synthesize_ambient_noise_windows(synth_count, WINDOW_SIZE, 42)
        ambient_labels = np.full(synth_count, labels_to_index["AMBIENT"], dtype=np.int64)
        x_all = np.concatenate([x_all, ambient_windows], axis=0)
        y_all = np.concatenate([y_all, ambient_labels], axis=0)
        kept["AMBIENT"] += synth_count

    print(f"Training windows per output class: {kept}")
    print(f"Excluded class counts: {excluded}")

    # Augment + shuffle
    x_aug, y_aug = augment_training_data(x_all, y_all)
    perm = np.random.default_rng(42).permutation(x_aug.shape[0])
    x_aug, y_aug = x_aug[perm], y_aug[perm]

    # ---- Build full model (same architecture as training script) ----
    num_spectrogram_bins = FFT_LENGTH // 2 + 1  # 129
    mel_weight_matrix = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=NUM_MEL_BINS,
        num_spectrogram_bins=num_spectrogram_bins,
        sample_rate=float(SAMPLE_RATE),
        lower_edge_hertz=MEL_LOWER_HZ,
        upper_edge_hertz=MEL_UPPER_HZ,
    )

    waveform_input = tf.keras.Input(shape=(WINDOW_SIZE,), dtype=tf.float32, name="waveform")

    x = tf.keras.layers.Lambda(
        lambda s: tf.abs(tf.signal.stft(s, frame_length=STFT_FRAME_LENGTH,
                                        frame_step=STFT_FRAME_STEP,
                                        fft_length=FFT_LENGTH)),
        name="stft_magnitude",
    )(waveform_input)

    x = tf.keras.layers.Lambda(
        lambda mag: tf.tensordot(mag, mel_weight_matrix, axes=1),
        name="mel_projection",
    )(x)

    x = tf.keras.layers.Lambda(
        lambda mel: tf.math.log(mel + 1e-6),
        name="log_mel",
    )(x)

    x = tf.keras.layers.Lambda(
        lambda t: tf.expand_dims(t, axis=-1),
        name="add_channel",
    )(x)

    # CNN layers — these are the ones we want to export
    x = tf.keras.layers.Conv2D(32, (3, 3), activation="relu", padding="same", name="conv1")(x)
    x = tf.keras.layers.BatchNormalization(name="bn1")(x)
    x = tf.keras.layers.MaxPooling2D((2, 2), name="pool1")(x)

    x = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv2")(x)
    x = tf.keras.layers.BatchNormalization(name="bn2")(x)
    x = tf.keras.layers.MaxPooling2D((2, 2), name="pool2")(x)

    x = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv3")(x)
    x = tf.keras.layers.BatchNormalization(name="bn3")(x)
    x = tf.keras.layers.GlobalAveragePooling2D(name="gap")(x)

    x = tf.keras.layers.Dense(64, activation="relu", name="dense1")(x)
    x = tf.keras.layers.Dropout(0.3, name="dropout")(x)
    outputs = tf.keras.layers.Dense(
        len(label_order), activation="softmax", name="class_probs"
    )(x)

    full_model = tf.keras.Model(inputs=waveform_input, outputs=outputs)
    full_model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )

    # Train
    print(f"Training on {x_aug.shape[0]} windows...")
    full_model.fit(
        x_aug, y_aug,
        validation_data=(x_all, y_all),
        epochs=args.epochs,
        batch_size=args.batch_size,
        verbose=2,
    )

    eval_loss, eval_acc = full_model.evaluate(x_all, y_all, verbose=0)
    print(f"Validation: loss={eval_loss:.4f}, accuracy={eval_acc:.4f}")

    # ---- Save TFLite (full model with STFT for Android) ----
    converter = tf.lite.TFLiteConverter.from_keras_model(full_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    args.tflite_output.parent.mkdir(parents=True, exist_ok=True)
    args.tflite_output.write_bytes(tflite_bytes)
    print(f"Wrote TFLite: {args.tflite_output} ({len(tflite_bytes)} bytes)")

    # ---- Extract CNN-only sub-model ----
    # Find the add_channel layer output — that's where mel spectrogram features
    # enter the CNN.  Input shape: [batch, time_frames, mel_bins, 1]
    add_channel_layer = full_model.get_layer("add_channel")
    add_channel_output_shape = add_channel_layer.output.shape[1:]  # (time, mel, 1)
    print(f"CNN input shape (after mel): {add_channel_output_shape}")

    # Build a new model that takes mel spectrogram input directly
    mel_input = tf.keras.Input(
        shape=add_channel_output_shape,
        dtype=tf.float32,
        name="log_mel_spectrogram",
    )

    # Re-wire through existing trained layers
    cnn_x = full_model.get_layer("conv1")(mel_input)
    cnn_x = full_model.get_layer("bn1")(cnn_x)
    cnn_x = full_model.get_layer("pool1")(cnn_x)
    cnn_x = full_model.get_layer("conv2")(cnn_x)
    cnn_x = full_model.get_layer("bn2")(cnn_x)
    cnn_x = full_model.get_layer("pool2")(cnn_x)
    cnn_x = full_model.get_layer("conv3")(cnn_x)
    cnn_x = full_model.get_layer("bn3")(cnn_x)
    cnn_x = full_model.get_layer("gap")(cnn_x)
    cnn_x = full_model.get_layer("dense1")(cnn_x)
    # Skip dropout for inference
    cnn_x = full_model.get_layer("class_probs")(cnn_x)

    cnn_model = tf.keras.Model(inputs=mel_input, outputs=cnn_x)
    cnn_model.summary()

    # Verify CNN-only model produces same output as full model
    test_waveform = x_all[:1]
    full_pred = full_model.predict(test_waveform, verbose=0)

    # Compute mel spectrogram features through the full model's layers
    mel_extractor = tf.keras.Model(
        inputs=full_model.input,
        outputs=add_channel_layer.output,
    )
    test_mel = mel_extractor.predict(test_waveform, verbose=0)
    cnn_pred = cnn_model.predict(test_mel, verbose=0)

    print(f"Full model pred:     {full_pred[0]}")
    print(f"CNN-only model pred: {cnn_pred[0]}")
    max_diff = np.max(np.abs(full_pred - cnn_pred))
    print(f"Max prediction diff: {max_diff:.8f}")
    assert max_diff < 1e-4, f"Predictions diverge: {max_diff}"

    # ---- Export CNN-only model to ONNX ----
    import tf2onnx
    import onnx

    # Save as SavedModel first, then convert
    saved_model_dir = str(args.onnx_output.parent / "cnn_saved_model")
    cnn_model.export(saved_model_dir)

    onnx_model, _ = tf2onnx.convert.from_keras(
        cnn_model,
        input_signature=[tf.TensorSpec(shape=(1,) + add_channel_output_shape, dtype=tf.float32, name="log_mel_spectrogram")],
        opset=13,
    )
    onnx.save(onnx_model, str(args.onnx_output))
    print(f"Wrote ONNX: {args.onnx_output}")

    # Quick ONNX validation
    import onnxruntime as ort
    sess = ort.InferenceSession(str(args.onnx_output))
    input_name = sess.get_inputs()[0].name
    onnx_pred = sess.run(None, {input_name: test_mel.astype(np.float32)})[0]
    print(f"ONNX pred:           {onnx_pred[0]}")
    onnx_diff = np.max(np.abs(full_pred - onnx_pred))
    print(f"ONNX vs full diff:   {onnx_diff:.8f}")

    print("\nDone. Desktop will compute mel spectrogram in Kotlin, feed to ONNX CNN.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
