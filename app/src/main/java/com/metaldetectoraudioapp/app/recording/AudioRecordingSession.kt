package com.metaldetectoraudioapp.app.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.metaldetectoraudioapp.app.audio.AudioConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

class AudioRecordingSession(
    private val cacheDirectory: File,
    private val sampleRateHz: Int = AudioConstants.RECORDING_SAMPLE_RATE_HZ,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, encoding)
    private val recordingBufferSize = max(minBufferSize, sampleRateHz)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var currentWriter: WavFileWriter? = null
    private var activeTempFile: File? = null
    private var recordingStartMs: Long = 0L

    fun start(): Boolean {
        if (captureJob != null) {
            return false
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            encoding,
            recordingBufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        val file = File(cacheDirectory, "recording_${System.currentTimeMillis()}.wav")
        val writer = WavFileWriter(
            outputFile = file,
            sampleRateHz = sampleRateHz,
            channels = AudioConstants.RECORDING_CHANNELS,
            bitsPerSample = 16
        )

        val tempBuffer = ShortArray(2048)
        recorder.startRecording()

        recordingStartMs = System.currentTimeMillis()
        audioRecord = recorder
        currentWriter = writer
        activeTempFile = file
        captureJob = scope.launch {
            while (isActive) {
                val samplesRead = recorder.read(tempBuffer, 0, tempBuffer.size, AudioRecord.READ_BLOCKING)
                if (samplesRead > 0) {
                    writer.append(tempBuffer, samplesRead)
                }
            }
        }

        return true
    }

    fun stop(): CapturedRecording? {
        val runningJob = captureJob ?: return null
        runningJob.cancel()
        captureJob = null

        audioRecord?.run {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null

        currentWriter?.close()
        currentWriter = null

        val file = activeTempFile
        activeTempFile = null
        val duration = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0)

        return if (file != null && file.exists()) {
            CapturedRecording(tempAudioFile = file, durationMs = duration)
        } else {
            null
        }
    }

    fun cancelAndDelete() {
        stop()?.tempAudioFile?.delete()
    }
}
