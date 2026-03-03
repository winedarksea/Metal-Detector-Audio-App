package com.metaldetectoraudioapp.app.audio.source

import com.metaldetectoraudioapp.app.audio.AudioConstants
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.max

/**
 * Desktop microphone capture using javax.sound.sampled.
 *
 * Mirrors [MicrophoneAudioInputSource] (Android) but uses the JVM
 * TargetDataLine API instead of Android's AudioRecord.
 */
class DesktopMicrophoneInputSource(
    override val sampleRateHz: Int = AudioConstants.INFERENCE_SAMPLE_RATE_HZ
) : AudioInputSource {

    private val audioFormat = AudioFormat(
        sampleRateHz.toFloat(),
        16,       // sample size in bits
        1,        // mono
        true,     // signed
        false     // little-endian
    )

    private var line: TargetDataLine? = null
    private val byteBuffer = ByteArray(AudioConstants.INFERENCE_CAPTURE_BLOCK_SIZE * 2)

    override fun start() {
        if (line != null) return
        val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
        val targetLine = AudioSystem.getLine(info) as TargetDataLine
        val bufferBytes = max(targetLine.bufferSize, sampleRateHz * 2)
        targetLine.open(audioFormat, bufferBytes)
        targetLine.start()
        line = targetLine
    }

    override fun readPcm16(targetBuffer: ShortArray): Int {
        val activeLine = line ?: return 0
        val bytesToRead = (targetBuffer.size * 2).coerceAtMost(byteBuffer.size)
        val bytesRead = activeLine.read(byteBuffer, 0, bytesToRead)
        if (bytesRead <= 0) return 0

        val samplesRead = bytesRead / 2
        for (i in 0 until samplesRead) {
            val lo = byteBuffer[i * 2].toInt() and 0xFF
            val hi = byteBuffer[i * 2 + 1].toInt()
            targetBuffer[i] = ((hi shl 8) or lo).toShort()
        }
        return samplesRead
    }

    override fun stop() {
        line?.stop()
    }

    override fun release() {
        line?.close()
        line = null
    }
}
