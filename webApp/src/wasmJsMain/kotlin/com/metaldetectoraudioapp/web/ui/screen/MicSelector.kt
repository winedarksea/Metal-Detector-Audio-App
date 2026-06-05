package com.metaldetectoraudioapp.web.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metaldetectoraudioapp.web.audio.MicDevice
import com.metaldetectoraudioapp.web.audio.listMicDevices
import com.metaldetectoraudioapp.web.audio.selectedMicDeviceId
import com.metaldetectoraudioapp.web.audio.setSelectedMicDeviceId
import androidx.compose.runtime.LaunchedEffect

private const val DEFAULT_LABEL = "System default"

/**
 * Microphone input picker for the web app. Mirrors the Android input-device dropdown.
 * The choice is stored globally (see [setSelectedMicDeviceId]) so it governs both the
 * Detect (inference) and Record capture paths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicSelector(modifier: Modifier = Modifier) {
    var devices by remember { mutableStateOf<List<MicDevice>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(selectedMicDeviceId()) }

    LaunchedEffect(Unit) {
        devices = listMicDevices()
    }

    val selectedLabel = devices.firstOrNull { it.deviceId == selectedId }?.label ?: DEFAULT_LABEL

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Microphone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
