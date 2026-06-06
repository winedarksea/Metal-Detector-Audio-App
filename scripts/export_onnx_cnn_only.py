#!/usr/bin/env python3
"""Export the CNN-only portion of the mel-CNN model to ONNX for desktop JVM inference.

The full model: waveform → STFT → mel → log → CNN → softmax
ONNX export:    log_mel_spectrogram → CNN → softmax

The STFT/mel/log feature extraction will be computed in Kotlin on the desktop
side, matching the parameters baked into the training script.

This is the production script for the models for the current apps.

Usage:
    conda run -n gpu311 python scripts/export_onnx_cnn_only.py
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

try:
    from .mel_cnn_pipeline import (
        DEFAULT_SAMPLE_RATE_HZ,
        DEFAULT_WINDOW_SIZE_SAMPLES,
        build_log_mel_feature_extractor,
        convert_keras_model_to_tflite,
        extract_cnn_only_model,
        mel_spectrogram_metadata,
        run_tflite_predictions,
        STARTER_MODEL_VERSION,
        train_and_convert_tflite,
    )
    from .train_starter_model import (
        MODEL_OUTPUT_LABELS,
        augment_training_data,
        build_audio_sample_records,
        collect_wav_files,
        create_training_windows,
        load_label_rows,
        synthesize_ambient_noise_windows,
    )
except ImportError:
    from mel_cnn_pipeline import (
        DEFAULT_SAMPLE_RATE_HZ,
        DEFAULT_WINDOW_SIZE_SAMPLES,
        build_log_mel_feature_extractor,
        convert_keras_model_to_tflite,
        extract_cnn_only_model,
        mel_spectrogram_metadata,
        run_tflite_predictions,
        STARTER_MODEL_VERSION,
        train_and_convert_tflite,
    )
    from train_starter_model import (
        MODEL_OUTPUT_LABELS,
        augment_training_data,
        build_audio_sample_records,
        collect_wav_files,
        create_training_windows,
        load_label_rows,
        synthesize_ambient_noise_windows,
    )


def write_json(path: Path, content: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")


def summarize_prediction_alignment(reference_predictions, candidate_predictions, labels):
    import numpy as np

    candidate_top1 = np.argmax(candidate_predictions, axis=1)
    reference_top1 = np.argmax(reference_predictions, axis=1)
    return {
        "top1_accuracy": float(np.mean(candidate_top1 == labels)),
        "top1_agreement_vs_reference": float(np.mean(candidate_top1 == reference_top1)),
        "max_abs_diff_vs_reference": float(np.max(np.abs(reference_predictions - candidate_predictions))),
        "mean_abs_diff_vs_reference": float(np.mean(np.abs(reference_predictions - candidate_predictions))),
    }


def load_onnx_export_dependencies():
    try:
        import onnx
        import onnxruntime as ort
        import tf2onnx
    except ModuleNotFoundError as error:
        raise ModuleNotFoundError(
            "ONNX export requires tf2onnx, onnx, and onnxruntime in the active "
            "Python environment. Install them before running this script."
        ) from error

    return tf2onnx, onnx, ort


def build_onnx_export_function(cnn_model, input_shape):
    import tensorflow as tf

    input_signature = [
        tf.TensorSpec(
            shape=(None,) + input_shape,
            dtype=tf.float32,
            name="log_mel_spectrogram",
        )
    ]

    @tf.function(input_signature=input_signature)
    def serving_default(log_mel_spectrogram):
        return {"class_probs": cnn_model(log_mel_spectrogram, training=False)}

    return serving_default, input_signature


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
        "--accelerator-float-output",
        type=Path,
        default=Path("models/starter_model_cnn.tflite"),
        help="Where to write the CNN-only float32 TFLite model",
    )
    parser.add_argument(
        "--accelerator-int8-output",
        type=Path,
        default=Path("models/starter_model_cnn_int8.tflite"),
        help="Where to write the CNN-only int8 TFLite model for LiteRT accelerators",
    )
    parser.add_argument(
        "--metadata-output",
        type=Path,
        default=Path("models/starter_model_metadata.json"),
        help="Where to write model metadata with artifact descriptors",
    )
    parser.add_argument(
        "--metrics-output",
        type=Path,
        default=Path("models/starter_model_metrics.json"),
        help="Where to write model metrics and artifact parity measurements",
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

    tf2onnx, onnx, ort = load_onnx_export_dependencies()

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
        sample_rate=DEFAULT_SAMPLE_RATE_HZ,
        window_size=DEFAULT_WINDOW_SIZE_SAMPLES,
        hop_size=DEFAULT_WINDOW_SIZE_SAMPLES // 2,
        rms_gate_threshold=0.015,
    )
    if "AMBIENT" in labels_to_index:
        non_ambient = kept["TARGET"] + kept["JUNK"]
        synth_count = max(1, round(non_ambient * 0.35))
        ambient_windows = synthesize_ambient_noise_windows(
            synth_count,
            DEFAULT_WINDOW_SIZE_SAMPLES,
            42,
        )
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

    tflite_bytes, numeric_metrics, full_model = train_and_convert_tflite(
        x_train=x_aug,
        y_train=y_aug,
        x_val=x_all,
        y_val=y_all,
        num_classes=len(label_order),
        window_size=DEFAULT_WINDOW_SIZE_SAMPLES,
        sample_rate=DEFAULT_SAMPLE_RATE_HZ,
        epochs=args.epochs,
        batch_size=args.batch_size,
    )
    print(
        "Validation: "
        f"loss={numeric_metrics['loss']:.4f}, accuracy={numeric_metrics['accuracy']:.4f}"
    )

    # ---- Save TFLite (full model with STFT for Android) ----
    args.tflite_output.parent.mkdir(parents=True, exist_ok=True)
    args.tflite_output.write_bytes(tflite_bytes)
    print(f"Wrote TFLite: {args.tflite_output} ({len(tflite_bytes)} bytes)")

    # ---- Extract CNN-only sub-model ----
    cnn_model, add_channel_output_shape = extract_cnn_only_model(full_model)
    print(f"CNN input shape (after mel): {add_channel_output_shape}")
    cnn_model.summary()

    mel_extractor = build_log_mel_feature_extractor(full_model)
    # mel_inputs: mel spectra of the original unaugmented windows, used for
    # parity checks and accuracy metrics (labels in y_all correspond 1-to-1).
    mel_inputs = mel_extractor.predict(x_all, verbose=0).astype(np.float32)
    # mel_aug_inputs: mel spectra of the full augmented training set.
    # Used for PTQ calibration so the quantizer sees the same activation
    # distribution the model was actually trained on.
    mel_aug_inputs = mel_extractor.predict(x_aug, verbose=0).astype(np.float32)
    print(f"PTQ calibration samples: {mel_aug_inputs.shape[0]} (augmented), "
          f"accuracy reference samples: {mel_inputs.shape[0]} (original)")
    full_pred = full_model.predict(x_all, verbose=0)
    cnn_pred = cnn_model.predict(mel_inputs, verbose=0)

    max_diff = np.max(np.abs(full_pred - cnn_pred))
    print(f"Max prediction diff: {max_diff:.8f}")
    assert max_diff < 1e-4, f"Predictions diverge: {max_diff}"

    accelerator_float_bytes = convert_keras_model_to_tflite(cnn_model)
    accelerator_int8_bytes = convert_keras_model_to_tflite(
        cnn_model,
        optimizations=["default"],
        representative_inputs=mel_aug_inputs,
        supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS_INT8],
        inference_input_type=tf.int8,
        inference_output_type=tf.int8,
    )

    args.accelerator_float_output.parent.mkdir(parents=True, exist_ok=True)
    args.accelerator_float_output.write_bytes(accelerator_float_bytes)
    args.accelerator_int8_output.parent.mkdir(parents=True, exist_ok=True)
    args.accelerator_int8_output.write_bytes(accelerator_int8_bytes)
    print(
        f"Wrote accelerator TFLite models: {args.accelerator_float_output.name}, "
        f"{args.accelerator_int8_output.name}"
    )

    float_tflite_pred = run_tflite_predictions(accelerator_float_bytes, mel_inputs)
    int8_tflite_pred = run_tflite_predictions(accelerator_int8_bytes, mel_inputs)

    # ---- Export CNN-only model to ONNX ----
    onnx_export_function, onnx_input_signature = build_onnx_export_function(
        cnn_model,
        add_channel_output_shape,
    )
    onnx_model, _ = tf2onnx.convert.from_function(
        onnx_export_function,
        input_signature=onnx_input_signature,
        opset=13,
    )
    onnx.save(onnx_model, str(args.onnx_output))
    print(f"Wrote ONNX: {args.onnx_output}")

    # Quick ONNX validation
    sess = ort.InferenceSession(str(args.onnx_output))
    input_name = sess.get_inputs()[0].name
    onnx_pred = sess.run(None, {input_name: mel_inputs.astype(np.float32)})[0]
    onnx_diff = np.max(np.abs(full_pred - onnx_pred))
    print(f"ONNX vs full diff:   {onnx_diff:.8f}")

    full_model_accuracy = float(np.mean(np.argmax(full_pred, axis=1) == y_all))
    comparison_metrics = {
        "full_model": {
            "top1_accuracy": full_model_accuracy,
        },
        "cnn_keras": summarize_prediction_alignment(full_pred, cnn_pred, y_all),
        "cnn_tflite_float": summarize_prediction_alignment(full_pred, float_tflite_pred, y_all),
        "cnn_tflite_int8": summarize_prediction_alignment(full_pred, int8_tflite_pred, y_all),
        "cnn_onnx": summarize_prediction_alignment(full_pred, onnx_pred, y_all),
    }

    timestamp = datetime.now(timezone.utc).isoformat()
    artifacts = {
        "waveform_tflite": args.tflite_output.name,
        "accelerator_float_tflite": args.accelerator_float_output.name,
        "accelerator_tflite": args.accelerator_int8_output.name,
        "desktop_onnx": args.onnx_output.name,
        "accelerator_input": {
            "kind": "log_mel_spectrogram",
            "time_frames": int(add_channel_output_shape[0]),
            "mel_bins": int(add_channel_output_shape[1]),
            "channels": int(add_channel_output_shape[2]),
        },
    }

    write_json(args.metadata_output, {
        "model_name": "starter_model",
        "model_version": STARTER_MODEL_VERSION,
        "labels": label_order,
        "input": {
            "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
            "window_size_samples": DEFAULT_WINDOW_SIZE_SAMPLES,
            "hop_size_samples": DEFAULT_WINDOW_SIZE_SAMPLES // 2,
            "expects_normalized_audio": True,
        },
        "artifacts": artifacts,
        "inference": {
            "ambient_strategy": "explicit_class",
            "recommended_threshold": 0.55,
        },
        "training": {
            "train_and_validation_share_examples": True,
            "backbone": "mel_cnn",
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "exclude_mixed_records": bool(args.no_mixed),
            "energy_gate_rms_threshold": 0.015,
            "class_window_counts": kept,
            "excluded_class_file_counts": excluded,
            "augmented_total_windows": int(x_aug.shape[0]),
            "mel_spectrogram": mel_spectrogram_metadata(),
            "synthetic_ambient": {
                "enabled": True,
                "ratio": 0.35,
                "noise_types": ["white", "brownian"],
            },
        },
        "timestamp_utc": timestamp,
    })

    write_json(args.metrics_output, {
        "model_name": "starter_model",
        "model_version": STARTER_MODEL_VERSION,
        "artifacts": artifacts,
        "sample_count": int(x_all.shape[0]),
        "window_count_before_augmentation": int(x_all.shape[0]),
        "window_count_after_augmentation": int(x_aug.shape[0]),
        "class_window_counts": kept,
        "excluded_class_file_counts": excluded,
        "metrics": numeric_metrics,
        "artifact_comparison": comparison_metrics,
        "timestamp_utc": timestamp,
    })

    print("\nDone. Desktop will compute mel spectrogram in Kotlin, feed to ONNX CNN.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
