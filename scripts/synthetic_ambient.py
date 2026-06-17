"""Synthetic AMBIENT waveform generators for starter-model training.

These windows are deliberately conservative: they broaden background coverage without
teaching the model that target-like detector tones are ordinary ambience.
"""

from __future__ import annotations

from typing import Callable

import numpy as np

AMBIENT_NOISE_PROFILE_NAMES = [
    "band_limited_white_brown",
    "band_limited_pink_brown",
    "lfo_modulated_noise",
    "silence_or_dropout",
]

SYNTHETIC_AMBIENT_LOW_CUTOFF_HZ = 100.0
SYNTHETIC_AMBIENT_HIGH_CUTOFF_HZ = 4000.0


def fft_bandpass_filter(
    samples: np.ndarray,
    sample_rate: int,
    low_cutoff_hz: float = SYNTHETIC_AMBIENT_LOW_CUTOFF_HZ,
    high_cutoff_hz: float = SYNTHETIC_AMBIENT_HIGH_CUTOFF_HZ,
) -> np.ndarray:
    """Band-limit synthetic ambience to a detector-audio-like passband."""
    if samples.size == 0:
        return samples.astype(np.float32)

    spectrum = np.fft.rfft(samples.astype(np.float32))
    frequencies_hz = np.fft.rfftfreq(samples.size, d=1.0 / float(sample_rate))
    passband_mask = (frequencies_hz >= low_cutoff_hz) & (frequencies_hz <= high_cutoff_hz)
    spectrum[~passband_mask] = 0.0
    return np.fft.irfft(spectrum, n=samples.size).astype(np.float32)


def synthesize_ambient_noise_windows(
    target_count: int,
    window_size: int,
    random_seed: int,
    sample_rate: int = 16_000,
) -> np.ndarray:
    """Generate deterministic synthetic AMBIENT windows across fixed profile quotas."""
    if target_count <= 0:
        return np.zeros((0, window_size), dtype=np.float32)

    rng = np.random.default_rng(random_seed)
    generators: list[Callable[[np.random.Generator, int, int], np.ndarray]] = [
        _band_limited_white_brown_window,
        _band_limited_white_brown_window,
        _band_limited_pink_brown_window,
        _lfo_modulated_noise_window,
        _silence_or_dropout_window,
    ]
    windows = np.zeros((target_count, window_size), dtype=np.float32)

    for index in range(target_count):
        generator = generators[index % len(generators)]
        windows[index] = _clip_float32(generator(rng, window_size, sample_rate))

    return windows


def _band_limited_white_brown_window(
    rng: np.random.Generator,
    window_size: int,
    sample_rate: int,
) -> np.ndarray:
    white = rng.normal(0.0, 1.0, window_size).astype(np.float32)
    brown = _brown_noise(rng, window_size)
    white_weight = float(rng.uniform(0.35, 0.8))
    mixed = white_weight * white + (1.0 - white_weight) * brown
    return _scale_to_rms(
        fft_bandpass_filter(mixed, sample_rate),
        target_rms=float(rng.uniform(0.05, 0.15)),
    )


def _band_limited_pink_brown_window(
    rng: np.random.Generator,
    window_size: int,
    sample_rate: int,
) -> np.ndarray:
    pink = _pink_noise(rng, window_size)
    brown = _brown_noise(rng, window_size)
    pink_weight = float(rng.uniform(0.45, 0.75))
    mixed = pink_weight * pink + (1.0 - pink_weight) * brown
    return _scale_to_rms(
        fft_bandpass_filter(mixed, sample_rate),
        target_rms=float(rng.uniform(0.05, 0.15)),
    )


def _lfo_modulated_noise_window(
    rng: np.random.Generator,
    window_size: int,
    sample_rate: int,
) -> np.ndarray:
    white = rng.normal(0.0, 1.0, window_size).astype(np.float32)
    pink = _pink_noise(rng, window_size)
    brown = _brown_noise(rng, window_size)
    mixed = 0.35 * white + 0.4 * pink + 0.25 * brown
    band_limited = fft_bandpass_filter(mixed, sample_rate)

    time_seconds = np.arange(window_size, dtype=np.float32) / float(sample_rate)
    lfo_hz = float(rng.uniform(1.0, 3.0))
    phase = float(rng.uniform(0.0, 2.0 * np.pi))
    modulation_depth = float(rng.uniform(0.35, 0.75))
    envelope = 1.0 - modulation_depth * (0.5 + 0.5 * np.sin(2.0 * np.pi * lfo_hz * time_seconds + phase))
    modulated = band_limited * envelope.astype(np.float32)
    return _scale_to_rms(modulated, target_rms=float(rng.uniform(0.05, 0.15)))


def _silence_or_dropout_window(
    rng: np.random.Generator,
    window_size: int,
    sample_rate: int,
) -> np.ndarray:
    if bool(rng.integers(0, 2)):
        return np.zeros(window_size, dtype=np.float32)

    base = _scale_to_rms(
        fft_bandpass_filter(_pink_noise(rng, window_size), sample_rate),
        target_rms=float(rng.uniform(0.035, 0.08)),
    )
    dropout_start = int(
        rng.integers(
            window_size // 8,
            max(window_size // 8 + 1, window_size * 5 // 8),
        )
    )
    dropout_length = int(
        rng.integers(
            window_size // 12,
            max(window_size // 12 + 1, window_size // 3),
        )
    )
    dropout_end = min(window_size, dropout_start + dropout_length)

    envelope = np.ones(window_size, dtype=np.float32)
    envelope[dropout_start:dropout_end] = 0.0
    return base * envelope


def _brown_noise(rng: np.random.Generator, window_size: int) -> np.ndarray:
    brown = np.cumsum(rng.normal(0.0, 0.08, window_size)).astype(np.float32)
    return _normalize_peak(brown)


def _pink_noise(rng: np.random.Generator, window_size: int) -> np.ndarray:
    white_spectrum = np.fft.rfft(rng.normal(0.0, 1.0, window_size).astype(np.float32))
    frequencies = np.fft.rfftfreq(window_size)
    scale = np.ones_like(frequencies, dtype=np.float32)
    non_zero = frequencies > 0
    scale[non_zero] = 1.0 / np.sqrt(frequencies[non_zero])
    scale[~non_zero] = 0.0
    pink = np.fft.irfft(white_spectrum * scale, n=window_size).astype(np.float32)
    return _normalize_peak(pink)


def _normalize_peak(samples: np.ndarray) -> np.ndarray:
    max_abs = float(np.max(np.abs(samples))) if samples.size else 0.0
    if max_abs <= 1e-8:
        return np.zeros_like(samples, dtype=np.float32)
    return (samples / max_abs).astype(np.float32)


def _scale_to_rms(samples: np.ndarray, target_rms: float) -> np.ndarray:
    rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float32)))) if samples.size else 0.0
    if rms <= 1e-8:
        return np.zeros_like(samples, dtype=np.float32)
    return (samples * (target_rms / rms)).astype(np.float32)


def _clip_float32(samples: np.ndarray) -> np.ndarray:
    return np.clip(samples, -1.0, 1.0).astype(np.float32)
