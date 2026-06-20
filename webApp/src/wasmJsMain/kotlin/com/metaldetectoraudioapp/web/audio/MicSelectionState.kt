package com.metaldetectoraudioapp.web.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single reactive source of truth for the selected microphone input on the web app.
 *
 * The chosen deviceId is mirrored into the JS global `window.__micDeviceId` (read directly by the
 * pure-JS capture code in [WebMicrophoneInputSource] and `WebRecordingViewModel`), but Kotlin owns
 * the value here so it can be observed by Compose ([selectedDeviceId]) and diagnosed from deep inside
 * the strict `getUserMedia` verifier (see [getUserMediaStrictlyVerified]).
 *
 * When the browser cannot prove that capture came from the requested input, we keep the user's
 * selection visible and reject capture rather than silently recording from the built-in microphone.
 */
object MicSelectionState {
    private val _selectedDeviceId = MutableStateFlow(selectedMicDeviceId())
    val selectedDeviceId: StateFlow<String> = _selectedDeviceId.asStateFlow()

    private val _statusNote = MutableStateFlow<String?>(null)
    val statusNote: StateFlow<String?> = _statusNote.asStateFlow()

    private val _diagnostics = MutableStateFlow(MicCaptureDiagnostics())
    val diagnostics: StateFlow<MicCaptureDiagnostics> = _diagnostics.asStateFlow()

    /** Human label of the current selection, used to phrase verification notes. */
    private var selectedLabel: String = ""

    /** User picked a device (deviceId "" = system default). Clears any prior status note. */
    fun select(deviceId: String, label: String) {
        selectedLabel = if (deviceId.isBlank()) "" else label
        setSelectedMicDeviceId(deviceId)
        _selectedDeviceId.value = deviceId
        _statusNote.value = null
        _diagnostics.value = MicCaptureDiagnostics(
            selectedLabel = if (deviceId.isBlank()) "System default" else label,
            requestedDeviceId = deviceId,
            verificationStatus = MicVerificationStatus.IDLE,
        )
    }

    /** The browser returned a track and its reported device matched the requested device if any. */
    fun reportVerifiedCapture(requestedDeviceId: String, actualDeviceId: String) {
        _diagnostics.value = MicCaptureDiagnostics(
            selectedLabel = selectedLabel.ifBlank { "System default" },
            requestedDeviceId = requestedDeviceId,
            actualDeviceId = actualDeviceId,
            verificationStatus = if (requestedDeviceId.isBlank()) {
                MicVerificationStatus.UNVERIFIED_DEFAULT
            } else {
                MicVerificationStatus.VERIFIED
            },
        )
        _statusNote.value = null
    }

    /** The browser returned a track that did not prove it came from the selected input. */
    fun reportRejectedCapture(requestedDeviceId: String, actualDeviceId: String) {
        val label = selectedLabel.ifBlank { "The selected microphone" }
        _diagnostics.value = MicCaptureDiagnostics(
            selectedLabel = label,
            requestedDeviceId = requestedDeviceId,
            actualDeviceId = actualDeviceId,
            verificationStatus = MicVerificationStatus.REJECTED,
        )
        _statusNote.value =
            "$label could not be verified in this browser. Capture stopped instead of using System default."
    }

    /** Surface a hard microphone error (no usable input at all, e.g. permission denied). */
    fun reportError(message: String) {
        if (
            _diagnostics.value.verificationStatus == MicVerificationStatus.REJECTED &&
            message.startsWith("Selected microphone could not be verified")
        ) {
            return
        }
        _statusNote.value = "Microphone error: $message"
    }
}

data class MicCaptureDiagnostics(
    val selectedLabel: String = "System default",
    val requestedDeviceId: String = "",
    val actualDeviceId: String = "",
    val verificationStatus: MicVerificationStatus = MicVerificationStatus.IDLE,
)

enum class MicVerificationStatus {
    IDLE,
    VERIFIED,
    UNVERIFIED_DEFAULT,
    REJECTED,
}
