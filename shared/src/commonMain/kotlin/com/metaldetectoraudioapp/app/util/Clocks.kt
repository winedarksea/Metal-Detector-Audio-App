package com.metaldetectoraudioapp.app.util

import kotlin.time.TimeSource

object Clocks {
    private val monotonicStart = TimeSource.Monotonic.markNow()

    /** Monotonic nanoseconds since first access; for elapsed/latency measurement only. */
    fun monotonicNanos(): Long = monotonicStart.elapsedNow().inWholeNanoseconds

    /** Wall-clock epoch milliseconds. */
    fun epochMillis(): Long = platformEpochMillis()
}
