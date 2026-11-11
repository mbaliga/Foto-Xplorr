package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** The classification only; what the platform answers on a device is not covered here. */
class ConnectionCheckTest {

    @Test
    fun `no active network is none, never metered`() {
        assertEquals(Connection.NONE, classify(hasActiveNetwork = false, reachesInternet = false, metered = true))
        assertEquals(Connection.NONE, classify(hasActiveNetwork = false, reachesInternet = true, metered = false))
    }

    @Test
    fun `a network that does not reach the internet is none`() {
        assertEquals(Connection.NONE, classify(hasActiveNetwork = true, reachesInternet = false, metered = false))
    }

    @Test
    fun `a network that reaches the internet is metered or not`() {
        assertEquals(Connection.METERED, classify(hasActiveNetwork = true, reachesInternet = true, metered = true))
        assertEquals(Connection.UNMETERED, classify(hasActiveNetwork = true, reachesInternet = true, metered = false))
    }
}
