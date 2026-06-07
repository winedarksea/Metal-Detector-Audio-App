package com.metaldetectoraudioapp.app.inference

import android.media.AudioDeviceInfo
import android.util.Log
import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.audio.pipeline.AudioPipelineFrame
import com.metaldetectoraudioapp.app.audio.pipeline.FrameStreamingPipeline
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InferenceController(
    private var modelMetadata: ModelMetadata,
    private val audioPipeline: FrameStreamingPipeline,
    initialClassifier: AudioWindowClassifier,
    private val metadataRepository: ModelMetadataRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logTag = "InferenceController"

    private val _uiState = MutableStateFlow(
        InferenceUiState(
            modelName = modelMetadata.modelName,
            modelVersion = modelMetadata.modelVersion,
            activeAccelerator = initialClassifier.activeAccelerator,
            threshold = modelMetadata.recommendedThreshold
        )
    )
    val uiState: StateFlow<InferenceUiState> = _uiState

    /** Tone-quality ribbon visual state, fed from the same log-mel the model consumes. */
    val ribbon = RibbonAnalyzer()
    private val melExtractor = AndroidMelSpectrogramFeatureExtractor()

    private var signalMonitorJob: Job? = null

    @Volatile
    private var inferenceInFlight = false

    @Volatile
    private var classifier: AudioWindowClassifier = initialClassifier
    private var latencyAccumulatorMs = 0L
    private var inferenceCount = 0L

    /** Epoch-ms until which the sticky TARGET banner should remain visible. */
    @Volatile
    private var stickyTargetEndMs = 0L

    fun setThreshold(value: Float) {
        _uiState.value = _uiState.value.copy(threshold = value)
    }

    fun setBackendPreference(
        preference: InferenceBackendPreference,
        appContext: android.content.Context,
    ) {
        if (_uiState.value.backendPreference == preference) return
        replaceClassifier(
            metadata = modelMetadata,
            appContext = appContext,
            backendPreference = preference,
        )
    }

    fun setPassthroughEnabled(enabled: Boolean) {
        audioPipeline.setPassthroughEnabled(enabled)
    }

    fun setInputDevice(device: AudioDeviceInfo?) {
        audioPipeline.setInputDevice(device)
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        audioPipeline.setOutputDevice(device)
    }

    fun start() {
        if (_uiState.value.isRunning) {
            return
        }

        ribbon.reset()
        _uiState.value = _uiState.value.copy(isRunning = true)
        signalMonitorJob = scope.launch {
            audioPipeline.signalStatusFlow.collectLatest { status ->
                _uiState.value = _uiState.value.copy(signalStatus = status)
            }
        }
        audioPipeline.start { frame ->
            handleFrame(frame)
        }
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

    fun getAvailableModels(): List<ModelMetadata> {
        return metadataRepository.listAvailableMetadata()
    }

    fun switchModel(metadata: ModelMetadata, appContext: android.content.Context) {
        replaceClassifier(
            metadata = metadata,
            appContext = appContext,
            backendPreference = _uiState.value.backendPreference,
        )
    }

    private fun replaceClassifier(
        metadata: ModelMetadata,
        appContext: android.content.Context,
        backendPreference: InferenceBackendPreference,
    ) {
        val wasRunning = _uiState.value.isRunning
        if (wasRunning) {
            stop()
        }

        // Guard: the audio pipeline frame size must match the new model's expected input.
        if (metadata.input.windowSizeSamples != modelMetadata.input.windowSizeSamples ||
            metadata.input.hopSizeSamples != modelMetadata.input.hopSizeSamples
        ) {
            Log.e(logTag, "Cannot switch to model '${metadata.modelName}': " +
                "input config (window=${metadata.input.windowSizeSamples}, hop=${metadata.input.hopSizeSamples}) " +
                "differs from pipeline (window=${modelMetadata.input.windowSizeSamples}, hop=${modelMetadata.input.hopSizeSamples})")
            if (wasRunning) start()
            return
        }

        // Wait for any in-flight inference to finish before closing the old classifier.
        // Bounded sleep-loop avoids burning CPU; inference is normally sub-ms and stop()
        // above prevents new frames from being dispatched, so one or two iterations suffice.
        val maxWaitMs = 300L
        val startWaitMs = System.currentTimeMillis()
        while (inferenceInFlight && System.currentTimeMillis() - startWaitMs < maxWaitMs) {
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (inferenceInFlight) {
            Log.w(logTag, "Inference still in-flight after ${maxWaitMs}ms during model switch; proceeding anyway")
        }

        val oldClassifier = classifier
        modelMetadata = metadata
        classifier = AndroidClassifierFactory.create(
            appContext = appContext,
            modelMetadata = metadata,
            backendPreference = backendPreference,
        )
        oldClassifier.close()

        // Reset inference stats for the new model session.
        latencyAccumulatorMs = 0L
        inferenceCount = 0L

        _uiState.value = _uiState.value.copy(
            modelName = metadata.modelName,
            modelVersion = metadata.modelVersion,
            activeAccelerator = classifier.activeAccelerator,
            backendPreference = backendPreference,
            perLabelScores = emptyMap(),
            lastInferenceError = null,
            threshold = metadata.recommendedThreshold,
            droppedFrames = 0,
            averageLatencyMs = 0f,
            recentDetections = emptyList(),
            stickyTargetActive = false,
            stickyTargetConfidence = 0f,
            recentTargetCount = 0
        )
        stickyTargetEndMs = 0L

        if (wasRunning) {
            start()
        }
    }

    private fun handleFrame(frame: AudioPipelineFrame) {
        if (!_uiState.value.isRunning) {
            return
        }

        // Update the tone-quality ribbon every frame — even when an inference is dropped below —
        // so the visual keeps scrolling. Cheap: reuses the same STFT/mel the model consumes.
        ribbon.process(melExtractor.extractLogMelSpectrogram(frame.samples))

        if (inferenceInFlight) {
            _uiState.value = _uiState.value.copy(droppedFrames = _uiState.value.droppedFrames + 1)
            Log.w(logTag, "Dropping inference frame to stay real-time")
            return
        }

        inferenceInFlight = true
        scope.launch {
            try {
                val result = classifier.classifyAudioWindow(frame.samples)
                val latencyMs = ((System.nanoTime() - frame.receivedAtNanos) / 1_000_000).coerceAtLeast(0)
                inferenceCount += 1
                latencyAccumulatorMs += latencyMs

                val threshold = _uiState.value.threshold
                val winningLabel = if (result.topScore >= threshold) result.topLabel else "AMBIENT"
                val now = System.currentTimeMillis()

                // --- Detection history bookkeeping ---
                val cutoff = now - InferenceUiState.RECENT_WINDOW_MS
                val currentState = _uiState.value

                // Add non-AMBIENT detections to the recent list.
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
                    currentState.recentDetections
                        .filter { it.timestampMs > cutoff }
                }

                // Sticky TARGET banner: activate on TARGET, keep alive for duration.
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
                    perLabelScores = result.perLabelScores,
                    lastInferenceError = null,
                    lastInferenceMs = result.inferenceTimeMs,
                    averageLatencyMs = if (inferenceCount == 0L) 0f else latencyAccumulatorMs.toFloat() / inferenceCount,
                    recentDetections = updatedRecent,
                    stickyTargetActive = stickyActive,
                    stickyTargetConfidence = stickyConfidence,
                    recentTargetCount = recentTargetCount
                )
                Log.d(
                    logTag,
                    "top=$winningLabel score=${result.topScore} inferMs=${result.inferenceTimeMs} latencyMs=$latencyMs sticky=$stickyActive recent=$recentTargetCount"
                )
            } catch (throwable: Throwable) {
                val diagnosticMessage =
                    "${classifier.activeAccelerator.shortLabel} inference failed: " +
                        (throwable.message ?: throwable::class.simpleName)
                Log.e(logTag, diagnosticMessage, throwable)
                _uiState.value = _uiState.value.copy(lastInferenceError = diagnosticMessage)
            } finally {
                inferenceInFlight = false
            }
        }
    }
}
