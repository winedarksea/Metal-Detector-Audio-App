package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import com.metaldetectoraudioapp.app.inference.RecentDetection
import com.metaldetectoraudioapp.app.ui.theme.DetectionColors

private fun Float.fmt2d(): String {
    val i = (this * 100).toLong()
    return "${i / 100}.${(i % 100).let { if (it < 0) -it else it }.toString().padStart(2, '0')}"
}

private fun Float.fmt1d(): String {
    val i = (this * 10).toLong()
    return "${i / 10}.${(i % 10).let { if (it < 0) -it else it }}"
}

private fun Float.fmt0d(): String = toLong().toString()

/**
 * Shared inference screen accepting state + callback lambdas.
 * Works identically on Android (Compose) and Desktop (Compose Multiplatform).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedInferenceScreen(
    uiState: InferenceUiState,
    ribbon: RibbonAnalyzer,
    passthroughEnabled: Boolean,
    availableModelOptions: List<InferenceModelOption>,
    selectedModelOptionId: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onThresholdChange: (Float) -> Unit,
    onPassthroughChange: (Boolean) -> Unit,
    onModelOptionSelected: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    modifier: Modifier = Modifier,
    micSelector: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onStart, enabled = !uiState.isRunning) {
                    Text("Start")
                }
                Button(onClick = onStop, enabled = uiState.isRunning) {
                    Text("Stop")
                }
            }
        }

        if (uiState.stickyTargetActive) {
            item {
                StickyTargetBanner(
                    confidence = uiState.stickyTargetConfidence,
                    recentTargetCount = uiState.recentTargetCount,
                )
            }
        }

        // Live view: RMS level, the tone-quality ribbon, and the current prediction together.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("RMS Level")
                    LinearProgressIndicator(
                        progress = { uiState.signalStatus.rmsLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    RibbonCanvas(
                        analyzer = ribbon,
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

        // Settings: signal status, passthrough, model selection.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    micSelector()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                            onCheckedChange = onPassthroughChange,
                        )
                    }

                    ModelOptionPicker(
                        label = "Classifier Model",
                        options = availableModelOptions,
                        selectedOptionId = selectedModelOptionId,
                        onOptionSelected = onModelOptionSelected,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Confidence Threshold: ${uiState.threshold.fmt2d()}")
                    Slider(
                        value = uiState.threshold,
                        valueRange = 0.05f..0.95f,
                        onValueChange = onThresholdChange,
                    )
                }
            }
        }

        if (uiState.recentDetections.isNotEmpty()) {
            item { RecentDetectionsCard(detections = uiState.recentDetections) }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Performance")
                    Text("Inference time: ${uiState.lastInferenceMs} ms")
                    Text("Average latency: ${uiState.averageLatencyMs.fmt1d()} ms")
                    Text("Dropped frames: ${uiState.droppedFrames}")
                    Text("Model: ${uiState.modelName} v${uiState.modelVersion}")
                }
            }
        }
    }
}

@Composable
private fun StickyTargetBanner(confidence: Float, recentTargetCount: Int) {
    val bannerColor by animateColorAsState(targetValue = DetectionColors.StickyBanner, label = "banner")

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
            Text("TARGET DETECTED", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Confidence: ${(confidence * 100).fmt0d()}%", color = Color.White.copy(alpha = 0.9f))
            if (recentTargetCount > 1) {
                Text(
                    "$recentTargetCount hits in last 30 s",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PredictionContent(uiState: InferenceUiState) {
    val color = when (uiState.topLabel.uppercase()) {
        "TARGET" -> DetectionColors.Target
        "JUNK" -> DetectionColors.Junk
        else -> DetectionColors.Ambient
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Current Prediction", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(modifier = Modifier.size(14.dp)) { drawCircle(color = color) }
            Text(uiState.topLabel)
            Text(uiState.confidence.fmt2d())
        }
        LinearProgressIndicator(
            progress = { uiState.confidence.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.15f))
        )
    }
}

@Composable
private fun RecentDetectionsCard(detections: List<RecentDetection>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Recent Detections (last 30 s)", style = MaterialTheme.typography.titleMedium)
            detections.asReversed().forEach { det ->
                val dotColor = if (det.label == "TARGET") DetectionColors.Target else DetectionColors.Junk
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = dotColor) }
                    Text(det.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${(det.confidence * 100).fmt0d()}%", style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatRelativeTime(det.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onOptionSelected: (String) -> Unit,
) {
    val expandedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val expanded = expandedState.value
    val selectedLabel = options.firstOrNull { it.id == selectedOptionId }?.label
        ?: options.firstOrNull()?.label
        ?: "None"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                selectedLabel,
                modifier = Modifier
                    .clickable { expandedState.value = true }
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expandedState.value = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onOptionSelected(option.id)
                            expandedState.value = false
                        }
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val elapsedSeconds = ((com.metaldetectoraudioapp.app.util.Clocks.epochMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        elapsedSeconds < 2 -> "just now"
        elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
        else -> "${elapsedSeconds / 60}m ago"
    }
}
