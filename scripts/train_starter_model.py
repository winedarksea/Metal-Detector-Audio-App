#!/usr/bin/env python3
"""
Train a starter TARGET/JUNK/AMBIENT TFLite model from ./assets.

Architecture: waveform -> log-mel spectrogram -> small 2D CNN -> softmax.
Key training features:
  - Energy-gated windowing: silent windows from TARGET/JUNK files are discarded
    (not mislabeled), solving the sparse-chirp problem.
  - Mel-spectrogram CNN purpose-built for audio classification (replaces frozen
    MobileNetV2-ImageNet which has no audio-relevant features).
  - Shorter 0.5 s windows (8000 samples @16 kHz) to match typical metal-detector
    chirp duration (50-300 ms).
  - Explicit AMBIENT output class with synthetic ambient augmentation to reduce
    false-positive bias in noisy conditions.

Validation shares examples with training because the dataset is tiny.
The script fails fast when labels or files are inconsistent.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, TYPE_CHECKING, Tuple

if TYPE_CHECKING:
    import numpy as np

try:
    from .mel_cnn_pipeline import (
        DEFAULT_HOP_SIZE_SAMPLES,
        DEFAULT_RMS_GATE_THRESHOLD,
        DEFAULT_SAMPLE_RATE_HZ,
        STARTER_MODEL_VERSION,
        DEFAULT_WINDOW_SIZE_SAMPLES,
        mel_spectrogram_metadata,
        train_and_convert_tflite,
    )
except ImportError:
    from mel_cnn_pipeline import (
        DEFAULT_HOP_SIZE_SAMPLES,
        DEFAULT_RMS_GATE_THRESHOLD,
        DEFAULT_SAMPLE_RATE_HZ,
        STARTER_MODEL_VERSION,
        DEFAULT_WINDOW_SIZE_SAMPLES,
        mel_spectrogram_metadata,
        train_and_convert_tflite,
    )

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SUPPORTED_CLASS_LABELS = ("TARGET", "JUNK", "AMBIENT")
MODEL_OUTPUT_LABELS = SUPPORTED_CLASS_LABELS
SUPPORTED_PATTERNS = ("SWING", "WIGGLE")


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class LabelRow:
    sample_id: str
    target_name: str
    class_label: str
    mixed_flag: bool
    include_in_training: bool
    pattern: str = ""


@dataclass(frozen=True)
class AudioSampleRecord:
    sample_id: str
    wav_path: Path
    pattern: str
    class_label: str
    mixed_flag: bool
    include_in_training: bool


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_cli_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train starter metal-detector audio model"
    )
    parser.add_argument("--assets-dir", type=Path, default=Path("assets"))
    parser.add_argument("--labels-csv", type=Path, default=Path("assets/cleaned_labels.csv"))
    parser.add_argument("--model-output", type=Path, default=Path("models/starter_model.tflite"))
    parser.add_argument(
        "--metadata-output", type=Path,
        default=Path("models/starter_model_metadata.json"),
    )  # TODO: consolidate these names into a single argument, a base name with suffixes
    parser.add_argument(
        "--metrics-output", type=Path,
        default=Path("models/starter_model_metrics.json"),
    )
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--sample-rate", type=int, default=DEFAULT_SAMPLE_RATE_HZ)
    parser.add_argument("--window-size", type=int, default=DEFAULT_WINDOW_SIZE_SAMPLES,
                        help="Window length in samples (default 8000 = 0.5 s @ 16 kHz).")
    parser.add_argument("--hop-size", type=int, default=DEFAULT_HOP_SIZE_SAMPLES,
                        help="Hop between windows in samples (default = window-size / 2).")
    parser.add_argument(
        "--rms-gate-threshold", type=float, default=DEFAULT_RMS_GATE_THRESHOLD,
        help="Min RMS for a window to be kept as its file label (TARGET/JUNK). "
             "Silent windows from non-AMBIENT files are discarded instead of "
             "being mislabeled.  Set to 0 to disable energy gating.",
    )
    parser.add_argument(
        "--synthesize-ambient-noise",
        action=argparse.BooleanOptionalAction, default=True,
        help="Generate synthetic AMBIENT windows for class balancing.",
    )
    parser.add_argument(
        "--ambient-noise-ratio", type=float, default=0.35,
        help="Synthetic ambient windows as a ratio of TARGET+JUNK windows.",
    )
    parser.add_argument("--ambient-noise-seed", type=int, default=7)
    parser.add_argument(
        "--no-mixed", action="store_true",
        help="Exclude all records marked as mixed_flag = True.",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Label / WAV loading (no ML dependencies)
# ---------------------------------------------------------------------------

def parse_bool(value: str) -> bool:
    lowered = value.strip().lower()
    if lowered in {"true", "1", "yes", "y"}:
        return True
    if lowered in {"false", "0", "no", "n"}:
        return False
    raise ValueError(f"Expected boolean field, got: {value!r}")


def validate_category_object_material_tokens(target_name: str, sample_id: str) -> None:
    tokens = [token.strip() for token in target_name.split("|") if token.strip()]
    if not tokens:
        raise ValueError(f"sample_id={sample_id} has no target_name tokens")

    for token in tokens:
        parts = [part.strip() for part in token.split(":")]
        if len(parts) != 3 or any(not part for part in parts):
            raise ValueError(
                "sample_id="
                f"{sample_id} target_name token '{token}' must match "
                "category:object:material"
            )


def infer_pattern_from_filename(file_name: str) -> str:
    lowered = file_name.lower()
    if "_sweep" in lowered or "_big" in lowered:
        return "SWING"
    if "_wig" in lowered or "_wiggle" in lowered:
        return "WIGGLE"
    # Legacy cleaned_labels.csv rows do not carry a pattern. Newer recordings without an
    # explicit filename marker are standard detector sweeps, so SWING is the safe default.
    return "SWING"


def infer_sample_id_from_filename(file_name: str) -> str:
    stem = Path(file_name).stem
    if stem.startswith("rec_"):
        return stem
    prefix = stem.split("_", 1)[0]
    return prefix


def load_label_rows(labels_csv_path: Path, metadata_csv_path: Path = None) -> Dict[str, LabelRow]:
    required_columns = {
        "sample_id", "target_name", "class_label",
        "mixed_flag", "include_in_training",
    }
    rows_by_id: Dict[str, LabelRow] = {}

    # Load from cleaned_labels.csv (legacy format)
    if labels_csv_path.exists():
        with labels_csv_path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                raise ValueError(f"{labels_csv_path.name} is missing a header row")

            missing_columns = required_columns - set(reader.fieldnames)
            if missing_columns:
                raise ValueError(
                    f"{labels_csv_path.name} missing columns: {', '.join(sorted(missing_columns))}"
                )

            for line_no, row in enumerate(reader, start=2):
                if not row.get("sample_id", "").strip():
                    continue
                sample_id = row["sample_id"].strip()
                if sample_id in rows_by_id:
                    raise ValueError(f"Duplicate sample_id={sample_id} at line {line_no} in {labels_csv_path.name}")

                class_label = row["class_label"].strip().upper()
                if class_label not in SUPPORTED_CLASS_LABELS:
                    raise ValueError(
                        f"Invalid class_label={class_label!r} for sample_id={sample_id}"
                    )
                target_name = row["target_name"].strip()
                if not target_name:
                    raise ValueError(f"sample_id={sample_id} has empty target_name")
                validate_category_object_material_tokens(target_name, sample_id)

                rows_by_id[sample_id] = LabelRow(
                    sample_id=sample_id,
                    target_name=target_name,
                    class_label=class_label,
                    mixed_flag=parse_bool(row["mixed_flag"]),
                    include_in_training=parse_bool(row["include_in_training"]),
                    pattern=""
                )

    # Load from recordings_metadata.csv (app format)
    if metadata_csv_path and metadata_csv_path.exists():
        with metadata_csv_path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            # Map app columns to LabelRow fields
            # app: recording_id -> sample_id
            # app: target_name -> target_name
            # app: class_label -> class_label
            # app: mixed_flag -> mixed_flag
            # app: include_in_training -> include_in_training
            for line_no, row in enumerate(reader, start=2):
                sample_id = row.get("recording_id", "").strip()
                if not sample_id:
                    continue
                if sample_id in rows_by_id:
                    # Prefer metadata from recordings_metadata.csv if there's a collision
                    pass

                class_label = row["class_label"].strip().upper()
                if class_label not in SUPPORTED_CLASS_LABELS:
                    continue # Skip unsupported labels in app metadata

                target_name = row["target_name"].strip()
                if not target_name:
                    continue
                
                try:
                    validate_category_object_material_tokens(target_name, sample_id)
                except ValueError:
                    # App might allow freeform target names, handle gracefully or skip
                    continue

                rows_by_id[sample_id] = LabelRow(
                    sample_id=sample_id,
                    target_name=target_name,
                    class_label=class_label,
                    mixed_flag=parse_bool(row.get("mixed_flag", "false")),
                    include_in_training=parse_bool(row.get("include_in_training", "false")),
                    pattern=row.get("pattern", "").strip().upper()
                )

    if not rows_by_id:
        raise ValueError("No label rows found")
    return rows_by_id


def collect_wav_files(assets_directory: Path) -> List[Path]:
    wav_files = sorted(p for p in assets_directory.glob("*.wav") if p.is_file())
    if not wav_files:
        raise ValueError(f"No .wav files found in {assets_directory}")
    return wav_files


def build_audio_sample_records(
    labels_by_id: Dict[str, LabelRow],
    wav_files: Iterable[Path],
) -> List[AudioSampleRecord]:
    records: List[AudioSampleRecord] = []
    seen_training_ids: set = set()

    for wav_path in wav_files:
        sample_id = infer_sample_id_from_filename(wav_path.name)

        label_row = labels_by_id.get(sample_id)
        if label_row is None:
            raise ValueError(
                f"Missing label row for sample_id={sample_id} ({wav_path.name})"
            )

        pattern = label_row.pattern
        if not pattern:
            pattern = infer_pattern_from_filename(wav_path.name)

        if pattern not in SUPPORTED_PATTERNS:
            raise ValueError(f"Unsupported pattern for {wav_path.name}: {pattern}")

        if label_row.include_in_training:
            seen_training_ids.add(sample_id)

        records.append(AudioSampleRecord(
            sample_id=sample_id, wav_path=wav_path, pattern=pattern,
            class_label=label_row.class_label, mixed_flag=label_row.mixed_flag,
            include_in_training=label_row.include_in_training,
        ))

    for sid, row in labels_by_id.items():
        if row.include_in_training and sid not in seen_training_ids:
            raise ValueError(
                f"sample_id={sid} is include_in_training=true but has no WAV file"
            )
    return records


# ---------------------------------------------------------------------------
# Audio I/O
# ---------------------------------------------------------------------------

def load_wav_as_mono_float32(wav_path: Path, target_sample_rate: int):
    import numpy as np
    from scipy.io import wavfile

    sample_rate, raw = wavfile.read(wav_path)
    if raw.dtype == np.int16:
        audio = raw.astype(np.float32) / 32768.0
    elif raw.dtype == np.int32:
        audio = raw.astype(np.float32) / 2147483648.0
    elif raw.dtype == np.uint8:
        audio = (raw.astype(np.float32) - 128.0) / 128.0
    elif np.issubdtype(raw.dtype, np.floating):
        audio = raw.astype(np.float32)
    else:
        audio = raw.astype(np.float32)

    if audio.ndim == 2:
        # Normalize integer PCM before downmixing. Averaging int16 channels first promotes
        # them to float64 and previously bypassed the required [-1, 1] scaling.
        audio = audio.mean(axis=1, dtype=np.float32)
    elif audio.ndim != 1:
        raise ValueError(
            f"Expected mono or multi-channel WAV samples in {wav_path}, got shape {audio.shape}"
        )

    if sample_rate != target_sample_rate:
        duration = len(audio) / sample_rate
        target_len = max(1, int(duration * target_sample_rate))
        src_axis = np.linspace(0.0, duration, len(audio), endpoint=False)
        dst_axis = np.linspace(0.0, duration, target_len, endpoint=False)
        audio = np.interp(dst_axis, src_axis, audio).astype(np.float32)

    return audio


# ---------------------------------------------------------------------------
# Training window creation (energy-gated)
# ---------------------------------------------------------------------------

def create_training_windows(
    sample_records: Iterable[AudioSampleRecord],
    labels_to_index: Dict[str, int],
    sample_rate: int,
    window_size: int,
    hop_size: int,
    rms_gate_threshold: float,
) -> Tuple[np.ndarray, np.ndarray, Dict[str, int], Dict[str, int], Dict[str, int]]:
    """Slide windows and label them.  For TARGET/JUNK files, windows below
    *rms_gate_threshold* are silently discarded (not mislabeled as AMBIENT).

    Returns (x, y, kept_class_counts, skipped_class_counts, excluded_class_counts).
    """
    import numpy as np

    features: List[np.ndarray] = []
    labels: List[int] = []
    kept_counts = {c: 0 for c in labels_to_index}
    skipped_counts = {c: 0 for c in labels_to_index}
    excluded_counts = {
        c: 0 for c in SUPPORTED_CLASS_LABELS
        if c not in labels_to_index
    }

    for record in sample_records:
        if not record.include_in_training:
            continue
        if record.class_label not in labels_to_index:
            if record.class_label in excluded_counts:
                excluded_counts[record.class_label] += 1
            continue

        audio = load_wav_as_mono_float32(record.wav_path, target_sample_rate=sample_rate)
        if len(audio) < window_size:
            padded = np.zeros(window_size, dtype=np.float32)
            padded[: len(audio)] = audio
            audio = padded

        for start in range(0, max(1, len(audio) - window_size + 1), hop_size):
            window = audio[start: start + window_size]
            if len(window) < window_size:
                tail = np.zeros(window_size, dtype=np.float32)
                tail[: len(window)] = window
                window = tail

            rms = float(np.sqrt(np.mean(window ** 2)))

            # Energy gate: discard silent windows from non-AMBIENT files.
            if rms_gate_threshold > 0 and record.class_label != "AMBIENT":
                if rms < rms_gate_threshold:
                    skipped_counts[record.class_label] += 1
                    continue

            # Fixed-scale: WAV samples already converted with /32768.0 — no per-window
            # peak normalization so amplitude information is preserved for the model.
            features.append(window.astype(np.float32))
            labels.append(labels_to_index[record.class_label])
            kept_counts[record.class_label] += 1

    if not features:
        raise ValueError("No training windows produced; check include_in_training and RMS gate")

    return (
        np.stack(features),
        np.array(labels, dtype=np.int64),
        kept_counts,
        skipped_counts,
        excluded_counts,
    )


def collect_negative_ambient_windows(
    sample_records: Iterable[AudioSampleRecord],
    sample_rate: int,
    window_size: int,
    hop_size: int,
) -> np.ndarray:
    """Collect normalized windows from AMBIENT files used for threshold calibration."""
    import numpy as np

    features: List[np.ndarray] = []
    for record in sample_records:
        if not record.include_in_training or record.class_label != "AMBIENT":
            continue

        audio = load_wav_as_mono_float32(record.wav_path, target_sample_rate=sample_rate)
        if len(audio) < window_size:
            padded = np.zeros(window_size, dtype=np.float32)
            padded[: len(audio)] = audio
            audio = padded

        for start in range(0, max(1, len(audio) - window_size + 1), hop_size):
            window = audio[start: start + window_size]
            if len(window) < window_size:
                tail = np.zeros(window_size, dtype=np.float32)
                tail[: len(window)] = window
                window = tail

            features.append(window.astype(np.float32))

    if not features:
        return np.zeros((0, window_size), dtype=np.float32)
    return np.stack(features)


# ---------------------------------------------------------------------------
# Synthetic ambient noise
# ---------------------------------------------------------------------------

def synthesize_ambient_noise_windows(
    target_count: int,
    window_size: int,
    random_seed: int,
):
    import numpy as np

    if target_count <= 0:
        return np.zeros((0, window_size), dtype=np.float32)

    rng = np.random.default_rng(random_seed)
    windows = np.zeros((target_count, window_size), dtype=np.float32)

    for i in range(target_count):
        white = rng.normal(0.0, 1.0, window_size).astype(np.float32)
        brown = np.cumsum(rng.normal(0.0, 0.08, window_size)).astype(np.float32)

        w = float(rng.uniform(0.35, 0.8))
        mixed = w * white + (1.0 - w) * brown

        # Scale to a realistic ambient RMS (0.05–0.15 of full scale) so the model
        # sees amplitude as a useful feature; peak normalization would destroy this.
        target_rms = float(rng.uniform(0.05, 0.15))
        rms = float(np.sqrt(np.mean(mixed ** 2)))
        if rms > 1e-8:
            mixed = mixed * (target_rms / rms)
        windows[i] = np.clip(mixed, -1.0, 1.0).astype(np.float32)

    return windows


# ---------------------------------------------------------------------------
# Data augmentation helpers
# ---------------------------------------------------------------------------

def augment_training_data(x: np.ndarray, y: np.ndarray, seed: int = 42):
    """Augmentation: time-shift, low-level additive noise, and random gain attenuation.

    Gain augmentation is essential for the loudness-invariant model: the spectral branch
    is peak-normalized (gain cancels), so attenuated copies share the same scaled
    spectrogram but a different log-RMS loudness scalar.  This decorrelates loudness from
    class so the model can't learn "TARGET just happened to be recorded louder."
    """
    import numpy as np

    rng = np.random.default_rng(seed)
    aug_x, aug_y = [x], [y]
    window_size = x.shape[1]

    for i in range(x.shape[0]):
        # Time shift by +/- 10 %
        shift = rng.integers(-window_size // 10, window_size // 10)
        shifted = np.roll(x[i], shift)
        aug_x.append(shifted[np.newaxis, :])
        aug_y.append(np.array([y[i]], dtype=y.dtype))

        # Additive Gaussian noise (SNR ~25 dB); clip to [-1, 1] to avoid
        # going out of range without destroying relative amplitude.
        noise_scale = float(rng.uniform(0.01, 0.04))
        noisy = np.clip(
            x[i] + rng.normal(0, noise_scale, window_size).astype(np.float32),
            -1.0, 1.0,
        )
        aug_x.append(noisy[np.newaxis, :])
        aug_y.append(np.array([y[i]], dtype=y.dtype))

        # Random gain attenuation in [-20 dB, 0 dB]. Attenuation-only avoids clipping;
        # the peak-norm spectral branch is unaffected, only the loudness scalar shifts.
        gain = float(10.0 ** (rng.uniform(-20.0, 0.0) / 20.0))
        attenuated = (x[i] * gain).astype(np.float32)
        aug_x.append(attenuated[np.newaxis, :])
        aug_y.append(np.array([y[i]], dtype=y.dtype))

    return np.concatenate(aug_x, axis=0), np.concatenate(aug_y, axis=0)


# ---------------------------------------------------------------------------
# JSON helpers
# ---------------------------------------------------------------------------

def write_json(path: Path, content: Dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# Pipeline
# ---------------------------------------------------------------------------

def run_training_pipeline(args: argparse.Namespace) -> int:
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

    # ---- dry-run: validate only, no ML imports, no file writes ----
    if args.dry_run:
        file_counts = {c: 0 for c in SUPPORTED_CLASS_LABELS}
        included = [r for r in sample_records if r.include_in_training]
        for r in included:
            file_counts[r.class_label] += 1

        print(f"Dry-run validation passed: {len(sample_records)} samples, "
              f"{len(included)} included in training.")
        print(f"  Class file counts: {file_counts}")
        return 0

    # ---- full training ----
    import numpy as np

    x_all, y_all, kept, skipped, excluded = create_training_windows(
        sample_records=sample_records,
        labels_to_index=labels_to_index,
        sample_rate=args.sample_rate,
        window_size=args.window_size,
        hop_size=args.hop_size,
        rms_gate_threshold=args.rms_gate_threshold,
    )

    ambient_file_windows = collect_negative_ambient_windows(
        sample_records=sample_records,
        sample_rate=args.sample_rate,
        window_size=args.window_size,
        hop_size=args.hop_size,
    )

    print(f"Output windows kept: {kept}")
    print(f"Output windows skipped (energy gate): {skipped}")
    print(f"Excluded non-output classes: {excluded}")
    print(f"Ambient windows from files: {ambient_file_windows.shape[0]}")

    # Synthetic ambient windows are included as explicit AMBIENT class examples.
    synth_count = 0
    if args.synthesize_ambient_noise and args.ambient_noise_ratio > 0:
        non_ambient = kept["TARGET"] + kept["JUNK"]
        synth_count = max(1, round(non_ambient * args.ambient_noise_ratio))
        ambient_windows = synthesize_ambient_noise_windows(
            synth_count, args.window_size, args.ambient_noise_seed,
        )
        ambient_labels = np.full(synth_count, labels_to_index["AMBIENT"], dtype=np.int64)
        x_all = np.concatenate([x_all, ambient_windows], axis=0)
        y_all = np.concatenate([y_all, ambient_labels], axis=0)
        kept["AMBIENT"] += synth_count

    # Augmentation (triples effective dataset size with time-shift + noise)
    x_aug, y_aug = augment_training_data(x_all, y_all)
    print(f"After augmentation: {x_aug.shape[0]} windows (from {x_all.shape[0]})")

    # Shuffle
    perm = np.random.default_rng(42).permutation(x_aug.shape[0])
    x_aug, y_aug = x_aug[perm], y_aug[perm]

    tflite_bytes, numeric_metrics, _trained_model = train_and_convert_tflite(
        x_train=x_aug, y_train=y_aug,
        x_val=x_all, y_val=y_all,  # evaluate on un-augmented set
        num_classes=len(label_order),
        window_size=args.window_size,
        sample_rate=args.sample_rate,
        epochs=args.epochs,
        batch_size=args.batch_size,
    )
    recommended_threshold = 0.55
    threshold_metrics = {
        "strategy": "explicit_ambient_class",
        "ambient_windows_from_files": int(ambient_file_windows.shape[0]),
        "synthetic_ambient_window_count": synth_count,
    }

    args.model_output.parent.mkdir(parents=True, exist_ok=True)
    args.model_output.write_bytes(tflite_bytes)

    timestamp = datetime.now(timezone.utc).isoformat()

    write_json(args.metadata_output, {
        "model_name": "starter_model",
        "model_version": STARTER_MODEL_VERSION,
        "labels": label_order,
        "input": {
            "sample_rate_hz": args.sample_rate,
            "window_size_samples": args.window_size,
            "hop_size_samples": args.hop_size,
            "expects_normalized_audio": True,
        },
        "artifacts": {
            "waveform_tflite": args.model_output.name,
        },
        "inference": {
            "ambient_strategy": "explicit_class",
            "recommended_threshold": recommended_threshold,
        },
        "training": {
            "train_and_validation_share_examples": True,
            "backbone": "mel_cnn",
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "exclude_mixed_records": bool(args.no_mixed),
            "energy_gate_rms_threshold": args.rms_gate_threshold,
            "class_window_counts": kept,
            "skipped_silent_windows": skipped,
            "excluded_class_file_counts": excluded,
            "ambient_window_count_from_files": int(ambient_file_windows.shape[0]),
            "synthetic_ambient_window_count": synth_count,
            "augmented_total_windows": int(x_aug.shape[0]),
            "mel_spectrogram": mel_spectrogram_metadata(),
            "synthetic_ambient": {
                "enabled": bool(args.synthesize_ambient_noise),
                "ratio": float(args.ambient_noise_ratio),
                "noise_types": ["white", "brownian"],
            },
        },
        "timestamp_utc": timestamp,
    })

    write_json(args.metrics_output, {
        "model_name": "starter_model",
        "model_version": STARTER_MODEL_VERSION,
        "sample_count": len(sample_records),
        "window_count_before_augmentation": int(x_all.shape[0]),
        "window_count_after_augmentation": int(x_aug.shape[0]),
        "artifacts": {
            "waveform_tflite": args.model_output.name,
        },
        "class_window_counts": kept,
        "skipped_silent_windows": skipped,
        "excluded_class_file_counts": excluded,
        "ambient_window_count_from_files": int(ambient_file_windows.shape[0]),
        "synthetic_ambient_window_count": synth_count,
        "recommended_threshold": recommended_threshold,
        "threshold_calibration_metrics": threshold_metrics,
        "metrics": numeric_metrics,
        "timestamp_utc": timestamp,
    })

    print(f"Wrote model:    {args.model_output} ({len(tflite_bytes)} bytes)")
    print(f"Wrote metadata: {args.metadata_output}")
    print(f"Wrote metrics:  {args.metrics_output}")
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
