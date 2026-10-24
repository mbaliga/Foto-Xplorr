package com.fotoxplorr.app.ui

import dev.aarso.cellshell.RoomEdge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room geography is one table, and the two screens that use rooms read it. These pin the table
 * itself (the owner's: details on top, actions below, view options at the side) and the seam both
 * screens build their shell slots through, so neither can quietly drift from the other again.
 */
class RoomGeographyTest {

    @Test
    fun `details are the top room, actions the bottom, view options the right and navigation the left`() {
        assertEquals(RoomEdge.TOP, RoomGeography.Details)
        assertEquals(RoomEdge.BOTTOM, RoomGeography.Actions)
        assertEquals(RoomEdge.RIGHT, RoomGeography.ViewOptions)
        assertEquals(RoomEdge.LEFT, RoomGeography.Navigation)
    }

    @Test
    fun `every kind has its own real edge and kindAt is the inverse of edgeFor`() {
        val edges = RoomKind.entries.map { RoomGeography.edgeFor(it) }
        assertEquals("two kinds share an edge", edges.size, edges.toSet().size)
        edges.forEach { assertNotEquals("a room cannot live at HOME", RoomEdge.HOME, it) }
        RoomKind.entries.forEach { assertEquals(it, RoomGeography.kindAt(RoomGeography.edgeFor(it))) }
        assertNull(RoomGeography.kindAt(RoomEdge.HOME))
    }

    @Test
    fun `roomSlots puts each kind on the edge the table names`() {
        val slots = roomSlots(navigation = "nav", details = "details", actions = "actions", viewOptions = "options")
        assertEquals("nav", slots.left)
        assertEquals("details", slots.top)
        assertEquals("actions", slots.bottom)
        assertEquals("options", slots.right)
    }

    @Test
    fun `a kind left out leaves its edge without a room`() {
        // The viewer has no navigation room: the shell must see a null left edge and refuse it.
        val slots = roomSlots<String>(details = "details", actions = "actions", viewOptions = "options")
        assertNull(slots.left)
        assertEquals("details", slots.top)
        val onlyDetails = roomSlots<String>(details = "details")
        assertNull(onlyDetails.right)
        assertNull(onlyDetails.bottom)
        assertNull(onlyDetails.left)
    }

    @Test
    fun `the viewer and the gallery build their slots through the table and name no edge themselves`() {
        // The drift this guards: the gallery and the viewer each choosing an edge per room. Both
        // screens must go through roomSlots, and neither may hand the shell a room by a literal
        // edge or open one by RoomEdge.TOP, RIGHT or BOTTOM.
        val main = File("src/main/java/com/fotoxplorr/app")
        listOf("viewer/ViewerScreen.kt", "gallery/GalleryScreen.kt").forEach { relative ->
            val file = File(main, relative)
            assertTrue("cannot find ${file.path} from ${File(".").absolutePath}", file.exists())
            val code = file.readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertTrue("$relative does not use roomSlots", "roomSlots" in code)
            listOf("RoomEdge.TOP", "RoomEdge.RIGHT", "RoomEdge.BOTTOM").forEach { literal ->
                assertTrue("$relative names $literal instead of RoomGeography", literal !in code)
            }
        }
    }

    @Test
    fun `the actions room is the staged one and its peek is exactly the strip`() {
        // The shell adds its own 72dp head band and the navigation bar; the peek is the usable
        // height below the band, so a second copy of the band here would show 72dp too much.
        assertEquals(QuickActionStripHeight, RoomGeography.ActionsPeek)
        assertEquals(RoomGeography.ActionsPeek, RoomGeography.peekFor(RoomKind.ACTIONS))
        // A strip whose targets are at least 48dp, and not so tall the peek is a third of a phone.
        assertTrue(QuickActionStripHeight >= QuickActionMinTarget)
        assertTrue(RoomGeography.ActionsPeek <= 200.dp)
    }

    @Test
    fun `no other room is staged`() {
        listOf(RoomKind.NAVIGATION, RoomKind.DETAILS, RoomKind.VIEW_OPTIONS).forEach {
            assertNull("$it has a peek", RoomGeography.peekFor(it))
        }
    }

    @Test
    fun `the staged room lives on the one edge the shell can stage`() {
        // SpatialShell stages only its bottom axis; if actions ever leave it the peek must go
        // with it (peekFor answers null) rather than be handed to an edge that cannot honour it.
        assertEquals(RoomEdge.BOTTOM, RoomGeography.Actions)
    }
}
