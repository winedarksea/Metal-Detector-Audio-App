package com.metaldetectoraudioapp.app.export

import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DatasetBundleManager(
    private val recordingRepository: RecordingRepository,
    private val cacheDirectory: File
) {

    fun exportBundle(outputStream: OutputStream) {
        val recordings = recordingRepository.listRecordings()

        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry("metadata/recordings_metadata.json"))
            val metadataJson = buildMetadataJson(recordings)
            zip.write(metadataJson.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("metadata/split_manifest.json"))
            zip.write(buildSplitManifestJson(recordings).toByteArray())
            zip.closeEntry()

            recordings.forEach { metadata ->
                val file = recordingRepository.resolveAudioFile(metadata) ?: return@forEach
                zip.putNextEntry(ZipEntry("audio/${metadata.audioFileName}"))
                file.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    fun importBundle(inputStream: InputStream): Int {
        val unzipDirectory = File(cacheDirectory, "bundle_import_${System.currentTimeMillis()}").apply { mkdirs() }
        val audioDirectory = File(unzipDirectory, "audio").apply { mkdirs() }
        var metadataFile: File? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    entry = zip.nextEntry
                    continue
                }

                val outputFile = File(unzipDirectory, entry.name)
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { output ->
                    zip.copyTo(output)
                }

                if (entry.name == "metadata/recordings_metadata.json") {
                    metadataFile = outputFile
                }
                if (entry.name.startsWith("audio/")) {
                    val normalizedFile = File(audioDirectory, outputFile.name)
                    if (normalizedFile.absolutePath != outputFile.absolutePath) {
                        outputFile.copyTo(normalizedFile, overwrite = true)
                        outputFile.delete()
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val importedMetadata = parseMetadataJson(metadataFile?.takeIf { it.exists() }?.readText())
        var importedCount = 0
        importedMetadata.forEach { metadata ->
            val sourceFile = File(audioDirectory, metadata.audioFileName)
            if (!sourceFile.exists()) {
                return@forEach
            }

            val tempCopy = File(cacheDirectory, "import_${System.nanoTime()}.wav")
            sourceFile.copyTo(tempCopy, overwrite = true)

            val saved = recordingRepository.saveCapturedRecording(
                capturedRecording = CapturedRecording(tempCopy, metadata.durationMs),
                labelDraft = RecordingLabelDraft(
                    targetNames = metadata.targetNames,
                    classLabel = metadata.classLabel,
                    pattern = metadata.pattern,
                    depthInches = metadata.depthInches,
                    notes = metadata.notes,
                    gpsLatitude = metadata.gpsLatitude,
                    gpsLongitude = metadata.gpsLongitude,
                    mixedFlag = metadata.mixedFlag,
                    includeInTraining = metadata.includeInTraining
                )
            )

            recordingRepository.updateRecording(
                saved.copy(
                    createdEpochMs = metadata.createdEpochMs,
                    durationMs = metadata.durationMs
                )
            )
            importedCount += 1
        }

        unzipDirectory.deleteRecursively()
        return importedCount
    }

    private fun buildMetadataJson(recordings: List<RecordingMetadata>): String {
        val content = recordings.joinToString(prefix = "[", postfix = "]", separator = ",") {
            it.toJson().toString()
        }
        return content
    }

    private fun buildSplitManifestJson(recordings: List<RecordingMetadata>): String {
        val trainingIds = recordings.filter { it.includeInTraining }.map { it.recordingId }
        val evalIds = trainingIds
        return org.json.JSONObject()
            .put("strategy", "all_samples_for_train_and_validation")
            .put("train_recording_ids", org.json.JSONArray(trainingIds))
            .put("validation_recording_ids", org.json.JSONArray(evalIds))
            .toString()
    }

    private fun parseMetadataJson(raw: String?): List<RecordingMetadata> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val array = org.json.JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(RecordingMetadata.fromJson(item))
            }
        }
    }
}
