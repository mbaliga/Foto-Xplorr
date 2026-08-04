package com.fotoxplorr.app.privacy

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveIdCodecTest {
    @Test
    fun `round trip preserves ids`() {
        val ids = linkedSetOf(MediaId(7), MediaId(42), MediaId(9001))
        assertEquals(ids, SensitiveIdCodec.decode(SensitiveIdCodec.encode(ids)))
    }

    @Test
    fun `invalid stored values are ignored`() {
        assertEquals(
            setOf(MediaId(12)),
            SensitiveIdCodec.decode(setOf("12", "not-a-number", "")),
        )
    }
}
