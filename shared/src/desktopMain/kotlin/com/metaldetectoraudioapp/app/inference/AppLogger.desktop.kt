package com.metaldetectoraudioapp.app.inference

actual object AppLogger {
    actual fun d(tag: String, message: String) { println("D/$tag: $message") }
    actual fun w(tag: String, message: String) { println("W/$tag: $message") }
    actual fun e(tag: String, message: String) { System.err.println("E/$tag: $message") }
}
