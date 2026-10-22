package com.fotoxplorr.app.experimental

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.SpatialMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class ShellEdgesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the zone is no wider than the shell's room band and the margin is clear of it`() {
        assertTrue(ShellEdges.ZONE_DP <= SpatialMotion.BAND_DP)
        assertEquals(ShellEdges.ZONE_DP + ShellEdges.MARGIN_DP, ShellEdges.INSET_DP, 0f)
    }

    @Test
    fun `the zone is the shell's own private edge size, read from its source`() {
        // EDGE_DP is private to the library, so a change there is noticed here rather than by a
        // drawing board that suddenly loses its first stroke at the edge.
        val source = listOf(
            File("../shared-libraries/cell-shell/src/main/kotlin/dev/aarso/cellshell/SpatialShell.kt"),
            File("shared-libraries/cell-shell/src/main/kotlin/dev/aarso/cellshell/SpatialShell.kt"),
        ).firstOrNull { it.isFile }
        assumeTrue("the shell's source is not checked out here", source != null)
        val declared = Regex("""private const val EDGE_DP = (\d+(?:\.\d+)?)f""").find(source!!.readText())
        assumeTrue("EDGE_DP is no longer declared that way; update this test", declared != null)
        assertEquals(declared!!.groupValues[1].toFloat(), ShellEdges.ZONE_DP, 0f)
    }

    @Test
    fun `an area marked clear of the shell edges is the window less the inset on every side`() {
        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                Box(Modifier.fillMaxSize().clearOfShellEdges().testTag("area"))
            }
        }
        val bounds = compose.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val density = compose.density
        val inset = with(density) { ShellEdges.inset.toPx() }
        assertEquals(inset, bounds.left, 1f)
        assertEquals(inset, bounds.top, 1f)
        assertEquals(with(density) { 400.dp.toPx() } - inset, bounds.right, 1f)
        assertEquals(with(density) { 800.dp.toPx() } - inset, bounds.bottom, 1f)
    }
}
