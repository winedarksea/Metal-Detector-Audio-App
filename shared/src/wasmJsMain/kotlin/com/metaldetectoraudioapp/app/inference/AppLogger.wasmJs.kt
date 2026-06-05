package com.metaldetectoraudioapp.app.inference

actual object AppLogger {
    actual fun d(tag: String, message: String) { consoleLog("D/$tag: $message") }
    actual fun w(tag: String, message: String) { consoleWarn("W/$tag: $message") }
    actual fun e(tag: String, message: String) { consoleError("E/$tag: $message") }
}

private fun consoleLog(msg: String): Unit = js("console.log(msg)")
private fun consoleWarn(msg: String): Unit = js("console.warn(msg)")
private fun consoleError(msg: String): Unit = js("console.error(msg)")
