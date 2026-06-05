package com.metaldetectoraudioapp.app.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Desktop [ZipCodec] using `java.util.zip` (DEFLATE). */
class JavaZipCodec : ZipCodec {

    override suspend fun zip(entries: List<ZipFileEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    override suspend fun unzip(bytes: ByteArray): List<ZipFileEntry> {
        val result = mutableListOf<ZipFileEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result += ZipFileEntry(entry.name, zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }
}
