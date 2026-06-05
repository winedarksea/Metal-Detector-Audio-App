package com.metaldetectoraudioapp.app.platform

/**
 * Lets the user pick a file and returns its bytes, or null if cancelled.
 *
 * Desktop: shows a native open-file dialog.
 * Web: presents `<input type=file>` asynchronously.
 */
interface FilePicker {
    suspend fun pickFile(mimeTypes: List<String> = emptyList()): ByteArray?
}
