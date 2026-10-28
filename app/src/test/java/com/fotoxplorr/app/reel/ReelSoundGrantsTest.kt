package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Letting go of the right to read a sound file nothing uses any more, over a library in memory. Nothing here calls the system. */
class ReelSoundGrantsTest {
    private val library = MemoryReelLibrary()
    private val sounds = FakeSoundFiles()

    private fun withSound(uri: String?, name: String = "") = ReelScreenKit.stills(1).copy(name = name, soundtrack = uri?.let { ReelSoundtrack(it, 0L, 3_000L) })

    private fun release(vararg uris: String, open: ReelProject? = null, openId: String? = null) =
        ReelSoundGrants.releaseUnused(uris.toSet(), open, openId, library, sounds)

    @Test
    fun `a file no reel keeps is let go`() {
        library.put(withSound("content://docs/other"))
        assertEquals(setOf("content://docs/a"), release("content://docs/a"))
        assertEquals(listOf("content://docs/a"), sounds.released)
    }

    @Test
    fun `a file another saved reel lays under its pictures is kept`() {
        library.put(withSound("content://docs/a", "Beach"))
        assertTrue(release("content://docs/a").isEmpty())
        assertTrue(sounds.released.isEmpty())
    }

    @Test
    fun `a file the reel on screen lays is kept, and its own saved copy, which still has the old file, is not asked`() {
        val id = library.put(withSound("content://docs/a"))
        // The person has just replaced a with b; the file on disk still says a until the autosave.
        assertEquals(setOf("content://docs/a"), release("content://docs/a", open = withSound("content://docs/b"), openId = id))
        // Put back, it is kept.
        assertTrue(release("content://docs/a", open = withSound("content://docs/a"), openId = id).isEmpty())
    }

    @Test
    fun `only the files nobody keeps go, and a reel that cannot be read keeps nothing`() {
        library.put(withSound("content://docs/b"))
        library.put(withSound("content://docs/c"), readable = false)
        assertEquals(setOf("content://docs/a", "content://docs/c"), release("content://docs/a", "content://docs/b", "content://docs/c"))
        assertEquals(setOf("content://docs/a", "content://docs/c"), sounds.released.toSet())
    }

    @Test
    fun `nothing to let go of touches nothing`() {
        assertTrue(release().isEmpty())
        assertTrue(sounds.released.isEmpty())
    }
}
