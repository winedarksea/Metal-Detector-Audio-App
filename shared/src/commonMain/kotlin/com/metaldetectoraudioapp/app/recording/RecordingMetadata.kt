package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.LabelConfidence
import com.metaldetectoraudioapp.app.ui.model.LocationVisibility
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.ui.model.SyncStatus

data class RecordingMetadata(
    val recordingId: String,
    val audioFileName: String,
    val objectLabels: List<RecordingObjectLabel>,
    val pattern: SweepPattern,
    val depthInches: String?,
    val notes: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val includeInTraining: Boolean,
    val createdEpochMs: Long,
    val durationMs: Long,
    val soilType: String? = null,
    val moisture: String? = null,
    val detectorModel: String? = null,
    val searchMode: String? = null,
    val audioProfile: String? = null,
    val sensitivity: String? = null,
    val recoverySpeed: String? = null,
    val stabilizer: String? = null,
    val imageFileName: String? = null,
    val labelConfidence: LabelConfidence = LabelConfidence.UNCONFIRMED,
    val locationVisibility: LocationVisibility = LocationVisibility.PRIVATE,
    val locationLabel: String? = null,
    val syncStatus: SyncStatus = SyncStatus.NOT_UPLOADED,
    val remoteId: String? = null,
    val syncedAtEpochMs: Long? = null,
    val authorUserId: String? = null,
) {
    init {
        validateRecordingObjectLabels(objectLabels)
    }

    val targetNames: List<String> get() = objectLabels.map { it.targetName }
    val classLabel: ClassLabel get() = deriveRecordingClassLabel(objectLabels)
    val mixedTargetAndJunk: Boolean get() = deriveMixedTargetAndJunk(objectLabels)
}
