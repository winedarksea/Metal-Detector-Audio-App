package com.metaldetectoraudioapp.app.audio.pipeline

class SlidingAudioFramer(
    private val frameSizeSamples: Int,
    private val hopSizeSamples: Int
) {
    private var ringBuffer = FloatArray(frameSizeSamples * 4)
    private var headIndex = 0
    private var itemCount = 0

    fun push(samples: FloatArray): List<FloatArray> {
        ensureCapacity(itemCount + samples.size)
        for (sample in samples) {
            val writeIndex = (headIndex + itemCount) % ringBuffer.size
            ringBuffer[writeIndex] = sample
            itemCount += 1
        }

        if (itemCount < frameSizeSamples) {
            return emptyList()
        }

        val frames = mutableListOf<FloatArray>()
        while (itemCount >= frameSizeSamples) {
            frames.add(readFrame())
            dropSamples(hopSizeSamples)
        }
        return frames
    }

    fun reset() {
        headIndex = 0
        itemCount = 0
    }

    private fun readFrame(): FloatArray {
        val frame = FloatArray(frameSizeSamples)
        for (offset in 0 until frameSizeSamples) {
            frame[offset] = ringBuffer[(headIndex + offset) % ringBuffer.size]
        }
        return frame
    }

    private fun dropSamples(count: Int) {
        val actualDropCount = minOf(count, itemCount)
        headIndex = (headIndex + actualDropCount) % ringBuffer.size
        itemCount -= actualDropCount
    }

    private fun ensureCapacity(requiredSize: Int) {
        if (requiredSize <= ringBuffer.size) {
            return
        }

        var newSize = ringBuffer.size
        while (newSize < requiredSize) {
            newSize *= 2
        }

        val newBuffer = FloatArray(newSize)
        for (offset in 0 until itemCount) {
            newBuffer[offset] = ringBuffer[(headIndex + offset) % ringBuffer.size]
        }

        ringBuffer = newBuffer
        headIndex = 0
    }
}
