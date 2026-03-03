package com.metaldetectoraudioapp.app.audio.pipeline

import com.metaldetectoraudioapp.app.audio.AudioConstants
import com.metaldetectoraudioapp.app.audio.source.AudioInputSource
import com.metaldetectoraudioapp.app.audio.source.AudioPassthroughPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class SharedAudioPipeline(
    private val inputSource: AudioInputSource,
    frameSizeSamples: Int = AudioConstants.INFERENCE_WINDOW_SIZE_SAMPLES,
    hopSizeSamples: Int = AudioConstants.INFERENCE_HOP_SIZE_SAMPLES,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FrameStreamingPipeline {

    private val bandLimitFilter = BandLimitFilter(inputSource.sampleRateHz)
    private val framer = SlidingAudioFramer(frameSizeSamples, hopSizeSamples)
    private val passthroughPlayer = AudioPassthroughPlayer(inputSource.sampleRateHz)

    private var readLoopJob: Job? = null

    private val _signalStatusFlow = MutableStateFlow(AudioSignalStatus())
    val signalStatusFlow: StateFlow<AudioSignalStatus> = _signalStatusFlow

    @Volatile
    private var isPassthroughEnabled = false

    override fun setPassthroughEnabled(enabled: Boolean) {
        isPassthroughEnabled = enabled
        passthroughPlayer.setEnabled(enabled)
    }

    override fun start(onFrame: (AudioPipelineFrame) -> Unit) {
        if (readLoopJob != null) {
            return
        }

        inputSource.start()
        readLoopJob = scope.launch {
            val pcmBuffer = ShortArray(AudioConstants.INFERENCE_CAPTURE_BLOCK_SIZE)
            while (isActive) {
                val samplesRead = inputSource.readPcm16(pcmBuffer)
                if (samplesRead <= 0) {
                    continue
                }

                if (isPassthroughEnabled) {
                    passthroughPlayer.write(pcmBuffer, samplesRead)
                }

                val floatBuffer = FloatArray(samplesRead)
                for (index in 0 until samplesRead) {
                    floatBuffer[index] = pcmBuffer[index] / 32768f
                }

                // Fixed-scale: /32768f above already puts samples in [-1, 1].
                // Per-block peak normalization is intentionally omitted — it destroys
                // amplitude information and creates a train/inference mismatch.
                bandLimitFilter.processInPlace(floatBuffer)

                val rms = calculateRms(floatBuffer)
                val clippingDetected = detectClipping(floatBuffer)
                val signalPresent = rms >= AudioConstants.SIGNAL_PRESENT_RMS_THRESHOLD

                _signalStatusFlow.value = AudioSignalStatus(
                    rmsLevel = rms,
                    clippingDetected = clippingDetected,
                    signalPresent = signalPresent
                )

                val frames = framer.push(floatBuffer)
                val captureTime = System.nanoTime()
                for (frame in frames) {
                    onFrame(
                        AudioPipelineFrame(
                            samples = frame,
                            receivedAtNanos = captureTime,
                            rmsLevel = rms,
                            clippingDetected = clippingDetected,
                            signalPresent = signalPresent
                        )
                    )
                }
            }
        }
    }

    override fun stop() {
        readLoopJob?.cancel()
        readLoopJob = null
        inputSource.stop()
        bandLimitFilter.reset()
        framer.reset()
    }

    override fun release() {
        stop()
        passthroughPlayer.release()
        inputSource.release()
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) {
            return 0f
        }

        var sumOfSquares = 0f
        for (sample in samples) {
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / samples.size)
    }

    private fun detectClipping(samples: FloatArray): Boolean {
        for (sample in samples) {
            if (abs(sample) >= AudioConstants.CLIPPING_THRESHOLD) {
                return true
            }
        }
        return false
    }
}
