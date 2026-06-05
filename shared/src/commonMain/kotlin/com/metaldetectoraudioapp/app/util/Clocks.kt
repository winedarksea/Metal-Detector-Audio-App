package com.metaldetectoraudioapp.app.util

import kotlinx.datetime.Clock
import kotlin.time.TimeSource

/**
 * Multiplatform replacements for the JVM-only `System.nanoTime()` / `System.currentTimeMillis()`
 * so timing code can live in commonMain and compile for desktop, android, and wasmJs.
 */
object Clocks {
    private val monotonicStart = TimeSource.Monotonic.markNow()

    /** Monotonic nanoseconds since first access; for elapsed/latency measurement only. */
    fun monotonicNanos(): Long = monotonicStart.elapsedNow().inWholeNanoseconds

    /** Wall-clock epoch milliseconds. */
    fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
