package com.metaldetectoraudioapp.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * `WavCodec`, `AudioTrim`, and the `AudioTrimmer` composable live in `shared/` and are duplicated
 * byte-identically into the Android `app/` module because app/ does not depend on :shared (same
 * arrangement as [com.metaldetectoraudioapp.app.audio.RibbonAnalyzerSyncTest]). This guards that the
 * copies stay identical.
 */
class AudioTrimSyncTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }

    private fun assertMirrored(relativePathInModule: String) {
        val root = repoRoot()
        val appCopy = File(root, "app/src/main/java/$relativePathInModule")
        val sharedCopy = File(root, "shared/src/commonMain/kotlin/$relativePathInModule")
        assertTrue("missing app copy: $appCopy", appCopy.exists())
        assertTrue("missing shared copy: $sharedCopy", sharedCopy.exists())
        assertEquals(
            "${appCopy.name} drifted between app/ and shared/ — keep the two copies identical",
            sharedCopy.readText(),
            appCopy.readText(),
        )
    }

    @Test
    fun wavCodec_isMirrored() {
        assertMirrored("com/metaldetectoraudioapp/app/recording/WavCodec.kt")
    }

    @Test
    fun audioTrim_isMirrored() {
        assertMirrored("com/metaldetectoraudioapp/app/recording/AudioTrim.kt")
    }

    @Test
    fun audioTrimmer_isMirrored() {
        assertMirrored("com/metaldetectoraudioapp/app/ui/screen/AudioTrimmer.kt")
    }
}
