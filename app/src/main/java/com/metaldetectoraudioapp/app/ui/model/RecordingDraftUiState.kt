package com.metaldetectoraudioapp.app.ui.model

data class RecordingDraftUiState(
    val targetNameInput: String = "",
    val classLabel: ClassLabel = ClassLabel.TARGET,
    val pattern: SweepPattern = SweepPattern.SWING,
    val depthInches: String = "",
    val notesInput: String = "",
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val mixedFlag: Boolean = false,
    val includeInTraining: Boolean = true,
    val soilType: String = "",
    val moisture: String = "",
    val detectorModel: String = ""
)
