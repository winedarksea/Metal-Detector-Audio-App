package com.metaldetectoraudioapp.app.ui.model

data class RecordingDraftUiState(
    val targetNameInput: String = "",
    val pattern: SweepPattern = SweepPattern.SWING,
    val depthInches: String = "",
    val notesInput: String = "",
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val includeInTraining: Boolean = true,
    val soilType: String = "",
    val moisture: String = "",
    val detectorModel: String = DEFAULT_DETECTOR_MODEL,
    val searchMode: String = DEFAULT_SEARCH_MODE,
    val sensitivity: String = "23",
    val recoverySpeed: String = "4",
    val stabilizer: String = "5",
)
