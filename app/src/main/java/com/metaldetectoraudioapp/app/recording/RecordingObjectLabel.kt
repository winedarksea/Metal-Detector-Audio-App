package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel

data class RecordingObjectLabel(
    val targetName: String,
    val labelClass: ClassLabel,
)

fun deriveRecordingClassLabel(objectLabels: List<RecordingObjectLabel>): ClassLabel = when {
    objectLabels.any { it.labelClass == ClassLabel.TARGET } -> ClassLabel.TARGET
    objectLabels.any { it.labelClass == ClassLabel.JUNK } -> ClassLabel.JUNK
    else -> ClassLabel.AMBIENT
}

fun deriveMixedTargetAndJunk(objectLabels: List<RecordingObjectLabel>): Boolean =
    objectLabels.any { it.labelClass == ClassLabel.TARGET } &&
        objectLabels.any { it.labelClass == ClassLabel.JUNK }

fun validateRecordingObjectLabels(objectLabels: List<RecordingObjectLabel>) {
    require(objectLabels.isNotEmpty()) { "At least one object label is required" }
    objectLabels.forEach { objectLabel ->
        val parts = objectLabel.targetName.split(":")
        require(parts.size == 3 && parts.all(String::isNotBlank)) {
            "Object labels must use category:object:material"
        }
    }
    val hasAmbient = objectLabels.any { it.labelClass == ClassLabel.AMBIENT }
    require(!hasAmbient || objectLabels.size == 1) {
        "AMBIENT must be the only label on a recording"
    }
    if (hasAmbient) {
        require(objectLabels.single().targetName == "ambient:background:unknown") {
            "AMBIENT recordings must use ambient:background:unknown"
        }
    }
}
