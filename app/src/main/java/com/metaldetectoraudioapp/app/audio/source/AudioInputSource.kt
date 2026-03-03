package com.metaldetectoraudioapp.app.audio.source

import android.media.AudioDeviceInfo

interface AudioInputSource {
    val sampleRateHz: Int
    fun start()
    fun readPcm16(targetBuffer: ShortArray): Int
    fun stop()
    fun release()
    fun setPreferredDevice(device: AudioDeviceInfo?) {}
}
