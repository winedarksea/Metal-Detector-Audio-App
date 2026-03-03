package com.metaldetectoraudioapp.app.inference

/**
 * A single past detection result kept in the rolling history buffer.
 * Used by the UI to show recent detections so users don't miss transient
 * TARGET alerts between inference cycles.
 */
data class RecentDetection(
    val label: String,
    val confidence: Float,
    val timestampMs: Long
)
