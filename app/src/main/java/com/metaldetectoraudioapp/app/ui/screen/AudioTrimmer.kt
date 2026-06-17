package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * "Mini-Audacity" trim control: a static amplitude waveform with two draggable handles. The audio
 * outside `[trimStartMs, trimEndMs]` is shaded; dragging a handle reports the new bounds via
 * [onTrimChange]. Pure Compose, so it is shared by web + desktop and duplicated byte-identically
 * into the Android `app/` module (which does not depend on :shared) — `AudioTrimSyncTest` enforces it.
 *
 * Touch anywhere grabs whichever handle is nearer, which keeps the gesture forgiving on small screens.
 */
// === AUDIO-TRIMMER-SYNC START ===
private enum class TrimHandle { NONE, START, END }

private const val MIN_SELECTION_MS = 50L

@Composable
fun AudioTrimmer(
    envelope: List<Float>,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimChange: (startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val shadeColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
    val handleColor = MaterialTheme.colorScheme.primary

    // Read inside the gesture lambdas (which do not recompose) without capturing stale values.
    val currentStart by rememberUpdatedState(trimStartMs)
    val currentEnd by rememberUpdatedState(trimEndMs)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(durationMs) {
                val widthPx = size.width.toFloat()
                if (widthPx <= 0f || durationMs <= 0L) return@pointerInput
                var dragging = TrimHandle.NONE
                detectDragGestures(
                    onDragStart = { offset ->
                        val xStart = currentStart.toFloat() / durationMs * widthPx
                        val xEnd = currentEnd.toFloat() / durationMs * widthPx
                        dragging =
                            if (abs(offset.x - xStart) <= abs(offset.x - xEnd)) TrimHandle.START
                            else TrimHandle.END
                    },
                    onDragEnd = { dragging = TrimHandle.NONE },
                    onDragCancel = { dragging = TrimHandle.NONE },
                    onDrag = { change, _ ->
                        change.consume()
                        val ms = ((change.position.x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
                        when (dragging) {
                            TrimHandle.START ->
                                onTrimChange(
                                    ms.coerceIn(0L, (currentEnd - MIN_SELECTION_MS).coerceAtLeast(0L)),
                                    currentEnd,
                                )
                            TrimHandle.END ->
                                onTrimChange(
                                    currentStart,
                                    ms.coerceIn(
                                        (currentStart + MIN_SELECTION_MS).coerceAtMost(durationMs),
                                        durationMs,
                                    ),
                                )
                            TrimHandle.NONE -> {}
                        }
                    },
                )
            }
    ) {
        val w = size.width
        val h = size.height
        drawRect(color = trackColor, size = Size(w, h))
        if (durationMs <= 0L || w <= 0f) return@Canvas

        val midY = h / 2f
        if (envelope.isNotEmpty()) {
            val barW = w / envelope.size
            envelope.forEachIndexed { index, raw ->
                val barH = raw.coerceIn(0f, 1f) * h
                drawRect(
                    color = waveColor,
                    topLeft = Offset(index * barW, midY - barH / 2f),
                    size = Size((barW - 0.5f).coerceAtLeast(1f), barH),
                )
            }
        }

        val xStart = trimStartMs.toFloat() / durationMs * w
        val xEnd = trimEndMs.toFloat() / durationMs * w
        drawRect(color = shadeColor, topLeft = Offset(0f, 0f), size = Size(xStart.coerceAtLeast(0f), h))
        drawRect(
            color = shadeColor,
            topLeft = Offset(xEnd, 0f),
            size = Size((w - xEnd).coerceAtLeast(0f), h),
        )

        val handleW = 3.dp.toPx()
        drawRect(
            color = handleColor,
            topLeft = Offset((xStart - handleW / 2f).coerceIn(0f, w - handleW), 0f),
            size = Size(handleW, h),
        )
        drawRect(
            color = handleColor,
            topLeft = Offset((xEnd - handleW / 2f).coerceIn(0f, w - handleW), 0f),
            size = Size(handleW, h),
        )
    }
}
// === AUDIO-TRIMMER-SYNC END ===
