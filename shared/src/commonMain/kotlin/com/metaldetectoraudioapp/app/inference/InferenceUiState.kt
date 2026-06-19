package com.metaldetectoraudioapp.app.inference

import com.metaldetectoraudioapp.app.audio.pipeline.AudioSignalStatus

data class InferenceUiState(
    val isRunning: Boolean = false,
    val topLabel: String = "AMBIENT",
    val confidence: Float = 0f,
    val threshold: Float = 0.55f,
    val activeAccelerator: InferenceAccelerator = InferenceAccelerator.UNKNOWN,
    val signalStatus: AudioSignalStatus = AudioSignalStatus(),
    val lastInferenceMs: Long = 0,
    val averageLatencyMs: Float = 0f,
    val droppedFrames: Int = 0,
    val modelVersion: String = "",
    val modelName: String = "",

    /** Non-null when the last inference attempt threw; surfaced so failures aren't silent. */
    val inferenceError: String? = null,

    /**
     * Rolling list of recent non-AMBIENT detections (newest last).
     * Lets the UI show a history log so users don't miss transient chirps.
     */
    val recentDetections: List<RecentDetection> = emptyList(),

    /**
     * When true, a prominent "TARGET DETECTED" banner should be shown.
     * Stays active for [STICKY_TARGET_DURATION_MS] after the last TARGET hit.
     */
    val stickyTargetActive: Boolean = false,

    /** Confidence of the TARGET detection that activated the sticky banner. */
    val stickyTargetConfidence: Float = 0f,

    /** Count of TARGET detections observed in the last 15 seconds. */
    val recentTargetCount: Int = 0
) {
    companion object {
        /** How long after a TARGET detection the sticky banner persists (ms). */
        const val STICKY_TARGET_DURATION_MS = 5_000L

        /** How far back to count recent TARGET detections (ms). */
        const val RECENT_WINDOW_MS = 15_000L

        /** Max entries kept in [recentDetections]. */
        const val MAX_RECENT_DETECTIONS = 5
    }
}
