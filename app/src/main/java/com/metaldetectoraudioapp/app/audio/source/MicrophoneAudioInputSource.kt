package com.metaldetectoraudioapp.app.audio.source

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.metaldetectoraudioapp.app.audio.AudioConstants
import kotlin.math.max

@SuppressLint("MissingPermission")
class MicrophoneAudioInputSource(
    context: Context,
    override val sampleRateHz: Int = AudioConstants.INFERENCE_SAMPLE_RATE_HZ
) : AudioInputSource {

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, encoding)
    private val audioRecordBufferSize = max(minBufferSize, AudioConstants.INFERENCE_CAPTURE_BLOCK_SIZE * 4)

    private val audioRecord: AudioRecord

    init {
        Log.i(TAG, "Creating AudioRecord: sampleRate=$sampleRateHz minBuffer=$minBufferSize bufferSize=$audioRecordBufferSize")
        audioRecord = AudioRecord.Builder()
            .setAudioSource(preferredDetectorAudioSource(context))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(audioRecordBufferSize)
            .build()
        Log.i(TAG, "AudioRecord created: state=${audioRecord.state}")
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording()
        }
    }

    override fun readPcm16(targetBuffer: ShortArray): Int {
        return if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.read(targetBuffer, 0, targetBuffer.size, AudioRecord.READ_BLOCKING)
        } else {
            0
        }
    }

    override fun stop() {
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
        }
    }

    override fun release() {
        audioRecord.release()
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        audioRecord.preferredDevice = device
    }

    companion object {
        private const val TAG = "MicAudioInputSource"
    }
}
