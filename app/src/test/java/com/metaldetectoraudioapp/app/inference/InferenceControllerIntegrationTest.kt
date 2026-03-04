package com.metaldetectoraudioapp.app.inference

import com.metaldetectoraudioapp.app.audio.pipeline.AudioPipelineFrame
import com.metaldetectoraudioapp.app.audio.pipeline.AudioSignalStatus
import com.metaldetectoraudioapp.app.audio.pipeline.FrameStreamingPipeline
import android.media.AudioDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceControllerIntegrationTest {
    @Test
    fun fixtureFrames_emitExpectedPredictionSequence() = runTest {
        val metadata = ModelMetadata(
            modelName = "test",
            modelVersion = "1",
            labels = listOf("TARGET", "JUNK", "AMBIENT"),
            input = ModelInputConfig(
                sampleRateHz = 16_000,
                windowSizeSamples = 8_000,
                hopSizeSamples = 4_000,
                expectsNormalizedAudio = true
            )
        )

        val fakePipeline = FakeFrameStreamingPipeline()
        val fakeClassifier = RuleBasedTestClassifier()
        val controller = InferenceController(
            modelMetadata = metadata,
            audioPipeline = fakePipeline,
            classifier = fakeClassifier,
            scope = this // Testing in current scope
        )

        controller.setThreshold(0.2f)
        controller.start()

        fakePipeline.emit(frameWithMean(0.8f))
        advanceUntilIdle()
        assertEquals("TARGET", controller.uiState.value.topLabel)

        fakePipeline.emit(frameWithMean(-0.7f))
        advanceUntilIdle()
        assertEquals("JUNK", controller.uiState.value.topLabel)

        fakePipeline.emit(frameWithMean(0.01f))
        advanceUntilIdle()
        assertEquals("AMBIENT", controller.uiState.value.topLabel)

        controller.release()
    }

    private fun frameWithMean(value: Float): AudioPipelineFrame {
        val samples = FloatArray(8_000) { value }
        return AudioPipelineFrame(
            samples = samples,
            receivedAtNanos = System.nanoTime(),
            rmsLevel = kotlin.math.abs(value),
            clippingDetected = false,
            signalPresent = kotlin.math.abs(value) > 0.02f
        )
    }
}

private class RuleBasedTestClassifier : AudioWindowClassifier {
    override fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val mean = samples.average().toFloat()
        return when {
            mean > 0.2f -> InferenceResult("TARGET", 0.92f, mapOf("TARGET" to 0.92f), 1)
            mean < -0.2f -> InferenceResult("JUNK", 0.9f, mapOf("JUNK" to 0.9f), 1)
            else -> InferenceResult("AMBIENT", 0.95f, mapOf("AMBIENT" to 0.95f), 1)
        }
    }

    override fun close() = Unit
}

private class FakeFrameStreamingPipeline : FrameStreamingPipeline {
    private var frameCallback: ((AudioPipelineFrame) -> Unit)? = null
    private val signalFlow = MutableStateFlow(AudioSignalStatus())

    override val signalStatusFlow: StateFlow<AudioSignalStatus> = signalFlow

    override fun setPassthroughEnabled(enabled: Boolean) = Unit

    override fun setInputDevice(device: AudioDeviceInfo?) = Unit

    override fun setOutputDevice(device: AudioDeviceInfo?) = Unit

    override fun start(onFrame: (AudioPipelineFrame) -> Unit) {
        frameCallback = onFrame
    }

    override fun stop() {
        frameCallback = null
    }

    override fun release() {
        frameCallback = null
    }

    fun emit(frame: AudioPipelineFrame) {
        signalFlow.value = AudioSignalStatus(
            rmsLevel = frame.rmsLevel,
            clippingDetected = frame.clippingDetected,
            signalPresent = frame.signalPresent
        )
        frameCallback?.invoke(frame)
    }
}
