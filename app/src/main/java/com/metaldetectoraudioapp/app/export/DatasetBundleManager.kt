package com.metaldetectoraudioapp.app.export

import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingMetadata
import com.metaldetectoraudioapp.app.recording.RecordingMetadataCsvCodec
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DatasetBundleManager(
    private val recordingRepository: RecordingRepository,
    private val cacheDirectory: File,
) {

    fun exportBundle(outputStream: OutputStream) {
        val recordings = recordingRepository.listRecordings()

        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry("metadata/recordings_metadata.csv"))
            val metadataCsv = RecordingMetadataCsvCodec.serialize(recordings)
            zip.write(metadataCsv.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("metadata/split_manifest.json"))
            zip.write(buildSplitManifestJson(recordings).toByteArray())
            zip.closeEntry()

            recordings.forEach { metadata ->
                val audioFile = recordingRepository.resolveAudioFile(metadata) ?: return@forEach
                zip.putNextEntry(ZipEntry("audio/${metadata.audioFileName}"))
                audioFile.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()

                val imageFile = recordingRepository.resolveImageFile(metadata)
                if (imageFile != null && metadata.imageFileName != null) {
                    zip.putNextEntry(ZipEntry("images/${metadata.imageFileName}"))
                    imageFile.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    fun importBundle(inputStream: InputStream): Int {
        val unzipDirectory = File(cacheDirectory, "bundle_import_${System.currentTimeMillis()}").apply { mkdirs() }
        val audioDirectory = File(unzipDirectory, "audio").apply { mkdirs() }
        val imageDirectory = File(unzipDirectory, "images").apply { mkdirs() }
        var metadataCsvFile: File? = null
        var metadataJsonFile: File? = null

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

                when {
                    entry.name == "metadata/recordings_metadata.csv" -> metadataCsvFile = outputFile
                    entry.name == "metadata/recordings_metadata.json" -> metadataJsonFile = outputFile
                    entry.name.startsWith("audio/") -> {
                        val normalizedFile = File(audioDirectory, outputFile.name)
                        if (normalizedFile.absolutePath != outputFile.absolutePath) {
                            outputFile.copyTo(normalizedFile, overwrite = true)
                            outputFile.delete()
                        }
                    }

                    entry.name.startsWith("images/") -> {
                        val normalizedFile = File(imageDirectory, outputFile.name)
                        if (normalizedFile.absolutePath != outputFile.absolutePath) {
                            outputFile.copyTo(normalizedFile, overwrite = true)
                            outputFile.delete()
                        }
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val importedMetadata = when {
            metadataCsvFile?.exists() == true -> {
                RecordingMetadataCsvCodec.parse(metadataCsvFile!!.readText())
            }

            metadataJsonFile?.exists() == true -> {
                parseMetadataJson(metadataJsonFile!!.readText())
            }

            else -> emptyList()
        }

        var importedCount = 0
        importedMetadata.forEach { metadata ->
            val sourceAudioFile = File(audioDirectory, metadata.audioFileName)
            if (!sourceAudioFile.exists()) {
                return@forEach
            }

            val tempAudioCopy = File(cacheDirectory, "import_${System.nanoTime()}.wav")
            sourceAudioFile.copyTo(tempAudioCopy, overwrite = true)

            val tempImageCopy = metadata.imageFileName?.let { imageName ->
                val sourceImage = File(imageDirectory, imageName)
                if (!sourceImage.exists()) {
                    null
                } else {
                    val extension = sourceImage.extension.ifBlank { "jpg" }
                    val tempImage = File(cacheDirectory, "import_img_${System.nanoTime()}.$extension")
                    sourceImage.copyTo(tempImage, overwrite = true)
                    tempImage
                }
            }

            val saved = recordingRepository.saveCapturedRecording(
                capturedRecording = CapturedRecording(tempAudioCopy, metadata.durationMs),
                labelDraft = RecordingLabelDraft(
                    targetNames = metadata.targetNames,
                    classLabel = metadata.classLabel,
                    pattern = metadata.pattern,
                    depthInches = metadata.depthInches,
                    notes = metadata.notes,
                    gpsLatitude = metadata.gpsLatitude,
                    gpsLongitude = metadata.gpsLongitude,
                    mixedFlag = metadata.mixedFlag,
                    includeInTraining = metadata.includeInTraining,
                    soilType = metadata.soilType,
                    moisture = metadata.moisture,
                    detectorModel = metadata.detectorModel,
                    searchMode = metadata.searchMode,
                    sensitivity = metadata.sensitivity,
                    recoverySpeed = metadata.recoverySpeed,
                    stabilizer = metadata.stabilizer,
                    imageTempFile = tempImageCopy,
                )
            )

            recordingRepository.updateRecording(
                saved.copy(
                    createdEpochMs = metadata.createdEpochMs,
                    durationMs = metadata.durationMs,
                )
            )
            importedCount += 1
        }

        unzipDirectory.deleteRecursively()
        return importedCount
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
