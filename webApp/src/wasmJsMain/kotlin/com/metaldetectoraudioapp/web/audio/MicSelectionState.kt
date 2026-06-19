package com.metaldetectoraudioapp.web.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single reactive source of truth for the selected microphone input on the web app.
 *
 * The chosen deviceId is mirrored into the JS global `window.__micDeviceId` (read directly by the
 * pure-JS capture code in [WebMicrophoneInputSource] and `WebRecordingViewModel`), but Kotlin owns
 * the value here so it can be observed by Compose ([selectedDeviceId]) and updated from deep inside
 * the `getUserMedia` fallback (see [getUserMediaWithFallback]).
 *
 * When capture can't open the requested device and falls back to the system default, [fellBackToDefault]
 * flips the selection back to "" so the picker visibly reverts and shows a [statusNote]. We never let
 * the UI imply a USB (or other) device is in use when it really isn't — the Android native app is the
 * reliable path for forced USB input.
 */
object MicSelectionState {
    private val _selectedDeviceId = MutableStateFlow(selectedMicDeviceId())
    val selectedDeviceId: StateFlow<String> = _selectedDeviceId.asStateFlow()

    private val _statusNote = MutableStateFlow<String?>(null)
    val statusNote: StateFlow<String?> = _statusNote.asStateFlow()

    /** Human label of the current selection, used to phrase the fallback note. */
    private var selectedLabel: String = ""

    /** User picked a device (deviceId "" = system default). Clears any prior status note. */
    fun select(deviceId: String, label: String) {
        selectedLabel = if (deviceId.isBlank()) "" else label
        setSelectedMicDeviceId(deviceId)
        _selectedDeviceId.value = deviceId
        _statusNote.value = null
    }

    /**
     * Capture couldn't open the requested device and succeeded on the system default instead.
     * Reverts the selection so the picker no longer claims the unavailable device is in use.
     */
    fun fellBackToDefault() {
        val label = selectedLabel.ifBlank { "The selected microphone" }
        selectedLabel = ""
        setSelectedMicDeviceId("")
        _selectedDeviceId.value = ""
        _statusNote.value = "$label is unavailable in this browser — recording with System default."
    }

    /** Surface a hard microphone error (no usable input at all, e.g. permission denied). */
    fun reportError(message: String) {
        _statusNote.value = "Microphone error: $message"
    }
}
