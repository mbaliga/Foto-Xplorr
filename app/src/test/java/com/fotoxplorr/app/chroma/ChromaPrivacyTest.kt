package com.fotoxplorr.app.chroma

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the source: the Chroma package has no way to write a picture anywhere, asks for the camera only
 * inside the experiment, and keeps the manifest's camera lines non-required. These are text checks of
 * the repository, not of behaviour; behaviour is in [ChromaJobTest].
 */
class ChromaPrivacyTest {
    private fun appFile(path: String): File {
        val f = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
        assertNotNull("$path not found from ${File(".").absolutePath}", f)
        return f!!
    }

    private fun chromaSources(): List<File> {
        val dir = appFile("src/main/java/com/fotoxplorr/app/chroma")
        return dir.listFiles { f -> f.extension == "kt" }!!.toList()
    }

    @Test
    fun noChromaFileWritesAPictureOrTouchesTheLibrary() {
        val forbidden = listOf(
            "MediaStore", "ContentResolver", "FileOutputStream", "openOutputStream", ".compress(", "FileWriter",
            "writeBytes", "writeText", "getExternalStorage", "SharedPreferences", "DataStore", "openFileOutput",
        )
        for (f in chromaSources()) {
            // The comments say what is NOT done, so scan code lines only.
            val code = f.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
            for (token in forbidden) {
                assertFalse("${f.name} uses $token", code.any { token in it })
            }
        }
    }

    @Test
    fun theJobHoldsNoContextSoItCannotWriteAnything() {
        val src = appFile("src/main/java/com/fotoxplorr/app/chroma/ChromaJob.kt").readText()
        assertFalse(Regex("""import android\.content""").containsMatchIn(src))
        assertFalse(Regex("""\bContext\b""").containsMatchIn(src.lines().filterNot { it.trim().startsWith("*") }.joinToString("\n")))
    }

    @Test
    fun theCameraIsAskedForInsideTheExperimentAndNowhereElse() {
        val asking = File(appFile("src/main/java/com/fotoxplorr/app").path).walkTopDown()
            .filter { it.extension == "kt" && "Manifest.permission.CAMERA" in it.readText() }
            .map { it.name }.toSet()
        assertEquals(setOf("ChromaScreen.kt"), asking)
    }

    @Test
    fun theScreenAsksOnOpenAndNotAtAppStart() {
        // The activity (the app start) never mentions the camera permission.
        val activity = appFile("src/main/java/com/fotoxplorr/app/FotoXplorrActivity.kt").readText()
        assertFalse("CAMERA" in activity)
    }

    @Test
    fun theCameraPermissionIsDeclaredOnceAndEveryCameraFeatureIsOptional() {
        val manifest = appFile("src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("""android\.permission\.CAMERA""").findAll(manifest).count())
        for (feature in listOf("camera.any", "camera", "camera.autofocus")) {
            val m = Regex("""<uses-feature\s+android:name="android\.hardware\.$feature"\s+android:required="false"\s*/>""").find(manifest)
            assertNotNull("android.hardware.$feature must be declared required=false", m)
        }
    }

    @Test
    fun noAudioPermissionIsAdded() {
        val manifest = appFile("src/main/AndroidManifest.xml").readText()
        assertTrue("RECORD_AUDIO" !in manifest)
    }
}
