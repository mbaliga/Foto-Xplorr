package com.fotoxplorr.app.recognition

import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentIdsTest {
    @Test
    fun `keeps the newest few ids newest first and drops the oldest`() {
        val ring = RecentIds(limit = 3)
        (1L..5L).forEach { ring.add(MediaId(it)) }
        assertEquals(listOf(5L, 4L, 3L), ring.newestFirst().map { it.value })
    }

    @Test
    fun `starts empty and the default bound is the stack size`() {
        assertEquals(emptyList<MediaId>(), RecentIds().newestFirst())
        val ring = RecentIds()
        (1L..20L).forEach { ring.add(MediaId(it)) }
        assertEquals(RecentIds.LIMIT, ring.newestFirst().size)
    }
}
