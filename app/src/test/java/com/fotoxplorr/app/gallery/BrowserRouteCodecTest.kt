package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Process death only hands `rememberSaveable` back what [encodeBrowserRoute] wrote -- a route
 * that does not round-trip through this pair is a route the user silently loses on a killed
 * process, indistinguishable from just landing back on Root.
 */
class BrowserRouteCodecTest {

    @Test
    fun `root round-trips`() {
        assertEquals(BrowserRoute.Root, decodeBrowserRoute(encodeBrowserRoute(BrowserRoute.Root)))
    }

    @Test
    fun `a device album round-trips with both its key and its name`() {
        val route = BrowserRoute.DeviceAlbum(key = "DCIM/Camera", name = "Camera")
        assertEquals(route, decodeBrowserRoute(encodeBrowserRoute(route)))
    }

    @Test
    fun `a collection round-trips`() {
        val route = BrowserRoute.Collection(id = "abc-123", name = "Summer trip")
        assertEquals(route, decodeBrowserRoute(encodeBrowserRoute(route)))
    }

    @Test
    fun `a smart album round-trips through its enum name`() {
        val route = BrowserRoute.Smart(SmartAlbum.VIDEOS)
        assertEquals(route, decodeBrowserRoute(encodeBrowserRoute(route)))
    }

    @Test
    fun `a tag round-trips`() {
        val route = BrowserRoute.Tag("family")
        assertEquals(route, decodeBrowserRoute(encodeBrowserRoute(route)))
    }

    @Test
    fun `unrecognised or empty input decodes to Root rather than throwing`() {
        assertEquals(BrowserRoute.Root, decodeBrowserRoute(emptyList()))
        assertEquals(BrowserRoute.Root, decodeBrowserRoute(listOf("unknown-kind")))
        assertEquals(BrowserRoute.Root, decodeBrowserRoute(listOf("smart", "NOT_A_REAL_ALBUM")))
        assertEquals(BrowserRoute.Root, decodeBrowserRoute(listOf("album")))
    }
}
