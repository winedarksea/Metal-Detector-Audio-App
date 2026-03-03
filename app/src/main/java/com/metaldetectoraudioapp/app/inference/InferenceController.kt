package com.metaldetectoraudioapp.app.inference

import android.util.Log
import com.metaldetectoraudioapp.app.audio.pipeline.AudioPipelineFrame
import com.metaldetectoraudioapp.app.audio.pipeline.FrameStreamingPipeline
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
            threshold = 0.55f
        )
    )
    val uiState: StateFlow<InferenceUiState> = _uiState

    private var signalMonitorJob: Job? = null

    @Volatile
    private var inferenceInFlight = false
    private var latencyAccumulatorMs = 0L
    private var inferenceCount = 0L

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
        if (_uiState.value.isRunning) {
            return
        }

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

    private fun handleFrame(frame: AudioPipelineFrame) {
        if (!_uiState.value.isRunning) {
            return
        }

        // Optimization: Manual downsampling to avoid GC pressure from Sequence/chunked/map
        // inside the high-frequency audio callback.
        val rawSamples = frame.samples
        val previewSize = 120
        // Calculate chunk size dynamically to fit the actual frame size
        val chunkSize = (rawSamples.size / previewSize).coerceAtLeast(1)
        val newPoints = ArrayList<Float>(previewSize)

        for (i in 0 until previewSize) {
            val offset = i * chunkSize
            if (offset + chunkSize > rawSamples.size) break
            var sum = 0f
            for (j in 0 until chunkSize) {
                sum += rawSamples[offset + j]
            }
            newPoints.add(sum / chunkSize)
        }

        _uiState.value = _uiState.value.copy(
            waveformPreviewPoints = newPoints
        )

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
            } finally {
                inferenceInFlight = false
            }
        }
    }
}
