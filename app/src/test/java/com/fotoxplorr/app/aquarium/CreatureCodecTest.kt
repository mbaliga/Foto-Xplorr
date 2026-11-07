package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CreatureCodecTest {

    private fun sample(id: String = UUID.randomUUID().toString()) = StoredCreature(
        id = id,
        createdAtMillis = 1_700_000_000_123L,
        widthPx = 240,
        heightPx = 150,
        headLeft = true,
    )

    @Test
    fun `a stored creature round trips through json`() {
        val c = sample()
        val back = CreatureCodec.decode(CreatureCodec.encode(c))
        assertEquals(c, back)
    }

    @Test
    fun `the encoded text carries a version and a uuid id`() {
        val text = CreatureCodec.encode(sample())
        assertTrue(text.contains("\"version\":${StoredCreature.CURRENT_VERSION}"))
        assertTrue(CreatureId.isValid(CreatureCodec.decode(text)!!.id))
    }

    @Test
    fun `generated ids are valid and distinct`() {
        val a = CreatureId.generate()
        val b = CreatureId.generate()
        assertTrue(CreatureId.isValid(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `unknown fields are ignored and a newer version is still read`() {
        val id = UUID.randomUUID().toString()
        val text = """{"version":99,"id":"$id","createdAt":5,"widthPx":100,"heightPx":50,"headLeft":false,
            "future":{"a":[1,2,3]},"mood":"happy"}"""
        val c = CreatureCodec.decode(text)
        assertNotNull(c)
        assertEquals(id, c!!.id)
        assertEquals(99, c.version)
        assertTrue(c.isFromNewerVersion)
        assertEquals(2f, c.aspect, 1e-6f)
        // Writing it back always uses the version this build understands.
        assertEquals(StoredCreature.CURRENT_VERSION, CreatureCodec.decode(CreatureCodec.encode(c))!!.version)
    }

    @Test
    fun `a missing or broken version is read as the first version`() {
        val id = UUID.randomUUID().toString()
        assertEquals(1, CreatureCodec.decode("""{"id":"$id"}""")!!.version)
        assertEquals(1, CreatureCodec.decode("""{"id":"$id","version":"two"}""")!!.version)
        assertEquals(1, CreatureCodec.decode("""{"id":"$id","version":-4}""")!!.version)
        assertEquals(1, CreatureCodec.decode("""{"id":"$id","version":0}""")!!.version)
    }

    @Test
    fun `missing optional fields take defaults`() {
        val id = UUID.randomUUID().toString()
        val c = CreatureCodec.decode("""{"version":1,"id":"$id"}""")!!
        assertEquals(0L, c.createdAtMillis)
        assertEquals(0, c.widthPx)
        assertFalse(c.headLeft)
        assertEquals(1f, c.aspect, 0f)
    }

    @Test
    fun `absurd numbers are clamped`() {
        val id = UUID.randomUUID().toString()
        val c = CreatureCodec.decode("""{"id":"$id","widthPx":1e12,"heightPx":-5,"createdAt":-9}""")!!
        assertEquals(StoredCreature.MAX_SIDE_PX, c.widthPx)
        assertEquals(0, c.heightPx)
        assertEquals(0L, c.createdAtMillis)
        assertTrue(c.aspect.isFinite() && c.aspect > 0f)
    }

    @Test
    fun `an id that could be a path is refused`() {
        for (bad in listOf(
            "", "../etc/passwd", "a/b", "..", "c:\\x", "not-a-uuid", " ",
            "123e4567-e89b-12d3-a456-42661417400", // one digit short
            "123e4567-e89b-12d3-a456-4266141740000", // one digit long
            "123e4567-e89b-12d3-a456-42661417400g", // not hex
            "123e4567-e89b-12d3-a456-426614174000/..",
        )) {
            assertFalse("'$bad'", CreatureId.isValid(bad))
            assertNull("'$bad'", CreatureCodec.decode("""{"id":"${bad.replace("\\", "\\\\")}"}"""))
        }
        assertTrue(CreatureId.isValid("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun `garbage is rejected without throwing`() {
        for (text in listOf("", "   ", "not json", "[]", "[1,2]", "null", "42", "\"x\"", "{", "{\"id\":", """{"version":1}""", """{"id":5}""")) {
            assertNull("'$text'", CreatureCodec.decode(text))
        }
    }

    @Test
    fun `a stored creature gives matching creature params`() {
        val p = sample().params()
        assertTrue(p.headLeft)
        assertEquals(240f / 150f, p.aspect, 1e-5f)
        assertEquals(CreatureParams.forAspect(240f / 150f, headLeft = true), p)
    }
}
