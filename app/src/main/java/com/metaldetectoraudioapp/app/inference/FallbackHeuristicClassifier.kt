package com.metaldetectoraudioapp.app.inference

import kotlin.math.abs

class FallbackHeuristicClassifier(
    private val labels: List<String>
) : AudioWindowClassifier {

    override fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        if (samples.isEmpty()) {
            return InferenceResult(
                topLabel = "AMBIENT",
                topScore = 1f,
                perLabelScores = mapOf("AMBIENT" to 1f),
                inferenceTimeMs = 0
            )
        }

        var meanAbs = 0f
        var zeroCrossings = 0
        var previous = samples.first()
        for (sample in samples) {
            meanAbs += abs(sample)
            if ((sample >= 0f && previous < 0f) || (sample < 0f && previous >= 0f)) {
                zeroCrossings += 1
            }
            previous = sample
        }
        meanAbs /= samples.size
        val crossingRate = zeroCrossings.toFloat() / samples.size

        val targetScore = (meanAbs * 1.8f + (0.5f - crossingRate)).coerceIn(0f, 1f)
        val junkScore = (crossingRate * 1.8f).coerceIn(0f, 1f)
        val ambientScore = (1f - meanAbs * 2f).coerceIn(0f, 1f)

        val scoreMap = mapOf(
            "TARGET" to targetScore,
            "JUNK" to junkScore,
            "AMBIENT" to ambientScore
        )

        val winner = scoreMap.maxByOrNull { it.value }
        val topLabel = winner?.key ?: "AMBIENT"
        val topScore = winner?.value ?: 1f
        return InferenceResult(
            topLabel = topLabel,
            topScore = topScore,
            perLabelScores = scoreMap,
            inferenceTimeMs = 0
        )
    }

    override fun close() = Unit
}
