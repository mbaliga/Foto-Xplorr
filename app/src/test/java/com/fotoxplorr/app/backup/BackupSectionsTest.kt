package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSectionsTest {
    private val recipe = parseJson("""{"crop":{"l":0.1,"t":0.2},"look":"warm"}""")!!

    private fun built() = BackupSections.build(
        metadata = parseJson("""{"kind":"fotoz-tags-and-collections","schema":2}""")!!,
        locks = listOf(BackupSections.Lock("path:dcim/private", "c2FsdA==", "aGFzaA==")),
        pins = listOf(BackupSections.Pin(42, 48.8566, 2.3522)),
        recipes = listOf(BackupSections.Recipe(7, 1_700_000_000, recipe)),
    )

    @Test
    fun `all four sections are written, by name, in a stable order`() {
        assertEquals(
            listOf(BackupSections.METADATA, BackupSections.LOCKS, BackupSections.PINS, BackupSections.RECIPES),
            built().map { it.name },
        )
    }

    @Test
    fun `locks, pins and recipes round trip`() {
        val sections = built().associate { it.name to it.data }
        val lock = BackupSections.locks(sections[BackupSections.LOCKS]).single()
        assertEquals("path:dcim/private", lock.folderKey)
        assertEquals("c2FsdA==", lock.saltBase64)
        assertEquals("aGFzaA==", lock.hashBase64)
        val pin = BackupSections.pins(sections[BackupSections.PINS]).single()
        assertEquals(42L, pin.mediaId)
        assertEquals(48.8566, pin.latitude, 1e-9)
        assertEquals(2.3522, pin.longitude, 1e-9)
        val back = BackupSections.recipes(sections[BackupSections.RECIPES]).single()
        assertEquals(7L, back.mediaId)
        assertEquals(1_700_000_000L, back.dateModifiedSeconds)
        assertEquals(recipe.stringify(), back.recipe.stringify())
    }

    @Test
    fun `a pin and a recipe carry who the photo is, and one without it reads back as none`() {
        val who = MediaIdentity("IMG_1.jpg", 1_234, 5_000, "DCIM/Camera/")
        val sections = BackupSections.build(
            metadata = parseJson("""{"kind":"x"}""")!!,
            locks = emptyList(),
            pins = listOf(BackupSections.Pin(1, 1.0, 2.0, who), BackupSections.Pin(2, 3.0, 4.0)),
            recipes = listOf(BackupSections.Recipe(1, 9, recipe, who), BackupSections.Recipe(2, 9, recipe)),
        ).associate { it.name to it.data }
        val pins = BackupSections.pins(sections[BackupSections.PINS])
        assertEquals(who, pins[0].identity)
        assertNull(pins[1].identity)
        val recipes = BackupSections.recipes(sections[BackupSections.RECIPES])
        assertEquals(who, recipes[0].identity)
        assertNull(recipes[1].identity)
        // A record from before the field has the same shape as one written without it.
        assertTrue("identity" !in (sections.getValue(BackupSections.PINS) as JsonValue.JsonArray).items[1].toString())
        // A damaged identity is no identity, and the record is still read.
        val damaged = parseJson("""[{"id":4,"lat":1,"lon":2,"identity":{"name":""}},{"id":5,"lat":1,"lon":2,"identity":"x"}]""")
        assertEquals(listOf(4L, 5L), BackupSections.pins(damaged).map { it.mediaId })
        assertTrue(BackupSections.pins(damaged).all { it.identity == null })
    }

    @Test
    fun `a section survives the manifest codec`() {
        val out = StringBuilder()
        val writer = ManifestWriter(out)
        writer.writeHeader(ManifestHeader(1, 5, "test", BackupFormat.MIN_ITERATIONS, BackupFormat.MIN_SEGMENT_SIZE))
        built().forEach(writer::writeSection)
        writer.finish()
        val manifest = ManifestReader.readAll(java.io.StringReader(out.toString()))
        assertEquals(1, BackupSections.locks(manifest.section(BackupSections.LOCKS)).size)
        assertEquals(1, BackupSections.pins(manifest.section(BackupSections.PINS)).size)
    }

    @Test
    fun `hostile or damaged section data yields nothing rather than a crash`() {
        assertTrue(BackupSections.locks(null).isEmpty())
        assertTrue(BackupSections.pins(JsonValue.JsonString("x")).isEmpty())
        val bad = parseJson("""[{"id":-1,"lat":1,"lon":1},{"id":3,"lat":91,"lon":0},{"id":4,"lat":0,"lon":181},{"id":"x","lat":0,"lon":0},{"id":5,"lat":10,"lon":20}]""")
        assertEquals(listOf(5L), BackupSections.pins(bad).map { it.mediaId })
        val badLocks = parseJson("""[{"key":"a"},{"key":"b","salt":"s","hash":"h"},7]""")
        assertEquals(listOf("b"), BackupSections.locks(badLocks).map { it.folderKey })
        val badRecipes = parseJson("""[{"id":1,"mod":2},{"id":1,"mod":2.5,"recipe":{}},{"id":3,"mod":4,"recipe":{"a":1}}]""")
        assertEquals(listOf(3L), BackupSections.recipes(badRecipes).map { it.mediaId })
    }

    @Test
    fun `a stored recipe file becomes a section entry and unreadable text does not`() {
        assertEquals(9L, BackupSections.recipeFromStored(9, 10, """{"a":1}""")!!.mediaId)
        assertNull(BackupSections.recipeFromStored(9, 10, "{not json"))
    }
}
