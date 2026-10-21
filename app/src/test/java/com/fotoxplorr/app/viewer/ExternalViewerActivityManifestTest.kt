package com.fotoxplorr.app.viewer

import java.io.File
import org.junit.Assert.assertFalse
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

    @Test
    fun `every data element of ExternalViewerActivity names the content scheme`() {
        // A bare android:mimeType matches file:// as well, and a file:// Uri would have this app
        // read any path it can with its own permissions.
        val dataElements = DATA_ELEMENT.findAll(declaredActivityBlock()).map { it.value }.toList()
        assertTrue("ExternalViewerActivity declares no <data> element", dataElements.isNotEmpty())
        dataElements.forEach { element ->
            assertTrue(
                "<data> without android:scheme=\"content\": $element",
                CONTENT_SCHEME.containsMatchIn(element),
            )
        }
    }

    @Test
    fun `FotoXplorrActivity declares no VIEW action so ExternalViewerActivity is the single VIEW target`() {
        val block = fotoXplorrActivityBlock()
        assertFalse(
            "FotoXplorrActivity must not answer ACTION_VIEW: the chooser would list the app twice and a " +
                "file:// Uri could reach the viewer.",
            ACTION_VIEW.containsMatchIn(block),
        )
        assertFalse("FotoXplorrActivity must not be BROWSABLE", BROWSABLE.containsMatchIn(block))
    }

    @Test
    fun `FotoXplorrActivity keeps receiving EDIT and SEND for image and video only`() {
        val block = fotoXplorrActivityBlock()
        assertTrue("missing the ACTION_EDIT filter", ACTION_EDIT.containsMatchIn(block))
        assertTrue("missing the ACTION_SEND filter", ACTION_SEND.containsMatchIn(block))
        assertFalse("audio is not a product surface any more", Regex("""audio/""").containsMatchIn(block))
    }

    @Test
    fun `ExternalViewerActivity supports picture-in-picture`() {
        // VideoPlayer offers a PiP button and arms auto-enter wherever it is hosted. Without this
        // attribute the button silently did nothing here and auto-enter was ignored.
        assertTrue(
            "ExternalViewerActivity must declare android:supportsPictureInPicture=\"true\"",
            SUPPORTS_PIP.containsMatchIn(declaredActivityBlock()),
        )
    }

    private fun declaredActivityBlock(): String {
        val xml = manifestXml().readText()
        return EXTERNAL_VIEWER_ACTIVITY_BLOCK.find(xml)?.value
            ?: error("No .viewer.ExternalViewerActivity <activity> found in AndroidManifest.xml")
    }

    private fun fotoXplorrActivityBlock(): String =
        FOTO_XPLORR_ACTIVITY_BLOCK.find(manifestXml().readText())?.value
            ?: error("No .FotoXplorrActivity <activity> found in AndroidManifest.xml")

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
        val FOTO_XPLORR_ACTIVITY_BLOCK = Regex(
            """<activity[^>]*android:name\s*=\s*"\.FotoXplorrActivity"[^>]*>.*?</activity>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val DATA_ELEMENT = Regex("""<data\b[^>]*/>""")
        val CONTENT_SCHEME = Regex("""android:scheme\s*=\s*"content"""")
        val ACTION_EDIT = Regex("""<action\s+android:name\s*=\s*"android\.intent\.action\.EDIT"\s*/>""")
        val ACTION_SEND = Regex("""<action\s+android:name\s*=\s*"android\.intent\.action\.SEND"\s*/>""")
        val BROWSABLE = Regex("""android\.intent\.category\.BROWSABLE""")
        val SUPPORTS_PIP = Regex("""android:supportsPictureInPicture\s*=\s*"true"""")
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
