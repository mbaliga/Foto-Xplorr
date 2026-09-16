package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure, framework-free half of [EditedCopyWriter]: string logic that needs no `Bitmap`,
 * `Canvas` or `ContentResolver` and therefore no Robolectric at all. See
 * [EditedCopyWriterPixelTest] for the alpha-compositing half, which does need real pixels.
 */
class EditedCopyWriterTest {

    // ---- safeRelativePath: bug #4, MediaStore's Images collection only accepts DCIM/ and
    // Pictures/ primaries; anything else makes insert() throw. ----

    @Test
    fun `a path already under DCIM or Pictures is kept as-is`() {
        assertEquals("DCIM/Camera/", safeRelativePath("DCIM/Camera/"))
        assertEquals("Pictures/Screenshots/", safeRelativePath("Pictures/Screenshots/"))
        assertEquals("Pictures/", safeRelativePath("Pictures/"))
    }

    @Test
    fun `a path MediaStore's Images collection would refuse falls back to a Foto Xplorr folder`() {
        assertEquals(FALLBACK_RELATIVE_PATH, safeRelativePath("Download/"))
        assertEquals(FALLBACK_RELATIVE_PATH, safeRelativePath("Documents/Scans/"))
        assertEquals(
            FALLBACK_RELATIVE_PATH,
            safeRelativePath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
        )
    }

    @Test
    fun `a missing or blank relative path also falls back rather than being sent through empty`() {
        assertEquals(FALLBACK_RELATIVE_PATH, safeRelativePath(null))
        assertEquals(FALLBACK_RELATIVE_PATH, safeRelativePath(""))
    }

    @Test
    fun `the primary segment check is case-insensitive`() {
        assertEquals("dcim/Camera/", safeRelativePath("dcim/Camera/"))
    }

    // ---- editedName ----

    @Test
    fun `an edited copy keeps the source stem and gets the chosen format's extension`() {
        assertEquals("sunset-edited.jpg", editedName("sunset.png", OutputFormat.JPEG))
        assertEquals("sunset-edited.webp", editedName("sunset.jpg", OutputFormat.WEBP))
    }

    @Test
    fun `re-editing an edit replaces the marker rather than stacking another one`() {
        assertEquals("sunset-edited.jpg", editedName("sunset-edited.jpg", OutputFormat.JPEG))
    }
}
