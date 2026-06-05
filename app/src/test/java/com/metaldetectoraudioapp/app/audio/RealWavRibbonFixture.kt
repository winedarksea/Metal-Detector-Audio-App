package com.metaldetectoraudioapp.app.audio

import com.metaldetectoraudioapp.app.audio.pipeline.AndroidMelSpectrogramFeatureExtractor
import com.metaldetectoraudioapp.app.audio.pipeline.AudioNormalizationProcessor
import com.metaldetectoraudioapp.app.audio.pipeline.BandLimitFilter
import com.metaldetectoraudioapp.app.audio.pipeline.SlidingAudioFramer
import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

object RealWavRibbonFixture {
    data class RibbonFixtureStats(
        val hazeSaturationFraction: Float,
        val averageHaze: Float,
        val lowMidHaze: Float,
        val highHaze: Float,
        val highCleanRibbonScore: Float,
        val diffuseLowMidHazeScore: Float,
    )

    fun analyzeFixture(assetName: String): RibbonAnalyzer {
        val analyzer = RibbonAnalyzer()
        val mel = AndroidMelSpectrogramFeatureExtractor()
        val normalizer = AudioNormalizationProcessor()
        val bandLimitFilter = BandLimitFilter(AudioConstants.INFERENCE_SAMPLE_RATE_HZ)
        val framer = SlidingAudioFramer(
            AudioConstants.INFERENCE_WINDOW_SIZE_SAMPLES,
            AudioConstants.INFERENCE_HOP_SIZE_SAMPLES,
        )
        val samples = loadDownmixedResampledAsset(assetName)

        var offset = 0
        while (offset < samples.size) {
            val count = minOf(AudioConstants.INFERENCE_CAPTURE_BLOCK_SIZE, samples.size - offset)
            val block = FloatArray(count)
            for (i in 0 until count) block[i] = samples[offset + i]
            normalizer.normalizeInPlace(block)
            bandLimitFilter.processInPlace(block)
            val frames = framer.push(block)
            for (frame in frames) {
                analyzer.process(mel.extractLogMelSpectrogram(frame))
            }
            offset += count
        }

        return analyzer
    }

    fun stats(analyzer: RibbonAnalyzer): RibbonFixtureStats {
        val writeCounter = analyzer.writeCounter
        val first = maxOf(0L, writeCounter - RibbonAnalyzer.HISTORY_COLS)
        var hazeSum = 0f
        var lowMidHazeSum = 0f
        var highHazeSum = 0f
        var hazeCount = 0
        var lowMidCount = 0
        var highCount = 0
        var saturatedHazeCount = 0
        var highCleanScore = 0f

        var g = first
        while (g < writeCounter) {
            for (h in 0 until RibbonAnalyzer.HAZE_BINS) {
                val haze = analyzer.haze(g, h)
                hazeSum += haze
                hazeCount += 1
                if (haze > HAZE_SATURATION_LEVEL) saturatedHazeCount += 1
                if (h < LOW_MID_HAZE_ROWS) {
                    lowMidHazeSum += haze
                    lowMidCount += 1
                }
                if (h >= RibbonAnalyzer.HAZE_BINS - HIGH_HAZE_ROWS) {
                    highHazeSum += haze
                    highCount += 1
                }
            }
            for (k in 0 until RibbonAnalyzer.MAX_PEAKS) {
                val binFrac = analyzer.peakBinFrac(g, k)
                if (binFrac >= 0f) {
                    val bandFrac = (binFrac / BAND_FRAC).coerceIn(0f, 1f)
                    val pitchWeight = bandFrac * bandFrac
                    val quality = analyzer.peakQuality(g, k)
                    val stability = analyzer.peakStability(g, k)
                    val score = quality * (0.35f + 0.65f * pitchWeight) * (0.75f + 0.25f * stability)
                    if (score > highCleanScore) highCleanScore = score
                }
            }
            g += 1L
        }

        val lowMidHaze = if (lowMidCount > 0) lowMidHazeSum / lowMidCount else 0f
        val highHaze = if (highCount > 0) highHazeSum / highCount else 0f
        return RibbonFixtureStats(
            hazeSaturationFraction = if (hazeCount > 0) saturatedHazeCount.toFloat() / hazeCount else 0f,
            averageHaze = if (hazeCount > 0) hazeSum / hazeCount else 0f,
            lowMidHaze = lowMidHaze,
            highHaze = highHaze,
            highCleanRibbonScore = highCleanScore,
            diffuseLowMidHazeScore = lowMidHaze - highHaze,
        )
    }

    private fun loadDownmixedResampledAsset(assetName: String): FloatArray {
        val wav = readPcm16Wav(File(repoRoot(), "assets/$assetName"))
        val mono = FloatArray(wav.frameCount)
        for (frame in 0 until wav.frameCount) {
            var sum = 0f
            for (channel in 0 until wav.channels) {
                sum += wav.samples[frame * wav.channels + channel] / 32768f
            }
            mono[frame] = sum / wav.channels
        }
        return resampleLinear(mono, wav.sampleRateHz, AudioConstants.INFERENCE_SAMPLE_RATE_HZ)
    }

    private data class Pcm16Wav(
        val channels: Int,
        val sampleRateHz: Int,
        val samples: ShortArray,
    ) {
        val frameCount: Int = samples.size / channels
    }

    private fun readPcm16Wav(file: File): Pcm16Wav {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV fixture is too small: ${file.absolutePath}" }
        require(String(bytes, 0, 4) == "RIFF") { "Not a RIFF WAV: ${file.absolutePath}" }
        require(String(bytes, 8, 4) == "WAVE") { "Not a WAVE file: ${file.absolutePath}" }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataStart = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = littleEndianInt(bytes, offset + 4)
            val chunkDataStart = offset + 8
            if (id == "fmt ") {
                val audioFormat = littleEndianShort(bytes, chunkDataStart).toInt()
                channels = littleEndianShort(bytes, chunkDataStart + 2).toInt()
                sampleRate = littleEndianInt(bytes, chunkDataStart + 4)
                bitsPerSample = littleEndianShort(bytes, chunkDataStart + 14).toInt()
                require(audioFormat == 1) { "Only PCM WAV fixtures are supported: ${file.name}" }
            } else if (id == "data") {
                dataStart = chunkDataStart
                dataSize = size
            }
            offset = chunkDataStart + size + (size and 1)
        }

        require(channels > 0) { "Missing WAV fmt chunk: ${file.name}" }
        require(bitsPerSample == 16) { "Only PCM16 WAV fixtures are supported: ${file.name}" }
        require(dataStart >= 0) { "Missing WAV data chunk: ${file.name}" }

        val sampleCount = minOf(dataSize, bytes.size - dataStart) / 2
        val samples = ShortArray(sampleCount)
        val data = ByteBuffer.wrap(bytes, dataStart, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = data.short
        }
        return Pcm16Wav(channels, sampleRate, samples)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun resampleLinear(samples: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz || samples.isEmpty()) return samples
        val ratio = fromHz.toDouble() / toHz.toDouble()
        val outLen = floor(samples.size / ratio).toInt().coerceAtLeast(1)
        return FloatArray(outLen) { i ->
            val srcPos = i * ratio
            val lo = srcPos.toInt().coerceIn(0, samples.lastIndex)
            val hi = (lo + 1).coerceIn(0, samples.lastIndex)
            val frac = (srcPos - lo).toFloat()
            samples[lo] * (1f - frac) + samples[hi] * frac
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root from ${System.getProperty("user.dir")}")
    }

    private const val HAZE_SATURATION_LEVEL = 0.90f
    private const val LOW_MID_HAZE_ROWS = 9
    private const val HIGH_HAZE_ROWS = 5
    private const val BAND_FRAC = RibbonAnalyzer.BAND_HI_BIN.toFloat() / RibbonAnalyzer.MEL_BINS
}
