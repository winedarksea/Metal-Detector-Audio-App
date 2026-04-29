package com.metaldetectoraudioapp.app.inference

enum class InferenceAccelerator(
    val shortLabel: String,
) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU"),
    UNKNOWN("?")
}