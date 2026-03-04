package com.metaldetectoraudioapp.app

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MetalDetectorAudioApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        Log.i(TAG, "Application created")
    }

    /** Writes crash logs to Downloads via MediaStore (visible in Files app). */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                Log.e(TAG, "FATAL CRASH on thread '${thread.name}':\n$stackTrace")

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val content = "Thread: ${thread.name}\nTime: $timestamp\n\n$stackTrace"

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "mda_crash_$timestamp.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) } }
            } catch (_: Exception) {
                // Don't let crash logging itself cause issues
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "MetalDetectorAudio"
    }
}
