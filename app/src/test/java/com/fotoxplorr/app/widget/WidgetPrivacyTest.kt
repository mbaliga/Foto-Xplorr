package com.fotoxplorr.app.widget

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.widget.WidgetFixtures.asset
import com.fotoxplorr.app.widget.WidgetFixtures.folderKey
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen is a public surface. Every picture a widget draws, and every picture a widget tap
 * opens, goes through `browsableAssets(...)` with no protected folder unlocked and sensitive items
 * hidden, whatever the app session or the in-app preference say (docs/TRAPS.md #33,
 * docs/widgets.md). These tests pin both arguments and the behaviour they produce.
 */
class WidgetPrivacyTest {

    @Test
    fun `the widget path calls browsableAssets with no unlocked folders and hideSensitive true`() {
        var seenUnlocked: Set<String>? = null
        var seenHideSensitive: Boolean? = null
        val locked = setOf("some-folder")
        WidgetPrivacy.visibleAssets(
            assets = listOf(asset(1)),
            archivedIds = emptySet(),
            sensitiveIds = emptySet(),
            lockedFolders = locked,
        ) { assets, _, _, lockedFolders, unlockedFolders, hideSensitive ->
            assertEquals(locked, lockedFolders)
            seenUnlocked = unlockedFolders
            seenHideSensitive = hideSensitive
            assets
        }
        assertEquals(emptySet<String>(), seenUnlocked)
        assertEquals(true, seenHideSensitive)
        assertTrue(WidgetPrivacy.UNLOCKED_FOLDERS.isEmpty())
        assertTrue(WidgetPrivacy.HIDE_SENSITIVE)
    }

    @Test
    fun `protected, sensitive, archived and trashed items never reach a widget`() {
        val shown = asset(1)
        val sensitive = asset(2)
        val archived = asset(3)
        val trashed = asset(4, trashed = true)
        val protectedItem = asset(5, folder = "Private")
        val visible = WidgetPrivacy.visibleAssets(
            assets = listOf(shown, sensitive, archived, trashed, protectedItem),
            archivedIds = setOf(archived.id),
            sensitiveIds = setOf(sensitive.id),
            lockedFolders = setOf(folderKey(protectedItem)),
        )
        assertEquals(listOf(shown), visible)
    }

    @Test
    fun `every builder draws only from the filtered list`() {
        val shown = asset(1)
        val sensitive = asset(2)
        val protectedItem = asset(3, folder = "Private")
        val library = WidgetFixtures.library(
            assets = listOf(shown, sensitive, protectedItem),
            favoriteIds = setOf(1, 2, 3),
            sensitiveIds = setOf(2),
            lockedFolders = setOf(folderKey(protectedItem)),
            collections = listOf(WidgetFixtures.collection("c", "Trip", setOf(1, 2, 3))),
        )
        val drawn = buildList<MediaAsset> {
            repeat(400) { day ->
                (favouriteOfTheDay(library, day.toLong()) as WidgetState.Ready).content.asset.let(::add)
            }
            addAll((recentItems(library) as WidgetState.Ready).content.items)
            addAll((collectionRows(library) as WidgetState.Ready).content.rows.flatMap { it.members })
        }
        assertEquals(setOf(shown), drawn.toSet())
        val counts = (libraryCounts(library) as WidgetState.Ready).content
        assertEquals(1, counts.total)
        assertEquals(1, counts.favourites)
    }

    @Test
    fun `a widget tap re-checks the photo and opens nothing that has since been hidden`() {
        val shown = asset(1)
        val nowSensitive = asset(2)
        val nowProtected = asset(3, folder = "Private")
        val assets = listOf(shown, nowSensitive, nowProtected)
        fun open(id: Long) = resolveWidgetOpen(
            request = WidgetOpenRequest(mediaId = id, runIds = listOf(1, 2, 3)),
            assets = assets,
            archivedIds = emptySet(),
            sensitiveIds = setOf(MediaId(2)),
            lockedFolders = setOf(folderKey(nowProtected)),
            identityIds = emptySet(),
        )
        assertNull(open(2))
        assertNull(open(3))
        assertNull(open(99))
        assertNull(open(WidgetLaunch.NO_MEDIA_ID))
        val opened = open(1)!!
        assertEquals(shown, opened.selected)
        // The run keeps only what may still be shown.
        assertEquals(listOf(shown), opened.run)
    }

    @Test
    fun `a widget tap keeps the run in the widget's order and falls back to the one photo`() {
        val a = asset(1)
        val b = asset(2)
        val c = asset(3)
        val opened = resolveWidgetOpen(WidgetOpenRequest(2, listOf(3, 2, 1, 2)), listOf(a, b, c), emptySet(), emptySet(), emptySet(), emptySet())!!
        assertEquals(listOf(c, b, a), opened.run)
        assertEquals(b, opened.selected)
        val alone = resolveWidgetOpen(WidgetOpenRequest(2, emptyList()), listOf(a, b, c), emptySet(), emptySet(), emptySet(), emptySet())!!
        assertEquals(listOf(b), alone.run)
    }

    @Test
    fun `identity documents and screenshots are never drawn or opened, though they are counted`() {
        val shown = asset(1)
        val passport = asset(2)
        val screenshot = asset(3, folder = "Screenshots")
        val library = WidgetFixtures.library(
            assets = listOf(shown, passport, screenshot),
            favoriteIds = setOf(1, 2, 3),
            identityIds = setOf(2),
            collections = listOf(WidgetFixtures.collection("c", "Visa", setOf(1, 2, 3))),
        )
        assertEquals(listOf(shown), library.pictured)
        val drawn = buildList<MediaAsset> {
            repeat(400) { day ->
                (favouriteOfTheDay(library, day.toLong()) as WidgetState.Ready).content.asset.let(::add)
            }
            addAll((recentItems(library) as WidgetState.Ready).content.items)
            addAll((collectionRows(library) as WidgetState.Ready).content.rows.flatMap { it.members })
        }
        assertEquals(setOf(shown), drawn.toSet())
        // A number names nothing: all three still count.
        assertEquals(3, (libraryCounts(library) as WidgetState.Ready).content.total)
        assertEquals(1, (libraryCounts(library) as WidgetState.Ready).content.screenshots)

        fun open(id: Long) = resolveWidgetOpen(WidgetOpenRequest(id, listOf(1, 2, 3)), listOf(shown, passport, screenshot), emptySet(), emptySet(), emptySet(), setOf(MediaId(2)))
        assertNull(open(2))
        assertNull(open(3))
        assertEquals(listOf(shown), open(1)!!.run)
    }

    @Test
    fun `a library of only identity documents and screenshots reads like an empty one`() {
        val library = WidgetFixtures.library(
            assets = listOf(asset(1), asset(2, folder = "Screenshots")),
            favoriteIds = setOf(1, 2),
            identityIds = setOf(1),
        )
        assertEquals(WidgetState.Withheld, favouriteOfTheDay(library, 0))
        assertEquals(WidgetState.Withheld, recentItems(library))
    }
}
