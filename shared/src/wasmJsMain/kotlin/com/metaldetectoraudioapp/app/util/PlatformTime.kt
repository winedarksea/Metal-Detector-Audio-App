package com.metaldetectoraudioapp.app.util

internal actual fun platformEpochMillis(): Long = jsDateNow().toLong()

private fun jsDateNow(): Double = js("Date.now()")
