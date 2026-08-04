package com.fotoxplorr.app.organize

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIdCodecTest {
    @Test
    fun `codec ignores invalid and negative ids`() {
        assertEquals(
            setOf(MediaId(7), MediaId(42)),
            decodeIds(setOf("7", "-1", "broken", "42")),
        )
    }

    @Test
    fun `codec produces stable numeric order`() {
        assertEquals(
            linkedSetOf("2", "10", "99"),
            encodeIds(setOf(MediaId(99), MediaId(2), MediaId(10))),
        )
    }

    @Test
    fun `codec round trip preserves valid ids`() {
        val ids = linkedSetOf(MediaId(0), MediaId(8), MediaId(999))
        assertEquals(ids, decodeIds(encodeIds(ids)))
    }
}
