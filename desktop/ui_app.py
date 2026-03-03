"""tkinter GUI for the metal-detector audio desktop test app.

Mirrors the Android InferenceScreen: waveform canvas, sticky TARGET
banner, per-label confidence bars, and a scrollable detection history.
"""

from __future__ import annotations

import threading
import time
import tkinter as tk
from collections import deque
from tkinter import ttk
from typing import Optional

import numpy as np

from inference_engine import InferenceEngine, InferenceResult

# ---------------------------------------------------------------------------
# Colour palette (matches the Compose theme)
# ---------------------------------------------------------------------------
COLOR_BG = "#121212"
COLOR_SURFACE = "#1E1E1E"
COLOR_ON_SURFACE = "#E0E0E0"
COLOR_PRIMARY = "#4CAF50"            # green
COLOR_TARGET = "#2E7D32"             # dark green
COLOR_TARGET_BG = "#1B5E20"
COLOR_JUNK = "#FFA726"               # orange
COLOR_AMBIENT = "#78909C"            # blue-grey
COLOR_WAVEFORM = "#66BB6A"
COLOR_BANNER_FG = "#FFFFFF"
COLOR_DIVIDER = "#333333"

LABEL_COLORS = {
    "TARGET": COLOR_TARGET,
    "JUNK": COLOR_JUNK,
    "AMBIENT": COLOR_AMBIENT,
}

# Waveform history displayed as a scrolling window (last N samples)
WAVEFORM_DISPLAY_SAMPLES = 8_000     # 0.5 s visible at 16 kHz

# ---------------------------------------------------------------------------
# Main application window
# ---------------------------------------------------------------------------

class MetalDetectorDesktopApp:
    """Single-window tkinter application for real-time metal detection."""

    def __init__(self, engine: InferenceEngine):
        self._engine = engine
        self._running = False

        # Latest audio for waveform drawing
        self._latest_samples: Optional[np.ndarray] = None
        self._rms_level: float = 0.0
        self._latest_result: Optional[InferenceResult] = None
        self._lock = threading.Lock()

        self._build_ui()

    # -- UI construction -----------------------------------------------------

    def _build_ui(self) -> None:
        self.root = tk.Tk()
        self.root.title("Metal Detector Audio — Desktop Test")
        self.root.configure(bg=COLOR_BG)
        self.root.geometry("520x780")
        self.root.minsize(440, 640)

        # Top banner (hidden until TARGET detected)
        self._banner_frame = tk.Frame(self.root, bg=COLOR_TARGET_BG, height=0)
        self._banner_frame.pack(fill="x")
        self._banner_frame.pack_forget()  # hidden initially
        self._banner_label = tk.Label(
            self._banner_frame,
            text="TARGET DETECTED",
            font=("Helvetica", 22, "bold"),
            fg=COLOR_BANNER_FG,
            bg=COLOR_TARGET_BG,
            pady=8,
        )
        self._banner_label.pack()
        self._banner_detail = tk.Label(
            self._banner_frame,
            text="",
            font=("Helvetica", 12),
            fg=COLOR_BANNER_FG,
            bg=COLOR_TARGET_BG,
        )
        self._banner_detail.pack()

        # Waveform canvas
        wave_label = tk.Label(
            self.root, text="Live Waveform", font=("Helvetica", 11),
            fg=COLOR_ON_SURFACE, bg=COLOR_BG, anchor="w",
        )
        wave_label.pack(fill="x", padx=12, pady=(10, 2))
        self._wave_canvas = tk.Canvas(
            self.root, bg=COLOR_SURFACE, height=120, highlightthickness=0,
        )
        self._wave_canvas.pack(fill="x", padx=12)

        # RMS level bar
        rms_frame = tk.Frame(self.root, bg=COLOR_BG)
        rms_frame.pack(fill="x", padx=12, pady=(4, 0))
        tk.Label(rms_frame, text="Level", font=("Helvetica", 9),
                 fg=COLOR_AMBIENT, bg=COLOR_BG).pack(side="left")
        self._rms_bar = tk.Canvas(rms_frame, bg=COLOR_SURFACE,
                                  height=10, highlightthickness=0)
        self._rms_bar.pack(side="left", fill="x", expand=True, padx=(6, 0))

        # Current prediction
        pred_frame = tk.Frame(self.root, bg=COLOR_BG)
        pred_frame.pack(fill="x", padx=12, pady=(12, 4))
        tk.Label(pred_frame, text="Current Prediction",
                 font=("Helvetica", 11), fg=COLOR_ON_SURFACE,
                 bg=COLOR_BG).pack(anchor="w")

        self._pred_label_var = tk.StringVar(value="—")
        self._pred_conf_var = tk.StringVar(value="")
        self._pred_latency_var = tk.StringVar(value="")

        pred_inner = tk.Frame(pred_frame, bg=COLOR_SURFACE, padx=10, pady=8)
        pred_inner.pack(fill="x", pady=(4, 0))
        self._pred_label_widget = tk.Label(
            pred_inner, textvariable=self._pred_label_var,
            font=("Helvetica", 28, "bold"), fg=COLOR_PRIMARY, bg=COLOR_SURFACE,
        )
        self._pred_label_widget.pack(anchor="w")
        tk.Label(pred_inner, textvariable=self._pred_conf_var,
                 font=("Helvetica", 12), fg=COLOR_ON_SURFACE,
                 bg=COLOR_SURFACE).pack(anchor="w")
        tk.Label(pred_inner, textvariable=self._pred_latency_var,
                 font=("Helvetica", 10), fg=COLOR_AMBIENT,
                 bg=COLOR_SURFACE).pack(anchor="w")

        # Per-label confidence bars
        bars_frame = tk.Frame(self.root, bg=COLOR_BG)
        bars_frame.pack(fill="x", padx=12, pady=(8, 4))
        tk.Label(bars_frame, text="Class Scores", font=("Helvetica", 11),
                 fg=COLOR_ON_SURFACE, bg=COLOR_BG).pack(anchor="w")
        self._bar_canvases: dict[str, tk.Canvas] = {}
        self._bar_labels_vars: dict[str, tk.StringVar] = {}
        for label in self._engine.labels:
            row = tk.Frame(bars_frame, bg=COLOR_SURFACE, pady=2)
            row.pack(fill="x", pady=1)
            var = tk.StringVar(value=f"{label}: —")
            tk.Label(row, textvariable=var, font=("Helvetica", 10),
                     fg=COLOR_ON_SURFACE, bg=COLOR_SURFACE, width=16,
                     anchor="w").pack(side="left", padx=(4, 0))
            bar = tk.Canvas(row, bg=COLOR_SURFACE, height=14,
                            highlightthickness=0)
            bar.pack(side="left", fill="x", expand=True, padx=(4, 4))
            self._bar_canvases[label] = bar
            self._bar_labels_vars[label] = var

        # Threshold slider
        thr_frame = tk.Frame(self.root, bg=COLOR_BG)
        thr_frame.pack(fill="x", padx=12, pady=(8, 2))
        self._threshold_var = tk.DoubleVar(value=0.55)
        tk.Label(thr_frame, text="Threshold", font=("Helvetica", 10),
                 fg=COLOR_ON_SURFACE, bg=COLOR_BG).pack(side="left")
        self._thr_value_label = tk.Label(
            thr_frame, text="0.55", font=("Helvetica", 10, "bold"),
            fg=COLOR_PRIMARY, bg=COLOR_BG, width=5,
        )
        self._thr_value_label.pack(side="right")
        thr_slider = ttk.Scale(
            thr_frame, from_=0.1, to=0.95,
            variable=self._threshold_var,
            command=self._on_threshold_change,
        )
        thr_slider.pack(side="left", fill="x", expand=True, padx=6)

        # Detection history
        hist_frame = tk.Frame(self.root, bg=COLOR_BG)
        hist_frame.pack(fill="both", expand=True, padx=12, pady=(8, 4))
        tk.Label(hist_frame, text="Recent Detections (last 30 s)",
                 font=("Helvetica", 11), fg=COLOR_ON_SURFACE,
                 bg=COLOR_BG).pack(anchor="w")
        self._history_text = tk.Text(
            hist_frame, bg=COLOR_SURFACE, fg=COLOR_ON_SURFACE,
            font=("Menlo", 10), height=8, state="disabled",
            highlightthickness=0, wrap="word", padx=6, pady=4,
        )
        self._history_text.pack(fill="both", expand=True, pady=(4, 0))
        self._history_text.tag_configure("target", foreground=COLOR_TARGET)
        self._history_text.tag_configure("junk", foreground=COLOR_JUNK)
        self._history_text.tag_configure("ambient", foreground=COLOR_AMBIENT)
        self._history_text.tag_configure("time", foreground=COLOR_AMBIENT)

        # Start / Stop button
        btn_frame = tk.Frame(self.root, bg=COLOR_BG)
        btn_frame.pack(fill="x", padx=12, pady=(6, 12))
        self._toggle_btn = tk.Button(
            btn_frame, text="▶  Start Listening", font=("Helvetica", 14, "bold"),
            fg=COLOR_BANNER_FG, bg=COLOR_PRIMARY, activebackground="#388E3C",
            activeforeground=COLOR_BANNER_FG, relief="flat", pady=8,
            command=self._on_toggle,
        )
        self._toggle_btn.pack(fill="x")

    # -- callbacks -----------------------------------------------------------

    def _on_threshold_change(self, value: str) -> None:
        v = float(value)
        self._engine.set_threshold(v)
        self._thr_value_label.config(text=f"{v:.2f}")

    def _on_toggle(self) -> None:
        if self._running:
            self.stop_listening()
        else:
            self.start_listening()

    # -- audio lifecycle -----------------------------------------------------

    def start_listening(self) -> None:
        from audio_capture import MicrophoneCaptureStream

        self._running = True
        self._toggle_btn.config(text="■  Stop Listening", bg="#C62828")
        self._mic = MicrophoneCaptureStream(
            on_window=self._on_audio_window,
            on_level=self._on_audio_level,
        )
        self._mic.start()
        self._schedule_ui_refresh()

    def stop_listening(self) -> None:
        self._running = False
        self._toggle_btn.config(text="▶  Start Listening", bg=COLOR_PRIMARY)
        if hasattr(self, "_mic"):
            self._mic.stop()

    # -- audio callbacks (called from PortAudio thread) ----------------------

    def _on_audio_window(self, samples: np.ndarray) -> None:
        result = self._engine.classify_window(samples)
        with self._lock:
            self._latest_samples = samples.copy()
            self._latest_result = result

    def _on_audio_level(self, rms: float) -> None:
        with self._lock:
            self._rms_level = rms

    # -- periodic UI refresh (runs on main thread) ---------------------------

    def _schedule_ui_refresh(self) -> None:
        if not self._running:
            return
        self._refresh_ui()
        self.root.after(80, self._schedule_ui_refresh)  # ~12 fps

    def _refresh_ui(self) -> None:
        with self._lock:
            samples = self._latest_samples
            result = self._latest_result
            rms = self._rms_level

        self._draw_waveform(samples)
        self._draw_rms(rms)
        self._update_prediction(result)
        self._update_bars(result)
        self._update_banner()
        self._update_history()

    # -- drawing helpers -----------------------------------------------------

    def _draw_waveform(self, samples: Optional[np.ndarray]) -> None:
        c = self._wave_canvas
        c.delete("all")
        w = c.winfo_width()
        h = c.winfo_height()
        if w < 2 or h < 2:
            return
        mid = h // 2

        # Centre line
        c.create_line(0, mid, w, mid, fill=COLOR_DIVIDER, width=1)

        if samples is None or len(samples) == 0:
            return

        # Down-sample for display
        display_len = min(len(samples), WAVEFORM_DISPLAY_SAMPLES)
        step = max(1, display_len // w)
        points = []
        for i in range(0, display_len, step):
            x = int(i / display_len * w)
            y = int(mid - samples[i] * mid * 0.9)
            points.append(x)
            points.append(y)

        if len(points) >= 4:
            c.create_line(*points, fill=COLOR_WAVEFORM, width=1, smooth=True)

    def _draw_rms(self, rms: float) -> None:
        c = self._rms_bar
        c.delete("all")
        w = c.winfo_width()
        h = c.winfo_height()
        bar_w = min(int(rms * w * 8), w)  # scale for visibility
        color = COLOR_PRIMARY if rms < 0.5 else "#FFA726" if rms < 0.8 else "#C62828"
        c.create_rectangle(0, 0, bar_w, h, fill=color, outline="")

    def _update_prediction(self, result: Optional[InferenceResult]) -> None:
        if result is None:
            return
        color = LABEL_COLORS.get(result.top_label, COLOR_ON_SURFACE)
        self._pred_label_var.set(result.top_label)
        self._pred_label_widget.config(fg=color)
        self._pred_conf_var.set(f"Confidence: {result.top_score:.1%}")
        self._pred_latency_var.set(f"Inference: {result.inference_time_ms:.1f} ms")

    def _update_bars(self, result: Optional[InferenceResult]) -> None:
        if result is None:
            return
        for label, canvas in self._bar_canvases.items():
            score = result.per_label_scores.get(label, 0.0)
            self._bar_labels_vars[label].set(f"{label}: {score:.1%}")
            canvas.delete("all")
            w = canvas.winfo_width()
            h = canvas.winfo_height()
            bar_w = int(score * w)
            color = LABEL_COLORS.get(label, COLOR_ON_SURFACE)
            canvas.create_rectangle(0, 0, bar_w, h, fill=color, outline="")

    def _update_banner(self) -> None:
        if self._engine.sticky_target_active:
            conf = self._engine.sticky_target_confidence
            hits = self._engine.recent_target_count
            self._banner_detail.config(
                text=f"{conf:.0%} confidence  ·  {hits} hit(s) in last 30 s"
            )
            self._banner_frame.pack(fill="x", before=self._wave_canvas.master)
        else:
            self._banner_frame.pack_forget()

    def _update_history(self) -> None:
        detections = self._engine.recent_detections
        now_ms = time.time() * 1000.0
        self._history_text.config(state="normal")
        self._history_text.delete("1.0", "end")
        if not detections:
            self._history_text.insert("end", "  No detections yet", "ambient")
        else:
            for d in detections:
                age_s = (now_ms - d.timestamp_ms) / 1000.0
                tag = d.label.lower() if d.label.lower() in ("target", "junk") else "ambient"
                self._history_text.insert("end", f"  ● {d.label}", tag)
                self._history_text.insert("end", f"  {d.confidence:.0%}", tag)
                self._history_text.insert("end", f"   {age_s:.0f}s ago\n", "time")
        self._history_text.config(state="disabled")

    # -- main loop -----------------------------------------------------------

    def run(self) -> None:
        """Start the tkinter event loop. Blocks until the window closes."""
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.mainloop()

    def _on_close(self) -> None:
        self.stop_listening()
        self.root.destroy()
