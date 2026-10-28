package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The palette builder's rules as pure functions: what each button does to the draft, and what survives a save. */
class PaletteDraftTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF3366CC.toInt()

    private fun palette(vararg colours: Int, name: String = "Test") = Palette.of(name, colours.toList())!!

    private fun started(vararg colours: Int): PaletteDraft = PaletteDraft().started(palette(*colours))

    // ---- starting -------------------------------------------------------------------------------

    @Test
    fun `a palette from one colour has five colours, that one first, and opens on the edit page`() {
        val d = PaletteDraft().fromColour(blue)
        assertEquals(BuilderPage.EDIT, d.page)
        assertEquals(PaletteDraft.START_COLOURS, d.palette!!.size)
        assertEquals(blue, d.palette!!.colors[0].argb)
        assertEquals(0, d.selected)
        assertTrue(d.dirty)
        assertTrue(d.sources.isEmpty())
    }

    @Test
    fun `a new palette takes the first default name no saved palette has`() {
        val taken = listOf(PaletteBuilderCopy.DEFAULT_NAME, "my PALETTE 2")
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, PaletteDraft().fromColour(blue).palette!!.name)
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME + " 3", PaletteDraft().fromColour(blue, taken).palette!!.name)
        val made = Palette.of(PaletteBuilderCopy.DEFAULT_NAME, listOf(red, blue))!!
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME + " 3", PaletteDraft().started(made, listOf(1L), taken).palette!!.name)
        // A name the person chose is theirs: taking it is how a palette is replaced, and the notice says so.
        val chosen = Palette.of("Sea", listOf(red, blue))!!
        assertEquals("Sea", PaletteDraft().started(chosen, listOf(1L), listOf("Sea")).palette!!.name)
    }

    @Test
    fun `a palette made from photos remembers them, and one opened from the saved list does not`() {
        val made = PaletteDraft().started(palette(red, blue), listOf(7L, 9L))
        assertEquals(listOf(7L, 9L), made.sources)
        assertTrue(made.dirty)
        val opened = made.opened(palette(red, blue))
        assertTrue(opened.sources.isEmpty())
        assertFalse(opened.dirty)
        assertTrue("a new palette starts a new name field", opened.generation > made.generation)
    }

    @Test
    fun `the edit and recolour pages need a palette`() {
        val none = PaletteDraft()
        assertSame(none, none.onPage(BuilderPage.EDIT))
        assertSame(none, none.onPage(BuilderPage.RECOLOUR))
        assertEquals(BuilderPage.PHOTOS, none.onPage(BuilderPage.PHOTOS).page)
        assertEquals(BuilderPage.RECOLOUR, started(red, blue).onPage(BuilderPage.RECOLOUR).page)
    }

    @Test
    fun `Back goes from recolour to edit, from every other page to start, and stops at start`() {
        val d = started(red, blue)
        assertEquals(BuilderPage.EDIT, d.onPage(BuilderPage.RECOLOUR).backed().page)
        assertEquals(BuilderPage.START, d.backed().page)
        assertEquals(BuilderPage.START, PaletteDraft(page = BuilderPage.PHOTOS).backed().page)
        assertEquals(BuilderPage.START, PaletteDraft(page = BuilderPage.COLOUR).backed().page)
        val start = PaletteDraft()
        assertSame(start, start.backed())
        assertFalse(start.hasInnerStep)
        assertTrue(d.hasInnerStep)
        assertEquals("the palette is kept", d.palette, d.backed().palette)
    }

    // ---- the steppers ---------------------------------------------------------------------------

    @Test
    fun `hue steps round the wheel and wraps both ways`() {
        val d = started(red, blue)
        assertEquals(15, d.nudged(HslChannel.HUE, 15).readout()!!.hue)
        assertEquals(345, d.nudged(HslChannel.HUE, -15).readout()!!.hue)
        // Twenty-four coarse steps are a full turn. Each step starts from the 8 bit colour the last one made, so a
        // degree of drift over the turn is expected; what matters is that it comes back round, not past or short.
        var turned = d
        repeat(24) { turned = turned.nudged(HslChannel.HUE, 15) }
        val hue = turned.readout()!!.hue
        assertTrue("hue $hue", hue <= 2 || hue >= 358)
    }

    @Test
    fun `saturation and lightness stop at the ends and then change nothing`() {
        val full = started(red, blue)
        assertEquals(100, full.readout()!!.saturation)
        assertSame("already as strong as it goes", full, full.nudged(HslChannel.SATURATION, 10))
        assertEquals(90, full.nudged(HslChannel.SATURATION, -10).readout()!!.saturation)

        val white = started(0xFFFFFFFF.toInt(), blue)
        assertSame(white, white.nudged(HslChannel.LIGHTNESS, 2))
        val black = started(0xFF000000.toInt(), blue)
        assertSame(black, black.nudged(HslChannel.LIGHTNESS, -2))
        assertEquals(10, black.nudged(HslChannel.LIGHTNESS, 10).readout()!!.lightness)
    }

    @Test
    fun `a grey has no hue to turn`() {
        val grey = started(0xFF808080.toInt(), blue)
        assertSame(grey, grey.nudged(HslChannel.HUE, 15))
    }

    @Test
    fun `a stepper acts on the picked swatch only, keeps its lock, and adds no undo step`() {
        val d = started(red, blue).selecting(1).toggledLock()
        val n = d.nudged(HslChannel.LIGHTNESS, 10)
        assertEquals(red, n.palette!!.colors[0].argb)
        assertTrue(n.palette!!.colors[1].locked)
        assertTrue(n.palette!!.colors[1].argb != blue)
        assertTrue(n.undo.isEmpty())
        assertTrue(n.dirty)
    }

    @Test
    fun `each stepper step is large enough to change an 8 bit colour on a mid colour`() {
        val mid = started(ColorMath.fromHsl(200.0, 0.5, 0.5), blue)
        for ((channel, step) in listOf(HslChannel.HUE to 3, HslChannel.SATURATION to 2, HslChannel.LIGHTNESS to 2)) {
            assertTrue("$channel +$step", mid.nudged(channel, step).palette != mid.palette)
            assertTrue("$channel -$step", mid.nudged(channel, -step).palette != mid.palette)
        }
    }

    @Test
    fun `the readout is whole numbers and a typed colour replaces the picked one`() {
        val d = started(red, blue)
        assertEquals(HslReadout(0, 100, 50), d.readout())
        val typed = d.withPickedColour(0xFF00FF00.toInt())
        assertEquals(0xFF00FF00.toInt(), typed.palette!!.colors[0].argb)
        assertSame(typed, typed.withPickedColour(0xFF00FF00.toInt()))
        assertNull(PaletteDraft().readout())
    }

    // ---- add, remove, move, lock ----------------------------------------------------------------

    @Test
    fun `Add makes a neighbour thirty degrees round and picks it`() {
        val d = started(red, blue).added()
        assertEquals(3, d.palette!!.size)
        assertEquals(2, d.selected)
        assertEquals(30, ColorMath.hsl(d.palette!!.colors[2].argb).h.toInt())
        assertTrue(d.undo.isEmpty())
    }

    @Test
    fun `Add stops at twelve and Remove stops at two`() {
        var d = started(red, blue)
        repeat(20) { d = d.added() }
        assertEquals(12, d.palette!!.size)
        assertFalse(d.canAdd)
        assertSame(d, d.added())

        var small = d
        repeat(20) { small = small.removed() }
        assertEquals(2, small.palette!!.size)
        assertFalse(small.canRemove)
        assertSame(small, small.removed())
    }

    @Test
    fun `Remove takes the picked colour out, keeps the pick in range and can be undone`() {
        val d = started(red, 0xFF00FF00.toInt(), blue).selecting(2)
        val r = d.removed()
        assertEquals(listOf(red, 0xFF00FF00.toInt()), r.palette!!.argbs)
        assertEquals(1, r.selected)
        assertEquals(1, r.undo.size)
        val back = r.undone()
        assertEquals(d.palette, back.palette)
        assertTrue(back.undo.isEmpty())
        assertSame(back, back.undone())
    }

    @Test
    fun `Move left and right swap with the neighbour, the pick follows, and the ends do nothing`() {
        val g = 0xFF00FF00.toInt()
        val d = started(red, g, blue).selecting(1)
        val right = d.movedBy(+1)
        assertEquals(listOf(red, blue, g), right.palette!!.argbs)
        assertEquals(2, right.selected)
        assertFalse(right.canMoveRight)
        assertSame(right, right.movedBy(+1))
        val left = right.movedBy(-1).movedBy(-1)
        assertEquals(listOf(g, red, blue), left.palette!!.argbs)
        assertEquals(0, left.selected)
        assertFalse(left.canMoveLeft)
        assertSame(left, left.movedBy(-1))
        assertSame(left, left.movedBy(0))
    }

    @Test
    fun `a lock goes with its colour when it moves`() {
        val d = started(red, blue).toggledLock().movedBy(+1)
        assertTrue(d.palette!!.colors[1].locked)
        assertFalse(d.palette!!.colors[0].locked)
        assertFalse(d.toggledLock().palette!!.colors[1].locked)
    }

    @Test
    fun `picking an out of range swatch changes nothing`() {
        val d = started(red, blue)
        assertSame(d, d.selecting(5))
        assertSame(d, d.selecting(-1))
        assertSame(d, d.selecting(0))
        assertEquals(1, d.selecting(1).selected)
    }

    // ---- harmony and undo -----------------------------------------------------------------------

    @Test
    fun `a harmony rebuilds around the picked colour, leaves locked ones, and Undo brings the old palette back`() {
        val g = 0xFF00AA00.toInt()
        val d = started(red, g, blue, 0xFFAAAAAA.toInt()).selecting(2).let { it.selecting(1).toggledLock().selecting(2) }
        val h = d.harmonised(HarmonyKind.TRIAD)
        assertEquals("the base keeps its colour", blue, h.palette!!.colors[2].argb)
        assertEquals("the locked one is left alone", g, h.palette!!.colors[1].argb)
        assertTrue(h.palette!!.colors[1].locked)
        assertTrue(h.palette!!.argbs != d.palette!!.argbs)
        assertEquals(1, h.undo.size)
        assertEquals(d.palette, h.undone().palette)
    }

    @Test
    fun `the same harmony twice changes nothing the second time, and adds no undo step`() {
        val once = started(red, blue, 0xFF00FF00.toInt()).harmonised(HarmonyKind.SPLIT_COMPLEMENTARY)
        val twice = once.harmonised(HarmonyKind.SPLIT_COMPLEMENTARY)
        assertSame(once, twice)
        assertEquals(1, twice.undo.size)
    }

    @Test
    fun `undo keeps the last ten edits`() {
        var d = PaletteDraft().started(Palette.of("Many", List(12) { ColorMath.rgb(it * 20, 40, 90) })!!)
        repeat(15) { d = d.removed().added() }
        assertEquals(PaletteDraft.MAX_UNDO, d.undo.size)
    }

    @Test
    fun `a rename is cleaned, is not an undo step, and a blank name becomes the default`() {
        val d = started(red, blue)
        val r = d.renamed("  Morning\n  light ")
        assertEquals("Morning light", r.palette!!.name)
        assertTrue(r.undo.isEmpty())
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, r.renamed("   ").palette!!.name)
        assertSame(r, r.renamed("Morning light"))
    }

    @Test
    fun `saving clears the dirty flag and any edit sets it again`() {
        val d = started(red, blue).savedNow()
        assertFalse(d.dirty)
        assertTrue(d.toggledLock().dirty)
        assertTrue(d.renamed("Other").dirty)
    }

    // ---- photo pages ----------------------------------------------------------------------------

    @Test
    fun `up to six photos can be ticked, in the order ticked, and a ticked one can be unticked`() {
        var d = PaletteDraft(page = BuilderPage.PHOTOS)
        assertFalse(d.canMake)
        for (id in 1L..8L) d = d.togglePick(id)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), d.picks)
        assertTrue(d.isFull)
        assertTrue(d.canMake)
        assertSame("a seventh is refused", d, d.togglePick(7L))
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L), d.togglePick(3L).picks)
        assertFalse(d.togglePick(3L).isFull)
    }

    @Test
    fun `the photo to recolour is a single choice that the same tap takes back`() {
        val d = started(red, blue)
        assertEquals(5L, d.pickRecolour(5L).recolourPick)
        assertEquals(6L, d.pickRecolour(5L).pickRecolour(6L).recolourPick)
        assertNull(d.pickRecolour(5L).pickRecolour(5L).recolourPick)
    }

    @Test
    fun `a photo that has gone drops out of the ticks and the recolour pick but not out of the sources`() {
        val d = PaletteDraft(page = BuilderPage.PHOTOS, picks = listOf(1L, 2L, 3L), recolourPick = 2L, sources = listOf(1L, 2L))
        val r = d.reconciled(setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), r.picks)
        assertNull(r.recolourPick)
        assertEquals("a lock must not let a palette off the gate", listOf(1L, 2L), r.sources)
        assertSame("nothing gone: the same draft", d, d.reconciled(setOf(1L, 2L, 3L, 4L)))
        assertSame("an empty list is a library still loading", d, d.reconciled(emptySet()))
    }

    @Test
    fun `the recolour settings go in steps of a quarter and map onto the recolour`() {
        val s = RecolourSettings()
        assertEquals(100, s.strength)
        assertEquals(1f, s.amount, 0f)
        assertEquals(RecolourSettings.KEEP_SHADING, s.keepLightness, 0f)
        assertEquals(0f, s.copy(keepShading = false).keepLightness, 0f)
        assertEquals(75, s.weaker().strength)
        assertEquals(25, s.weaker().weaker().weaker().weaker().weaker().strength)
        assertEquals(100, s.stronger().strength)
        assertEquals(0.5f, RecolourSettings(50).amount, 0f)
        for (bad in listOf(0, 10, 26, 125, -25)) {
            val failed = runCatching { RecolourSettings(bad) }.isFailure
            assertTrue("strength $bad", failed)
        }
    }

    // ---- the export gate ------------------------------------------------------------------------

    @Test
    fun `the gate lets a palette go only while every photo it came from may be exported`() {
        assertNull(ExportGate.refusal(emptyList()) { false })
        assertNull(ExportGate.refusal(listOf(1L, 2L)) { true })
        assertEquals(PaletteBuilderCopy.SOURCE_PROTECTED, ExportGate.refusal(listOf(1L, 2L)) { it == 1L })
        assertEquals(PaletteBuilderCopy.SOURCE_PROTECTED, ExportGate.refusal(listOf(1L)) { false })
    }

    // ---- saving the draft -----------------------------------------------------------------------

    private fun full(): PaletteDraft {
        val base = PaletteDraft(
            page = BuilderPage.RECOLOUR,
            sources = listOf(11L, 12L),
            picks = listOf(3L, 4L),
            recolourPick = 12L,
            recolour = RecolourSettings(50, keepShading = false),
        ).started(Palette.of("Sunset", listOf(red, blue, 0xFF00FF00.toInt(), 0xFFFFFF00.toInt()))!!, listOf(11L, 12L))
        return base.copy(page = BuilderPage.RECOLOUR, picks = listOf(3L, 4L), recolourPick = 12L, recolour = RecolourSettings(50, false))
            .selecting(2).toggledLock().removed().harmonised(HarmonyKind.TRIAD).selecting(1)
    }

    @Test
    fun `a draft round trips whole through its saved text`() {
        val d = full()
        val back = PaletteDraft.decode(d.encode())
        assertEquals(d, back)
        assertTrue(back.undo.size >= 2)
    }

    @Test
    fun `a draft with no palette round trips too`() {
        val d = PaletteDraft(page = BuilderPage.PHOTOS, picks = listOf(1L, 2L))
        assertEquals(d, PaletteDraft.decode(d.encode()))
        assertEquals(PaletteDraft(), PaletteDraft.decode(PaletteDraft().encode()))
    }

    @Test
    fun `a page that needs a palette but has none comes back as the start page`() {
        val text = PaletteDraft(page = BuilderPage.EDIT).encode()
        assertEquals(BuilderPage.START, PaletteDraft.decode(text).page)
    }

    @Test
    fun `a palette that cannot be read loses the draft, not the app`() {
        val text = full().encode()
        val head = text.substringBefore('\n')
        assertEquals(PaletteDraft(), PaletteDraft.decode(head + "\nfotoz-palette 1\ncolor #zzzzzz\n"))
        assertEquals(PaletteDraft(), PaletteDraft.decode(head + "\nnonsense"))
    }

    @Test
    fun `a pick past the palette's size is brought back into range`() {
        val d = started(red, blue)
        val text = d.encode().replaceFirst(";0;", ";9;")
        assertEquals(1, PaletteDraft.decode(text).selected)
    }

    @Test
    fun `damaged or foreign text gives an empty draft`() {
        val good = full().encode()
        val line = good.substringBefore('\n')
        val bad = listOf(
            "", "x", "\n", "1;START", "2" + line.drop(1) + "\n", line.replace("RECOLOUR", "NOWHERE") + "\n",
            line.replace(";50;", ";51;") + "\n", line.replace(";50;", ";0;") + "\n", line.replace(";50;", ";x;") + "\n",
            line.replaceFirst("RECOLOUR;", "RECOLOUR;-1;") + "\n", line.replace("11,12", "11,x") + "\n",
        )
        for (text in bad) assertEquals("[$text]", PaletteDraft(), PaletteDraft.decode(text))
    }

    @Test
    fun `a name that holds the undo marker still reads back with its colours and undo list`() {
        for (name in listOf("===", "a===", "Mood ===", "===\u00a0x", "---", "a---", "x ===")) {
            val p = palette(red, blue, 0xFF00AA00.toInt(), name = name)
            val d = PaletteDraft().started(p).selecting(1).removed().copy(page = BuilderPage.EDIT)
            assertEquals("[$name] has undo", 1, d.undo.size)
            assertEquals("[$name]", d, PaletteDraft.decode(d.encode()))
            val noUndo = PaletteDraft().started(p)
            assertEquals("[$name] no undo", noUndo, PaletteDraft.decode(noUndo.encode()))
            // The earlier palette on the undo list may carry such a name too.
            val named = d.renamed(name)
            assertEquals("[$name] renamed", named, PaletteDraft.decode(named.encode()))
        }
    }

    @Test
    fun `at most six ticked photos and twelve sources are read back`() {
        val line = "1;PHOTOS;0;0;${(1..20).joinToString(",")};${(1..20).joinToString(",")};;100;1;0\n"
        val d = PaletteDraft.decode(line)
        assertEquals(6, d.picks.size)
        assertEquals(12, d.sources.size)
    }

    @Test
    fun `random text never throws and never gives a page that needs a palette without one`() {
        val rnd = Random(42)
        val pieces = listOf("1", ";", "\n", "EDIT", "RECOLOUR", "START", "===", "fotoz-palette 1", "color #FF0000", "name x", "---", "99", "-3", ",", "x")
        repeat(2000) {
            val text = buildString { repeat(rnd.nextInt(30)) { append(pieces[rnd.nextInt(pieces.size)]) } }
            val d = PaletteDraft.decode(text)
            if (d.palette == null) assertFalse(text, d.page == BuilderPage.EDIT || d.page == BuilderPage.RECOLOUR)
            if (d.palette != null) assertTrue(d.selected in d.palette!!.colors.indices)
        }
    }

    @Test
    fun `the saved text stays small enough for the saved state`() {
        var d = PaletteDraft().started(Palette.of("A fairly long palette name here", List(12) { ColorMath.rgb(it * 20, 40, 90) })!!)
        repeat(15) { d = d.removed().added() }
        d = d.copy(sources = (1L..12L).toList(), picks = (1L..6L).toList())
        assertTrue("${d.encode().length} chars", d.encode().length < 8_000)
    }

    @Test
    fun `the saved asset is found by its row id however the volume is spelled`() {
        val a = PaletteTestKit.asset(42, "a.jpg")
        assertEquals(a, PaletteDraft.savedAsset(listOf(a), "content://media/external_primary/images/media/42"))
        assertEquals(a, PaletteDraft.savedAsset(listOf(a), "content://media/external/images/media/42"))
        assertNull(PaletteDraft.savedAsset(listOf(a), "content://media/external/images/media/43"))
        assertNull(PaletteDraft.savedAsset(listOf(a), ""))
    }
}
