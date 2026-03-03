package com.metaldetectoraudioapp.desktop.audio

import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.WavFileWriter
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * JVM desktop microphone recorder that writes 48kHz mono PCM16 WAV files.
 */
class DesktopAudioRecordingSession(
    private val cacheDirectory: File,
    private val sampleRateHz: Int = 48_000,
) {
    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var targetLine: TargetDataLine? = null

    @Volatile
    private var writer: WavFileWriter? = null

    @Volatile
    private var currentOutputFile: File? = null

    @Volatile
    private var startEpochMs: Long = 0

    fun start(): Boolean {
        if (recordingThread?.isAlive == true) {
            return false
        }

        cacheDirectory.mkdirs()
        val outputFile = File(cacheDirectory, "capture_${System.nanoTime()}.wav")
        val audioFormat = AudioFormat(sampleRateHz.toFloat(), 16, 1, true, false)
        val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)

        val newLine = runCatching {
            AudioSystem.getLine(lineInfo) as TargetDataLine
        }.getOrElse {
            return false
        }

        val newWriter = runCatching {
            WavFileWriter(outputFile, sampleRateHz, 1, 16)
        }.getOrElse {
            return false
        }

        return try {
            newLine.open(audioFormat)
            newLine.start()

            targetLine = newLine
            writer = newWriter
            currentOutputFile = outputFile
            startEpochMs = System.currentTimeMillis()

            val thread = Thread(
                {
                    val byteBuffer = ByteArray((sampleRateHz / 10) * 2)
                    val sampleBuffer = ShortArray(byteBuffer.size / 2)

                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val bytesRead = try {
                                newLine.read(byteBuffer, 0, byteBuffer.size)
                            } catch (_: Throwable) {
                                break
                            }

                            if (bytesRead <= 0) {
                                continue
                            }

                            val sampleCount = bytesRead / 2
                            for (index in 0 until sampleCount) {
                                val low = byteBuffer[index * 2].toInt() and 0xFF
                                val high = byteBuffer[index * 2 + 1].toInt()
                                sampleBuffer[index] = ((high shl 8) or low).toShort()
                            }

                            newWriter.append(sampleBuffer, sampleCount)
                        }
                    } finally {
                        runCatching { newWriter.close() }
                    }
                },
                "desktop-audio-recording-thread"
            )

            thread.isDaemon = true
            recordingThread = thread
            thread.start()
            true
        } catch (_: Throwable) {
            runCatching { newLine.close() }
            runCatching { newWriter.close() }
            outputFile.delete()
            targetLine = null
            writer = null
            currentOutputFile = null
            false
        }
    }

    fun stop(): CapturedRecording? {
        val activeThread = recordingThread ?: return null

        targetLine?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }

        activeThread.interrupt()
        runCatching { activeThread.join(1_000) }

        recordingThread = null
        targetLine = null
        writer = null

        val outputFile = currentOutputFile ?: return null
        currentOutputFile = null

        if (!outputFile.exists()) {
            return null
        }

        val durationMs = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(0)
        return CapturedRecording(outputFile, durationMs)
    }

    fun cancelAndDelete() {
        val captured = stop()
        captured?.tempAudioFile?.delete()
    }
}
