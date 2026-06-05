package com.metaldetectoraudioapp.web.platform

import com.metaldetectoraudioapp.app.platform.FileDownloader

class WebFileDownloader : FileDownloader {
    override suspend fun download(fileName: String, bytes: ByteArray, mimeType: String) {
        val url = createBlobUrl(bytes, mimeType)
        triggerDownload(url, fileName)
        revokeObjectUrl(url)
    }
}

private fun triggerDownload(url: String, fileName: String) {
    js("""
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    """)
}
