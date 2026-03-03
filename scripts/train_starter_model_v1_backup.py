#!/usr/bin/env python3
"""
Train a starter TARGET/JUNK/AMBIENT TFLite model from ./assets.

Design notes:
- The dataset is small, so train and validation intentionally share the same examples.
- Validation is strict; the script fails fast when labels/files are inconsistent.
- The model uses a MobileNetV2 backbone as a transfer-learning starter.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

SUPPORTED_CLASS_LABELS = ("TARGET", "JUNK", "AMBIENT")
SUPPORTED_PATTERNS = ("SWING", "WIGGLE")


@dataclass(frozen=True)
class LabelRow:
    sample_id: int
    target_name: str
    class_label: str
    mixed_flag: bool
    include_in_training: bool


@dataclass(frozen=True)
class AudioSampleRecord:
    sample_id: int
    wav_path: Path
    pattern: str
    class_label: str
    mixed_flag: bool
    include_in_training: bool


def parse_cli_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train starter metal detector audio model")
    parser.add_argument("--assets-dir", type=Path, default=Path("assets"))
    parser.add_argument("--labels-csv", type=Path, default=Path("assets/cleaned_labels.csv"))
    parser.add_argument("--model-output", type=Path, default=Path("models/starter_model.tflite"))
    parser.add_argument(
        "--metadata-output",
        type=Path,
        default=Path("models/starter_model_metadata.json"),
    )
    parser.add_argument(
        "--metrics-output",
        type=Path,
        default=Path("models/starter_model_metrics.json"),
    )
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--sample-rate", type=int, default=16_000)
    parser.add_argument("--window-size", type=int, default=16_000)
    parser.add_argument("--hop-size", type=int, default=8_000)
    parser.add_argument(
        "--synthesize-ambient-noise",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Generate synthetic AMBIENT windows from white+brown noise.",
    )
    parser.add_argument(
        "--ambient-noise-ratio",
        type=float,
        default=0.35,
        help="Synthetic ambient windows as a ratio of non-ambient windows.",
    )
    parser.add_argument(
        "--ambient-noise-seed",
        type=int,
        default=7,
        help="Random seed for synthetic ambient generation.",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def parse_bool(value: str) -> bool:
    lowered = value.strip().lower()
    if lowered in {"true", "1", "yes", "y"}:
        return True
    if lowered in {"false", "0", "no", "n"}:
        return False
    raise ValueError(f"Expected boolean field, got: {value!r}")


def infer_pattern_from_filename(file_name: str) -> str:
    lowered = file_name.lower()
    if "_sweep" in lowered or "_big" in lowered:
        return "SWING"
    if "_wig" in lowered or "_wiggle" in lowered:
        return "WIGGLE"
    raise ValueError(f"Unable to infer pattern from filename: {file_name}")


def infer_sample_id_from_filename(file_name: str) -> int:
    stem = Path(file_name).stem
    prefix = stem.split("_", 1)[0]
    try:
        return int(prefix)
    except ValueError as error:
        raise ValueError(f"Invalid sample id in filename: {file_name}") from error


def load_label_rows(labels_csv_path: Path) -> Dict[int, LabelRow]:
    required_columns = {
        "sample_id",
        "target_name",
        "class_label",
        "mixed_flag",
        "include_in_training",
    }
    with labels_csv_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError("Labels CSV is missing a header row")

        missing_columns = required_columns - set(reader.fieldnames)
        if missing_columns:
            missing = ", ".join(sorted(missing_columns))
            raise ValueError(f"Labels CSV is missing required columns: {missing}")

        rows_by_sample_id: Dict[int, LabelRow] = {}
        for line_number, row in enumerate(reader, start=2):
            if not row.get("sample_id", "").strip():
                continue

            sample_id = int(row["sample_id"])
            if sample_id in rows_by_sample_id:
                raise ValueError(f"Duplicate sample_id={sample_id} at line {line_number}")

            class_label = row["class_label"].strip().upper()
            if class_label not in SUPPORTED_CLASS_LABELS:
                raise ValueError(
                    f"Invalid class_label={class_label!r} for sample_id={sample_id}; "
                    f"expected one of {SUPPORTED_CLASS_LABELS}"
                )

            target_name = row["target_name"].strip()
            if not target_name:
                raise ValueError(f"sample_id={sample_id} has an empty target_name")

            rows_by_sample_id[sample_id] = LabelRow(
                sample_id=sample_id,
                target_name=target_name,
                class_label=class_label,
                mixed_flag=parse_bool(row["mixed_flag"]),
                include_in_training=parse_bool(row["include_in_training"]),
            )

    if not rows_by_sample_id:
        raise ValueError("No label rows found")
    return rows_by_sample_id


def collect_wav_files(assets_directory: Path) -> List[Path]:
    wav_files = sorted(path for path in assets_directory.glob("*.wav") if path.is_file())
    if not wav_files:
        raise ValueError(f"No .wav files found in {assets_directory}")
    return wav_files


def build_audio_sample_records(
    labels_by_sample_id: Dict[int, LabelRow],
    wav_files: Iterable[Path],
) -> List[AudioSampleRecord]:
    sample_records: List[AudioSampleRecord] = []
    seen_training_sample_ids = set()

    for wav_path in wav_files:
        sample_id = infer_sample_id_from_filename(wav_path.name)
        pattern = infer_pattern_from_filename(wav_path.name)
        if pattern not in SUPPORTED_PATTERNS:
            raise ValueError(f"Unsupported pattern inferred for {wav_path.name}: {pattern}")

        label_row = labels_by_sample_id.get(sample_id)
        if label_row is None:
            raise ValueError(
                f"Missing label row for sample_id={sample_id} referenced by {wav_path.name}"
            )

        if label_row.include_in_training:
            seen_training_sample_ids.add(sample_id)

        sample_records.append(
            AudioSampleRecord(
                sample_id=sample_id,
                wav_path=wav_path,
                pattern=pattern,
                class_label=label_row.class_label,
                mixed_flag=label_row.mixed_flag,
                include_in_training=label_row.include_in_training,
            )
        )

    # Training-eligible rows must have at least one backing WAV.
    for sample_id, row in labels_by_sample_id.items():
        if row.include_in_training and sample_id not in seen_training_sample_ids:
            raise ValueError(
                f"sample_id={sample_id} is include_in_training=true but has no WAV file"
            )

    return sample_records


def load_wav_as_mono_float32(wav_path: Path, target_sample_rate: int) -> np.ndarray:
    import numpy as np
    from scipy.io import wavfile

    sample_rate, raw = wavfile.read(wav_path)
    if raw.ndim == 2:
        raw = raw.mean(axis=1)

    if raw.dtype == np.int16:
        audio = raw.astype(np.float32) / 32768.0
    elif raw.dtype == np.int32:
        audio = raw.astype(np.float32) / 2147483648.0
    elif raw.dtype == np.float32:
        audio = raw
    else:
        audio = raw.astype(np.float32)

    if sample_rate != target_sample_rate:
        # Keep dependencies lean by using simple linear interpolation resampling.
        duration_seconds = len(audio) / sample_rate
        target_length = max(1, int(duration_seconds * target_sample_rate))
        original_axis = np.linspace(0.0, duration_seconds, len(audio), endpoint=False)
        target_axis = np.linspace(0.0, duration_seconds, target_length, endpoint=False)
        audio = np.interp(target_axis, original_axis, audio).astype(np.float32)

    return audio


def create_training_windows(
    sample_records: Iterable[AudioSampleRecord],
    labels_to_index: Dict[str, int],
    sample_rate: int,
    window_size: int,
    hop_size: int,
) -> Tuple[np.ndarray, np.ndarray, Dict[str, int]]:
    import numpy as np

    features: List[np.ndarray] = []
    labels: List[int] = []
    class_counts = {class_label: 0 for class_label in SUPPORTED_CLASS_LABELS}

    for record in sample_records:
        if not record.include_in_training:
            continue

        audio = load_wav_as_mono_float32(record.wav_path, target_sample_rate=sample_rate)
        if len(audio) < window_size:
            padded = np.zeros((window_size,), dtype=np.float32)
            padded[: len(audio)] = audio
            audio = padded

        for start in range(0, max(1, len(audio) - window_size + 1), hop_size):
            window = audio[start : start + window_size]
            if len(window) < window_size:
                tail = np.zeros((window_size,), dtype=np.float32)
                tail[: len(window)] = window
                window = tail

            max_abs = np.max(np.abs(window))
            if max_abs > 1e-6:
                window = window / max_abs

            features.append(window.astype(np.float32))
            labels.append(labels_to_index[record.class_label])
            class_counts[record.class_label] += 1

    if not features:
        raise ValueError("No training windows produced; check include_in_training labels")

    x = np.stack(features, axis=0)
    y = np.array(labels, dtype=np.int64)
    return x, y, class_counts


def synthesize_ambient_noise_windows(
    target_window_count: int,
    window_size: int,
    random_seed: int,
):
    import numpy as np

    if target_window_count <= 0:
        return np.zeros((0, window_size), dtype=np.float32)

    rng = np.random.default_rng(random_seed)
    windows = np.zeros((target_window_count, window_size), dtype=np.float32)

    for index in range(target_window_count):
        white_noise = rng.normal(loc=0.0, scale=1.0, size=window_size).astype(np.float32)
        brown_steps = rng.normal(loc=0.0, scale=0.08, size=window_size).astype(np.float32)
        brown_noise = np.cumsum(brown_steps)

        white_noise = white_noise / (np.max(np.abs(white_noise)) + 1e-8)
        brown_noise = brown_noise / (np.max(np.abs(brown_noise)) + 1e-8)

        white_weight = float(rng.uniform(0.35, 0.8))
        brown_weight = 1.0 - white_weight
        mixed_noise = white_weight * white_noise + brown_weight * brown_noise

        max_abs = np.max(np.abs(mixed_noise))
        if max_abs > 1e-6:
            mixed_noise = mixed_noise / max_abs
        windows[index] = mixed_noise.astype(np.float32)

    return windows


def build_transfer_model(num_classes: int, window_size: int):
    import tensorflow as tf  # Imported lazily to keep validation-only usage lightweight.

    waveform_input = tf.keras.Input(shape=(window_size,), dtype=tf.float32, name="waveform")
    x = tf.keras.layers.Lambda(
        lambda signal: tf.signal.stft(
            signal,
            frame_length=400,
            frame_step=160,
            fft_length=512,
        )
    )(waveform_input)
    x = tf.keras.layers.Lambda(lambda spec: tf.abs(spec))(x)
    x = tf.keras.layers.Lambda(lambda spec: tf.math.log(spec + 1e-6))(x)
    x = tf.keras.layers.Lambda(lambda spec: tf.expand_dims(spec, axis=-1))(x)
    x = tf.keras.layers.Resizing(96, 96)(x)
    x = tf.keras.layers.Lambda(lambda image: tf.image.grayscale_to_rgb(image))(x)

    try:
        backbone = tf.keras.applications.MobileNetV2(
            include_top=False,
            input_shape=(96, 96, 3),
            pooling="avg",
            weights="imagenet",
        )
    except Exception:
        # The app still works with a randomly initialized backbone in offline environments,
        # but this path should only happen when pretrained weights cannot be fetched.
        backbone = tf.keras.applications.MobileNetV2(
            include_top=False,
            input_shape=(96, 96, 3),
            pooling="avg",
            weights=None,
        )

    backbone.trainable = False
    x = backbone(x)
    x = tf.keras.layers.Dropout(0.25)(x)
    outputs = tf.keras.layers.Dense(num_classes, activation="softmax", name="class_probs")(x)

    model = tf.keras.Model(inputs=waveform_input, outputs=outputs)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=3e-4),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def train_and_convert_tflite(
    x_train: np.ndarray,
    y_train: np.ndarray,
    x_val: np.ndarray,
    y_val: np.ndarray,
    num_classes: int,
    window_size: int,
    epochs: int,
    batch_size: int,
) -> Tuple[bytes, Dict[str, float]]:
    import tensorflow as tf

    model = build_transfer_model(num_classes=num_classes, window_size=window_size)
    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=epochs,
        batch_size=batch_size,
        verbose=2,
    )

    eval_loss, eval_accuracy = model.evaluate(x_val, y_val, verbose=0)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    final_metrics = {
        "loss": float(eval_loss),
        "accuracy": float(eval_accuracy),
        "train_loss": float(history.history["loss"][-1]),
        "train_accuracy": float(history.history["accuracy"][-1]),
        "val_loss": float(history.history["val_loss"][-1]),
        "val_accuracy": float(history.history["val_accuracy"][-1]),
    }
    return tflite_model, final_metrics


def write_json(path: Path, content: Dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")


def run_training_pipeline(args: argparse.Namespace) -> int:
    labels_by_sample_id = load_label_rows(args.labels_csv)
    wav_files = collect_wav_files(args.assets_dir)
    sample_records = build_audio_sample_records(labels_by_sample_id=labels_by_sample_id, wav_files=wav_files)

    label_order = list(SUPPORTED_CLASS_LABELS)
    labels_to_index = {label: index for index, label in enumerate(label_order)}

    if args.dry_run:
        class_counts = {class_label: 0 for class_label in SUPPORTED_CLASS_LABELS}
        included_records = [record for record in sample_records if record.include_in_training]
        for record in included_records:
            class_counts[record.class_label] += 1

        metrics_payload = {
            "model_name": "starter_model",
            "model_version": "0.1.0-dry-run",
            "dry_run": True,
            "sample_count": len(sample_records),
            "training_file_count": len(included_records),
            "class_file_counts": class_counts,
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        }
        write_json(args.metrics_output, metrics_payload)
        metadata_payload = {
            "model_name": "starter_model",
            "model_version": "0.1.0-dry-run",
            "labels": label_order,
            "input": {
                "sample_rate_hz": args.sample_rate,
                "window_size_samples": args.window_size,
                "hop_size_samples": args.hop_size,
                "expects_normalized_audio": True,
            },
            "training": {
                "train_and_validation_share_examples": True,
                "class_file_counts": class_counts,
                "synthetic_ambient": {
                    "enabled": bool(args.synthesize_ambient_noise),
                    "ratio": float(args.ambient_noise_ratio),
                    "noise_types": ["white", "brownian"],
                },
            },
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        }
        write_json(args.metadata_output, metadata_payload)
        print("Dry-run validation completed successfully.")
        return 0

    x_all, y_all, class_counts = create_training_windows(
        sample_records=sample_records,
        labels_to_index=labels_to_index,
        sample_rate=args.sample_rate,
        window_size=args.window_size,
        hop_size=args.hop_size,
    )
    synthetic_ambient_window_count = 0
    if args.synthesize_ambient_noise and args.ambient_noise_ratio > 0:
        import numpy as np

        non_ambient_window_count = class_counts["TARGET"] + class_counts["JUNK"]
        synthetic_ambient_window_count = max(
            1,
            int(round(non_ambient_window_count * args.ambient_noise_ratio)),
        )
        ambient_windows = synthesize_ambient_noise_windows(
            target_window_count=synthetic_ambient_window_count,
            window_size=args.window_size,
            random_seed=args.ambient_noise_seed,
        )
        ambient_labels = np.full(
            shape=(ambient_windows.shape[0],),
            fill_value=labels_to_index["AMBIENT"],
            dtype=np.int64,
        )
        x_all = np.concatenate([x_all, ambient_windows], axis=0)
        y_all = np.concatenate([y_all, ambient_labels], axis=0)
        class_counts["AMBIENT"] += int(ambient_windows.shape[0])

    tflite_model, numeric_metrics = train_and_convert_tflite(
        x_train=x_all,
        y_train=y_all,
        x_val=x_all,
        y_val=y_all,
        num_classes=len(label_order),
        window_size=args.window_size,
        epochs=args.epochs,
        batch_size=args.batch_size,
    )

    args.model_output.parent.mkdir(parents=True, exist_ok=True)
    args.model_output.write_bytes(tflite_model)

    metadata_payload = {
        "model_name": "starter_model",
        "model_version": "0.1.0",
        "labels": label_order,
        "input": {
            "sample_rate_hz": args.sample_rate,
            "window_size_samples": args.window_size,
            "hop_size_samples": args.hop_size,
            "expects_normalized_audio": True,
        },
        "training": {
            "train_and_validation_share_examples": True,
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "class_window_counts": class_counts,
            "synthetic_ambient_window_count": synthetic_ambient_window_count,
            "synthetic_ambient": {
                "enabled": bool(args.synthesize_ambient_noise),
                "ratio": float(args.ambient_noise_ratio),
                "noise_types": ["white", "brownian"],
            },
        },
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
    }

    metrics_payload = {
        "model_name": "starter_model",
        "model_version": "0.1.0",
        "sample_count": len(sample_records),
        "window_count": int(x_all.shape[0]),
        "class_window_counts": class_counts,
        "synthetic_ambient_window_count": synthetic_ambient_window_count,
        "metrics": numeric_metrics,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
    }

    write_json(args.metadata_output, metadata_payload)
    write_json(args.metrics_output, metrics_payload)

    print(f"Wrote model: {args.model_output}")
    print(f"Wrote metadata: {args.metadata_output}")
    print(f"Wrote metrics: {args.metrics_output}")
    return 0


def main() -> int:
    args = parse_cli_args()
    try:
        return run_training_pipeline(args)
    except Exception as error:  # pylint: disable=broad-except
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
