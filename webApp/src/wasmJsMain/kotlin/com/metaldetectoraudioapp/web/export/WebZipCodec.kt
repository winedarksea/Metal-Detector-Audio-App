package com.metaldetectoraudioapp.web.export

import com.metaldetectoraudioapp.app.export.ZipCodec
import com.metaldetectoraudioapp.app.export.ZipFileEntry

/**
 * Pure-Kotlin STORED-method ZIP writer/reader — no third-party lib, no java.util.zip.
 *
 * STORED means data is copied uncompressed. The archives are valid, standard ZIP files readable
 * by desktop unzip and importable by [com.metaldetectoraudioapp.app.export.JavaZipCodec].
 */
class WebZipCodec : ZipCodec {

    override suspend fun zip(entries: List<ZipFileEntry>): ByteArray {
        val localHeaders = mutableListOf<Pair<Int, ZipFileEntry>>() // offset → entry
        val buf = mutableListOf<Byte>()

        fun writeInt(v: Int) {
            buf += (v and 0xFF).toByte()
            buf += ((v shr 8) and 0xFF).toByte()
            buf += ((v shr 16) and 0xFF).toByte()
            buf += ((v shr 24) and 0xFF).toByte()
        }
        fun writeShort(v: Int) {
            buf += (v and 0xFF).toByte()
            buf += ((v shr 8) and 0xFF).toByte()
        }
        fun writeBytes(bytes: ByteArray) { bytes.forEach { buf += it } }
        fun writeString(s: String) = writeBytes(s.encodeToByteArray())

        for (entry in entries) {
            val nameBytes = entry.path.encodeToByteArray()
            val crc = crc32(entry.bytes)
            val offset = buf.size
            localHeaders += Pair(offset, entry)

            // Local file header signature
            writeInt(0x04034b50)
            writeShort(20)        // version needed: 2.0
            writeShort(0)         // general purpose bit flag
            writeShort(0)         // compression: STORED
            writeShort(0)         // last mod time
            writeShort(0)         // last mod date
            writeInt(crc)
            writeInt(entry.bytes.size)
            writeInt(entry.bytes.size)
            writeShort(nameBytes.size)
            writeShort(0)         // extra field length
            writeBytes(nameBytes)
            writeBytes(entry.bytes)
        }

        val centralDirStart = buf.size
        for ((offset, entry) in localHeaders) {
            val nameBytes = entry.path.encodeToByteArray()
            val crc = crc32(entry.bytes)
            writeInt(0x02014b50) // central directory signature
            writeShort(20)       // version made by
            writeShort(20)       // version needed
            writeShort(0)        // bit flag
            writeShort(0)        // compression: STORED
            writeShort(0)        // last mod time
            writeShort(0)        // last mod date
            writeInt(crc)
            writeInt(entry.bytes.size)
            writeInt(entry.bytes.size)
            writeShort(nameBytes.size)
            writeShort(0)        // extra field
            writeShort(0)        // file comment
            writeShort(0)        // disk start
            writeShort(0)        // internal attrs
            writeInt(0)          // external attrs
            writeInt(offset)
            writeBytes(nameBytes)
        }

        val centralDirSize = buf.size - centralDirStart
        // End of central directory record
        writeInt(0x06054b50)
        writeShort(0)            // disk number
        writeShort(0)            // disk with central dir
        writeShort(localHeaders.size)
        writeShort(localHeaders.size)
        writeInt(centralDirSize)
        writeInt(centralDirStart)
        writeShort(0)            // comment length

        return buf.toByteArray()
    }

    override suspend fun unzip(bytes: ByteArray): List<ZipFileEntry> {
        val entries = mutableListOf<ZipFileEntry>()
        var pos = 0

        fun readInt(): Int {
            val v = ((bytes[pos + 3].toInt() and 0xFF) shl 24) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                    (bytes[pos].toInt() and 0xFF)
            pos += 4; return v
        }
        fun readShort(): Int {
            val v = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos].toInt() and 0xFF)
            pos += 2; return v
        }
        fun skip(n: Int) { pos += n }

        while (pos + 4 <= bytes.size) {
            val sig = readInt()
            when (sig) {
                0x04034b50 -> { // local file header
                    skip(2) // version needed
                    skip(2) // flags
                    skip(2) // compression
                    skip(2) // mod time
                    skip(2) // mod date
                    skip(4) // crc
                    val compSize = readInt()
                    skip(4) // uncompressed size (same for STORED)
                    val nameLen = readShort()
                    val extraLen = readShort()
                    val name = bytes.decodeToString(pos, pos + nameLen)
                    skip(nameLen)
                    skip(extraLen)
                    val data = bytes.copyOfRange(pos, pos + compSize)
                    skip(compSize)
                    entries += ZipFileEntry(name, data)
                }
                0x02014b50 -> return entries // central dir → done reading local entries
                0x06054b50 -> return entries // EOCD → done
                else -> return entries       // unknown → stop
            }
        }
        return entries
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            val tableIdx = ((crc xor b.toLong().and(0xFF)) and 0xFF).toInt()
            crc = (crc32Table[tableIdx] xor (crc ushr 8))
        }
        return (crc xor 0xFFFFFFFFL).toInt()
    }

    companion object {
        private val crc32Table: LongArray = LongArray(256) { i ->
            var c = i.toLong()
            repeat(8) { c = if (c and 1L != 0L) (0xEDB88320L xor (c ushr 1)) else (c ushr 1) }
            c
        }
    }
}
