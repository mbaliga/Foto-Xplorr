package com.fotoxplorr.app.gallery

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "Preview window" setting ([PeekMode]): its default, its key, that it survives a restart, and
 * the pure rules the window is laid out by. Same shape as [GalleryPreferencesGridLongPressTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeekModeTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun preferences() = GalleryPreferences(context)

    private fun raw() = context.getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)

    @Test
    fun `the preview closes on release by default`() {
        assertEquals(PeekMode.CLOSE_ON_RELEASE, GalleryPreferencesState().peekMode)
        assertEquals(PeekMode.CLOSE_ON_RELEASE, preferences().observe().value.peekMode)
    }

    @Test
    fun `the choice is stored by name under its own key and survives a restart`() {
        PeekMode.entries.forEach { mode ->
            preferences().setPeekMode(mode)
            assertEquals(mode.name, raw().getString("peek_mode", null))
            assertEquals(mode, preferences().observe().value.peekMode)
        }
    }

    @Test
    fun `a name this build does not know falls back to closing on release`() {
        raw().edit().putString("peek_mode", "FLOATING").commit()
        assertEquals(PeekMode.CLOSE_ON_RELEASE, preferences().observe().value.peekMode)
    }

    @Test
    fun `changing the preview leaves what a hold does alone`() {
        preferences().setGridLongPress(GridLongPress.SHARE)
        preferences().setPeekMode(PeekMode.STAYS_OPEN)
        assertEquals(GridLongPress.SHARE, preferences().observe().value.gridLongPress)
    }

    @Test
    fun `the chips are short and distinct`() {
        assertEquals(listOf(PeekCopy.CLOSES_ON_RELEASE, PeekCopy.STAYS_OPEN), PeekMode.entries.map { it.label })
        PeekMode.entries.forEach { assertTrue(it.label, it.label.length <= 24) }
    }

    @Test
    fun `the window offers the selection bar's three actions in its order`() {
        assertEquals(
            listOf(PeekAction.EXPORT_ZIP, PeekAction.MOVE_TO_FOLDER, PeekAction.COPY_TO_FOLDER),
            peekActionSlots(movable = true, inTrash = false),
        )
    }

    @Test
    fun `a source file loses move and copy takes its slot`() {
        assertEquals(listOf(PeekAction.EXPORT_ZIP, PeekAction.COPY_TO_FOLDER), peekActionSlots(movable = false, inTrash = false))
    }

    @Test
    fun `the trash offers no actions`() {
        assertTrue(peekActionSlots(movable = true, inTrash = true).isEmpty())
        assertTrue(peekActionSlots(movable = false, inTrash = true).isEmpty())
    }

    @Test
    fun `the window is the reference's 392 dp on a 440 dp phone and capped on a tablet`() {
        assertEquals(392.dp, peekWindowSide(440.dp, 956.dp))
        assertEquals(312.dp, peekWindowSide(360.dp, 800.dp))
        assertEquals(PEEK_WINDOW_MAX, peekWindowSide(1280.dp, 800.dp))
        assertEquals(PEEK_WINDOW_MIN, peekWindowSide(100.dp, 100.dp))
    }
}
