package com.metaldetectoraudioapp.app.export

/** One file inside a dataset bundle: a relative [path] and its raw [bytes]. */
class ZipFileEntry(val path: String, val bytes: ByteArray)

/**
 * Platform-abstracted ZIP read/write for dataset bundles.
 *
 * Desktop uses `java.util.zip` (DEFLATE); web uses a pure-Kotlin STORED writer/reader. Both
 * produce standard ZIP archives the app can round-trip. `suspend` to allow async web work.
 */
interface ZipCodec {
    suspend fun zip(entries: List<ZipFileEntry>): ByteArray
    suspend fun unzip(bytes: ByteArray): List<ZipFileEntry>
}
