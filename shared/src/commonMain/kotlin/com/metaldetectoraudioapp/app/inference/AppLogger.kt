package com.metaldetectoraudioapp.app.inference

/**
 * Platform-agnostic logger that replaces [android.util.Log].
 * Each platform provides its own implementation.
 */
expect object AppLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String)
}
