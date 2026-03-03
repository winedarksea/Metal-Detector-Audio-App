package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.source.WavFixtureAudioInputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavFixtureAudioInputSourceTest {
    @Test
    fun readsPcmSamplesFromFixtureFile() {
        val tempFile = Files.createTempFile("fixture", ".wav").toFile()
        writePcm16Wav(tempFile, sampleRate = 16_000, samples = shortArrayOf(10, -10, 100, -100))

        val source = WavFixtureAudioInputSource(tempFile)
        source.start()
        val buffer = ShortArray(8)
        val count = source.readPcm16(buffer)

        assertEquals(4, count)
        assertEquals(10, buffer[0].toInt())
        assertEquals(-10, buffer[1].toInt())
        assertEquals(100, buffer[2].toInt())
        assertEquals(-100, buffer[3].toInt())
        assertTrue(source.readPcm16(buffer) == 0)
    }

    private fun writePcm16Wav(file: File, sampleRate: Int, samples: ShortArray) {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        samples.forEach { buffer.putShort(it) }
        file.writeBytes(buffer.array())
    }
}
