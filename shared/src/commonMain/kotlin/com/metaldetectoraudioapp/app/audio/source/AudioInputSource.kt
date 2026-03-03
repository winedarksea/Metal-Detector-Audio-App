package com.metaldetectoraudioapp.app.audio.source

interface AudioInputSource {
    val sampleRateHz: Int
    fun start()
    fun readPcm16(targetBuffer: ShortArray): Int
    fun stop()
    fun release()
}
