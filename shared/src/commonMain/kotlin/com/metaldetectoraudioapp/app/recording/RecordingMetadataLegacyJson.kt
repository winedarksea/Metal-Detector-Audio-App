package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parser for the legacy `recordings_metadata.json` format (a JSON array of recording objects).
 *
 * Newer builds persist recordings as CSV via [RecordingMetadataCsvCodec]; this exists only to
 * migrate older installs on first launch. Pure kotlinx.serialization so it is multiplatform-ready.
 */
object RecordingMetadataLegacyJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseArray(rawJson: String): List<RecordingMetadata> {
        val array = runCatching { json.parseToJsonElement(rawJson).jsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { fromObject(element.jsonObject) }.getOrNull()
        }
    }

    private fun fromObject(obj: JsonObject): RecordingMetadata {
        val targetNames = obj["target_names"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: obj.optString("target_name")?.let { listOf(it) }
            ?: emptyList()

        return RecordingMetadata(
            recordingId = obj["recording_id"]!!.jsonPrimitive.content,
            audioFileName = obj["audio_file_name"]!!.jsonPrimitive.content,
            targetNames = targetNames,
            classLabel = ClassLabel.fromWireValue(obj.optString("class_label") ?: ""),
            pattern = SweepPattern.fromWireValue(obj.optString("pattern") ?: ""),
            depthInches = obj.optString("depth_inches"),
            notes = obj.optString("notes"),
            gpsLatitude = obj["gps_latitude"]?.jsonPrimitive?.doubleOrNull,
            gpsLongitude = obj["gps_longitude"]?.jsonPrimitive?.doubleOrNull,
            mixedFlag = obj["mixed_flag"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeInTraining = obj["include_in_training"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdEpochMs = obj["created_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            soilType = obj.optString("soil_type"),
            moisture = obj.optString("moisture"),
            detectorModel = obj.optString("detector_model"),
            searchMode = obj.optString("search_mode"),
            sensitivity = obj.optString("sensitivity"),
            recoverySpeed = obj.optString("recovery_speed"),
            stabilizer = obj.optString("stabilizer"),
            imageFileName = obj.optString("image_file_name"),
        )
    }

    /** Mirrors the old org.json behaviour: blank or literal "null" strings become null. */
    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
