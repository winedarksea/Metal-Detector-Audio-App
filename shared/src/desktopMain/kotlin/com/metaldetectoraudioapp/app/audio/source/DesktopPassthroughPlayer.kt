package com.metaldetectoraudioapp.app.audio.source

import com.metaldetectoraudioapp.app.audio.pipeline.PassthroughSink
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Desktop speaker passthrough using javax.sound.sampled.
 *
 * Mirrors [AudioPassthroughPlayer] (Android) using a SourceDataLine.
 */
class DesktopPassthroughPlayer(
    sampleRateHz: Int = com.metaldetectoraudioapp.app.audio.AudioConstants.INFERENCE_SAMPLE_RATE_HZ
) : PassthroughSink {

    private val audioFormat = AudioFormat(
        sampleRateHz.toFloat(), 16, 1, true, false
    )
    private var line: SourceDataLine? = null
    private var active = false

    override fun setEnabled(enabled: Boolean) {
        if (enabled == active) return
        active = enabled
        if (enabled) {
            if (line == null) {
                val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
                val sourceLine = AudioSystem.getLine(info) as SourceDataLine
                sourceLine.open(audioFormat)
                sourceLine.start()
                line = sourceLine
            }
        } else {
            line?.flush()
            line?.stop()
            line?.close()
            line = null
        }
    }

    override fun writePcm16(samples: ShortArray, count: Int) {
        if (!active || count <= 0) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8).toByte()
        }
        line?.write(bytes, 0, bytes.size)
    }

    override fun release() {
        line?.close()
        line = null
    }
}
