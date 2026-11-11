package com.fotoxplorr.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The overwrite flow's visible half: a successful replace changes `dateModifiedSeconds`, and Coil
 * must treat that as a different image to cache -- otherwise the grid and the viewer keep showing
 * whatever they had cached for that Uri, silently, forever. See MediaImage's own doc.
 */
class MediaImageCacheKeyTest {

    @Test
    fun `the same uri and modified time always produce the same key`() {
        assertEquals(
            mediaImageCacheKey("content://media/1", 1_000L),
            mediaImageCacheKey("content://media/1", 1_000L),
        )
    }

    @Test
    fun `a changed modified time busts the cache key for the same uri`() {
        assertNotEquals(
            mediaImageCacheKey("content://media/1", 1_000L),
            mediaImageCacheKey("content://media/1", 2_000L),
        )
    }

    @Test
    fun `different uris never collide even at the same modified time`() {
        assertNotEquals(
            mediaImageCacheKey("content://media/1", 1_000L),
            mediaImageCacheKey("content://media/2", 1_000L),
        )
    }
}
