package com.metaldetectoraudioapp.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MetalDetectorAudioApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                Log.e(TAG, "FATAL CRASH on thread '${thread.name}':\n$stackTrace")

                // Write crash log to internal storage for retrieval via adb
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val crashFile = File(filesDir, "crash_$timestamp.log")
                crashFile.writeText(
                    "Thread: ${thread.name}\n" +
                    "Time: $timestamp\n\n" +
                    stackTrace
                )

                // Keep only the 5 most recent crash logs
                filesDir.listFiles { file -> file.name.startsWith("crash_") && file.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            } catch (_: Exception) {
                // Don't let crash logging itself cause issues
            }

            // Chain to the default handler so the OS still reports the crash
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "MetalDetectorAudio"
    }
}
