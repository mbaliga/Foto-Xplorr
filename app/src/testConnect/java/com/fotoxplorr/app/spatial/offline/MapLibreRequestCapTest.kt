package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the cap touches the map library in. The real library cannot load on a JVM, so this proves
 * the wrapper's ordering with fakes: the HTTP hook is never reached before the library is started,
 * because reaching it first fails the library's HTTP class for the rest of the process.
 */
class MapLibreRequestCapTest {

    private val log = ArrayList<String>()
    private var ready = false
    private var applyWorks = true

    private val cap = MapLibreRequestCap(
        libraryReady = { log += "ready?"; ready },
        apply = { max, agent -> log += "apply:$max:$agent"; applyWorks },
        undo = { log += "undo" },
    )

    @Test
    fun `with the map library not started install fails and never reaches the HTTP hook`() {
        assertFalse(cap.install())
        assertEquals(listOf("ready?"), log)
    }

    @Test
    fun `restore before the library started does not touch the HTTP hook either`() {
        cap.restore()
        assertFalse(log.contains("undo"))
    }

    @Test
    fun `once the library starts install applies the provider's limit and the app's name`() {
        ready = true
        assertTrue(cap.install())
        assertEquals(
            "apply:${OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS}:${MapLibreRequestCap.USER_AGENT_SUFFIX}",
            log.last(),
        )
        cap.restore()
        assertEquals("undo", log.last())
    }

    @Test
    fun `a library that failed to start is asked again on the next install`() {
        assertFalse(cap.install())
        ready = true
        assertTrue(cap.install())
        assertEquals(2, log.count { it == "ready?" })
    }

    @Test
    fun `a hook that refuses the client reports false`() {
        ready = true
        applyWorks = false
        assertFalse(cap.install())
    }
}
