import csv
import tempfile
import unittest
from pathlib import Path
from wave import open as wave_open

from scripts import train_starter_model


def write_sine_wav(path: Path, sample_rate: int = 16000, duration_seconds: float = 0.25) -> None:
    sample_count = int(sample_rate * duration_seconds)
    with wave_open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes((b"\x00\x00" * sample_count))


class TrainStarterModelValidationTest(unittest.TestCase):
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
                        "mixed_flag",
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
                        "mixed_flag": "false",
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

    def test_validation_rejects_missing_label_row(self):
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
                        "mixed_flag",
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
                        "mixed_flag": "false",
                        "include_in_training": "true",
                        "original_description": "Nail",
                    }
                )

            orphan_wav = assets_dir / "10_sweep.wav"
            write_sine_wav(orphan_wav)
            labels = train_starter_model.load_label_rows(labels_csv)

            with self.assertRaises(ValueError):
                train_starter_model.build_audio_sample_records(
                    labels_by_id=labels,
                    wav_files=[orphan_wav],
                )

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
                        "mixed_flag",
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
                        "mixed_flag": "false",
                        "include_in_training": "true",
                        "original_description": "Quarter",
                    }
                )

            with self.assertRaises(ValueError):
                train_starter_model.load_label_rows(labels_csv)

    def test_synthesize_ambient_noise_windows_generates_expected_shape(self):
        windows = train_starter_model.synthesize_ambient_noise_windows(
            target_count=5,
            window_size=128,
            random_seed=123,
        )

        self.assertEqual((5, 128), windows.shape)
        self.assertGreater(float(abs(windows).sum()), 0.0)


if __name__ == "__main__":
    unittest.main()
