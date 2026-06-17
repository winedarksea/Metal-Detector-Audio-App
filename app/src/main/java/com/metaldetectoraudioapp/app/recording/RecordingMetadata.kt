package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import org.json.JSONArray
import org.json.JSONObject

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
) {
    init {
        validateRecordingObjectLabels(objectLabels)
    }

    val targetNames: List<String> get() = objectLabels.map { it.targetName }
    val classLabel: ClassLabel get() = deriveRecordingClassLabel(objectLabels)
    val mixedTargetAndJunk: Boolean get() = deriveMixedTargetAndJunk(objectLabels)

    fun toJson(): JSONObject {
        return JSONObject()
            .put("recording_id", recordingId)
            .put("audio_file_name", audioFileName)
            .put("target_names", JSONArray(targetNames))
            .put("class_label", classLabel.name)
            .put("pattern", pattern.name)
            .put("depth_inches", depthInches ?: JSONObject.NULL)
            .put("notes", notes ?: JSONObject.NULL)
            .put("gps_latitude", gpsLatitude ?: JSONObject.NULL)
            .put("gps_longitude", gpsLongitude ?: JSONObject.NULL)
            .put("mixed_target_and_junk", mixedTargetAndJunk)
            .put("include_in_training", includeInTraining)
            .put("created_epoch_ms", createdEpochMs)
            .put("duration_ms", durationMs)
            .put("soil_type", soilType ?: JSONObject.NULL)
            .put("moisture", moisture ?: JSONObject.NULL)
            .put("detector_model", detectorModel ?: JSONObject.NULL)
            .put("search_mode", searchMode ?: JSONObject.NULL)
            .put("audio_profile", audioProfile ?: JSONObject.NULL)
            .put("sensitivity", sensitivity ?: JSONObject.NULL)
            .put("recovery_speed", recoverySpeed ?: JSONObject.NULL)
            .put("stabilizer", stabilizer ?: JSONObject.NULL)
            .put("image_file_name", imageFileName ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordingMetadata {
            val targetNameJson = json.optJSONArray("target_names")
            val targetNames = when {
                targetNameJson != null -> buildList {
                    for (index in 0 until targetNameJson.length()) {
                        add(targetNameJson.getString(index))
                    }
                }

                json.has("target_name") -> listOfNotNull(
                    json.optString("target_name").takeIf { it.isNotBlank() && it != "null" }
                )

                else -> emptyList()
            }

            return RecordingMetadata(
                recordingId = json.getString("recording_id"),
                audioFileName = json.getString("audio_file_name"),
                objectLabels = targetNames.map {
                    RecordingObjectLabel(
                        targetName = it,
                        labelClass = ClassLabel.AMBIENT,
                    )
                },
                pattern = SweepPattern.fromWireValue(json.optString("pattern")),
                depthInches = json.optString("depth_inches").takeIf { it.isNotBlank() && it != "null" },
                notes = json.optString("notes").takeIf { it.isNotBlank() && it != "null" },
                gpsLatitude = if (json.has("gps_latitude") && !json.isNull("gps_latitude")) {
                    json.getDouble("gps_latitude")
                } else {
                    null
                },
                gpsLongitude = if (json.has("gps_longitude") && !json.isNull("gps_longitude")) {
                    json.getDouble("gps_longitude")
                } else {
                    null
                },
                includeInTraining = json.optBoolean("include_in_training", false),
                createdEpochMs = json.optLong("created_epoch_ms", 0L),
                durationMs = json.optLong("duration_ms", 0L),
                soilType = json.optString("soil_type").takeIf { it.isNotBlank() && it != "null" },
                moisture = json.optString("moisture").takeIf { it.isNotBlank() && it != "null" },
                detectorModel = json.optString("detector_model").takeIf { it.isNotBlank() && it != "null" },
                searchMode = json.optString("search_mode").takeIf { it.isNotBlank() && it != "null" },
                audioProfile = json.optString("audio_profile").takeIf { it.isNotBlank() && it != "null" },
                sensitivity = json.optString("sensitivity").takeIf { it.isNotBlank() && it != "null" },
                recoverySpeed = json.optString("recovery_speed").takeIf { it.isNotBlank() && it != "null" },
                stabilizer = json.optString("stabilizer").takeIf { it.isNotBlank() && it != "null" },
                imageFileName = json.optString("image_file_name").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}
