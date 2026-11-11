package com.fotoxplorr.app.viewer

import dev.aarso.cellshell.SpatialMotion
import org.junit.Assert.assertEquals
import org.junit.Test

/** The band the viewer's flight leaves under the parked card is the shell's own, not a copy. */
class PlaceMorphShellBandTest {

    @Test
    fun `the shell band is the library's band`() {
        assertEquals(SpatialMotion.BAND_DP, PlaceMorph.SHELL_BAND_DP, 0f)
    }
}
