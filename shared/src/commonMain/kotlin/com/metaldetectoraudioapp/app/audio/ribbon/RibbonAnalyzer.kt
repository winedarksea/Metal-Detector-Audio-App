package com.metaldetectoraudioapp.app.audio.ribbon

import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.ln

/**
 * Streaming tone-quality analyzer for the detection-screen ribbon visual.
 *
 * Converts the model's log-mel spectrogram into tracked clean-tone peaks plus coarse haze
 * energy. A clean, coherent tone becomes a bright sharp ribbon; messy/noisy energy becomes
 * dim diffuse haze. Fixed scratch arrays keep the audio/UI handoff allocation-free.
 *
 * IMPORTANT: duplicated in `app/` and `shared/`; keep byte-identical.
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
    private val displayLogMel = FloatArray(numMelBins)
    private val prominence = FloatArray(numMelBins)
    private val smooth = FloatArray(numMelBins)
    private val candBin = FloatArray(numMelBins)
    private val candVal = FloatArray(numMelBins)
    private val candTaken = BooleanArray(numMelBins)
    private val chosenIdx = IntArray(MAX_PEAKS)
    private val laneMatched = BooleanArray(MAX_PEAKS)
    private val noiseFloor = FloatArray(numMelBins)
    private val previousResidual = FloatArray(numMelBins)
    private val rowCleanToneQuality = FloatArray(HAZE_BINS)
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
        displayLogMel.fill(0f)
        prominence.fill(0f)
        smooth.fill(0f)
        previousResidual.fill(0f)
        rowCleanToneQuality.fill(0f)
        noiseFloor.fill(0f)
        noiseFloorInitialized = false
        peaks.fill(0f)
        hazeBuf.fill(0f)
        writeCounter = 0L
    }

    /** Appends only the newest [NEW_COLS] columns from a 50%-overlapping log-mel window. */
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

        // Residual powers haze; log-mel prominence powers peaks so steady tones stay visible.
        var residualSum = 0f
        var fluxSum = 0f
        for (b in BAND_LO_BIN until hi) {
            val sample = safeLogMel(col[b])
            displayLogMel[b] = sample
            val floor = updateNoiseFloorBin(b, sample)
            val v = sample - floor
            val r = compressResidual(if (v > 0f) v else 0f)
            val diff = r - previousResidual[b]
            fluxSum += if (diff >= 0f) diff else -diff
            residual[b] = r
            residualSum += r
        }

        val bandWidth = hi - BAND_LO_BIN
        val residualMean = if (bandWidth > 0) residualSum / bandWidth else 0f
        val flux = clamp01((if (bandWidth > 0) fluxSum / bandWidth else 0f) / FLUX_SCALE)
        val flatness = spectralFlatness(hi, residualMean)

        // Use sanitized log-mel here so steady true tones cannot disappear into the floor.
        for (b in BAND_LO_BIN until hi) {
            var sum = 0f
            var cnt = 0
            var w = b - BG_WIN
            while (w <= b + BG_WIN) {
                if (w in BAND_LO_BIN until hi) { sum += displayLogMel[w]; cnt++ }
                w++
            }
            val bg = if (cnt > 0) sum / cnt else 0f
            val v = displayLogMel[b] - bg * PEAK_LOCAL_BACKGROUND_WEIGHT
            prominence[b] = if (v > 0f) v else 0f
        }

        smooth[BAND_LO_BIN] = prominence[BAND_LO_BIN]
        smooth[hi - 1] = prominence[hi - 1]
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            smooth[b] = 0.25f * prominence[b - 1] + 0.5f * prominence[b] + 0.25f * prominence[b + 1]
        }

        var sum = 0f
        for (b in BAND_LO_BIN until hi) sum += smooth[b]
        val mean = if (bandWidth > 0) sum / bandWidth else 0f

        var candCount = 0
        for (b in BAND_LO_BIN + 1 until hi - 1) {
            val c = smooth[b]
            if (c >= MIN_PEAK && c > smooth[b - 1] && c >= smooth[b + 1]) {
                candBin[candCount] = b.toFloat()
                candVal[candCount] = c
                candCount++
            }
        }

        selectLaneCandidates(candCount)

        val base = ((writeCounter % HISTORY_COLS).toInt()) * MAX_PEAKS * PEAK_ATTRS
        for (k in 0 until MAX_PEAKS) laneMatched[k] = false
        rowCleanToneQuality.fill(0f)
        var columnCleanToneQuality = 0f
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
                val row = ((binFloat - BAND_LO_BIN) * HAZE_BINS / bandWidth).toInt()
                    .coerceIn(0, HAZE_BINS - 1)
                if (quality > rowCleanToneQuality[row]) rowCleanToneQuality[row] = quality
                if (row > 0 && quality * HAZE_NEIGHBOR_TONE_FRACTION > rowCleanToneQuality[row - 1]) {
                    rowCleanToneQuality[row - 1] = quality * HAZE_NEIGHBOR_TONE_FRACTION
                }
                if (row < HAZE_BINS - 1 && quality * HAZE_NEIGHBOR_TONE_FRACTION > rowCleanToneQuality[row + 1]) {
                    rowCleanToneQuality[row + 1] = quality * HAZE_NEIGHBOR_TONE_FRACTION
                }
                if (quality > columnCleanToneQuality) columnCleanToneQuality = quality
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

        // Haze: soft-kneed residual energy with clean-tone suppression to keep targets distinct.
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
            val messyLift = 1f + HAZE_MESSINESS_LIFT * (0.65f * flatness + 0.35f * flux)
            val cleanToneSuppression = 1f - HAZE_CLEAN_TONE_SUPPRESSION * rowCleanToneQuality[h]
            val cleanQ2 = columnCleanToneQuality * columnCleanToneQuality
            val columnToneSuppression = 1f - HAZE_COLUMN_CLEAN_TONE_SUPPRESSION * cleanQ2
            val lowMidCleanSuppression = 1f - HAZE_LOW_MID_CLEAN_TONE_SUPPRESSION * cleanQ2 * cleanQ2 * (1f - rowFrac)
            hazeBuf[hazeBase + h] = clamp01(
                soft * HAZE_MAX * lowMidWeight * messyLift *
                    cleanToneSuppression * columnToneSuppression * lowMidCleanSuppression,
            )
        }

        for (b in BAND_LO_BIN until hi) previousResidual[b] = residual[b]

        // Publish the column.
        writeCounter = writeCounter + 1L
    }

    private fun ensureNoiseFloor(col: FloatArray, hi: Int) {
        if (noiseFloorInitialized) return
        for (b in BAND_LO_BIN until hi) {
            val sample = safeLogMel(col[b])
            noiseFloor[b] = if (sample <= QUIET_LOG_MEL_THRESHOLD) {
                sample
            } else {
                sample - INITIAL_NOISE_FLOOR_HEADROOM
            }
        }
        noiseFloorInitialized = true
    }

    private fun updateNoiseFloorBin(bin: Int, value: Float): Float {
        val sample = safeLogMel(value)
        val floor = noiseFloor[bin]
        val delta = sample - floor
        val alpha = if (delta < 0f) NOISE_FLOOR_FALL_ALPHA else NOISE_FLOOR_RISE_ALPHA
        val updated = floor + delta * alpha
        noiseFloor[bin] = updated
        return updated
    }

    private fun safeLogMel(value: Float): Float =
        when {
            value.isNaN() -> MIN_LOG_MEL
            value == Float.NEGATIVE_INFINITY -> MIN_LOG_MEL
            value == Float.POSITIVE_INFINITY -> MAX_LOG_MEL
            value < MIN_LOG_MEL -> MIN_LOG_MEL
            value > MAX_LOG_MEL -> MAX_LOG_MEL
            else -> value
        }

    private fun compressResidual(value: Float): Float {
        if (value <= 0f || value.isNaN()) return 0f
        return RESIDUAL_DISPLAY_CAP * value / (value + RESIDUAL_DISPLAY_KNEE)
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

    private fun clamp01(v: Float): Float =
        if (v.isNaN() || v == Float.NEGATIVE_INFINITY) 0f else if (v == Float.POSITIVE_INFINITY || v > 1f) 1f else if (v < 0f) 0f else v

    companion object {
        const val HISTORY_COLS = 512

        const val VISIBLE_COLS = 360

        const val MAX_PEAKS = 3

        const val PEAK_ATTRS = 5

        const val HAZE_BINS = 20

        const val MEL_BINS = 40

        const val NEW_COLS = 31
        const val COLUMNS_PER_SECOND = 124f

        const val BAND_LO_BIN = 0
        const val BAND_HI_BIN = 28

        private const val BG_WIN = 6

        private const val MIN_PEAK = 0.08f
        private const val SNR_SCALE = 2.4f
        private const val CONTRAST_SCALE = 1.9f
        private const val SHARP_SCALE = 1.5f
        private const val SHARP_W = 3
        private const val CONTINUITY_BINS = 2.5f
        private const val SNR_GATE = 2.1f

        private const val NOISE_FLOOR_RISE_ALPHA = 0.004f
        private const val NOISE_FLOOR_FALL_ALPHA = 0.08f
        private const val INITIAL_NOISE_FLOOR_HEADROOM = 4.0f
        private const val QUIET_LOG_MEL_THRESHOLD = -11.0f
        private const val PEAK_LOCAL_BACKGROUND_WEIGHT = 1.0f
        private const val LOCAL_BACKGROUND_WEIGHT = 0.55f
        private const val FLATNESS_EPSILON = 0.0001f
        private const val FLUX_SCALE = 0.65f
        private const val JITTER_BINS = 3.5f
        private const val LANE_LINK_BINS = 4.0f
        private const val LANE_CONTINUITY_BONUS = 0.35f
        private const val LANE_MAX_MISSES = 4
        private const val STABILITY_DECAY = 0.82f
        private const val STABILITY_MISS_DECAY = 0.55f
        private const val MIN_LOG_MEL = -13.9f
        private const val MAX_LOG_MEL = 6.0f
        private const val RESIDUAL_DISPLAY_CAP = 4.6f
        private const val RESIDUAL_DISPLAY_KNEE = 2.2f
        private const val HAZE_SOFT_KNEE = 1.15f
        private const val HAZE_MAX = 0.58f
        private const val LOW_MID_HAZE_LIFT = 0.14f
        private const val HAZE_MESSINESS_LIFT = 0.16f
        private const val HAZE_CLEAN_TONE_SUPPRESSION = 0.95f
        private const val HAZE_NEIGHBOR_TONE_FRACTION = 0.85f
        private const val HAZE_COLUMN_CLEAN_TONE_SUPPRESSION = 2.0f
        private const val HAZE_LOW_MID_CLEAN_TONE_SUPPRESSION = 12.0f
        private const val LOW_PITCH_QUALITY = 1.05f
        private const val HIGH_PITCH_QUALITY = 1.05f

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
