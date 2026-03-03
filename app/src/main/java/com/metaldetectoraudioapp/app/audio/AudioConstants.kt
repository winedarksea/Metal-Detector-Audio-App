package com.metaldetectoraudioapp.app.audio

object AudioConstants {
    const val INFERENCE_SAMPLE_RATE_HZ = 16_000
    const val INFERENCE_CHANNELS = 1
    const val INFERENCE_CAPTURE_BLOCK_SIZE = 1_024

    /** 0.5 s window matches typical metal-detector chirp duration (50-300 ms).
     *  To test a larger window (e.g. 1.0 s), change this single line to 16_000.
     *  NOTE: Changing this requires retraining the model with --window-size set to the same value. */
    const val INFERENCE_WINDOW_SIZE_SAMPLES = 8_000

    /** Half-window hop gives ~4 predictions per second at 0.5 s. */
    const val INFERENCE_HOP_SIZE_SAMPLES = INFERENCE_WINDOW_SIZE_SAMPLES / 2

    const val SIGNAL_PRESENT_RMS_THRESHOLD = 0.02f
    const val CLIPPING_THRESHOLD = 0.98f

    const val RECORDING_SAMPLE_RATE_HZ = 48_000
    const val RECORDING_CHANNELS = 1
}
