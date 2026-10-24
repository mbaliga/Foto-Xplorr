package com.fotoxplorr.app.experimental

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Experimental host's decisions, with no Android: which entry is open, what is saved, in what order. */
class ExperimentalHostTest {

    private fun entry(
        id: String,
        group: ExperimentGroup = ExperimentGroup.EXPLORE,
        restorable: Boolean = true,
        available: Boolean = true,
    ) = ExperimentEntry(
        id = id,
        title = id,
        blurb = id,
        group = group,
        icon = Icons.Outlined.Science,
        restorable = restorable,
        available = { available },
    ) { _, _ -> }

    @Test
    fun `groups come in Make, Explore, Setup order and empty groups are left out`() {
        val setup = entry("s", ExperimentGroup.SETUP)
        val explore1 = entry("e1", ExperimentGroup.EXPLORE)
        val explore2 = entry("e2", ExperimentGroup.EXPLORE)
        val grouped = groupedEntries(listOf(setup, explore1, explore2))
        assertEquals(listOf(ExperimentGroup.EXPLORE, ExperimentGroup.SETUP), grouped.map { it.first })
        assertEquals(listOf("e1", "e2"), grouped[0].second.map { it.id })

        val withMake = groupedEntries(listOf(setup, explore1, entry("m", ExperimentGroup.MAKE)))
        assertEquals(
            listOf(ExperimentGroup.MAKE, ExperimentGroup.EXPLORE, ExperimentGroup.SETUP),
            withMake.map { it.first },
        )
        assertTrue(groupedEntries(emptyList()).isEmpty())
    }

    @Test
    fun `a saved id opens its entry`() {
        val a = entry("a")
        assertSame(a, entryToShow(listOf(entry("z"), a), "a") { true })
    }

    @Test
    fun `no id and an unknown id both give the hub`() {
        val entries = listOf(entry("a"))
        assertNull(entryToShow(entries, null) { true })
        assertNull(entryToShow(entries, "removed-in-a-later-version") { true })
        assertNull(entryToShow(emptyList(), "a") { true })
    }

    @Test
    fun `an entry the device cannot run gives the hub`() {
        val entries = listOf(entry("a", available = false), entry("b"))
        assertNull(entryToShow(entries, "a") { false })
        assertSame(entries[1], entryToShow(entries, "b") { true })
    }

    @Test
    fun `the open id is saved with the process it was saved in`() {
        assertNull(idToSave(null, "p1"))
        val saved = idToSave("camera", "p1")
        assertEquals("camera", idToRestore(listOf(entry("camera", restorable = false)), saved, "p1"))
    }

    @Test
    fun `in the same process every open id comes back`() {
        val entries = listOf(entry("keep"), entry("camera", restorable = false))
        assertEquals("keep", idToRestore(entries, idToSave("keep", "p1"), "p1"))
        assertEquals("camera", idToRestore(entries, idToSave("camera", "p1"), "p1"))
    }

    @Test
    fun `after the process was killed only a restorable entry comes back`() {
        val entries = listOf(entry("keep"), entry("camera", restorable = false))
        assertEquals("keep", idToRestore(entries, idToSave("keep", "p1"), "p2"))
        assertNull(idToRestore(entries, idToSave("camera", "p1"), "p2"))
        assertNull(idToRestore(entries, idToSave("unknown", "p1"), "p2"))
        assertNull(idToRestore(entries, null, "p2"))
    }

    @Test
    fun `an id saved without a process is treated as from an earlier process`() {
        val entries = listOf(entry("keep"), entry("camera", restorable = false))
        assertEquals("keep", idToRestore(entries, "keep", "p1"))
        assertNull(idToRestore(entries, "camera", "p1"))
    }

    @Test
    fun `duplicate ids are found`() {
        assertTrue(duplicateIds(listOf(entry("a"), entry("b"))).isEmpty())
        assertEquals(listOf("a"), duplicateIds(listOf(entry("a"), entry("b"), entry("a"))))
    }

    @Test
    fun `the location read starts once, only when nothing is scanned and nothing is running`() {
        assertTrue(shouldStartLocationIndex(GeoIndexState(), alreadyAsked = false))
        assertFalse(shouldStartLocationIndex(GeoIndexState(), alreadyAsked = true))
        assertFalse(shouldStartLocationIndex(GeoIndexState(isIndexing = true), alreadyAsked = false))
        assertFalse(shouldStartLocationIndex(GeoIndexState(scannedCount = 3, totalCount = 3), alreadyAsked = false))
    }

    @Test
    fun `pixels may be read only for a photo the gallery shows now`() {
        val shown = setOf(MediaId(1), MediaId(2))
        assertTrue(mayReadPixels(MediaId(1), shown))
        assertFalse("a re-locked or archived photo is gone from the list", mayReadPixels(MediaId(3), shown))
        assertFalse(mayReadPixels(MediaId(1), emptySet()))
    }

    @Test
    fun `an edge zone is hit on every side and missed in the middle`() {
        val w = 400f
        val h = 800f
        val zone = 60f
        assertTrue(inShellEdgeZone(10f, 400f, w, h, zone))
        assertTrue(inShellEdgeZone(395f, 400f, w, h, zone))
        assertTrue(inShellEdgeZone(200f, 10f, w, h, zone))
        assertTrue(inShellEdgeZone(200f, 790f, w, h, zone))
        assertFalse(inShellEdgeZone(200f, 400f, w, h, zone))
        assertFalse(inShellEdgeZone(60f, 60f, w, h, zone))
    }

    @Test
    fun `the built-in list has unique ids, the pinned ids and the Setup entry last`() {
        assertTrue(duplicateIds(builtInExperiments).isEmpty())
        // These are saved state and the "Open the app on"-style stable names: a rename is a break.
        assertEquals(
            listOf("aquarium", "wall-mockup", "panorama", "similarity", "wall", "preview", "compass", "ai"),
            builtInExperiments.map { it.id },
        )
        assertEquals("ai", builtInExperiments.last().id)
        assertEquals(ExperimentGroup.SETUP, builtInExperiments.last().group)
        assertEquals("Compass view", builtInExperiments.first { it.id == "compass" }.title)
        assertEquals(
            listOf("aquarium", "wall-mockup", "panorama"),
            builtInExperiments.filter { it.group == ExperimentGroup.MAKE }.map { it.id },
        )
        assertTrue("the old street and photo map cards are gone", builtInExperiments.none { it.title.contains("map", ignoreCase = true) })
    }

    @Test
    fun `the panorama entry is registered in Make with its own copy, a stable id, and comes back after a kill`() {
        val entry = builtInExperiments.single { it.id == "panorama" }
        assertEquals("Stitch a panorama", entry.title)
        assertEquals(com.fotoxplorr.app.pano.PanoCopy.TITLE, entry.title)
        assertEquals("the blurb is the copy object's one sentence", com.fotoxplorr.app.pano.PanoCopy.BLURB, entry.blurb)
        assertEquals(ExperimentGroup.MAKE, entry.group)
        assertTrue("opening it starts no work and asks no permission", entry.restorable)
        assertEquals("panorama", idToRestore(builtInExperiments, idToSave("panorama", "before"), "after"))
    }

    @Test
    fun `an entry that reads the location on open is not reopened after the process was killed`() {
        // The compass starts a location read when it opens and asks for the location permission in
        // context (ExperimentEntry.restorable), so a restored id must not put the person back in it.
        assertEquals(listOf("compass"), builtInExperiments.filter { !it.restorable }.map { it.id })
        assertNull(idToRestore(builtInExperiments, idToSave("compass", "before"), "after"))
        assertEquals("aquarium", idToRestore(builtInExperiments, idToSave("aquarium", "before"), "after"))
        assertEquals("wall-mockup", idToRestore(builtInExperiments, idToSave("wall-mockup", "before"), "after"))
        // The same process (the viewer round trip, leaving the destination) keeps the compass.
        assertEquals("compass", idToRestore(builtInExperiments, idToSave("compass", "same"), "same"))
    }

    @Test
    fun `the hub's own words are one short sentence or phrase and the cards take them from the copy object`() {
        listOf(
            ExperimentalCopy.TITLE, ExperimentalCopy.HEADER, ExperimentalCopy.UNAVAILABLE,
            ExperimentalCopy.PRIVACY_TITLE, ExperimentalCopy.SIMILARITY_TITLE, ExperimentalCopy.WALL_TITLE,
            ExperimentalCopy.PREVIEW_TITLE, ExperimentalCopy.COMPASS_TITLE, ExperimentalCopy.AI_TITLE,
            ExperimentalCopy.BADGE_ON_DEVICE, ExperimentalCopy.BADGE_OPENGL, ExperimentalCopy.BADGE_TOUCH_DPAD,
            ExperimentalCopy.BADGE_KEYSTORE, ExperimentalCopy.BADGE_INDEXING, ExperimentalCopy.located(12),
        ).forEach { text ->
            assertTrue("'$text' is short", text.isNotBlank() && text == text.trim() && text.length <= 50)
            assertTrue("'$text' is at most one sentence", text.count { it == '.' } <= 1)
        }
        assertEquals("12 located", ExperimentalCopy.located(12))
    }

    @Test
    fun `every registered entry has a stable id, a title and a one-sentence blurb`() {
        val idShape = Regex("[a-z][a-z0-9-]*")
        builtInExperiments.forEach { e ->
            assertTrue("id '${e.id}' is lower-case words joined by hyphens", idShape.matches(e.id))
            assertTrue("${e.id} has a title", e.title.isNotBlank() && e.title == e.title.trim())
            assertTrue("${e.id} has a blurb", e.blurb.isNotBlank() && e.blurb == e.blurb.trim())
            assertTrue("${e.id} blurb is short: ${e.blurb}", e.blurb.length <= 80)
            assertTrue("${e.id} blurb is not a paragraph: ${e.blurb}", e.blurb.count { it == '.' } <= 1)
        }
        assertEquals(
            "titles are the cards' names and must not repeat",
            builtInExperiments.size,
            builtInExperiments.map { it.title }.toSet().size,
        )
        assertTrue(duplicateIds(builtInExperiments).isEmpty())
    }

    private fun photo(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "p$id.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = id,
        dateModifiedSeconds = id,
        width = 4,
        height = 4,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `with Blur sensitive on, photos flagged sensitive are not given to an experiment`() {
        val all = listOf(photo(3), photo(2), photo(1))
        val given = assetsForExperiments(all, sensitiveIds = setOf(MediaId(3)), blurSensitive = true)
        assertEquals("newest-first order is kept", listOf(2L, 1L), given.map { it.id.value })
    }

    @Test
    fun `with Blur sensitive off, or nothing flagged, the list is passed on as it is`() {
        val all = listOf(photo(2), photo(1))
        assertSame(all, assetsForExperiments(all, setOf(MediaId(2)), blurSensitive = false))
        assertSame(all, assetsForExperiments(all, emptySet(), blurSensitive = true))
    }
}
