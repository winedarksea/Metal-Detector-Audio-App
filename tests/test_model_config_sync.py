import json
import re
import unittest
from pathlib import Path

from scripts import mel_cnn_pipeline


REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID_AUDIO_CONSTANTS = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "AudioConstants.kt"
ANDROID_LITERT_MEL_EXTRACTOR = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "pipeline" / "AndroidMelSpectrogramFeatureExtractor.kt"
ANDROID_LITERT_CLASSIFIER = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "metaldetectoraudioapp" / "app" / "inference" / "LiteRtCnnClassifier.kt"
DESKTOP_MEL_EXTRACTOR = REPO_ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "metaldetectoraudioapp" / "app" / "audio" / "pipeline" / "MelSpectrogramFeatureExtractor.kt"
MODEL_METADATA = REPO_ROOT / "models" / "starter_model_metadata.json"


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
        self.assertEqual(
            124,
            int(
                parse_kotlin_literal(
                    read_file(ANDROID_LITERT_CLASSIFIER),
                    r"const val DEFAULT_TIME_FRAMES = ([0-9_]+)",
                )
            ),
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

    def test_loudness_epsilon_matches_between_python_and_kotlin(self):
        for extractor_path in (DESKTOP_MEL_EXTRACTOR, ANDROID_LITERT_MEL_EXTRACTOR):
            source = read_file(extractor_path)
            epsilon = parse_kotlin_literal(
                source,
                r"const val LOUDNESS_EPSILON = ([0-9eE_\.\-f]+)",
            )
            self.assertEqual(
                mel_cnn_pipeline.LOUDNESS_EPSILON,
                epsilon,
                f"LOUDNESS_EPSILON drift in {extractor_path.name}",
            )

    def test_kotlin_extractors_have_loudness_invariant_methods(self):
        # Both extractors must expose the peak-norm + min-max spectral input and the loudness
        # scalar so the on-device features match the two-input model graph.
        for extractor_path in (DESKTOP_MEL_EXTRACTOR, ANDROID_LITERT_MEL_EXTRACTOR):
            source = read_file(extractor_path)
            self.assertIn("fun extractScaledSpectrogram", source)
            self.assertIn("fun computeLoudness", source)

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


class AcceleratorQuantizationSyncTest(unittest.TestCase):
    """The Android LiteRT path (LiteRtCnnClassifier) (de)quantizes int8 accelerator I/O using the
    scale/zero_point stored in model metadata, because LiteRT does not expose them at runtime. If
    these drift from the actual int8 .tflite the model crashes or returns garbage, so lock them."""

    def test_metadata_quant_params_match_int8_tflite(self):
        try:
            import tensorflow  # noqa: F401
        except ModuleNotFoundError:
            self.skipTest("tensorflow unavailable")
        try:
            from scripts.export_onnx_cnn_only import tflite_io_descriptors
        except Exception as exc:  # pragma: no cover - tensorflow optional locally
            self.skipTest(f"tensorflow/export deps unavailable: {exc}")

        artifacts = json.loads(MODEL_METADATA.read_text(encoding="utf-8"))["artifacts"]
        int8_tflite = REPO_ROOT / "models" / artifacts["accelerator_tflite"]
        if not int8_tflite.exists():
            self.skipTest(f"int8 tflite not present: {int8_tflite}")
        if "accelerator_loudness_input" not in artifacts:
            self.skipTest("legacy single-input model — re-export for the two-input architecture")

        spectrogram_descriptor, loudness_descriptor, output_descriptor = tflite_io_descriptors(
            int8_tflite.read_bytes()
        )

        meta_input = artifacts["accelerator_input"]
        self.assertEqual(spectrogram_descriptor["dtype"], meta_input.get("dtype"))
        self.assertEqual(spectrogram_descriptor.get("scale"), meta_input.get("scale"))
        self.assertEqual(spectrogram_descriptor.get("zero_point"), meta_input.get("zero_point"))
        self.assertEqual(spectrogram_descriptor.get("input_index"), meta_input.get("input_index"))

        meta_loudness = artifacts["accelerator_loudness_input"]
        self.assertEqual(loudness_descriptor["dtype"], meta_loudness.get("dtype"))
        self.assertEqual(loudness_descriptor.get("scale"), meta_loudness.get("scale"))
        self.assertEqual(loudness_descriptor.get("zero_point"), meta_loudness.get("zero_point"))
        self.assertEqual(loudness_descriptor.get("input_index"), meta_loudness.get("input_index"))

        self.assertEqual(output_descriptor, artifacts.get("accelerator_output"))


if __name__ == "__main__":
    unittest.main()
