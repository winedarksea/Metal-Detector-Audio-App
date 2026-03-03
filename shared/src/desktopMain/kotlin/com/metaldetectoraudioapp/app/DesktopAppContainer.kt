package com.metaldetectoraudioapp.app

import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import java.io.File

/**
 * Desktop dependency container. Uses the user home directory for file storage.
 */
class DesktopAppContainer {
    private val appDataDir = File(
        System.getProperty("user.home"),
        ".metaldetector-audio"
    ).also { it.mkdirs() }

    val recordingRepository = RecordingRepository(appDataDir)
    val datasetBundleManager = DatasetBundleManager(
        recordingRepository,
        File(appDataDir, "cache").also { it.mkdirs() }
    )
}
