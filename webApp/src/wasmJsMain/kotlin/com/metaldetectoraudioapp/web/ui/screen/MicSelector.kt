package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.metaldetectoraudioapp.web.audio.WebPassthroughMonitor
import com.metaldetectoraudioapp.web.audio.listAudioDevices
import com.metaldetectoraudioapp.web.audio.outputSelectionSupported
import com.metaldetectoraudioapp.web.audio.registerDeviceChangeListener
import com.metaldetectoraudioapp.web.audio.selectedMicDeviceId
import com.metaldetectoraudioapp.web.audio.selectedOutputDeviceId
import com.metaldetectoraudioapp.web.audio.setSelectedMicDeviceId
import kotlinx.coroutines.launch

private const val DEFAULT_LABEL = "System default"

/**
 * Microphone input picker for the web app. Mirrors the Android input-device picker styling so it
 * reads as a sibling of the other settings rows (label on the left, a tappable Material 3 chip on
 * the right). The choice is stored globally (see [setSelectedMicDeviceId]) so it governs both the
 * Detect (inference) and Record capture paths.
 *
 * A refresh button re-enumerates on demand (e.g. after plugging in a USB metal-detector adapter),
 * and a `devicechange` listener auto-refreshes on hot-plug. When [passthroughEnabled] is true and
 * the browser supports output selection, an output (speaker/headphone) picker is shown.
 */
@Composable
fun MicSelector(
    modifier: Modifier = Modifier,
    passthroughEnabled: Boolean = false,
) {
    var devices by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    var selectedId by remember { mutableStateOf(selectedMicDeviceId()) }
    var selectedOutId by remember { mutableStateOf(selectedOutputDeviceId()) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val snapshot = listAudioDevices()
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
                "Microphone",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            DeviceDropdown(
                selectedLabel = devices.firstOrNull { it.deviceId == selectedId }?.label ?: DEFAULT_LABEL,
                devices = devices,
                onDefault = { selectedId = ""; setSelectedMicDeviceId("") },
                onSelected = { selectedId = it.deviceId; setSelectedMicDeviceId(it.deviceId) },
            )
            FilledTonalButton(onClick = { scope.launch { reload() } }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Refresh")
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

        UsbInputDiagnostic(inputs = devices, outputs = outputs)
    }
}

/** Best-effort note for a USB connection the browser exposes only as an output. */
@Composable
private fun UsbInputDiagnostic(inputs: List<MicDevice>, outputs: List<MicDevice>) {
    fun List<MicDevice>.hasUsb() = any { it.label.contains("usb", ignoreCase = true) }
    if (outputs.hasUsb() && !inputs.hasUsb()) {
        Text(
            "The browser exposes this USB connection as output-only, so the app cannot record it. " +
                "Use a USB-C interface or TRRS microphone adapter that Android exposes as an input.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
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
