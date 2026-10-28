package com.fotoxplorr.app.vision

import android.os.Build
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a corrected copy may be made: never of a photo marked sensitive (the copy cannot carry the
 * mark), and, from a locked folder, only where the copy stays in that folder (TRAPS #33).
 */
class CorrectedCopyPolicyTest {

    private val q = Build.VERSION_CODES.Q
    private val locked = setOf("path:dcim/private", "path:download")

    private fun allowed(
        relativePath: String?,
        id: Long = 7,
        sensitive: Set<Long> = emptySet(),
        sdk: Int = q,
    ) = colourVisionCopyAllowed(
        ColourVisionKit.asset(id = id, relativePath = relativePath),
        sensitiveIds = sensitive.map { MediaId(it) }.toSet(),
        lockedFolders = locked,
        sdk = sdk,
    )

    @Test
    fun `an ordinary photo may be copied`() {
        assertTrue(allowed("DCIM/Camera/"))
    }

    @Test
    fun `a photo marked sensitive is never copied, whichever photo is on screen`() {
        assertFalse(allowed("DCIM/Camera/", id = 7, sensitive = setOf(7)))
        assertTrue(allowed("DCIM/Camera/", id = 7, sensitive = setOf(8)))
    }

    @Test
    fun `a photo in a locked folder whose copy lands beside it may be copied`() {
        assertTrue(allowed("DCIM/Private/"))
    }

    @Test
    fun `a photo in a locked folder whose copy would land elsewhere is refused`() {
        // Download/ is not a folder MediaStore's Images collection accepts, so the editor's writer
        // falls back to Pictures/Fotoz/, which the lock does not cover.
        assertFalse(allowed("Download/"))
    }

    @Test
    fun `a locked folder before Android 10 is refused, because the copy goes to the default folder`() {
        assertFalse(allowed("DCIM/Private/", sdk = Build.VERSION_CODES.P))
    }

    @Test
    fun `a photo outside any locked folder may be copied wherever the copy lands`() {
        assertTrue(allowed("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertTrue(allowed("Documents/Scans/"))
        assertTrue(allowed(null))
    }

    @Test
    fun `the lock is matched the way the gallery matches it - slashes and case do not matter`() {
        assertFalse(allowed("/Download"))
        assertFalse(allowed("download/"))
        // The writer's own check is exact-case, so a lower-case DCIM would send the copy to the fallback folder.
        assertFalse(allowed("dcim/private"))
        assertTrue(allowed("DCIM/Private"))
    }
}
