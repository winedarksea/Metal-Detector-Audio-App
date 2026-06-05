package com.metaldetectoraudioapp.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop [FileDownloader]: shows a native save dialog and writes bytes to the chosen path. */
class DesktopFileDownloader : FileDownloader {

    override suspend fun download(fileName: String, bytes: ByteArray, mimeType: String) {
        withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, "Save As", FileDialog.SAVE)
            dialog.file = fileName
            dialog.isVisible = true
            val chosen = dialog.file ?: return@withContext
            val dir = dialog.directory ?: return@withContext
            File(dir, chosen).writeBytes(bytes)
        }
    }
}
