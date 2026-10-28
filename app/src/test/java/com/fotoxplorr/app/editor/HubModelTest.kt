package com.fotoxplorr.app.editor

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModelTest {

    private fun ids(specs: List<ToolSpec>) = specs.map { it.id }

    private fun used(vararg uses: String): ToolUsage = uses.fold(ToolUsage.EMPTY) { u, id -> u.record(id) }

    private fun times(n: Int, id: String): Array<String> = Array(n) { id }

    private val everyday = listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.SATURATION, ToolIds.CROP, ToolIds.STRAIGHTEN)

    // ---- cold start ----

    @Test
    fun `a new user sees the everyday tools in the centre and all five groups round them`() {
        val view = HubModel.view(HubState())
        assertEquals(everyday, ids(view.centre))
        assertEquals(ToolGroup.entries.toList(), view.ring)
        assertEquals(5, view.ring.size)
        assertNull(view.groupPage)
        assertNull(view.openTool)
        assertNull(view.activeGroup)
    }

    @Test
    fun `settling with no history gives the top of the cold start ranking`() {
        assertEquals(everyday, HubModel.settleCentre(emptyList(), ToolUsage.EMPTY))
        assertEquals(everyday.take(4), HubModel.settleCentre(emptyList(), ToolUsage.EMPTY, size = 4))
        assertEquals(everyday, ids(HubModel.ranked(ToolUsage.EMPTY).take(5)))
        assertTrue(HubModel.ranked(ToolUsage.EMPTY).none { !it.centreEligible })
    }

    @Test
    fun `view is a pure function`() {
        val state = HubModel.settle(HubModel.record(HubState(), ToolIds.HEAL))
        assertEquals(HubModel.view(state), HubModel.view(state))
    }

    // ---- adaptation and hysteresis ----

    @Test
    fun `a new favourite takes the slot of the weakest tool and the rest stay put`() {
        val usage = used(*times(3, ToolIds.WHITES))
        val centre = HubModel.settleCentre(everyday, usage)
        // Straighten is last in the cold start order, so it is the weakest of five equals.
        assertEquals(listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.SATURATION, ToolIds.CROP, ToolIds.WHITES), centre)
    }

    @Test
    fun `two uses are not enough to displace a default, three are`() {
        assertEquals(everyday, HubModel.settleCentre(everyday, used(*times(2, ToolIds.WHITES))))
        assertTrue(ToolIds.WHITES in HubModel.settleCentre(everyday, used(*times(3, ToolIds.WHITES))))
    }

    @Test
    fun `a learned favourite beats the cold start defaults for good`() {
        var usage = ToolUsage.EMPTY
        repeat(40) { usage = usage.record(ToolIds.TINT).record(ToolIds.SHARPEN) }
        val centre = HubModel.settleCentre(everyday, usage)
        assertTrue(ToolIds.TINT in centre && ToolIds.SHARPEN in centre)
    }

    @Test
    fun `a near tie never flips the centre however long it goes on`() {
        // Five incumbents and one challenger of the same rarity, all used in strict rotation: their
        // scores stay within about 14 percent of each other, inside the 25 percent margin.
        val incumbents = listOf(ToolIds.HIGHLIGHTS, ToolIds.SHADOWS, ToolIds.TEMPERATURE, ToolIds.VIBRANCE, ToolIds.SHARPEN)
        val rotation = incumbents + ToolIds.CLARITY
        var usage = ToolUsage.EMPTY
        repeat(100) { rotation.forEach { usage = usage.record(it) } } // warm up so the prior is irrelevant
        var centre = incumbents
        var settledChanges = 0
        var naiveChanges = 0
        var naive = HubModel.settleCentre(emptyList(), usage)
        repeat(100) {
            for (id in rotation) {
                usage = usage.record(id)
                val next = HubModel.settleCentre(centre, usage)
                if (next != centre) settledChanges++
                centre = next
                val again = HubModel.settleCentre(emptyList(), usage)
                if (again.toSet() != naive.toSet()) naiveChanges++
                naive = again
            }
        }
        assertEquals(0, settledChanges)
        assertEquals(incumbents, centre)
        // The test has teeth: recomputing from scratch would have reshuffled over and over.
        assertTrue("naive changed $naiveChanges times", naiveChanges > 100)
    }

    @Test
    fun `over a seeded long history the centre moves far less than a plain top five would`() {
        val random = Random(2026)
        // A skewed taste: a few tools get most of the use, drifting halfway through.
        val early = listOf(ToolIds.EXPOSURE, ToolIds.CROP, ToolIds.SHADOWS, ToolIds.VIBRANCE, ToolIds.CLARITY, ToolIds.TINT)
        val late = listOf(ToolIds.EXPOSURE, ToolIds.CROP, ToolIds.HEAL, ToolIds.PRESETS, ToolIds.SHARPEN, ToolIds.TINT)
        var usage = ToolUsage.EMPTY
        var centre = HubModel.settleCentre(emptyList(), usage)
        var naive = centre
        var settledChanges = 0
        var naiveChanges = 0
        repeat(800) { n ->
            val pool = if (n < 400) early else late
            val id = if (random.nextInt(10) < 8) pool[random.nextInt(pool.size)] else ToolCatalog.all[random.nextInt(ToolCatalog.all.size)].id
            usage = usage.record(id)
            val next = HubModel.settleCentre(centre, usage)
            if (next.toSet() != centre.toSet()) settledChanges++
            centre = next
            val plain = HubModel.settleCentre(emptyList(), usage)
            if (plain.toSet() != naive.toSet()) naiveChanges++
            naive = plain
            assertEquals(5, centre.size)
            assertEquals(5, centre.toSet().size)
            assertTrue(centre.all { ToolCatalog.get(it)!!.centreEligible })
        }
        assertTrue("settled $settledChanges naive $naiveChanges", settledChanges * 2 <= naiveChanges)
        assertTrue("settled $settledChanges", settledChanges <= 25)
        // After the taste drifted, the centre followed it.
        assertTrue(ToolIds.HEAL in centre || ToolIds.PRESETS in centre || ToolIds.SHARPEN in centre)
    }

    @Test
    fun `settling is stable once settled`() {
        val usage = used(*times(6, ToolIds.WHITES), *times(6, ToolIds.TINT))
        val once = HubModel.settleCentre(everyday, usage)
        assertEquals(once, HubModel.settleCentre(once, usage))
    }

    @Test
    fun `ties are broken by the cold start order not by chance`() {
        val a = HubModel.settleCentre(emptyList(), ToolUsage.EMPTY)
        repeat(5) { assertEquals(a, HubModel.settleCentre(emptyList(), ToolUsage.EMPTY)) }
        // Equal learned scores for two rare tools: the earlier catalogue entry wins the last slot.
        val usage = ToolUsage.decode("tu1;blacks=9000;whites=9000")
        val centre = HubModel.settleCentre(emptyList(), usage, size = 1)
        assertEquals(listOf(ToolIds.WHITES), centre)
    }

    @Test
    fun `settling repairs a bad remembered centre`() {
        val garbage = listOf("nope", ToolIds.CROP, ToolIds.CROP, ToolIds.RESET, ToolIds.REVERT, "", ToolIds.HEAL)
        val centre = HubModel.settleCentre(garbage, ToolUsage.EMPTY)
        assertEquals(5, centre.size)
        assertEquals(5, centre.toSet().size)
        // Heal survives the clean-up but, at a rare tool's prior, is displaced by the first default
        // that clearly out-ranks it, and the newcomer takes its slot.
        assertEquals(listOf(ToolIds.CROP, ToolIds.STRAIGHTEN), centre.take(2))
        assertTrue(centre.none { it == ToolIds.RESET || it == ToolIds.REVERT || it == "nope" })
        // An over-long remembered centre is cut, not trusted.
        assertEquals(4, HubModel.settleCentre(ToolCatalog.all.map { it.id }, ToolUsage.EMPTY, size = 4).size)
    }

    // ---- never reorders while a tool is open ----

    @Test
    fun `the centre does not move while a tool is open however much it is used`() {
        var state = HubModel.settle(HubState())
        val before = HubModel.view(state).centre
        state = HubModel.openTool(state, ToolIds.WHITES)
        repeat(30) {
            state = HubModel.record(state, ToolIds.WHITES)
            assertEquals(before, HubModel.view(state).centre)
        }
        assertEquals(ToolIds.WHITES, HubModel.view(state).openTool?.id)
        // The use is remembered; the centre catches up only when the tool is closed.
        assertTrue(state.memory.usage.score(ToolIds.WHITES) > 15_000)
        state = HubModel.closeTool(state)
        assertNull(HubModel.view(state).openTool)
        assertTrue(ToolIds.WHITES in ids(HubModel.view(state).centre))
        assertNotEquals(before, HubModel.view(state).centre)
    }

    @Test
    fun `recording a use never changes the centre by itself`() {
        var state = HubModel.settle(HubState())
        val before = state.memory.centre
        repeat(30) { state = HubModel.record(state, ToolIds.TINT) }
        assertEquals(before, state.memory.centre)
        assertEquals(everyday, ids(HubModel.view(state).centre))
        state = HubModel.settle(state)
        assertTrue(ToolIds.TINT in ids(HubModel.view(state).centre))
    }

    @Test
    fun `an unsettled memory shows the cold start centre and not a guess from use`() {
        val memory = HubMemory(usage = used(*times(30, ToolIds.TINT)), centre = emptyList())
        assertEquals(everyday, ids(HubModel.view(HubState(memory)).centre))
        assertEquals(everyday, ids(HubModel.view(HubState(memory, openTool = ToolIds.TINT)).centre))
    }

    @Test
    fun `an unknown tool cannot be opened or recorded`() {
        val state = HubState()
        assertEquals(state, HubModel.openTool(state, "nope"))
        assertEquals(ToolUsage.EMPTY, HubModel.record(state, "nope").memory.usage)
        assertNull(HubModel.view(HubState(openTool = "nope")).openTool)
    }

    // ---- group pages: nothing scrolls, nothing is lost ----

    @Test
    fun `a group opens onto its everyday tools and the rare ones are one tap deeper`() {
        var state = HubModel.showGroup(HubState(), ToolGroup.LIGHT)
        var page = HubModel.view(state).groupPage!!
        assertEquals(listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.HIGHLIGHTS, ToolIds.SHADOWS), ids(page.front))
        assertTrue(page.deeper.isEmpty())
        assertEquals(3, page.deeperCount)
        state = HubModel.toggleDeeper(state)
        page = HubModel.view(state).groupPage!!
        assertEquals(listOf(ToolIds.WHITES, ToolIds.BLACKS, ToolIds.CURVES), ids(page.deeper))
        assertEquals(ToolGroup.LIGHT, HubModel.view(state).activeGroup)
    }

    @Test
    fun `a rare tool earns its place on the front page with use, in catalogue order`() {
        val page = HubModel.groupPage(ToolGroup.LIGHT, used(*times(2, ToolIds.WHITES)), deeperShown = false)
        assertEquals(
            listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.HIGHLIGHTS, ToolIds.SHADOWS, ToolIds.WHITES),
            ids(page.front),
        )
        assertEquals(2, page.deeperCount)
    }

    @Test
    fun `a front page never has more than five tools, the least used spill deeper`() {
        val usage = used(*times(3, ToolIds.WHITES), *times(3, ToolIds.BLACKS))
        val page = HubModel.groupPage(ToolGroup.LIGHT, usage, deeperShown = true)
        assertEquals(HubModel.FRONT_MAX, page.front.size)
        // Highlights and Shadows tie at the bottom; the later catalogue entry goes first.
        assertEquals(listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.HIGHLIGHTS, ToolIds.WHITES, ToolIds.BLACKS), ids(page.front))
        assertEquals(listOf(ToolIds.SHADOWS, ToolIds.CURVES), ids(page.deeper))
    }

    @Test
    fun `no group page needs scrolling, with no history or with everything used heavily`() {
        val everything = ToolCatalog.all.fold(ToolUsage.EMPTY) { u, spec ->
            (1..10).fold(u) { acc, _ -> acc.record(spec.id) }
        }
        for (usage in listOf(ToolUsage.EMPTY, everything)) {
            for (group in ToolGroup.entries) {
                val page = HubModel.groupPage(group, usage, deeperShown = true)
                assertTrue("${group.name} front ${page.front.size}", page.front.size in 1..HubModel.FRONT_MAX)
                assertTrue("${group.name} total", page.front.size + page.deeper.size <= 8)
                assertEquals(page.deeper.size, page.deeperCount)
            }
        }
        assertTrue(HubModel.CENTRE_SIZE in 4..5)
    }

    @Test
    fun `every tool is reachable from exactly one group page`() {
        val usage = used(*times(5, ToolIds.WHITES), *times(4, ToolIds.CURVES))
        for (u in listOf(ToolUsage.EMPTY, usage)) {
            val shown = ToolGroup.entries.flatMap { g ->
                val page = HubModel.groupPage(g, u, deeperShown = true)
                (page.front + page.deeper).also { all -> assertTrue(all.all { it.group == g }) }
            }
            assertEquals(ToolCatalog.all.size, shown.size)
            assertEquals(ToolCatalog.all.toSet(), shown.toSet())
        }
    }

    @Test
    fun `the centre is a shortcut, the same tool is still in its group`() {
        val inCentre = HubModel.view(HubState()).centre.first()
        val page = HubModel.groupPage(inCentre.group, ToolUsage.EMPTY, deeperShown = true)
        assertTrue(inCentre in page.front)
    }

    // ---- navigation ----

    @Test
    fun `back steps out one level at a time and then says there is nowhere left`() {
        var state = HubModel.showGroup(HubState(), ToolGroup.COLOUR)
        state = HubModel.toggleDeeper(state)
        state = HubModel.openTool(state, ToolIds.HSL)
        state = HubModel.back(state)!!
        assertNull(state.openTool)
        assertTrue(state.deeperShown)
        state = HubModel.back(state)!!
        assertFalse(state.deeperShown)
        assertEquals(ToolGroup.COLOUR, state.openGroup)
        state = HubModel.back(state)!!
        assertNull(state.openGroup)
        assertNull(HubModel.back(state))
    }

    @Test
    fun `a tool opened from the centre goes back to the hub not to a group`() {
        var state = HubModel.openTool(HubState(), ToolIds.CROP)
        assertEquals(ToolGroup.CROP_STRAIGHTEN, HubModel.view(state).activeGroup)
        state = HubModel.back(state)!!
        assertNull(state.openGroup)
        assertNull(HubModel.back(state))
    }

    @Test
    fun `tapping a ring button while a tool is open closes it and settles the centre`() {
        var state = HubModel.settle(HubState())
        state = HubModel.openTool(state, ToolIds.TINT)
        repeat(10) { state = HubModel.record(state, ToolIds.TINT) }
        state = HubModel.showGroup(state, ToolGroup.DETAIL)
        assertNull(state.openTool)
        assertEquals(ToolGroup.DETAIL, state.openGroup)
        assertFalse(state.deeperShown)
        assertTrue(ToolIds.TINT in state.memory.centre)
    }

    @Test
    fun `More does nothing without a group page`() {
        val state = HubState()
        assertEquals(state, HubModel.toggleDeeper(state))
    }

    @Test
    fun `saved navigation strings restore, and unknown ones are dropped`() {
        val memory = HubMemory.EMPTY
        val ok = HubState.restore(memory, ToolIds.HEAL, "EFFECTS", deeperShown = true)
        assertEquals(HubState(memory, ToolIds.HEAL, ToolGroup.EFFECTS, true), ok)
        val bad = HubState.restore(memory, "gone", "GONE", deeperShown = true)
        assertEquals(HubState(memory), bad)
    }

    // ---- persistence ----

    @Test
    fun `memory round trips through one string`() {
        val random = Random(99)
        var state = HubState()
        repeat(200) {
            state = HubModel.record(state, ToolCatalog.all[random.nextInt(ToolCatalog.all.size)].id)
            if (it % 20 == 0) state = HubModel.settle(state)
        }
        val text = state.memory.encode()
        assertFalse(text.contains('\r'))
        assertEquals(state.memory, HubMemory.decode(text))
        assertEquals(HubModel.view(state), HubModel.view(HubState(HubMemory.decode(text))))
    }

    @Test
    fun `corrupt memory text reads as empty and a bad part is dropped alone`() {
        val bad = listOf(null, "", "hm1", "hm2\n\ntu1", "hm1\na\nb\nc", "random", "hm1\n,,,\n\n", "\n\n", "x".repeat(9_000))
        for (text in bad) {
            val memory = HubMemory.decode(text)
            assertEquals("input: $text", ToolUsage.EMPTY, memory.usage)
            assertEquals("input: $text", emptyList<String>(), memory.centre)
        }
        val partial = HubMemory.decode("hm1\ncrop,nope,crop,heal\ntu1;crop=oops;heal=1500")
        assertEquals(listOf("crop", "heal"), partial.centre)
        assertEquals(listOf("heal"), partial.usage.ids)
        val goodCentreBadUsage = HubMemory.decode("hm1\ncrop,heal\nnot usage")
        assertEquals(listOf("crop", "heal"), goodCentreBadUsage.centre)
        assertTrue(goodCentreBadUsage.usage.isEmpty)
    }

    @Test
    fun `an empty memory encodes and decodes to itself`() {
        assertEquals(HubMemory.EMPTY, HubMemory.decode(HubMemory.EMPTY.encode()))
    }
}
