package com.metaldetectoraudioapp.app.recording

import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    private val outputFile: File,
    private val sampleRateHz: Int,
    private val channels: Int,
    private val bitsPerSample: Int
) {
    private val file = RandomAccessFile(outputFile, "rw")
    private var dataLengthBytes = 0

    init {
        writeHeader(0)
    }

    fun append(samples: ShortArray, sampleCount: Int) {
        for (index in 0 until sampleCount) {
            writeShortLE(samples[index].toInt())
            dataLengthBytes += 2
        }
    }

    fun close() {
        writeHeader(dataLengthBytes)
        file.close()
    }

    private fun writeHeader(dataSizeBytes: Int) {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        file.seek(0)
        file.writeBytes("RIFF")
        writeIntLE(36 + dataSizeBytes)
        file.writeBytes("WAVE")

        file.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRateHz)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)

        file.writeBytes("data")
        writeIntLE(dataSizeBytes)
    }

    private fun writeIntLE(value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
        file.write((value shr 16) and 0xFF)
        file.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }
}
