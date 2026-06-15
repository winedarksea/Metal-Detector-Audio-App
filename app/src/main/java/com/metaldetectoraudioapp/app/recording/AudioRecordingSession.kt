package com.metaldetectoraudioapp.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import com.metaldetectoraudioapp.app.audio.AudioConstants
import com.metaldetectoraudioapp.app.audio.source.preferredDetectorAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

class AudioRecordingSession(
    private val context: Context,
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

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _waveformPoints = MutableStateFlow<List<Float>>(emptyList())
    val waveformPoints: StateFlow<List<Float>> = _waveformPoints.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(preferredInputDevice: AudioDeviceInfo? = null): Boolean {
        if (captureJob != null) {
            return false
        }

        val recorder = AudioRecord(
            preferredDetectorAudioSource(context),
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

        recorder.preferredDevice = preferredInputDevice

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
                    var sumSq = 0.0
                    for (i in 0 until samplesRead) {
                        val s = tempBuffer[i] / 32768.0
                        sumSq += s * s
                    }
                    _rmsLevel.value = sqrt(sumSq / samplesRead).toFloat()
                    val step = maxOf(1, samplesRead / 100)
                    _waveformPoints.value = (0 until samplesRead step step).map { i -> tempBuffer[i] / 32768f }
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
