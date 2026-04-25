package com.metaldetectoraudioapp.app.ui.screen

import android.media.AudioDeviceInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.audio.source.AudioDeviceManager
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import com.metaldetectoraudioapp.app.inference.RecentDetection
import com.metaldetectoraudioapp.app.ui.InferenceViewModel

private val TargetGreen = Color(0xFF2E7D32)
private val JunkRed = Color(0xFFC62828)
private val AmbientGray = Color(0xFF616161)
private val StickyBannerGreen = Color(0xFF1B5E20)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceScreen(
    viewModel: InferenceViewModel,
    contentPadding: PaddingValues
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val passthroughEnabled by viewModel.passthroughEnabled.collectAsStateWithLifecycle()
    val availableModelOptions by viewModel.availableModelOptions.collectAsStateWithLifecycle()
    val selectedModelOptionId by viewModel.selectedModelOptionId.collectAsStateWithLifecycle()
    val inputDevices by viewModel.deviceManager.inputDevices.collectAsStateWithLifecycle()
    val outputDevices by viewModel.deviceManager.outputDevices.collectAsStateWithLifecycle()
    val selectedInputDevice by viewModel.selectedInputDevice.collectAsStateWithLifecycle()
    val selectedOutputDevice by viewModel.selectedOutputDevice.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (uiState.isRunning) {
                Button(
                    onClick = viewModel::stop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = JunkRed)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Detection")
                }
            } else {
                Button(
                    onClick = viewModel::start,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TargetGreen)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Detecting")
                }
            }
        }

        // Sticky TARGET banner — persists for 5 s after the last TARGET hit.
        if (uiState.stickyTargetActive) {
            item {
                StickyTargetBanner(
                    confidence = uiState.stickyTargetConfidence,
                    recentTargetCount = uiState.recentTargetCount
                )
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection Check", style = MaterialTheme.typography.titleMedium)
                    Text("RMS Level")
                    LinearProgressIndicator(
                        progress = { uiState.signalStatus.rmsLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Waveform")
                    WaveformCanvas(waveformPoints = uiState.waveformPreviewPoints)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.signalStatus.signalPresent,
                            onClick = {},
                            label = { Text(if (uiState.signalStatus.signalPresent) "Signal Present" else "No Signal") }
                        )
                        FilterChip(
                            selected = uiState.signalStatus.clippingDetected,
                            onClick = {},
                            label = { Text(if (uiState.signalStatus.clippingDetected) "Clipping" else "No Clip") }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speaker Passthrough", modifier = Modifier.weight(1f))
                        Switch(
                            checked = passthroughEnabled,
                            onCheckedChange = { viewModel.setPassthroughEnabled(it) }
                        )
                    }

                    ModelOptionPicker(
                        label = "Classifier Model",
                        options = availableModelOptions,
                        selectedOptionId = selectedModelOptionId,
                        onOptionSelected = viewModel::selectModelOption
                    )

                    AudioDevicePicker(
                        label = "Input Device",
                        devices = inputDevices,
                        selectedDevice = selectedInputDevice,
                        onDeviceSelected = viewModel::setInputDevice
                    )

                    if (passthroughEnabled) {
                        AudioDevicePicker(
                            label = "Output Device",
                            devices = outputDevices,
                            selectedDevice = selectedOutputDevice,
                            onDeviceSelected = viewModel::setOutputDevice
                        )
                    }
                }
            }
        }

        item {
            PredictionCard(uiState = uiState)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confidence Threshold: ${"%.2f".format(uiState.threshold)}")
                    Slider(
                        value = uiState.threshold,
                        valueRange = 0.05f..0.95f,
                        onValueChange = viewModel::updateThreshold
                    )
                }
            }
        }

        // Recent detections log (non-AMBIENT hits in last 30 s).
        if (uiState.recentDetections.isNotEmpty()) {
            item {
                RecentDetectionsCard(detections = uiState.recentDetections)
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Performance")
                    Text("Inference time: ${uiState.lastInferenceMs} ms")
                    Text("Average latency: ${"%.1f".format(uiState.averageLatencyMs)} ms")
                    Text("Dropped frames: ${uiState.droppedFrames}")
                    Text("Model: ${uiState.modelName} v${uiState.modelVersion}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

/** Prominent green banner that stays visible for several seconds after a TARGET detection. */
@Composable
private fun StickyTargetBanner(confidence: Float, recentTargetCount: Int) {
    val bannerColor by animateColorAsState(targetValue = StickyBannerGreen, label = "banner")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bannerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "TARGET DETECTED",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Confidence: ${"%.0f".format(confidence * 100)}%",
                color = Color.White.copy(alpha = 0.9f)
            )
            if (recentTargetCount > 1) {
                Text(
                    "$recentTargetCount hits in last 30 s",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun WaveformCanvas(waveformPoints: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
    ) {
        if (waveformPoints.isEmpty()) return@Canvas
        val midY = size.height / 2f
        val path = Path()
        waveformPoints.forEachIndexed { index, value ->
            val x = index.toFloat() / (waveformPoints.lastIndex.coerceAtLeast(1)) * size.width
            val y = midY - value.coerceIn(-1f, 1f) * midY
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor)
    }
}

@Composable
private fun PredictionCard(uiState: InferenceUiState) {
    val color = when (uiState.topLabel.uppercase()) {
        "TARGET" -> TargetGreen
        "JUNK" -> JunkRed
        else -> AmbientGray
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Current Prediction", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    drawCircle(color = color)
                }
                Text(uiState.topLabel)
                Text("${"%.2f".format(uiState.confidence)}")
            }
            LinearProgressIndicator(
                progress = { uiState.confidence.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.15f))
            )
        }
    }
}

/** Scrollable card showing the most recent significant (non-AMBIENT) detections. */
@Composable
private fun RecentDetectionsCard(detections: List<RecentDetection>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent Detections (last 30 s)", style = MaterialTheme.typography.titleMedium)

            // Show newest first (reversed).
            detections.asReversed().forEach { det ->
                val dotColor = if (det.label == "TARGET") TargetGreen else JunkRed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = dotColor)
                    }
                    Text(
                        det.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${"%.0f".format(det.confidence * 100)}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        formatRelativeTime(det.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun AudioDevicePicker(
    label: String,
    devices: List<AudioDeviceInfo>,
    selectedDevice: AudioDeviceInfo?,
    onDeviceSelected: (AudioDeviceInfo?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = selectedDevice?.let { AudioDeviceManager.deviceDisplayName(it) } ?: "Default"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                displayName,
                modifier = Modifier
                    .clickable { expanded = true }
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Default") },
                    onClick = {
                        onDeviceSelected(null)
                        expanded = false
                    }
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(AudioDeviceManager.deviceDisplayName(device)) },
                        onClick = {
                            onDeviceSelected(device)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOptionPicker(
    label: String,
    options: List<InferenceModelOption>,
    selectedOptionId: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedOptionId }?.label
        ?: options.firstOrNull()?.label
        ?: "None"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                selectedLabel,
                modifier = Modifier
                    .clickable { expanded = true }
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onOptionSelected(option.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val elapsedSeconds = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        elapsedSeconds < 2 -> "just now"
        elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
        else -> "${elapsedSeconds / 60}m ago"
    }
}
