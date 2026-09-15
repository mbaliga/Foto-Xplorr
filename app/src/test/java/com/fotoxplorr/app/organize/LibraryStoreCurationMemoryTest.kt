package com.fotoxplorr.app.organize

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one promise the auto-curation feature has to keep: a person's "no" sticks.
 *
 * Before this memory existed, removing an auto tag or clearing a machine caption was a reprieve
 * of at most one background pass -- the next run re-derived the same answer from the same
 * recognition data and put it straight back. That is not a bug anyone reports; they just stop
 * using the chips. So each of these pins one specific way the store must remember, and one
 * specific way it must NOT over-remember (a typed tag removed says nothing about the auto-tagger;
 * a typed caption cleared leaves the slot honestly empty).
 *
 * Robolectric rather than a pure-JVM fake, because the thing under test IS the SharedPreferences
 * read-modify-write choreography -- a fake that skipped it would pass while the real store lost
 * writes. Each test gets a fresh preferences file from Robolectric and builds its own instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStoreCurationMemoryTest {

    private fun store() = LibraryStore(RuntimeEnvironment.getApplication())

    @Test
    fun `an auto tag a person removed is never re-applied`() {
        val library = store()
        val photo = MediaId(1L)
        library.addAutoTags(photo, setOf("beach"))
        assertEquals(setOf("beach"), library.observe().value.autoTagsFor(photo))

        library.removeTag(setOf(photo), "beach")
        assertEquals(setOf("beach"), library.observe().value.rejectedAutoTagsFor(photo))

        library.addAutoTags(photo, setOf("beach"))
        assertTrue("re-applied after the person removed it", library.observe().value.tagsFor(photo).isEmpty())
    }

    @Test
    fun `removing a tag a person typed records no rejection`() {
        val library = store()
        val photo = MediaId(1L)
        library.addTag(setOf(photo), "beach")
        library.removeTag(setOf(photo), "beach")
        assertTrue(library.observe().value.rejectedAutoTagsFor(photo).isEmpty())

        // So the auto-tagger is still free to propose it: the person said nothing about it.
        library.addAutoTags(photo, setOf("beach"))
        assertEquals(setOf("beach"), library.observe().value.autoTagsFor(photo))
    }

    @Test
    fun `typing a rejected tag back by hand withdraws the rejection and makes it theirs`() {
        val library = store()
        val photo = MediaId(1L)
        library.addAutoTags(photo, setOf("beach"))
        library.removeTag(setOf(photo), "beach")
        library.addTag(setOf(photo), "beach")

        val state = library.observe().value
        assertEquals(setOf("beach"), state.tagsFor(photo))
        assertTrue("a tag the person typed must not read as auto", state.autoTagsFor(photo).isEmpty())
        assertTrue(state.rejectedAutoTagsFor(photo).isEmpty())
    }

    @Test
    fun `bulk-clearing auto tags is remembered as a rejection of each`() {
        val library = store()
        val photo = MediaId(1L)
        library.addAutoTags(photo, setOf("beach", "sky"))
        library.clearAutoTags()
        assertEquals(setOf("beach", "sky"), library.observe().value.rejectedAutoTagsFor(photo))
        library.addAutoTags(photo, setOf("beach", "sky"))
        assertTrue(library.observe().value.tagsFor(photo).isEmpty())
    }

    @Test
    fun `a machine caption a person cleared is not written again`() {
        val library = store()
        val photo = MediaId(2L)
        assertTrue(library.applyMachineCaption(photo, "A dog on a beach"))
        library.setCaption(photo, "")
        assertTrue(library.observe().value.isMachineCaptionSuppressed(photo))

        assertFalse(library.applyMachineCaption(photo, "A dog on a beach"))
        assertEquals("", library.observe().value.captionFor(photo))
    }

    @Test
    fun `clearing a caption the person typed leaves the slot fillable`() {
        val library = store()
        val photo = MediaId(2L)
        library.setCaption(photo, "my own words")
        library.setCaption(photo, "")
        assertFalse(library.observe().value.isMachineCaptionSuppressed(photo))
        assertTrue(library.applyMachineCaption(photo, "A dog on a beach"))
    }

    @Test
    fun `a batch commit honours both memories, never touches typed words, and is idempotent`() {
        val library = store()
        val rejectedOn = MediaId(1L)
        val suppressedOn = MediaId(2L)
        val typedOn = MediaId(3L)
        val fresh = MediaId(4L)

        library.addAutoTags(rejectedOn, setOf("beach"))
        library.removeTag(setOf(rejectedOn), "beach")
        library.applyMachineCaption(suppressedOn, "old machine sentence")
        library.setCaption(suppressedOn, "")
        library.setCaption(typedOn, "mine")

        val changed = library.applyCuration(
            autoTags = mapOf(
                rejectedOn to setOf("beach", "sky"),
                fresh to setOf("dog"),
            ),
            captions = mapOf(
                suppressedOn to "new machine sentence",
                typedOn to "machine sentence",
                fresh to "A dog",
            ),
        )
        // rejectedOn gains only "sky"; fresh gains "dog" and a caption. suppressedOn and typedOn
        // are untouched, so exactly two photos changed.
        assertEquals(2, changed)

        val state = library.observe().value
        assertEquals(setOf("sky"), state.tagsFor(rejectedOn))
        assertEquals("", state.captionFor(suppressedOn))
        assertEquals("mine", state.captionFor(typedOn))
        assertFalse(state.isMachineCaption(typedOn))
        assertEquals(setOf("dog"), state.autoTagsFor(fresh))
        assertEquals("A dog", state.captionFor(fresh))
        assertTrue(state.isMachineCaption(fresh))

        // Same proposal again: nothing to do, and the count says so.
        assertEquals(
            0,
            library.applyCuration(
                autoTags = mapOf(rejectedOn to setOf("beach", "sky"), fresh to setOf("dog")),
                captions = mapOf(fresh to "A dog"),
            ),
        )
    }

    @Test
    fun `two photos gaining the same tag in one batch both keep it`() {
        val library = store()
        val a = MediaId(1L)
        val b = MediaId(2L)
        library.applyCuration(mapOf(a to setOf("sky"), b to setOf("sky")), emptyMap())
        val state = library.observe().value
        assertEquals(setOf("sky"), state.autoTagsFor(a))
        assertEquals(setOf("sky"), state.autoTagsFor(b))
    }

    @Test
    fun `memories are pruned with the photos they were about`() {
        val library = store()
        val gone = MediaId(1L)
        val kept = MediaId(2L)
        library.addAutoTags(gone, setOf("beach"))
        library.removeTag(setOf(gone), "beach")
        library.applyMachineCaption(gone, "x")
        library.setCaption(gone, "")
        library.addAutoTags(kept, setOf("sky"))

        library.removeMissingMedia(setOf(kept))

        val state = library.observe().value
        assertTrue(state.rejectedAutoTagsFor(gone).isEmpty())
        assertFalse(state.isMachineCaptionSuppressed(gone))
        assertEquals(setOf("sky"), state.autoTagsFor(kept))
    }

    @Test
    fun `memories survive an export and import`() {
        val library = store()
        val photo = MediaId(1L)
        library.addAutoTags(photo, setOf("beach"))
        library.removeTag(setOf(photo), "beach")
        library.applyMachineCaption(photo, "x")
        library.setCaption(photo, "")

        val backup = library.exportJson()
        // importJson clears the file and rewrites it from the backup, so importing into the same
        // store is the round trip a restore performs.
        assertTrue(library.importJson(backup).isSuccess)

        val state = library.observe().value
        assertEquals(setOf("beach"), state.rejectedAutoTagsFor(photo))
        assertTrue(state.isMachineCaptionSuppressed(photo))
        assertTrue(library.applyMachineCaption(photo, "x").not())
        library.addAutoTags(photo, setOf("beach"))
        assertTrue(library.observe().value.tagsFor(photo).isEmpty())
    }
}
