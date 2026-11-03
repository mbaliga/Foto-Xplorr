package com.fotoxplorr.app.basemap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The gesture layer keeps working when what it was composed with changes under it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class MapGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private var camera by mutableStateOf(MapCamera(8.0, 10.0, 50.0))
    private var ceiling by mutableStateOf(MapMath.MAX_LEVEL)
    private var viewport by mutableStateOf(MapViewport(400.0, 400.0))

    private fun host() {
        compose.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("map")
                    .mapGestures(
                        camera = { camera },
                        viewport = { viewport },
                        onCamera = { camera = it },
                        maxLevel = { ceiling },
                    ),
            )
        }
    }

    @Test fun `a pinch can pass level 8 once an imported file raises the ceiling`() {
        host()
        compose.onNodeWithTag("map").performTouchInput {
            pinch(Offset(80f, 100f), Offset(100f, 100f), Offset(120f, 100f), Offset(300f, 100f))
        }
        compose.waitForIdle()
        assertEquals(8.0, camera.level, 1e-9)
        ceiling = 12.0
        compose.waitForIdle()
        compose.onNodeWithTag("map").performTouchInput {
            pinch(Offset(80f, 100f), Offset(100f, 100f), Offset(120f, 100f), Offset(300f, 100f))
        }
        compose.waitForIdle()
        assertTrue("level ${camera.level}", camera.level > 8.0)
    }

    @Test fun `panning still moves the camera after the viewport changes`() {
        camera = MapCamera(3.0, 10.0, 50.0)
        host()
        viewport = MapViewport(300.0, 500.0)
        camera = MapCamera(3.0, 10.0, 50.0)
        compose.waitForIdle()
        val before = camera
        compose.onNodeWithTag("map").performTouchInput { swipe(Offset(150f, 100f), Offset(50f, 100f), durationMillis = 200) }
        compose.waitForIdle()
        assertTrue("camera did not move", camera.centerLon > before.centerLon)
    }
}
