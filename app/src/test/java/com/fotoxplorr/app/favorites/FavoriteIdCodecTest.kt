package com.fotoxplorr.app.favorites

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteIdCodecTest {
    @Test
    fun decodeIgnoresInvalidAndNegativeValues() {
        val decoded = FavoriteIdCodec.decode(
            setOf("7", "not-a-number", "-1", "42"),
        )

        assertEquals(setOf(MediaId(7), MediaId(42)), decoded)
    }

    @Test
    fun encodeIsStableAndSorted() {
        val encoded = FavoriteIdCodec.encode(
            linkedSetOf(MediaId(42), MediaId(7), MediaId(12)),
        )

        assertEquals(linkedSetOf("7", "12", "42"), encoded)
    }

    @Test
    fun roundTripPreservesValidIds() {
        val ids = linkedSetOf(MediaId(0), MediaId(1), MediaId(999))

        assertEquals(ids, FavoriteIdCodec.decode(FavoriteIdCodec.encode(ids)))
    }
}
