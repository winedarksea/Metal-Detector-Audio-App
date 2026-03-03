"""TFLite model wrapper matching the Android MetalClassifierInterpreter.

Loads ``starter_model.tflite``, accepts a raw float32 waveform window,
peak-normalises it, runs inference, and returns structured results.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List

import numpy as np

# ---------------------------------------------------------------------------
# Result dataclass
# ---------------------------------------------------------------------------

@dataclass
class InferenceResult:
    top_label: str
    top_score: float
    per_label_scores: Dict[str, float]
    inference_time_ms: float


@dataclass
class RecentDetection:
    label: str
    confidence: float
    timestamp_ms: float


# ---------------------------------------------------------------------------
# Engine
# ---------------------------------------------------------------------------

# UX timing constants (mirrors InferenceUiState companion)
STICKY_TARGET_DURATION_MS = 5_000
RECENT_WINDOW_MS = 30_000
MAX_RECENT_DETECTIONS = 20


class InferenceEngine:
    """Wraps the TFLite starter model and manages detection history.

    Parameters
    ----------
    model_dir : path to the ``models/`` folder containing
        ``starter_model.tflite`` and ``starter_model_metadata.json``.
    """

    def __init__(self, model_dir: Path):
        meta_path = model_dir / "starter_model_metadata.json"
        model_path = model_dir / "starter_model.tflite"

        with open(meta_path) as f:
            meta = json.load(f)

        self.labels: List[str] = meta["labels"]
        self.window_size: int = meta["input"]["window_size_samples"]
        self.expects_normalized: bool = meta["input"]["expects_normalized_audio"]
        self._recommended_threshold: float = float(
            meta.get("inference", {}).get("recommended_threshold", 0.55)
        )

        # Load TFLite interpreter
        import tensorflow as tf
        self._interpreter = tf.lite.Interpreter(model_path=str(model_path))
        self._interpreter.allocate_tensors()
        self._input_detail = self._interpreter.get_input_details()[0]
        self._output_detail = self._interpreter.get_output_details()[0]

        # Detection history state
        self._recent_detections: list[RecentDetection] = []
        self._sticky_target_end_ms: float = 0.0
        self._threshold: float = self._recommended_threshold

    # -- configuration -------------------------------------------------------

    def set_threshold(self, value: float) -> None:
        self._threshold = value

    # -- inference -----------------------------------------------------------

    def classify_window(self, samples: np.ndarray) -> InferenceResult:
        """Run inference on a raw float32 waveform window.

        Peak-normalises the window (matching the Android pipeline), runs the
        TFLite model, and returns the result.
        """
        window = np.array(samples, dtype=np.float32)

        # Truncate or zero-pad to model input size
        if len(window) > self.window_size:
            window = window[: self.window_size]
        elif len(window) < self.window_size:
            padded = np.zeros(self.window_size, dtype=np.float32)
            padded[: len(window)] = window
            window = padded

        # Peak-normalise (mirrors AudioNormalizationProcessor)
        if self.expects_normalized:
            peak = float(np.max(np.abs(window)))
            if peak > 1e-6:
                window = window / peak

        # Reshape to [1, window_size]
        input_data = window.reshape(1, -1)

        t0 = time.perf_counter()
        self._interpreter.set_tensor(self._input_detail["index"], input_data)
        self._interpreter.invoke()
        scores = self._interpreter.get_tensor(self._output_detail["index"])[0]
        elapsed_ms = (time.perf_counter() - t0) * 1000.0

        top_idx = int(np.argmax(scores))
        raw_top_label = self.labels[top_idx] if top_idx < len(self.labels) else "AMBIENT"
        top_score = float(scores[top_idx])
        top_label = raw_top_label if top_score >= self._threshold else "AMBIENT"

        per_label = {
            self.labels[i]: float(scores[i])
            for i in range(min(len(self.labels), len(scores)))
        }
        if "AMBIENT" not in per_label:
            per_label["AMBIENT"] = max(0.0, 1.0 - top_score)

        result = InferenceResult(
            top_label=top_label,
            top_score=top_score,
            per_label_scores=per_label,
            inference_time_ms=elapsed_ms,
        )

        self._update_detection_history(result)
        return result

    # -- detection history ---------------------------------------------------

    def _update_detection_history(self, result: InferenceResult) -> None:
        now_ms = time.time() * 1000.0

        # Record non-AMBIENT detections above threshold
        if result.top_label != "AMBIENT" and result.top_score >= self._threshold:
            self._recent_detections.append(
                RecentDetection(
                    label=result.top_label,
                    confidence=result.top_score,
                    timestamp_ms=now_ms,
                )
            )
            # Cap list size
            if len(self._recent_detections) > MAX_RECENT_DETECTIONS:
                self._recent_detections = self._recent_detections[-MAX_RECENT_DETECTIONS:]

        # Extend sticky banner on TARGET
        if result.top_label == "TARGET" and result.top_score >= self._threshold:
            self._sticky_target_end_ms = now_ms + STICKY_TARGET_DURATION_MS

        # Prune old detections outside the recent window
        cutoff = now_ms - RECENT_WINDOW_MS
        self._recent_detections = [d for d in self._recent_detections if d.timestamp_ms >= cutoff]

    @property
    def sticky_target_active(self) -> bool:
        return time.time() * 1000.0 < self._sticky_target_end_ms

    @property
    def sticky_target_confidence(self) -> float:
        """Confidence of the most recent TARGET detection (for display)."""
        for d in reversed(self._recent_detections):
            if d.label == "TARGET":
                return d.confidence
        return 0.0

    @property
    def recent_target_count(self) -> int:
        return sum(1 for d in self._recent_detections if d.label == "TARGET")

    @property
    def recent_detections(self) -> list[RecentDetection]:
        return list(reversed(self._recent_detections))  # newest first
