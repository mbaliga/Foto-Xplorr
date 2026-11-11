package com.fotoxplorr.app.viewer.sphere

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the 360 view is offered (only for a block [SphereMeta.parse] accepts), that the gyro listener
 * follows the host's ON_START / ON_STOP, and that the first look is re-clamped once the real aspect is known.
 */
class SphereGateTest {
    private fun props(vararg kv: Pair<String, String>): (String) -> String? = kv.toMap()::get

    @Test
    fun `an equirectangular block is offered`() {
        assertTrue(SphereMeta.accepts(props("ProjectionType" to "equirectangular")))
        assertTrue(SphereMeta.accepts(props("ProjectionType" to " Equirectangular ", "FullPanoWidthPixels" to "8192", "FullPanoHeightPixels" to "4096")))
    }

    @Test
    fun `a cylindrical panorama or a bare FullPano width is not offered`() {
        assertFalse(SphereMeta.accepts(props("ProjectionType" to "cylindrical")))
        assertFalse(SphereMeta.accepts(props("FullPanoWidthPixels" to "8192")))
        assertFalse(SphereMeta.accepts(props()))
    }

    @Test
    fun `an equirectangular block with impossible sizes is not offered`() {
        assertFalse(
            SphereMeta.accepts(
                props(
                    "ProjectionType" to "equirectangular",
                    "FullPanoWidthPixels" to "1000", "FullPanoHeightPixels" to "500",
                    "CroppedAreaImageWidthPixels" to "2000", "CroppedAreaImageHeightPixels" to "500",
                ),
            ),
        )
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `the gyro listener is unregistered on ON_STOP and registered again on ON_START`() {
        val owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        var registered = 0
        var unregistered = 0
        val gate = StartedGate(start = { registered++ }, stop = { unregistered++ })
        owner.registry.addObserver(gate)
        assertTrue(gate.running)
        assertEquals(1, registered)

        owner.registry.currentState = Lifecycle.State.CREATED // Home, or the lock key
        assertFalse(gate.running)
        assertEquals(1, unregistered)

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(gate.running)
        assertEquals(2, registered)

        // Leaving composition: removed and closed, once.
        owner.registry.removeObserver(gate)
        gate.close()
        gate.close()
        assertEquals(2, unregistered)
    }

    @Test
    fun `a gate added while the host is stopped does not register`() {
        val owner = Owner().also { it.registry.currentState = Lifecycle.State.CREATED }
        var registered = 0
        val gate = StartedGate(start = { registered++ }, stop = {})
        owner.registry.addObserver(gate)
        assertFalse(gate.running)
        assertEquals(0, registered)
    }

    @Test
    fun `the first look, clamped before layout, is pulled back in once the real aspect arrives`() {
        // A 120 degree wide partial panorama, opening near its right edge.
        val meta = SphereMeta(
            fullWidth = 3600, fullHeight = 1800, croppedWidth = 1200, croppedHeight = 600,
            croppedLeft = 1200, croppedTop = 600, initialHeading = 55f,
        )
        val beforeLayout = SphereLook.initial(meta, 0.5f)
        val landscape = 2f
        val reclamped = SphereLook.clamp(beforeLayout, meta, landscape)
        // At the real aspect the view's right edge would have been past the panorama's.
        val halfH = SphereLook.hFov(beforeLayout.fov, landscape) / 2f
        assertTrue(beforeLayout.yaw + halfH > meta.lonMax + 0.01f)
        assertNotEquals(beforeLayout.yaw, reclamped.yaw)
        assertTrue(reclamped.yaw + SphereLook.hFov(reclamped.fov, landscape) / 2f <= meta.lonMax + 1e-3f)
        assertEquals(SphereLook.initial(meta, landscape), reclamped)
    }
}
