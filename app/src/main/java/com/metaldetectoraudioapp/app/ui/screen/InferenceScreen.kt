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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metaldetectoraudioapp.app.BuildConfig
import com.metaldetectoraudioapp.app.audio.source.AudioDeviceManager
import com.metaldetectoraudioapp.app.inference.InferenceAccelerator
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import com.metaldetectoraudioapp.app.inference.RecentDetection
import com.metaldetectoraudioapp.app.ui.InferenceViewModel
import com.metaldetectoraudioapp.app.ui.theme.DetectionColors
import com.metaldetectoraudioapp.app.ui.theme.Spacing

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
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            if (uiState.isRunning) {
                Button(
                    onClick = viewModel::stop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DetectionColors.Junk)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Stop Detection")
                }
            } else {
                Button(
                    onClick = viewModel::start,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DetectionColors.Target)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
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

        // Live view: RMS level, the tone-quality ribbon, and the current prediction together.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("RMS Level", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { uiState.signalStatus.rmsLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    RibbonCanvas(
                        analyzer = viewModel.ribbon,
                        isRunning = uiState.isRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    PredictionContent(uiState = uiState)
                }
            }
        }

        // Settings: signal status, passthrough, model + device selection.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                        Text(
                            "Speaker Passthrough",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge
                        )
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

                    HardwareAccelerationPreferenceControl(
                        backendPreference = uiState.backendPreference,
                        onHardwareAccelerationEnabledChanged =
                            viewModel::setHardwareAccelerationEnabled,
                    )

                    AudioDevicePicker(
                        label = "Input Device",
                        devices = inputDevices,
                        selectedDevice = selectedInputDevice,
                        onDeviceSelected = viewModel::setInputDevice,
                        onRefresh = viewModel::refreshAudioDevices
                    )

                    UsbInputDiagnosticBanner(
                        inputDevices = inputDevices,
                        outputDevices = outputDevices
                    )

                    if (passthroughEnabled) {
                        AudioDevicePicker(
                            label = "Output Device",
                            devices = outputDevices,
                            selectedDevice = selectedOutputDevice,
                            onDeviceSelected = viewModel::setOutputDevice
                        )
                        Text(
                            "Speaker passthrough is audible while detection is running.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = "Build: v${BuildConfig.VERSION_NAME} • ${BuildConfig.BUILD_DATE_UTC}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "Confidence Threshold: ${"%.2f".format(uiState.threshold)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = uiState.threshold,
                        valueRange = 0.05f..0.95f,
                        onValueChange = viewModel::updateThreshold
                    )
                }
            }
        }

        // Recent detections log (non-AMBIENT hits in last 15 s).
        if (uiState.recentDetections.isNotEmpty()) {
            item {
                RecentDetectionsCard(detections = uiState.recentDetections)
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Performance", style = MaterialTheme.typography.titleMedium)
                    Text("Inference time: ${uiState.lastInferenceMs} ms", style = MaterialTheme.typography.bodyMedium)
                    Text("Average latency: ${"%.1f".format(uiState.averageLatencyMs)} ms", style = MaterialTheme.typography.bodyMedium)
                    Text("Dropped frames: ${uiState.droppedFrames}", style = MaterialTheme.typography.bodyMedium)
                    InferenceScoreDiagnostics(uiState)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            "Model: ${uiState.modelName} v${uiState.modelVersion}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        AcceleratorBadge(accelerator = uiState.activeAccelerator)
                    }
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
    val bannerColor by animateColorAsState(targetValue = DetectionColors.StickyBanner, label = "banner")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bannerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
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
                    "$recentTargetCount hits in last 15 s",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** Simple amplitude waveform, still used by the recording screen. */
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
private fun PredictionContent(uiState: InferenceUiState) {
    val color = when (uiState.topLabel.uppercase()) {
        "TARGET" -> DetectionColors.Target
        "JUNK" -> DetectionColors.Junk
        else -> DetectionColors.Ambient
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Current Prediction", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
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

@Composable
private fun AcceleratorBadge(accelerator: InferenceAccelerator) {
    val badgeColor = when (accelerator) {
        InferenceAccelerator.CPU -> DetectionColors.Ambient
        InferenceAccelerator.GPU -> DetectionColors.AcceleratorGpu
        InferenceAccelerator.NPU -> DetectionColors.Target
        InferenceAccelerator.UNKNOWN -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .background(
                color = badgeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = badgeColor, shape = CircleShape)
        )
        Text(
            accelerator.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Scrollable card showing the most recent significant (non-AMBIENT) detections. */
@Composable
private fun RecentDetectionsCard(detections: List<RecentDetection>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Recent Detections (last 15 s)", style = MaterialTheme.typography.titleMedium)

            // Show newest first (reversed).
            detections.asReversed().forEach { det ->
                val dotColor = if (det.label == "TARGET") DetectionColors.Target else DetectionColors.Junk
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
    onDeviceSelected: (AudioDeviceInfo?) -> Unit,
    onRefresh: (() -> Unit)? = null
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
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh audio sources")
            }
        }
    }
}

/** Banner explaining a USB connection Android exposes only as an output. */
@Composable
internal fun UsbInputDiagnosticBanner(
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>
) {
    if (!AudioDeviceManager.usbOutputWithoutInput(inputDevices, outputDevices)) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            "Android exposes this USB connection as output-only, so the app cannot record it. " +
                "Use a USB-C audio interface or TRRS microphone adapter that Android exposes as an input.",
            modifier = Modifier.padding(Spacing.md),
            style = MaterialTheme.typography.bodySmall,
        )
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
