package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.web.audio.MicDevice
import com.metaldetectoraudioapp.web.audio.listMicDevices
import com.metaldetectoraudioapp.web.audio.selectedMicDeviceId
import com.metaldetectoraudioapp.web.audio.setSelectedMicDeviceId

private const val DEFAULT_LABEL = "System default"

/**
 * Microphone input picker for the web app. Mirrors the Android input-device picker styling so it
 * reads as a sibling of the other settings rows (label on the left, a tappable Material 3 chip on
 * the right). The choice is stored globally (see [setSelectedMicDeviceId]) so it governs both the
 * Detect (inference) and Record capture paths.
 */
@Composable
fun MicSelector(modifier: Modifier = Modifier) {
    var devices by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(selectedMicDeviceId()) }

    LaunchedEffect(Unit) {
        devices = listMicDevices()
    }

    val selectedLabel = devices.firstOrNull { it.deviceId == selectedId }?.label ?: DEFAULT_LABEL

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Microphone",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
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
                    onClick = {
                        selectedId = ""
                        setSelectedMicDeviceId("")
                        expanded = false
                    }
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label) },
                        onClick = {
                            selectedId = device.deviceId
                            setSelectedMicDeviceId(device.deviceId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
