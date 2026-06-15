import csv
import io
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch
from wave import open as wave_open

import numpy as np

from scripts import train_starter_model
from scripts import mel_cnn_pipeline


def write_sine_wav(path: Path, sample_rate: int = 16000, duration_seconds: float = 0.25) -> None:
    sample_count = int(sample_rate * duration_seconds)
    with wave_open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes((b"\x00\x00" * sample_count))


class TrainStarterModelValidationTest(unittest.TestCase):
    def test_default_window_is_one_second_at_16khz(self):
        self.assertEqual(16_000, mel_cnn_pipeline.DEFAULT_WINDOW_SIZE_SAMPLES)
        self.assertEqual(8_000, mel_cnn_pipeline.DEFAULT_HOP_SIZE_SAMPLES)

    def test_filename_without_pattern_defaults_to_swing(self):
        self.assertEqual(
            "SWING",
            train_starter_model.infer_pattern_from_filename("7.wav"),
        )
        self.assertEqual(
            "SWING",
            train_starter_model.infer_pattern_from_filename("7_first.wav"),
        )

    def test_explicit_wiggle_filename_remains_wiggle(self):
        self.assertEqual(
            "WIGGLE",
            train_starter_model.infer_pattern_from_filename("7_wiggle.wav"),
        )

    def test_stereo_pcm16_is_scaled_before_channels_are_averaged(self):
        stereo_pcm16 = np.array(
            [
                [32767, 32767],
                [-32768, 0],
                [16384, -16384],
            ],
            dtype=np.int16,
        )
        fake_wavfile = types.SimpleNamespace(
            read=lambda _: (16000, stereo_pcm16)
        )
        fake_scipy_io = types.ModuleType("scipy.io")
        fake_scipy_io.wavfile = fake_wavfile
        fake_scipy = types.ModuleType("scipy")
        fake_scipy.io = fake_scipy_io

        with patch.dict(
            sys.modules,
            {"scipy": fake_scipy, "scipy.io": fake_scipy_io},
        ):
            audio = train_starter_model.load_wav_as_mono_float32(
                Path("stereo.wav"),
                target_sample_rate=16000,
            )

        np.testing.assert_allclose(
            audio,
            np.array([32767 / 32768, -0.5, 0.0], dtype=np.float32),
            rtol=0.0,
            atol=1e-7,
        )
        self.assertEqual(np.float32, audio.dtype)

    def test_validation_accepts_consistent_assets(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            assets_dir = root / "assets"
            assets_dir.mkdir()
            labels_csv = assets_dir / "cleaned_labels.csv"

            with labels_csv.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "sample_id",
                        "target_name",
                        "class_label",
                        "depth_inches",
                        "mixed_target_and_junk",
                        "include_in_training",
                        "original_description",
                    ],
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "sample_id": "10",
                        "target_name": "coin:quarter:cupronickel-clad-copper",
                        "class_label": "TARGET",
                        "depth_inches": "8",
                        "mixed_target_and_junk": "false",
                        "include_in_training": "true",
                        "original_description": "Quarter at 8\"",
                    }
                )

            write_sine_wav(assets_dir / "10_sweep.wav")

            labels = train_starter_model.load_label_rows(labels_csv)
            records = train_starter_model.build_audio_sample_records(
                labels_by_id=labels,
                wav_files=[assets_dir / "10_sweep.wav"],
            )

            self.assertEqual(1, len(records))
            self.assertEqual("SWING", records[0].pattern)
            self.assertEqual("TARGET", records[0].class_label)

    def test_validation_skips_unlabeled_wav_with_warning(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            assets_dir = root / "assets"
            assets_dir.mkdir()
            labels_csv = assets_dir / "cleaned_labels.csv"

            with labels_csv.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "sample_id",
                        "target_name",
                        "class_label",
                        "depth_inches",
                        "mixed_target_and_junk",
                        "include_in_training",
                        "original_description",
                    ],
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "sample_id": "99",
                        "target_name": "artifact:nail:iron",
                        "class_label": "JUNK",
                        "depth_inches": "",
                        "mixed_target_and_junk": "false",
                        "include_in_training": "false",
                        "original_description": "Nail",
                    }
                )

            orphan_wav = assets_dir / "10_sweep.wav"
            write_sine_wav(orphan_wav)
            labels = train_starter_model.load_label_rows(labels_csv)

            warning_output = io.StringIO()
            with patch("sys.stderr", warning_output):
                records = train_starter_model.build_audio_sample_records(
                    labels_by_id=labels,
                    wav_files=[orphan_wav],
                )

            self.assertEqual([], records)
            self.assertIn("WARNING: Skipping unlabeled WAV 10_sweep.wav", warning_output.getvalue())
            self.assertIn("cleaned_labels.csv", warning_output.getvalue())

    def test_validation_rejects_non_taxonomy_target_name(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            assets_dir = root / "assets"
            assets_dir.mkdir()
            labels_csv = assets_dir / "cleaned_labels.csv"

            with labels_csv.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "sample_id",
                        "target_name",
                        "class_label",
                        "depth_inches",
                        "mixed_target_and_junk",
                        "include_in_training",
                        "original_description",
                    ],
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "sample_id": "1",
                        "target_name": "quarter",
                        "class_label": "TARGET",
                        "depth_inches": "6",
                        "mixed_target_and_junk": "false",
                        "include_in_training": "true",
                        "original_description": "Quarter",
                    }
                )

            with self.assertRaises(ValueError):
                train_starter_model.load_label_rows(labels_csv)

    def test_legacy_mixed_flag_column_remains_readable(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            labels_csv = Path(temp_dir) / "labels.csv"
            labels_csv.write_text(
                "sample_id,target_name,class_label,mixed_flag,include_in_training\n"
                "1,coin:dime:silver,TARGET,true,true\n",
                encoding="utf-8",
            )

            rows = train_starter_model.load_label_rows(labels_csv)

            self.assertTrue(rows["1"].mixed_target_and_junk)

    def test_mixed_target_and_junk_rejects_non_target_recording(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            labels_csv = Path(temp_dir) / "labels.csv"
            labels_csv.write_text(
                "sample_id,target_name,class_label,mixed_target_and_junk,include_in_training\n"
                "1,trash:foil:aluminum,JUNK,true,true\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "only TARGET rows may be mixed"):
                train_starter_model.load_label_rows(labels_csv)

    def test_app_rows_derive_target_and_junk_mix_from_object_classes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            labels_csv = root / "missing.csv"
            metadata_csv = root / "recordings_metadata.csv"
            metadata_csv.write_text(
                "recording_id,target_name,label_class,class_label,pattern,"
                "mixed_target_and_junk,include_in_training\n"
                "rec_1,coin:dime:silver,TARGET,TARGET,SWING,true,true\n"
                "rec_1,trash:foil:aluminum,JUNK,TARGET,SWING,true,true\n",
                encoding="utf-8",
            )

            rows = train_starter_model.load_label_rows(labels_csv, metadata_csv)

            self.assertEqual("TARGET", rows["rec_1"].class_label)
            self.assertTrue(rows["rec_1"].mixed_target_and_junk)

    def test_synthesize_ambient_noise_windows_generates_expected_shape(self):
        windows = train_starter_model.synthesize_ambient_noise_windows(
            target_count=5,
            window_size=128,
            random_seed=123,
        )

        self.assertEqual((5, 128), windows.shape)
        self.assertGreater(float(abs(windows).sum()), 0.0)

    def test_additive_noise_augmentation_preserves_quiet_signal(self):
        sample_count = 16_000
        time_axis = np.arange(sample_count, dtype=np.float32) / sample_count
        quiet_signal = (0.001 * np.sin(2.0 * np.pi * 50.0 * time_axis)).astype(
            np.float32
        )

        augmented, augmented_labels, augmented_weights, augmented_mask = (
            train_starter_model.augment_training_data(
                quiet_signal[np.newaxis, :],
                np.array([0], dtype=np.int64),
                np.array([1.0], dtype=np.float32),
                np.array([False], dtype=bool),
                seed=123,
            )
        )

        noisy_signal = augmented[2]
        added_noise = noisy_signal - quiet_signal
        signal_rms = float(np.sqrt(np.mean(np.square(quiet_signal))))
        noise_rms = float(np.sqrt(np.mean(np.square(added_noise))))
        measured_snr_db = 20.0 * np.log10(signal_rms / noise_rms)

        self.assertEqual((4, sample_count), augmented.shape)
        np.testing.assert_array_equal(np.zeros(4, dtype=np.int64), augmented_labels)
        np.testing.assert_array_equal(
            np.ones(4, dtype=np.float32), augmented_weights
        )
        np.testing.assert_array_equal(
            np.zeros(4, dtype=bool), augmented_mask
        )
        self.assertGreaterEqual(measured_snr_db, 24.5)
        self.assertLessEqual(measured_snr_db, 40.5)


if __name__ == "__main__":
    unittest.main()
