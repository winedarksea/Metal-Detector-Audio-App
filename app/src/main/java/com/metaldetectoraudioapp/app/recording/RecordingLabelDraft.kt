package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import java.io.File

data class RecordingLabelDraft(
    val objectLabels: List<RecordingObjectLabel>,
    val pattern: SweepPattern,
    val depthInches: String?,
    val notes: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val includeInTraining: Boolean,
    val soilType: String? = null,
    val moisture: String? = null,
    val detectorModel: String? = null,
    val searchMode: String? = null,
    val audioProfile: String? = null,
    val sensitivity: String? = null,
    val recoverySpeed: String? = null,
    val stabilizer: String? = null,
    val imageTempFile: File? = null,
) {
    init {
        validateRecordingObjectLabels(objectLabels)
    }
}
