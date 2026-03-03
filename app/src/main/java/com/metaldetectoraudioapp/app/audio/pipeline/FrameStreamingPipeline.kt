package com.metaldetectoraudioapp.app.audio.pipeline

import android.media.AudioDeviceInfo
import kotlinx.coroutines.flow.StateFlow

interface FrameStreamingPipeline {
    val signalStatusFlow: StateFlow<AudioSignalStatus>
    fun setPassthroughEnabled(enabled: Boolean)
    fun setInputDevice(device: AudioDeviceInfo?) {}
    fun setOutputDevice(device: AudioDeviceInfo?) {}
    fun start(onFrame: (AudioPipelineFrame) -> Unit)
    fun stop()
    fun release()
}
