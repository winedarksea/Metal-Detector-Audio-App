package com.metaldetectoraudioapp.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The ribbon DSP and renderer are duplicated in the Android `app/` module and the `shared/`
 * module because `app/` does not depend on `:shared` (mirrors [AndroidMelSpectrogramFeatureExtractor]
 * vs the shared `MelSpectrogramFeatureExtractor]). This guards that the copies stay byte-identical.
 */
class RibbonAnalyzerSyncTest {

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
    fun ribbonAnalyzer_isMirrored() {
        assertMirrored("com/metaldetectoraudioapp/app/audio/ribbon/RibbonAnalyzer.kt")
    }

    @Test
    fun ribbonCanvas_isMirrored() {
        assertMirrored("com/metaldetectoraudioapp/app/ui/screen/RibbonCanvas.kt")
    }
}
