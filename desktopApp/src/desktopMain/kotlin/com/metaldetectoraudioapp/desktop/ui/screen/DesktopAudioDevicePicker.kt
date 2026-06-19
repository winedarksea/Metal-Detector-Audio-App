package com.metaldetectoraudioapp.desktop.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.audio.DesktopAudioDevice

@Composable
internal fun DesktopAudioDevicePicker(
    label: String,
    devices: List<DesktopAudioDevice>,
    selectedDevice: DesktopAudioDevice?,
    onDeviceSelected: (DesktopAudioDevice?) -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selectedDevice?.displayName ?: "System default"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                selectedLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("System default") },
                    onClick = {
                        onDeviceSelected(null)
                        expanded = false
                    },
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.displayName) },
                        onClick = {
                            onDeviceSelected(device)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh audio devices")
            }
        }
    }
}
