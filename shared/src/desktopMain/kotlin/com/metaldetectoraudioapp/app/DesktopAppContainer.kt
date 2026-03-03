package com.metaldetectoraudioapp.app

import com.metaldetectoraudioapp.app.export.DatasetBundleManager
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

    val recordingRepository = RecordingRepository(appDataDirectory)
    val datasetBundleManager = DatasetBundleManager(
        recordingRepository,
        File(appDataDirectory, "cache").also { it.mkdirs() }
    )
}
