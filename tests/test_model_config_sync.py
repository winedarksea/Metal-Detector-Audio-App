import re
import unittest
from pathlib import Path

from scripts import mel_cnn_pipeline


REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID_AUDIO_CONSTANTS = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "AudioConstants.kt"
ANDROID_LITERT_MEL_EXTRACTOR = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "pipeline" / "AndroidMelSpectrogramFeatureExtractor.kt"
DESKTOP_MEL_EXTRACTOR = REPO_ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "pipeline" / "MelSpectrogramFeatureExtractor.kt"


def read_file(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_kotlin_literal(text: str, pattern: str) -> float:
    match = re.search(pattern, text)
    if match is None:
        raise AssertionError(f"Pattern not found: {pattern}")

    token = match.group(1).replace("_", "")
    if token.endswith("f"):
        token = token[:-1]
    return float(token)


class ModelConfigSyncTest(unittest.TestCase):
    def test_android_window_size_matches_training_default(self):
        audio_constants = read_file(ANDROID_AUDIO_CONSTANTS)

        window_size = parse_kotlin_literal(
            audio_constants,
            r"const val INFERENCE_WINDOW_SIZE_SAMPLES = ([0-9_]+)",
        )

        self.assertEqual(
            mel_cnn_pipeline.DEFAULT_WINDOW_SIZE_SAMPLES,
            int(window_size),
        )
        self.assertIn(
            "const val INFERENCE_HOP_SIZE_SAMPLES = INFERENCE_WINDOW_SIZE_SAMPLES / 2",
            audio_constants,
        )

    def test_desktop_mel_defaults_match_training_config(self):
        extractor_source = read_file(DESKTOP_MEL_EXTRACTOR)
        mel_metadata = mel_cnn_pipeline.mel_spectrogram_metadata()

        self.assertEqual(
            mel_cnn_pipeline.DEFAULT_SAMPLE_RATE_HZ,
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val sampleRate: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["fft_length"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val fftLength: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["stft_frame_length"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val frameLength: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["stft_frame_step"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val frameStep: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["num_mel_bins"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val numMelBins: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["lower_edge_hertz"],
            parse_kotlin_literal(
                extractor_source,
                r"private val melLowerHz: Float = ([0-9_\.f]+)",
            ),
        )
        self.assertEqual(
            mel_metadata["upper_edge_hertz"],
            parse_kotlin_literal(
                extractor_source,
                r"private val melUpperHz: Float = ([0-9_\.f]+)",
            ),
        )

    def test_android_litert_mel_defaults_match_training_config(self):
        extractor_source = read_file(ANDROID_LITERT_MEL_EXTRACTOR)
        mel_metadata = mel_cnn_pipeline.mel_spectrogram_metadata()

        self.assertEqual(
            mel_cnn_pipeline.DEFAULT_SAMPLE_RATE_HZ,
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val sampleRate: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["fft_length"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val fftLength: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["stft_frame_length"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val frameLength: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["stft_frame_step"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val frameStep: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["num_mel_bins"],
            int(
                parse_kotlin_literal(
                    extractor_source,
                    r"private val numMelBins: Int = ([0-9_]+)",
                )
            ),
        )
        self.assertEqual(
            mel_metadata["lower_edge_hertz"],
            parse_kotlin_literal(
                extractor_source,
                r"private val melLowerHz: Float = ([0-9_\.f]+)",
            ),
        )
        self.assertEqual(
            mel_metadata["upper_edge_hertz"],
            parse_kotlin_literal(
                extractor_source,
                r"private val melUpperHz: Float = ([0-9_\.f]+)",
            ),
        )


if __name__ == "__main__":
    unittest.main()