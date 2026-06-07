package com.metaldetectoraudioapp.app.audio.source

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPassthroughPlayer(sampleRateHz: Int) {
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBuffer = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRateHz)
                .setChannelMask(channelConfig)
                .build()
        )
        .setBufferSizeInBytes(minBuffer)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    private var isActive = false
    private var preferredDevice: AudioDeviceInfo? = null

    fun setEnabled(enabled: Boolean) {
        if (enabled == isActive) {
            return
        }
        isActive = enabled
        if (enabled) {
            track.play()
            // Re-assert routing after play(): a preferred device chosen while the track was
            // stopped is applied more reliably once the track is running on some devices.
            applyPreferredDevice()
        } else {
            track.pause()
            track.flush()
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        applyPreferredDevice()
    }

    private fun applyPreferredDevice() {
        // setPreferredDevice returns false if the route was rejected; fall back to the
        // system default (null) so audio still plays somewhere rather than silently failing.
        val accepted = track.setPreferredDevice(preferredDevice)
        if (!accepted && preferredDevice != null) {
            Log.w(TAG, "Output device ${preferredDevice?.productName} rejected; using system default")
            track.setPreferredDevice(null)
        }
    }

    fun write(pcmSamples: ShortArray, sampleCount: Int) {
        if (!isActive || sampleCount <= 0) {
            return
        }
        track.write(pcmSamples, 0, sampleCount, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun release() {
        track.release()
    }

    private companion object {
        const val TAG = "AudioPassthroughPlayer"
    }
}
