package com.metaldetectoraudioapp.app.inference

import com.metaldetectoraudioapp.app.audio.pipeline.AudioPipelineFrame
import com.metaldetectoraudioapp.app.audio.pipeline.FrameStreamingPipeline
import com.metaldetectoraudioapp.app.audio.pipeline.MelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import com.metaldetectoraudioapp.app.util.Clocks
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InferenceController(
    private val modelMetadata: ModelMetadata,
    private val audioPipeline: FrameStreamingPipeline,
    private val classifier: AudioWindowClassifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logTag = "InferenceController"

    private val _uiState = MutableStateFlow(
        InferenceUiState(
            modelName = modelMetadata.modelName,
            modelVersion = modelMetadata.modelVersion,
            activeAccelerator = classifier.activeAccelerator,
            threshold = modelMetadata.recommendedThreshold
        )
    )
    val uiState: StateFlow<InferenceUiState> = _uiState

    /** Tone-quality ribbon visual state, fed from the same log-mel the model consumes. */
    val ribbon = RibbonAnalyzer()
    private val melExtractor = MelSpectrogramFeatureExtractor()

    private var signalMonitorJob: Job? = null

    @Volatile
    private var inferenceInFlight = false
    private var latencyAccumulatorMs = 0L
    private var inferenceCount = 0L

    /** Logged once so a persistent inference failure doesn't spam the console every frame. */
    @Volatile
    private var inferenceErrorLogged = false

    /** Epoch-ms until which the sticky TARGET banner should remain visible. */
    @Volatile
    private var stickyTargetEndMs = 0L

    fun setThreshold(value: Float) {
        _uiState.value = _uiState.value.copy(threshold = value)
    }

    fun setPassthroughEnabled(enabled: Boolean) {
        audioPipeline.setPassthroughEnabled(enabled)
    }

    fun start() {
        if (_uiState.value.isRunning) return
        ribbon.reset()
        _uiState.value = _uiState.value.copy(isRunning = true)
        signalMonitorJob = scope.launch {
            audioPipeline.signalStatusFlow.collectLatest { status ->
                _uiState.value = _uiState.value.copy(signalStatus = status)
            }
        }
        audioPipeline.start { frame -> handleFrame(frame) }
    }

    fun stop() {
        audioPipeline.stop()
        signalMonitorJob?.cancel()
        signalMonitorJob = null
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    fun release() {
        stop()
        classifier.close()
        audioPipeline.release()
    }

    private fun handleFrame(frame: AudioPipelineFrame) {
        if (!_uiState.value.isRunning) return

        // Update the tone-quality ribbon every frame — even when an inference is dropped below —
        // so the visual keeps scrolling. Cheap: reuses the same STFT/mel the model consumes.
        ribbon.process(melExtractor.extractLogMelSpectrogram(frame.samples))

        if (inferenceInFlight) {
            _uiState.value = _uiState.value.copy(droppedFrames = _uiState.value.droppedFrames + 1)
            AppLogger.w(logTag, "Dropping inference frame to stay real-time")
            return
        }

        inferenceInFlight = true
        scope.launch {
            try {
                val result = classifier.classifyAudioWindow(frame.samples)
                val latencyMs = ((Clocks.monotonicNanos() - frame.receivedAtNanos) / 1_000_000).coerceAtLeast(0)
                inferenceCount += 1
                latencyAccumulatorMs += latencyMs

                val threshold = _uiState.value.threshold
                val winningLabel = if (result.topScore >= threshold) result.topLabel else "AMBIENT"
                val now = Clocks.epochMillis()

                val cutoff = now - InferenceUiState.RECENT_WINDOW_MS
                val currentState = _uiState.value

                val updatedRecent = if (winningLabel != "AMBIENT") {
                    val detection = RecentDetection(
                        label = winningLabel,
                        confidence = result.topScore,
                        timestampMs = now
                    )
                    (currentState.recentDetections + detection)
                        .filter { it.timestampMs > cutoff }
                        .takeLast(InferenceUiState.MAX_RECENT_DETECTIONS)
                } else {
                    currentState.recentDetections.filter { it.timestampMs > cutoff }
                }

                if (winningLabel == "TARGET") {
                    stickyTargetEndMs = now + InferenceUiState.STICKY_TARGET_DURATION_MS
                }
                val stickyActive = now < stickyTargetEndMs
                val stickyConfidence = if (winningLabel == "TARGET") {
                    result.topScore
                } else if (stickyActive) {
                    currentState.stickyTargetConfidence
                } else {
                    0f
                }

                val recentTargetCount = updatedRecent.count { it.label == "TARGET" }

                _uiState.value = currentState.copy(
                    topLabel = winningLabel,
                    confidence = result.topScore,
                    lastInferenceMs = result.inferenceTimeMs,
                    inferenceError = null,
                    averageLatencyMs = if (inferenceCount == 0L) 0f
                    else latencyAccumulatorMs.toFloat() / inferenceCount,
                    recentDetections = updatedRecent,
                    stickyTargetActive = stickyActive,
                    stickyTargetConfidence = stickyConfidence,
                    recentTargetCount = recentTargetCount
                )
                inferenceErrorLogged = false
                AppLogger.d(
                    logTag,
                    "top=$winningLabel score=${result.topScore} inferMs=${result.inferenceTimeMs} " +
                            "latencyMs=$latencyMs sticky=$stickyActive recent=$recentTargetCount"
                )
            } catch (t: Throwable) {
                // Without this, an exception in classifyAudioWindow is silently dropped by the
                // coroutine scope, leaving the UI frozen at its default ("AMBIENT", 0.0, 0 ms).
                val message = t.message ?: t.toString()
                _uiState.value = _uiState.value.copy(inferenceError = message)
                if (!inferenceErrorLogged) {
                    inferenceErrorLogged = true
                    AppLogger.e(logTag, "Inference failed: $message")
                }
            } finally {
                inferenceInFlight = false
            }
        }
    }
}
