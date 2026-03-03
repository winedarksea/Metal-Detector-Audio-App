package com.metaldetectoraudioapp.app.audio.source

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only source that replays a mono PCM16 WAV fixture.
 */
class WavFixtureAudioInputSource(
    fixtureFile: File,
    private val loopWhenExhausted: Boolean = false
) : AudioInputSource {

    private val pcmBytes: ByteArray
    override val sampleRateHz: Int

    private var readOffset = 0

    init {
        val bytes = BufferedInputStream(FileInputStream(fixtureFile)).use { it.readBytes() }
        if (bytes.size < 44) {
            error("WAV fixture is too small: ${fixtureFile.absolutePath}")
        }
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val bitsPerSample = header.getShort(34).toInt()
        val dataSize = header.getInt(40)
        sampleRateHz = header.getInt(24)

        require(channels == 1) { "Only mono fixtures are supported" }
        require(bitsPerSample == 16) { "Only PCM16 fixtures are supported" }

        val expectedDataSize = bytes.size - 44
        val boundedDataSize = minOf(dataSize, expectedDataSize)
        pcmBytes = bytes.copyOfRange(44, 44 + boundedDataSize)
    }

    override fun start() {
        readOffset = 0
    }

    override fun readPcm16(targetBuffer: ShortArray): Int {
        if (readOffset >= pcmBytes.size && !loopWhenExhausted) {
            return 0
        }
        if (loopWhenExhausted && readOffset >= pcmBytes.size) {
            readOffset = 0
        }

        var samplesRead = 0
        while (samplesRead < targetBuffer.size && readOffset + 1 < pcmBytes.size) {
            val low = pcmBytes[readOffset].toInt() and 0xFF
            val high = pcmBytes[readOffset + 1].toInt()
            targetBuffer[samplesRead] = ((high shl 8) or low).toShort()
            readOffset += 2
            samplesRead += 1
        }

        return samplesRead
    }

    override fun stop() = Unit

    override fun release() = Unit
}
