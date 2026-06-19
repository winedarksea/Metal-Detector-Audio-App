package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.web.audio.MicDevice
import com.metaldetectoraudioapp.web.audio.MicSelectionState
import com.metaldetectoraudioapp.web.audio.WebPassthroughMonitor
import com.metaldetectoraudioapp.web.audio.listAudioDevices
import com.metaldetectoraudioapp.web.audio.mediaElementSinkSelectionSupported
import com.metaldetectoraudioapp.web.audio.outputSelectionSupported
import com.metaldetectoraudioapp.web.audio.registerDeviceChangeListener
import com.metaldetectoraudioapp.web.audio.selectedOutputDeviceId
import com.metaldetectoraudioapp.web.audio.selectedPreviewOutputDeviceId
import com.metaldetectoraudioapp.web.audio.setSelectedPreviewOutputDeviceId
import kotlinx.coroutines.launch

private const val DEFAULT_LABEL = "System default"

/**
 * Microphone input picker for the web app. Mirrors the Android input-device picker styling so it
 * reads as a sibling of the other settings rows (label on the left, a tappable Material 3 chip on
 * the right). The choice is stored in the [MicSelectionState] reactive holder (mirrored to the JS
 * global the capture paths read) so it governs both the Detect (inference) and Record capture
 * paths, and so a capture-time fallback to the system default can visibly revert the selection.
 *
 * A refresh button re-enumerates on demand (e.g. after plugging in a USB metal-detector adapter),
 * and a `devicechange` listener auto-refreshes on hot-plug. When [passthroughEnabled] is true and
 * the browser supports output selection, an output (speaker/headphone) picker is shown.
 */
@Composable
fun MicSelector(
    modifier: Modifier = Modifier,
    passthroughEnabled: Boolean = false,
    previewPlaybackEnabled: Boolean = false,
    inputLabel: String = "Microphone",
) {
    var devices by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    // Selected input + status note come from the shared reactive holder so a capture-time fallback to
    // the system default visibly reverts the dropdown (see MicSelectionState).
    val selectedId by MicSelectionState.selectedDeviceId.collectAsState()
    val micStatusNote by MicSelectionState.statusNote.collectAsState()
    var selectedOutId by remember { mutableStateOf(selectedOutputDeviceId()) }
    var selectedPreviewOutId by remember { mutableStateOf(selectedPreviewOutputDeviceId()) }
    val scope = rememberCoroutineScope()

    suspend fun reload(activeStream: Boolean = false) {
        val snapshot = listAudioDevices(useActiveStream = activeStream)
        devices = snapshot.inputDevices
        outputs = snapshot.outputDevices
    }

    LaunchedEffect(Unit) {
        reload()
        registerDeviceChangeListener { scope.launch { reload() } }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                inputLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            DeviceDropdown(
                selectedLabel = devices.firstOrNull { it.deviceId == selectedId }?.label ?: DEFAULT_LABEL,
                devices = devices,
                onDefault = { MicSelectionState.select("", DEFAULT_LABEL) },
                onSelected = { MicSelectionState.select(it.deviceId, it.label) },
            )
            IconButton(
                onClick = { scope.launch { reload(activeStream = true) } },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh audio sources")
            }
        }

        // Surfaced when capture couldn't open the chosen device and reverted to the system default,
        // or when the mic failed outright — so the user never assumes a USB device is in use when it isn't.
        micStatusNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (previewPlaybackEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Playback Device",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DeviceDropdown(
                    selectedLabel = outputs.firstOrNull { it.deviceId == selectedPreviewOutId }?.label
                        ?: DEFAULT_LABEL,
                    devices = outputs,
                    onDefault = {
                        selectedPreviewOutId = ""
                        setSelectedPreviewOutputDeviceId("")
                    },
                    onSelected = { requestedDevice ->
                        selectedPreviewOutId = requestedDevice.deviceId
                        setSelectedPreviewOutputDeviceId(requestedDevice.deviceId)
                    },
                )
            }
            if (!mediaElementSinkSelectionSupported()) {
                Text(
                    "This browser can't choose the playback output — preview uses the system audio output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (outputs.isEmpty()) {
                Text(
                    "This browser doesn't list audio outputs — playback follows your device's system " +
                        "audio output (change it in your Android/OS sound settings).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (passthroughEnabled && outputSelectionSupported()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Output",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DeviceDropdown(
                    selectedLabel = outputs.firstOrNull { it.deviceId == selectedOutId }?.label ?: DEFAULT_LABEL,
                    devices = outputs,
                    onDefault = { selectedOutId = ""; WebPassthroughMonitor.setOutputSink("") },
                    onSelected = { requestedDevice ->
                        WebPassthroughMonitor.chooseOutputSink(requestedDevice.deviceId) { permittedDeviceId ->
                            selectedOutId = permittedDeviceId
                        }
                    },
                )
            }
            if (outputs.isEmpty()) {
                Text(
                    "This browser doesn't list audio outputs — passthrough follows your device's system " +
                        "audio output (change it in your Android/OS sound settings).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (passthroughEnabled) {
            Text(
                "This browser can only use the system-selected audio output. Change the output " +
                    "in Android or browser system controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (passthroughEnabled) {
            Text(
                "Speaker passthrough is audible while detection is running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // No fragile label string-matching: if a chosen input can't be opened, capture now falls
        // back to the default and the status note above says so. When no separate input is listed,
        // explain the platform limitation honestly and point to the native app for USB.
        if (devices.size <= 1) {
            Text(
                "Don't see a plugged-in USB microphone? Android browsers may not expose USB audio " +
                    "inputs to web apps — use the native Android app for reliable USB recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceDropdown(
    selectedLabel: String,
    devices: List<MicDevice>,
    onDefault: () -> Unit,
    onSelected: (MicDevice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            selectedLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(DEFAULT_LABEL) },
                onClick = { onDefault(); expanded = false }
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.label) },
                    onClick = { onSelected(device); expanded = false }
                )
            }
        }
    }
}
