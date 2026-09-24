package com.fotoxplorr.app.picker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhotoPickerActivity] (P0-20) is only reachable at all if its manifest declaration actually
 * routes `ACTION_GET_CONTENT`/`ACTION_PICK` to it -- modelled on
 * [com.fotoxplorr.app.viewer.ExternalViewerActivityManifestTest], a raw-text regex over the real
 * file that ships in the APK rather than an XML parse that could tolerate a declaration the
 * manifest merger itself would reject.
 */
class PhotoPickerActivityManifestTest {

    @Test
    fun `PhotoPickerActivity is exported with GET_CONTENT and PICK filters covering image and video`() {
        val activityBlock = declaredActivityBlock()

        assertTrue(
            "PhotoPickerActivity must be android:exported=\"true\" -- another app cannot launch " +
                "it to pick media otherwise, and Android 12+ refuses to even install a manifest " +
                "with an intent-filtered activity left unexported.",
            EXPORTED_TRUE.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <action android:name=\"android.intent.action.GET_CONTENT\" />",
            ACTION_GET_CONTENT.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <action android:name=\"android.intent.action.PICK\" />",
            ACTION_PICK.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <category android:name=\"android.intent.category.DEFAULT\" />",
            CATEGORY_DEFAULT.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing <category android:name=\"android.intent.category.OPENABLE\" /> on the " +
                "GET_CONTENT filter",
            CATEGORY_OPENABLE.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing a <data android:mimeType=\"image/*\" /> entry",
            IMAGE_DATA.containsMatchIn(activityBlock),
        )
        assertTrue(
            "missing a <data android:mimeType=\"video/*\" /> entry",
            VIDEO_DATA.containsMatchIn(activityBlock),
        )
    }

    private fun declaredActivityBlock(): String {
        val xml = manifestXml().readText()
        return PHOTO_PICKER_ACTIVITY_BLOCK.find(xml)?.value
            ?: error("No .picker.PhotoPickerActivity <activity> found in AndroidManifest.xml")
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
        // DOT_MATCHES_ALL so both intent-filters between the opening tag and </activity> are
        // captured, not just whatever precedes the first newline.
        val PHOTO_PICKER_ACTIVITY_BLOCK = Regex(
            """<activity[^>]*android:name\s*=\s*"\.picker\.PhotoPickerActivity"[^>]*>.*?</activity>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val EXPORTED_TRUE = Regex("""android:exported\s*=\s*"true"""")
        val ACTION_GET_CONTENT = Regex(
            """<action\s+android:name\s*=\s*"android\.intent\.action\.GET_CONTENT"\s*/>""",
        )
        val ACTION_PICK = Regex("""<action\s+android:name\s*=\s*"android\.intent\.action\.PICK"\s*/>""")
        val CATEGORY_DEFAULT = Regex(
            """<category\s+android:name\s*=\s*"android\.intent\.category\.DEFAULT"\s*/>""",
        )
        val CATEGORY_OPENABLE = Regex(
            """<category\s+android:name\s*=\s*"android\.intent\.category\.OPENABLE"\s*/>""",
        )
        val IMAGE_DATA = Regex("""<data\s+android:mimeType\s*=\s*"image/\*"\s*/>""")
        val VIDEO_DATA = Regex("""<data\s+android:mimeType\s*=\s*"video/\*"\s*/>""")
    }
}
