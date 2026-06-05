package com.metaldetectoraudioapp.app.recording

/**
 * Persistent storage for the recording dataset, abstracted over the platform.
 *
 * Desktop backs this with the filesystem (`dataset/audio`, `dataset/images`, a CSV file);
 * web backs it with IndexedDB. All operations are `suspend` because the web/IndexedDB API is
 * inherently asynchronous; on desktop the implementations simply run synchronously.
 */
interface DatasetStore {
    suspend fun readMetadataCsv(): String
    suspend fun writeMetadataCsv(csv: String)

    suspend fun saveAudio(fileName: String, bytes: ByteArray)
    suspend fun readAudio(fileName: String): ByteArray?
    suspend fun deleteAudio(fileName: String)

    suspend fun saveImage(fileName: String, bytes: ByteArray)
    suspend fun readImage(fileName: String): ByteArray?
    suspend fun deleteImage(fileName: String)
}
