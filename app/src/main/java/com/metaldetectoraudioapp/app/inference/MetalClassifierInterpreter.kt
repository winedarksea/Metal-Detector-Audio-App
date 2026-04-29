package com.metaldetectoraudioapp.app.inference

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.system.measureNanoTime

class MetalClassifierInterpreter(
    private val modelMetadata: ModelMetadata,
    appContext: Context,
    modelAssetName: String = "starter_model.tflite"
) : AudioWindowClassifier {

    override val activeAccelerator: InferenceAccelerator = InferenceAccelerator.CPU

    private val interpreter = Interpreter(loadModelFile(appContext, modelAssetName), Interpreter.Options())

    override fun classifyAudioWindow(samples: FloatArray): InferenceResult {
        val inputWindow = FloatArray(modelMetadata.input.windowSizeSamples)
        if (samples.size >= inputWindow.size) {
            samples.copyInto(inputWindow, endIndex = inputWindow.size)
        } else {
            samples.copyInto(inputWindow)
        }

        val inputBuffer = ByteBuffer
            .allocateDirect(inputWindow.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        inputWindow.forEach { inputBuffer.putFloat(it) }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(modelMetadata.labels.size) }
        val nanos = measureNanoTime {
            interpreter.run(inputBuffer, output)
        }

        val scores = output[0]
        var topIndex = 0
        var topScore = Float.NEGATIVE_INFINITY
        scores.forEachIndexed { index, value ->
            if (value > topScore) {
                topIndex = index
                topScore = value
            }
        }

        val map = buildMap {
            modelMetadata.labels.forEachIndexed { index, label ->
                put(label, scores.getOrElse(index) { 0f })
            }
        }

        return InferenceResult(
            topLabel = modelMetadata.labels.getOrElse(topIndex) { "AMBIENT" },
            topScore = topScore,
            perLabelScores = map,
            inferenceTimeMs = nanos / 1_000_000
        )
    }

    override fun close() {
        interpreter.close()
    }

    private fun loadModelFile(context: Context, modelAssetName: String): ByteBuffer {
        context.assets.openFd(modelAssetName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }
}
