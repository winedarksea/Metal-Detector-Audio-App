package com.metaldetectoraudioapp.app.audio

object AudioConstants {
    const val INFERENCE_SAMPLE_RATE_HZ = 16_000
    const val INFERENCE_CHANNELS = 1
    const val INFERENCE_CAPTURE_BLOCK_SIZE = 1_024

    /** 0.5 s window matches typical metal-detector chirp duration (50-300 ms). */
    const val INFERENCE_WINDOW_SIZE_SAMPLES = 8_000

    /** 0.25 s hop gives ~4 predictions per second for responsive detection. */
    const val INFERENCE_HOP_SIZE_SAMPLES = 4_000

    const val SIGNAL_PRESENT_RMS_THRESHOLD = 0.02f
    const val CLIPPING_THRESHOLD = 0.98f

    const val RECORDING_SAMPLE_RATE_HZ = 48_000
    const val RECORDING_CHANNELS = 1
}
