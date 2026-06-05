package com.metaldetectoraudioapp.web.storage

import com.metaldetectoraudioapp.app.recording.DatasetStore
import com.metaldetectoraudioapp.web.platform.initWriteBuf
import com.metaldetectoraudioapp.web.platform.setWriteBufByte
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * DatasetStore backed by IndexedDB — required on web (Safari evicts the ephemeral filesystem).
 *
 * Byte data is transferred WASM↔JS via a temporary global array: JS writes the byte values
 * into `window.__idbTmpBytes`, then Kotlin reads them via [readTmpBytes].
 */
class IndexedDbDatasetStore : DatasetStore {

    override suspend fun readMetadataCsv(): String =
        suspendCancellableCoroutine { cont ->
            idbGetString("meta", "recordings_metadata.csv") { v ->
                if (cont.isActive) cont.resume(v ?: "")
            }
        }

    override suspend fun writeMetadataCsv(csv: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            idbPutString("meta", "recordings_metadata.csv", csv) {
                if (cont.isActive) cont.resume(Unit)
            }
        }

    override suspend fun saveAudio(fileName: String, bytes: ByteArray) = putBlob("audio", fileName, bytes)
    override suspend fun readAudio(fileName: String): ByteArray? = getBlob("audio", fileName)
    override suspend fun deleteAudio(fileName: String) = deleteBlob("audio", fileName)

    override suspend fun saveImage(fileName: String, bytes: ByteArray) = putBlob("images", fileName, bytes)
    override suspend fun readImage(fileName: String): ByteArray? = getBlob("images", fileName)
    override suspend fun deleteImage(fileName: String) = deleteBlob("images", fileName)

    private suspend fun putBlob(store: String, key: String, bytes: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            // Stage bytes in global buf first (ByteArray can't cross JS boundary directly)
            initWriteBuf(bytes.size)
            for (i in bytes.indices) setWriteBufByte(i, bytes[i].toInt())
            idbPutBytesFromGlobal(store, key, bytes.size) { if (cont.isActive) cont.resume(Unit) }
        }

    private suspend fun getBlob(store: String, key: String): ByteArray? =
        suspendCancellableCoroutine { cont ->
            idbGetBytesToGlobal(store, key, notFound = {
                if (cont.isActive) cont.resume(null)
            }, found = { size ->
                if (cont.isActive) cont.resume(readTmpBytes(size))
            })
        }

    private suspend fun deleteBlob(store: String, key: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            idbDelete(store, key) { if (cont.isActive) cont.resume(Unit) }
        }
}

// ── JS bridge ─────────────────────────────────────────────────────────────────

/** Reads bytes from `window.__idbTmpBytes` (populated by [idbGetBytesToGlobal]). */
private fun readTmpBytes(size: Int): ByteArray {
    val result = ByteArray(size)
    for (i in 0 until size) {
        result[i] = readTmpByte(i)
    }
    clearTmpBytes()
    return result
}

private fun readTmpByte(index: Int): Byte = js("(window.__idbTmpBytes[index] << 24 >> 24)")

private fun clearTmpBytes(): Unit = js("window.__idbTmpBytes = null")

private fun idbGetString(store: String, key: String, callback: (String?) -> Unit) {
    js("""
        var req = indexedDB.open('metaldetector_dataset', 1);
        req.onupgradeneeded = function(e) {
            var db = e.target.result;
            ['meta','audio','images'].forEach(function(s) {
                if (!db.objectStoreNames.contains(s)) db.createObjectStore(s);
            });
        };
        req.onsuccess = function(e) {
            var db = e.target.result, tx = db.transaction(store, 'readonly');
            var r = tx.objectStore(store).get(key);
            r.onsuccess = function() {
                callback(r.result !== undefined ? String(r.result) : null);
            };
            r.onerror = function() { callback(null); };
        };
        req.onerror = function() { callback(null); };
    """)
}

private fun idbPutString(store: String, key: String, value: String, callback: () -> Unit) {
    js("""
        var req = indexedDB.open('metaldetector_dataset', 1);
        req.onupgradeneeded = function(e) {
            var db = e.target.result;
            ['meta','audio','images'].forEach(function(s) {
                if (!db.objectStoreNames.contains(s)) db.createObjectStore(s);
            });
        };
        req.onsuccess = function(e) {
            var db = e.target.result, tx = db.transaction(store, 'readwrite');
            tx.objectStore(store).put(value, key);
            tx.oncomplete = function() { callback(); };
            tx.onerror    = function() { callback(); };
        };
        req.onerror = function() { callback(); };
    """)
}

private fun idbPutBytesFromGlobal(store: String, key: String, count: Int, callback: () -> Unit) {
    js("""
        var src = window.__ktWriteBuf;
        var arr = new Uint8Array(count);
        for (var i = 0; i < count; i++) arr[i] = src[i] & 0xFF;
        var req = indexedDB.open('metaldetector_dataset', 1);
        req.onupgradeneeded = function(e) {
            var db = e.target.result;
            ['meta','audio','images'].forEach(function(s) {
                if (!db.objectStoreNames.contains(s)) db.createObjectStore(s);
            });
        };
        req.onsuccess = function(e) {
            var db = e.target.result, tx = db.transaction(store, 'readwrite');
            tx.objectStore(store).put(arr.buffer, key);
            tx.oncomplete = function() { callback(); };
            tx.onerror    = function() { callback(); };
        };
        req.onerror = function() { callback(); };
    """)
}

private fun idbGetBytesToGlobal(
    store: String,
    key: String,
    notFound: () -> Unit,
    found: (size: Int) -> Unit,
) {
    js("""
        var req = indexedDB.open('metaldetector_dataset', 1);
        req.onupgradeneeded = function(e) {
            var db = e.target.result;
            ['meta','audio','images'].forEach(function(s) {
                if (!db.objectStoreNames.contains(s)) db.createObjectStore(s);
            });
        };
        req.onsuccess = function(e) {
            var db = e.target.result, tx = db.transaction(store, 'readonly');
            var r = tx.objectStore(store).get(key);
            r.onsuccess = function() {
                if (r.result === undefined || r.result === null) { notFound(); return; }
                var view = new Int8Array(r.result);
                window.__idbTmpBytes = view;
                found(view.length);
            };
            r.onerror = function() { notFound(); };
        };
        req.onerror = function() { notFound(); };
    """)
}

private fun idbDelete(store: String, key: String, callback: () -> Unit) {
    js("""
        var req = indexedDB.open('metaldetector_dataset', 1);
        req.onsuccess = function(e) {
            var db = e.target.result, tx = db.transaction(store, 'readwrite');
            tx.objectStore(store).delete(key);
            tx.oncomplete = function() { callback(); };
            tx.onerror    = function() { callback(); };
        };
        req.onerror = function() { callback(); };
    """)
}
