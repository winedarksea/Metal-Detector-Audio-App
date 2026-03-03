"""Microphone capture with sliding-window frame extraction.

Streams 16 kHz mono audio from the default input device and yields
overlapping windows identical to the Android SlidingAudioFramer.
"""

from __future__ import annotations

import threading
from collections import deque
from typing import Callable, Optional

import numpy as np

# ---------------------------------------------------------------------------
# Constants (mirror AudioConstants.kt)
# ---------------------------------------------------------------------------
SAMPLE_RATE_HZ = 16_000
WINDOW_SIZE_SAMPLES = 8_000        # 0.5 s
HOP_SIZE_SAMPLES = 4_000           # 0.25 s
CAPTURE_BLOCK_SIZE = 1_024         # ~64 ms per sounddevice callback


class SlidingFrameExtractor:
    """Ring-buffer framer that mirrors SlidingAudioFramer.kt.

    Push arbitrary chunks of mono float32 samples.  When enough data has
    accumulated, pop complete windows at the configured hop interval.
    """

    def __init__(
        self,
        window_size: int = WINDOW_SIZE_SAMPLES,
        hop_size: int = HOP_SIZE_SAMPLES,
    ):
        self._window_size = window_size
        self._hop_size = hop_size
        self._buffer = np.zeros(0, dtype=np.float32)

    def push(self, samples: np.ndarray) -> list[np.ndarray]:
        """Append *samples* and return any complete windows."""
        self._buffer = np.concatenate([self._buffer, samples])
        windows: list[np.ndarray] = []
        while len(self._buffer) >= self._window_size:
            windows.append(self._buffer[: self._window_size].copy())
            self._buffer = self._buffer[self._hop_size:]
        return windows

    def reset(self) -> None:
        self._buffer = np.zeros(0, dtype=np.float32)


class MicrophoneCaptureStream:
    """Captures audio from the default mic and delivers framed windows.

    Parameters
    ----------
    on_window : callable receiving (np.ndarray,) with shape (WINDOW_SIZE,).
    on_level  : optional callable receiving (float,) with the RMS of the
                most recent capture block — used for a live level meter.
    """

    def __init__(
        self,
        on_window: Callable[[np.ndarray], None],
        on_level: Optional[Callable[[float], None]] = None,
    ):
        self._on_window = on_window
        self._on_level = on_level
        self._framer = SlidingFrameExtractor()
        self._stream = None  # will be a sounddevice.InputStream
        self._lock = threading.Lock()

    # -- lifecycle -----------------------------------------------------------

    def start(self) -> None:
        import sounddevice as sd

        with self._lock:
            if self._stream is not None:
                return
            self._framer.reset()
            self._stream = sd.InputStream(
                samplerate=SAMPLE_RATE_HZ,
                channels=1,
                dtype="float32",
                blocksize=CAPTURE_BLOCK_SIZE,
                callback=self._audio_callback,
            )
            self._stream.start()

    def stop(self) -> None:
        with self._lock:
            if self._stream is not None:
                self._stream.stop()
                self._stream.close()
                self._stream = None

    # -- internal ------------------------------------------------------------

    def _audio_callback(self, indata, frames, time_info, status):
        """Called on the PortAudio thread for each capture block."""
        mono = indata[:, 0].copy()  # shape (frames,)

        if self._on_level is not None:
            rms = float(np.sqrt(np.mean(mono ** 2)))
            self._on_level(rms)

        windows = self._framer.push(mono)
        for win in windows:
            self._on_window(win)
