package com.metaldetectoraudioapp.app

import android.content.Context
import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.recording.RecordingRepository

class AppContainer(context: Context) {
    val recordingRepository = RecordingRepository(context.filesDir)
    val datasetBundleManager = DatasetBundleManager(recordingRepository, context.cacheDir)
}
