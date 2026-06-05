package com.metaldetectoraudioapp.app.audio.pipeline

import com.metaldetectoraudioapp.app.audio.AudioConstants
import com.metaldetectoraudioapp.app.audio.source.AudioInputSource
import com.metaldetectoraudioapp.app.util.Clocks
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads PCM16 from an [AudioInputSource], normalises, band-limits,
 * frames, and emits [AudioPipelineFrame]s.
 *
 * Passthrough is delegated to the optional [PassthroughSink] so that
 * the pipeline itself contains no platform audio-output code.
 */
class SharedAudioPipeline(
    private val inputSource: AudioInputSource,
    frameSizeSamples: Int = AudioConstants.INFERENCE_WINDOW_SIZE_SAMPLES,
    hopSizeSamples: Int = AudioConstants.INFERENCE_HOP_SIZE_SAMPLES,
    private val passthroughSink: PassthroughSink? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FrameStreamingPipeline {

    private val normalizationProcessor = AudioNormalizationProcessor()
    private val bandLimitFilter = BandLimitFilter(inputSource.sampleRateHz)
    private val framer = SlidingAudioFramer(frameSizeSamples, hopSizeSamples)

    private var readLoopJob: Job? = null

    private val _signalStatusFlow = MutableStateFlow(AudioSignalStatus())
    override val signalStatusFlow: StateFlow<AudioSignalStatus> = _signalStatusFlow

    @Volatile
    private var isPassthroughEnabled = false

    override fun setPassthroughEnabled(enabled: Boolean) {
        isPassthroughEnabled = enabled
        passthroughSink?.setEnabled(enabled)
    }

    override fun start(onFrame: (AudioPipelineFrame) -> Unit) {
        if (readLoopJob != null) return
        inputSource.start()
        readLoopJob = scope.launch {
            val pcmBuffer = ShortArray(AudioConstants.INFERENCE_CAPTURE_BLOCK_SIZE)
            while (isActive) {
                val samplesRead = inputSource.readPcm16(pcmBuffer)
                if (samplesRead <= 0) { yield(); continue }

                if (isPassthroughEnabled) {
                    passthroughSink?.writePcm16(pcmBuffer, samplesRead)
                }

                val floatBuffer = FloatArray(samplesRead)
                for (i in 0 until samplesRead) {
                    floatBuffer[i] = pcmBuffer[i] / 32768f
                }

                normalizationProcessor.normalizeInPlace(floatBuffer)
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
                val captureTime = Clocks.monotonicNanos()
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
        passthroughSink?.release()
        inputSource.release()
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumOfSquares = 0f
        for (sample in samples) sumOfSquares += sample * sample
        return sqrt(sumOfSquares / samples.size)
    }

    private fun detectClipping(samples: FloatArray): Boolean {
        for (sample in samples) {
            if (abs(sample) >= AudioConstants.CLIPPING_THRESHOLD) return true
        }
        return false
    }
}
