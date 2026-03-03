package com.metaldetectoraudioapp.app.audio.pipeline

import kotlinx.coroutines.flow.StateFlow

interface FrameStreamingPipeline {
    val signalStatusFlow: StateFlow<AudioSignalStatus>
    fun setPassthroughEnabled(enabled: Boolean)
    fun start(onFrame: (AudioPipelineFrame) -> Unit)
    fun stop()
    fun release()
}
