package com.metaldetectoraudioapp.app.platform

/**
 * Saves bytes as a named file to the user's storage.
 *
 * Desktop: shows a native save-file dialog and writes to the chosen path.
 * Web: triggers a browser download via Blob + `<a download>`.
 */
interface FileDownloader {
    suspend fun download(
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    )
}
