package com.metaldetectoraudioapp.app.recording

/**
 * Pure-Kotlin WAV (PCM) encode/decode — no java.io, so it compiles for every target.
 *
 * Desktop streams audio to disk via `WavFileWriter`; this codec is used where the whole clip is
 * already in memory (web recording, fixtures, computing duration). Mono/stereo 8/16-bit PCM.
 */
object WavCodec {

    /** Decoded PCM16 WAV: interleaved 16-bit samples plus format info. */
    class DecodedWav(
        val samples: ShortArray,
        val sampleRateHz: Int,
        val channels: Int,
    ) {
        val durationMs: Long
            get() = if (sampleRateHz <= 0 || channels <= 0) 0L
            else (samples.size.toLong() * 1000L) / (sampleRateHz.toLong() * channels.toLong())
    }

    /** Encode interleaved PCM16 samples into a complete WAV byte array. */
    fun encodePcm16(
        samples: ShortArray,
        sampleRateHz: Int,
        channels: Int = 1,
    ): ByteArray {
        val bitsPerSample = 16
        val dataSize = samples.size * 2
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + dataSize)
        var p = 0

        fun putAscii(s: String) { for (c in s) out[p++] = c.code.toByte() }
        fun putIntLE(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
            out[p++] = ((v shr 16) and 0xFF).toByte()
            out[p++] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShortLE(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
        }

        putAscii("RIFF"); putIntLE(36 + dataSize); putAscii("WAVE")
        putAscii("fmt "); putIntLE(16); putShortLE(1); putShortLE(channels)
        putIntLE(sampleRateHz); putIntLE(byteRate); putShortLE(blockAlign); putShortLE(bitsPerSample)
        putAscii("data"); putIntLE(dataSize)
        for (sample in samples) {
            out[p++] = (sample.toInt() and 0xFF).toByte()
            out[p++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Decode a mono/stereo PCM16 WAV byte array. Reads the canonical 44-byte header layout
     * (RIFF/WAVE/fmt /data) produced by [encodePcm16] and `WavFileWriter`.
     */
    fun decodePcm16(bytes: ByteArray): DecodedWav {
        require(bytes.size >= 44) { "WAV too small: ${bytes.size} bytes" }
        val channels = readShortLE(bytes, 22)
        val sampleRate = readIntLE(bytes, 24)
        val bitsPerSample = readShortLE(bytes, 34)
        require(bitsPerSample == 16) { "Only PCM16 supported, got $bitsPerSample-bit" }

        val declaredDataSize = readIntLE(bytes, 40)
        val available = bytes.size - 44
        val dataSize = if (declaredDataSize in 1..available) declaredDataSize else available
        val sampleCount = dataSize / 2
        val samples = ShortArray(sampleCount)
        var offset = 44
        for (i in 0 until sampleCount) {
            val low = bytes[offset].toInt() and 0xFF
            val high = bytes[offset + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort()
            offset += 2
        }
        return DecodedWav(samples, sampleRate, channels)
    }

    private fun readShortLE(bytes: ByteArray, index: Int): Int {
        val low = bytes[index].toInt() and 0xFF
        val high = bytes[index + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun readIntLE(bytes: ByteArray, index: Int): Int {
        val b0 = bytes[index].toInt() and 0xFF
        val b1 = bytes[index + 1].toInt() and 0xFF
        val b2 = bytes[index + 2].toInt() and 0xFF
        val b3 = bytes[index + 3].toInt() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}
