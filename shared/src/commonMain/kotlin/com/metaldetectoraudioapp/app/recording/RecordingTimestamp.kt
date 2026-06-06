package com.metaldetectoraudioapp.app.recording

/**
 * Formats [epochMs] as a local-time `YYYYMMDD_HHMMSS_mmm` stamp used in recording IDs.
 *
 * Implemented per-platform: JVM uses kotlinx-datetime, wasmJs uses the JS `Date` API. The wasm
 * actual deliberately avoids kotlinx-datetime — its `Instant.Companion` symbol fails to link under
 * Kotlin/Wasm, which previously broke "Save Recording" in the PWA.
 */
expect fun formatRecordingTimestamp(epochMs: Long): String
