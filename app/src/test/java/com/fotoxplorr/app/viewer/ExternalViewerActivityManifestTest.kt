package com.fotoxplorr.app.viewer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExternalViewerActivity]'s whole reason to exist is the `AndroidManifest.xml` declaration
 * itself (P0-18) -- no amount of correct Kotlin matters if the manifest never routes an incoming
 * `ACTION_VIEW` to it, or routes it with the wrong `exported`/mime coverage. Modelled on
 * [com.fotoxplorr.app.share.FileProviderAuthorityTest]: a raw-text regex over the real file that
 * ships in the APK, not an XML parse that could tolerate a malformed declaration the merger
 * itself would reject.
 */
class ExternalViewerActivityManifestTest {

    @Test
    fun `ExternalViewerActivity is exported with a VIEW filter covering image and video`() {
        val activityBlock = declaredActivityBlock()

        assertTrue(
            "ExternalViewerActivity must be android:exported=\"true\" -- it is this app's only " +
                "non-launcher entry point, and Android 12+ refuses to even install a manifest " +
                "with an intent-filtered activity left unexported.",
            EXPORTED_TRUE.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <action android:name=\"android.intent.action.VIEW\" />",
            ACTION_VIEW.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <category android:name=\"android.intent.category.DEFAULT\" />",
            CATEGORY_DEFAULT.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing a <data android:scheme=\"content\" android:mimeType=\"image/*\" /> entry",
            IMAGE_DATA.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing a <data android:scheme=\"content\" android:mimeType=\"video/*\" /> entry",
            VIDEO_DATA.containsMatchIn(activityBlock),
        )
    }

    private fun declaredActivityBlock(): String {
        val xml = manifestXml().readText()
        return EXTERNAL_VIEWER_ACTIVITY_BLOCK.find(xml)?.value
            ?: error("No .viewer.ExternalViewerActivity <activity> found in AndroidManifest.xml")
    }

    private fun manifestXml(): File {
        // Unit tests run with the module directory as the working directory, but that is a
        // convention rather than a guarantee, so try the repository root as well before failing.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found; looked in ${candidates.map { it.absolutePath }}")
    }

    private companion object {
        // Deliberately over the raw file rather than an XML parse -- see this class's own KDoc.
        // DOT_MATCHES_ALL so the whole <intent-filter> between the opening tag and </activity>
        // is captured, not just whatever precedes the first newline.
        val EXTERNAL_VIEWER_ACTIVITY_BLOCK = Regex(
            """<activity[^>]*android:name\s*=\s*"\.viewer\.ExternalViewerActivity"[^>]*>.*?</activity>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val EXPORTED_TRUE = Regex("""android:exported\s*=\s*"true"""")
        val ACTION_VIEW = Regex("""<action\s+android:name\s*=\s*"android\.intent\.action\.VIEW"\s*/>""")
        val CATEGORY_DEFAULT = Regex(
            """<category\s+android:name\s*=\s*"android\.intent\.category\.DEFAULT"\s*/>""",
        )
        val IMAGE_DATA = Regex(
            """<data\s+android:scheme\s*=\s*"content"\s+android:mimeType\s*=\s*"image/\*"\s*/>""",
        )
        val VIDEO_DATA = Regex(
            """<data\s+android:scheme\s*=\s*"content"\s+android:mimeType\s*=\s*"video/\*"\s*/>""",
        )
    }
}
