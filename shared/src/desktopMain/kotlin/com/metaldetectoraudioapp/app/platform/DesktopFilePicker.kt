package com.metaldetectoraudioapp.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop [FilePicker]: shows a native open dialog and returns the chosen file's bytes. */
class DesktopFilePicker : FilePicker {

    override suspend fun pickFile(mimeTypes: List<String>): ByteArray? {
        return withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, "Open", FileDialog.LOAD)
            dialog.isVisible = true
            val chosen = dialog.file ?: return@withContext null
            val dir = dialog.directory ?: return@withContext null
            File(dir, chosen).takeIf { it.exists() }?.readBytes()
        }
    }
}
