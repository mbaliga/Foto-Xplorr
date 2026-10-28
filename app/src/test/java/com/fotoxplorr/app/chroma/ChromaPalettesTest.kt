package com.fotoxplorr.app.chroma

import com.fotoxplorr.app.palettebuilder.Palette
import com.fotoxplorr.app.palettebuilder.PaletteLibrary
import com.fotoxplorr.app.palettebuilder.PaletteStore
import com.fotoxplorr.app.palettebuilder.SavedPalettes
import com.fotoxplorr.app.palettebuilder.StoreSave
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The hand-off of a colour to the palette builder's saved list: the choices, the names, the three ways it can end. */
class ChromaPalettesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val red = ChromaTestKit.argb(200, 30, 30)
    private val blue = ChromaTestKit.argb(30, 30, 200)
    private val green = ChromaTestKit.argb(30, 200, 30)

    private fun palette(name: String, vararg colours: Int) = Palette.of(name, colours.toList())!!

    private fun reading(argb: Int, at: Long) = SavedReading(argb, ReadingKind.APPROXIMATE, at)

    private class FakeLibrary(
        var palettes: List<Palette> = emptyList(),
        var saveResult: ((Palette) -> StoreSave)? = null,
        var unreadable: Boolean = false,
    ) : PaletteLibrary {
        val saved = mutableListOf<Palette>()
        override suspend fun load() = SavedPalettes(palettes, unreadable = unreadable)
        override suspend fun save(palette: Palette): StoreSave {
            val r = saveResult?.invoke(palette) ?: StoreSave.Saved(replaced = palettes.any { it.name.equals(palette.name, true) })
            if (r is StoreSave.Saved) {
                saved += palette
                palettes = listOf(palette) + palettes.filterNot { it.name.equals(palette.name, true) }
            }
            return r
        }
        override suspend fun delete(name: String) = false
    }

    // ---- the choices ------------------------------------------------------------------------------------

    @Test
    fun `a new palette is named so that no saved palette is replaced`() {
        assertEquals("Chroma ID", ChromaPalettes.newName(emptyList()))
        assertEquals("Chroma ID 2", ChromaPalettes.newName(listOf(palette("chroma id", red, blue))))
        assertEquals(
            "Chroma ID 3",
            ChromaPalettes.newName(listOf(palette("Chroma ID", red, blue), palette("CHROMA ID 2", red, green))),
        )
        assertEquals("Chroma ID", ChromaPalettes.newName(listOf(palette("Chroma ID 2", red, blue))))
    }

    @Test
    fun `each saved palette is a choice, with the reason when it cannot take the colour`() {
        val twelve = Palette.of("Full", (0 until 12).map { ChromaTestKit.argb(it * 10, 0, 0) })!!
        val has = palette("Has", red, blue)
        val room = palette("Room", blue, green)
        val choices = ChromaPalettes.targets(red, listOf(twelve, has, room), partner = null)
        val adds = choices.filterIsInstance<PaletteTarget.AddTo>()
        assertEquals(listOf("Full", "Has", "Room"), adds.map { it.name })
        assertEquals(listOf(Unavailable.FULL, Unavailable.ALREADY_THERE, null), adds.map { it.reason })
        assertEquals(listOf(false, false, true), adds.map { it.available })
        assertEquals(12, adds[0].size)
        assertTrue(choices.last() is PaletteTarget.NewNeedsTwo)
    }

    @Test
    fun `a new palette is offered only with a second colour to give it`() {
        val partner = reading(blue, 5)
        val choices = ChromaPalettes.targets(red, emptyList(), partner)
        val new = choices.single() as PaletteTarget.NewPalette
        assertEquals("Chroma ID", new.name)
        assertEquals(partner, new.partner)
    }

    @Test
    fun `the partner is the latest other reading of another colour`() {
        val subject = reading(red, 10)
        val history = listOf(subject, reading(red, 9), reading(blue, 4), reading(green, 8))
        assertEquals(reading(green, 8), ChromaPalettes.partnerFor(subject, history))
        assertNull(ChromaPalettes.partnerFor(subject, listOf(subject, reading(red, 3))))
        assertNull(ChromaPalettes.partnerFor(subject, emptyList()))
    }

    // ---- saving -----------------------------------------------------------------------------------------

    @Test
    fun `adding to a palette keeps its name and appends the colour`() = runBlocking {
        val lib = FakeLibrary(listOf(palette("Sea", blue, green)))
        val target = ChromaPalettes.targets(red, lib.palettes, null).first()
        val r = ChromaPalettes.save(lib, red, target)
        assertEquals("Sea", (r as HandoffResult.Added).name)
        assertEquals(listOf(blue, green, red), lib.saved.single().argbs)
        assertEquals("Sea", lib.saved.single().name)
    }

    @Test
    fun `a colour already there is not added twice`() = runBlocking {
        val lib = FakeLibrary(listOf(palette("Sea", blue, red)))
        val r = ChromaPalettes.save(lib, red, PaletteTarget.AddTo("Sea", 2, null))
        assertEquals("Sea", (r as HandoffResult.AlreadyThere).name)
        assertTrue(lib.saved.isEmpty())
    }

    @Test
    fun `a full palette says so and writes nothing`() = runBlocking {
        val twelve = Palette.of("Full", (0 until 12).map { ChromaTestKit.argb(it * 10, 0, 0) })!!
        val lib = FakeLibrary(listOf(twelve))
        val r = ChromaPalettes.save(lib, red, PaletteTarget.AddTo("Full", 12, null))
        assertEquals("Full", (r as HandoffResult.Full).name)
        assertTrue(lib.saved.isEmpty())
    }

    @Test
    fun `a palette deleted since the choices were shown is a failure, not a new palette`() = runBlocking {
        val lib = FakeLibrary(emptyList())
        assertTrue(ChromaPalettes.save(lib, red, PaletteTarget.AddTo("Gone", 2, null)) is HandoffResult.Failed)
        assertTrue(lib.saved.isEmpty())
    }

    @Test
    fun `a new palette holds this colour then the partner, under a free name`() = runBlocking {
        val lib = FakeLibrary(listOf(palette("Chroma ID", blue, green)))
        val target = PaletteTarget.NewPalette("Chroma ID", reading(blue, 1))
        val r = ChromaPalettes.save(lib, red, target)
        assertEquals("Chroma ID 2", (r as HandoffResult.Created).name)
        assertEquals(listOf(red, blue), lib.saved.single().argbs)
        assertEquals(2, lib.palettes.size)
    }

    @Test
    fun `a full list and a failed write are told apart`() = runBlocking {
        val full = FakeLibrary(saveResult = { StoreSave.Full })
        assertTrue(ChromaPalettes.save(full, red, PaletteTarget.NewPalette("Chroma ID", reading(blue, 1))) is HandoffResult.ListFull)
        val failed = FakeLibrary(listOf(palette("Sea", blue, green)), saveResult = { StoreSave.Failed })
        assertTrue(ChromaPalettes.save(failed, red, PaletteTarget.AddTo("Sea", 2, null)) is HandoffResult.Failed)
    }

    @Test
    fun `an unreadable palette file is never written over`() = runBlocking {
        val lib = FakeLibrary(unreadable = true)
        assertTrue(ChromaPalettes.save(lib, red, PaletteTarget.NewPalette("Chroma ID", reading(blue, 1))) is HandoffResult.Failed)
        assertTrue(lib.saved.isEmpty())
    }

    @Test
    fun `the choice to start a palette with one colour cannot be saved`() = runBlocking {
        assertTrue(ChromaPalettes.save(FakeLibrary(), red, PaletteTarget.NewNeedsTwo) is HandoffResult.Failed)
    }

    @Test
    fun `through the builders real store a colour lands in a saved palette`() = runBlocking {
        val store = PaletteStore(File(temp.root, "palettes"))
        store.saveNow(palette("Sea", blue, green))
        val target = ChromaPalettes.targets(red, store.loadNow().palettes, null).first()
        assertTrue(ChromaPalettes.save(store, red, target) is HandoffResult.Added)
        assertEquals(listOf(blue, green, red), store.loadNow().palettes.single().argbs)
        val made = ChromaPalettes.save(store, red, PaletteTarget.NewPalette("Chroma ID", reading(blue, 1)))
        assertEquals("Chroma ID", (made as HandoffResult.Created).name)
        assertEquals(listOf("Chroma ID", "Sea"), store.loadNow().palettes.map { it.name })
    }
}
