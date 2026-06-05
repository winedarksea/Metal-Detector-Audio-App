package com.metaldetectoraudioapp.app.audio.ribbon

import kotlin.concurrent.Volatile

/**
 * Streaming tone-quality analyzer for the detection-screen "ribbon" visual.
 *
 * It turns the log-mel spectrogram (already computed for inference) into a scrolling
 * stream of columns. Each column carries:
 *   - up to [MAX_PEAKS] tracked spectral peaks (sub-bin position, tone-quality, SNR), and
 *   - a coarse [HAZE_BINS]-row "haze" energy profile (the junk cloud).
 *
 * A clean, strong, coherent tone shows up as a bright sharp ribbon; messy/noisy energy
 * shows up as a dim fuzzy haze. Mapping is brand-agnostic: no metal/tone calibration.
 *
 * Real-time constraints:
 *   - No per-frame heap allocation: fixed scratch arrays + pre-allocated ring buffers.
 *   - Single-producer (audio/inference thread via [process]) / single-consumer (UI render
 *     thread via the `peak*`/`haze` accessors). [writeCounter] is volatile and bumped only
 *     after a column's data is written, giving the reader a happens-before barrier. A torn
 *     read of the single newest column is visually irrelevant.
 *
 * IMPORTANT: This file is duplicated verbatim in the Android `app/` module and the `shared/`
 * module because `app/` does not depend on `:shared`. Keep both copies byte-identical;
 * `RibbonAnalyzerSyncTest` enforces it. Edit logic here, then mirror.
 */
// === RIBBON-SYNC START ===
class RibbonAnalyzer(
    private val numMelBins: Int = 40,
) {
    /** Pre-allocated ring buffer of peaks: [HISTORY_COLS][MAX_PEAKS][PEAK_ATTRS]. */
    private val peaks = FloatArray(HISTORY_COLS * MAX_PEAKS * PEAK_ATTRS)

    /** Pre-allocated ring buffer of haze energy: [HISTORY_COLS][HAZE_BINS], values 0..1. */
    private val hazeBuf = FloatArray(HISTORY_COLS * HAZE_BINS)

    // Per-column scratch (reused, never escapes).
    private val norm = FloatArray(numMelBins)
    private val smooth = FloatArray(numMelBins)
    private val candBin = FloatArray(numMelBins)
    private val candVal = FloatArray(numMelBins)
    private val prevPeakBin = FloatArray(MAX_PEAKS) { -1f }
    private val chosenIdx = IntArray(MAX_PEAKS)

    /**
     * Total number of columns ever written. Volatile: the writer publishes a column by
     * incrementing this *after* filling its slot; the reader snapshots it first.
     */
    @Volatile
    var writeCounter: Long = 0L
        private set

    /** Clears all history. Call when (re)starting a session to avoid stale visuals. */
    fun reset() {
        for (i in prevPeakBin.indices) prevPeakBin[i] = -1f
        peaks.fill(0f)
        hazeBuf.fill(0f)
        writeCounter = 0L
    }

    /**
     * Consumes a log-mel spectrogram window ([timeFrames][melBins]) and appends only the
     * newest [NEW_COLS] columns — the non-overlapping region versus the previous window —
     * so consecutive 50%-overlapping inference windows produce one continuous timeline.
     */
    fun process(logMel: Array<FloatArray>) {
        val frames = logMel.size
        if (frames == 0) return
        val start = if (frames > NEW_COLS) frames - NEW_COLS else 0
        for (f in start until frames) {
            processColumn(logMel[f])
        }
    }

    private fun processColumn(col: FloatArray) {
        val hi = if (BAND_HI_BIN < col.size) BAND_HI_BIN else col.size

        // 1. Spectral prominence: how far each bin rises above its *local frequency* background.
        // A narrowband tone stands out; broadband noise stays near its neighbours (prominence ~0).
        // This answers "is there a distinct tone component above its local background?".
        for (b in BAND_LO_BIN until hi) {
            var sum = 0f
            var cnt = 0
            var w = b - BG_WIN
            while (w <= b + BG_WIN) {
                if (w in BAND_LO_BIN until hi) { sum += col[w]; cnt++ }
                w++
            }
            val bg = if (cnt > 0) sum / cnt else 0f
            val v = col[b] - bg
            norm[b] = if (v > 0f) v else 0f
        }

        // 2. Light 3-tap smoothing across frequency.
        smooth[BAND_LO_BIN] = norm[BAND_LO_BIN]
        smooth[hi - 1] = norm[hi - 1]
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            smooth[b] = 0.25f * norm[b - 1] + 0.5f * norm[b] + 0.25f * norm[b + 1]
        }

        // 3. Band mean (for contrast).
        var sum = 0f
        for (b in BAND_LO_BIN until hi) sum += smooth[b]
        val bandWidth = hi - BAND_LO_BIN
        val mean = if (bandWidth > 0) sum / bandWidth else 0f

        // 4. Collect local maxima above a floor.
        var candCount = 0
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            val c = smooth[b]
            if (c >= MIN_PEAK && c > smooth[b - 1] && c >= smooth[b + 1]) {
                candBin[candCount] = b.toFloat()
                candVal[candCount] = c
                candCount++
            }
        }

        // 5. Select the top MAX_PEAKS candidates by value.
        val nChosen = selectTopK(candCount)

        val base = ((writeCounter % HISTORY_COLS).toInt()) * MAX_PEAKS * PEAK_ATTRS
        for (k in 0 until MAX_PEAKS) {
            val o = base + k * PEAK_ATTRS
            if (k < nChosen) {
                val b = chosenIdx[k]
                val binFloat = parabolicBin(b, hi)
                val peakVal = smooth[b]

                // Local sharpness: peak height above a small neighbourhood (excluding peak).
                var localSum = 0f
                var localCount = 0
                var w = b - SHARP_W
                while (w <= b + SHARP_W) {
                    if (w in BAND_LO_BIN until hi && w != b) {
                        localSum += smooth[w]; localCount++
                    }
                    w++
                }
                val localAvg = if (localCount > 0) localSum / localCount else 0f

                val snrNorm = clamp01(peakVal / SNR_SCALE)
                val contrast = clamp01((peakVal - mean) / CONTRAST_SCALE)
                val sharp = clamp01((peakVal - localAvg) / SHARP_SCALE)

                // Temporal continuity vs. the previous column's peaks.
                var continuity = 0f
                for (p in prevPeakBin) {
                    if (p >= 0f) {
                        val d = if (binFloat > p) binFloat - p else p - binFloat
                        val cc = clamp01(1f - d / CONTINUITY_BINS)
                        if (cc > continuity) continuity = cc
                    }
                }

                var quality = W_CONTRAST * contrast + W_SHARP * sharp + W_CONTINUITY * continuity
                // Weak SNR can never look like a clean tone.
                quality *= clamp01(snrNorm * SNR_GATE)
                quality = clamp01(quality)

                peaks[o] = binFloat / numMelBins
                peaks[o + 1] = quality
                peaks[o + 2] = snrNorm
                prevPeakBin[k] = binFloat
            } else {
                peaks[o] = -1f
                peaks[o + 1] = 0f
                peaks[o + 2] = 0f
                prevPeakBin[k] = -1f
            }
        }

        // 6. Haze (the junk cloud): absolute log-energy per coarse row, mapped to brightness.
        // Independent of prominence so broadband/messy energy still shows as a visible cloud,
        // while quiet bins stay dark.
        val hazeBase = ((writeCounter % HISTORY_COLS).toInt()) * HAZE_BINS
        for (h in 0 until HAZE_BINS) {
            val lo = BAND_LO_BIN + (bandWidth * h) / HAZE_BINS
            val hh = BAND_LO_BIN + (bandWidth * (h + 1)) / HAZE_BINS
            var hsum = 0f
            var hcount = 0
            var i = lo
            while (i < hh && i < hi) { hsum += col[i]; hcount++; i++ }
            val avg = if (hcount > 0) hsum / hcount else HAZE_REF_LOW
            hazeBuf[hazeBase + h] = clamp01((avg - HAZE_REF_LOW) / (HAZE_REF_HIGH - HAZE_REF_LOW))
        }

        // Publish the column.
        writeCounter = writeCounter + 1L
    }

    /** Partial selection of the [MAX_PEAKS] largest [candVal] entries into [chosenIdx]. */
    private fun selectTopK(candCount: Int): Int {
        val n = if (candCount < MAX_PEAKS) candCount else MAX_PEAKS
        // Track which candidates are already taken via a sentinel on a copy-free pass.
        var taken = 0L // bitset; candCount <= numMelBins (<64 in practice)
        for (k in 0 until n) {
            var bestI = -1
            var bestV = Float.NEGATIVE_INFINITY
            for (c in 0 until candCount) {
                if ((taken shr c) and 1L == 0L && candVal[c] > bestV) {
                    bestV = candVal[c]; bestI = c
                }
            }
            if (bestI < 0) return k
            taken = taken or (1L shl bestI)
            chosenIdx[k] = candBin[bestI].toInt()
        }
        return n
    }

    /** Parabolic (quadratic) interpolation of the true peak position for a smooth ribbon. */
    private fun parabolicBin(b: Int, hi: Int): Float {
        if (b <= BAND_LO_BIN || b >= hi - 1) return b.toFloat()
        val l = smooth[b - 1]
        val c = smooth[b]
        val r = smooth[b + 1]
        val denom = l - 2f * c + r
        if (denom == 0f) return b.toFloat()
        val delta = 0.5f * (l - r) / denom
        val d = if (delta > 0.5f) 0.5f else if (delta < -0.5f) -0.5f else delta
        return b + d
    }

    // ---- Reader accessors (UI thread). globalIndex is a column's writeCounter value. ----

    /** Sub-band peak position in 0..1 (relative pitch), or -1 if no peak. */
    fun peakBinFrac(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k)]

    fun peakQuality(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 1]

    fun peakSnr(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 2]

    /** Haze energy 0..1 for row [h] (0 = low pitch) of the given column. */
    fun haze(globalIndex: Long, h: Int): Float =
        hazeBuf[((globalIndex % HISTORY_COLS).toInt()) * HAZE_BINS + h]

    private fun slotPeak(globalIndex: Long, k: Int): Int =
        ((globalIndex % HISTORY_COLS).toInt()) * MAX_PEAKS * PEAK_ATTRS + k * PEAK_ATTRS

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    companion object {
        /** Ring-buffer depth in columns (~4 s of history at [COLUMNS_PER_SECOND]). */
        const val HISTORY_COLS = 512

        /** Columns mapped across the visible width. */
        const val VISIBLE_COLS = 240

        /** Tracked spectral peaks per column (the ribbons). */
        const val MAX_PEAKS = 3

        /** Floats stored per peak: [binFrac, quality, snr]. */
        const val PEAK_ATTRS = 3

        /** Coarse haze rows. */
        const val HAZE_BINS = 20

        /** Mel-filterbank bin count (matches the feature extractor). */
        const val MEL_BINS = 40

        /**
         * Effective new columns per inference window = hop / frameStep = 4000 / 128 ≈ 31,
         * and the corresponding scroll rate the renderer uses to interpolate between windows:
         * NEW_COLS / (hop / sampleRate) = 31 / 0.25 s = 124 columns/s.
         */
        const val NEW_COLS = 31
        const val COLUMNS_PER_SECOND = 124f

        /**
         * Populated mel band. Audio is band-limited to ~120–3500 Hz before framing, while the
         * mel filterbank spans 80–7600 Hz over 40 bins, so only the lower ~28 bins carry signal.
         * Restricting the visual to this band keeps the pitch/hue axis meaningful.
         */
        const val BAND_LO_BIN = 0
        const val BAND_HI_BIN = 28

        // Half-width (in mel bins) of the local-frequency background used for spectral prominence.
        private const val BG_WIN = 6

        // Haze brightness mapping: log-mel energy at/below LOW reads dark, at/above HIGH reads full.
        private const val HAZE_REF_LOW = -9.0f
        private const val HAZE_REF_HIGH = 1.0f

        // Peak detection / quality shaping (tunable; tests assert ordering, not exact values).
        private const val MIN_PEAK = 0.15f
        private const val SNR_SCALE = 4.0f
        private const val CONTRAST_SCALE = 3.0f
        private const val SHARP_SCALE = 2.5f
        private const val SHARP_W = 3
        private const val CONTINUITY_BINS = 2.5f
        private const val SNR_GATE = 1.6f

        private const val W_CONTRAST = 0.45f
        private const val W_SHARP = 0.25f
        private const val W_CONTINUITY = 0.30f
    }
}
// === RIBBON-SYNC END ===
