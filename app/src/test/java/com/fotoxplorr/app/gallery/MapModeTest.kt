package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.NavRailPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's Map word and the bottom-left disc: one definition of what they do, held for the
 * pull-out room's rail and the pinned panel's alike.
 */
class MapModeTest {

    @Test
    fun `the rail lists every destination in order with Map added before Places`() {
        val words = railWords()
        val destinations = HyleDestination.entries.map { it.name to it.label }
        assertEquals("Map is additive", destinations.size + 1, words.size)
        assertEquals(destinations, words.filter { it.first != MAP_RAIL_ID })
        assertEquals(
            HyleDestination.PLACES.name,
            words[words.indexOfFirst { it.first == MAP_RAIL_ID } + 1].first,
        )
        assertEquals(MAP_RAIL_LABEL, words.first { it.first == MAP_RAIL_ID }.second)
    }

    @Test
    fun `the map word cannot be mistaken for a destination`() {
        assertTrue(HyleDestination.entries.none { it.name == MAP_RAIL_ID })
    }

    @Test
    fun `choosing Map keeps the destination and the route`() {
        val choice = railChoice(MAP_RAIL_ID, HyleDestination.VIDEOS)
        assertTrue(choice.mapOpen)
        assertEquals(HyleDestination.VIDEOS, choice.destination)
        assertFalse("the map shows the album you were in", choice.resetsRoute)
    }

    @Test
    fun `choosing Map from Places or Protected falls back to Photos`() {
        assertEquals(HyleDestination.PHOTOS, railChoice(MAP_RAIL_ID, HyleDestination.PLACES).destination)
        assertEquals(HyleDestination.PHOTOS, railChoice(MAP_RAIL_ID, HyleDestination.PROTECTED).destination)
        assertTrue(railChoice(MAP_RAIL_ID, HyleDestination.PROTECTED).mapOpen)
    }

    @Test
    fun `choosing any real word leaves the map and goes to that destination's root`() {
        HyleDestination.entries.forEach { destination ->
            val choice = railChoice(destination.name, HyleDestination.PHOTOS)
            assertFalse(destination.name, choice.mapOpen)
            assertEquals(destination, choice.destination)
            assertTrue(destination.name, choice.resetsRoute)
        }
    }

    @Test
    fun `the selected word is Map only while the map is showing`() {
        assertEquals(MAP_RAIL_ID, railSelectedId(mapOpen = true, destination = HyleDestination.PHOTOS))
        assertEquals(HyleDestination.PHOTOS.name, railSelectedId(mapOpen = false, destination = HyleDestination.PHOTOS))
    }

    @Test
    fun `the selected id is always a word the rail has`() {
        val ids = railWords().map { it.first }.toSet()
        HyleDestination.entries.forEach { destination ->
            assertTrue(railSelectedId(true, destination) in ids)
            assertTrue(railSelectedId(false, destination) in ids)
        }
    }

    @Test
    fun `the disc and the range are offered everywhere there are photos and nowhere else`() {
        HyleDestination.entries.forEach { destination ->
            val photos = destination != HyleDestination.PLACES && destination != HyleDestination.PROTECTED
            assertEquals(destination.name, photos, mapToggleOffered(destination))
            assertEquals(destination.name, photos, timeRangeApplies(destination))
        }
    }

    @Test
    fun `things pinned to the bottom left clear the rail on a wide window`() {
        assertEquals(0, railInsetDp(NavRailPresentation.PULL_OUT))
        assertEquals(56, railInsetDp(NavRailPresentation.COLLAPSED))
        assertEquals(260, railInsetDp(NavRailPresentation.PINNED))
    }
}
