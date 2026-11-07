package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Compose-free half of the hub's wiring: [HubSession] over [HubModel]. */
class HubRingSessionTest {

    class FakeStore(var memory: HubMemory = HubMemory.EMPTY) : HubMemoryStore {
        var saves = 0
        override fun load() = memory
        override fun save(memory: HubMemory) {
            this.memory = HubMemory.decode(memory.encode()) // what a real round trip would keep
            saves++
        }
    }

    private fun ids(session: HubSession) = session.view.centre.map { it.id }

    @Test
    fun `a first run shows the cold start centre and no page`() {
        val session = HubSession.fresh(FakeStore())
        assertEquals(ToolCatalog.defaultCentre().map { it.id }, ids(session))
        assertNull(session.view.groupPage)
        assertNull(session.view.openTool)
        assertFalse("nowhere to go back to, so Back leaves the editor", session.canGoBack)
        assertFalse(session.back())
    }

    @Test
    fun `a tool is counted once per opening, at its first change, however many follow`() {
        val store = FakeStore()
        val session = HubSession.fresh(store)
        session.applied() // no tool open: nothing
        assertTrue(store.memory.usage.isEmpty)

        session.open(ToolIds.HIGHLIGHTS)
        assertTrue("opening alone teaches nothing", store.memory.usage.isEmpty)
        repeat(50) { session.applied() }
        assertEquals(ToolUsage.ONE_USE, store.memory.usage.score(ToolIds.HIGHLIGHTS))

        session.closeTool()
        session.open(ToolIds.HIGHLIGHTS)
        session.applied()
        assertTrue("a second opening is a second use", store.memory.usage.score(ToolIds.HIGHLIGHTS) > ToolUsage.ONE_USE)
    }

    @Test
    fun `the centre does not move while a tool is open, only when it is closed`() {
        val session = HubSession.fresh(FakeStore())
        repeat(6) {
            val before = ids(session)
            session.open(ToolIds.TINT)
            session.applied()
            assertEquals("while the tool is open", before, ids(session))
            session.back() // closes the tool and lets the centre catch up
        }
        // Eligible and used six times, far past the margin.
        assertTrue(ToolIds.TINT in ids(session))
    }

    @Test
    fun `Back steps tool, then more, then the group page, then leaves`() {
        val session = HubSession.fresh(FakeStore())
        session.ring(ToolGroup.LIGHT)
        session.toggleMore()
        session.open(ToolIds.CURVES)
        assertTrue(session.canGoBack)
        assertTrue(session.back())
        assertNull(session.view.openTool)
        assertTrue("still on the page with More shown", session.state.deeperShown)
        assertTrue(session.back())
        assertFalse(session.state.deeperShown)
        assertEquals(ToolGroup.LIGHT, session.view.groupPage?.group)
        assertTrue(session.back())
        assertNull(session.view.groupPage)
        assertFalse(session.canGoBack)
        assertFalse("now Back is the host's", session.back())
    }

    @Test
    fun `tapping the showing group goes home, another group switches`() {
        val session = HubSession.fresh(FakeStore())
        session.ring(ToolGroup.COLOUR)
        session.ring(ToolGroup.DETAIL)
        assertEquals(ToolGroup.DETAIL, session.view.groupPage?.group)
        session.ring(ToolGroup.DETAIL)
        assertNull(session.view.groupPage)
        assertNull(session.state.openGroup)
    }

    @Test
    fun `a ring tap while a tool is open closes the tool and shows that group`() {
        val session = HubSession.fresh(FakeStore())
        session.open(ToolIds.EXPOSURE)
        assertEquals("the open tool's group is marked", ToolGroup.LIGHT, session.view.activeGroup)
        session.ring(ToolGroup.LIGHT)
        assertNull(session.view.openTool)
        assertEquals(ToolGroup.LIGHT, session.view.groupPage?.group)
    }

    @Test
    fun `an unknown tool id is ignored`() {
        val session = HubSession.fresh(FakeStore())
        session.open("not_a_tool")
        assertNull(session.view.openTool)
    }

    @Test
    fun `home drops a group page but never an open tool`() {
        val session = HubSession.fresh(FakeStore())
        session.ring(ToolGroup.LIGHT)
        session.open(ToolIds.CONTRAST)
        session.home()
        assertEquals(ToolIds.CONTRAST, session.view.openTool?.id)
        session.closeTool()
        session.home()
        assertNull(session.view.groupPage)
    }

    @Test
    fun `use survives the process - a new session reads the same memory and settles it`() {
        val store = FakeStore()
        repeat(4) {
            val session = HubSession.fresh(store)
            session.open(ToolIds.HIGHLIGHTS)
            session.applied()
            session.closeTool()
        }
        val next = HubSession.fresh(store)
        assertTrue(ToolIds.HIGHLIGHTS in ids(next))
        assertEquals("the centre was kept", store.memory.centre, ids(next))
    }

    @Test
    fun `a first run writes the settled cold start centre once and nothing else`() {
        val store = FakeStore()
        HubSession.fresh(store)
        assertEquals(1, store.saves) // the settled cold-start centre, once
    }

    @Test
    fun `pressing an action counts nothing and the page stays as it was`() {
        val store = FakeStore()
        val session = HubSession.fresh(store)
        repeat(3) {
            session.home()
            session.ring(ToolGroup.CROP_STRAIGHTEN)
            session.open(ToolIds.RESET)
            session.applied()
            session.closeTool()
        }
        assertTrue(store.memory.usage.isEmpty)
        session.home()
        session.ring(ToolGroup.CROP_STRAIGHTEN)
        assertFalse(ToolIds.RESET in session.view.groupPage!!.front.map { it.id })
    }
}
