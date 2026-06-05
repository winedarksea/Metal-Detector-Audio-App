package com.metaldetectoraudioapp.app.recording

/**
 * A finished, in-memory recording: the encoded PCM16 WAV bytes plus its duration.
 *
 * Byte-based (rather than a `File`) so the recording flow is multiplatform — desktop reads the
 * captured temp WAV into bytes, web captures straight into memory.
 */
class CapturedRecording(
    val wavBytes: ByteArray,
    val durationMs: Long,
)
