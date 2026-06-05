package com.metaldetectoraudioapp.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer

/**
 * Streaming tone-quality "ribbon" visual.
 *
 * Two layers, drawn over a dark scope-like background:
 *  - a faint scrolling **haze** (the junk cloud) coloured by pitch zone, and
 *  - up to [RibbonAnalyzer.MAX_PEAKS] bright glowing **ribbons** (clean tone components),
 *    where a clean strong tone reads as a sharp laser line and noisy energy as a wide diffuse halo.
 *
 * Data arrives at ~4 Hz (one inference window) but each window contributes ~31 columns; the
 * renderer drives itself from the frame clock ([withFrameNanos]) and interpolates a smooth scroll
 * head toward [RibbonAnalyzer.writeCounter] so motion is continuous. It reads the analyzer's
 * pre-allocated ring buffers directly — no per-frame allocation beyond the small ribbon [Path]s.
 *
 * IMPORTANT: duplicated verbatim in the Android `app/` module and `shared/` (app/ does not depend
 * on :shared). Keep both copies byte-identical; `RibbonAnalyzerSyncTest` enforces it.
 */
// === RIBBON-SYNC START ===
private class RibbonScroll {
    var headPos: Float = -1f
    var prevNanos: Long = 0L

    fun advance(nowNanos: Long, target: Float) {
        if (headPos < 0f || prevNanos == 0L) {
            headPos = target
            prevNanos = nowNanos
            return
        }
        val dt = (nowNanos - prevNanos).coerceAtLeast(0L) / 1_000_000_000f
        prevNanos = nowNanos
        headPos += dt * RibbonAnalyzer.COLUMNS_PER_SECOND
        if (headPos > target) headPos = target
        // Snap forward after a stall/restart so we never lag off-screen.
        if (target - headPos > RibbonAnalyzer.VISIBLE_COLS) headPos = target - RibbonAnalyzer.VISIBLE_COLS
    }
}

@Composable
fun RibbonCanvas(
    analyzer: RibbonAnalyzer,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val frameNanos = remember { mutableStateOf(0L) }
    val scroll = remember { RibbonScroll() }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (true) {
                withFrameNanos { frameNanos.value = it }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth()) {
        val now = frameNanos.value // subscribe so the draw re-runs every frame while running
        val wc = analyzer.writeCounter
        scroll.advance(now, wc.toFloat())
        drawRibbon(analyzer, scroll.headPos, wc)
    }
}

private val BandFrac = RibbonAnalyzer.BAND_HI_BIN.toFloat() / RibbonAnalyzer.MEL_BINS

/** Perceptual low→high pitch gradient (warm red → orange → amber → light blue → cyan/teal). */
private fun pitchColor(t: Float): Color {
    val x = if (t < 0f) 0f else if (t > 1f) 1f else t
    return when {
        x < 0.20f -> lerp(Color(0xFFFF3366), Color(0xFFFF9933), x / 0.20f)
        x < 0.45f -> lerp(Color(0xFFFF9933), Color(0xFFFFD54F), (x - 0.20f) / 0.25f)
        x < 0.70f -> lerp(Color(0xFFFFD54F), Color(0xFF4DD0E1), (x - 0.45f) / 0.25f)
        else -> lerp(Color(0xFF4DD0E1), Color(0xFF00E676), (x - 0.70f) / 0.30f)
    }
}

private fun hazeColor(t: Float): Color =
    lerp(Color(0xFF27313B), pitchColor(t), 0.42f)

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun clean01(value: Float): Float =
    if (value.isNaN() || value == Float.NEGATIVE_INFINITY) 0f else if (value == Float.POSITIVE_INFINITY || value > 1f) 1f else if (value < 0f) 0f else value

private fun DrawScope.drawRibbon(analyzer: RibbonAnalyzer, headPos: Float, writeCounter: Long) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Dark scope background so additive glow pops.
    drawRect(color = Color(0xFF0A0E14), size = Size(w, h))

    // Newest *written* column is writeCounter-1; never read the not-yet-written (stale) slot.
    val newest = writeCounter - 1L
    if (newest < 0L) return

    val visible = RibbonAnalyzer.VISIBLE_COLS
    val pxPerCol = w / visible
    val oldestValid = writeCounter - RibbonAnalyzer.HISTORY_COLS + 1
    val firstG = maxOf(0L, maxOf(oldestValid, (headPos - visible).toLong()))
    val lastG = minOf(headPos.toLong(), newest)
    if (lastG < firstG) return

    fun xOf(g: Long): Float = w - (headPos - g) * pxPerCol

    // ---- Layer 1: haze (junk cloud) ----
    val hazeRows = RibbonAnalyzer.HAZE_BINS
    val rowH = h / hazeRows
    val cellW = pxPerCol + 1f
    var g = firstG
    while (g <= lastG) {
        val x = xOf(g)
        for (row in 0 until hazeRows) {
            val v = analyzer.haze(g, row)
            if (v > 0.04f) {
                val t = (row + 0.5f) / hazeRows
                val yTop = h * (1f - (row + 1f) / hazeRows)
                drawRect(
                    color = hazeColor(t),
                    topLeft = Offset(x, yTop),
                    size = Size(cellW, rowH + 1f),
                    alpha = clean01(v * 0.32f),
                    blendMode = BlendMode.SrcOver,
                )
            }
        }
        g++
    }

    // ---- Layer 2: ribbons (tracked tone peaks) ----
    for (k in 0 until RibbonAnalyzer.MAX_PEAKS) {
        // Halo pass then core pass, so cores sit on top of every halo.
        drawRibbonSegments(analyzer, k, firstG, lastG, headPos, pxPerCol, halo = true)
        drawRibbonSegments(analyzer, k, firstG, lastG, headPos, pxPerCol, halo = false)
    }
}

private fun DrawScope.drawRibbonSegments(
    analyzer: RibbonAnalyzer,
    k: Int,
    firstG: Long,
    lastG: Long,
    headPos: Float,
    pxPerCol: Float,
    halo: Boolean,
) {
    val h = size.height
    val w = size.width

    var g = firstG
    while (g < lastG) {
        val aBin = analyzer.peakBinFrac(g, k)
        val bBin = analyzer.peakBinFrac(g + 1L, k)
        if (aBin < 0f || bBin < 0f) {
            g++
            continue
        }

        val aBand = (aBin / BandFrac).coerceIn(0f, 1f)
        val bBand = (bBin / BandFrac).coerceIn(0f, 1f)
        // A large jump is a new tone component, not a ribbon segment to connect.
        if (kotlin.math.abs(aBand - bBand) > MAX_SEGMENT_BAND_JUMP) {
            g++
            continue
        }

        val quality = ((analyzer.peakQuality(g, k) + analyzer.peakQuality(g + 1L, k)) * 0.5f)
            .let(::clean01)
        val snr = ((analyzer.peakSnr(g, k) + analyzer.peakSnr(g + 1L, k)) * 0.5f)
            .let(::clean01)
        val messiness = ((analyzer.peakMessiness(g, k) + analyzer.peakMessiness(g + 1L, k)) * 0.5f)
            .let(::clean01)
        val band = (aBand + bBand) * 0.5f
        val x1 = w - (headPos - g) * pxPerCol
        val x2 = w - (headPos - (g + 1L)) * pxPerCol
        val y1 = h * (1f - aBand)
        val y2 = h * (1f - bBand)

        val alpha: Float
        val widthDp: Float
        val blendMode: BlendMode
        if (halo) {
            alpha = lerpF(0.05f, 0.18f, quality) * lerpF(1.05f, 0.62f, messiness)
            widthDp = lerpF(22f, 7f, quality) + messiness * 7f
            blendMode = BlendMode.Plus
        } else {
            alpha = (quality * lerpF(1.0f, 0.68f, messiness) + snr * quality * 0.18f)
            widthDp = lerpF(0.6f, 2.8f, quality)
            blendMode = BlendMode.SrcOver
        }
        if (alpha > 0.001f && widthDp > 0.01f) {
            drawLine(
                color = pitchColor(band),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = widthDp.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = clean01(alpha),
                blendMode = blendMode,
            )
        }
        g++
    }
}

private const val MAX_SEGMENT_BAND_JUMP = 0.18f
// === RIBBON-SYNC END ===
