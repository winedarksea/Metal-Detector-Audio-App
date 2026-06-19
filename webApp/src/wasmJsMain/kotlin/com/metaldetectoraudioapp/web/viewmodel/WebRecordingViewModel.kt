package com.metaldetectoraudioapp.web.viewmodel

import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.app.recording.AudioTrim
import com.metaldetectoraudioapp.app.recording.CapturedRecording
import com.metaldetectoraudioapp.app.recording.RecordingLabelDraft
import com.metaldetectoraudioapp.app.recording.RecordingObjectLabel
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.recording.WavCodec
import com.metaldetectoraudioapp.app.ui.model.ClassLabel
import com.metaldetectoraudioapp.app.ui.model.PendingImage
import com.metaldetectoraudioapp.app.ui.model.RecordingDraftUiState
import com.metaldetectoraudioapp.app.ui.model.parseLabelEntries
import com.metaldetectoraudioapp.app.ui.model.RecordingUiState
import com.metaldetectoraudioapp.app.ui.model.SweepPattern
import com.metaldetectoraudioapp.app.util.Clocks
import com.metaldetectoraudioapp.web.audio.selectedPreviewOutputDeviceId
import com.metaldetectoraudioapp.web.platform.WebLocationProvider
import com.metaldetectoraudioapp.web.platform.WebLocationResult
import com.metaldetectoraudioapp.web.platform.WebPhotoCaptureProvider
import com.metaldetectoraudioapp.web.platform.WebPhotoCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WebRecordingViewModel(
    private val recordingRepository: RecordingRepository,
    private val audioPlayer: AudioPlayer,
    private val photoCaptureProvider: WebPhotoCaptureProvider,
    private val locationProvider: WebLocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pcmSamples = mutableListOf<Float>()
    private var captureSampleRate = 16_000
    private var recordingStartMs = 0L
    private var isCapturing = false

    private var lastCapturedRecording: CapturedRecording? = null
    private var playbackJob: Job? = null
    private var durationTickerJob: Job? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.isRecording) return
        clearPendingInternal(announce = false)
        pcmSamples.clear()
        recordingStartMs = Clocks.epochMillis()
        isCapturing = true
        globalRecordingViewModel = this
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            saveResultMessage = null,
            errorMessage = null,
            pendingDurationMs = 0,
        )
        startDurationTicker()
        startWebCapture(
            onChunk = { ctxRate, count -> onRecChunkGlobal(ctxRate, count) },
            onError = { message -> onCaptureStartFailed(message) },
        )
    }

    internal fun onRecChunk(ctxRate: Int, count: Int) {
        captureSampleRate = ctxRate
        if (!isCapturing) return
        val buf = readGlobalRecBuf(count)
        pcmSamples.addAll(buf.asList())
    }

    internal fun onCaptureStartFailed(message: String) {
        if (!isCapturing) return
        stopDurationTicker()
        isCapturing = false
        stopWebCapture()
        if (globalRecordingViewModel === this) globalRecordingViewModel = null
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            errorMessage = "Recording microphone failed: $message",
        )
    }

    fun stopRecording() {
        stopDurationTicker()
        isCapturing = false
        stopWebCapture()
        if (globalRecordingViewModel === this) globalRecordingViewModel = null

        val src = pcmSamples.toFloatArray()
        val resampled = resampleLinear(src, captureSampleRate, 16_000)
        val shorts = ShortArray(resampled.size) { i ->
            (resampled[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val wavBytes = WavCodec.encodePcm16(shorts, 16_000, channels = 1)
        val durationMs = (Clocks.epochMillis() - recordingStartMs).coerceAtLeast(0L)
        val captured = CapturedRecording(wavBytes = wavBytes, durationMs = durationMs)
        lastCapturedRecording = captured
        val envelope = if (shorts.isEmpty()) emptyList() else AudioTrim.clipEnvelope(wavBytes)

        _uiState.value = _uiState.value.copy(
            isRecording = false,
            pendingAudio = captured,
            pendingDurationMs = durationMs,
            clipEnvelope = envelope,
            trimStartMs = 0,
            trimEndMs = durationMs,
            saveResultMessage = null,
            errorMessage = if (shorts.isEmpty()) "No audio captured — check microphone permission" else null
        )
    }

    fun clearPendingCapture() = clearPendingInternal(announce = true)

    fun updateTrim(startMs: Long, endMs: Long) {
        _uiState.value = _uiState.value.copy(trimStartMs = startMs, trimEndMs = endMs)
    }

    fun resetTrim() {
        _uiState.value = _uiState.value.copy(
            trimStartMs = 0,
            trimEndMs = _uiState.value.pendingDurationMs,
        )
    }

    fun playPreview() {
        val state = _uiState.value
        val captured = state.pendingAudio ?: return
        stopPreview()
        val bytes = if (state.isTrimmed) {
            AudioTrim.trimWav(captured.wavBytes, state.trimStartMs, state.trimEndMs)
        } else {
            captured.wavBytes
        }
        playbackJob = scope.launch {
            _uiState.value = _uiState.value.copy(isPlayingPreview = true)
            audioPlayer.play(bytes, selectedPreviewOutputDeviceId())
            _uiState.value = _uiState.value.copy(isPlayingPreview = false)
        }
    }

    fun stopPreview() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        _uiState.value = _uiState.value.copy(isPlayingPreview = false)
    }

    fun updateTargetNames(value: String) {
        updateDraft(_uiState.value.draft.copy(targetNameInput = value))
    }
    fun updatePattern(value: SweepPattern) = updateDraft(_uiState.value.draft.copy(pattern = value))
    fun updateDepthInches(value: String) = updateDraft(_uiState.value.draft.copy(depthInches = value))
    fun updateNotes(value: String) = updateDraft(_uiState.value.draft.copy(notesInput = value))
    fun updateIncludeInTraining(value: Boolean) = updateDraft(_uiState.value.draft.copy(includeInTraining = value))
    fun updateSoilType(value: String) = updateDraft(_uiState.value.draft.copy(soilType = value))
    fun updateMoisture(value: String) = updateDraft(_uiState.value.draft.copy(moisture = value))
    fun updateDetectorModel(value: String) = updateDraft(_uiState.value.draft.copy(detectorModel = value))
    fun updateSearchMode(value: String) = updateDraft(_uiState.value.draft.copy(searchMode = value))
    fun updateAudioProfile(value: String) = updateDraft(_uiState.value.draft.copy(audioProfile = value))
    fun updateSensitivity(value: String) = updateDraft(_uiState.value.draft.copy(sensitivity = value))
    fun updateRecoverySpeed(value: String) = updateDraft(_uiState.value.draft.copy(recoverySpeed = value))
    fun updateStabilizer(value: String) = updateDraft(_uiState.value.draft.copy(stabilizer = value))

    fun capturePhoto(useCamera: Boolean) {
        if (_uiState.value.isRecording || _uiState.value.isPhotoCaptureInProgress) {
            return
        }

        _uiState.value = _uiState.value.copy(
            isPhotoCaptureInProgress = true,
            saveResultMessage = null,
            errorMessage = null,
        )
        photoCaptureProvider.capturePhoto(useCamera) { result ->
            when (result) {
                is WebPhotoCaptureResult.Captured -> {
                    _uiState.value = _uiState.value.copy(
                        pendingImage = PendingImage(result.bytes, result.extension),
                        isPhotoCaptureInProgress = false,
                        saveResultMessage = "Photo attached",
                        errorMessage = null,
                    )
                }
                WebPhotoCaptureResult.Cancelled -> {
                    _uiState.value = _uiState.value.copy(
                        isPhotoCaptureInProgress = false,
                        saveResultMessage = "Photo selection cancelled",
                    )
                }
                is WebPhotoCaptureResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isPhotoCaptureInProgress = false,
                        errorMessage = "Photo failed: ${result.message}",
                    )
                }
            }
        }
    }

    fun removePendingPhoto() {
        _uiState.value = _uiState.value.copy(
            pendingImage = null,
            saveResultMessage = "Photo removed",
            errorMessage = null,
        )
    }

    fun captureCurrentLocation() {
        if (_uiState.value.isLocationCaptureInProgress) {
            return
        }

        _uiState.value = _uiState.value.copy(
            isLocationCaptureInProgress = true,
            saveResultMessage = null,
            errorMessage = null,
        )
        locationProvider.captureCurrentLocation { result ->
            when (result) {
                is WebLocationResult.Captured -> {
                    updateDraft(
                        _uiState.value.draft.copy(
                            gpsLatitude = result.latitude,
                            gpsLongitude = result.longitude,
                        )
                    )
                    _uiState.value = _uiState.value.copy(
                        isLocationCaptureInProgress = false,
                        saveResultMessage = "Current GPS location captured",
                        errorMessage = null,
                    )
                }
                WebLocationResult.PermissionDenied -> setLocationCaptureError(
                    "Location permission was denied. Allow location in this site's browser settings to retry."
                )
                WebLocationResult.Timeout -> setLocationCaptureError(
                    "Location request timed out. Move to an open area and retry."
                )
                WebLocationResult.Unavailable -> setLocationCaptureError(
                    "Current location is unavailable. Ensure device location services are on and retry."
                )
                WebLocationResult.Unsupported -> setLocationCaptureError(
                    "Location is not supported by this browser."
                )
                is WebLocationResult.Failed -> setLocationCaptureError(
                    "Location failed: ${result.message}"
                )
            }
        }
    }

    fun clearCurrentLocation() {
        updateDraft(
            _uiState.value.draft.copy(
                gpsLatitude = null,
                gpsLongitude = null,
            )
        )
        _uiState.value = _uiState.value.copy(
            saveResultMessage = "GPS location cleared",
            errorMessage = null,
        )
    }

    fun saveRecording() {
        val captured = lastCapturedRecording ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Record audio before saving")
            return
        }
        val draft = _uiState.value.draft
        val objectLabels = parseLabelEntries(draft.targetNameInput)
            .filter { it.obj.isNotBlank() || it.name.isNotBlank() || it.material.isNotBlank() }
            .map {
                RecordingObjectLabel("${it.obj}:${it.name}:${it.material}", it.labelClass)
            }
        if (objectLabels.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "At least one labeled object is required")
            return
        }
        val state = _uiState.value
        val recordingToSave = if (state.isTrimmed) {
            val trimmed = AudioTrim.trimWav(captured.wavBytes, state.trimStartMs, state.trimEndMs)
            CapturedRecording(wavBytes = trimmed, durationMs = AudioTrim.durationMs(trimmed))
        } else {
            captured
        }
        val pendingImage = state.pendingImage

        scope.launch {
            try {
                val metadata = recordingRepository.saveCapturedRecording(
                    capturedRecording = recordingToSave,
                    labelDraft = RecordingLabelDraft(
                        objectLabels = objectLabels,
                        pattern = draft.pattern,
                        depthInches = draft.depthInches.ifBlank { null },
                        notes = draft.notesInput.ifBlank { null },
                        gpsLatitude = draft.gpsLatitude,
                        gpsLongitude = draft.gpsLongitude,
                        includeInTraining = draft.includeInTraining,
                        soilType = draft.soilType.ifBlank { null },
                        moisture = draft.moisture.ifBlank { null },
                        detectorModel = draft.detectorModel.ifBlank { null },
                        searchMode = draft.searchMode.ifBlank { null },
                        audioProfile = draft.audioProfile.ifBlank { null },
                        sensitivity = draft.sensitivity.ifBlank { null },
                        recoverySpeed = draft.recoverySpeed.ifBlank { null },
                        stabilizer = draft.stabilizer.ifBlank { null },
                        imageBytes = pendingImage?.bytes,
                        imageExtension = pendingImage?.extension,
                    )
                )
                lastCapturedRecording = null
                _uiState.value = RecordingUiState(
                    draft = RecordingDraftUiState(
                        includeInTraining = true,
                        pattern = metadata.pattern
                    ),
                    saveResultMessage = "Saved ${metadata.audioFileName}",
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(errorMessage = "Save failed: ${e.message}")
            }
        }
    }

    fun close() {
        isCapturing = false
        if (globalRecordingViewModel === this) globalRecordingViewModel = null
        stopWebCapture()
        audioPlayer.stop()
        playbackJob?.cancel()
        durationTickerJob?.cancel()
        scope.cancel()
    }

    private fun clearPendingInternal(announce: Boolean) {
        stopDurationTicker()
        stopPreview()
        lastCapturedRecording = null
        pcmSamples.clear()
        _uiState.value = _uiState.value.copy(
            pendingAudio = null,
            pendingImage = null,
            isPhotoCaptureInProgress = false,
            pendingDurationMs = 0,
            clipEnvelope = emptyList(),
            trimStartMs = 0,
            trimEndMs = 0,
            isPlayingPreview = false,
            saveResultMessage = if (announce) "Cleared unsaved recording" else null,
            errorMessage = null,
        )
    }

    private fun updateDraft(newDraft: RecordingDraftUiState) {
        _uiState.value = _uiState.value.copy(draft = newDraft, errorMessage = null)
    }

    private fun setLocationCaptureError(message: String) {
        _uiState.value = _uiState.value.copy(
            isLocationCaptureInProgress = false,
            errorMessage = message,
        )
    }

    private fun startDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = scope.launch {
            while (isActive && _uiState.value.isRecording) {
                val elapsed = (Clocks.epochMillis() - recordingStartMs).coerceAtLeast(0L)
                _uiState.value = _uiState.value.copy(pendingDurationMs = elapsed)
                delay(250L)
            }
        }
    }

    private fun stopDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = null
    }

}

private fun resampleLinear(src: FloatArray, fromHz: Int, toHz: Int): FloatArray {
    if (fromHz == toHz || src.isEmpty()) return src
    val ratio = fromHz.toDouble() / toHz
    val outLen = (src.size / ratio).toInt().coerceAtLeast(1)
    return FloatArray(outLen) { i ->
        val pos = i * ratio
        val lo = pos.toInt().coerceIn(0, src.lastIndex)
        val hi = (lo + 1).coerceIn(0, src.lastIndex)
        src[lo] * (1f - (pos - lo).toFloat()) + src[hi] * (pos - lo).toFloat()
    }
}

// ── Global singleton + JS bridge ─────────────────────────────────────────────

private var globalRecordingViewModel: WebRecordingViewModel? = null

/** Called from JS with only primitive params (satisfies Kotlin/WASM interop rules). */
fun onRecChunkGlobal(ctxRate: Int, count: Int) {
    globalRecordingViewModel?.onRecChunk(ctxRate, count)
}

private fun readGlobalRecBuf(count: Int): FloatArray {
    val buf = FloatArray(count)
    for (i in 0 until count) buf[i] = readRecBufAt(i)
    return buf
}

private fun readRecBufAt(i: Int): Float = js("window.__recBuf[i]")

private fun startWebCapture(onChunk: (Int, Int) -> Unit, onError: (String) -> Unit) {
    js("""
        var __mic = {
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false,
            channelCount: 1
        };
        if (window.__micDeviceId) __mic.deviceId = { exact: window.__micDeviceId };
        navigator.mediaDevices.getUserMedia({ audio: __mic, video: false }).then(function(stream) {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            window.__recCtx = ctx;
            window.__recStream = stream;
            // iOS Safari starts the context suspended; resume it within this user gesture.
            if (ctx.state === 'suspended' && ctx.resume) ctx.resume();
            var src = ctx.createMediaStreamSource(stream);

            function sendChunk(channelData) {
                window.__recBuf = channelData;
                onChunk(Math.round(ctx.sampleRate), channelData.length);
            }

            if (ctx.audioWorklet) {
                ctx.audioWorklet.addModule('/app/mic-worklet.js').then(function() {
                    var node = new AudioWorkletNode(ctx, 'mic-processor');
                    var silentGain = ctx.createGain();
                    silentGain.gain.value = 0;
                    window.__recNode = node;
                    window.__recSilentGain = silentGain;
                    node.port.onmessage = function(e) { sendChunk(e.data); };
                    src.connect(node);
                    node.connect(silentGain);
                    silentGain.connect(ctx.destination);
                }).catch(function() { fallback(ctx, src, sendChunk); });
            } else { fallback(ctx, src, sendChunk); }

            function fallback(ctx, src, sendChunk) {
                var proc = ctx.createScriptProcessor(4096, 1, 1);
                window.__recProc = proc;
                proc.onaudioprocess = function(e) { sendChunk(e.inputBuffer.getChannelData(0)); };
                src.connect(proc);
                proc.connect(ctx.destination);
            }
        }).catch(function(e) {
            var message = (e && e.message) ? e.message : String(e);
            console.error('Recording getUserMedia failed:', e);
            onError(message);
        });
    """)
}

private fun stopWebCapture() {
    js("""
        if (window.__recNode)   { window.__recNode.disconnect();   window.__recNode = null; }
        if (window.__recSilentGain) { window.__recSilentGain.disconnect(); window.__recSilentGain = null; }
        if (window.__recProc)   { window.__recProc.disconnect();   window.__recProc = null; }
        if (window.__recStream) { window.__recStream.getTracks().forEach(function(t) { t.stop(); }); window.__recStream = null; }
        if (window.__recCtx)    { window.__recCtx.close();         window.__recCtx = null; }
        window.__recBuf = null;
    """)
}
