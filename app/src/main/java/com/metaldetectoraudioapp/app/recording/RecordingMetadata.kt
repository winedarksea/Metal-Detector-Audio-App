package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import org.json.JSONArray
import org.json.JSONObject

data class RecordingMetadata(
    val recordingId: String,
    val audioFileName: String,
    val targetNames: List<String>,
    val classLabel: ClassLabel,
    val pattern: SweepPattern,
    val depthInches: String?,
    val notes: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val mixedFlag: Boolean,
    val includeInTraining: Boolean,
    val createdEpochMs: Long,
    val durationMs: Long
) {
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
            .put("mixed_flag", mixedFlag)
            .put("include_in_training", includeInTraining)
            .put("created_epoch_ms", createdEpochMs)
            .put("duration_ms", durationMs)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordingMetadata {
            val targetNameJson = json.optJSONArray("target_names") ?: JSONArray()
            val targetNames = buildList {
                for (index in 0 until targetNameJson.length()) {
                    add(targetNameJson.getString(index))
                }
            }

            return RecordingMetadata(
                recordingId = json.getString("recording_id"),
                audioFileName = json.getString("audio_file_name"),
                targetNames = targetNames,
                classLabel = ClassLabel.fromWireValue(json.optString("class_label")),
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
                mixedFlag = json.optBoolean("mixed_flag", false),
                includeInTraining = json.optBoolean("include_in_training", false),
                createdEpochMs = json.optLong("created_epoch_ms", 0L),
                durationMs = json.optLong("duration_ms", 0L)
            )
        }
    }
}
