package com.fotoxplorr.app.shell

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.openwith.mediaStoreRowAsset
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's save and replace as the ViewModel holds them across the things that go wrong around
 * a write: a save still rendering when another photo is offered, a result that reports back late,
 * a write whose own code dies, and a copy that succeeds while the original is still at risk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditorWriteLifecycleTest {

    private fun photo(id: Long): MediaAsset = mediaStoreRowAsset(
        rowId = id,
        contentUri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
        isTrashed = false,
    )

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun editorOn(asset: MediaAsset) = AppStateViewModel(SavedStateHandle()).also { it.setEditingAsset(asset) }

    // ---- a save that is still rendering ----

    @Test
    fun `another photo is refused while a save is still rendering and the first editor stays`() {
        val a = photo(1)
        val b = photo(2)
        val vm = editorOn(a)

        vm.saveInProgress = true

        assertTrue(vm.editorCloseBlocked)
        assertFalse(vm.setEditingAsset(b))
        assertSame(a, vm.editingAsset)
        assertFalse(vm.closeEditor())
    }

    @Test
    fun `the same photo is accepted while a save is rendering and everything opens again once it ends`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.saveInProgress = true

        assertTrue(vm.setEditingAsset(a))

        vm.saveInProgress = false
        assertFalse(vm.editorCloseBlocked)
        assertTrue(vm.setEditingAsset(photo(2)))
    }

    @Test
    fun `a replace request for a photo the editor has left is refused so the caller can say so`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.setEditingAsset(photo(2))

        assertFalse("beginOverwrite reports the dropped edit", vm.beginOverwrite(a, bitmap()))
        assertNull(vm.pendingOverwriteAsset)
    }

    @Test
    fun `a late copy result for photo A does not close photo B's editor`() {
        val a = photo(1)
        val b = photo(2)
        val vm = editorOn(a)
        vm.setEditingAsset(b)

        assertFalse(vm.closeEditorFor(a.id))

        assertSame(b, vm.editingAsset)
    }

    @Test
    fun `a copy result for the open photo closes its editor`() {
        val a = photo(1)
        val vm = editorOn(a)

        assertTrue(vm.closeEditorFor(a.id))

        assertNull(vm.editingAsset)
    }

    // ---- the write runs on the ViewModel's scope ----

    @Test
    fun `a write whose own code dies is recorded as a failure with the original at risk`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        vm.launchWrite { error("the process-side code blew up") }

        assertTrue(vm.overwriteDeclined)
        assertTrue(vm.overwriteOriginalAtRisk)
        assertEquals(OVERWRITE_INTERRUPTED_MESSAGE, vm.overwriteFailureMessage)
        assertFalse(vm.overwriteInFlight)
        assertTrue("only Save a copy is left", vm.editorCloseBlocked)
        assertSame(a, vm.editingAsset)
    }

    @Test
    fun `a copy whose own code dies releases the lock and keeps the banner`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(OverwriteFailure("The original may be damaged.", originalAtRisk = true))
        vm.beginCopyOfDeclinedOverwrite()

        vm.launchWrite { error("boom") }

        assertFalse(vm.overwriteCopySaving)
        assertTrue(vm.overwriteDeclined && vm.overwriteOriginalAtRisk)
        assertEquals("The original may be damaged.", vm.overwriteFailureMessage)
    }

    @Test
    fun `a write that records its outcome is left alone`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        vm.launchWrite { vm.finishOverwriteSucceeded() }

        assertNull(vm.editingAsset)
        assertNull(vm.pendingOverwriteAsset)
        assertNull(vm.overwriteFailureMessage)
    }

    @Test
    fun `a cancelled write is not mistaken for an interrupted one`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())

        vm.launchWrite { throw CancellationException("cleared") }

        assertTrue("state is untouched; the ViewModel is going away", vm.overwriteInFlight)
    }

    // ---- a copy after a replace that left the original at risk ----

    @Test
    fun `a copy saved while the original is at risk says so`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(OverwriteFailure("The original may be damaged.", originalAtRisk = true))
        vm.beginCopyOfDeclinedOverwrite()

        assertTrue(vm.finishCopyOfDeclinedOverwrite(saved = true))

        assertNull(vm.editingAsset)
    }

    @Test
    fun `a copy saved after a replace that left the original safe says nothing extra`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(OverwriteFailure("Android did not allow replacing the original photo.", originalAtRisk = false))
        vm.beginCopyOfDeclinedOverwrite()

        assertFalse(vm.finishCopyOfDeclinedOverwrite(saved = true))
    }

    @Test
    fun `a copy that failed says nothing and keeps the risk`() {
        val a = photo(1)
        val vm = editorOn(a)
        vm.beginOverwrite(a, bitmap())
        vm.recordOverwriteFailure(OverwriteFailure("The original may be damaged.", originalAtRisk = true))
        vm.beginCopyOfDeclinedOverwrite()

        assertFalse(vm.finishCopyOfDeclinedOverwrite(saved = false))

        assertTrue(vm.overwriteOriginalAtRisk)
    }
}
