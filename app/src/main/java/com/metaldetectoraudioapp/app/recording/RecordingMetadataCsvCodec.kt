package com.metaldetectoraudioapp.app.recording

import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.SweepPattern

object RecordingMetadataCsvCodec {
    private val columns = listOf(
        "recording_id",
        "audio_file_name",
        "target_name",
        "label_class",
        "class_label",
        "pattern",
        "depth_inches",
        "notes",
        "gps_latitude",
        "gps_longitude",
        "mixed_target_and_junk",
        "include_in_training",
        "created_epoch_ms",
        "duration_ms",
        "soil_type",
        "moisture",
        "detector_model",
        "search_mode",
        "sensitivity",
        "recovery_speed",
        "stabilizer",
        "image_file_name",
    )

    fun serialize(recordings: List<RecordingMetadata>): String {
        val builder = StringBuilder()
        builder.append(columns.joinToString(",")).append('\n')

        recordings.forEach { metadata ->
            metadata.objectLabels.forEach { objectLabel ->
                val row = listOf(
                    metadata.recordingId,
                    metadata.audioFileName,
                    objectLabel.targetName,
                    objectLabel.labelClass.name,
                    metadata.classLabel.name,
                    metadata.pattern.name,
                    metadata.depthInches.orEmpty(),
                    metadata.notes.orEmpty(),
                    metadata.gpsLatitude?.toString().orEmpty(),
                    metadata.gpsLongitude?.toString().orEmpty(),
                    metadata.mixedTargetAndJunk.toString(),
                    metadata.includeInTraining.toString(),
                    metadata.createdEpochMs.toString(),
                    metadata.durationMs.toString(),
                    metadata.soilType.orEmpty(),
                    metadata.moisture.orEmpty(),
                    metadata.detectorModel.orEmpty(),
                    metadata.searchMode.orEmpty(),
                    metadata.sensitivity.orEmpty(),
                    metadata.recoverySpeed.orEmpty(),
                    metadata.stabilizer.orEmpty(),
                    metadata.imageFileName.orEmpty(),
                )
                builder.append(row.joinToString(",") { escapeCsv(it) }).append('\n')
            }
        }

        return builder.toString()
    }

    fun parse(raw: String): List<RecordingMetadata> {
        val rows = parseCsvRows(raw)
        if (rows.isEmpty()) {
            return emptyList()
        }

        val header = rows.first().map { it.trim().lowercase() }
        val hasObjectLevelClasses = "label_class" in header
        val byRecordingId = linkedMapOf<String, MutableMetadataRow>()

        rows.drop(1).forEach { row ->
            val recordingId = valueFor(header, row, "recording_id").trim()
            if (recordingId.isBlank()) {
                return@forEach
            }

            val targetName = valueFor(header, row, "target_name").trim()
            val labelClass = if (hasObjectLevelClasses) {
                ClassLabel.fromWireValue(valueFor(header, row, "label_class"))
            } else {
                ClassLabel.AMBIENT
            }
            val existing = byRecordingId[recordingId]
            if (existing != null) {
                if (targetName.isNotBlank()) {
                    existing.objectLabels += RecordingObjectLabel(targetName, labelClass)
                }
                return@forEach
            }

            val metadataRow = MutableMetadataRow(
                recordingId = recordingId,
                audioFileName = valueFor(header, row, "audio_file_name"),
                objectLabels = mutableListOf<RecordingObjectLabel>().also { list ->
                    if (targetName.isNotBlank()) {
                        list += RecordingObjectLabel(targetName, labelClass)
                    }
                },
                pattern = SweepPattern.fromWireValue(valueFor(header, row, "pattern")),
                depthInches = blankToNull(valueFor(header, row, "depth_inches")),
                notes = blankToNull(valueFor(header, row, "notes")),
                gpsLatitude = parseDoubleOrNull(valueFor(header, row, "gps_latitude")),
                gpsLongitude = parseDoubleOrNull(valueFor(header, row, "gps_longitude")),
                includeInTraining = parseBoolean(valueFor(header, row, "include_in_training")),
                createdEpochMs = valueFor(header, row, "created_epoch_ms").toLongOrNull() ?: 0L,
                durationMs = valueFor(header, row, "duration_ms").toLongOrNull() ?: 0L,
                soilType = blankToNull(valueFor(header, row, "soil_type")),
                moisture = blankToNull(valueFor(header, row, "moisture")),
                detectorModel = blankToNull(valueFor(header, row, "detector_model")),
                searchMode = blankToNull(valueFor(header, row, "search_mode")),
                sensitivity = blankToNull(valueFor(header, row, "sensitivity")),
                recoverySpeed = blankToNull(valueFor(header, row, "recovery_speed")),
                stabilizer = blankToNull(valueFor(header, row, "stabilizer")),
                imageFileName = blankToNull(valueFor(header, row, "image_file_name")),
            )
            byRecordingId[recordingId] = metadataRow
        }

        return byRecordingId.values.map { row ->
            RecordingMetadata(
                recordingId = row.recordingId,
                audioFileName = row.audioFileName,
                objectLabels = row.objectLabels.ifEmpty {
                    listOf(RecordingObjectLabel("ambient:background:unknown", ClassLabel.AMBIENT))
                },
                pattern = row.pattern,
                depthInches = row.depthInches,
                notes = row.notes,
                gpsLatitude = row.gpsLatitude,
                gpsLongitude = row.gpsLongitude,
                includeInTraining = row.includeInTraining,
                createdEpochMs = row.createdEpochMs,
                durationMs = row.durationMs,
                soilType = row.soilType,
                moisture = row.moisture,
                detectorModel = row.detectorModel,
                searchMode = row.searchMode,
                sensitivity = row.sensitivity,
                recoverySpeed = row.recoverySpeed,
                stabilizer = row.stabilizer,
                imageFileName = row.imageFileName,
            )
        }
    }

    private fun valueFor(header: List<String>, row: List<String>, key: String): String {
        val index = header.indexOf(key)
        if (index < 0) {
            return ""
        }
        return row.getOrElse(index) { "" }
    }

    private fun parseDoubleOrNull(value: String): Double? = value.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()

    private fun parseBoolean(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "true", "1", "yes", "y" -> true
            else -> false
        }
    }

    private fun blankToNull(value: String): String? = value.trim().takeIf { it.isNotBlank() }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('"') || escaped.contains('\n') || escaped.contains('\r')) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun parseCsvRows(raw: String): List<List<String>> {
        if (raw.isBlank()) {
            return emptyList()
        }

        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < raw.length) {
            val ch = raw[index]
            when {
                ch == '"' -> {
                    val nextIsQuote = index + 1 < raw.length && raw[index + 1] == '"'
                    if (inQuotes && nextIsQuote) {
                        currentCell.append('"')
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }

                ch == ',' && !inQuotes -> {
                    currentRow += currentCell.toString()
                    currentCell.clear()
                }

                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (ch == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') {
                        index += 1
                    }
                    currentRow += currentCell.toString()
                    currentCell.clear()
                    if (currentRow.any { it.isNotBlank() }) {
                        rows += currentRow.toList()
                    }
                    currentRow.clear()
                }

                else -> currentCell.append(ch)
            }
            index += 1
        }

        currentRow += currentCell.toString()
        if (currentRow.any { it.isNotBlank() }) {
            rows += currentRow
        }

        return rows
    }

    private data class MutableMetadataRow(
        val recordingId: String,
        val audioFileName: String,
        val objectLabels: MutableList<RecordingObjectLabel>,
        val pattern: SweepPattern,
        val depthInches: String?,
        val notes: String?,
        val gpsLatitude: Double?,
        val gpsLongitude: Double?,
        val includeInTraining: Boolean,
        val createdEpochMs: Long,
        val durationMs: Long,
        val soilType: String?,
        val moisture: String?,
        val detectorModel: String?,
        val searchMode: String?,
        val sensitivity: String?,
        val recoverySpeed: String?,
        val stabilizer: String?,
        val imageFileName: String?,
    )
}
