package com.metaldetectoraudioapp.app

import com.metaldetectoraudioapp.app.audio.DesktopAudioPlayer
import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.export.JavaZipCodec
import com.metaldetectoraudioapp.app.platform.DesktopFileDownloader
import com.metaldetectoraudioapp.app.platform.DesktopFilePicker
import com.metaldetectoraudioapp.app.recording.FileDatasetStore
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import java.io.File

/**
 * Desktop dependency container. Uses the user home directory for file storage.
 */
class DesktopAppContainer {
    val appDataDirectory = File(
        System.getProperty("user.home"),
        ".metaldetector-audio"
    ).also { it.mkdirs() }

    private val datasetStore = FileDatasetStore(appDataDirectory)

    /** Absolute path to the dataset directory, for desktop "open folder" UI. */
    val datasetDirectoryPath: String = datasetStore.datasetDirectoryPath

    val recordingRepository = RecordingRepository(datasetStore)
    val datasetBundleManager = DatasetBundleManager(recordingRepository, JavaZipCodec())

    val audioPlayer = DesktopAudioPlayer()
    val fileDownloader = DesktopFileDownloader()
    val filePicker = DesktopFilePicker()
}
