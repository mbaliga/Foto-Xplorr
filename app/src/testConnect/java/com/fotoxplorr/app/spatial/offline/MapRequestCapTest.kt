package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.feature.airemote.MapRequestCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The pure part of the request cap: how the app's name is added to the library's User-Agent. */
class MapRequestCapTest {

    @Test
    fun `the suffix follows the library's own identifier`() {
        assertEquals(
            "com.fotoxplorr.app.connect/0.4 (4) MapLibre Android/13.0.2 Fotoz",
            MapRequestCap.userAgentWith("com.fotoxplorr.app.connect/0.4 (4) MapLibre Android/13.0.2", "Fotoz"),
        )
    }

    @Test
    fun `without an identifier the suffix stands alone`() {
        assertEquals("Fotoz", MapRequestCap.userAgentWith(null, "Fotoz"))
        assertEquals("Fotoz", MapRequestCap.userAgentWith("  ", "Fotoz"))
    }

    @Test
    fun `a request that already carries the suffix is not given it twice`() {
        assertEquals("Lib/1 Fotoz", MapRequestCap.userAgentWith("Lib/1 Fotoz", "Fotoz"))
    }

    @Test
    fun `an empty suffix changes nothing and a suffix with odd characters stays plain ASCII`() {
        assertEquals("Lib/1", MapRequestCap.userAgentWith("Lib/1", ""))
        val cleaned = MapRequestCap.userAgentWith("Lib/1", "Fotoz\né")
        assertEquals("Lib/1 Fotoz", cleaned)
        assertFalse(cleaned.contains('\n'))
    }

    @Test
    fun `the app's own suffix is the one the provider asked for`() {
        assertEquals("Fotoz", MapLibreRequestCap.USER_AGENT_SUFFIX)
        assertEquals(4, OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS)
    }
}
