package com.metaldetectoraudioapp.app.audio.ribbon

import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.ln

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
    private val residual = FloatArray(numMelBins)
    private val prominence = FloatArray(numMelBins)
    private val smooth = FloatArray(numMelBins)
    private val candBin = FloatArray(numMelBins)
    private val candVal = FloatArray(numMelBins)
    private val candTaken = BooleanArray(numMelBins)
    private val chosenIdx = IntArray(MAX_PEAKS)
    private val laneMatched = BooleanArray(MAX_PEAKS)
    private val noiseFloor = FloatArray(numMelBins)
    private val previousResidual = FloatArray(numMelBins)
    private val laneBin = FloatArray(MAX_PEAKS) { -1f }
    private val laneStability = FloatArray(MAX_PEAKS)
    private val laneAge = IntArray(MAX_PEAKS)
    private var noiseFloorInitialized = false

    /**
     * Total number of columns ever written. Volatile: the writer publishes a column by
     * incrementing this *after* filling its slot; the reader snapshots it first.
     */
    @Volatile
    var writeCounter: Long = 0L
        private set

    /** Clears all history. Call when (re)starting a session to avoid stale visuals. */
    fun reset() {
        for (i in laneBin.indices) {
            laneBin[i] = -1f
            laneStability[i] = 0f
            laneAge[i] = 0
        }
        residual.fill(0f)
        prominence.fill(0f)
        smooth.fill(0f)
        previousResidual.fill(0f)
        noiseFloor.fill(0f)
        noiseFloorInitialized = false
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
        for (f in 0 until start) {
            updateNoiseFloorOnly(logMel[f])
        }
        for (f in start until frames) {
            processColumn(logMel[f])
        }
    }

    private fun updateNoiseFloorOnly(col: FloatArray) {
        val hi = if (BAND_HI_BIN < col.size) BAND_HI_BIN else col.size
        ensureNoiseFloor(col, hi)
        for (b in BAND_LO_BIN until hi) {
            updateNoiseFloorBin(b, col[b])
        }
    }

    private fun processColumn(col: FloatArray) {
        val hi = if (BAND_HI_BIN < col.size) BAND_HI_BIN else col.size

        ensureNoiseFloor(col, hi)

        // 1. Adaptive residual: energy above each mel bin's rolling floor. This keeps loud
        // detector responses from turning the whole haze layer into a saturated blob.
        var residualSum = 0f
        var fluxSum = 0f
        for (b in BAND_LO_BIN until hi) {
            val floor = updateNoiseFloorBin(b, col[b])
            val v = col[b] - floor
            val r = if (v > 0f) v else 0f
            val diff = r - previousResidual[b]
            fluxSum += if (diff >= 0f) diff else -diff
            residual[b] = r
            residualSum += r
        }

        val bandWidth = hi - BAND_LO_BIN
        val residualMean = if (bandWidth > 0) residualSum / bandWidth else 0f
        val flux = clamp01((if (bandWidth > 0) fluxSum / bandWidth else 0f) / FLUX_SCALE)
        val flatness = spectralFlatness(hi, residualMean)

        // 2. Spectral prominence: a narrowband tone rises above local residual background.
        for (b in BAND_LO_BIN until hi) {
            var sum = 0f
            var cnt = 0
            var w = b - BG_WIN
            while (w <= b + BG_WIN) {
                if (w in BAND_LO_BIN until hi) { sum += residual[w]; cnt++ }
                w++
            }
            val bg = if (cnt > 0) sum / cnt else 0f
            val v = residual[b] - bg * LOCAL_BACKGROUND_WEIGHT
            prominence[b] = if (v > 0f) v else 0f
        }

        // 3. Light 3-tap smoothing across frequency.
        smooth[BAND_LO_BIN] = prominence[BAND_LO_BIN]
        smooth[hi - 1] = prominence[hi - 1]
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            smooth[b] = 0.25f * prominence[b - 1] + 0.5f * prominence[b] + 0.25f * prominence[b + 1]
        }

        // 4. Band mean (for contrast).
        var sum = 0f
        for (b in BAND_LO_BIN until hi) sum += smooth[b]
        val mean = if (bandWidth > 0) sum / bandWidth else 0f

        // 5. Collect local maxima above a floor.
        var candCount = 0
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            val c = smooth[b]
            if (c >= MIN_PEAK && c > smooth[b - 1] && c >= smooth[b + 1]) {
                candBin[candCount] = b.toFloat()
                candVal[candCount] = c
                candCount++
            }
        }

        // 6. Assign candidates to stable lanes, then fill empty lanes with strongest leftovers.
        selectLaneCandidates(candCount)

        val base = ((writeCounter % HISTORY_COLS).toInt()) * MAX_PEAKS * PEAK_ATTRS
        for (k in 0 until MAX_PEAKS) laneMatched[k] = false
        for (k in 0 until MAX_PEAKS) {
            val o = base + k * PEAK_ATTRS
            val candidateIndex = chosenIdx[k]
            if (candidateIndex >= 0) {
                val b = candBin[candidateIndex].toInt()
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

                val previousLaneBin = laneBin[k]
                val binDelta = if (previousLaneBin >= 0f) {
                    val d = binFloat - previousLaneBin
                    if (d >= 0f) d else -d
                } else {
                    CONTINUITY_BINS
                }
                val continuity = clamp01(1f - binDelta / CONTINUITY_BINS)
                val jitter = clamp01(binDelta / JITTER_BINS)
                val pitch = clamp01(binFloat / BAND_HI_BIN)
                val pitchLift = LOW_PITCH_QUALITY + (HIGH_PITCH_QUALITY - LOW_PITCH_QUALITY) * pitch
                val stability = clamp01(laneStability[k] * STABILITY_DECAY + continuity * (1f - STABILITY_DECAY))

                var quality = W_CONTRAST * contrast +
                    W_SHARP * sharp +
                    W_STABILITY * stability +
                    W_SNR * snrNorm
                // Weak SNR can never look like a clean tone.
                quality *= clamp01(snrNorm * SNR_GATE)
                quality *= pitchLift
                quality *= 1f - FLATNESS_PENALTY * flatness
                quality *= 1f - FLUX_PENALTY * flux
                quality *= 1f - JITTER_PENALTY * jitter
                quality = clamp01(quality)
                val messiness = clamp01(0.45f * flatness + 0.30f * flux + 0.25f * (1f - quality))

                peaks[o] = binFloat / numMelBins
                peaks[o + 1] = quality
                peaks[o + 2] = snrNorm
                peaks[o + 3] = stability
                peaks[o + 4] = messiness
                laneBin[k] = binFloat
                laneStability[k] = stability
                laneAge[k] = 0
                laneMatched[k] = true
            } else {
                peaks[o] = -1f
                peaks[o + 1] = 0f
                peaks[o + 2] = 0f
                peaks[o + 3] = 0f
                peaks[o + 4] = 0f
            }
        }
        for (k in 0 until MAX_PEAKS) {
            if (!laneMatched[k]) {
                laneAge[k] += 1
                laneStability[k] *= STABILITY_MISS_DECAY
                if (laneAge[k] > LANE_MAX_MISSES) {
                    laneBin[k] = -1f
                    laneStability[k] = 0f
                }
            }
        }

        // 7. Haze (the junk cloud): residual energy with a soft knee, so real recordings stay
        // textured instead of saturating to one dark mass.
        val hazeBase = ((writeCounter % HISTORY_COLS).toInt()) * HAZE_BINS
        for (h in 0 until HAZE_BINS) {
            val lo = BAND_LO_BIN + (bandWidth * h) / HAZE_BINS
            val hh = BAND_LO_BIN + (bandWidth * (h + 1)) / HAZE_BINS
            var hsum = 0f
            var hcount = 0
            var i = lo
            while (i < hh && i < hi) { hsum += residual[i]; hcount++; i++ }
            val avg = if (hcount > 0) hsum / hcount else 0f
            val soft = avg / (avg + HAZE_SOFT_KNEE)
            val rowFrac = (h + 0.5f) / HAZE_BINS
            val lowMidWeight = 1f + LOW_MID_HAZE_LIFT * (1f - rowFrac)
            val messyLift = 1f + HAZE_MESSINESS_LIFT * flatness
            hazeBuf[hazeBase + h] = clamp01(soft * HAZE_MAX * lowMidWeight * messyLift)
        }

        for (b in BAND_LO_BIN until hi) previousResidual[b] = residual[b]

        // Publish the column.
        writeCounter = writeCounter + 1L
    }

    private fun ensureNoiseFloor(col: FloatArray, hi: Int) {
        if (noiseFloorInitialized) return
        for (b in BAND_LO_BIN until hi) {
            noiseFloor[b] = col[b]
        }
        noiseFloorInitialized = true
    }

    private fun updateNoiseFloorBin(bin: Int, value: Float): Float {
        val floor = noiseFloor[bin]
        val delta = value - floor
        val alpha = if (delta < 0f) NOISE_FLOOR_FALL_ALPHA else NOISE_FLOOR_RISE_ALPHA
        val updated = floor + delta * alpha
        noiseFloor[bin] = updated
        return updated
    }

    /** Assign nearest candidates to existing lanes before filling remaining lanes by strength. */
    private fun selectLaneCandidates(candCount: Int) {
        for (i in 0 until candCount) candTaken[i] = false
        for (k in 0 until MAX_PEAKS) chosenIdx[k] = -1

        for (lane in 0 until MAX_PEAKS) {
            val previous = laneBin[lane]
            if (previous < 0f) continue
            var bestI = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 0 until candCount) {
                if (!candTaken[c]) {
                    val dRaw = candBin[c] - previous
                    val d = if (dRaw >= 0f) dRaw else -dRaw
                    if (d <= LANE_LINK_BINS) {
                        val continuity = 1f - d / LANE_LINK_BINS
                        val score = candVal[c] + continuity * LANE_CONTINUITY_BONUS
                        if (score > bestScore) {
                            bestScore = score
                            bestI = c
                        }
                    }
                }
            }
            if (bestI >= 0) {
                candTaken[bestI] = true
                chosenIdx[lane] = bestI
            }
        }

        for (lane in 0 until MAX_PEAKS) {
            if (chosenIdx[lane] >= 0) continue
            var bestI = -1
            var bestV = Float.NEGATIVE_INFINITY
            for (c in 0 until candCount) {
                if (!candTaken[c] && candVal[c] > bestV) {
                    bestV = candVal[c]
                    bestI = c
                }
            }
            if (bestI >= 0) {
                candTaken[bestI] = true
                chosenIdx[lane] = bestI
            }
        }

        return
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

    private fun spectralFlatness(hi: Int, mean: Float): Float {
        if (mean <= 0.0001f) return 0f
        var logSum = 0f
        var count = 0
        for (b in BAND_LO_BIN until hi) {
            logSum += ln(residual[b] + FLATNESS_EPSILON)
            count++
        }
        if (count == 0) return 0f
        return clamp01(exp(logSum / count) / (mean + FLATNESS_EPSILON))
    }

    // ---- Reader accessors (UI thread). globalIndex is a column's writeCounter value. ----

    /** Sub-band peak position in 0..1 (relative pitch), or -1 if no peak. */
    fun peakBinFrac(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k)]

    fun peakQuality(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 1]

    fun peakSnr(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 2]

    fun peakStability(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 3]

    fun peakMessiness(globalIndex: Long, k: Int): Float =
        peaks[slotPeak(globalIndex, k) + 4]

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

        /** Floats stored per peak: [binFrac, quality, snr, stability, messiness]. */
        const val PEAK_ATTRS = 5

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

        // Peak detection / quality shaping (tunable; tests assert ordering, not exact values).
        private const val MIN_PEAK = 0.08f
        private const val SNR_SCALE = 2.4f
        private const val CONTRAST_SCALE = 1.9f
        private const val SHARP_SCALE = 1.5f
        private const val SHARP_W = 3
        private const val CONTINUITY_BINS = 2.5f
        private const val SNR_GATE = 2.1f

        private const val NOISE_FLOOR_RISE_ALPHA = 0.004f
        private const val NOISE_FLOOR_FALL_ALPHA = 0.08f
        private const val LOCAL_BACKGROUND_WEIGHT = 0.55f
        private const val FLATNESS_EPSILON = 0.0001f
        private const val FLUX_SCALE = 0.65f
        private const val JITTER_BINS = 3.5f
        private const val LANE_LINK_BINS = 4.0f
        private const val LANE_CONTINUITY_BONUS = 0.35f
        private const val LANE_MAX_MISSES = 4
        private const val STABILITY_DECAY = 0.82f
        private const val STABILITY_MISS_DECAY = 0.55f
        private const val HAZE_SOFT_KNEE = 0.95f
        private const val HAZE_MAX = 0.72f
        private const val LOW_MID_HAZE_LIFT = 0.18f
        private const val HAZE_MESSINESS_LIFT = 0.25f
        private const val LOW_PITCH_QUALITY = 0.84f
        private const val HIGH_PITCH_QUALITY = 1.12f

        private const val W_CONTRAST = 0.36f
        private const val W_SHARP = 0.26f
        private const val W_STABILITY = 0.24f
        private const val W_SNR = 0.14f
        private const val FLATNESS_PENALTY = 0.35f
        private const val FLUX_PENALTY = 0.20f
        private const val JITTER_PENALTY = 0.16f
    }
}
// === RIBBON-SYNC END ===
