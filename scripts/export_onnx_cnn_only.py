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
        LOUDNESS_EPSILON,
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
        LOUDNESS_EPSILON,
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


def tflite_tensor_descriptor(detail: dict) -> dict:
    """Builds the accelerator tensor descriptor (dtype + affine quant params) the Android/web
    apps read from model metadata to (de)quantize int8 accelerator I/O. See
    LiteRtCnnClassifier.kt and ModelMetadataRepository.kt."""
    import numpy as np

    scale, zero_point = detail.get("quantization", (0.0, 0))
    is_int8 = detail["dtype"] is np.int8
    descriptor: dict = {"dtype": "int8" if is_int8 else "float32"}
    if is_int8:
        descriptor["scale"] = float(scale)
        descriptor["zero_point"] = int(zero_point)
    return descriptor


def tflite_io_descriptors(model_bytes: bytes) -> tuple[dict, dict, dict]:
    """Returns (spectrogram_input_descriptor, loudness_input_descriptor, output_descriptor)
    for the two-input CNN-only TFLite model. Inputs are matched by rank: the spectrogram
    tensor is rank-4 [1,61,40,1] and the loudness scalar is rank-2 [1,1]. Each input
    descriptor also carries "input_index" — its ordinal position in the model's input list
    — so the on-device runtime (LiteRtCnnClassifier) writes the correct input buffer."""
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=model_bytes)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    spectrogram_ordinal = next(i for i, d in enumerate(input_details) if len(d["shape"]) == 4)
    loudness_ordinal = next(i for i, d in enumerate(input_details) if len(d["shape"]) == 2)

    spectrogram_descriptor = tflite_tensor_descriptor(input_details[spectrogram_ordinal])
    spectrogram_descriptor["input_index"] = int(spectrogram_ordinal)
    loudness_descriptor = tflite_tensor_descriptor(input_details[loudness_ordinal])
    loudness_descriptor["input_index"] = int(loudness_ordinal)

    return (
        spectrogram_descriptor,
        loudness_descriptor,
        tflite_tensor_descriptor(interpreter.get_output_details()[0]),
    )


def write_json(path: Path, content: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")


def classification_outcome_metrics(predictions, labels, label_order):
    import numpy as np

    predicted_labels = np.argmax(predictions, axis=1)
    class_count = len(label_order)
    confusion_matrix = np.zeros((class_count, class_count), dtype=np.int64)
    for actual_index, predicted_index in zip(labels, predicted_labels):
        confusion_matrix[int(actual_index), int(predicted_index)] += 1

    per_class = {}
    for class_index, class_label in enumerate(label_order):
        true_positive = int(confusion_matrix[class_index, class_index])
        false_negative = int(confusion_matrix[class_index, :].sum() - true_positive)
        false_positive = int(confusion_matrix[:, class_index].sum() - true_positive)
        support = int(confusion_matrix[class_index, :].sum())
        predicted_count = int(confusion_matrix[:, class_index].sum())
        precision_denominator = true_positive + false_positive
        recall_denominator = true_positive + false_negative
        precision = (
            true_positive / precision_denominator if precision_denominator else 0.0
        )
        recall = true_positive / recall_denominator if recall_denominator else 0.0
        f1 = (
            2.0 * precision * recall / (precision + recall)
            if precision + recall
            else 0.0
        )
        per_class[class_label] = {
            "precision": float(precision),
            "recall": float(recall),
            "f1": float(f1),
            "support": support,
            "predicted_count": predicted_count,
            "true_positive": true_positive,
            "false_positive": false_positive,
            "false_negative": false_negative,
        }

    total = int(confusion_matrix.sum())
    return {
        "top1_accuracy": (
            float(np.trace(confusion_matrix) / total) if total else 0.0
        ),
        "balanced_accuracy": float(
            np.mean([metrics["recall"] for metrics in per_class.values()])
        ),
        "macro_f1": float(
            np.mean([metrics["f1"] for metrics in per_class.values()])
        ),
        "confusion_matrix": {
            actual_label: {
                predicted_label: int(confusion_matrix[actual_index, predicted_index])
                for predicted_index, predicted_label in enumerate(label_order)
            }
            for actual_index, actual_label in enumerate(label_order)
        },
        "per_class": per_class,
    }


def summarize_prediction_alignment(
    reference_predictions,
    candidate_predictions,
    labels,
    label_order,
):
    import numpy as np

    candidate_top1 = np.argmax(candidate_predictions, axis=1)
    reference_top1 = np.argmax(reference_predictions, axis=1)
    return {
        **classification_outcome_metrics(candidate_predictions, labels, label_order),
        "top1_agreement_vs_reference": float(np.mean(candidate_top1 == reference_top1)),
        "max_abs_diff_vs_reference": float(np.max(np.abs(reference_predictions - candidate_predictions))),
        "mean_abs_diff_vs_reference": float(np.mean(np.abs(reference_predictions - candidate_predictions))),
    }


def print_classification_outcome_summary(artifact_name, metrics, label_order) -> None:
    print(
        f"\n{artifact_name}: accuracy={metrics['top1_accuracy']:.4f}, "
        f"balanced_accuracy={metrics['balanced_accuracy']:.4f}, "
        f"macro_f1={metrics['macro_f1']:.4f}"
    )
    print("  Per-class outcomes:")
    for class_label in label_order:
        class_metrics = metrics["per_class"][class_label]
        print(
            f"    {class_label:<7} "
            f"precision={class_metrics['precision']:.4f} "
            f"recall={class_metrics['recall']:.4f} "
            f"f1={class_metrics['f1']:.4f} "
            f"support={class_metrics['support']} "
            f"predicted={class_metrics['predicted_count']} "
            f"FP={class_metrics['false_positive']} "
            f"FN={class_metrics['false_negative']}"
        )
    print("  Confusion matrix (rows=actual, columns=predicted):")
    print("    actual\\pred " + " ".join(f"{label:>8}" for label in label_order))
    for actual_label in label_order:
        row = metrics["confusion_matrix"][actual_label]
        print(
            f"    {actual_label:>11} "
            + " ".join(f"{row[predicted_label]:>8}" for predicted_label in label_order)
        )


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
            name="scaled_log_mel_spectrogram",
        ),
        tf.TensorSpec(
            shape=(None, 1),
            dtype=tf.float32,
            name="loudness",
        ),
    ]

    @tf.function(input_signature=input_signature)
    def serving_default(scaled_log_mel_spectrogram, loudness):
        return {
            "class_probs": cnn_model(
                [scaled_log_mel_spectrogram, loudness], training=False
            )
        }

    return serving_default, input_signature


def onnx_input_feeds(session, spectrogram, loudness):
    """Map the spectrogram + loudness arrays to the ONNX session's input names by rank."""
    feeds = {}
    for spec in session.get_inputs():
        # spectrogram input is rank-4 [N,61,40,1]; loudness is rank-2 [N,1].
        feeds[spec.name] = spectrogram if len(spec.shape) == 4 else loudness
    return feeds


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
    parser.add_argument(
        "--mixed-sample-weight", type=float, default=0.2,
        help="Per-window sample_weight for 'cross-class-mixed' windows (target_name spans "
             "both a TARGET category like coin/jewelry and a JUNK category like "
             "trash/hardware, mixed_flag=true). Still trained on, but contributes less to "
             "the loss than clean windows (weight 1.0). Set to 1.0 to disable.",
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

    x_all, y_all, sample_weights, cross_class_mixed_mask, kept, excluded, cross_class_mixed_window_counts = create_training_windows(
        sample_records=sample_records,
        labels_to_index=labels_to_index,
        sample_rate=DEFAULT_SAMPLE_RATE_HZ,
        window_size=DEFAULT_WINDOW_SIZE_SAMPLES,
        hop_size=DEFAULT_WINDOW_SIZE_SAMPLES // 2,
        mixed_sample_weight=args.mixed_sample_weight,
    )
    training_file_count = sum(
        record.include_in_training and record.class_label in labels_to_index
        for record in sample_records
    )
    print(f"Training source files used: {training_file_count}")
    print(f"Training windows constructed from source files: {x_all.shape[0]}")

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
        sample_weights = np.concatenate(
            [sample_weights, np.ones(synth_count, dtype=np.float32)], axis=0
        )
        cross_class_mixed_mask = np.concatenate(
            [cross_class_mixed_mask, np.zeros(synth_count, dtype=bool)], axis=0
        )
        kept["AMBIENT"] += synth_count

    print(f"Training windows per output class: {kept}")
    print(f"Excluded class counts: {excluded}")

    # Augment + shuffle
    x_aug, y_aug, w_aug, mask_aug = augment_training_data(
        x_all, y_all, sample_weights, cross_class_mixed_mask
    )
    perm = np.random.default_rng(42).permutation(x_aug.shape[0])
    x_aug, y_aug, w_aug, mask_aug = x_aug[perm], y_aug[perm], w_aug[perm], mask_aug[perm]

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
        sample_weight_train=w_aug,
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
    # The feature extractor outputs the two tensors the CNN-only model consumes:
    # the scaled (peak-norm -> log-mel -> min-max) spectrogram and the log-RMS loudness.
    # *_inputs: features of the original unaugmented windows, used for parity checks and
    # accuracy metrics (labels in y_all correspond 1-to-1).
    mel_inputs, loud_inputs = mel_extractor.predict(x_all, verbose=0)
    mel_inputs = mel_inputs.astype(np.float32)
    loud_inputs = loud_inputs.astype(np.float32)
    # *_aug: features of the full augmented training set, used for PTQ calibration so the
    # quantizer sees the same activation distribution the model was actually trained on.
    mel_aug_inputs, loud_aug_inputs = mel_extractor.predict(x_aug, verbose=0)
    mel_aug_inputs = mel_aug_inputs.astype(np.float32)
    loud_aug_inputs = loud_aug_inputs.astype(np.float32)
    print(f"PTQ calibration samples: {mel_aug_inputs.shape[0]} (augmented), "
          f"accuracy reference samples: {mel_inputs.shape[0]} (original)")
    full_pred = full_model.predict(x_all, verbose=0)
    cnn_pred = cnn_model.predict([mel_inputs, loud_inputs], verbose=0)

    max_diff = np.max(np.abs(full_pred - cnn_pred))
    print(f"Max prediction diff: {max_diff:.8f}")
    assert max_diff < 1e-4, f"Predictions diverge: {max_diff}"

    accelerator_float_bytes = convert_keras_model_to_tflite(cnn_model)
    accelerator_int8_bytes = convert_keras_model_to_tflite(
        cnn_model,
        optimizations=["default"],
        representative_inputs=[mel_aug_inputs, loud_aug_inputs],
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

    float_tflite_pred = run_tflite_predictions(accelerator_float_bytes, [mel_inputs, loud_inputs])
    int8_tflite_pred = run_tflite_predictions(accelerator_int8_bytes, [mel_inputs, loud_inputs])

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

    # Quick ONNX validation (two named inputs: spectrogram + loudness)
    sess = ort.InferenceSession(str(args.onnx_output))
    onnx_feeds = onnx_input_feeds(sess, mel_inputs.astype(np.float32), loud_inputs.astype(np.float32))
    onnx_pred = sess.run(None, onnx_feeds)[0]
    onnx_diff = np.max(np.abs(full_pred - onnx_pred))
    print(f"ONNX vs full diff:   {onnx_diff:.8f}")

    comparison_metrics = {
        "full_model": classification_outcome_metrics(full_pred, y_all, label_order),
        "cnn_keras": summarize_prediction_alignment(
            full_pred, cnn_pred, y_all, label_order
        ),
        "cnn_tflite_float": summarize_prediction_alignment(
            full_pred, float_tflite_pred, y_all, label_order
        ),
        "cnn_tflite_int8": summarize_prediction_alignment(
            full_pred, int8_tflite_pred, y_all, label_order
        ),
        "cnn_onnx": summarize_prediction_alignment(
            full_pred, onnx_pred, y_all, label_order
        ),
    }

    # "cross-class-mixed" cohort: windows from recordings where target_name spans both a
    # TARGET category (coin/jewelry) and a JUNK category (trash/hardware) -- "target
    # near/under junk". cross_class_mixed_mask indexes x_all/y_all/*_pred in their
    # original (unshuffled) order, same as create_training_windows returned them.
    cross_class_mixed_count = int(cross_class_mixed_mask.sum())
    if cross_class_mixed_count > 0:
        y_mixed = y_all[cross_class_mixed_mask]
        full_pred_mixed = full_pred[cross_class_mixed_mask]
        cnn_pred_mixed = cnn_pred[cross_class_mixed_mask]
        float_tflite_pred_mixed = float_tflite_pred[cross_class_mixed_mask]
        int8_tflite_pred_mixed = int8_tflite_pred[cross_class_mixed_mask]
        onnx_pred_mixed = onnx_pred[cross_class_mixed_mask]
        comparison_metrics["cross_class_mixed"] = {
            "window_count": cross_class_mixed_count,
            "full_model": classification_outcome_metrics(full_pred_mixed, y_mixed, label_order),
            "cnn_keras": summarize_prediction_alignment(
                full_pred_mixed, cnn_pred_mixed, y_mixed, label_order
            ),
            "cnn_tflite_float": summarize_prediction_alignment(
                full_pred_mixed, float_tflite_pred_mixed, y_mixed, label_order
            ),
            "cnn_tflite_int8": summarize_prediction_alignment(
                full_pred_mixed, int8_tflite_pred_mixed, y_mixed, label_order
            ),
            "cnn_onnx": summarize_prediction_alignment(
                full_pred_mixed, onnx_pred_mixed, y_mixed, label_order
            ),
        }
    else:
        comparison_metrics["cross_class_mixed"] = {"window_count": 0}

    timestamp = datetime.now(timezone.utc).isoformat()
    loudness_mean = float(numeric_metrics["loudness_mean"])
    loudness_std = float(numeric_metrics["loudness_std"])
    (
        int8_spectrogram_descriptor,
        int8_loudness_descriptor,
        int8_output_descriptor,
    ) = tflite_io_descriptors(accelerator_int8_bytes)
    artifacts = {
        "waveform_tflite": args.tflite_output.name,
        "accelerator_float_tflite": args.accelerator_float_output.name,
        "accelerator_tflite": args.accelerator_int8_output.name,
        "desktop_onnx": args.onnx_output.name,
        "accelerator_input": {
            "kind": "scaled_log_mel_spectrogram",
            "time_frames": int(add_channel_output_shape[0]),
            "mel_bins": int(add_channel_output_shape[1]),
            "channels": int(add_channel_output_shape[2]),
            # dtype + quantization params let the accelerator runtime feed the int8 model.
            **int8_spectrogram_descriptor,
        },
        # Second model input: the standardized-in-graph log-RMS loudness scalar.
        # On-device code feeds raw log(rms + eps); (mean, std) are baked into the graph.
        "accelerator_loudness_input": {
            "kind": "loudness",
            "feature": "log_rms",
            "epsilon": LOUDNESS_EPSILON,
            "standardization_mean": loudness_mean,
            "standardization_std": loudness_std,
            **int8_loudness_descriptor,
        },
        "accelerator_output": int8_output_descriptor,
    }

    write_json(args.metadata_output, {
        "model_name": "starter_model",
        "model_version": STARTER_MODEL_VERSION,
        "labels": label_order,
        "input": {
            "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
            "window_size_samples": DEFAULT_WINDOW_SIZE_SAMPLES,
            "hop_size_samples": DEFAULT_WINDOW_SIZE_SAMPLES // 2,
            # Model is loudness-invariant: feed RAW fixed-scale (int16/32768) windows.
            # On-device code peak-normalizes + min-max scales the spectrogram and computes
            # the log-RMS loudness scalar; do NOT pre-normalize the waveform amplitude.
            "expects_normalized_audio": False,
            "preprocessing": {
                "peak_normalize_window": True,
                "spectrogram_min_max_scale": True,
                "loudness_scalar": {
                    "feature": "log_rms",
                    "epsilon": LOUDNESS_EPSILON,
                    "standardization_mean": loudness_mean,
                    "standardization_std": loudness_std,
                    "standardization_in_graph": True,
                },
            },
        },
        "artifacts": artifacts,
        "inference": {
            "ambient_strategy": "explicit_class",
            "recommended_threshold": 0.55,
            "energy_gate_rms_threshold": 0.015,
        },
        "training": {
            "train_and_validation_share_examples": True,
            "backbone": "mel_cnn_loudness_invariant",
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "exclude_mixed_records": bool(args.no_mixed),
            "mixed_sample_weight": float(args.mixed_sample_weight),
            "cross_class_mixed_window_counts": cross_class_mixed_window_counts,
            "class_window_counts": kept,
            "excluded_class_file_counts": excluded,
            "augmented_total_windows": int(x_aug.shape[0]),
            "mel_spectrogram": mel_spectrogram_metadata(),
            "loudness_standardization": {
                "mean": loudness_mean,
                "std": loudness_std,
            },
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
        "mixed_sample_weight": float(args.mixed_sample_weight),
        "cross_class_mixed_window_counts": cross_class_mixed_window_counts,
        "metrics": numeric_metrics,
        "artifact_comparison": comparison_metrics,
        "timestamp_utc": timestamp,
    })

    print("\nTraining-dataset classification outcomes (not an independent validation set):")
    for artifact_name, artifact_metrics in comparison_metrics.items():
        if artifact_name == "cross_class_mixed":
            continue
        print_classification_outcome_summary(
            artifact_name,
            artifact_metrics,
            label_order,
        )

    mixed_metrics = comparison_metrics["cross_class_mixed"]
    print(f"\nCross-class-mixed cohort ('target near/under junk'): {mixed_metrics['window_count']} windows")
    if mixed_metrics["window_count"] > 0:
        for artifact_name in ("full_model", "cnn_keras", "cnn_tflite_float", "cnn_tflite_int8", "cnn_onnx"):
            print_classification_outcome_summary(
                f"cross_class_mixed/{artifact_name}", mixed_metrics[artifact_name], label_order
            )

    print("\nDone. Desktop will compute mel spectrogram in Kotlin, feed to ONNX CNN.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
