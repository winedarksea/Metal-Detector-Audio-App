package com.metaldetectoraudioapp.web.platform

/**
 * Helpers bridging Kotlin/Wasm ↔ JavaScript.
 *
 * Kotlin/WASM restriction: only primitives (Int, Long, Double, Boolean),
 * Strings, and `external` types can cross the JS interop boundary.
 * ByteArray/FloatArray must be copied through a shared global JS buffer.
 */

// ── Global byte write buffer (Kotlin → JS) ────────────────────────────────────

/** Copies [bytes] into `window.__ktWriteBuf` and returns the count. */
fun initWriteBuf(size: Int): Unit = js("window.__ktWriteBuf = new Uint8Array(size)")
fun setWriteBufByte(i: Int, v: Int): Unit = js("window.__ktWriteBuf[i] = v & 0xFF")

/** Writes bytes to the global buffer in preparation for a JS call. */
fun writeBytesToGlobal(bytes: ByteArray) {
    initWriteBuf(bytes.size)
    for (i in bytes.indices) setWriteBufByte(i, bytes[i].toInt())
}

// ── Blob / URL helpers ────────────────────────────────────────────────────────

/**
 * Creates a Blob URL from bytes. Copies bytes to global buf then invokes JS.
 * The caller must call [revokeObjectUrl] when done.
 */
fun createBlobUrl(bytes: ByteArray, mimeType: String): String {
    writeBytesToGlobal(bytes)
    return createBlobUrlFromGlobal(bytes.size, mimeType)
}

private fun createBlobUrlFromGlobal(count: Int, mimeType: String): String =
    js("URL.createObjectURL(new Blob([window.__ktWriteBuf.slice(0, count)], { type: mimeType }))")

fun revokeObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")

// ── Promise<T> ────────────────────────────────────────────────────────────────

external class Promise<T : JsAny?>(
    executor: (resolve: (T) -> Unit, reject: (JsAny?) -> Unit) -> Unit
) : JsAny

suspend fun <T : JsAny?> Promise<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        promiseThen(
            promise = this,
            onFulfilled = { value: T -> cont.resumeWith(Result.success(value)) },
            onRejected = { reason: JsAny? ->
                cont.resumeWith(Result.failure(RuntimeException("JS Promise rejected: ${jsErrorMessage(reason)}")))
            }
        )
    }

/** Extracts a human-readable message from a rejected promise reason (Error.message or String). */
private fun jsErrorMessage(reason: JsAny?): String =
    js("String(reason && reason.message ? reason.message : reason)")

// Top-level (non-extension) call avoids the "js() not allowed in extension" restriction.
private fun <T : JsAny?> promiseThen(
    promise: Promise<T>,
    onFulfilled: (T) -> Unit,
    onRejected: (JsAny?) -> Unit,
): Unit = js("promise.then(onFulfilled, onRejected)")
